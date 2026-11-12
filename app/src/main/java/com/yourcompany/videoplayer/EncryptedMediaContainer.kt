package com.yourcompany.videoplayer

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

internal object EncryptedMediaContainer {
    const val FILE_EXTENSION = "mpv"

    private const val VERSION = 1
    private const val CHUNK_SIZE = 1024 * 1024
    private const val GCM_NONCE_SIZE = 12
    private const val FILE_NONCE_PREFIX_SIZE = 8
    private const val TAG_SIZE = 16
    private const val KEY_SIZE = 32
    private const val HEADER_SIZE = 8 + 4 + 4 + 8 + FILE_NONCE_PREFIX_SIZE + GCM_NONCE_SIZE + 4
    private const val MAX_EXTENSION_SIZE = 16
    private const val MAX_WRAPPED_KEY_SIZE = KEY_SIZE + 2 + MAX_EXTENSION_SIZE + TAG_SIZE
    private val magic = "MVPBLK01".toByteArray(StandardCharsets.US_ASCII)
    private val secureRandom = SecureRandom()
    private val supportedExtensions = setOf(
        "mp4", "m4v", "mov", "3gp", "3g2",
        "mkv", "webm", "ts", "mts", "m2ts",
        "flv", "avi", "wmv", "asf", "mpg", "mpeg", "vob"
    )

