package com.yourcompany.videoplayer

import android.app.Activity
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class SettingsActivity : AppCompatActivity() {

    private lateinit var progressCard: MaterialCardView
    private lateinit var progressOverlay: View
    private lateinit var progressPercentage: TextView
    private lateinit var progressText: TextView
    private lateinit var linearProgress: ProgressBar
    private lateinit var etaText: TextView

    private val videoPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val paths = result.data?.getStringArrayListExtra("PATHS")
            if (!paths.isNullOrEmpty()) {
                startBatchConversion(paths)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<Toolbar>(R.id.toolbar_settings)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { onBackPressed() }

        progressCard = findViewById(R.id.progress_card)
        progressOverlay = findViewById(R.id.progress_overlay)
        progressPercentage = findViewById(R.id.progress_percentage)
        progressText = findViewById(R.id.progress_text)
        linearProgress = findViewById(R.id.linear_progress)
        etaText = findViewById(R.id.eta_text)

        val btnConverter = findViewById<MaterialCardView>(R.id.btn_video_converter)
        btnConverter.setOnClickListener {
            val intent = Intent(this, VideoPickerActivity::class.java)
            videoPickerLauncher.launch(intent)
        }
    }

    private fun standardizePath(path: String): String {
        if (path.isEmpty()) return path
        return path.replace("/mnt/media_rw/", "/storage/").replace("//", "/")
    }

    private fun getVolumeRoot(path: String): String {
        val stdPath = standardizePath(path)
        if (stdPath.startsWith("/storage/emulated/0")) return "/storage/emulated/0"
        val parts = stdPath.split("/")
        if (parts.size >= 3 && parts[1] == "storage") return "/storage/${parts[2]}"
        return Environment.getExternalStorageDirectory().absolutePath
    }

    private fun startBatchConversion(paths: List<String>) {
        progressCard.visibility = View.VISIBLE
        progressOverlay.visibility = View.VISIBLE
        
        lifecycleScope.launch(Dispatchers.IO) {
            var successCount = 0
            val failures = mutableListOf<String>()
            paths.forEachIndexed { index, path ->
                val stdPath = standardizePath(path)
                val file = File(stdPath)
                if (!file.exists()) {
                    failures += "${file.name}: source file no longer exists"
                    return@forEachIndexed
                }
                val volumeRoot = getVolumeRoot(stdPath)
                val outDir = File(volumeRoot, "Convert")
                if (!outDir.exists() && !outDir.mkdirs()) {
                    failures += "${file.name}: could not create the output directory"
                    return@forEachIndexed
                }
                val finalName = "${file.nameWithoutExtension}_encrypted.${EncryptedMediaContainer.FILE_EXTENSION}"
                val outFile = File(outDir, finalName)
                val tempFile = File(outDir, "$finalName.tmp")

                withContext(Dispatchers.Main) {
                    progressText.text = "Securing (${index + 1}/${paths.size}):\n${file.name}"
                    progressPercentage.text = "0%"
                    linearProgress.progress = 0
                }
                    
                try {
                    if (EncryptedMediaContainer.isContainer(file) || VideoFile.isLegacyEncrypted(file.absolutePath)) {
                        throw IOException("This video is already encrypted or uses an unsupported legacy format.")
                    }
                    EncryptedMediaContainer.encrypt(
                        file,
                        tempFile,
                        file.extension,
                        LocalMediaKeyStore.getOrCreateWrappingKey()
                    ) { progress ->
                        runOnUiThread {
                            progressPercentage.text = "$progress%"
                            linearProgress.progress = progress
                        }
                    }
                    Files.move(tempFile.toPath(), outFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    successCount++
                    MediaScannerConnection.scanFile(
                        this@SettingsActivity,
                        arrayOf(outFile.absolutePath),
                        arrayOf(EncryptedMediaMimeType.fromExtension(file.extension)),
                        null
                    )
                } catch (error: Exception) {
                    tempFile.delete()
                    failures += "${file.name}: ${error.message ?: "encryption failed"}"
                }
            }

            withContext(Dispatchers.Main) {
                progressCard.visibility = View.GONE
                progressOverlay.visibility = View.GONE
                val result = buildString {
                    append("Encrypted $successCount videos.")
                    if (failures.isNotEmpty()) {
                        append(" Failed ${failures.size}: ")
                        append(failures.take(2).joinToString("; "))
                    }
                }
                Toast.makeText(this@SettingsActivity, result, Toast.LENGTH_LONG).show()
            }
        }
    }

}
