package com.yourcompany.videoplayer

import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VideoListActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var videoAdapter: VideoAdapter
    private var videoList = ArrayList<VideoFile>()
    private var folderName: String = ""
    private var folderPath: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_list)

        try {
            val toolbar = findViewById<Toolbar>(R.id.toolbar)
            val toolbarTitle = findViewById<TextView>(R.id.toolbar_title)
            val btnRootHome = findViewById<ImageButton>(R.id.btn_root_home)

            folderName = intent.getStringExtra("FOLDER_NAME") ?: "Videos"
            folderPath = intent.getStringExtra("FOLDER_PATH") ?: ""

            toolbarTitle.text = folderName

            btnRootHome.setOnClickListener {
                finish()
            }

            recyclerView = findViewById(R.id.videoRecyclerView)
            recyclerView.layoutManager = LinearLayoutManager(this)

            videoAdapter = VideoAdapter(this, videoList)
            recyclerView.adapter = videoAdapter

            loadVideos()

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onResume() {
        super.onResume()
        loadVideos()
    }

    fun loadVideos() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val repo = VideoRepository(this@VideoListActivity)
                val fetchedVideos = repo.getVideosFromFolder(folderPath)

                withContext(Dispatchers.Main) {
                    videoList.clear()
                    videoList.addAll(fetchedVideos)
                    videoAdapter.notifyDataSetChanged()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK) {
            if (requestCode == 124) {
                val uri = VideoAdapter.pendingRenameUri
                val newName = VideoAdapter.pendingNewName
                if (uri != null && newName != null) {
                    val values = android.content.ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, newName)
                    }
                    contentResolver.update(uri, values, null, null)
                    VideoAdapter.pendingRenameUri = null
                    VideoAdapter.pendingNewName = null
                }
                loadVideos()
                Toast.makeText(this, "Renamed successfully", Toast.LENGTH_SHORT).show()
            } else if (requestCode == 123) {
                loadVideos()
                Toast.makeText(this, "Deleted successfully", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
