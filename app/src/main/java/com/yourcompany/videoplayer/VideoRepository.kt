package com.yourcompany.videoplayer

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.util.ArrayList

class VideoRepository(val context: Context) {

    private fun standardizePath(path: String): String {
        if (path.isEmpty() || path == "STORAGE_ROOT") return path
        // Replace restricted mount points with public /storage/ mount point
        var p = path.replace("/mnt/media_rw/", "/storage/")
        while (p.contains("//")) p = p.replace("//", "/")
        if (p.length > 1 && p.endsWith("/")) p = p.substring(0, p.length - 1)
        return p
    }

    fun getVideosFromDirectories(directoryPaths: List<String>): ArrayList<VideoFile> {
        val videos = scanDirectories(directoryPaths).videos.sortedWith { left, right ->
            naturalCompare(left.title.lowercase(), right.title.lowercase())
        }
        return ArrayList(videos)
    }

    internal fun scanDirectories(directoryPaths: List<String>): VideoScanResult {
        val configuredRoots = directoryPaths.map { File(it).canonicalPath }.distinct()
        val roots = configuredRoots.map(::File).filter { it.isDirectory }
        val scannedRoots = configuredRoots.filter(::isOnMountedVolume)
        val list = ArrayList<VideoFile>()
        val foundPaths = mutableSetOf<String>()

        if (roots.isNotEmpty()) {
            getVideosFromFolder(extractMissingDuration = false).forEach { video ->
                val videoFile = File(video.path).canonicalFile
                if (videoFile.isFile &&
                    roots.any { root -> videoFile.toPath().startsWith(root.toPath()) }
                ) {
                    list.add(video)
                    foundPaths.add(videoFile.path)
                }
            }

            roots.forEach { root ->
                scanManualRecursive(
                    root,
                    list,
                    foundPaths,
                    0,
                    extractDuration = false,
                    failOnError = true
                )
            }
        }

        val uniqueVideos = list
            .map { video -> video.copy(path = File(video.path).canonicalPath) }
            .distinctBy(VideoFile::path)
        return VideoScanResult(uniqueVideos, configuredRoots, scannedRoots)
    }

