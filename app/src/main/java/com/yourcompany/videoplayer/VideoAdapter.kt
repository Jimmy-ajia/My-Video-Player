package com.yourcompany.videoplayer

import android.app.Activity
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.signature.ObjectKey
import com.google.android.material.imageview.ShapeableImageView
import kotlinx.coroutines.*
import java.io.File

class VideoAdapter(
    private val context: Context,
    private var videoList: ArrayList<VideoFile>
) : RecyclerView.Adapter<VideoAdapter.VideoHolder>() {

    private val adapterScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        var pendingRenameUri: android.net.Uri? = null
        var pendingNewName: String? = null
    }

    class VideoHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.videoTitle)
        val thumbnail: ShapeableImageView = view.findViewById(R.id.videoThumbnail)
        val duration: TextView = view.findViewById(R.id.videoDuration)
        val size: TextView = view.findViewById(R.id.videoSize)
        val menu: ImageButton = view.findViewById(R.id.videoMenu)
        val progress: ProgressBar = view.findViewById(R.id.watchProgress)
        var thumbJob: Job? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.video_item, parent, false)
        return VideoHolder(view)
    }

    override fun onBindViewHolder(holder: VideoHolder, position: Int) {
        val video = videoList[position]

        val sharedPrefs = context.getSharedPreferences("RESUME_INFO", Context.MODE_PRIVATE)
        val durPrefs = context.getSharedPreferences("SECURE_DUR", Context.MODE_PRIVATE)
        val resumePos = sharedPrefs.getLong(video.path, 0L)
        val secureDur = durPrefs.getLong(video.path, 0L)
        val duration = if (secureDur > 0) secureDur else video.duration
        
        if (resumePos > 1000 && duration > 0) {
            holder.progress.visibility = View.VISIBLE
            holder.progress.max = duration.toInt()
            holder.progress.progress = resumePos.toInt()
        } else {
            holder.progress.visibility = View.GONE
        }

        val playPrefs = context.getSharedPreferences("PLAY_INFO", Context.MODE_PRIVATE)
        val lastId = playPrefs.getLong("LAST_ID", -1L)

        if (video.id == lastId) {
            holder.title.setTextColor(Color.parseColor("#FF5252"))
            holder.title.text = "▶ ${video.displayTitle}"
        } else {
            holder.title.setTextColor(Color.WHITE)
            holder.title.text = video.displayTitle
        }

        holder.duration.text = video.formattedDuration
        holder.size.text = video.formattedSize

        val settingsPrefs = context.getSharedPreferences("SETTINGS", Context.MODE_PRIVATE)
        val currentSig = settingsPrefs.getString("SECURITY_SIGNATURE", "HAMZA") ?: "HAMZA"

        holder.thumbJob?.cancel()
        holder.thumbJob = adapterScope.launch {
            val isEncrypted = withContext(Dispatchers.IO) { VideoFile.isFileEncrypted(context, video.path) }
            
            // 🔥 Fix for HEVC 10-bit Thumbnails:
            // Use MediaStore URI for normal videos so Glide can fetch the system-cached thumbnail.
            // File path often fails for HEVC 10-bit on many devices.
            val thumbSource: Any = if (isEncrypted) {
                Uri.parse("content://com.yourcompany.videoplayer.decryption${video.path}")
            } else {
                ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, video.id)
            }

            Glide.with(context)
                .asBitmap()
                .load(thumbSource)
                .signature(ObjectKey("${video.path}_${currentSig}_$isEncrypted"))
                .apply(
                    RequestOptions()
                        .placeholder(R.drawable.ic_video)
                        .error(R.drawable.ic_video)
                        .centerCrop()
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .override(300, 300)
                )
                .into(holder.thumbnail)
        }

        holder.itemView.setOnClickListener {
            val anim = AnimationUtils.loadAnimation(context, R.anim.click_shrink)
            it.startAnimation(anim)
            playPrefs.edit().putLong("LAST_ID", video.id).apply()
            
            it.postDelayed({
                val intent = Intent(context, PlayerActivity::class.java).apply {
                    putExtra("VIDEO_LIST", videoList)
                    putExtra("POSITION", holder.adapterPosition)
                }
                context.startActivity(intent)
            }, 100)
        }

        holder.menu.setOnClickListener {
            val popup = PopupMenu(context, holder.menu)
            popup.menuInflater.inflate(R.menu.video_menu, popup.menu)
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.rename -> { showRenameDialog(video); true }
                    R.id.delete -> { deleteVideoRequest(video); true }
                    else -> false
                }
            }
            popup.show()
        }
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
                    val ext = video.path.substringAfterLast(".", "mp4")
                    val finalName = "$newName.$ext"
                    performRename(video.id, finalName)
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun performRename(videoId: Long, finalName: String) {
        val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, videoId)
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, finalName)
        }
        try {
            val updated = context.contentResolver.update(uri, values, null, null)
            if (updated > 0) {
                Toast.makeText(context, "Renamed!", Toast.LENGTH_SHORT).show()
                refreshData()
            }
        } catch (e: SecurityException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pendingRenameUri = uri
                pendingNewName = finalName
                val pendingIntent = MediaStore.createWriteRequest(context.contentResolver, listOf(uri))
                (context as Activity).startIntentSenderForResult(pendingIntent.intentSender, 124, null, 0, 0, 0)
            }
        }
    }

    private fun deleteVideoRequest(video: VideoFile) {
        val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, video.id)
        AlertDialog.Builder(context, R.style.WhiteDialog)
            .setTitle("Delete Video")
            .setMessage("Are you sure you want to delete this video?")
            .setPositiveButton("Delete") { _, _ ->
                try {
                    context.contentResolver.delete(uri, null, null)
                    Toast.makeText(context, "Deleted", Toast.LENGTH_SHORT).show()
                    refreshData()
                } catch (e: SecurityException) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val pendingIntent = MediaStore.createDeleteRequest(context.contentResolver, listOf(uri))
                        (context as Activity).startIntentSenderForResult(pendingIntent.intentSender, 123, null, 0, 0, 0)
                    }
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun refreshData() {
        (context as Activity).runOnUiThread {
            if (context is VideoListActivity) {
                context.loadVideos()
            }
        }
    }

    override fun getItemCount() = videoList.size
    
    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        adapterScope.cancel()
    }
}
