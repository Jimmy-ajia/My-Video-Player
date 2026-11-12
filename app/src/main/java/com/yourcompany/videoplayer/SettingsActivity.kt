package com.yourcompany.videoplayer

import android.app.Activity
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.os.Environment
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
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
                        LocalMediaKeyStore.getOrCreateWrappingKey(this@SettingsActivity)
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
