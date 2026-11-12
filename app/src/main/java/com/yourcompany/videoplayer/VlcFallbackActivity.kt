package com.yourcompany.videoplayer

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File
import java.io.IOException

class VlcFallbackActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var libVlc: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private var encryptedFileDescriptor: android.os.ParcelFileDescriptor? = null
    private lateinit var videoLayout: VLCVideoLayout
    private lateinit var playPauseButton: Button
    private lateinit var seekBar: SeekBar
    private lateinit var timeLabel: TextView
    private var userSeeking = false

    private val updateProgress = object : Runnable {
        override fun run() {
            val player = mediaPlayer ?: return
            val duration = player.length
            val position = player.time.coerceAtLeast(0L)
            if (!userSeeking && duration > 0) {
                seekBar.progress = ((position.toDouble() / duration) * seekBar.max)
                    .toInt()
                    .coerceIn(0, seekBar.max)
            }
            timeLabel.text = "${formatTime(position)} / ${formatTime(duration)}"
            playPauseButton.text = if (player.isPlaying) "暂停" else "播放"
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_vlc_fallback)
        videoLayout = findViewById(R.id.vlc_video_layout)
        playPauseButton = findViewById(R.id.vlc_play_pause)
        seekBar = findViewById(R.id.vlc_seek)
        timeLabel = findViewById(R.id.vlc_time)

        val path = intent.getStringExtra(EXTRA_PATH)
        if (path.isNullOrBlank()) {
            Toast.makeText(this, "缺少视频文件路径", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        findViewById<TextView>(R.id.vlc_title).text =
            intent.getStringExtra(EXTRA_TITLE) ?: File(path).name
        findViewById<Button>(R.id.vlc_back).setOnClickListener { finish() }
        playPauseButton.setOnClickListener {
            mediaPlayer?.let { player ->
                if (player.isPlaying) player.pause() else player.play()
            }
        }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) = Unit

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                userSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                val duration = mediaPlayer?.length ?: 0L
                if (duration > 0) {
                    mediaPlayer?.time = duration * seekBar.progress / seekBar.max
                }
                userSeeking = false
            }
        })

        try {
            startPlayback(path)
        } catch (error: Exception) {
            Toast.makeText(
                this,
                "VLC 无法播放此文件：${error.message ?: "未知错误"}",
                Toast.LENGTH_LONG
            ).show()
            finish()
        }
    }

    private fun startPlayback(path: String) {
        val playerLibrary = LibVLC(this, arrayListOf("--no-drop-late-frames", "--file-caching=1500"))
        libVlc = playerLibrary
        val player = MediaPlayer(playerLibrary)
        mediaPlayer = player
        player.attachViews(videoLayout, null, false, false)
        player.setEventListener { event ->
            if (event.type == MediaPlayer.Event.EncounteredError) {
                runOnUiThread {
                    Toast.makeText(this, "VLC 无法解码该文件", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }

        val isEncrypted = intent.getBooleanExtra(EXTRA_ENCRYPTED, false)
        val media = if (isEncrypted) {
            val descriptor = contentResolver.openFileDescriptor(EncryptedVideoUri.fromPath(path), "r")
                ?: throw IOException("无法打开加密视频")
            encryptedFileDescriptor = descriptor
            Media(playerLibrary, descriptor.fileDescriptor)
        } else {
            Media(playerLibrary, Uri.fromFile(File(path)))
        }
        media.addOption(":file-caching=1500")
        player.media = media
        media.release()
        player.play()
        handler.post(updateProgress)
    }

    override fun onDestroy() {
        handler.removeCallbacks(updateProgress)
        mediaPlayer?.apply {
            stop()
            detachViews()
            release()
        }
        mediaPlayer = null
        libVlc?.release()
        libVlc = null
        encryptedFileDescriptor?.close()
        encryptedFileDescriptor = null
        super.onDestroy()
    }

    private fun formatTime(timeMs: Long): String {
        if (timeMs < 0) return "--:--"
        val totalSeconds = timeMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds / 60) % 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_ENCRYPTED = "encrypted"
        const val EXTRA_TITLE = "title"
    }
}
