package com.yourcompany.videoplayer

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.GeneralSecurityException

class NetworkMountBrowserActivity : AppCompatActivity() {
    private lateinit var mount: NetworkMount
    private lateinit var toolbar: Toolbar
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var listView: ListView
    private lateinit var listAdapter: ArrayAdapter<String>
    private var entries: List<NetworkMountEntry> = emptyList()
    private var currentPath = ""
    private var isBusy = false
    private lateinit var backCallback: OnBackPressedCallback

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_network_mount_browser)
        toolbar = findViewById(R.id.toolbar_network_mount)
        status = findViewById(R.id.network_mount_status)
        progress = findViewById(R.id.network_mount_progress)
        listView = findViewById(R.id.network_mount_entries)
        listAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        listView.adapter = listAdapter

        val mountId = intent.getStringExtra(EXTRA_MOUNT_ID)
        if (mountId.isNullOrBlank()) {
            finishWithError("网络共享配置不存在。")
            return
        }
        mount = try {
            NetworkMountStore.get(applicationContext, mountId)
                ?: run {
                    finishWithError("网络共享已被移除。")
                    return
                }
        } catch (error: IOException) {
            finishWithError(error.message ?: "无法读取网络共享配置。")
            return
        } catch (error: GeneralSecurityException) {
            finishWithError(error.message ?: "无法解密网络共享凭据。")
            return
        } catch (error: IllegalArgumentException) {
            finishWithError(error.message ?: "网络共享配置无效。")
            return
        }

        toolbar.title = mount.name
        toolbar.setNavigationOnClickListener {
            if (currentPath.isEmpty()) finish() else navigateUp()
        }
        backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                navigateUp()
            }
        }
        onBackPressedDispatcher.addCallback(this, backCallback)
        listView.setOnItemClickListener { _, _, position, _ ->
            val entry = entries.getOrNull(position) ?: return@setOnItemClickListener
            if (entry.isDirectory) navigateTo(entry.relativePath) else playRemoteVideo(entry)
        }
        loadDirectory("")
    }

    private fun loadDirectory(path: String) {
        if (isBusy) return
        currentPath = path
        backCallback.isEnabled = currentPath.isNotEmpty()
        isBusy = true
        progress.isIndeterminate = true
        progress.visibility = View.VISIBLE
        status.text = if (path.isEmpty()) mount.serverUrl else "${mount.serverUrl}$path/"
        lifecycleScope.launch {
            try {
                val listedEntries = withContext(Dispatchers.IO) {
                    NetworkMountRepository.list(mount, path)
                }
                entries = listedEntries
                listAdapter.clear()
                listAdapter.addAll(listedEntries.map(::formatEntry))
                status.text = if (listedEntries.isEmpty()) {
                    "此目录中没有支持的视频或子目录。"
                } else {
                    "${listedEntries.size} 个项目"
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                showNetworkError(error.message ?: "无法读取网络共享目录。")
            } catch (error: GeneralSecurityException) {
                showNetworkError(error.message ?: "网络共享身份验证失败。")
            } catch (error: IllegalArgumentException) {
                showNetworkError(error.message ?: "网络共享地址无效。")
            } finally {
                isBusy = false
                progress.visibility = View.GONE
            }
        }
    }

    private fun navigateTo(path: String) {
        loadDirectory(path)
    }

    private fun navigateUp() {
        val parent = currentPath.substringBeforeLast('/', "")
        loadDirectory(parent)
    }

    private fun playRemoteVideo(entry: NetworkMountEntry) {
        if (isBusy) return
        isBusy = true
        progress.isIndeterminate = entry.size <= 0L
        progress.progress = 0
        progress.visibility = View.VISIBLE
        status.text = "正在下载到应用临时缓存：${entry.name}"
        lifecycleScope.launch {
            try {
                val localFile = withContext(Dispatchers.IO) {
                    NetworkMountRepository.download(applicationContext, mount, entry) { percent ->
                        runOnUiThread {
                            progress.isIndeterminate = false
                            progress.progress = percent
                            status.text = "正在下载：${entry.name} ($percent%)"
                        }
                    }
                }
                val video = VideoFile(
                    id = localFile.absolutePath.hashCode().toLong(),
                    path = localFile.absolutePath,
                    title = entry.name,
                    folderName = mount.name,
                    size = localFile.length(),
                    duration = 0L,
                    dateModified = localFile.lastModified() / 1000
                )
                startActivity(
                    Intent(this@NetworkMountBrowserActivity, PlayerActivity::class.java)
                        .putExtra("VIDEO_LIST", arrayListOf(video))
                        .putExtra("POSITION", 0)
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                showNetworkError(error.message ?: "无法下载网络视频。")
            } catch (error: GeneralSecurityException) {
                showNetworkError(error.message ?: "网络共享身份验证失败。")
            } catch (error: IllegalArgumentException) {
                showNetworkError(error.message ?: "网络共享地址无效。")
            } finally {
                isBusy = false
                progress.visibility = View.GONE
            }
        }
    }

    private fun formatEntry(entry: NetworkMountEntry): String {
        val prefix = if (entry.isDirectory) "[目录]" else "[视频]"
        val size = if (entry.isDirectory || entry.size <= 0) "" else " · ${formatSize(entry.size)}"
        return "$prefix ${entry.name}$size"
    }

    private fun formatSize(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1.0) String.format("%.1f MB", mb)
        else "${bytes / 1024} KB"
    }

    private fun showNetworkError(message: String) {
        status.text = message
        AlertDialog.Builder(this)
            .setTitle("网络共享错误")
            .setMessage(message)
            .setPositiveButton("确定", null)
            .show()
    }

    private fun finishWithError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    companion object {
        const val EXTRA_MOUNT_ID = "network_mount_id"
    }
}
