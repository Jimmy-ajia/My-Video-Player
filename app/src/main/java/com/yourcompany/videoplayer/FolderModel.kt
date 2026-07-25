package com.yourcompany.videoplayer

import java.io.Serializable

data class FolderModel(
    val folderName: String,
    val path: String,
    val videoCount: String,
    val firstVideoThumbnail: String? = null
) : Serializable