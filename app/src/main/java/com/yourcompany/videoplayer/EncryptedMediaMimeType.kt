package com.yourcompany.videoplayer

import android.webkit.MimeTypeMap

internal object EncryptedMediaMimeType {
    fun fromExtension(extension: String): String {
        val normalized = extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(normalized)
            ?: when (normalized) {
                "mp4", "m4v", "mov" -> "video/mp4"
                "3gp" -> "video/3gpp"
                "3g2" -> "video/3gpp2"
                "mkv" -> "video/x-matroska"
                "webm" -> "video/webm"
                "ts", "mts", "m2ts" -> "video/mp2t"
                "flv" -> "video/x-flv"
                "avi" -> "video/x-msvideo"
                "wmv" -> "video/x-ms-wmv"
                "asf" -> "video/x-ms-asf"
                "mpg", "mpeg", "vob" -> "video/mpeg"
                else -> "application/octet-stream"
            }
    }
}
