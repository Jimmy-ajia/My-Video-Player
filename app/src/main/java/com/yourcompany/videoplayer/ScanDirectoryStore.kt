package com.yourcompany.videoplayer

import android.content.Context
import android.os.Environment
import java.io.File

internal object ScanDirectoryStore {
    private const val PREFERENCES = "SCAN_DIRECTORIES"
    private const val KEY_PATHS = "paths"

    fun defaults(): List<File> {
        val storageRoot = Environment.getExternalStorageDirectory()
        return listOf("Movies", "DCIM", "Pictures", "Download")
            .map { File(storageRoot, it).canonicalFile }
    }

    fun getPaths(context: Context): List<String> {
        val storedPaths = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getStringSet(KEY_PATHS, null)
        return storedPaths?.toList()?.sorted() ?: defaults().map(File::getPath)
    }

    fun add(context: Context, path: String): Boolean {
        val normalizedPath = File(path).canonicalPath
        val paths = getPaths(context).toMutableSet()
        if (!paths.add(normalizedPath)) return false
        save(context, paths)
        return true
    }

    fun remove(context: Context, path: String) {
        val normalizedPath = File(path).canonicalPath
        val paths = getPaths(context).toMutableSet()
        if (paths.remove(normalizedPath)) save(context, paths)
    }

    private fun save(context: Context, paths: Set<String>) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_PATHS, paths.toSet())
            .apply()
    }
}