    fun getVideosFromFolder(
        folderPath: String = "",
        extractMissingDuration: Boolean = true
    ): ArrayList<VideoFile> {
        val list = ArrayList<VideoFile>()
        val foundPaths = mutableSetOf<String>()
        val stdFolderPath = standardizePath(folderPath)

        val urisToQuery = mutableListOf<Uri>()
        urisToQuery.add(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val volumeNames = MediaStore.getExternalVolumeNames(context)
                for (vol in volumeNames) {
                    if (vol != MediaStore.VOLUME_EXTERNAL_PRIMARY) {
                        urisToQuery.add(MediaStore.Video.Media.getContentUri(vol))
                    }
                }
            } catch (e: Exception) { }
        }

        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATE_MODIFIED
        )

        for (uri in urisToQuery.distinct()) {
            try {
                val cursor = context.contentResolver.query(uri, projection, null, null, null)
                cursor?.use {
                    val dataIdx = it.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                    val idIdx = it.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    val nameIdx = it.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                    val bucketIdx = it.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                    val sizeIdx = it.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                    val durationIdx = it.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                    val dateIdx = it.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)

                    while (it.moveToNext()) {
                        val originalPath = it.getString(dataIdx) ?: ""
                        if (originalPath.isEmpty()) continue
                        
                        val stdPath = standardizePath(originalPath)
                        if (foundPaths.contains(stdPath)) continue

                        if (stdFolderPath.isNotEmpty() && stdFolderPath != "STORAGE_ROOT") {
                            val lowerStdPath = stdPath.lowercase()
                            val lowerFolder = stdFolderPath.lowercase()
                            if (!(lowerStdPath.startsWith(lowerFolder + "/") || lowerStdPath == lowerFolder)) {
                                continue
                            }
                        }

                        val fName = if (bucketIdx != -1) it.getString(bucketIdx) ?: "Unknown" else "Unknown"
                        var duration = it.getLong(durationIdx)
                        
                        // 🔥 Fix: MediaStore provides 0 for some videos, try getting it manually
                        if (duration <= 0 && extractMissingDuration) {
                            duration = getDurationManually(stdPath)
                        }

                        list.add(VideoFile(
                            id = it.getLong(idIdx),
                            path = stdPath,
                            title = it.getString(nameIdx) ?: "Unknown",
                            folderName = fName,
                            size = it.getLong(sizeIdx).let { s -> if (s > 0) s else File(stdPath).length() },
                            duration = duration,
                            dateModified = it.getLong(dateIdx)
                        ))
                        foundPaths.add(stdPath)
                    }
                }
            } catch (e: Exception) { }
        }

        if (stdFolderPath.isNotEmpty() && stdFolderPath != "STORAGE_ROOT") {
            scanManualRecursive(File(stdFolderPath), list, foundPaths, 0)
        }

        list.sortWith { o1, o2 -> naturalCompare(o1.title.lowercase(), o2.title.lowercase()) }
        return list
    }

    private fun getDurationManually(path: String): Long {
        var duration = 0L
        val retriever = MediaMetadataRetriever()
        try {
            if (EncryptedMediaContainer.isContainer(File(path))) {
                context.contentResolver.openFileDescriptor(EncryptedVideoUri.fromPath(path), "r")?.use {
                    retriever.setDataSource(it.fileDescriptor)
                }
            } else {
                retriever.setDataSource(path)
            }
            val time = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            duration = time?.toLong() ?: 0L
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { retriever.release() } catch (e: Exception) {}
        }
        return duration
    }

    private fun scanManualRecursive(
        dir: File,
        list: MutableList<VideoFile>,
        foundPaths: MutableSet<String>,
        depth: Int,
        extractDuration: Boolean = true,
        failOnError: Boolean = false
    ) {
        if (!dir.exists() || !dir.isDirectory) return
        if (depth > MAX_SCAN_DEPTH) {
            if (failOnError) throw IOException("扫描目录层级过深：${dir.absolutePath}")
            return
        }
        val videoExtensions = setOf(
            "mp4",
            "m4v",
            EncryptedMediaContainer.FILE_EXTENSION,
            EncryptedMediaContainer.LEGACY_FILE_EXTENSION,
            "mkv",
            "webm",
            "avi",
            "mov",
            "wmv",
            "flv",
            "3gp",
            "3g2",
            "ts",
            "mts",
            "m2ts",
            "mpg",
            "mpeg",
            "vob",
            "asf"
        )
        try {
            val files = dir.listFiles()
            if (files == null) {
                if (failOnError && dir.exists()) {
                    throw IOException("无法读取扫描目录：${dir.absolutePath}")
                }
                return
            }
            files.forEach { file ->
                val stdPath = standardizePath(file.absolutePath)
                if (file.isDirectory) {
                    if (!file.name.startsWith(".")) {
                        scanManualRecursive(
                            file,
                            list,
                            foundPaths,
                            depth + 1,
                            extractDuration,
                            failOnError
                        )
                    }
                } else if (file.isFile && videoExtensions.contains(file.extension.lowercase())) {
                    if (!foundPaths.contains(stdPath)) {
                        list.add(VideoFile(
                            id = file.hashCode().toLong(), 
                            path = stdPath, 
                            title = file.name, 
                            folderName = dir.name, 
                            size = file.length(), 
                            duration = if (extractDuration) getDurationManually(stdPath) else 0L,
                            dateModified = file.lastModified() / 1000
                        ))
                        foundPaths.add(stdPath)
                    }
                }
            }
        } catch (e: Exception) {
            if (failOnError) throw IOException("扫描目录失败：${dir.absolutePath}", e)
        }
    }

    private fun isOnMountedVolume(path: String): Boolean {
        val directory = File(path).canonicalFile
        if (directory.isDirectory) return true

        val primaryRoot = Environment.getExternalStorageDirectory().canonicalFile
        if (directory.toPath().startsWith(primaryRoot.toPath())) {
            return primaryRoot.isDirectory
        }

        val storageRoot = File("/storage").canonicalFile
        if (!directory.toPath().startsWith(storageRoot.toPath())) return false
        val relativePath = storageRoot.toPath().relativize(directory.toPath())
        if (relativePath.nameCount == 0) return storageRoot.isDirectory
        return File(storageRoot, relativePath.getName(0).toString()).isDirectory
    }

    private companion object {
        const val MAX_SCAN_DEPTH = 64
    }

    private fun naturalCompare(s1: String, s2: String): Int {
        val n1 = s1.length; val n2 = s2.length
        var i1 = 0; var i2 = 0
        while (i1 < n1 && i2 < n2) {
            val c1 = s1[i1]; val c2 = s2[i2]
            if (Character.isDigit(c1) && Character.isDigit(c2)) {
                var num1 = ""; while (i1 < n1 && Character.isDigit(s1[i1])) { num1 += s1[i1]; i1++ }
                var num2 = ""; while (i2 < n2 && Character.isDigit(s2[i2])) { num2 += s2[i2]; i2++ }
                val res = try { num1.toLong().compareTo(num2.toLong()) } catch (e: Exception) { 0 }
                if (res != 0) return res
            } else {
                if (c1 != c2) return c1.compareTo(c2)
                i1++; i2++
            }
        }
        return n1 - n2
    }
}
