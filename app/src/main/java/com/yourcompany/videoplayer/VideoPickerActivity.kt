package com.yourcompany.videoplayer

import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.signature.ObjectKey
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.*
import java.io.File
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class VideoPickerActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: PickerUnifiedAdapter
    private val itemList = ArrayList<Any>()
    private val selectedPaths = HashSet<String>()
    
    private var currentPath: String? = "STORAGE_ROOT"
    private val rootPath = "STORAGE_ROOT"
    private var convertMenuItem: MenuItem? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_picker)

        val toolbar = findViewById<Toolbar>(R.id.toolbar_picker)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { 
            if (currentPath == rootPath) finish() else onBackPressed()
        }

        recyclerView = findViewById(R.id.rv_picker)
        recyclerView.layoutManager = LinearLayoutManager(this)
        
        loadContent()
    }

    private fun loadContent() {
        val repo = VideoRepository(this)
        val stdCurrentPath = standardizePath(currentPath ?: rootPath)
        val allVideos = if (stdCurrentPath == rootPath) repo.getVideosFromFolder("") else repo.getVideosFromFolder(stdCurrentPath)

        val foldersInCurrentDir = ArrayList<FolderModel>()
        val videosInCurrentDir = ArrayList<VideoFile>()
        val processedSubFolders = mutableSetOf<String>()

        if (stdCurrentPath == "STORAGE_ROOT") {
            val storageManager = getSystemService(Context.STORAGE_SERVICE) as StorageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                for (volume in storageManager.storageVolumes) {
                    val path = getVolumePath(volume) ?: continue
                    val stdVolPath = standardizePath(path)
                    if (stdVolPath.contains("self") || stdVolPath.contains("knox") || stdVolPath == "/storage/emulated") continue
                    
                    val name = if (volume.isPrimary) "Internal Storage" else volume.getDescription(this) ?: "SD Card"
                    val count = allVideos.count { standardizePath(it.path).startsWith(stdVolPath) }
                    foldersInCurrentDir.add(FolderModel(name, stdVolPath, count.toString(), ""))
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
                        if (!processedSubFolders.contains(subFolderName.lowercase())) {
                            val count = allVideos.count { getRelativePath(it.path, subFolderPath) != null }
                            foldersInCurrentDir.add(FolderModel(subFolderName, subFolderPath, count.toString(), video.path))
                            processedSubFolders.add(subFolderName.lowercase())
                        }
                    } else if (parts.size == 1) {
                        videosInCurrentDir.add(video)
                    }
                }
            }
        }

        foldersInCurrentDir.sortBy { it.folderName.lowercase() }
        videosInCurrentDir.sortBy { it.title.lowercase() }

        itemList.clear()
        itemList.addAll(foldersInCurrentDir)
        itemList.addAll(videosInCurrentDir)

        adapter = PickerUnifiedAdapter(this, itemList, selectedPaths) { item ->
            if (item is FolderModel) {
                currentPath = item.path
                loadContent()
            } else if (item is VideoFile) {
                toggleSelection(item.path)
            }
        }
        recyclerView.adapter = adapter
        updateUI()
    }

    private fun toggleSelection(path: String) {
        if (selectedPaths.contains(path)) {
            selectedPaths.remove(path)
        } else {
            selectedPaths.add(path)
        }
        adapter.notifyDataSetChanged()
        updateUI()
    }

    private fun updateUI() {
        val count = selectedPaths.size
        supportActionBar?.title = if (count > 0) "$count Selected" else "Select Video"
        convertMenuItem?.isVisible = count > 0
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_video_picker, menu)
        convertMenuItem = menu.findItem(R.id.action_convert)
        updateUI()
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_convert) {
            val intent = Intent()
            intent.putStringArrayListExtra("PATHS", ArrayList(selectedPaths.toList()))
            setResult(Activity.RESULT_OK, intent)
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onBackPressed() {
        val stdCurrentPath = standardizePath(currentPath ?: rootPath)
        if (stdCurrentPath != rootPath) {
            val parent = File(stdCurrentPath).parentFile
            if (parent != null && parent.absolutePath != "/" && parent.absolutePath != "/storage") {
                currentPath = parent.absolutePath
            } else {
                currentPath = rootPath
            }
            loadContent()
        } else {
            super.onBackPressed()
        }
    }

    private fun standardizePath(path: String): String {
        if (path == "STORAGE_ROOT") return path
        var p = path.replace("/mnt/media_rw/", "/storage/").replace("//", "/")
        if (p.length > 1 && p.endsWith("/")) p = p.substring(0, p.length - 1)
        return p
    }

    private fun getRelativePath(videoPath: String, folderPath: String): String? {
        val v = standardizePath(videoPath).lowercase()
        val f = standardizePath(folderPath).lowercase()
        return if (v.startsWith("$f/")) standardizePath(videoPath).substring(f.length + 1) else null
    }

    private fun getVolumePath(volume: Any): String? {
        return try {
            val method = volume.javaClass.getMethod(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) "getDirectory" else "getPath")
            val result = method.invoke(volume)
            if (result is File) result.absolutePath else result as String
        } catch (e: Exception) { null }
    }

    private class PickerUnifiedAdapter(
        private val context: Context,
        private val items: List<Any>,
        private val selectedPaths: HashSet<String>,
        private val onItemClick: (Any) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val adapterScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        private val encryptionCache = ConcurrentHashMap<String, Boolean>()

        override fun getItemViewType(position: Int) = if (items[position] is FolderModel) 1 else 2

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(context)
            return if (viewType == 1) FolderViewHolder(inflater.inflate(R.layout.folder_item, parent, false))
            else VideoViewHolder(inflater.inflate(R.layout.video_item, parent, false))
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = items[position]
            val settingsPrefs = context.getSharedPreferences("SETTINGS", Context.MODE_PRIVATE)
            val currentSig = settingsPrefs.getString("SECURITY_SIGNATURE", "HAMZA") ?: "HAMZA"

            if (holder is FolderViewHolder && item is FolderModel) {
                holder.name.text = item.folderName
                holder.count.text = item.videoCount
                holder.icon.setImageResource(R.drawable.ios_folder)
                holder.itemView.setOnClickListener { onItemClick(item) }
            } else if (holder is VideoViewHolder && item is VideoFile) {
                holder.title.text = item.title
                holder.size.text = formatSize(item.size)
                holder.duration.text = formatDuration(item.duration)
                holder.menu.visibility = View.GONE

                val isSelected = selectedPaths.contains(item.path)
                holder.overlay.visibility = if (isSelected) View.VISIBLE else View.GONE
                holder.tick.visibility = if (isSelected) View.VISIBLE else View.GONE
                holder.card.setCardBackgroundColor(if (isSelected) Color.parseColor("#332196F3") else Color.parseColor("#1E1E1E"))
                holder.card.strokeColor = if (isSelected) Color.parseColor("#2196F3") else Color.parseColor("#1AFFFFFF")
                holder.card.strokeWidth = if (isSelected) 4 else 1

                // 🔥 Async Thumbnail Loading with Context for Dynamic Signature
                holder.thumbJob?.cancel()
                holder.thumbJob = adapterScope.launch {
                    val isEncrypted = encryptionCache[item.path] ?: withContext(Dispatchers.IO) {
                        VideoFile.isFileEncrypted(context, item.path)
                    }
                    encryptionCache[item.path] = isEncrypted

                    val thumbUri: Uri = if (isEncrypted) {
                        Uri.parse("content://com.yourcompany.videoplayer.decryption${item.path}")
                    } else {
                        ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, item.id)
                    }

                    // Fix: Use holder.itemView.context or holder.thumbnail to avoid crash on destroyed activity
                    Glide.with(holder.itemView)
                        .asBitmap()
                        .load(thumbUri)
                        .signature(ObjectKey("${item.path}_${currentSig}_$isEncrypted"))
                        .apply(
                            RequestOptions()
                                .placeholder(R.drawable.ic_video)
                                .error(R.drawable.ic_video)
                                .centerCrop()
                                .diskCacheStrategy(DiskCacheStrategy.ALL)
                                .override(250, 250)
                        )
                        .into(holder.thumbnail)
                }
                
                holder.itemView.setOnClickListener { onItemClick(item) }
            }
        }

        override fun getItemCount() = items.size
        
        override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
            super.onDetachedFromRecyclerView(recyclerView)
            adapterScope.cancel()
        }

        private fun formatSize(s: Long) = String.format("%.1f MB", s / (1024.0 * 1024.0))
        private fun formatDuration(ms: Long): String {
            val s = ms / 1000; val m = s / 60; val h = m / 60
            return if (h > 0) String.format("%02d:%02d:%02d", h, m % 60, s % 60) else String.format("%02d:%02d", m % 60, s % 60)
        }

        class FolderViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.folderName)
            val count: TextView = v.findViewById(R.id.videoCount)
            val icon: ImageView = v.findViewById(R.id.folderIcon)
        }
        class VideoViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val card: MaterialCardView = v.findViewById(R.id.videoCard)
            val thumbnail: ImageView = v.findViewById(R.id.videoThumbnail)
            val title: TextView = v.findViewById(R.id.videoTitle)
            val size: TextView = v.findViewById(R.id.videoSize)
            val duration: TextView = v.findViewById(R.id.videoDuration)
            val overlay: View = v.findViewById(R.id.selectionOverlay)
            val tick: ImageView = v.findViewById(R.id.tickMark)
            val menu: View = v.findViewById(R.id.videoMenu)
            var thumbJob: Job? = null
        }
    }
}
