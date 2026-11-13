package com.yourcompany.videoplayer

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null
    private var notificationProvider: PlaybackMediaNotificationProvider? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        
        // 🔥 Optimization for HEVC 10-bit
        val renderersFactory = DefaultRenderersFactory(this)
        // Hardware ko priority dein (ON), PREFER extension ko hata kar
        renderersFactory.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        renderersFactory.setEnableDecoderFallback(true)

        // Increase buffer for high bitrate HEVC files
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                50000,  // Min buffer
                100000, // Max buffer
                2500,   // Buffer for playback
                5000    // Buffer after rebuffering
            )
            .setBackBuffer(10000, true)
            .build()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        // Use a TrackSelector that is more lenient with decoder capabilities
        val trackSelector = DefaultTrackSelector(this)
        trackSelector.setParameters(
            trackSelector.buildUponParameters()
                .setForceHighestSupportedBitrate(true)
        )

        val playerBuilder = ExoPlayer.Builder(this, renderersFactory)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .setSeekParameters(SeekParameters.CLOSEST_SYNC)
        
        val exoPlayer = playerBuilder.build()
        this.player = exoPlayer
        
        val intent = Intent(this, PlayerActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, 
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, exoPlayer)
            .setSessionActivity(pendingIntent)
            .build()

        val notificationProvider = PlaybackMediaNotificationProvider(this)
        this.notificationProvider = notificationProvider
        setMediaNotificationProvider(notificationProvider)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        notificationProvider?.release()
        notificationProvider = null
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
