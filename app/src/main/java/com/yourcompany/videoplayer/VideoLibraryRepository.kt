package com.yourcompany.videoplayer

import androidx.room.withTransaction
import java.io.File

internal class VideoLibraryRepository(
    private val videoRepository: VideoRepository,
    private val database: VideoLibraryDatabase
) {
    fun observeVideos(sortOrder: VideoSortOrder) = when (sortOrder) {
        VideoSortOrder.MODIFIED_DESC -> database.videoDao().observeByModifiedDate()
        VideoSortOrder.NAME_ASC -> database.videoDao().observeByNameAscending()
        VideoSortOrder.NAME_DESC -> database.videoDao().observeByNameDescending()
    }

    suspend fun refresh(directoryPaths: List<String>) {
        val scanResult = videoRepository.scanDirectories(directoryPaths)
        database.withTransaction {
            val dao = database.videoDao()
            val changes = VideoCatalogDiff.calculate(dao.getAll(), scanResult)
            if (changes.upserts.isNotEmpty()) dao.upsertAll(changes.upserts)
            changes.deletions.chunked(DELETE_BATCH_SIZE).forEach { paths ->
                dao.deleteByPaths(paths)
            }
        }
    }

    private companion object {
        const val DELETE_BATCH_SIZE = 500
    }
}

internal data class VideoCatalogChanges(
    val upserts: List<VideoEntity>,
    val deletions: List<String>
)

internal object VideoCatalogDiff {
    fun calculate(existing: List<VideoEntity>, scan: VideoScanResult): VideoCatalogChanges {
        val existingByPath = existing.associateBy(VideoEntity::path)
        val scannedByPath = scan.videos
            .map { it.copy(path = File(it.path).canonicalPath) }
            .map(VideoEntity::from)
            .associateBy(VideoEntity::path)

        val upserts = scannedByPath.values.filter { existingByPath[it.path] != it }
        val deletions = existing.asSequence()
            .filter { entity ->
                val stillConfigured = scan.configuredDirectories.any { root ->
                    isWithin(entity.path, root)
                }
                !stillConfigured ||
                    (scan.scannedDirectories.any { root -> isWithin(entity.path, root) } &&
                        entity.path !in scannedByPath)
            }
            .map(VideoEntity::path)
            .toList()

        return VideoCatalogChanges(upserts, deletions)
    }

    private fun isWithin(path: String, directory: String): Boolean {
        val normalizedDirectory = directory.trimEnd(File.separatorChar)
        return path == normalizedDirectory ||
            path.startsWith(normalizedDirectory + File.separator)
    }
}
