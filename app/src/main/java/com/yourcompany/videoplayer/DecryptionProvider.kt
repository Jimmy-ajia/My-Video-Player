package com.yourcompany.videoplayer

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

class DecryptionProvider : ContentProvider() {
    private lateinit var handlerThread: HandlerThread
    private lateinit var backgroundHandler: Handler

    override fun onCreate(): Boolean {
        handlerThread = HandlerThread("DecryptionThread").apply { start() }
        backgroundHandler = Handler(handlerThread.looper)
        return true
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?) = null
    override fun getType(uri: Uri): String? {
        val file = uri.path?.let(::File) ?: return null
        if (!EncryptedMediaContainer.isContainer(file)) return null
        val appContext = context ?: return null
        val extension = LocalMediaKeyStore.openEncryptedMedia(appContext, file)
            .use { it.originalExtension }
        return EncryptedMediaMimeType.fromExtension(extension)
    }
    override fun insert(uri: Uri, values: ContentValues?) = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Encrypted media is read-only.")
        val file = uri.path?.let(::File) ?: throw FileNotFoundException("Missing encrypted media path.")
        if (!EncryptedMediaContainer.isContainer(file)) {
            throw FileNotFoundException("Unsupported or invalid encrypted media container.")
        }

        val appContext = context ?: throw FileNotFoundException("The media provider is unavailable.")
        val reader = try {
            LocalMediaKeyStore.openEncryptedMedia(appContext, file)
        } catch (error: Exception) {
            throw FileNotFoundException(error.message ?: "Unable to open encrypted media.").also {
                it.initCause(error)
            }
        }
        val storageManager = appContext.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            ?: run {
                reader.close()
                throw FileNotFoundException("Proxy file descriptors are unavailable.")
            }

        return try {
            storageManager.openProxyFileDescriptor(
                ParcelFileDescriptor.MODE_READ_ONLY,
                object : ProxyFileDescriptorCallback() {
                    override fun onGetSize(): Long = reader.size

                    override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                        return try {
                            reader.readAt(offset, size, data)
                        } catch (error: Exception) {
                            throw ErrnoException("read encrypted media", OsConstants.EIO, error)
                        }
                    }

                    override fun onRelease() {
                        reader.close()
                    }
                },
                backgroundHandler
            ) ?: throw IOException("Unable to create the virtual media descriptor.")
        } catch (error: Exception) {
            reader.close()
            throw FileNotFoundException(error.message ?: "Unable to expose the virtual media file.").also {
                it.initCause(error)
            }
        }
    }

    override fun shutdown() {
        handlerThread.quitSafely()
        super.shutdown()
    }
}
