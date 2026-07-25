package com.yourcompany.videoplayer

import android.content.Context
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

@OptIn(UnstableApi::class)
object Dialogs {

    // 1. ✅ REAL RENAME DIALOG
    fun showRenameDialog(context: Context, videoFile: VideoFile, onRenamed: (String) -> Unit) {
        val et = EditText(context)
        et.setText(videoFile.title) // VideoFile class uses 'title'

        val container = FrameLayout(context)
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(60, 20, 60, 10) // UI spacing
        et.layoutParams = params
        container.addView(et)

        AlertDialog.Builder(context, R.style.CustomDialog)
            .setTitle("Rename Video")
            .setView(container)
            .setPositiveButton("OK") { _, _ ->
                val newName = et.text.toString().trim()
                if (newName.isNotEmpty()) {
                    onRenamed(newName)
                } else {
                    Toast.makeText(context, "Name cannot be empty", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // 2. ✅ REAL DELETE DIALOG
    fun showDeleteDialog(context: Context, videoTitle: String, onDelete: () -> Unit) {
        AlertDialog.Builder(context, R.style.CustomDialog)
            .setTitle("Delete Video")
            .setMessage("Are you sure you want to delete '$videoTitle'?")
            .setPositiveButton("Delete") { _, _ -> onDelete() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // 3. ✅ AUDIO TRACK DIALOG (Switches dual audio)
    fun showAudioTrackDialog(context: Context, player: ExoPlayer) {
        val tracks = player.currentTracks
        val audioList = mutableListOf<String>()
        val trackGroups = mutableListOf<Pair<androidx.media3.common.Tracks.Group, Int>>()

        for (group in tracks.groups) {
            if (group.type == C.TRACK_TYPE_AUDIO) {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val label = format.language?.uppercase() ?: "Audio ${audioList.size + 1}"
                    audioList.add(label)
                    trackGroups.add(group to i)
                }
            }
        }

        if (audioList.isEmpty()) {
            Toast.makeText(context, "No multiple audio tracks found", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(context, R.style.CustomDialog)
            .setTitle("Select Audio Track")
            .setItems(audioList.toTypedArray()) { _, which ->
                val (group, idx) = trackGroups[which]
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, idx))
                    .build()
                Toast.makeText(context, "Switched to ${audioList[which]}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // 4. ✅ SPEED CONTROL DIALOG
    fun showSpeedDialog(context: Context, player: ExoPlayer) {
        val speeds = arrayOf("0.5x", "0.75x", "Normal (1.0x)", "1.25x", "1.5x", "2.0x")
        val values = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

        AlertDialog.Builder(context, R.style.CustomDialog)
            .setTitle("Playback Speed")
            .setItems(speeds) { _, which ->
                player.playbackParameters = PlaybackParameters(values[which])
                Toast.makeText(context, "Speed set to ${speeds[which]}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}