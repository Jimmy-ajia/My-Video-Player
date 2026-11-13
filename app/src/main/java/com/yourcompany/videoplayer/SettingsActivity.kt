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
import androidx.core.content.ContextCompat
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
        findViewById<View>(R.id.btn_add_smb_mount).setOnClickListener {
            showAddNetworkMountDialog(NetworkMountType.SMB)
        }
        findViewById<View>(R.id.btn_add_webdav_mount).setOnClickListener {
            showAddNetworkMountDialog(NetworkMountType.WEBDAV)
        }
        refreshScanDirectories()
        refreshNetworkMounts()
    }

    private fun showAddNetworkMountDialog(type: NetworkMountType) {
        val nameInput = EditText(this).apply {
            hint = "名称"
            setSingleLine(true)
        }
        val addressInput = EditText(this).apply {
            hint = if (type == NetworkMountType.SMB) {
                "smb://192.168.1.10/share/"
            } else {
                "https://example.com/remote.php/dav/"
            }
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
        }
        val domainInput = if (type == NetworkMountType.SMB) {
            EditText(this).apply {
                hint = "域名/工作组（可选）"
                setSingleLine(true)
            }
        } else {
            null
        }
        val usernameInput = EditText(this).apply {
            hint = "用户名（可选）"
            setSingleLine(true)
        }
        val passwordInput = EditText(this).apply {
            hint = "密码"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, 0)
            addView(nameInput)
            addView(addressInput)
            domainInput?.let(::addView)
            addView(usernameInput)
            addView(passwordInput)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (type == NetworkMountType.SMB) "添加 SMB 共享" else "添加 WebDAV")
            .setView(fields)
            .setNegativeButton("取消", null)
            .setPositiveButton("添加", null)
            .create()
        dialog.setOnDismissListener {
            passwordInput.text?.clear()
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text.toString().trim()
                if (name.isBlank()) {
                    nameInput.error = "请输入名称。"
                    return@setOnClickListener
                }
                val normalizedUrl = try {
                    NetworkMountRepository.normalizeServerUrl(
                        type,
                        addressInput.text.toString()
                    )
                } catch (error: IllegalArgumentException) {
                    addressInput.error = error.message
                    return@setOnClickListener
                }
                val mount = NetworkMount(
                    id = java.util.UUID.randomUUID().toString(),
                    name = name,
                    type = type,
                    serverUrl = normalizedUrl,
                    username = usernameInput.text.toString().trim(),
                    password = passwordInput.text.toString(),
                    domain = domainInput?.text?.toString()?.trim().orEmpty()
                )
                try {
                    NetworkMountStore.put(applicationContext, mount)
                    dialog.dismiss()
                    refreshNetworkMounts()
                    Toast.makeText(this, "已保存网络共享。", Toast.LENGTH_SHORT).show()
                } catch (error: IOException) {
                    Toast.makeText(
                        this,
                        error.message ?: "无法保存网络共享。",
                        Toast.LENGTH_LONG
                    ).show()
                } catch (error: GeneralSecurityException) {
                    Toast.makeText(
                        this,
                        error.message ?: "无法加密网络共享凭据。",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        dialog.show()
    }

    private fun refreshNetworkMounts() {
        val container = findViewById<LinearLayout>(R.id.network_mounts_container)
        container.removeAllViews()
        val mounts = try {
            NetworkMountStore.getAll(applicationContext)
        } catch (error: IOException) {
            showNetworkMountError(error)
            return
        } catch (error: GeneralSecurityException) {
            showNetworkMountError(error)
            return
        } catch (error: IllegalArgumentException) {
            showNetworkMountError(error)
            return
        }
        if (mounts.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "尚未添加网络共享。"
                setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_secondary))
                textSize = 13f
            })
            return
        }
        mounts.forEach { mount ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                val padding = (6 * resources.displayMetrics.density).toInt()
                setPadding(0, padding, 0, padding)
            }
            val label = TextView(this).apply {
                text = "${mount.name}（${mount.type.name}）\n${mount.serverUrl}"
                setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_primary))
                textSize = 12f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }
            val browseButton = com.google.android.material.button.MaterialButton(this).apply {
                text = "浏览"
                setOnClickListener {
                    startActivity(
                        Intent(this@SettingsActivity, NetworkMountBrowserActivity::class.java)
                            .putExtra(NetworkMountBrowserActivity.EXTRA_MOUNT_ID, mount.id)
                    )
                }
            }
            val removeButton = android.widget.ImageButton(this).apply {
                setImageResource(R.drawable.ic_delete)
                contentDescription = "移除网络共享 ${mount.name}"
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
                setOnClickListener { confirmRemoveNetworkMount(mount) }
            }
            row.addView(label)
            row.addView(browseButton)
            row.addView(removeButton)
            container.addView(row)
        }
    }

    private fun confirmRemoveNetworkMount(mount: NetworkMount) {
        AlertDialog.Builder(this)
            .setTitle("移除网络共享")
            .setMessage("移除“${mount.name}”？共享端的文件不会被删除。")
            .setNegativeButton("取消", null)
            .setPositiveButton("移除") { _, _ ->
                try {
                    NetworkMountStore.remove(applicationContext, mount.id)
                    refreshNetworkMounts()
                } catch (error: IOException) {
                    showNetworkMountError(error)
                } catch (error: GeneralSecurityException) {
                    showNetworkMountError(error)
                }
            }
            .show()
    }

    private fun showNetworkMountError(error: Exception) {
        Toast.makeText(this, error.message ?: "网络共享设置操作失败。", Toast.LENGTH_LONG).show()
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
                setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_secondary))
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
                setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_primary))
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
