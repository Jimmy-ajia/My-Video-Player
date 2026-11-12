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
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var recyclerView: RecyclerView
    private lateinit var videoAdapter: MainVideoAdapter
    private lateinit var emptyMessage: TextView
    private lateinit var operationPanel: View
    private lateinit var operationText: TextView
    private lateinit var operationProgress: ProgressBar
    private lateinit var fabResume: ExtendedFloatingActionButton
    private var isLoading = false
    private var isConverting = false

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

        recyclerView = findViewById(R.id.videoRecyclerView)
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
        fabResume = findViewById(R.id.fab_last_played)
        fabResume.setOnClickListener { playLastWatched() }

        checkPermissions()
    }

    override fun onResume() {
        super.onResume()
        if (hasPermission()) {
            loadContent()
            updateResumeFab()
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
        if (isLoading || isConverting || !hasPermission()) return
        isLoading = true
        emptyMessage.text = "正在扫描视频目录…"
        emptyMessage.visibility = View.VISIBLE
        recyclerView.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val directories = ScanDirectoryStore.getPaths(applicationContext)
                val videos = withContext(Dispatchers.IO) {
                    VideoRepository(applicationContext).getVideosFromDirectories(directories)
                }
                videoAdapter.submitList(videos)
                val hasVideos = videos.isNotEmpty()
                emptyMessage.text = if (hasVideos) "" else "所选目录中没有找到视频。"
                emptyMessage.visibility = if (hasVideos) View.GONE else View.VISIBLE
                recyclerView.visibility = if (hasVideos) View.VISIBLE else View.GONE
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                emptyMessage.text = "扫描视频失败：${error.message ?: "未知错误"}"
                emptyMessage.visibility = View.VISIBLE
                recyclerView.visibility = View.GONE
                Toast.makeText(this@MainActivity, emptyMessage.text, Toast.LENGTH_LONG).show()
            } finally {
                isLoading = false
            }
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
        val action = if (encrypting) "加密" else "解密"
        val retention = if (encrypting) "原始明文视频会保留。" else "原加密文件会保留。"
        AlertDialog.Builder(this)
            .setTitle("${action}视频")
            .setMessage("将在源目录生成转换后的文件，不覆盖已有文件。$retention")
            .setNegativeButton("取消", null)
            .setPositiveButton(action) { _, _ -> convertVideo(video, encrypting) }
            .show()
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

    private fun updateResumeFab() {
        val lastPath = getSharedPreferences("PLAY_INFO", Context.MODE_PRIVATE)
            .getString("LAST_PATH", "")
        fabResume.visibility =
            if (!lastPath.isNullOrEmpty() && File(lastPath).isFile) View.VISIBLE else View.GONE
    }

    private fun playLastWatched() {
        val preferences = getSharedPreferences("PLAY_INFO", Context.MODE_PRIVATE)
        val lastPath = preferences.getString("LAST_PATH", "") ?: return
        val lastFile = File(lastPath)
        if (!lastFile.isFile) return

        val videos = ArrayList(videoAdapter.currentList)
        var position = videos.indexOfFirst { it.path == lastPath }
        if (position < 0) {
            videos.add(
                VideoFile(
                    id = preferences.getLong("LAST_ID", -1L),
                    path = lastPath,
                    title = lastFile.name,
                    folderName = lastFile.parentFile?.name ?: "Unknown",
                    size = lastFile.length(),
                    duration = 0
                )
            )
            position = videos.lastIndex
        }
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra("VIDEO_LIST", videos)
            putExtra("POSITION", position)
        })
    }

    companion object {
        private const val REQUEST_STORAGE_PERMISSION = 101
        private const val REQUEST_NOTIFICATION_PERMISSION = 103
    }
}
