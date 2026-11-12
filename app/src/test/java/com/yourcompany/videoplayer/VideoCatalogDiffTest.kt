package com.yourcompany.videoplayer

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoCatalogDiffTest {
    @Test
    fun updatesChangedVideosAndRemovesOnlyConfirmedStaleRows() {
        val scannedRoot = "/storage/emulated/0/Movies"
        val unavailableRoot = "/storage/ABCD-1234/Movies"
        val unchanged = VideoEntity.from(video("/storage/emulated/0/Movies/unchanged.mp4", 10))
        val changed = VideoEntity.from(video("/storage/emulated/0/Movies/changed.mp4", 10))
        val stale = VideoEntity.from(video("/storage/emulated/0/Movies/deleted.mp4", 10))
        val offline = VideoEntity.from(video("$unavailableRoot/offline.mp4", 10))
        val removedDirectory = VideoEntity.from(video("/storage/emulated/0/Old/deleted.mp4", 10))
        val scan = VideoScanResult(
            videos = listOf(
                unchanged.toVideoFile(),
                changed.copy(size = 20).toVideoFile(),
                video("$scannedRoot/new.mp4", 5)
            ),
            configuredDirectories = listOf(scannedRoot, unavailableRoot),
            scannedDirectories = listOf(scannedRoot)
        )

        val changes = VideoCatalogDiff.calculate(
            listOf(unchanged, changed, stale, offline, removedDirectory),
            scan
        )

        assertEquals(
            listOf("$scannedRoot/changed.mp4", "$scannedRoot/new.mp4"),
            changes.upserts.map(VideoEntity::path).sorted()
        )
        assertEquals(
            listOf(stale.path, removedDirectory.path).sorted(),
            changes.deletions.sorted()
        )
    }

    private fun video(path: String, size: Long) = VideoFile(
        id = path.hashCode().toLong(),
        path = path,
        title = path.substringAfterLast('/'),
        folderName = path.substringBeforeLast('/').substringAfterLast('/'),
        size = size,
        duration = 0,
        dateModified = 1,
        mimeType = ""
    )
}
