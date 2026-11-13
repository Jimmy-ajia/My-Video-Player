package com.yourcompany.videoplayer

import android.app.Notification
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList

@UnstableApi
internal class PlaybackMediaNotificationProvider(context: Context) :
    MediaNotification.Provider {
    private val defaultProvider = DefaultMediaNotificationProvider.Builder(context)
        .setChannelId("playback_channel")
        .setChannelName(R.string.app_name)
        .build()
    private val handler = Handler(Looper.getMainLooper())
    private var activeSession: MediaSession? = null
    private var mediaButtons: ImmutableList<CommandButton> = ImmutableList.of()
    private var actionFactory: MediaNotification.ActionFactory? = null
    private var callback: MediaNotification.Provider.Callback? = null

    private val refreshNotification = object : Runnable {
        override fun run() {
            val session = activeSession ?: return
            val factory = actionFactory ?: return
            val notificationCallback = callback ?: return
            if (!session.player.isPlaying) return

            notificationCallback.onNotificationChanged(
                createNotification(session, mediaButtons, factory, notificationCallback)
            )
            scheduleRefresh()
        }
    }

    override fun createNotification(
        mediaSession: MediaSession,
        mediaButtons: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        callback: MediaNotification.Provider.Callback
    ): MediaNotification {
        activeSession = mediaSession
        this.mediaButtons = mediaButtons
        this.actionFactory = actionFactory
        this.callback = callback
        val notification = defaultProvider.createNotification(
            mediaSession,
            mediaButtons,
            actionFactory,
            callback
        ).withPlaybackTime(mediaSession)
        scheduleRefresh()
        return notification
    }

    override fun handleCustomCommand(
        mediaSession: MediaSession,
        action: String,
        extras: Bundle
    ): Boolean = defaultProvider.handleCustomCommand(mediaSession, action, extras)

    fun release() {
        handler.removeCallbacksAndMessages(null)
        activeSession = null
        actionFactory = null
        callback = null
    }

    private fun scheduleRefresh() {
        handler.removeCallbacks(refreshNotification)
        if (activeSession?.player?.isPlaying == true) {
            handler.postDelayed(refreshNotification, NOTIFICATION_REFRESH_MS)
        }
    }

    private fun MediaNotification.withPlaybackTime(session: MediaSession): MediaNotification {
        val player = session.player
        val time = PlaybackNotificationTime.format(player.currentPosition, player.duration)
        notification.extras?.putCharSequence(Notification.EXTRA_TEXT, time)
        notification.contentView?.setTextViewText(android.R.id.text2, time)
        notification.bigContentView?.setTextViewText(android.R.id.text2, time)
        notification.headsUpContentView?.setTextViewText(android.R.id.text2, time)
        return this
    }

    private companion object {
        const val NOTIFICATION_REFRESH_MS = 1_000L
    }
}
