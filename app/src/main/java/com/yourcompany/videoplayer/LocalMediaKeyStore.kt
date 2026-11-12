package com.yourcompany.videoplayer

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object LocalMediaKeyStore {
    private const val LEGACY_KEY_ALIAS = "local_media_container_master_v1"
    private const val DEVICE_KEY_ALIAS = "local_media_recovery_storage_v1"
    private const val RECOVERY_KEY_FILE = "media_recovery_key.bin"
    private const val VERSION = 1
    private const val KEY_SIZE = 32
    private const val NONCE_SIZE = 12
    private const val TAG_SIZE = 16
    private const val HEADER_SIZE = 8 + 4
    private const val ENCRYPTED_KEY_FILE_SIZE = HEADER_SIZE + NONCE_SIZE + KEY_SIZE + TAG_SIZE
    private val localFileMagic = "MPVLOC01".toByteArray(Charsets.US_ASCII)

    @Synchronized
    fun getOrCreateWrappingKey(context: Context): SecretKey {
        loadRecoverableKey(context)?.let { return it }

        val keyBytes = ByteArray(KEY_SIZE).also(java.security.SecureRandom()::nextBytes)
        try {
            storeRecoverableKey(context, keyBytes)
            return SecretKeySpec(keyBytes, "AES")
        } finally {
            keyBytes.fill(0)
        }
    }

    @Synchronized
    fun exportRecoveryKey(context: Context): ByteArray {
        val keyBytes = getOrCreateWrappingKey(context).encoded
            ?: throw GeneralSecurityException("The recovery key is not exportable.")
        if (keyBytes.size != KEY_SIZE) {
            keyBytes.fill(0)
            throw GeneralSecurityException("The recovery key has an invalid size.")
        }
        return keyBytes
    }

    @Synchronized
    fun importRecoveryKey(context: Context, keyBytes: ByteArray) {
        if (keyBytes.size != KEY_SIZE) {
            throw IllegalArgumentException("Invalid recovery key size.")
        }

        val existingKey = try {
            loadRecoverableKey(context)
        } catch (_: IOException) {
            null
        }
        if (existingKey != null) {
            val existingBytes = existingKey.encoded
                ?: throw GeneralSecurityException("The installed recovery key is not exportable.")
            try {
                if (MessageDigest.isEqual(existingBytes, keyBytes)) return
                throw IOException(
                    "A different recovery key is already installed. Import this backup before encrypting new videos."
                )
            } finally {
                existingBytes.fill(0)
            }
        }

        storeRecoverableKey(context, keyBytes)
    }

    @Synchronized
    fun openEncryptedMedia(context: Context, file: File): EncryptedMediaContainer.Reader {
        var recoveryError: IOException? = null
        val recoveryKey = try {
            loadRecoverableKey(context)
        } catch (error: IOException) {
            recoveryError = error
            null
        }

        if (recoveryKey != null) {
            try {
                return EncryptedMediaContainer.open(file, recoveryKey)
            } catch (error: IOException) {
                recoveryError = error
            }
        }

        val legacyKey = getLegacyWrappingKeyOrNull()
        if (legacyKey != null) {
            try {
                return EncryptedMediaContainer.open(file, legacyKey)
            } catch (error: IOException) {
                recoveryError?.addSuppressed(error)
                throw recoveryError ?: error
            }
        }

        throw recoveryError
            ?: IOException("No recovery key is available. Import a recovery key before opening this video.")
    }

    private fun loadRecoverableKey(context: Context): SecretKey? {
        val file = File(context.filesDir, RECOVERY_KEY_FILE)
        if (!file.exists()) return null
        if (file.length() != ENCRYPTED_KEY_FILE_SIZE.toLong()) {
            throw IOException("The locally stored recovery key is invalid.")
        }

        val encoded = ByteArray(ENCRYPTED_KEY_FILE_SIZE)
        try {
            FileInputStream(file).use { input ->
                DataInputStream(input).readFully(encoded)
            }
            val header = encoded.copyOfRange(0, HEADER_SIZE)
            val fields = DataInputStream(ByteArrayInputStream(header))
            val magic = ByteArray(localFileMagic.size).also(fields::readFully)
            if (!magic.contentEquals(localFileMagic) || fields.readInt() != VERSION) {
                throw IOException("The locally stored recovery key has an unsupported format.")
            }

            val nonce = encoded.copyOfRange(HEADER_SIZE, HEADER_SIZE + NONCE_SIZE)
            val ciphertext = encoded.copyOfRange(HEADER_SIZE + NONCE_SIZE, encoded.size)
            val clearKey = try {
                crypt(
                    Cipher.DECRYPT_MODE,
                    getOrCreateAndroidKey(DEVICE_KEY_ALIAS),
                    nonce,
                    header,
                    ciphertext
                )
            } catch (error: GeneralSecurityException) {
                throw IOException("The locally stored recovery key cannot be unlocked.", error)
            } finally {
                ciphertext.fill(0)
            }

            try {
                if (clearKey.size != KEY_SIZE) {
                    throw IOException("The locally stored recovery key has an invalid size.")
                }
                return SecretKeySpec(clearKey, "AES")
            } finally {
                clearKey.fill(0)
                header.fill(0)
                nonce.fill(0)
            }
        } finally {
            encoded.fill(0)
        }
    }

    private fun storeRecoverableKey(context: Context, keyBytes: ByteArray) {
        val header = ByteBuffer.allocate(HEADER_SIZE)
            .put(localFileMagic)
            .putInt(VERSION)
            .array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateAndroidKey(DEVICE_KEY_ALIAS))
        val nonce = cipher.iv
            ?: throw GeneralSecurityException("The device key store did not provide an encryption nonce.")
        if (nonce.size != NONCE_SIZE) {
            throw GeneralSecurityException("The device key store returned an unsupported nonce.")
        }
        cipher.updateAAD(header)
        val ciphertext = cipher.doFinal(keyBytes)
        val encoded = ByteArrayOutputStream(ENCRYPTED_KEY_FILE_SIZE).use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(header)
                output.write(nonce)
                output.write(ciphertext)
            }
            bytes.toByteArray()
        }

        val destination = File(context.filesDir, RECOVERY_KEY_FILE)
        val temporary = File(context.filesDir, "$RECOVERY_KEY_FILE.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(encoded)
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
            encoded.fill(0)
            ciphertext.fill(0)
            header.fill(0)
            nonce.fill(0)
        }
    }

    private fun getLegacyWrappingKeyOrNull(): SecretKey? {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!keyStore.containsAlias(LEGACY_KEY_ALIAS)) return null
        return keyStore.getKey(LEGACY_KEY_ALIAS, null) as? SecretKey
            ?: throw GeneralSecurityException("The legacy media key has an unexpected type.")
    }

    private fun getOrCreateAndroidKey(alias: String): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keyStore.containsAlias(alias)) {
            return keyStore.getKey(alias, null) as? SecretKey
                ?: throw GeneralSecurityException("The local media key has an unexpected type.")
        }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generateKey()
        }
    }

    private fun crypt(
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
}
