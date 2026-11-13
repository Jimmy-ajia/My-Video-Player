package com.yourcompany.videoplayer

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.signature.ObjectKey
import com.google.android.material.card.MaterialCardView
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
    private val onEncryptionToggle: (VideoFile, VideoFile.EncryptionType) -> Unit,
    private val onLongPress: (VideoFile) -> Unit,
    private val onSelectionToggle: (VideoFile) -> Unit,
    private val onDeleteClick: (VideoFile) -> Unit
) : ListAdapter<VideoFile, MainVideoAdapter.VideoHolder>(DIFF_CALLBACK) {

    private val adapterScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val encryptionCache = ConcurrentHashMap<String, CachedEncryptionState>()
    private var managementMode = false
    private var selectedPaths: Set<String> = emptySet()
    private var revealedDeletePath: String? = null
    private var recentPath = context.getSharedPreferences("PLAY_INFO", Context.MODE_PRIVATE)
        .getString("LAST_PATH", "")

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoHolder =
        VideoHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_main_video, parent, false)
        )

    override fun onBindViewHolder(holder: VideoHolder, position: Int, payloads: MutableList<Any>) {
        val video = getItem(position)
        if (payloads.contains(HIGHLIGHT_PAYLOAD)) {
            bindRecentHighlight(holder, video)
        }
        if (payloads.contains(MANAGEMENT_PAYLOAD) || payloads.contains(DELETE_REVEAL_PAYLOAD)) {
            bindManagementState(holder, video)
        }
        val handledPayloads = payloads.isNotEmpty() &&
            payloads.all {
                it == HIGHLIGHT_PAYLOAD || it == MANAGEMENT_PAYLOAD || it == DELETE_REVEAL_PAYLOAD
            }
        if (!handledPayloads) {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun onBindViewHolder(holder: VideoHolder, position: Int) {
        val video = getItem(position)
        holder.boundPath = video.path
        holder.title.text = video.displayTitle
        bindRecentHighlight(holder, video)
        holder.thumbnail.setImageResource(R.drawable.ic_video)
        holder.encryptionState.text = "检查加密状态…"
        holder.encryptionToggle.isEnabled = false
        holder.encryptionToggle.setImageResource(R.drawable.ic_unlock)
        holder.encryptionToggle.setOnClickListener {
            if (holder.bindingAdapterPosition != RecyclerView.NO_POSITION) {
                onEncryptionToggle(video, holder.encryptionType)
            }
        }
        holder.deleteButton.setOnClickListener {
            if (holder.bindingAdapterPosition != RecyclerView.NO_POSITION) {
                onDeleteClick(video)
            }
        }
        holder.itemView.setOnClickListener {
            if (holder.bindingAdapterPosition != RecyclerView.NO_POSITION) {
                if (managementMode) onSelectionToggle(video) else onVideoClick(video)
            }
        }
        holder.itemView.setOnLongClickListener {
            if (holder.bindingAdapterPosition == RecyclerView.NO_POSITION || managementMode) {
                false
            } else {
                onLongPress(video)
                true
            }
        }
        holder.selectionCheckbox.setOnClickListener {
            if (holder.bindingAdapterPosition != RecyclerView.NO_POSITION) {
                onSelectionToggle(video)
            }
        }
        bindManagementState(holder, video)

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

    fun refreshRecentPlaybackHighlight() {
        val updatedPath = context.getSharedPreferences("PLAY_INFO", Context.MODE_PRIVATE)
            .getString("LAST_PATH", "")
        if (updatedPath == recentPath) return

        val oldPath = recentPath
        recentPath = updatedPath
        listOf(oldPath, updatedPath).forEach { path ->
            val position = currentList.indexOfFirst { it.path == path }
            if (position >= 0) notifyItemChanged(position, HIGHLIGHT_PAYLOAD)
        }
    }

    fun updateManagementState(enabled: Boolean, selectedPaths: Set<String>) {
        val modeChanged = managementMode != enabled
        val changedPaths = this.selectedPaths.union(selectedPaths)
            .filter { (it in this.selectedPaths) != (it in selectedPaths) }
        val previousReveal = revealedDeletePath
        managementMode = enabled
        this.selectedPaths = selectedPaths.toSet()
        if (enabled) revealedDeletePath = null

        if (modeChanged) {
            notifyDataSetChanged()
        } else {
            (changedPaths + listOfNotNull(previousReveal, revealedDeletePath))
                .distinct()
                .forEach { path ->
                    val position = currentList.indexOfFirst { it.path == path }
                    if (position >= 0) notifyItemChanged(position, MANAGEMENT_PAYLOAD)
                }
        }
    }

    fun revealDelete(path: String) {
        if (managementMode || revealedDeletePath == path) return
        val previousPath = revealedDeletePath
        revealedDeletePath = path
        listOfNotNull(previousPath, path).forEach { changedPath ->
            val position = currentList.indexOfFirst { it.path == changedPath }
            if (position >= 0) notifyItemChanged(position, DELETE_REVEAL_PAYLOAD)
        }
    }

    fun closeDeleteReveal() {
        val path = revealedDeletePath ?: return
        revealedDeletePath = null
        val position = currentList.indexOfFirst { it.path == path }
        if (position >= 0) notifyItemChanged(position, DELETE_REVEAL_PAYLOAD)
    }

    fun isDeleteRevealed(path: String): Boolean = revealedDeletePath == path

    private fun bindManagementState(holder: VideoHolder, video: VideoFile) {
        holder.encryptionToggle.visibility = if (managementMode) View.GONE else View.VISIBLE
        holder.selectionCheckbox.visibility = if (managementMode) View.VISIBLE else View.GONE
        holder.selectionCheckbox.isChecked = video.path in selectedPaths
        holder.deleteButton.visibility =
            if (!managementMode && revealedDeletePath == video.path) View.VISIBLE else View.GONE
        holder.card.translationX = if (!managementMode && revealedDeletePath == video.path) {
            -holder.deleteButton.width.toFloat().coerceAtLeast(
                84 * holder.itemView.resources.displayMetrics.density
            )
        } else {
            0f
        }
    }

    private fun bindRecentHighlight(holder: VideoHolder, video: VideoFile) {
        val isRecent = video.path == recentPath
        val density = holder.itemView.resources.displayMetrics.density
        val context = holder.itemView.context
        holder.card.strokeColor = ContextCompat.getColor(
            context,
            if (isRecent) R.color.color_primary else R.color.color_outline
        )
        holder.card.strokeWidth = (if (isRecent) 2 * density else density).toInt()
        holder.title.setTextColor(
            ContextCompat.getColor(
                context,
                if (isRecent) R.color.color_primary else R.color.text_primary
            )
        )
    }

    override fun onViewRecycled(holder: VideoHolder) {
        holder.bindJob?.cancel()
        Glide.with(holder.thumbnail).clear(holder.thumbnail)
        holder.boundPath = null
        holder.card.translationX = 0f
        super.onViewRecycled(holder)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        adapterScope.cancel()
    }

    class VideoHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView = view.findViewById(R.id.main_video_card)
        val thumbnail: ShapeableImageView = view.findViewById(R.id.main_video_thumbnail)
        val title: TextView = view.findViewById(R.id.main_video_title)
        val encryptionState: TextView = view.findViewById(R.id.main_video_encryption_state)
        val encryptionToggle: ImageButton = view.findViewById(R.id.main_video_encryption_toggle)
        val selectionCheckbox: CheckBox = view.findViewById(R.id.main_video_selection_checkbox)
        val deleteButton: View = view.findViewById(R.id.main_video_delete)
        var encryptionType: VideoFile.EncryptionType = VideoFile.EncryptionType.NONE
        var boundPath: String? = null
        var bindJob: Job? = null
    }

    companion object {
        private const val HIGHLIGHT_PAYLOAD = "recent_playback_highlight"
        private const val MANAGEMENT_PAYLOAD = "management_selection"
        private const val DELETE_REVEAL_PAYLOAD = "delete_reveal"

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
