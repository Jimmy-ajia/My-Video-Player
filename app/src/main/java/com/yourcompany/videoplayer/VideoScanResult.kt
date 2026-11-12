package com.yourcompany.videoplayer

internal data class VideoScanResult(
    val videos: List<VideoFile>,
    val configuredDirectories: List<String>,
    val scannedDirectories: List<String>
)
