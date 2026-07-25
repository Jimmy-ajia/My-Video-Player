package com.yourcompany.videoplayer

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.util.LruCache
import androidx.annotation.RequiresApi
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.max
import kotlin.math.min

class DecryptionProvider : ContentProvider() {
    private lateinit var handlerThread: HandlerThread
    private lateinit var backgroundHandler: Handler
    
    private val signaturePositionCache = LruCache<String, Long>(1000)

    companion object {
        private const val MARKER = "_HAMZA_SECURED_VIDEO_"
        private const val SCAN_LIMIT = 1024 * 1024 * 15L // Increased to 15MB
        private const val TAG = "DecryptionProvider"
        private const val IV_SIZE = 16
    }

    override fun onCreate(): Boolean {
        handlerThread = HandlerThread("DecryptionThread")
        handlerThread.start()
        backgroundHandler = Handler(handlerThread.looper)
        return true
    }

    override fun query(u: Uri, p: Array<String>?, s: String?, sa: Array<String>?, o: String?) = null
    override fun getType(u: Uri) = "video/*"
    override fun insert(u: Uri, v: ContentValues?) = null
    override fun delete(u: Uri, s: String?, sa: Array<String>?) = 0
    override fun update(u: Uri, v: ContentValues?, s: String?, sa: Array<String>?) = 0

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val path = uri.path ?: return null
        val file = File(path)
        if (!file.exists()) return null

        val prefs = context?.getSharedPreferences("SETTINGS", Context.MODE_PRIVATE)
        val signatureStr = prefs?.getString("SECURITY_SIGNATURE", "HAMZA") ?: "HAMZA"
        val signatureBytes = signatureStr.toByteArray()

        val cacheKey = "${file.absolutePath}_$signatureStr"
        val cachedPos = signaturePositionCache.get(cacheKey)
        
        val dataStartPos = cachedPos ?: findDataStartSync(file, signatureBytes)
        
        if (dataStartPos != -1L) {
            signaturePositionCache.put(cacheKey, dataStartPos)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                return openDecryptedStream(file, dataStartPos, signatureBytes)
            }
        }
        
        // Return original if not encrypted or signature mismatch (VLC plays decoy)
        return try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: Exception) { null }
    }

    private fun findDataStartSync(file: File, signature: ByteArray): Long {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val markerBytes = MARKER.toByteArray()
                val bufferSize = 1024 * 1024 // 1MB chunks
                val buffer = ByteArray(bufferSize)
                var currentPos = 0L

                while (currentPos < SCAN_LIMIT && currentPos < file.length()) {
                    raf.seek(currentPos)
                    val readCount = raf.read(buffer)
                    if (readCount < markerBytes.size) break
                    
                    for (i in 0 .. (readCount - markerBytes.size)) {
                        var match = true
                        for (j in markerBytes.indices) {
                            if (buffer[i + j] != markerBytes[j]) {
                                match = false
                                break
                            }
                        }
                        
                        if (match) {
                            val sigStartPos = currentPos + i + markerBytes.size
                            val actualSigBytes = ByteArray(signature.size)
                            raf.seek(sigStartPos)
                            val sigRead = raf.read(actualSigBytes)
                            
                            if (sigRead == signature.size && actualSigBytes.contentEquals(signature)) {
                                return sigStartPos + signature.size
                            }
                            return -1L 
                        }
                    }
                    currentPos += (bufferSize - markerBytes.size)
                }
                -1L
            }
        } catch (e: Exception) { -1L }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun openDecryptedStream(file: File, dataStartPos: Long, signature: ByteArray): ParcelFileDescriptor? {
        val storageManager = context?.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        val raf = RandomAccessFile(file, "r")
        
        // Read IV from file
        val iv = ByteArray(IV_SIZE)
        raf.seek(dataStartPos)
        raf.readFully(iv)
        
        val encryptedDataStart = dataStartPos + IV_SIZE
        val dataSize = file.length() - encryptedDataStart
        
        // Derive Key from Signature
        val keyBytes = MessageDigest.getInstance("SHA-256").digest(signature)
        val secretKey = SecretKeySpec(keyBytes, "AES")

        return try {
            storageManager?.openProxyFileDescriptor(
                ParcelFileDescriptor.MODE_READ_ONLY,
                object : ProxyFileDescriptorCallback() {
                    override fun onGetSize(): Long = dataSize
                    override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                        synchronized(raf) {
                            try {
                                raf.seek(encryptedDataStart + offset)
                                val bytesRead = raf.read(data, 0, size)
                                if (bytesRead > 0) {
                                    decryptCTR(secretKey, iv, offset, data, bytesRead)
                                }
                                return max(0, bytesRead)
                            } catch (e: Exception) { return 0 }
                        }
                    }
                    
                    private fun decryptCTR(key: SecretKeySpec, baseIv: ByteArray, offset: Long, data: ByteArray, length: Int) {
                        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
                        
                        val blockIndex = offset / 16
                        val byteOffsetInBlock = (offset % 16).toInt()
                        
                        val ivInt = java.math.BigInteger(1, baseIv)
                        val newIv = ivInt.add(java.math.BigInteger.valueOf(blockIndex)).toByteArray()
                        val paddedIv = ByteArray(16)
                        System.arraycopy(newIv, max(0, newIv.size - 16), paddedIv, max(0, 16 - newIv.size), min(16, newIv.size))
                        
                        cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(paddedIv))
                        
                        if (byteOffsetInBlock > 0) {
                            cipher.update(ByteArray(byteOffsetInBlock))
                        }
                        
                        val decrypted = cipher.doFinal(data, 0, length)
                        System.arraycopy(decrypted, 0, data, 0, length)
                    }

                    override fun onRelease() { try { raf.close() } catch (e: Exception) {} }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            try { raf.close() } catch (ex: Exception) {}
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
    }
}
