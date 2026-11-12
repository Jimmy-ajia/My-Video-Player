package com.yourcompany.videoplayer

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.signature.ObjectKey
import com.google.android.material.imageview.ShapeableImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

internal class MainVideoAdapter(
    private val context: Context,
    private val onVideoClick: (VideoFile) -> Unit,
    private val onEncryptionToggle: (VideoFile, VideoFile.EncryptionType) -> Unit
) : ListAdapter<VideoFile, MainVideoAdapter.VideoHolder>(DIFF_CALLBACK) {

    private val adapterScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val encryptionCache = ConcurrentHashMap<String, CachedEncryptionState>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoHolder =
        VideoHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_main_video, parent, false)
        )

    override fun onBindViewHolder(holder: VideoHolder, position: Int) {
        val video = getItem(position)
        holder.boundPath = video.path
        holder.title.text = video.displayTitle
        holder.title.setTextColor(
            if (context.getSharedPreferences("PLAY_INFO", Context.MODE_PRIVATE)
                    .getString("LAST_PATH", "") == video.path
            ) Color.parseColor("#FFFF5252") else Color.WHITE
        )
        holder.thumbnail.setImageResource(R.drawable.ic_video)
        holder.encryptionState.text = "检查加密状态…"
        holder.encryptionToggle.isEnabled = false
        holder.encryptionToggle.setImageResource(R.drawable.ic_unlock)
        holder.encryptionToggle.setOnClickListener {
            if (holder.bindingAdapterPosition != RecyclerView.NO_POSITION) {
                onEncryptionToggle(video, holder.encryptionType)
            }
        }
        holder.itemView.setOnClickListener {
            if (holder.bindingAdapterPosition != RecyclerView.NO_POSITION) onVideoClick(video)
        }

        holder.bindJob?.cancel()
        holder.bindJob = adapterScope.launch {
            val encryptionType = withContext(Dispatchers.IO) {
                val file = File(video.path)
                val modified = file.lastModified()
                val size = file.length()
                encryptionCache[video.path]
                    ?.takeIf { it.modified == modified && it.size == size }
                    ?.type
                    ?: VideoFile.encryptionType(context, video.path).also { type ->
                        encryptionCache[video.path] =
                            CachedEncryptionState(modified, size, type)
                    }
            }
            if (holder.boundPath != video.path) return@launch

            holder.encryptionType = encryptionType
            val encrypted = encryptionType == VideoFile.EncryptionType.CURRENT
            val legacy = encryptionType == VideoFile.EncryptionType.LEGACY
            holder.encryptionState.text = when (encryptionType) {
                VideoFile.EncryptionType.CURRENT -> "已加密"
                VideoFile.EncryptionType.LEGACY -> "旧格式加密"
                VideoFile.EncryptionType.NONE -> "未加密"
            }
            holder.encryptionToggle.setImageResource(
                if (encryptionType == VideoFile.EncryptionType.NONE) {
                    R.drawable.ic_unlock
                } else {
                    R.drawable.ic_lock
                }
            )
            holder.encryptionToggle.contentDescription = when (encryptionType) {
                VideoFile.EncryptionType.CURRENT -> "解密视频"
                VideoFile.EncryptionType.LEGACY -> "旧格式视频暂不支持转换"
                VideoFile.EncryptionType.NONE -> "加密视频"
            }
            holder.encryptionToggle.isEnabled = !legacy

            if (!legacy) {
                val thumbnailSource: Any = if (encrypted) {
                    EncryptedVideoUri.fromPath(video.path)
                } else {
                    File(video.path)
                }
                Glide.with(holder.thumbnail)
                    .asBitmap()
                    .load(thumbnailSource)
                    .signature(ObjectKey("${video.path}_${File(video.path).lastModified()}_$encrypted"))
                    .apply(
                        RequestOptions()
                            .placeholder(R.drawable.ic_video)
                            .error(R.drawable.ic_video)
                            .centerCrop()
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .override(400, 240)
                    )
                    .into(holder.thumbnail)
            }
        }
    }

    override fun onViewRecycled(holder: VideoHolder) {
        holder.bindJob?.cancel()
        Glide.with(holder.thumbnail).clear(holder.thumbnail)
        holder.boundPath = null
        super.onViewRecycled(holder)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        adapterScope.cancel()
    }

    class VideoHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumbnail: ShapeableImageView = view.findViewById(R.id.main_video_thumbnail)
        val title: TextView = view.findViewById(R.id.main_video_title)
        val encryptionState: TextView = view.findViewById(R.id.main_video_encryption_state)
        val encryptionToggle: ImageButton = view.findViewById(R.id.main_video_encryption_toggle)
        var encryptionType: VideoFile.EncryptionType = VideoFile.EncryptionType.NONE
        var boundPath: String? = null
        var bindJob: Job? = null
    }

    companion object {
        private data class CachedEncryptionState(
            val modified: Long,
            val size: Long,
            val type: VideoFile.EncryptionType
        )

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<VideoFile>() {
            override fun areItemsTheSame(oldItem: VideoFile, newItem: VideoFile): Boolean =
                oldItem.path == newItem.path

            override fun areContentsTheSame(oldItem: VideoFile, newItem: VideoFile): Boolean =
                oldItem == newItem
        }
    }
}
