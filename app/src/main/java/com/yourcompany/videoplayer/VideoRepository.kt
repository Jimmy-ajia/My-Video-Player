package com.yourcompany.videoplayer

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
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

    fun getVideosFromFolder(folderPath: String = ""): ArrayList<VideoFile> {
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
                        if (duration <= 0) {
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
            retriever.setDataSource(path)
            val time = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            duration = time?.toLong() ?: 0L
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { retriever.release() } catch (e: Exception) {}
        }
        return duration
    }

    private fun scanManualRecursive(dir: File, list: MutableList<VideoFile>, foundPaths: MutableSet<String>, depth: Int) {
        if (!dir.exists() || !dir.isDirectory || depth > 2) return 
        val videoExtensions = setOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "3gp")
        try {
            dir.listFiles()?.forEach { file ->
                val stdPath = standardizePath(file.absolutePath)
                if (file.isDirectory) {
                    if (!file.name.startsWith(".")) scanManualRecursive(file, list, foundPaths, depth + 1)
                } else if (file.isFile && videoExtensions.contains(file.extension.lowercase())) {
                    if (!foundPaths.contains(stdPath)) {
                        list.add(VideoFile(
                            id = file.hashCode().toLong(), 
                            path = stdPath, 
                            title = file.name, 
                            folderName = dir.name, 
                            size = file.length(), 
                            duration = getDurationManually(stdPath), 
                            dateModified = file.lastModified() / 1000
                        ))
                        foundPaths.add(stdPath)
                    }
                }
            }
        } catch (e: Exception) { }
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
