package com.yourcompany.videoplayer

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.signature.ObjectKey
import com.google.android.material.imageview.ShapeableImageView
import java.io.File

class SuggestedVideoAdapter(
    private var videoList: List<VideoFile>,
    private var encryptionMap: Map<String, Boolean>,
    private val onVideoClick: (VideoFile) -> Unit
) : RecyclerView.Adapter<SuggestedVideoAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumbnail: ShapeableImageView = view.findViewById(R.id.suggestedThumbnail)
        val title: TextView = view.findViewById(R.id.suggestedTitle)
        val watchProgress: ProgressBar = view.findViewById(R.id.suggestedWatchProgress)
    }

    fun updateData(newList: List<VideoFile>, newMap: Map<String, Boolean>) {
        this.videoList = newList
        this.encryptionMap = newMap
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_suggested_video, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val video = videoList[position]
        holder.title.text = video.title

        val context = holder.itemView.context
        
        // Watch Progress Logic
        val resumePrefs = context.getSharedPreferences("RESUME_INFO", Context.MODE_PRIVATE)
        val resumePos = resumePrefs.getLong(video.path, 0L)
        val durPrefs = context.getSharedPreferences("SECURE_DUR", Context.MODE_PRIVATE)
        val savedDur = if (video.duration > 0) video.duration else durPrefs.getLong(video.path, 0L)

        if (resumePos > 1000 && savedDur > 0) {
            holder.watchProgress.visibility = View.VISIBLE
            holder.watchProgress.max = savedDur.toInt()
            holder.watchProgress.progress = resumePos.toInt()
        } else {
            holder.watchProgress.visibility = View.GONE
        }
        
        val isEncrypted = encryptionMap[video.path] ?: false
        
        // 🔥 Fix for HEVC 10-bit Thumbnails in Suggested List:
        // Use MediaStore URI for normal videos to leverage system thumbnails.
        val thumbSource: Any = if (isEncrypted) {
            EncryptedVideoUri.fromPath(video.path)
        } else {
            ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, video.id)
        }

        Glide.with(holder.thumbnail)
            .asBitmap()
            .load(thumbSource)
            .signature(ObjectKey("${video.path}_${File(video.path).lastModified()}_$isEncrypted"))
            .apply(
                RequestOptions()
                    .placeholder(R.drawable.ic_video)
                    .error(R.drawable.ic_video)
                    .centerCrop()
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .override(300, 200)
            )
            .into(holder.thumbnail)

        holder.itemView.setOnClickListener { onVideoClick(video) }
    }

    override fun getItemCount(): Int = videoList.size
}
