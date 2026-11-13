package com.yourcompany.videoplayer

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.Toolbar
import androidx.activity.viewModels
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.activity.OnBackPressedCallback
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class MainActivity : AppCompatActivity() {
    private val videoLibraryViewModel: VideoLibraryViewModel by viewModels()
    private lateinit var recyclerView: RecyclerView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var videoAdapter: MainVideoAdapter
    private lateinit var emptyMessage: TextView
    private lateinit var managementToolbar: View
    private lateinit var managementCount: TextView
    private lateinit var operationPanel: View
    private lateinit var operationText: TextView
    private lateinit var operationProgress: ProgressBar
    private var scanDialog: AlertDialog? = null
    private var isConverting = false
    private var isCheckingForPlaintext = false
    private var managementMode = false
    private val selectedVideoPaths = linkedSetOf<String>()
    private lateinit var managementBackCallback: OnBackPressedCallback

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<Toolbar>(R.id.toolbar_main)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        findViewById<TextView>(R.id.toolbar_title).text = "Videos"
        findViewById<ImageView>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<ImageView>(R.id.btn_sort).setOnClickListener(::showSortMenu)

        recyclerView = findViewById(R.id.videoRecyclerView)
        swipeRefresh = findViewById(R.id.video_swipe_refresh)
        swipeRefresh.setOnRefreshListener {
            if (isConverting || !hasPermission()) {
                swipeRefresh.isRefreshing = false
            } else {
                loadContent()
            }
        }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.setHasFixedSize(true)
        videoAdapter = MainVideoAdapter(
            context = this,
            onVideoClick = ::playVideo,
            onEncryptionToggle = ::requestEncryptionToggle,
            onLongPress = ::enterManagementMode,
            onSelectionToggle = ::toggleVideoSelection,
            onDeleteClick = ::confirmDeleteVideo
        )
        recyclerView.adapter = videoAdapter
        attachSwipeActions()

        emptyMessage = findViewById(R.id.video_list_empty)
        managementToolbar = findViewById(R.id.main_management_toolbar)
        managementCount = findViewById(R.id.main_management_count)
        operationPanel = findViewById(R.id.video_operation_panel)
        operationText = findViewById(R.id.video_operation_text)
        operationProgress = findViewById(R.id.video_operation_progress)
        findViewById<View>(R.id.main_management_select_all).setOnClickListener {
            if (selectedVideoPaths.size == videoAdapter.currentList.size) {
                selectedVideoPaths.clear()
            } else {
                selectedVideoPaths.clear()
                selectedVideoPaths.addAll(videoAdapter.currentList.map(VideoFile::path))
            }
            updateManagementUi()
        }
        findViewById<View>(R.id.main_management_encrypt).setOnClickListener {
            confirmBatchConversion(encrypting = true)
        }
        findViewById<View>(R.id.main_management_decrypt).setOnClickListener {
            confirmBatchConversion(encrypting = false)
        }
        findViewById<View>(R.id.main_management_delete).setOnClickListener {
            confirmBatchDelete()
        }
        findViewById<View>(R.id.main_management_done).setOnClickListener { exitManagementMode() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                exitManagementMode()
            }
        }.also { callback ->
            managementBackCallback = callback
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { videoLibraryViewModel.videos.collect(::displayVideos) }
                launch { videoLibraryViewModel.scanState.collect(::displayScanState) }
            }
        }

        checkPermissions()
    }

    override fun onResume() {
        super.onResume()
        if (hasPermission()) {
            videoAdapter.refreshRecentPlaybackHighlight()
            autoScanOnFirstStartup()
        } else {
            emptyMessage.text = "请授予存储访问权限以扫描视频目录。"
            emptyMessage.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        }
    }

    private fun hasPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
        } else if (!hasPermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                REQUEST_STORAGE_PERMISSION
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATION_PERMISSION
            )
        }
    }

    fun loadContent() {
        if (isConverting || !hasPermission()) return
        videoLibraryViewModel.refreshVideos()
    }

    private fun autoScanOnFirstStartup() {
        val preferences = getSharedPreferences(SCAN_PREFERENCES, Context.MODE_PRIVATE)
        if (preferences.getBoolean(AUTO_SCAN_ATTEMPTED_KEY, false)) return
        preferences.edit().putBoolean(AUTO_SCAN_ATTEMPTED_KEY, true).apply()
        loadContent()
    }

    private fun displayVideos(videos: List<VideoFile>) {
        selectedVideoPaths.retainAll(videos.mapTo(mutableSetOf(), VideoFile::path))
        videoAdapter.submitList(videos) {
            if (managementMode) updateManagementUi()
        }
        val hasVideos = videos.isNotEmpty()
        emptyMessage.text = if (hasVideos) "" else "所选目录中没有找到视频。"
        emptyMessage.visibility = if (hasVideos) View.GONE else View.VISIBLE
        recyclerView.visibility = if (hasVideos) View.VISIBLE else View.GONE
    }

    private fun displayScanState(state: VideoScanState) {
        when (state) {
            VideoScanState.Idle, VideoScanState.Complete -> {
                swipeRefresh.isRefreshing = false
                dismissScanDialog()
            }
            VideoScanState.Scanning -> {
                swipeRefresh.isRefreshing = true
                showScanDialog()
            }
            is VideoScanState.Failed -> {
                swipeRefresh.isRefreshing = false
                dismissScanDialog()
                Toast.makeText(this, "扫描视频失败：${state.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showScanDialog() {
        if (scanDialog?.isShowing == true) return

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
        }
        content.addView(TextView(this).apply {
            text = "视频加载中…"
            textSize = 16f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
        })
        content.addView(
            ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = true
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(4)
            ).apply {
                topMargin = dp(14)
            }
        )
        scanDialog = AlertDialog.Builder(this)
            .setView(content)
            .setCancelable(false)
            .create()
            .also(AlertDialog::show)
    }

    private fun dismissScanDialog() {
        scanDialog?.dismiss()
        scanDialog = null
    }

    private fun showSortMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        VideoSortOrder.entries.forEachIndexed { index, sortOrder ->
            popup.menu.add(0, index, index, sortOrder.label).isCheckable = true
        }
        popup.menu.setGroupCheckable(0, true, true)
        popup.menu.findItem(videoLibraryViewModel.sortOrder.value.ordinal)?.isChecked = true
        popup.setOnMenuItemClickListener { item ->
            VideoSortOrder.entries.getOrNull(item.itemId)?.let(videoLibraryViewModel::setSortOrder)
            true
        }
        popup.show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun attachSwipeActions() {
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            0,
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun getSwipeDirs(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int {
                if (managementMode) return 0
                val holder = viewHolder as? MainVideoAdapter.VideoHolder ?: return 0
                val path = holder.boundPath ?: return 0
                return if (videoAdapter.isDeleteRevealed(path)) {
                    ItemTouchHelper.RIGHT
                } else {
                    ItemTouchHelper.LEFT
                }
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float = 0.15f

            override fun onChildDraw(
                canvas: Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                val holder = viewHolder as? MainVideoAdapter.VideoHolder ?: return
                val path = holder.boundPath ?: return
                val revealWidth = dp(88).toFloat()
                val start = if (videoAdapter.isDeleteRevealed(path)) -revealWidth else 0f
                val delta = if (start < 0f) dX.coerceAtLeast(0f) else dX.coerceAtMost(0f)
                holder.card.translationX = (start + delta).coerceIn(-revealWidth, 0f)
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                val holder = viewHolder as? MainVideoAdapter.VideoHolder ?: return
                val path = holder.boundPath ?: return
                holder.card.translationX =
                    if (videoAdapter.isDeleteRevealed(path)) -dp(88).toFloat() else 0f
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val holder = viewHolder as? MainVideoAdapter.VideoHolder ?: return
                val path = holder.boundPath ?: return
                if (direction == ItemTouchHelper.LEFT) {
                    videoAdapter.revealDelete(path)
                } else {
                    videoAdapter.closeDeleteReveal()
                }
            }
        }).attachToRecyclerView(recyclerView)
    }

    private fun enterManagementMode(video: VideoFile) {
        managementMode = true
        selectedVideoPaths.clear()
        selectedVideoPaths.add(video.path)
        videoAdapter.closeDeleteReveal()
        updateManagementUi()
    }

    private fun toggleVideoSelection(video: VideoFile) {
        if (!managementMode) return
        if (!selectedVideoPaths.add(video.path)) selectedVideoPaths.remove(video.path)
        updateManagementUi()
    }

    private fun exitManagementMode() {
        managementMode = false
        selectedVideoPaths.clear()
        updateManagementUi()
    }

    private fun updateManagementUi() {
        managementToolbar.visibility = if (managementMode) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btn_sort).visibility = if (managementMode) View.GONE else View.VISIBLE
        findViewById<View>(R.id.btn_settings).visibility = if (managementMode) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.toolbar_title).text = if (managementMode) {
            "视频管理"
        } else {
            "Videos"
        }
        managementCount.text = "已选 ${selectedVideoPaths.size} 项"
        managementBackCallback.isEnabled = managementMode
        findViewById<TextView>(R.id.main_management_select_all).text =
            if (videoAdapter.currentList.isNotEmpty() &&
                selectedVideoPaths.size == videoAdapter.currentList.size
            ) {
                "取消全选"
            } else {
                "全选"
            }
        videoAdapter.updateManagementState(managementMode, selectedVideoPaths)
    }

    private fun selectedVideos(): List<VideoFile> =
        videoAdapter.currentList.filter { it.path in selectedVideoPaths }

    private fun confirmDeleteVideo(video: VideoFile) {
        AlertDialog.Builder(this)
            .setTitle("删除视频")
            .setMessage("确定删除“${video.displayTitle}”？此操作无法撤销。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                videoAdapter.closeDeleteReveal()
                deleteVideos(listOf(video))
            }
            .show()
    }

    private fun confirmBatchDelete() {
        val videos = selectedVideos()
        if (videos.isEmpty()) {
            Toast.makeText(this, "请先选择视频。", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("批量删除")
            .setMessage("确定删除所选的 ${videos.size} 个视频？此操作无法撤销。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ -> deleteVideos(videos) }
            .show()
    }

    private fun confirmBatchConversion(encrypting: Boolean) {
        val videos = selectedVideos()
        if (videos.isEmpty()) {
            Toast.makeText(this, "请先选择视频。", Toast.LENGTH_SHORT).show()
            return
        }
        val action = actionName(encrypting)
        AlertDialog.Builder(this)
            .setTitle("批量$action")
            .setMessage("将检查所选视频并转换支持的文件。原始文件会保留，不覆盖已有文件。")
            .setNegativeButton("取消", null)
            .setPositiveButton(action) { _, _ -> convertVideos(videos, encrypting) }
            .show()
    }

    private fun convertVideos(videos: List<VideoFile>, encrypting: Boolean) {
        if (isConverting) return
        isConverting = true
        operationPanel.visibility = View.VISIBLE
        operationProgress.progress = 0

        lifecycleScope.launch {
            var convertedCount = 0
            val issues = mutableListOf<String>()
            try {
                val typedVideos = withContext(Dispatchers.IO) {
                    videos.map { video ->
                        video to VideoFile.encryptionType(applicationContext, video.path)
                    }
                }
                val targetType = if (encrypting) {
                    VideoFile.EncryptionType.NONE
                } else {
                    VideoFile.EncryptionType.CURRENT
                }
                val eligibleVideos = typedVideos.filter { it.second == targetType }
                typedVideos.filter { it.second != targetType }.forEach { (video, type) ->
                    val reason = if (type == VideoFile.EncryptionType.LEGACY) {
                        "旧格式加密文件不支持转换"
                    } else if (encrypting) {
                        "已经加密"
                    } else {
                        "尚未加密"
                    }
                    issues += "${video.displayTitle}：$reason"
                }

                eligibleVideos.forEachIndexed { index, (video, _) ->
                    operationText.text =
                        "正在${actionName(encrypting)} (${index + 1}/${eligibleVideos.size})：${video.displayTitle}"
                    try {
                        val output = withContext(Dispatchers.IO) {
                            val onProgress: (Int) -> Unit = { progress ->
                                runOnUiThread {
                                    operationProgress.progress =
                                        ((index * 100 + progress) / eligibleVideos.size)
                                            .coerceIn(0, 100)
                                }
                            }
                            val source = File(video.path)
                            if (encrypting) {
                                VideoFileConverter.encrypt(applicationContext, source, onProgress)
                            } else {
                                VideoFileConverter.decrypt(applicationContext, source, onProgress)
                            }
                        }
                        MediaScannerConnection.scanFile(
                            this@MainActivity,
                            arrayOf(output.absolutePath),
                            arrayOf(EncryptedMediaMimeType.fromExtension(output.extension)),
                            null
                        )
                        convertedCount++
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        issues += "${video.displayTitle}：${error.message ?: "未知错误"}"
                    }
                }
                showOperationResult("${actionName(encrypting)}结果", convertedCount, issues)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    "批量${actionName(encrypting)}失败：${error.message ?: "未知错误"}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                isConverting = false
                operationPanel.visibility = View.GONE
                if (hasPermission()) loadContent()
            }
        }
    }

    private fun deleteVideos(videos: List<VideoFile>) {
        if (isConverting) return
        isConverting = true
        operationPanel.visibility = View.VISIBLE
        operationText.text = "正在删除 ${videos.size} 个视频…"
        operationProgress.progress = 0

        lifecycleScope.launch {
            var deletedCount = 0
            val issues = mutableListOf<String>()
            try {
                val results = withContext(Dispatchers.IO) {
                    videos.mapIndexed { index, video ->
                        val issue = try {
                            val file = File(video.path)
                            if (!file.isFile ||
                                file.length() != video.size ||
                                (video.dateModified > 0L &&
                                    file.lastModified() / 1000 != video.dateModified)
                            ) {
                                throw IOException("文件不存在或已变化，未删除。")
                            }
                            if (!file.delete()) throw IOException("系统拒绝删除此文件。")
                            null
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            error.message ?: "未知错误"
                        }
                        runOnUiThread {
                            operationProgress.progress = ((index + 1) * 100 / videos.size)
                        }
                        video to issue
                    }
                }
                results.forEach { (video, issue) ->
                    if (issue == null) {
                        deletedCount++
                        clearPlaybackInfo(video.path)
                    } else {
                        issues += "${video.displayTitle}：$issue"
                    }
                }
                videoAdapter.closeDeleteReveal()
                showOperationResult("删除结果", deletedCount, issues)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    "删除失败：${error.message ?: "未知错误"}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                isConverting = false
                operationPanel.visibility = View.GONE
                if (hasPermission()) loadContent()
            }
        }
    }

    private fun clearPlaybackInfo(path: String) {
        getSharedPreferences("RESUME_INFO", Context.MODE_PRIVATE)
            .edit()
            .remove(path)
            .apply()
        val playbackPreferences = getSharedPreferences("PLAY_INFO", Context.MODE_PRIVATE)
        if (playbackPreferences.getString("LAST_PATH", null) == path) {
            playbackPreferences.edit()
                .remove("LAST_PATH")
                .remove("LAST_ID")
                .apply()
        }
    }

    private fun showOperationResult(title: String, successCount: Int, issues: List<String>) {
        val message = buildString {
            append("成功：$successCount 项")
            if (issues.isNotEmpty()) {
                append("\n未处理：${issues.size} 项")
                issues.take(MAX_OPERATION_ISSUES).forEach { append("\n• $it") }
                if (issues.size > MAX_OPERATION_ISSUES) {
                    append("\n另有 ${issues.size - MAX_OPERATION_ISSUES} 项，请检查文件后重试。")
                }
            }
        }
        if (issues.isEmpty()) {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        } else {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("确定", null)
                .show()
        }
    }

    private fun requestEncryptionToggle(
        video: VideoFile,
        encryptionType: VideoFile.EncryptionType
    ) {
        if (encryptionType == VideoFile.EncryptionType.LEGACY) {
            Toast.makeText(this, "此旧格式加密文件暂不支持转换。", Toast.LENGTH_LONG).show()
            return
        }
        val encrypting = encryptionType == VideoFile.EncryptionType.NONE
        if (!encrypting) {
            checkForExistingPlaintext(video)
            return
        }

        showConversionConfirmation(video, encrypting = true)
    }

    private fun checkForExistingPlaintext(video: VideoFile) {
        if (isCheckingForPlaintext || isConverting) return
        isCheckingForPlaintext = true
        lifecycleScope.launch {
            try {
                val plaintext = withContext(Dispatchers.IO) {
                    VideoFileConverter.existingPlaintextFor(applicationContext, File(video.path))
                }
                if (plaintext == null) {
                    showConversionConfirmation(video, encrypting = false)
                } else {
                    showExistingPlaintextDialog(video, plaintext)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    "无法检查原视频：${error.message ?: "未知错误"}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                isCheckingForPlaintext = false
            }
        }
    }

    private fun showConversionConfirmation(video: VideoFile, encrypting: Boolean) {
        val action = if (encrypting) "加密" else "解密"
        val retention = if (encrypting) "原始明文视频会保留。" else "原加密文件会保留。"
        AlertDialog.Builder(this)
            .setTitle("${action}视频")
            .setMessage("将在源目录生成转换后的文件，不覆盖已有文件。$retention")
            .setNegativeButton("取消", null)
            .setPositiveButton(action) { _, _ -> convertVideo(video, encrypting) }
            .show()
    }

    private fun showExistingPlaintextDialog(video: VideoFile, plaintext: File) {
        AlertDialog.Builder(this)
            .setTitle("已存在原视频")
            .setMessage(
                "已找到预计的原视频：${plaintext.name}。确认后只删除加密文件 ${File(video.path).name}，" +
                    "保留该明文文件且不再解密。应用根据同目录文件名和原扩展名匹配，无法验证两者内容完全一致。"
            )
            .setNegativeButton("保留两者", null)
            .setPositiveButton("删除加密文件") { _, _ ->
                deleteEncryptedDuplicate(video, plaintext)
            }
            .show()
    }

    private fun deleteEncryptedDuplicate(video: VideoFile, expectedPlaintext: File) {
        if (isConverting) return
        isConverting = true
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val encrypted = File(video.path)
                    if (!encrypted.isFile ||
                        encrypted.length() != video.size ||
                        !EncryptedMediaContainer.isContainer(encrypted)
                    ) {
                        throw IOException("加密文件已变化，请重新扫描后再试。")
                    }
                    val currentPlaintext = VideoFileConverter.existingPlaintextFor(
                        applicationContext,
                        encrypted
                    )
                    if (currentPlaintext?.canonicalFile != expectedPlaintext.canonicalFile) {
                        throw IOException("原视频已不存在或文件状态已变化；加密文件未删除。")
                    }
                    if (!encrypted.delete()) {
                        throw IOException("无法删除加密文件；原视频未受影响。")
                    }
                }
                Toast.makeText(this@MainActivity, "已删除加密副本，原视频保留。", Toast.LENGTH_LONG).show()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    "删除加密文件失败：${error.message ?: "未知错误"}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                isConverting = false
                if (hasPermission()) loadContent()
            }
        }
    }

    private fun convertVideo(video: VideoFile, encrypting: Boolean) {
        if (isConverting) return
        isConverting = true
        operationPanel.visibility = View.VISIBLE
        operationText.text = "${if (encrypting) "正在加密" else "正在解密"}：${video.displayTitle}"
        operationProgress.progress = 0

        lifecycleScope.launch {
            try {
                val source = File(video.path)
                val output = withContext(Dispatchers.IO) {
                    val onProgress: (Int) -> Unit = { progress ->
                        runOnUiThread {
                            operationProgress.progress = progress
                        }
                    }
                    if (encrypting) {
                        VideoFileConverter.encrypt(applicationContext, source, onProgress)
                    } else {
                        VideoFileConverter.decrypt(applicationContext, source, onProgress)
                    }
                }
                MediaScannerConnection.scanFile(
                    this@MainActivity,
                    arrayOf(output.absolutePath),
                    arrayOf(EncryptedMediaMimeType.fromExtension(output.extension)),
                    null
                )
                Toast.makeText(this@MainActivity, "${actionName(encrypting)}完成：${output.name}", Toast.LENGTH_LONG).show()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    "${actionName(encrypting)}失败：${error.message ?: "未知错误"}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                isConverting = false
                operationPanel.visibility = View.GONE
                if (hasPermission()) loadContent()
            }
        }
    }

    private fun actionName(encrypting: Boolean): String =
        if (encrypting) "加密" else "解密"

    private fun playVideo(video: VideoFile) {
        val videos = ArrayList(videoAdapter.currentList)
        val position = videos.indexOfFirst { it.path == video.path }
        if (position < 0) return

        getSharedPreferences("PLAY_INFO", Context.MODE_PRIVATE)
            .edit()
            .putLong("LAST_ID", video.id)
            .putString("LAST_PATH", video.path)
            .apply()
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra("VIDEO_LIST", videos)
            putExtra("POSITION", position)
        })
    }

    override fun onDestroy() {
        dismissScanDialog()
        super.onDestroy()
    }

    companion object {
        private const val MAX_OPERATION_ISSUES = 8
        private const val SCAN_PREFERENCES = "VIDEO_SCAN"
        private const val AUTO_SCAN_ATTEMPTED_KEY = "INITIAL_AUTO_SCAN_ATTEMPTED"
        private const val REQUEST_STORAGE_PERMISSION = 101
        private const val REQUEST_NOTIFICATION_PERMISSION = 103
    }
}
