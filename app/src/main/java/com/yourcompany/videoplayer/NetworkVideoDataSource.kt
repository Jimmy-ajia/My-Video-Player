package com.yourcompany.videoplayer

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import jcifs.smb.SmbRandomAccessFile
import java.io.IOException

internal object NetworkVideoUri {
    const val SCHEME = "smbmedia"

    data class Address(
        val mountId: String,
        val relativePath: String,
        val fileLength: Long?
    )

    fun effectiveFileLength(reportedLength: Long, advertisedLength: Long?): Long? =
        reportedLength.takeIf { it > 0 } ?: advertisedLength

    fun create(mountId: String, relativePath: String, fileLength: Long = 0L): Uri {
        require(mountId.isNotBlank())
        val builder = Uri.Builder().scheme(SCHEME).authority(mountId)
        relativePath.split('/').filter(String::isNotEmpty).forEach(builder::appendPath)
        if (fileLength > 0) builder.appendQueryParameter("length", fileLength.toString())
        return builder.build()
    }

    fun parse(uri: Uri): Address {
        require(uri.scheme == SCHEME && !uri.authority.isNullOrBlank()) {
            "The SMB video address is invalid."
        }
        val path = uri.pathSegments.joinToString("/")
        require(path.isNotBlank()) { "The SMB video path is missing." }
        val lengthValue = uri.getQueryParameter("length")
        val fileLength = lengthValue?.toLongOrNull()
        require(lengthValue == null || fileLength != null && fileLength > 0) {
            "The SMB video length is invalid."
        }
        return Address(uri.authority!!, path, fileLength)
    }
}

internal class NetworkVideoDataSource(
    context: Context,
    private val defaultFactory: DataSource.Factory = DefaultDataSource.Factory(context)
) : DataSource {
    private val appContext = context.applicationContext
    private val transferListeners = mutableListOf<TransferListener>()
    private var activeSource: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        transferListeners += transferListener
        activeSource?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        close()
        val source = if (dataSpec.uri.scheme == NetworkVideoUri.SCHEME) {
            SmbVideoSource(appContext)
        } else {
            defaultFactory.createDataSource()
        }
        transferListeners.forEach(source::addTransferListener)
        activeSource = source
        return try {
            source.open(dataSpec)
        } catch (error: Exception) {
            activeSource = null
            try {
                source.close()
            } catch (closeError: Exception) {
                error.addSuppressed(closeError)
            }
            throw error
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        activeSource?.read(buffer, offset, length)
            ?: throw IOException("The video data source is not open.")

    override fun getUri(): Uri? = activeSource?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        activeSource?.responseHeaders.orEmpty()

    override fun close() {
        val source = activeSource ?: return
        activeSource = null
        source.close()
    }
}

private class SmbVideoSource(context: Context) : BaseDataSource(true) {
    private val appContext = context.applicationContext
    private var uri: Uri? = null
    private var remoteFile: SmbRandomAccessFile? = null
    private var bytesRemaining = C.LENGTH_UNSET.toLong()

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val address = try {
            NetworkVideoUri.parse(dataSpec.uri)
        } catch (error: IllegalArgumentException) {
            throw IOException("The SMB video address is invalid.", error)
        }
        val mount = try {
            NetworkMountStore.get(appContext, address.mountId)
        } catch (error: java.security.GeneralSecurityException) {
            throw IOException("Unable to read the saved SMB credentials.", error)
        } ?: throw IOException("The SMB share configuration no longer exists.")
        val file = NetworkMountRepository.openRandomAccessFile(mount, address.relativePath)
        try {
            val fileLength = NetworkVideoUri.effectiveFileLength(file.length(), address.fileLength)
            if (fileLength != null && dataSpec.position > fileLength) {
                throw IOException("The requested SMB video position exceeds the file size.")
            }
            file.seek(dataSpec.position)
            bytesRemaining = when {
                fileLength == null -> dataSpec.length
                dataSpec.length == C.LENGTH_UNSET.toLong() ->
                    fileLength - dataSpec.position
                else -> minOf(dataSpec.length, fileLength - dataSpec.position)
            }
            remoteFile = file
            uri = dataSpec.uri
            transferStarted(dataSpec)
            return bytesRemaining
        } catch (error: Exception) {
            try {
                file.close()
            } catch (closeError: Exception) {
                error.addSuppressed(closeError)
            }
            throw error
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val requestedLength = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            minOf(length.toLong(), bytesRemaining).toInt()
        }
        val read = remoteFile?.read(buffer, offset, requestedLength)
            ?: throw IOException("The SMB video data source is not open.")
        if (read < 0) {
            if (bytesRemaining != C.LENGTH_UNSET.toLong() && bytesRemaining > 0) {
                throw IOException("The SMB video ended before the requested data was read.")
            }
            return C.RESULT_END_OF_INPUT
        }
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        bytesRemaining = C.LENGTH_UNSET.toLong()
        val file = remoteFile ?: return
        remoteFile = null
        try {
            file.close()
        } finally {
            transferEnded()
        }
    }
}
