package com.yourcompany.videoplayer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.Settings
import android.view.MenuItem
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.reflect.Method
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var unifiedAdapter: UnifiedAdapter
    private lateinit var toolbar: Toolbar
    private lateinit var toolbarTitle: TextView
    private lateinit var btnSettings: ImageView
    private lateinit var fabResume: ExtendedFloatingActionButton
    private val itemList = ArrayList<Any>()
    private var currentPath: String? = null
    private var rootPath: String? = "STORAGE_ROOT"
    private var isLoading = false

    private fun standardizePath(path: String): String {
        if (path == "STORAGE_ROOT") return path
        var fixedPath = path.replace("/mnt/media_rw/", "/storage/")
            .replace("//", "/")
        if (fixedPath.length > 1 && fixedPath.endsWith("/")) {
            fixedPath = fixedPath.substring(0, fixedPath.length - 1)
        }
        return fixedPath
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        toolbar = findViewById(R.id.toolbar_main)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        toolbarTitle = findViewById(R.id.toolbar_title)
        btnSettings = findViewById(R.id.btn_settings)
        recyclerView = findViewById(R.id.folderRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        
        val resId = R.anim.item_animation_fall_down
        val animation = AnimationUtils.loadLayoutAnimation(this, resId)
        recyclerView.layoutAnimation = animation

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        currentPath = rootPath
        checkPermissions()
        
        fabResume = findViewById(R.id.fab_last_played)
        fabResume.setOnClickListener { playLastWatched() }
    }

    override fun onResume() {
        super.onResume()
        if (hasPermission()) {
            loadContent()
            updateResumeFab()
        }
    }

    private fun updateResumeFab() {
        val prefs = getSharedPreferences("PLAY_INFO", Context.MODE_PRIVATE)
        val lastPath = prefs.getString("LAST_PATH", "")
        if (!lastPath.isNullOrEmpty() && File(lastPath).exists()) {
            fabResume.visibility = View.VISIBLE
        } else {
            fabResume.visibility = View.GONE
        }
    }

    private fun playLastWatched() {
        val prefs = getSharedPreferences("PLAY_INFO", Context.MODE_PRIVATE)
        val lastPath = prefs.getString("LAST_PATH", "") ?: return
        val lastFile = File(lastPath)
        if (lastFile.exists()) {
            val video = VideoFile(
                id = prefs.getLong("LAST_ID", -1L),
                path = lastPath,
                title = lastFile.name,
                folderName = lastFile.parentFile?.name ?: "Unknown",
                size = lastFile.length(),
                duration = 0
            )
            val intent = Intent(this, PlayerActivity::class.java).apply {
                putExtra("VIDEO_LIST", arrayListOf(video))
                putExtra("POSITION", 0)
            }
            startActivity(intent)
        }
    }

    private fun hasPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:$packageName")
                    startActivityForResult(intent, 102)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivityForResult(intent, 102)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 103)
                }
            }
        } else {
            val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) 
                Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(permission), 101)
            }
        }
    }

    fun loadContent() {
        if (isLoading) return
        isLoading = true
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val repo = VideoRepository(this@MainActivity)
                val stdCurrentPath = standardizePath(currentPath ?: rootPath!!)
                
                val allVideos = if (stdCurrentPath == rootPath) repo.getVideosFromFolder("") else repo.getVideosFromFolder(stdCurrentPath)

                val foldersInCurrentDir = ArrayList<FolderModel>()
                val videosInCurrentDir = ArrayList<VideoFile>()
                val processedSubFolders = mutableSetOf<String>()

                if (stdCurrentPath == "STORAGE_ROOT") {
                    val storageManager = getSystemService(Context.STORAGE_SERVICE) as StorageManager
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        val storageVolumes = storageManager.storageVolumes
                        for (volume in storageVolumes) {
                            val path = getVolumePath(volume) ?: continue
                            val stdVolPath = standardizePath(path)
                            if (stdVolPath.contains("self") || stdVolPath.contains("knox") || stdVolPath == "/storage/emulated") continue
                            
                            val name = if (volume.isPrimary) "Internal Storage" else volume.getDescription(this@MainActivity) ?: "SD Card"
                            val count = allVideos.count { standardizePath(it.path).startsWith(stdVolPath) }
                            val firstVideo = allVideos.find { standardizePath(it.path).startsWith(stdVolPath) }?.path ?: ""
                            
                            foldersInCurrentDir.add(FolderModel(name, stdVolPath, count.toString(), firstVideo))
                        }
                    }
                } else {
                    for (video in allVideos) {
                        val relPath = getRelativePath(video.path, stdCurrentPath)
                        if (relPath != null && relPath.isNotEmpty()) {
                            val parts = relPath.split('/')
                            if (parts.size > 1) {
                                val subFolderName = parts[0]
                                val subFolderPath = stdCurrentPath + "/" + subFolderName
                                val subFolderNameLower = subFolderName.lowercase(Locale.ROOT)
                                if (!processedSubFolders.contains(subFolderNameLower)) {
                                    val count = allVideos.count { getRelativePath(it.path, subFolderPath) != null }
                                    foldersInCurrentDir.add(FolderModel(subFolderName, subFolderPath, count.toString(), video.path))
                                    processedSubFolders.add(subFolderNameLower)
                                }
                            } else if (parts.size == 1) {
                                videosInCurrentDir.add(video)
                            }
                        }
                    }

                    if (foldersInCurrentDir.isEmpty() && videosInCurrentDir.isEmpty()) {
                        val dir = File(stdCurrentPath)
                        dir.listFiles()?.forEach { file ->
                            if (file.isDirectory && !file.name.startsWith(".")) {
                                foldersInCurrentDir.add(FolderModel(file.name, standardizePath(file.absolutePath), "0", ""))
                            }
                        }
                    }
                }
                
                foldersInCurrentDir.sortWith { o1, o2 -> naturalCompare(o1.folderName.lowercase(), o2.folderName.lowercase()) }
                videosInCurrentDir.sortWith { o1, o2 -> naturalCompare(o1.title.lowercase(), o2.title.lowercase()) }

                withContext(Dispatchers.Main) {
                    updateToolbar()
                    itemList.clear()
                    itemList.addAll(foldersInCurrentDir)
                    itemList.addAll(videosInCurrentDir)

                    if (::unifiedAdapter.isInitialized) {
                        unifiedAdapter.notifyDataSetChanged()
                    } else {
                        unifiedAdapter = UnifiedAdapter(this@MainActivity, itemList) { selectedFolder ->
                            if (!isLoading) {
                                currentPath = standardizePath(selectedFolder.path)
                                loadContent()
                            }
                        }
                        recyclerView.adapter = unifiedAdapter
                    }
                    isLoading = false
                }
            } catch (e: Exception) { 
                withContext(Dispatchers.Main) { isLoading = false }
                e.printStackTrace() 
            }
        }
    }

    private fun isVideoFile(file: File): Boolean {
        val exts = setOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "3gp")
        return exts.contains(file.extension.lowercase())
    }

    private fun getRelativePath(videoPath: String, folderPath: String): String? {
        val stdVideoPath = standardizePath(videoPath)
        val stdFolderPath = standardizePath(folderPath)
        val videoLower = stdVideoPath.lowercase(Locale.ROOT)
        val folderLower = stdFolderPath.lowercase(Locale.ROOT)
        if (videoLower.startsWith(folderLower + "/")) {
            return stdVideoPath.substring(stdFolderPath.length + 1)
        }
        return null
    }

    private fun getVolumePath(volume: Any): String? {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val getDirectory: Method = volume.javaClass.getMethod("getDirectory")
                return (getDirectory.invoke(volume) as File).absolutePath
            }
            val getPath: Method = volume.javaClass.getMethod("getPath")
            return getPath.invoke(volume) as String
        } catch (e: Exception) { }
        return null
    }

    private fun updateToolbar() {
        val stdCurrentPath = standardizePath(currentPath ?: rootPath!!)
        if (stdCurrentPath == rootPath) {
            toolbarTitle.text = "Device Storage"
            toolbar.navigationIcon = null
        } else {
            var name = File(stdCurrentPath).name
            if (stdCurrentPath == standardizePath(Environment.getExternalStorageDirectory().absolutePath)) name = "Internal Storage"
            toolbarTitle.text = name
            toolbar.setNavigationIcon(R.drawable.ic_back)
            toolbar.navigationIcon?.setTint(ContextCompat.getColor(this, android.R.color.white))
            toolbar.setNavigationOnClickListener { onBackPressed() }
        }
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

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { onBackPressed(); return true }
        return super.onOptionsItemSelected(item)
    }

    override fun onBackPressed() {
        if (isLoading) return
        val stdCurrentPath = standardizePath(currentPath ?: rootPath!!)
        if (stdCurrentPath != rootPath) {
            val storageManager = getSystemService(Context.STORAGE_SERVICE) as StorageManager
            var isAtVolumeRoot = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                for (volume in storageManager.storageVolumes) {
                    if (stdCurrentPath == standardizePath(getVolumePath(volume) ?: "")) { isAtVolumeRoot = true; break }
                }
            }
            if (isAtVolumeRoot) { currentPath = rootPath; loadContent(); return }
            val parent = File(stdCurrentPath).parentFile
            if (parent != null && parent.absolutePath != "/" && parent.absolutePath != "/storage") {
                currentPath = standardizePath(parent.absolutePath)
                loadContent()
                return
            }
            currentPath = rootPath; loadContent(); return
        }
        super.onBackPressed()
    }
}
