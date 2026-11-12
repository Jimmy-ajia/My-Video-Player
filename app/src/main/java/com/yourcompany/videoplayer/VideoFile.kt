package com.yourcompany.videoplayer

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.io.Serializable

data class VideoFile(
    val id: Long,
    val path: String,
    val title: String,
    val folderName: String,
    val size: Long,
    var duration: Long,
    val dateModified: Long = 0L,
    val mimeType: String = ""
) : Serializable {

    val formattedDuration: String get() = formatDuration(duration)
    val formattedSize: String get() = formatSize(size)
    val displayTitle: String get() = if (title.isNotBlank() && title != "Unknown") title else path.substringAfterLast("/")

    enum class EncryptionType { NONE, CURRENT, LEGACY }

    companion object {
        private const val LEGACY_MARKER = "_HAMZA_SECURED_VIDEO_"
        private const val LEGACY_SCAN_LIMIT = 15 * 1024 * 1024L

        fun formatDuration(durationMs: Long): String {
            if (durationMs <= 0) return "--:--"
            val seconds = (durationMs / 1000) % 60
            val minutes = (durationMs / (1000 * 60)) % 60
            val hours = (durationMs / (1000 * 60 * 60))
            return if (hours > 0) String.format("%d:%02d:%02d", hours, minutes, seconds)
            else String.format("%02d:%02d", minutes, seconds)
        }
        
        private fun formatSize(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val mb = bytes / (1024.0 * 1024.0)
            return if (mb >= 1) String.format("%.1f MB", mb) else "${bytes / 1024} KB"
        }

        fun isFileEncrypted(context: Context, path: String): Boolean =
            EncryptedMediaContainer.isContainer(File(path))

        fun encryptionType(context: Context, path: String): EncryptionType = when {
            isFileEncrypted(context, path) -> EncryptionType.CURRENT
            isLegacyEncrypted(path) -> EncryptionType.LEGACY
            else -> EncryptionType.NONE
        }

        fun isLegacyEncrypted(path: String): Boolean {
            val file = File(path)
            if (!file.isFile) return false
            if (hasLegacyContainerFooter(file)) return true
            val marker = LEGACY_MARKER.toByteArray(Charsets.US_ASCII)
            return try {
                RandomAccessFile(file, "r").use { raf ->
                    val buffer = ByteArray(1024 * 1024)
                    var position = 0L
                    while (position < LEGACY_SCAN_LIMIT && position < file.length()) {
                        raf.seek(position)
                        val read = raf.read(buffer)
                        if (read < marker.size) return@use false
                        for (index in 0..read - marker.size) {
                            if (buffer.regionMatches(index, marker)) return@use true
                        }

                        position += read - marker.size + 1
                    }
                    false
                }
            } catch (_: java.io.IOException) {
                false
            }
        }

        private fun hasLegacyContainerFooter(file: File): Boolean {
            if (file.length() < 16) return false
            return try {
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(file.length() - 16)
                    val magic = ByteArray(8)
                    raf.readFully(magic)
                    magic.contentEquals("MVPEND01".toByteArray(Charsets.US_ASCII))
                }
            } catch (_: java.io.IOException) {
                false
            }
        }

        private fun ByteArray.regionMatches(offset: Int, expected: ByteArray): Boolean {
            for (index in expected.indices) if (this[offset + index] != expected[index]) return false
            return true
        }
    }
}
