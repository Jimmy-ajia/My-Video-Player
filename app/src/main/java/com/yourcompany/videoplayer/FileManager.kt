package com.yourcompany.videoplayer

import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.result.IntentSenderRequest

object FileManager {

    fun renameMedia(
        context: Context,
        videoId: Long,
        finalNameWithExt: String,
        onSuccess: () -> Unit = {},
        onFailure: (String) -> Unit = {}
    ): IntentSenderRequest? {
        val uri = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, videoId.toString())

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, finalNameWithExt)
        }

        return try {
            val rowsUpdated = context.contentResolver.update(uri, values, null, null)
            if (rowsUpdated > 0) {
                onSuccess()
                null
            } else {
                onFailure("No rows updated")
                null
            }
        } catch (e: SecurityException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val recoverable = e as? android.app.RecoverableSecurityException
                val intentSender = recoverable?.userAction?.actionIntent?.intentSender
                if (intentSender != null) {
                    IntentSenderRequest.Builder(intentSender).build()
                } else null
            } else {
                onFailure("Permission denied")
                null
            }
        } catch (e: Exception) {
            onFailure(e.message ?: "Unknown error")
            null
        }
    }

    fun deleteMedia(context: Context, videoUris: List<Uri>): PendingIntent? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                MediaStore.createDeleteRequest(context.contentResolver, videoUris)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun deleteSingleVideo(context: Context, videoId: Long): PendingIntent? {
        val uri = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, videoId.toString())
        return deleteMedia(context, listOf(uri))
    }
}