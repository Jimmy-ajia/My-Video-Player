package com.yourcompany.videoplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class VideoFileConverterTest {
    @Test
    fun conversionOutputsAreSiblingFilesAndNeverOverwriteSourceOrExistingOutput() {
        val directory = Files.createTempDirectory("video-conversion").toFile()
        val source = File(directory, "clip.mp4").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        try {
            val encrypted = VideoFileConverter.encryptedOutputFor(source)
            assertEquals(directory.canonicalFile, encrypted.parentFile?.canonicalFile)
            assertNotEquals(source.canonicalFile, encrypted.canonicalFile)
            encrypted.writeBytes(byteArrayOf(4))

            val nextEncrypted = VideoFileConverter.encryptedOutputFor(source)
            assertNotEquals(encrypted.canonicalFile, nextEncrypted.canonicalFile)
            assertEquals("clip_encrypted_1.oxu", nextEncrypted.name)

            val decrypted = VideoFileConverter.decryptedOutputFor(encrypted, "mp4")
            assertEquals(directory.canonicalFile, decrypted.parentFile?.canonicalFile)
            assertEquals("clip_decrypted.mp4", decrypted.name)
            assertTrue(source.isFile)
            assertTrue(encrypted.isFile)
        } finally {
            directory.deleteRecursively()
        }
    }
}
