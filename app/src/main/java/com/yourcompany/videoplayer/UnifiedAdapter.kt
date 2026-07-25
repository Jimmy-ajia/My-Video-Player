package com.yourcompany.videoplayer

import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.signature.ObjectKey
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class UnifiedAdapter(
    private val context: Context,
    private val items: ArrayList<Any>,
    private val onFolderClick: (FolderModel) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val adapterScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val encryptionCache = ConcurrentHashMap<String, Boolean>()

    companion object {
        private const val TYPE_FOLDER = 1
        private const val TYPE_VIDEO = 2
    }

    override fun getItemViewType(position: Int): Int = if (items[position] is FolderModel) TYPE_FOLDER else TYPE_VIDEO

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_FOLDER) {
            FolderViewHolder(LayoutInflater.from(context).inflate(R.layout.folder_item, parent, false))
        } else {
            VideoViewHolder(LayoutInflater.from(context).inflate(R.layout.video_item, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        val playPrefs = context.getSharedPreferences("PLAY_INFO", Context.MODE_PRIVATE)
        val lastId = playPrefs.getLong("LAST_ID", -1L)
        val lastPath = playPrefs.getString("LAST_PATH", "") ?: ""
        val settingsPrefs = context.getSharedPreferences("SETTINGS", Context.MODE_PRIVATE)
        val alwaysVlc = settingsPrefs.getBoolean("ALWAYS_VLC", false)
        val durPrefs = context.getSharedPreferences("SECURE_DUR", Context.MODE_PRIVATE)
        val currentSig = settingsPrefs.getString("SECURITY_SIGNATURE", "HAMZA") ?: "HAMZA"

        if (holder is FolderViewHolder && item is FolderModel) {
            holder.folderName.text = item.folderName
            holder.videoCount.text = item.videoCount
            holder.folderIcon.setImageResource(R.drawable.ios_folder)
            
            if (lastPath.startsWith(item.path)) {
                holder.folderName.setTextColor(Color.parseColor("#FF5252"))
                holder.folderIcon.setColorFilter(Color.parseColor("#FF5252"))
            } else {
                holder.folderName.setTextColor(Color.WHITE)
                holder.folderIcon.clearColorFilter()
            }
            holder.itemView.setOnClickListener { onFolderClick(item) }
        } else if (holder is VideoViewHolder && item is VideoFile) {
            holder.videoTitle.text = item.displayTitle
            holder.videoSize.text = item.formattedSize
            
            if (item.path == lastPath || item.id == lastId) {
                holder.videoTitle.setTextColor(Color.parseColor("#FF5252"))
            } else {
                holder.videoTitle.setTextColor(Color.WHITE)
            }

            // Duration Logic
            holder.thumbJob?.cancel()
            val isEnc = encryptionCache[item.path]
            val savedDur = durPrefs.getLong(item.path, 0L)
            
            if (savedDur > 0) {
                item.duration = savedDur
            }
            holder.videoDuration.text = item.formattedDuration

            // Watch Progress Logic
            val resumePrefs = context.getSharedPreferences("RESUME_INFO", Context.MODE_PRIVATE)
            val resumePos = resumePrefs.getLong(item.path, 0L)
            if (resumePos > 1000 && item.duration > 0) {
                holder.watchProgress.visibility = View.VISIBLE
                holder.watchProgress.max = item.duration.toInt()
                holder.watchProgress.progress = resumePos.toInt()
            } else {
                holder.watchProgress.visibility = View.GONE
            }

            holder.thumbJob = adapterScope.launch {
                val encrypted = isEnc ?: withContext(Dispatchers.IO) { VideoFile.isFileEncrypted(context, item.path) }
                encryptionCache[item.path] = encrypted
                
                if (encrypted && savedDur <= 0L) {
                    val realDur = withContext(Dispatchers.IO) { getRealDuration(item.path) }
                    if (realDur > 0) {
                        durPrefs.edit().putLong(item.path, realDur).apply()
                        item.duration = realDur
                        holder.videoDuration.text = item.formattedDuration
                        if (resumePos > 1000) {
                            holder.watchProgress.visibility = View.VISIBLE
                            holder.watchProgress.max = item.duration.toInt()
                            holder.watchProgress.progress = resumePos.toInt()
                        }
                    }
                }
                
                loadThumbnail(holder, item, encrypted, currentSig)
            }

            holder.itemView.setOnClickListener {
                playPrefs.edit().putLong("LAST_ID", item.id).putString("LAST_PATH", item.path).apply()
                val videoItems = items.filterIsInstance<VideoFile>()
                context.startActivity(Intent(context, PlayerActivity::class.java).apply {
                    putExtra("VIDEO_LIST", ArrayList(videoItems))
                    putExtra("POSITION", videoItems.indexOf(item))
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }

            holder.videoMenu.setOnClickListener {
                val popup = PopupMenu(context, holder.videoMenu)
                popup.menuInflater.inflate(R.menu.video_menu, popup.menu)
                popup.setOnMenuItemClickListener { menuItem ->
                    when (menuItem.itemId) {
                        R.id.rename -> { showRenameDialog(item); true }
                        R.id.delete -> { deleteVideoRequest(item); true }
                        else -> false
                    }
                }
                popup.show()
            }
        }
    }

    private fun loadThumbnail(holder: VideoViewHolder, item: VideoFile, isEncrypted: Boolean, signature: String) {
        // 🔥 Fix: Use File source instead of MediaStore ID for more reliable thumbnails on manual scans
        val thumbSource: Any = if (isEncrypted) {
            Uri.parse("content://com.yourcompany.videoplayer.decryption${item.path}")
        } else {
            File(item.path)
        }

        Glide.with(context)
            .asBitmap()
            .load(thumbSource)
            .signature(ObjectKey("${item.path}_${signature}_$isEncrypted"))
            .apply(RequestOptions()
                .placeholder(R.drawable.ic_video)
                .error(R.drawable.ic_video)
                .centerCrop()
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .override(300, 300))
            .into(holder.videoThumbnail)
    }

    private fun getRealDuration(path: String): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            val uri = Uri.parse("content://com.yourcompany.videoplayer.decryption$path")
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
        } catch (e: Exception) { 0L }
        finally { try { retriever.release() } catch (e: Exception) {} }
    }

    private fun showRenameDialog(video: VideoFile) {
        val et = EditText(context)
        et.setTextColor(Color.BLACK)
        et.setText(video.title.substringBeforeLast("."))
        val container = FrameLayout(context)
        val params = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.setMargins(60, 20, 60, 10)
        et.layoutParams = params
        container.addView(et)

        AlertDialog.Builder(context, R.style.WhiteDialog)
            .setTitle("Rename Video")
            .setView(container)
            .setPositiveButton("OK") { _, _ ->
                val newName = et.text.toString().trim()
                if (newName.isNotEmpty()) {
                    val senderRequest = FileManager.renameMedia(context, video.id, newName, 
                        onSuccess = { refreshData() },
                        onFailure = { msg -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
                    )
                    senderRequest?.let { (context as Activity).startIntentSenderForResult(it.intentSender, 124, null, 0, 0, 0) }
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun deleteVideoRequest(video: VideoFile) {
        val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, video.id)
        AlertDialog.Builder(context, R.style.WhiteDialog)
            .setTitle("Delete Video")
            .setMessage("Are you sure you want to delete this video?")
            .setPositiveButton("Delete") { _, _ ->
                val pendingIntent = FileManager.deleteSingleVideo(context, video.id)
                if (pendingIntent != null) {
                    (context as Activity).startIntentSenderForResult(pendingIntent.intentSender, 123, null, 0, 0, 0)
                } else {
                    try {
                        context.contentResolver.delete(uri, null, null)
                        refreshData()
                    } catch (e: Exception) { }
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun refreshData() {
        (context as Activity).runOnUiThread { if (context is MainActivity) context.loadContent() }
    }

    override fun getItemCount(): Int = items.size
    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        adapterScope.cancel()
    }

    class FolderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val folderIcon: ImageView = view.findViewById(R.id.folderIcon)
        val folderName: TextView = view.findViewById(R.id.folderName)
        val videoCount: TextView = view.findViewById(R.id.videoCount)
    }

    class VideoViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val videoThumbnail: ImageView = view.findViewById(R.id.videoThumbnail)
        val videoTitle: TextView = view.findViewById(R.id.videoTitle)
        val videoSize: TextView = view.findViewById(R.id.videoSize)
        val videoDuration: TextView = view.findViewById(R.id.videoDuration)
        val videoMenu: ImageButton = view.findViewById(R.id.videoMenu)
        val watchProgress: ProgressBar = view.findViewById(R.id.watchProgress)
        var thumbJob: Job? = null
    }
}
