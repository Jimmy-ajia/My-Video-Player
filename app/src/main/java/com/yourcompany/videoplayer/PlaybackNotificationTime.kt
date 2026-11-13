package com.yourcompany.videoplayer

import java.util.Locale

internal object PlaybackNotificationTime {
    fun format(positionMs: Long, durationMs: Long): String {
        val duration = if (durationMs > 0L) formatTime(durationMs) else "--:--:--"
        return "${formatTime(positionMs)} / $duration"
    }

    private fun formatTime(timeMs: Long): String {
        val totalSeconds = timeMs.coerceAtLeast(0L) / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds / 60) % 60
        val seconds = totalSeconds % 60
        return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds)
    }
}
