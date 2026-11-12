package com.yourcompany.videoplayer

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

internal object RecoveryKeyBackup {
    const val FILE_EXTENSION = "mpvkey"
    const val MIN_PASSWORD_LENGTH = 12
    const val MAX_FILE_SIZE = 8 + 4 + 4 + 16 + 12 + 32 + 16

    private const val VERSION = 1
    private const val KEY_SIZE = 32
    private const val SALT_SIZE = 16
    private const val NONCE_SIZE = 12
    private const val TAG_SIZE = 16
    private const val KDF_ITERATIONS = 600_000
    private const val MIN_KDF_ITERATIONS = 100_000
    private const val MAX_KDF_ITERATIONS = 1_000_000
    private const val HEADER_SIZE = 8 + 4 + 4 + SALT_SIZE + NONCE_SIZE
    private val magic = "MPVKEY01".toByteArray(Charsets.US_ASCII)
    private val secureRandom = SecureRandom()

    fun encrypt(recoveryKey: ByteArray, password: CharArray): ByteArray {
        require(recoveryKey.size == KEY_SIZE) { "Invalid recovery key size." }
        require(password.size >= MIN_PASSWORD_LENGTH) {
            "Use a recovery password with at least $MIN_PASSWORD_LENGTH characters."
        }

        val salt = ByteArray(SALT_SIZE).also(secureRandom::nextBytes)
        val nonce = ByteArray(NONCE_SIZE).also(secureRandom::nextBytes)
        val header = ByteBuffer.allocate(HEADER_SIZE)
            .put(magic)
            .putInt(VERSION)
            .putInt(KDF_ITERATIONS)
            .put(salt)
            .put(nonce)
            .array()
        val derivedKeyBytes = deriveKey(password, salt, KDF_ITERATIONS)
        val ciphertext = try {
            crypt(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(derivedKeyBytes, "AES"),
                nonce,
                header,
                recoveryKey
            )
        } finally {
            derivedKeyBytes.fill(0)
        }

        return try {
            ByteArrayOutputStream(MAX_FILE_SIZE).use { bytes ->
                DataOutputStream(bytes).use { output ->
                    output.write(header)
                    output.write(ciphertext)
                }
                bytes.toByteArray()
            }
        } finally {
            salt.fill(0)
            nonce.fill(0)
            header.fill(0)
            ciphertext.fill(0)
        }
    }

    @Throws(IOException::class, GeneralSecurityException::class)
    fun decrypt(encoded: ByteArray, password: CharArray): ByteArray {
        if (encoded.size != MAX_FILE_SIZE) throw IOException("Invalid recovery key file size.")
        if (password.isEmpty()) throw IOException("A recovery password is required.")

        val header = encoded.copyOfRange(0, HEADER_SIZE)
        val fields = DataInputStream(header.inputStream())
        val fileMagic = ByteArray(magic.size).also(fields::readFully)
        if (!fileMagic.contentEquals(magic)) throw IOException("This is not a recovery key file.")
        if (fields.readInt() != VERSION) throw IOException("Unsupported recovery key file version.")
        val iterations = fields.readInt()
        if (iterations !in MIN_KDF_ITERATIONS..MAX_KDF_ITERATIONS) {
            throw IOException("Unsupported recovery key password settings.")
        }

        val salt = ByteArray(SALT_SIZE).also(fields::readFully)
        val nonce = ByteArray(NONCE_SIZE).also(fields::readFully)
        val ciphertext = encoded.copyOfRange(HEADER_SIZE, encoded.size)
        val derivedKeyBytes = deriveKey(password, salt, iterations)
        return try {
            val clearKey = try {
                crypt(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(derivedKeyBytes, "AES"),
                    nonce,
                    header,
                    ciphertext
                )
            } catch (error: GeneralSecurityException) {
                throw IOException("The recovery password is incorrect or the file is damaged.", error)
            }
            if (clearKey.size != KEY_SIZE) {
                clearKey.fill(0)
                throw IOException("Invalid recovery key data.")
            }
            clearKey
        } finally {
            header.fill(0)
            salt.fill(0)
            nonce.fill(0)
            ciphertext.fill(0)
            derivedKeyBytes.fill(0)
        }
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, KEY_SIZE * 8)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec)
                .encoded
        } finally {
            spec.clearPassword()
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
