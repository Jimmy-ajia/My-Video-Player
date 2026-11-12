package com.yourcompany.videoplayer

import android.app.Activity
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.*
import java.security.GeneralSecurityException

class SettingsActivity : AppCompatActivity() {

    private lateinit var progressCard: MaterialCardView
    private lateinit var progressOverlay: View
    private lateinit var progressPercentage: TextView
    private lateinit var progressText: TextView
    private lateinit var linearProgress: ProgressBar
    private lateinit var etaText: TextView

    private val recoveryBackupCreator =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            if (uri != null) showRecoveryPasswordDialog(uri, exporting = true)
        }

    private val recoveryBackupPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) showRecoveryPasswordDialog(uri, exporting = false)
        }

    private val scanDirectoryPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) addScanDirectory(uri)
        }

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

        findViewById<View>(R.id.btn_export_recovery_key).setOnClickListener {
            recoveryBackupCreator.launch("My-Video-Player-Recovery.${RecoveryKeyBackup.FILE_EXTENSION}")
        }
        findViewById<View>(R.id.btn_import_recovery_key).setOnClickListener {
            recoveryBackupPicker.launch(arrayOf("*/*"))
        }
        findViewById<View>(R.id.btn_add_scan_directory).setOnClickListener {
            scanDirectoryPicker.launch(null)
        }
        refreshScanDirectories()
    }

    private fun showRecoveryPasswordDialog(uri: Uri, exporting: Boolean) {
        val passwordInput = EditText(this).apply {
            hint = if (exporting) "Recovery password (12+ characters)" else "Recovery password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        val confirmationInput = if (exporting) {
            EditText(this).apply {
                hint = "Confirm recovery password"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setSingleLine(true)
            }
        } else {
            null
        }
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, 0)
            addView(passwordInput)
            confirmationInput?.let(::addView)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (exporting) "Protect recovery key" else "Unlock recovery key")
            .setView(fields)
            .setNegativeButton("Cancel", null)
            .setPositiveButton(if (exporting) "Export" else "Import", null)
            .create()
        dialog.setOnDismissListener {
            passwordInput.text?.clear()
            confirmationInput?.text?.clear()
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val password = passwordInput.text.toString().toCharArray()
                if (exporting && password.size < RecoveryKeyBackup.MIN_PASSWORD_LENGTH) {
                    password.fill('\u0000')
                    passwordInput.error =
                        "Use at least ${RecoveryKeyBackup.MIN_PASSWORD_LENGTH} characters."
                    return@setOnClickListener
                }

                if (confirmationInput != null) {
                    val confirmation = confirmationInput.text.toString().toCharArray()
                    val matches = password.contentEquals(confirmation)
                    confirmation.fill('\u0000')
                    if (!matches) {
                        password.fill('\u0000')
                        confirmationInput.error = "Passwords do not match."
                        return@setOnClickListener
                    }
                }

                dialog.dismiss()
                if (exporting) {
                    exportRecoveryBackup(uri, password)
                } else {
                    importRecoveryBackup(uri, password)
                }
            }
        }
        dialog.show()
    }

    private fun exportRecoveryBackup(uri: Uri, password: CharArray) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val keyBytes = LocalMediaKeyStore.exportRecoveryKey(applicationContext)
                    try {
                        val backup = RecoveryKeyBackup.encrypt(keyBytes, password)
                        try {
                            val output = contentResolver.openOutputStream(uri, "wt")
                                ?: throw IOException("Unable to create the recovery key file.")
                            output.use { it.write(backup) }
                        } finally {
                            backup.fill(0)
                        }
                    } finally {
                        keyBytes.fill(0)
                    }
                }
                Toast.makeText(this@SettingsActivity, "Recovery key exported.", Toast.LENGTH_LONG).show()
            } catch (error: IOException) {
                showRecoveryKeyError(error)
            } catch (error: GeneralSecurityException) {
                showRecoveryKeyError(error)
            } catch (error: IllegalArgumentException) {
                showRecoveryKeyError(error)
            } finally {
                password.fill('\u0000')
            }
        }
    }

    private fun importRecoveryBackup(uri: Uri, password: CharArray) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val backup = contentResolver.openInputStream(uri)?.use(::readRecoveryBackup)
                        ?: throw IOException("Unable to open the recovery key file.")
                    try {
                        val keyBytes = RecoveryKeyBackup.decrypt(backup, password)
                        try {
                            LocalMediaKeyStore.importRecoveryKey(applicationContext, keyBytes)
                        } finally {
                            keyBytes.fill(0)
                        }
                    } finally {
                        backup.fill(0)
                    }
                }
                Toast.makeText(this@SettingsActivity, "Recovery key imported.", Toast.LENGTH_LONG).show()
            } catch (error: IOException) {
                showRecoveryKeyError(error)
            } catch (error: GeneralSecurityException) {
                showRecoveryKeyError(error)
            } catch (error: IllegalArgumentException) {
                showRecoveryKeyError(error)
            } finally {
                password.fill('\u0000')
            }
        }
    }

    private fun readRecoveryBackup(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream(RecoveryKeyBackup.MAX_FILE_SIZE)
        val buffer = ByteArray(256)
        try {
            var totalRead = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                totalRead += count
                if (totalRead > RecoveryKeyBackup.MAX_FILE_SIZE) {
                    throw IOException("The selected file is too large to be a recovery key.")
                }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        } finally {
            buffer.fill(0)
        }
    }

    private fun showRecoveryKeyError(error: Exception) {
        Toast.makeText(
            this,
            error.message ?: "Recovery key operation failed.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun addScanDirectory(uri: Uri) {
        try {
            val directory = resolveExternalStorageTree(uri)
            if (ScanDirectoryStore.add(applicationContext, directory.path)) {
                Toast.makeText(this, "已添加扫描目录。", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "该目录已在扫描列表中。", Toast.LENGTH_SHORT).show()
            }
            refreshScanDirectories()
        } catch (error: IOException) {
            Toast.makeText(this, error.message ?: "无法添加此目录。", Toast.LENGTH_LONG).show()
        } catch (error: IllegalArgumentException) {
            Toast.makeText(this, error.message ?: "无法识别所选目录。", Toast.LENGTH_LONG).show()
        } catch (error: SecurityException) {
            Toast.makeText(
                this,
                error.message ?: "无法添加此目录。",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun resolveExternalStorageTree(uri: Uri): File {
        if (uri.authority != "com.android.externalstorage.documents") {
            throw IOException("请选择 Android 共享存储或 SD 卡中的目录。")
        }
        val documentId = DocumentsContract.getTreeDocumentId(uri)
        val separator = documentId.indexOf(':')
        if (separator <= 0) throw IOException("无法识别所选目录。")

        val volumeId = documentId.substring(0, separator)
        val relativePath = documentId.substring(separator + 1)
        val volumeRoot = if (volumeId.equals("primary", ignoreCase = true)) {
            Environment.getExternalStorageDirectory()
        } else {
            File("/storage", volumeId)
        }
        val canonicalRoot = volumeRoot.canonicalFile
        val directory = File(canonicalRoot, relativePath).canonicalFile
        if (directory != canonicalRoot &&
            !directory.path.startsWith(canonicalRoot.path + File.separator)
        ) {
            throw IOException("所选目录不在共享存储或 SD 卡范围内。")
        }
        if (!directory.isDirectory) throw IOException("所选目录不存在或不可访问。")
        return directory
    }

    private fun refreshScanDirectories() {
        val container = findViewById<LinearLayout>(R.id.scan_directories_container)
        container.removeAllViews()
        val presetPaths = ScanDirectoryStore.defaults().map { it.canonicalPath }.toSet()
        val paths = ScanDirectoryStore.getPaths(applicationContext)
        if (paths.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "尚未添加扫描目录。"
                setTextColor(0xFFAAAAAA.toInt())
                textSize = 13f
            })
            return
        }

        paths.forEach { path ->
            val directory = File(path)
            val isPreset = try {
                directory.canonicalPath in presetPaths
            } catch (_: IOException) {
                false
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                val padding = (8 * resources.displayMetrics.density).toInt()
                setPadding(0, padding, 0, padding)
            }
            val label = TextView(this).apply {
                text = "${directory.name}${if (isPreset) "（预设）" else ""}\n$path"
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 13f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }
            val removeButton = android.widget.ImageButton(this).apply {
                setImageResource(R.drawable.ic_delete)
                contentDescription = "删除扫描目录 ${directory.name}"
                background = getDrawable(
                    android.util.TypedValue().let { value ->
                        theme.resolveAttribute(
                            android.R.attr.selectableItemBackgroundBorderless,
                            value,
                            true
                        )
                        value.resourceId
                    }
                )
                setOnClickListener {
                    AlertDialog.Builder(this@SettingsActivity)
                        .setTitle("删除扫描目录")
                        .setMessage("从扫描列表中移除 ${directory.name}？目录中的文件不会被删除。")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("移除") { _, _ ->
                            ScanDirectoryStore.remove(applicationContext, path)
                            refreshScanDirectories()
                        }
                        .show()
                }
            }
            row.addView(label)
            row.addView(removeButton)
            container.addView(row)
        }
    }

    private fun startBatchConversion(paths: List<String>) {
        progressCard.visibility = View.VISIBLE
        progressOverlay.visibility = View.VISIBLE
        
        lifecycleScope.launch(Dispatchers.IO) {
            var successCount = 0
            val failures = mutableListOf<String>()
            paths.forEachIndexed { index, path ->
                val file = File(path)
                if (!file.isFile) {
                    failures += "${file.name}: source file no longer exists"
                    return@forEachIndexed
                }

                withContext(Dispatchers.Main) {
                    progressText.text = "Securing (${index + 1}/${paths.size}):\n${file.name}"
                    progressPercentage.text = "0%"
                    linearProgress.progress = 0
                }
                    
                try {
                    if (EncryptedMediaContainer.isContainer(file)) {
                        throw IOException("This video is already encrypted or uses an unsupported legacy format.")
                    }
                    val outFile = VideoFileConverter.encrypt(applicationContext, file) { progress ->
                        runOnUiThread {
                            progressPercentage.text = "$progress%"
                            linearProgress.progress = progress
                        }
                    }
                    successCount++
                    MediaScannerConnection.scanFile(
                        this@SettingsActivity,
                        arrayOf(outFile.absolutePath),
                        arrayOf(EncryptedMediaMimeType.fromExtension(outFile.extension)),
                        null
                    )
                } catch (error: Exception) {
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
