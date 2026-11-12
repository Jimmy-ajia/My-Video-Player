package com.yourcompany.videoplayer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import javax.crypto.spec.SecretKeySpec

class EncryptedMediaContainerTest {
    private val wrappingKey = SecretKeySpec(ByteArray(32) { (it * 17 + 3).toByte() }, "AES")

    @Test
    fun encryptsAnyMediaBytesAndSupportsRandomRangeReadsAcrossChunks() {
        val sourceBytes = ByteArray(2 * 1024 * 1024 + 73).also(SecureRandom()::nextBytes)
        val source = File.createTempFile("source", ".mkv")
        val encrypted = File.createTempFile("encrypted", ".mpv")
        try {
            source.writeBytes(sourceBytes)
            EncryptedMediaContainer.encrypt(source, encrypted, "mkv", wrappingKey)

            assertTrue(EncryptedMediaContainer.isContainer(encrypted))
            EncryptedMediaContainer.open(encrypted, wrappingKey).use { reader ->
                assertEquals("mkv", reader.originalExtension)
                assertEquals(sourceBytes.size.toLong(), reader.size)

                val start = 1024 * 1024 - 31
                val restored = ByteArray(1024 * 1024 + 91)
                val count = reader.readAt(start.toLong(), restored.size, restored)
                assertEquals(restored.size, count)
                assertArrayEquals(sourceBytes.copyOfRange(start, start + count), restored)
            }
        } finally {
            source.delete()
            encrypted.delete()
        }
    }

    @Test
    fun rejectsWrongWrappingKeyAndTamperedChunks() {
        val source = File.createTempFile("source", ".mp4")
        val encrypted = File.createTempFile("encrypted", ".mpv")
        try {
            source.writeBytes(ByteArray(2048) { it.toByte() })
            EncryptedMediaContainer.encrypt(source, encrypted, "mp4", wrappingKey)

            val wrongKey = SecretKeySpec(ByteArray(32) { (it * 11 + 9).toByte() }, "AES")
            assertThrows(IOException::class.java) {
                EncryptedMediaContainer.open(encrypted, wrongKey)
            }

            val bytes = encrypted.readBytes()
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            encrypted.writeBytes(bytes)
            EncryptedMediaContainer.open(encrypted, wrappingKey).use { reader ->
                assertThrows(IOException::class.java) {
                    reader.readAt(0, 64, ByteArray(64))
                }
            }
        } finally {
            source.delete()
            encrypted.delete()
        }
    }

    @Test
    fun rejectsInvalidHeaderChanges() {
        val source = File.createTempFile("source", ".mov")
        val encrypted = File.createTempFile("encrypted", ".mpv")
        try {
            source.writeBytes(ByteArray(32) { it.toByte() })
            EncryptedMediaContainer.encrypt(source, encrypted, "mov", wrappingKey)
            val bytes = encrypted.readBytes()
            bytes[12] = (bytes[12].toInt() xor 1).toByte()
            encrypted.writeBytes(bytes)

            assertTrue(EncryptedMediaContainer.isContainer(encrypted))
            assertThrows(IOException::class.java) {
                EncryptedMediaContainer.open(encrypted, wrappingKey)
            }
        } finally {
            source.delete()
            encrypted.delete()
        }
    }

    @Test
    fun outputDoesNotExposeSourceContent() {
        val marker = "cloud-drive-must-only-see-ciphertext-".repeat(12)
        val source = File.createTempFile("source", ".avi")
        val encrypted = File.createTempFile("encrypted", ".mpv")
        try {
            source.writeText(marker)
            EncryptedMediaContainer.encrypt(source, encrypted, "avi", wrappingKey)

            val stored = encrypted.readBytes().toString(Charsets.ISO_8859_1)
            assertFalse(stored.contains(marker))
        } finally {
            source.delete()
            encrypted.delete()
        }
    }

    @Test
    fun encryptsAndRestoresRetainedVideoSamples() {
        val extensions = listOf("mp4", "webm", "mkv", "mov", "avi", "flv", "ts", "wmv", "3gp", "vob")

        extensions.forEach { extension ->
            val resource = javaClass.getResourceAsStream("/video-samples/flower.$extension")
                ?: error("Missing video sample: flower.$extension")
            val source = File.createTempFile("sample-$extension", ".$extension")
            val encrypted = File.createTempFile("sample-encrypted-", ".mpv")

            try {
                resource.use { input ->
                    source.outputStream().use { output -> input.copyTo(output) }
                }
                val sourceBytes = source.readBytes()
                val restoredBytes = ByteArray(sourceBytes.size)
                EncryptedMediaContainer.encrypt(source, encrypted, extension, wrappingKey)

                assertTrue(EncryptedMediaContainer.isContainer(encrypted))
                EncryptedMediaContainer.open(encrypted, wrappingKey).use { reader ->
                    assertEquals(extension, reader.originalExtension)
                    assertEquals(sourceBytes.size.toLong(), reader.size)

                    var offset = 0
                    while (offset < restoredBytes.size) {
                        val buffer = ByteArray(minOf(64 * 1024, restoredBytes.size - offset))
                        val count = reader.readAt(offset.toLong(), buffer.size, buffer)
                        assertEquals(buffer.size, count)
                        buffer.copyInto(restoredBytes, offset)
                        offset += count
                    }
                }

                assertArrayEquals(sourceBytes, restoredBytes)
            } finally {
                source.delete()
                encrypted.delete()
            }
        }
    }
}
