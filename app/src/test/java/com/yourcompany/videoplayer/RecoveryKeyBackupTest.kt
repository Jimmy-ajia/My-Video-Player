package com.yourcompany.videoplayer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.io.IOException
import javax.crypto.spec.SecretKeySpec

class RecoveryKeyBackupTest {
    @Test
    fun passwordProtectedBackupRestoresRecoveryKey() {
        val recoveryKey = ByteArray(32) { (it * 13 + 7).toByte() }
        val password = "test recovery phrase 2026".toCharArray()
        try {
            val backup = RecoveryKeyBackup.encrypt(recoveryKey, password)
            assertEquals(RecoveryKeyBackup.MAX_FILE_SIZE, backup.size)

            val restored = RecoveryKeyBackup.decrypt(backup, password)
            try {
                assertArrayEquals(recoveryKey, restored)
            } finally {
                restored.fill(0)
                backup.fill(0)
            }
        } finally {
            recoveryKey.fill(0)
            password.fill('\u0000')
        }
    }

    @Test
    fun rejectsIncorrectPasswordAndTamperedBackup() {
        val recoveryKey = ByteArray(32) { (it * 5 + 11).toByte() }
        val password = "test recovery phrase 2026".toCharArray()
        val wrongPassword = "another recovery phrase".toCharArray()
        try {
            val backup = RecoveryKeyBackup.encrypt(recoveryKey, password)
            assertThrows(IOException::class.java) {
                RecoveryKeyBackup.decrypt(backup, wrongPassword)
            }

            val tampered = backup.copyOf().apply {
                this[this.lastIndex] = (this.last().toInt() xor 1).toByte()
            }
            assertThrows(IOException::class.java) {
                RecoveryKeyBackup.decrypt(tampered, password)
            }
            backup.fill(0)
            tampered.fill(0)
        } finally {
            recoveryKey.fill(0)
            password.fill('\u0000')
            wrongPassword.fill('\u0000')
        }
    }

    @Test
    fun requiresLongPasswordWhenCreatingBackup() {
        val recoveryKey = ByteArray(32) { it.toByte() }
        assertThrows(IllegalArgumentException::class.java) {
            RecoveryKeyBackup.encrypt(recoveryKey, "short".toCharArray())
        }
        recoveryKey.fill(0)
    }

    @Test
    fun importedRecoveryKeyOpensEncryptedVideo() {
        val recoveryKey = ByteArray(32) { (it * 9 + 1).toByte() }
        val password = "test recovery phrase 2026".toCharArray()
        val source = File.createTempFile("recoverable", ".mp4")
        val encrypted = File.createTempFile("recoverable", ".mpv")
        try {
            val sourceBytes = ByteArray(180_000) { (it * 3).toByte() }
            source.writeBytes(sourceBytes)
            EncryptedMediaContainer.encrypt(
                source,
                encrypted,
                "mp4",
                SecretKeySpec(recoveryKey, "AES")
            )

            val backup = RecoveryKeyBackup.encrypt(recoveryKey, password)
            val importedKey = RecoveryKeyBackup.decrypt(backup, password)
            try {
                EncryptedMediaContainer.open(
                    encrypted,
                    SecretKeySpec(importedKey, "AES")
                ).use { reader ->
                    val restored = ByteArray(sourceBytes.size)
                    var offset = 0
                    while (offset < restored.size) {
                        val buffer = ByteArray(minOf(32 * 1024, restored.size - offset))
                        val count = reader.readAt(offset.toLong(), buffer.size, buffer)
                        assertEquals(buffer.size, count)
                        buffer.copyInto(restored, offset)
                        offset += count
                    }
                    assertArrayEquals(sourceBytes, restored)
                }
            } finally {
                importedKey.fill(0)
                backup.fill(0)
                sourceBytes.fill(0)
            }
        } finally {
            recoveryKey.fill(0)
            password.fill('\u0000')
            source.delete()
            encrypted.delete()
        }
    }
}
