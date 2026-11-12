package com.yourcompany.videoplayer

import android.net.Uri

internal object EncryptedVideoUri {
    private const val AUTHORITY = "com.yourcompany.videoplayer.decryption"

    fun fromPath(path: String): Uri = Uri.Builder()
        .scheme("content")
        .authority(AUTHORITY)
        .path(path)
        .build()
}
