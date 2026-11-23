package com.yourcompany.videoplayer

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkEntryFormatterTest {
    @Test
    fun formatsKnownAndUnknownDurations() {
        assertEquals("02:05", NetworkEntryFormatter.duration(125_000L))
        assertEquals("1:02:03", NetworkEntryFormatter.duration(3_723_000L))
        assertEquals("--:--", NetworkEntryFormatter.duration(null))
        assertEquals("--:--", NetworkEntryFormatter.duration(0L))
    }

    @Test
    fun formatsSizesInBinaryUnits() {
        assertEquals("512 B", NetworkEntryFormatter.size(512L))
        assertEquals("1.0 KB", NetworkEntryFormatter.size(1024L))
        assertEquals("1.5 MB", NetworkEntryFormatter.size(1_572_864L))
        assertEquals("2.0 GB", NetworkEntryFormatter.size(2_147_483_648L))
        assertEquals("--", NetworkEntryFormatter.size(0L))
    }

    @Test
    fun formatsDateInProvidedZone() {
        assertEquals(
            "2023-11-14",
            NetworkEntryFormatter.date(1_700_000_000_000L, ZoneId.of("UTC"))
        )
        assertEquals("--", NetworkEntryFormatter.date(0L))
    }

    @Test
    fun buildsFileDetailsWithAllFields() {
        val entry = NetworkMountEntry(
            name = "movie.mp4",
            relativePath = "dir/movie.mp4",
            isDirectory = false,
            size = 1024L,
            modifiedAt = 0L
        )
        assertEquals("-- · --:-- · 1.0 KB", NetworkEntryFormatter.details(entry, null))
        assertEquals("-- · 02:05 · 1.0 KB", NetworkEntryFormatter.details(entry, 125_000L))
    }

    @Test
    fun buildsDirectoryDetailsWithDateOnly() {
        val entry = NetworkMountEntry(
            name = "dir",
            relativePath = "dir",
            isDirectory = true,
            size = 0L,
            modifiedAt = 0L
        )
        assertEquals("--", NetworkEntryFormatter.details(entry, null))
    }
}
