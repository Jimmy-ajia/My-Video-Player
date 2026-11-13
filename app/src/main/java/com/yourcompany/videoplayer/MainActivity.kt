package com.yourcompany.videoplayer

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.recyclerview.widget.LinearLayoutManager
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
    private lateinit var operationPanel: View
    private lateinit var operationText: TextView
    private lateinit var operationProgress: ProgressBar
    private var scanDialog: AlertDialog? = null
    private var isConverting = false
    private var isCheckingForPlaintext = false

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
            onEncryptionToggle = ::requestEncryptionToggle
        )
        recyclerView.adapter = videoAdapter

        emptyMessage = findViewById(R.id.video_list_empty)
        operationPanel = findViewById(R.id.video_operation_panel)
        operationText = findViewById(R.id.video_operation_text)
        operationProgress = findViewById(R.id.video_operation_progress)

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
        videoAdapter.submitList(videos)
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
        private const val SCAN_PREFERENCES = "VIDEO_SCAN"
        private const val AUTO_SCAN_ATTEMPTED_KEY = "INITIAL_AUTO_SCAN_ATTEMPTED"
        private const val REQUEST_STORAGE_PERMISSION = 101
        private const val REQUEST_NOTIFICATION_PERMISSION = 103
    }
}
