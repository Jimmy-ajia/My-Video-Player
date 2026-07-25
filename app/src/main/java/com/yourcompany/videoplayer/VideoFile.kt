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

    companion object {
        private const val MARKER = "_HAMZA_SECURED_VIDEO_"
        private const val SCAN_LIMIT = 1024 * 1024 * 50L // 50MB Scan limit for marker

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

        data class SecurityStatus(val isSecured: Boolean, val signatureMatches: Boolean)

        fun checkSecurityStatus(context: Context, path: String): SecurityStatus {
            val file = File(path)
            if (!file.exists() || file.length() < 2000) return SecurityStatus(false, false)

            val prefs = context.getSharedPreferences("SETTINGS", Context.MODE_PRIVATE)
            val currentSignature = prefs.getString("SECURITY_SIGNATURE", "HAMZA") ?: "HAMZA"
            val markerBytes = MARKER.toByteArray()
            val sigBytes = currentSignature.toByteArray()

            return try {
                RandomAccessFile(file, "r").use { raf ->
                    val bufferSize = 1024 * 1024 * 2 // 2MB buffer for faster scanning
                    val buffer = ByteArray(bufferSize)
                    var currentPos = 0L
                    var foundMarker = false
                    var signatureMatched = false

                    while (currentPos < SCAN_LIMIT && currentPos < file.length()) {
                        raf.seek(currentPos)
                        val readCount = raf.read(buffer)
                        if (readCount < markerBytes.size) break

                        for (i in 0 .. (readCount - markerBytes.size)) {
                            var match = true
                            for (j in markerBytes.indices) {
                                if (buffer[i + j] != markerBytes[j]) {
                                    match = false
                                    break
                                }
                            }

                            if (match) {
                                foundMarker = true
                                val sigStartPos = currentPos + i + markerBytes.size
                                raf.seek(sigStartPos)
                                
                                val actualSigInFile = ByteArray(sigBytes.size)
                                val read = raf.read(actualSigInFile)
                                if (read == sigBytes.size && actualSigInFile.contentEquals(sigBytes)) {
                                    signatureMatched = true
                                }
                                break 
                            }
                        }
                        if (foundMarker) break
                        currentPos += (bufferSize - markerBytes.size)
                    }
                    SecurityStatus(foundMarker, signatureMatched)
                }
            } catch (e: Exception) {
                SecurityStatus(false, false)
            }
        }

        fun isFileSecured(context: Context, path: String): Boolean = checkSecurityStatus(context, path).isSecured
        fun isFileEncrypted(context: Context, path: String): Boolean = checkSecurityStatus(context, path).signatureMatches
    }
}
