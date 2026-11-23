package com.yourcompany.videoplayer

import java.time.Instant
import java.time.ZoneId
import java.util.Locale

internal object NetworkEntryFormatter {
    fun date(modifiedAtMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        if (modifiedAtMillis <= 0L) return "--"
        return Instant.ofEpochMilli(modifiedAtMillis)
            .atZone(zone)
            .toLocalDate()
            .toString()
    }

    fun duration(durationMs: Long?): String =
        if (durationMs == null || durationMs <= 0L) "--:--" else VideoFile.formatDuration(durationMs)

    fun size(bytes: Long): String {
        if (bytes <= 0L) return "--"
        if (bytes < 1024L) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes / 1024.0
        var unit = 0
        while (value >= 1024.0 && unit < units.lastIndex) {
            value /= 1024.0
            unit++
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit])
    }

    /** Second row of a browser entry: date · duration · size for files, date only for folders. */
    fun details(entry: NetworkMountEntry, durationMs: Long?): String =
        if (entry.isDirectory) {
            date(entry.modifiedAt)
        } else {
            listOf(date(entry.modifiedAt), duration(durationMs), size(entry.size))
                .joinToString(" · ")
        }
}