    fun isContainer(file: File): Boolean {
        if (!file.isFile || file.length() < HEADER_SIZE + TAG_SIZE) return false
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val fileMagic = ByteArray(magic.size)
                raf.readFully(fileMagic)
                fileMagic.contentEquals(magic)
            }
        } catch (_: IOException) {
            false
        }
    }

    fun encrypt(
        inputFile: File,
        outputFile: File,
        originalExtension: String,
        wrappingKey: SecretKey,
        onProgress: (Int) -> Unit = {}
    ) {
        require(inputFile.isFile) { "Input file does not exist." }
        require(inputFile.length() > 0) { "Input video is empty." }
        require(inputFile.canonicalFile != outputFile.canonicalFile) {
            "The encrypted output must differ from the source file."
        }
        require(!isContainer(inputFile)) { "The input is already encrypted." }
        val plaintextSize = inputFile.length()

        val extension = originalExtension.trim().lowercase()
        val extensionBytes = extension.toByteArray(StandardCharsets.US_ASCII)
        require(
            extension in supportedExtensions &&
            extensionBytes.isNotEmpty() &&
                extensionBytes.size <= MAX_EXTENSION_SIZE &&
                extensionBytes.all { it.toInt() in 'a'.code..'z'.code || it.toInt() in '0'.code..'9'.code }
        ) { "Unsupported source file extension." }

        val dataKeyBytes = ByteArray(KEY_SIZE).also(secureRandom::nextBytes)
        try {
            val dataKey = SecretKeySpec(dataKeyBytes, "AES")
            val fileNoncePrefix = ByteArray(FILE_NONCE_PREFIX_SIZE).also(secureRandom::nextBytes)
            val wrappedKeySize = KEY_SIZE + 2 + extensionBytes.size + TAG_SIZE
            val wrappingCipher = Cipher.getInstance("AES/GCM/NoPadding")
            wrappingCipher.init(Cipher.ENCRYPT_MODE, wrappingKey)
            val wrapNonce = wrappingCipher.iv
                ?: throw GeneralSecurityException("The key store did not provide an encryption nonce.")
            if (wrapNonce.size != GCM_NONCE_SIZE) {
                throw GeneralSecurityException("The key store returned an unsupported encryption nonce.")
            }
            val header = encodeHeader(plaintextSize, fileNoncePrefix, wrapNonce, wrappedKeySize)
            val keyMetadata = encodeKeyMetadata(dataKeyBytes, extensionBytes)
            val wrappedKey = try {
                wrappingCipher.updateAAD(header)
                wrappingCipher.doFinal(keyMetadata)
            } finally {
                keyMetadata.fill(0)
            }

            val parent = outputFile.absoluteFile.parentFile
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw IOException("Unable to create the output directory.")
            }

            try {
                DataOutputStream(BufferedOutputStream(FileOutputStream(outputFile))).use { output ->
                    output.write(header)
                    output.write(wrappedKey)

                    BufferedInputStream(FileInputStream(inputFile)).use { input ->
                        val buffer = ByteArray(CHUNK_SIZE)
                        var totalRead = 0L
                        var chunkIndex = 0L
                        try {
                            while (totalRead < plaintextSize) {
                                val chunkLength = readChunk(
                                    input,
                                    buffer,
                                    min(CHUNK_SIZE.toLong(), plaintextSize - totalRead).toInt()
                                )
                                if (chunkLength <= 0) {
                                    throw EOFException("Input video changed while it was being encrypted.")
                                }
                                val plaintextChunk = buffer.copyOf(chunkLength)
                                val encrypted = try {
                                    cryptGcm(
                                        Cipher.ENCRYPT_MODE,
                                        dataKey,
                                        chunkNonce(fileNoncePrefix, chunkIndex),
                                        chunkAad(header, chunkIndex, chunkLength),
                                        plaintextChunk
                                    )
                                } finally {
                                    plaintextChunk.fill(0)
                                    buffer.fill(0)
                                }
                                output.write(encrypted)
                                encrypted.fill(0)
                                totalRead += chunkLength
                                chunkIndex++
                                onProgress((totalRead * 100 / plaintextSize).toInt())
                            }
                            if (input.read() != -1) {
                                throw IOException("Input video changed while it was being encrypted.")
                            }
                        } finally {
                            buffer.fill(0)
                        }
                    }
                }
                val expectedLength = containerLength(plaintextSize, wrappedKey.size)
                check(outputFile.length() == expectedLength) {
                    "The encrypted media container was written with an unexpected size."
                }
            } catch (error: Exception) {
                outputFile.delete()
                throw error
            }
        } finally {
            dataKeyBytes.fill(0)
        }
    }

    fun open(file: File, wrappingKey: SecretKey): Reader = Reader(file, wrappingKey)

    internal class Reader(file: File, wrappingKey: SecretKey) : AutoCloseable {
        private val raf = RandomAccessFile(file, "r")
        private val header: ByteArray
        private val fileNoncePrefix: ByteArray
        private val dataStart: Long
        private val dataKey: SecretKey
        val size: Long
        val originalExtension: String

        init {
            try {
                if (!file.isFile || file.length() < HEADER_SIZE + TAG_SIZE) {
                    throw IOException("Not an encrypted media container.")
                }
                header = ByteArray(HEADER_SIZE)
                raf.readFully(header)
                val fields = DataInputStream(ByteArrayInputStream(header))
                val fileMagic = ByteArray(magic.size)
                fields.readFully(fileMagic)
                if (!fileMagic.contentEquals(magic)) throw IOException("Not an encrypted media container.")
                if (fields.readInt() != VERSION) throw IOException("Unsupported encrypted media container version.")
                val chunkSize = fields.readInt()
                if (chunkSize != CHUNK_SIZE) throw IOException("Unsupported encrypted media chunk size.")
                size = fields.readLong()
                if (size <= 0) throw IOException("Invalid encrypted media size.")
                fileNoncePrefix = ByteArray(FILE_NONCE_PREFIX_SIZE).also(fields::readFully)
                val wrapNonce = ByteArray(GCM_NONCE_SIZE).also(fields::readFully)
                val wrappedSize = fields.readInt()
                if (wrappedSize < KEY_SIZE + 2 + 1 + TAG_SIZE || wrappedSize > MAX_WRAPPED_KEY_SIZE) {
                    throw IOException("Invalid encrypted media key metadata size.")
                }
                val wrappedKey = ByteArray(wrappedSize).also(raf::readFully)
                dataStart = raf.filePointer
                if (containerLength(size, wrappedSize) != file.length()) {
                    throw IOException("Encrypted media container length does not match its header.")
                }

                val keyMetadata = try {
                    cryptGcm(Cipher.DECRYPT_MODE, wrappingKey, wrapNonce, header, wrappedKey)
                } catch (error: GeneralSecurityException) {
                    throw IOException("Encrypted media key authentication failed.", error)
                }
                try {
                    val metadataInput = DataInputStream(ByteArrayInputStream(keyMetadata))
                    val rawKey = ByteArray(KEY_SIZE)
                    try {
                        metadataInput.readFully(rawKey)
                        val extensionSize = metadataInput.readUnsignedShort()
                        if (extensionSize !in 1..MAX_EXTENSION_SIZE || metadataInput.available() != extensionSize) {
                            throw IOException("Invalid encrypted media source metadata.")
                        }
                        val extensionBytes = ByteArray(extensionSize)
                        metadataInput.readFully(extensionBytes)
                        originalExtension = String(extensionBytes, StandardCharsets.US_ASCII)
                        if (originalExtension.any { it !in 'a'..'z' && it !in '0'..'9' }) {
                            throw IOException("Invalid encrypted media source extension.")
                        }
                        dataKey = SecretKeySpec(rawKey, "AES")
                    } finally {
                        rawKey.fill(0)
                    }
                } finally {
                    keyMetadata.fill(0)
                }
            } catch (error: Exception) {
                raf.close()
                throw error
            }
        }

        @Synchronized
        fun readAt(offset: Long, requestedSize: Int, destination: ByteArray): Int {
            require(offset >= 0 && requestedSize >= 0 && requestedSize <= destination.size)
            if (offset >= size) return -1
            val readSize = min(requestedSize.toLong(), size - offset).toInt()
            if (readSize == 0) return 0

            var position = offset
            var destinationOffset = 0
            val end = offset + readSize
            while (position < end) {
                val chunkIndex = position / CHUNK_SIZE
                val chunkStart = chunkIndex * CHUNK_SIZE
                val chunkLength = min(CHUNK_SIZE.toLong(), size - chunkStart).toInt()
                val ciphertext = ByteArray(chunkLength + TAG_SIZE)
                raf.seek(dataStart + chunkIndex * (CHUNK_SIZE.toLong() + TAG_SIZE))
                raf.readFully(ciphertext)

                val plaintext = try {
                    cryptGcm(
                        Cipher.DECRYPT_MODE,
                        dataKey,
                        chunkNonce(fileNoncePrefix, chunkIndex),
                        chunkAad(header, chunkIndex, chunkLength),
                        ciphertext
                    )
                } catch (error: GeneralSecurityException) {
                    throw IOException("Encrypted media chunk authentication failed.", error)
                } finally {
                    ciphertext.fill(0)
                }

                try {
                    val chunkOffset = (position - chunkStart).toInt()
                    val copyLength = min((end - position).toInt(), chunkLength - chunkOffset)
                    plaintext.copyInto(destination, destinationOffset, chunkOffset, chunkOffset + copyLength)
                    position += copyLength
                    destinationOffset += copyLength
                } finally {
                    plaintext.fill(0)
                }
            }
            return readSize
        }

        override fun close() = raf.close()
    }

    private fun encodeHeader(
        plaintextSize: Long,
        fileNoncePrefix: ByteArray,
        wrapNonce: ByteArray,
        wrappedKeySize: Int
    ): ByteArray = ByteArrayOutputStream(HEADER_SIZE).use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.write(magic)
            output.writeInt(VERSION)
            output.writeInt(CHUNK_SIZE)
            output.writeLong(plaintextSize)
            output.write(fileNoncePrefix)
            output.write(wrapNonce)
            output.writeInt(wrappedKeySize)
        }
        bytes.toByteArray()
    }

    private fun encodeKeyMetadata(key: ByteArray, extension: ByteArray): ByteArray =
        ByteArrayOutputStream(KEY_SIZE + 2 + extension.size).use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(key)
                output.writeShort(extension.size)
                output.write(extension)
            }
            bytes.toByteArray()
        }

    private fun chunkNonce(fileNoncePrefix: ByteArray, chunkIndex: Long): ByteArray {
        if (chunkIndex > 0xffffffffL) throw IOException("Encrypted media contains too many chunks.")
        return ByteBuffer.allocate(GCM_NONCE_SIZE)
            .put(fileNoncePrefix)
            .putInt(chunkIndex.toInt())
            .array()
    }

    private fun chunkAad(header: ByteArray, chunkIndex: Long, chunkLength: Int): ByteArray =
        ByteBuffer.allocate(header.size + 8)
            .put(header)
            .putInt(chunkIndex.toInt())
            .putInt(chunkLength)
            .array()

    private fun cryptGcm(
        mode: Int,
        key: SecretKey,
        nonce: ByteArray,
        aad: ByteArray,
        input: ByteArray
    ): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(mode, key, GCMParameterSpec(TAG_SIZE * 8, nonce))
        updateAAD(aad)
        doFinal(input)
    }

    private fun readChunk(input: BufferedInputStream, buffer: ByteArray, maximum: Int): Int {
        var total = 0
        while (total < maximum) {
            val count = input.read(buffer, total, maximum - total)
            if (count < 0) break
            total += count
        }
        return total
    }

    private fun containerLength(plaintextSize: Long, wrappedKeySize: Int): Long {
        val chunkCount = (plaintextSize - 1) / CHUNK_SIZE + 1
        if (chunkCount > 0xffffffffL) throw IOException("Encrypted media is too large.")
        val overhead = HEADER_SIZE.toLong() + wrappedKeySize + chunkCount * TAG_SIZE
        if (plaintextSize > Long.MAX_VALUE - overhead) throw IOException("Encrypted media is too large.")
        return plaintextSize + overhead
    }
}
