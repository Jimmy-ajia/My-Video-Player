package com.yourcompany.videoplayer

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.AnimatedVectorDrawable
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.*
import android.util.Rational
import android.view.*
import android.view.animation.*
import android.widget.*
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.*
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.SubtitleView
import androidx.recyclerview.widget.RecyclerView
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File
import java.util.*
import kotlin.math.abs
import kotlin.math.max

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private lateinit var playerView: androidx.media3.ui.PlayerView
    private lateinit var subtitleView: SubtitleView
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val controller: MediaController?
        get() = if (controllerFuture?.isDone == true) controllerFuture?.get() else null

    private var libVLC: LibVLC? = null
    private var vlcMediaPlayer: MediaPlayer? = null
    private lateinit var vlcVideoLayout: VLCVideoLayout
    private var isUsingVlc = false
    private var vlcResizeMode = 0
    private var fakeDuration = 0L

    private lateinit var audioManager: AudioManager
    private lateinit var volumeBar: ProgressBar
    private lateinit var brightnessBar: ProgressBar
    private lateinit var gestureContainer: LinearLayout
    private lateinit var gestureIcon: ImageView
    private lateinit var gestureText: TextView
    private lateinit var lockBtn: ImageButton
    private lateinit var unlockBtnTop: ImageButton
    private lateinit var controlsWrapper: View
    private lateinit var seekBar: SeekBar
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvTotalDuration: TextView
    private lateinit var playPauseBtn: ImageButton
    private lateinit var videoTitleTop: TextView
    private lateinit var speedBoostText: TextView
    private lateinit var centerFeedbackIcon: ImageView
    private lateinit var errorOverlay: View
    private lateinit var signatureMismatchOverlay: View
    private lateinit var gestureOverlay: View
    private lateinit var rvSuggested: RecyclerView
    private lateinit var suggestedContainer: View
    private lateinit var centerControls: View
    private lateinit var topBar: View
    private lateinit var bottomBar: View
    private lateinit var controlsBgOverlay: View
    private lateinit var rewOverlay: View
    private lateinit var ffwdOverlay: View
    
    private lateinit var previewFrameContainer: View
    private lateinit var ivPreviewFrame: ImageView
    private lateinit var tvPreviewTime: TextView
    
    private lateinit var speedSelectionCard: View
    private lateinit var speedIndicatorLeft: ImageView
    private lateinit var speedIndicatorRight: ImageView
    private lateinit var speedTexts: List<TextView>
    private val speedValues = floatArrayOf(4.0f, 3.0f, 2.5f, 2.0f, 1.5f)
    private var longPressStartY = 0f
    private var currentSpeedIndex = 3 // Default 2.0x
    private var isCardOnRight = true
    
    private var suggestedAdapter: SuggestedVideoAdapter? = null
    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hideControls() }
    private val updateHandler = Handler(Looper.getMainLooper())

    private var scaleFactor = 1.0f
    private var translateX = 0f
    private var translateY = 0f
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private lateinit var gestureDetector: GestureDetector

    private var isUserSeeking = false
    private var activeGesture = 0 
    private var isListExpanded = false
    private var firstTapX = 0f
    private var lastTapTime = 0L
    private val translationHiddenDp = 250f
    private var checkSignatureJob: Job? = null

    private val updateProgressAction = object : Runnable {
        override fun run() {
            if (!isUserSeeking) {
                if (isUsingVlc) {
                    vlcMediaPlayer?.let {
                        if (it.isPlaying) {
                            val currentPos = it.time
                            val duration = it.length
                            seekBar.progress = currentPos.toInt()
                            tvCurrentTime.text = formatTime(currentPos)
                            if (duration > 0) tvTotalDuration.text = formatTime(duration)
                            saveResumePosition(currentPos)
                        }
                    }
                } else {
                    controller?.let {
                        if (it.isPlaying || it.playbackState == Player.STATE_READY) {
                            val currentPos = it.currentPosition
                            val duration = if (fakeDuration > 0) fakeDuration else it.duration
                            seekBar.max = duration.toInt()
                            tvTotalDuration.text = formatTime(duration)
                            seekBar.progress = currentPos.toInt()
                            tvCurrentTime.text = formatTime(currentPos)
                            saveResumePosition(currentPos)
                        }
                    }
                }
            }
            updateHandler.postDelayed(this, 1000)
        }
    }

    private var isLocked = false
    private var videoList = ArrayList<VideoFile>()
    private var position = 0
    private var rotationMode = 0
    private var volumeVisualPercent = 0f
    private var wasPlayingBeforeScroll = false
    private var tempScrubPos = 0L
    private var initialSeekPos = 0L
    private var gestureStartX = 0f
    private var gestureCooldown = false
    
    private var frameRetriever: MediaMetadataRetriever? = null
    private var previewJob: Job? = null
    private var retrieverInitJob: Job? = null
    private val previewPositionChannel = Channel<Long>(Channel.CONFLATED)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            setContentView(R.layout.activity_player)
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
            audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            syncVolumePercent()

            if (initViews()) {
                initializeController()
                handleIntent(intent)
                setupGestures()
                showControls()
                startPreviewWorker()
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun startPreviewWorker() {
        lifecycleScope.launch(Dispatchers.IO) {
            for (pos in previewPositionChannel) {
                val retriever = frameRetriever ?: continue
                try {
                    val timeUs = pos * 1000
                    val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        retriever.getScaledFrameAtTime(
                            timeUs, 
                            MediaMetadataRetriever.OPTION_CLOSEST, 
                            320, 180
                        )
                    } else {
                        retriever.getFrameAtTime(
                            timeUs, 
                            MediaMetadataRetriever.OPTION_CLOSEST
                        )
                    }
                    if (bitmap != null) {
                        withContext(Dispatchers.Main) {
                            ivPreviewFrame.setImageBitmap(bitmap)
                        }
                    }
                } catch (e: Exception) { }
            }
        }
    }

    private fun syncVolumePercent() {
        val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val systemPct = (currentVol.toFloat() / (if(maxVol > 0) maxVol.toFloat() else 1f)) * 100f
        if (volumeVisualPercent <= 100f || systemPct < 100f) volumeVisualPercent = systemPct
    }

    private fun initializeController() {
        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture?.addListener({
            val controller = controller ?: return@addListener
            playerView.player = controller
            controller.addListener(object : Player.Listener {
                override fun onCues(cues: List<Cue>) { subtitleView.setCues(cues) }
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (!isUsingVlc) { updatePlayPauseBtn(isPlaying); if (isPlaying) resetTimer() }
                }
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) {
                        errorOverlay.visibility = View.GONE
                        signatureMismatchOverlay.visibility = View.GONE
                        val dur = if (fakeDuration > 0) fakeDuration else controller.duration
                        seekBar.max = dur.toInt()
                        tvTotalDuration.text = formatTime(dur)
                        updateHandler.post(updateProgressAction)
                    }
                    if (state == Player.STATE_ENDED) { saveResumePosition(0); playNext() }
                }
                override fun onPlayerError(error: PlaybackException) { 
                    val prefs = getSharedPreferences("SETTINGS", Context.MODE_PRIVATE)
                    if (!prefs.getBoolean("ALWAYS_VLC", false)) showHamzasModeRequired()
                }
            })
            if (videoList.isNotEmpty()) playCurrentVideo()
        }, MoreExecutors.directExecutor())
    }

    private fun updatePlayPauseBtn(isPlaying: Boolean) {
        val drawableRes = if (isPlaying) R.drawable.ic_play_to_pause else R.drawable.ic_pause_to_play
        val avd = ContextCompat.getDrawable(this, drawableRes) as? AnimatedVectorDrawable
        if (avd != null) { playPauseBtn.setImageDrawable(avd); avd.start() }
        else { playPauseBtn.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow) }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val list = intent.getSerializableExtra("VIDEO_LIST") as? ArrayList<VideoFile>
        if (list != null) { videoList.clear(); videoList.addAll(list) }
        position = intent.getIntExtra("POSITION", 0)
        if (videoList.isNotEmpty()) videoTitleTop.text = videoList[position].title
        playCurrentVideo()
    }

    private fun playCurrentVideo() {
        if (videoList.isEmpty()) return
        val path = videoList[position].path
        
        checkSignatureJob?.cancel()
        controller?.stop(); vlcMediaPlayer?.stop()
        
        errorOverlay.visibility = View.GONE
        signatureMismatchOverlay.visibility = View.GONE
        playerView.visibility = View.GONE
        vlcVideoLayout.visibility = View.GONE
        subtitleView.visibility = View.GONE

        retrieverInitJob?.cancel()
        previewJob?.cancel()
        retrieverInitJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                frameRetriever?.release()
                val retriever = MediaMetadataRetriever()
                val isEncrypted = VideoFile.isFileEncrypted(this@PlayerActivity, path)
                
                if (isEncrypted) {
                    val uri = Uri.parse("content://com.yourcompany.videoplayer.decryption$path")
                    contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        retriever.setDataSource(pfd.fileDescriptor)
                    }
                } else {
                    retriever.setDataSource(path)
                }
                frameRetriever = retriever
            } catch (e: Exception) { 
                e.printStackTrace()
                frameRetriever = null 
            }
        }

        checkSignatureJob = lifecycleScope.launch {
            val prefs = getSharedPreferences("SETTINGS", Context.MODE_PRIVATE)
            val hamzasMode = prefs.getBoolean("ALWAYS_VLC", false)

            val detection = withContext(Dispatchers.IO) {
                val status = VideoFile.checkSecurityStatus(this@PlayerActivity, path)
                Pair(status.isSecured, status.signatureMatches)
            }

            val isSecured = detection.first
            val sigMatches = detection.second

            withContext(Dispatchers.Main) {
                if (isSecured) {
                    if (sigMatches) {
                        if (hamzasMode) switchToVlcDecoder(true) else showHamzasModeRequired()
                    } else {
                        showSignatureMismatch()
                    }
                } else {
                    if (hamzasMode) switchToVlcDecoder(false) else playWithExo(path)
                }
                updateSuggestedList()
            }
        }
    }

    private fun showHamzasModeRequired() {
        errorOverlay.visibility = View.VISIBLE; playerView.visibility = View.GONE; vlcVideoLayout.visibility = View.GONE
        hideControls()
        controller?.stop(); vlcMediaPlayer?.stop()
    }

    private fun showSignatureMismatch() {
        signatureMismatchOverlay.visibility = View.VISIBLE; playerView.visibility = View.GONE; vlcVideoLayout.visibility = View.GONE
        hideControls()
        controller?.stop(); vlcMediaPlayer?.stop()
    }

    private fun playWithExo(path: String) {
        if (isUsingVlc) { vlcMediaPlayer?.stop(); isUsingVlc = false }
        vlcVideoLayout.visibility = View.GONE; playerView.visibility = View.VISIBLE; subtitleView.visibility = View.VISIBLE
        saveLastWatched()
        controller?.let { it.repeatMode = Player.REPEAT_MODE_OFF; it.setMediaItem(MediaItem.fromUri(path)); it.prepare(); it.play(); val resumePos = getResumePosition(path); if (resumePos > 0) it.seekTo(resumePos) }
    }

    private fun switchToVlcDecoder(isEncrypted: Boolean) {
        if (!isUsingVlc) { controller?.stop(); isUsingVlc = true }
        playerView.visibility = View.GONE; vlcVideoLayout.visibility = View.VISIBLE; subtitleView.visibility = View.GONE
        val path = videoList[position].path; saveLastWatched()
        
        if (libVLC == null) {
            val options = arrayListOf("-vvv", "--no-drop-late-frames", "--rtsp-tcp", "--file-caching=5000", "--network-caching=5000")
            libVLC = LibVLC(this, options)
        }
        
        if (vlcMediaPlayer == null) vlcMediaPlayer = MediaPlayer(libVLC) else vlcMediaPlayer?.stop()
        vlcMediaPlayer?.detachViews(); vlcMediaPlayer?.attachViews(vlcVideoLayout, null, true, true)

        try {
            val media = if (isEncrypted) {
                val decryptionUri = Uri.parse("content://com.yourcompany.videoplayer.decryption$path")
                val pfd = contentResolver.openFileDescriptor(decryptionUri, "r")
                if (pfd != null) Media(libVLC!!, pfd.fileDescriptor) else Media(libVLC!!, decryptionUri)
            } else {
                Media(libVLC!!, Uri.fromFile(File(path)))
            }
            media.addOption(":file-caching=5000")
            media.addOption(":network-caching=5000")
            vlcMediaPlayer?.media = media; media.release()
        } catch (e: Exception) { showToast("VLC Error") }

        vlcMediaPlayer?.setEventListener { event -> runOnUiThread { when (event.type) {
            MediaPlayer.Event.Playing -> { updatePlayPauseBtn(true); seekBar.max = vlcMediaPlayer?.length?.toInt() ?: 0; tvTotalDuration.text = formatTime(vlcMediaPlayer?.length ?: 0); updateHandler.post(updateProgressAction); val resumePos = getResumePosition(path); if (resumePos > 0) vlcMediaPlayer?.time = resumePos; resetTimer() }
            MediaPlayer.Event.Paused -> { updatePlayPauseBtn(false); resetTimer() }
            MediaPlayer.Event.EndReached -> { saveResumePosition(0); playNext() }
            MediaPlayer.Event.EncounteredError -> { if (isEncrypted) showSignatureMismatch() }
        } } }
        vlcMediaPlayer?.play()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (isLocked || activeGesture != 0 || gestureCooldown) return false
                if (isListExpanded) { collapseList(); return true }
                if (controlsWrapper.visibility == View.VISIBLE) {
                    if (isViewAtPos(lockBtn, e.x, e.y) || isViewAtPos(topBar, e.x, e.y) || isViewAtPos(centerControls, e.x, e.y) || isViewAtPos(bottomBar, e.x, e.y)) return false
                    hideControls()
                } else {
                    if (signatureMismatchOverlay.visibility != View.VISIBLE && errorOverlay.visibility != View.VISIBLE) showControls()
                }
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (isLocked || activeGesture != 0 || gestureCooldown) return false
                val width = resources.displayMetrics.widthPixels
                
                val currentZone = if (e.x < width * 0.35) -1 else if (e.x > width * 0.65) 1 else 0
                val firstZone = if (firstTapX < width * 0.35) -1 else if (firstTapX > width * 0.65) 1 else 0
                if (currentZone != firstZone) return false

                when (currentZone) {
                    -1 -> { if (isUsingVlc) vlcMediaPlayer?.let { it.time -= 10000 } else controller?.seekBack(); showSeekOverlay(false) }
                    1 -> { if (isUsingVlc) vlcMediaPlayer?.let { it.time += 10000 } else controller?.seekForward(); showSeekOverlay(true) }
                    0 -> { togglePlayPause(); showCenterFeedback(if(if(isUsingVlc) vlcMediaPlayer?.isPlaying == true else controller?.isPlaying == true) R.drawable.ic_pause else R.drawable.ic_play_arrow) }
                }
                resetTimer(); return true
            }
            override fun onLongPress(e: MotionEvent) { 
                if (isLocked || activeGesture != 0 || controlsWrapper.visibility == View.VISIBLE) return
                val isP = if(isUsingVlc) vlcMediaPlayer?.isPlaying == true else controller?.isPlaying == true
                if (!isP) return
                
                activeGesture = 3
                longPressStartY = e.y
                currentSpeedIndex = 3 // Default 2.0x
                
                showSpeedSelectionCard(e.x, e.y)
                updateSpeed(speedValues[currentSpeedIndex])
                speedBoostText.visibility = View.VISIBLE
                vibrate(20)
            }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dX: Float, dY: Float): Boolean {
                if (isLocked || e1 == null) return false
                val width = resources.displayMetrics.widthPixels
                val height = resources.displayMetrics.heightPixels
                if (e2.pointerCount > 1) activeGesture = 4
                if (activeGesture == 4) {
                    if (scaleFactor > 1.0f) {
                        val target = if (isUsingVlc) vlcVideoLayout else playerView
                        translateX = (translateX - dX).coerceIn(-max(0f, (target.width * scaleFactor - target.width) / 2f), max(0f, (target.width * scaleFactor - target.width) / 2f))
                        translateY = (translateY - dY).coerceIn(-max(0f, (target.height * scaleFactor - target.height) / 2f), max(0f, (target.height * scaleFactor - target.height) / 2f))
                        target.translationX = translateX; target.translationY = translateY
                    }
                    return true
                }
                if (activeGesture == 0) {
                    if (abs(dX) > abs(dY)) {
                        if (isListExpanded) return false
                        activeGesture = 1; gestureStartX = e2.x; wasPlayingBeforeScroll = if(isUsingVlc) vlcMediaPlayer?.isPlaying == true else controller?.isPlaying == true
                        initialSeekPos = if(isUsingVlc) vlcMediaPlayer?.time ?: 0L else controller?.currentPosition ?: 0L
                        tempScrubPos = initialSeekPos; isUserSeeking = true
                        if (controlsWrapper.visibility != View.VISIBLE) {
                            controlsWrapper.visibility = View.VISIBLE
                            bottomBar.visibility = View.VISIBLE; bottomBar.alpha = 1f; bottomBar.translationY = 0f
                            topBar.visibility = View.GONE; centerControls.visibility = View.GONE; lockBtn.visibility = View.GONE
                        }
                    } else {
                        if (isListExpanded) { if (dY < -25) { collapseList(); return true }; return false }
                        if (e1.x < width * 0.35) activeGesture = 2
                        else if (e1.x > width * 0.65) { activeGesture = 2; syncVolumePercent() }
                        else { if (dY > 25 && controlsWrapper.visibility == View.VISIBLE) { expandList(); return true }; return false }
                    }
                }
                if (activeGesture == 1) {
                    val dur = if(isUsingVlc) vlcMediaPlayer?.length ?: 0L else controller?.duration ?: 0L
                    if (dur <= 0) return false
                    tempScrubPos = (initialSeekPos + ((e2.x - gestureStartX) * (300000f / width)).toLong()).coerceIn(0L, dur)
                    seekBar.progress = tempScrubPos.toInt(); tvCurrentTime.text = formatTime(tempScrubPos)
                    updatePreviewFrame(tempScrubPos)
                } else if (activeGesture == 2) { if (e1.x < width * 0.35) adjustBrightness(dY / height) else adjustVolume(dY / height) }
                resetTimer(); return true
            }
        })
        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean { if (isLocked || controlsWrapper.visibility == View.VISIBLE) return false; activeGesture = 4; return true }
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scaleFactor = (scaleFactor * detector.scaleFactor).coerceIn(0.25f, 10.0f); val target = if (isUsingVlc) vlcVideoLayout else playerView; target.scaleX = scaleFactor; target.scaleY = scaleFactor
                if (scaleFactor <= 1.0f) { translateX = 0f; translateY = 0f; target.translationX = 0f; target.translationY = 0f }
                gestureContainer.visibility = View.VISIBLE; gestureIcon.setImageResource(R.drawable.ic_resize); gestureText.text = "${(scaleFactor * 100).toInt()}%"
                return true
            }
        })
        gestureOverlay.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                val now = System.currentTimeMillis()
                if (now - lastTapTime > ViewConfiguration.getDoubleTapTimeout()) firstTapX = event.x
                lastTapTime = now
            }
            scaleGestureDetector.onTouchEvent(event); gestureDetector.onTouchEvent(event)
            
            if (activeGesture == 3 && event.actionMasked == MotionEvent.ACTION_MOVE) {
                val deltaY = longPressStartY - event.y
                val step = 45
                val indexOffset = (deltaY / step).toInt()
                val newIndex = (3 - indexOffset).coerceIn(0, speedValues.size - 1)
                if (newIndex != currentSpeedIndex) {
                    currentSpeedIndex = newIndex
                    updateSpeed(speedValues[currentSpeedIndex])
                    highlightSpeedInCard(currentSpeedIndex)
                    vibrate(15)
                }
            }

            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                gestureContainer.visibility = View.GONE
                if (activeGesture == 1) { 
                    if (isUsingVlc) vlcMediaPlayer?.time = tempScrubPos else controller?.seekTo(tempScrubPos)
                    if (wasPlayingBeforeScroll) { if (isUsingVlc) vlcMediaPlayer?.play() else controller?.play() }
                    previewFrameContainer.visibility = View.GONE
                    Handler(Looper.getMainLooper()).postDelayed({ isUserSeeking = false }, 500) 
                    if (topBar.visibility == View.GONE) hideControls() 
                }
                else if (activeGesture == 3) { 
                    updateSpeed(1.0f)
                    speedBoostText.visibility = View.GONE
                    speedSelectionCard.visibility = View.GONE
                }
                if (activeGesture != 0) { gestureCooldown = true; Handler(Looper.getMainLooper()).postDelayed({ gestureCooldown = false }, 300) }
                activeGesture = 0; if (controlsWrapper.visibility == View.VISIBLE) resetTimer()
            }
            true
        }
    }

    private fun initViews(): Boolean {
        return try {
            playerView = findViewById(R.id.player_view); subtitleView = findViewById(R.id.subtitle_view)
            vlcVideoLayout = findViewById(R.id.vlc_video_layout); errorOverlay = findViewById(R.id.error_overlay)
            signatureMismatchOverlay = findViewById(R.id.signature_mismatch_overlay)
            gestureOverlay = findViewById(R.id.gesture_overlay); volumeBar = findViewById(R.id.volume_progress); brightnessBar = findViewById(R.id.brightness_progress)
            gestureContainer = findViewById(R.id.gesture_container); gestureIcon = findViewById(R.id.gesture_icon); gestureText = findViewById(R.id.gesture_text)
            lockBtn = findViewById(R.id.btn_lock); unlockBtnTop = findViewById(R.id.btn_unlock_top)
            controlsWrapper = findViewById(R.id.controls_wrapper); seekBar = findViewById(R.id.video_seekbar)
            tvCurrentTime = findViewById(R.id.tv_current_time); tvTotalDuration = findViewById(R.id.tv_total_duration); playPauseBtn = findViewById(R.id.btn_play_pause)
            videoTitleTop = findViewById(R.id.video_title_top); speedBoostText = findViewById(R.id.speed_boost_text); centerFeedbackIcon = findViewById(R.id.click_feedback_icon)
            rewOverlay = findViewById(R.id.rew_overlay); ffwdOverlay = findViewById(R.id.ffwd_overlay)
            rvSuggested = findViewById(R.id.rv_suggested_videos); suggestedContainer = findViewById(R.id.suggested_container); centerControls = findViewById(R.id.center_controls)
            topBar = findViewById(R.id.top_bar_scroll); bottomBar = findViewById(R.id.bottom_controls_container); controlsBgOverlay = findViewById(R.id.controls_bg_overlay)
            
            previewFrameContainer = findViewById(R.id.preview_frame_container)
            ivPreviewFrame = findViewById(R.id.iv_preview_frame)
            tvPreviewTime = findViewById(R.id.tv_preview_time)
            
            speedSelectionCard = findViewById(R.id.speed_selection_card)
            speedIndicatorLeft = findViewById(R.id.speed_indicator_left)
            speedIndicatorRight = findViewById(R.id.speed_indicator_right)
            speedTexts = listOf(
                findViewById(R.id.speed_4x),
                findViewById(R.id.speed_3x),
                findViewById(R.id.speed_25x),
                findViewById(R.id.speed_2x),
                findViewById(R.id.speed_15x)
            )

            suggestedContainer.translationY = translationHiddenDp * resources.displayMetrics.density
            findViewById<Button>(R.id.btn_close_mismatch).setOnClickListener { finish() }
            findViewById<Button>(R.id.btn_goto_settings).setOnClickListener { 
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            playPauseBtn.setOnClickListener { togglePlayPause(); resetTimer() }
            findViewById<ImageButton>(R.id.btn_back)?.setOnClickListener { finish() }
            findViewById<ImageButton>(R.id.btn_prev)?.setOnClickListener { playPrevious(); resetTimer() }
            findViewById<ImageButton>(R.id.btn_next)?.setOnClickListener { playNext(); resetTimer() }
            findViewById<ImageButton>(R.id.btn_audio_track)?.setOnClickListener { showAudioDialog(); resetTimer() }
            findViewById<ImageButton>(R.id.btn_subtitle)?.setOnClickListener { showSubtitleDialog(); resetTimer() }
            findViewById<ImageButton>(R.id.btn_resize)?.setOnClickListener { changeResizeMode(); resetTimer() }
            findViewById<ImageButton>(R.id.btn_speed)?.setOnClickListener { showSpeedDialog(); resetTimer() }
            findViewById<ImageButton>(R.id.btn_rotate)?.setOnClickListener { cycleRotationMode(); resetTimer() }
            findViewById<ImageButton>(R.id.btn_pip)?.setOnClickListener { enterPiP(); resetTimer() }
            lockBtn.setOnClickListener { isLocked = true; updateLockUI() }
            unlockBtnTop.setOnClickListener { isLocked = false; updateLockUI() }
            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { 
                    if (fromUser) { 
                        tvCurrentTime.text = formatTime(p.toLong())
                        updatePreviewFrame(p.toLong())
                    } 
                }
                override fun onStartTrackingTouch(s: SeekBar?) { isUserSeeking = true; hideHandler.removeCallbacks(hideRunnable) }
                override fun onStopTrackingTouch(s: SeekBar?) { 
                    if (isUsingVlc) vlcMediaPlayer?.time = seekBar.progress.toLong() else controller?.seekTo(seekBar.progress.toLong())
                    previewFrameContainer.visibility = View.GONE
                    Handler(Looper.getMainLooper()).postDelayed({ isUserSeeking = false }, 1000); resetTimer() 
                }
            })
            true
        } catch (e: Exception) { false }
    }

    private fun showSpeedSelectionCard(touchX: Float, touchY: Float) {
        speedSelectionCard.visibility = View.VISIBLE
        
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val cardWidth = speedSelectionCard.width.takeIf { it > 0 } ?: (100 * resources.displayMetrics.density).toInt()
        val cardHeight = speedSelectionCard.height.takeIf { it > 0 } ?: (180 * resources.displayMetrics.density).toInt()
        
        var targetX = touchX + 60
        isCardOnRight = true
        if (targetX + cardWidth > screenWidth - 50) {
            targetX = touchX - cardWidth - 60
            isCardOnRight = false
        }
        
        var targetY = touchY - (cardHeight / 2)
        targetY = targetY.coerceIn(50f, (screenHeight - cardHeight - 50).toFloat())
        
        speedSelectionCard.x = targetX
        speedSelectionCard.y = targetY
        speedSelectionCard.alpha = 0f
        speedSelectionCard.animate().alpha(1f).setDuration(200).start()
        
        highlightSpeedInCard(3)
    }

    private fun highlightSpeedInCard(index: Int) {
        speedTexts.forEachIndexed { i, textView ->
            if (i == index) {
                textView.setTextColor(Color.WHITE)
                textView.textSize = 20f
                textView.setTypeface(null, android.graphics.Typeface.BOLD)
                
                val indicator = if (isCardOnRight) speedIndicatorLeft else speedIndicatorRight
                val otherIndicator = if (isCardOnRight) speedIndicatorRight else speedIndicatorLeft
                
                indicator.visibility = View.VISIBLE
                otherIndicator.visibility = View.INVISIBLE
                
                textView.post {
                    val textCenterY = textView.y + (textView.height / 2f) - (indicator.height / 2f)
                    indicator.animate().y(textCenterY).setDuration(150).setInterpolator(DecelerateInterpolator()).start()
                }
            } else {
                textView.setTextColor(Color.parseColor("#80FFFFFF"))
                textView.textSize = 16f
                textView.setTypeface(null, android.graphics.Typeface.NORMAL)
            }
        }
        speedBoostText.text = "${speedValues[index]}x Speed"
    }

    private fun updateSpeed(speed: Float) {
        if (isUsingVlc) vlcMediaPlayer?.rate = speed else controller?.setPlaybackSpeed(speed)
    }

    private fun vibrate(ms: Long) {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            v.vibrate(ms)
        }
    }

    private fun updatePreviewFrame(pos: Long) {
        if (videoList.isEmpty()) return
        previewFrameContainer.visibility = View.VISIBLE
        tvPreviewTime.text = formatTime(pos)
        
        val dur = if(isUsingVlc) vlcMediaPlayer?.length ?: 0L else controller?.duration ?: 0L
        if (dur <= 0) return

        val seekBarWidth = seekBar.width - seekBar.paddingStart - seekBar.paddingEnd
        val thumbX = seekBar.paddingStart + (seekBarWidth * (pos.toFloat() / dur))
        
        val location = IntArray(2)
        seekBar.getLocationInWindow(location)
        val parentLocation = IntArray(2)
        (previewFrameContainer.parent as View).getLocationInWindow(parentLocation)
        
        val targetX = location[0] + thumbX - parentLocation[0] - (previewFrameContainer.width / 2)
        previewFrameContainer.x = targetX.coerceIn(0f, (previewFrameContainer.parent as View).width.toFloat() - previewFrameContainer.width)

        previewPositionChannel.trySend(pos)
    }

    private fun expandList() {
        if (isListExpanded) return
        isListExpanded = true; suggestedContainer.visibility = View.VISIBLE; suggestedContainer.animate().translationY(0f).setDuration(500).setInterpolator(DecelerateInterpolator()).start()
        bottomBar.animate().alpha(0f).translationY(200f).setDuration(300).start(); topBar.animate().alpha(0f).translationY(-200f).setDuration(300).start()
        controlsBgOverlay.visibility = View.VISIBLE; controlsBgOverlay.animate().alpha(1f).setDuration(300).start(); applyBlur(true)
    }

    private fun collapseList() {
        if (!isListExpanded) return
        isListExpanded = false; val density = resources.displayMetrics.density
        suggestedContainer.animate().translationY(translationHiddenDp * density).setDuration(500).setInterpolator(DecelerateInterpolator()).start()
        if (controlsWrapper.visibility == View.VISIBLE) { 
            bottomBar.visibility = View.VISIBLE; topBar.visibility = View.VISIBLE; centerControls.visibility = View.VISIBLE; lockBtn.visibility = View.VISIBLE
            bottomBar.animate().alpha(1f).translationY(0f).setDuration(300).start(); topBar.animate().alpha(1f).translationY(0f).setDuration(300).start() 
        }
        else { controlsBgOverlay.animate().alpha(0f).setDuration(300).withEndAction { controlsBgOverlay.visibility = View.GONE }.start() }
        applyBlur(false)
    }

    private fun applyBlur(blur: Boolean) {
        val target = if (isUsingVlc) vlcVideoLayout else playerView
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (blur) { target.setRenderEffect(RenderEffect.createBlurEffect(18f, 18f, Shader.TileMode.CLAMP)); centerControls.animate().alpha(0f).scaleX(0.7f).scaleY(0.7f).setDuration(400).start(); lockBtn.animate().alpha(0f).scaleX(0.7f).scaleY(0.7f).setDuration(400).start(); target.animate().scaleX(0.95f).scaleY(0.95f).setDuration(500).start() }
            else { target.setRenderEffect(null); centerControls.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(400).start(); lockBtn.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(400).start(); target.animate().scaleX(scaleFactor).scaleY(scaleFactor).setDuration(500).start() }
        }
    }

    private fun showControls() {
        if (isLocked) { controlsWrapper.visibility = View.GONE; suggestedContainer.visibility = View.GONE }
        else {
            if (signatureMismatchOverlay.visibility == View.VISIBLE || errorOverlay.visibility == View.VISIBLE) return
            controlsWrapper.visibility = View.VISIBLE
            bottomBar.visibility = View.VISIBLE; topBar.visibility = View.VISIBLE; centerControls.visibility = View.VISIBLE; lockBtn.visibility = View.VISIBLE
            controlsBgOverlay.visibility = View.VISIBLE; controlsBgOverlay.alpha = 0f; controlsBgOverlay.animate().alpha(1f).setDuration(450).start()
            topBar.translationY = -200f; topBar.alpha = 0f; topBar.animate().translationY(0f).alpha(1f).setDuration(450).setInterpolator(DecelerateInterpolator()).start()
            bottomBar.translationY = 200f; bottomBar.alpha = 0f; bottomBar.animate().translationY(0f).alpha(1f).setDuration(450).setInterpolator(DecelerateInterpolator()).start()
            centerControls.scaleX = 0.5f; centerControls.scaleY = 0.5f; centerControls.alpha = 0f; centerControls.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(500).setInterpolator(OvershootInterpolator(1.2f)).start()
            lockBtn.scaleX = 0.5f; lockBtn.scaleY = 0.5f; lockBtn.alpha = 0f; lockBtn.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(500).setInterpolator(OvershootInterpolator(1.2f)).start()
        }
        resetTimer()
    }

    private fun hideControls() {
        if (isListExpanded) collapseList()
        topBar.animate().translationY(-200f).alpha(0f).setDuration(400).setInterpolator(AccelerateInterpolator()).start()
        bottomBar.animate().translationY(200f).alpha(0f).setDuration(400).setInterpolator(AccelerateInterpolator()).start()
        centerControls.animate().scaleX(0.7f).scaleY(0.7f).alpha(0f).setDuration(350).setInterpolator(AnticipateInterpolator()).start()
        lockBtn.animate().scaleX(0.7f).scaleY(0.7f).alpha(0f).setDuration(350).setInterpolator(AnticipateInterpolator()).withEndAction { controlsWrapper.visibility = View.GONE }.start()
        controlsBgOverlay.animate().alpha(0f).setDuration(400).withEndAction { controlsBgOverlay.visibility = View.GONE }.start()
        hideHandler.removeCallbacks(hideRunnable)
    }

    private fun isViewAtPos(view: View, x: Float, y: Float): Boolean {
        if (view.visibility != View.VISIBLE) return false
        val location = IntArray(2); view.getLocationOnScreen(location)
        return x >= location[0] && x <= location[0] + view.width && y >= location[1] && y <= location[1] + view.height
    }

    private fun togglePlayPause() {
        val isP = if(isUsingVlc) vlcMediaPlayer?.isPlaying == true else controller?.isPlaying == true
        if (isUsingVlc) { if (isP) vlcMediaPlayer?.pause() else vlcMediaPlayer?.play() } else { if (isP) controller?.pause() else controller?.play() }
    }

    private fun playNext() { if (position < videoList.size - 1) { position++; videoTitleTop.text = videoList[position].title; playCurrentVideo() } }
    private fun playPrevious() { if (position > 0) { position--; videoTitleTop.text = videoList[position].title; playCurrentVideo() } }
    private fun formatTime(ms: Long): String { val s = ms / 1000; val sec = s % 60; val min = (s / 60) % 60; val hr = s / 3600; return if (hr > 0) String.format("%02d:%02d:%02d", hr, min, sec) else String.format("%02d:%02d", min, sec) }
    private fun getResumePosition(p: String): Long = getSharedPreferences("RESUME_INFO", Context.MODE_PRIVATE).getLong(p, 0L)
    private fun saveResumePosition(pos: Long) {
        if (videoList.isEmpty() || position >= videoList.size) return
        val path = videoList[position].path
        val totalDur = if (isUsingVlc) vlcMediaPlayer?.length ?: 0L else controller?.duration ?: 0L
        val finalPos = if (totalDur > 10000 && (pos >= totalDur - 5000 || pos >= totalDur * 0.98)) 0L else pos
        getSharedPreferences("RESUME_INFO", Context.MODE_PRIVATE).edit().putLong(path, finalPos).apply()
    }
    private fun saveLastWatched() { if (videoList.isEmpty() || position >= videoList.size) return; val v = videoList[position]; getSharedPreferences("PLAY_INFO", Context.MODE_PRIVATE).edit().putLong("LAST_ID", v.id).putString("LAST_PATH", v.path).apply() }
    private fun updateLockUI() { if (isLocked) { hideControls(); unlockBtnTop.visibility = View.VISIBLE } else { unlockBtnTop.visibility = View.GONE; showControls() } }
    private fun resetTimer() { hideHandler.removeCallbacks(hideRunnable); if (if(isUsingVlc) vlcMediaPlayer?.isPlaying == true else controller?.isPlaying == true) hideHandler.postDelayed(hideRunnable, 4000) }
    private fun showSeekOverlay(isForward: Boolean) { val overlay = if (isForward) ffwdOverlay else rewOverlay; overlay.visibility = View.VISIBLE; overlay.alpha = 0f; overlay.animate().alpha(1f).setDuration(200).withEndAction { overlay.animate().alpha(0f).setDuration(500).setStartDelay(300).withEndAction { overlay.visibility = View.GONE }.start() }.start() }
    private fun adjustVolume(deltaP: Float) { gestureContainer.visibility = View.VISIBLE; gestureIcon.setImageResource(R.drawable.ic_volume); volumeBar.visibility = View.VISIBLE; brightnessBar.visibility = View.GONE; volumeBar.max = 300; val maxH = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC); volumeVisualPercent = (volumeVisualPercent + (deltaP * 200f)).coerceIn(0f, 300f); if (volumeVisualPercent <= 100f) { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (volumeVisualPercent / 100 * maxH).toInt(), 0); if (isUsingVlc) vlcMediaPlayer?.volume = 100; gestureText.text = "${volumeVisualPercent.toInt()}%" } else { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxH, 0); if (isUsingVlc) { vlcMediaPlayer?.volume = volumeVisualPercent.toInt(); gestureText.text = "Boost: ${volumeVisualPercent.toInt()}%" } else gestureText.text = "100% (Max)" }; volumeBar.progress = volumeVisualPercent.toInt() }
    private fun adjustBrightness(deltaP: Float) { gestureContainer.visibility = View.VISIBLE; gestureIcon.setImageResource(R.drawable.ic_brightness); brightnessBar.visibility = View.VISIBLE; volumeBar.visibility = View.GONE; val lp = window.attributes; if (lp.screenBrightness < 0) lp.screenBrightness = 0.5f; lp.screenBrightness = (lp.screenBrightness + deltaP).coerceIn(0.01f, 1.0f); window.attributes = lp; brightnessBar.progress = (lp.screenBrightness * 100).toInt(); gestureText.text = "${brightnessBar.progress}%" }
    private fun cycleRotationMode() { rotationMode = (rotationMode + 1) % 3; when (rotationMode) { 0 -> { requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR; showToast("Auto") }; 1 -> { requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE; showToast("Landscape") }; 2 -> { requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT; showToast("Portrait") } } }
    private fun showToast(m: String) { Toast.makeText(this, m, Toast.LENGTH_SHORT).apply { setGravity(Gravity.CENTER, 0, 0); show() } }
    private fun showSpeedDialog() { val s = arrayOf("0.25x", "0.5x", "0.75x", "Normal", "1.25x", "1.5x", "1.75x", "2.0x"); val v = floatArrayOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f); AlertDialog.Builder(this, R.style.WhiteDialog).setTitle("Playback Speed").setItems(s) { _: DialogInterface, w: Int -> if (isUsingVlc) vlcMediaPlayer?.rate = v[w] else controller?.setPlaybackSpeed(v[w]); resetTimer() }.show() }
    private fun showAudioDialog() {
        if (isUsingVlc) { val tracks = vlcMediaPlayer?.audioTracks ?: return; val aL = mutableListOf<String>(); val aI = mutableListOf<Int>(); for (t in tracks) if (t.id != -1) { aL.add(t.name ?: "Audio ${aL.size + 1}"); aI.add(t.id) }; if (aL.isEmpty()) return; AlertDialog.Builder(this, R.style.WhiteDialog).setTitle("Select Audio").setItems(aL.toTypedArray()) { _: DialogInterface, w: Int -> vlcMediaPlayer?.setAudioTrack(aI[w]); resetTimer() }.show() }
        else { val tracks = controller?.currentTracks ?: return; val aL = mutableListOf<String>(); val tG = mutableListOf<Pair<Tracks.Group, Int>>(); for (g in tracks.groups) if (g.type == C.TRACK_TYPE_AUDIO) for (i in 0 until g.length) { val f = g.getTrackFormat(i); aL.add(f.label ?: f.language ?: "Audio ${aL.size + 1}"); tG.add(g to i) }; if (aL.isEmpty()) return; AlertDialog.Builder(this, R.style.WhiteDialog).setTitle("Select Audio").setItems(aL.toTypedArray()) { _: DialogInterface, w: Int -> val (g, i) = tG[w]; controller?.trackSelectionParameters = controller?.trackSelectionParameters?.buildUpon()?.setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))?.build()!!; resetTimer() }.show() }
    }
    private fun showSubtitleDialog() {
        if (isUsingVlc) { val tracks = vlcMediaPlayer?.spuTracks ?: return; val sL = mutableListOf("Off"); val sI = mutableListOf(-1); for (t in tracks) if (t.id != -1) { sL.add(t.name ?: "Subtitle ${sL.size}"); sI.add(t.id) }; AlertDialog.Builder(this, R.style.WhiteDialog).setTitle("Subtitles").setItems(sL.toTypedArray()) { _: DialogInterface, w: Int -> vlcMediaPlayer?.setSpuTrack(sI[w]); resetTimer() }.show() }
        else { val tracks = controller?.currentTracks ?: return; val sL = mutableListOf("Off"); val tG = mutableListOf<Pair<Tracks.Group, Int>?>(null); for (g in tracks.groups) if (g.type == C.TRACK_TYPE_TEXT) for (i in 0 until g.length) { val f = g.getTrackFormat(i); sL.add(f.label ?: f.language ?: "Subtitle ${sL.size}"); tG.add(g to i) }; AlertDialog.Builder(this, R.style.WhiteDialog).setTitle("Subtitles").setItems(sL.toTypedArray()) { _: DialogInterface, w: Int -> if (w == 0) controller?.trackSelectionParameters = controller?.trackSelectionParameters?.buildUpon()?.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)?.build()!! else { val p = tG[w]!!; controller?.trackSelectionParameters = controller?.trackSelectionParameters?.buildUpon()?.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)?.setOverrideForType(TrackSelectionOverride(p.first.mediaTrackGroup, p.second))?.build()!! }; resetTimer() }.show() }
    }
    private fun showCenterFeedback(i: Int) { centerFeedbackIcon.setImageResource(i); centerFeedbackIcon.visibility = View.VISIBLE; centerFeedbackIcon.alpha = 0f; centerFeedbackIcon.scaleX = 0.5f; centerFeedbackIcon.scaleY = 0.5f; centerFeedbackIcon.animate().alpha(1f).scaleX(1.2f).scaleY(1.2f).setDuration(250).withEndAction { centerFeedbackIcon.animate().alpha(0f).scaleX(1.5f).scaleY(1.5f).setDuration(250).withEndAction { centerFeedbackIcon.visibility = View.GONE }.start() }.start() }
    private fun changeResizeMode() {
        val t = if (isUsingVlc) vlcVideoLayout else playerView; scaleFactor = 1.0f; translateX = 0f; translateY = 0f; t.scaleX = 1.0f; t.scaleY = 1.0f; t.translationX = 0f; t.translationY = 0f
        if (isUsingVlc) { vlcMediaPlayer?.let { vlcResizeMode = (vlcResizeMode + 1) % 3; when (vlcResizeMode) { 0 -> { it.videoScale = MediaPlayer.ScaleType.SURFACE_FIT_SCREEN; it.aspectRatio = null; showToast("Fit") }; 1 -> { it.videoScale = MediaPlayer.ScaleType.SURFACE_FILL; it.aspectRatio = null; showToast("Stretch") }; 2 -> { val m = resources.displayMetrics; it.aspectRatio = "${m.widthPixels}:${m.heightPixels}"; it.videoScale = MediaPlayer.ScaleType.SURFACE_FILL; showToast("Zoom") } } } }
        else { playerView.resizeMode = when (playerView.resizeMode) { AspectRatioFrameLayout.RESIZE_MODE_FIT -> { showToast("Fill"); AspectRatioFrameLayout.RESIZE_MODE_FILL }; AspectRatioFrameLayout.RESIZE_MODE_FILL -> { showToast("Zoom"); AspectRatioFrameLayout.RESIZE_MODE_ZOOM }; else -> { showToast("Fit"); AspectRatioFrameLayout.RESIZE_MODE_FIT } } }
        resetTimer()
    }
    private fun updateSuggestedList() {
        if (videoList.isEmpty()) return
        lifecycleScope.launch(Dispatchers.IO) {
            val currentVideo = videoList[position]
            val nextVideo = if (position + 1 < videoList.size) videoList[position + 1] else null
            val prevVideo = if (position - 1 >= 0) videoList[position - 1] else null
            val otherVideos = videoList.toMutableList()
            otherVideos.removeAt(position)
            val finalSuggested = mutableListOf<VideoFile>()
            nextVideo?.let { finalSuggested.add(it); otherVideos.remove(it) }
            prevVideo?.let { finalSuggested.add(it); otherVideos.remove(it) }
            otherVideos.shuffle()
            finalSuggested.addAll(otherVideos.take(8 - finalSuggested.size))
            val encryptionMap = mutableMapOf<String, Boolean>()
            finalSuggested.forEach { video -> encryptionMap[video.path] = VideoFile.isFileEncrypted(this@PlayerActivity, video.path) }
            withContext(Dispatchers.Main) {
                if (suggestedAdapter == null) { 
                    suggestedAdapter = SuggestedVideoAdapter(finalSuggested, encryptionMap) { selectedVideo -> 
                        val actualPos = videoList.indexOf(selectedVideo)
                        if (actualPos != -1) { 
                            position = actualPos
                            videoTitleTop.text = videoList[position].title
                            if (isListExpanded) collapseList()
                            playCurrentVideo()
                            resetTimer() 
                        } 
                    } 
                    rvSuggested.adapter = suggestedAdapter 
                }
                else {
                    suggestedAdapter?.updateData(finalSuggested, encryptionMap)
                }
            }
        }
    }

    private fun enterPiP() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val params = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()
            } else null

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && params != null) {
                enterPictureInPictureMode(params)
            } else {
                enterPictureInPictureMode()
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val isP = if (isUsingVlc) vlcMediaPlayer?.isPlaying == true else controller?.isPlaying == true
        if (isP) enterPiP()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            hideControls()
            suggestedContainer.visibility = View.GONE
        } else {
            if (controlsWrapper.visibility == View.GONE) showControls()
        }
    }

    override fun onStart() { super.onStart(); if (isUsingVlc) { vlcMediaPlayer?.detachViews(); vlcMediaPlayer?.attachViews(vlcVideoLayout, null, true, true) } }
    override fun onStop() { super.onStop(); if (isUsingVlc) vlcMediaPlayer?.detachViews() }
    override fun onDestroy() { 
        super.onDestroy()
        updateHandler.removeCallbacks(updateProgressAction)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        vlcMediaPlayer?.release()
        libVLC?.release()
        frameRetriever?.release()
        previewJob?.cancel()
        retrieverInitJob?.cancel()
    }
}
