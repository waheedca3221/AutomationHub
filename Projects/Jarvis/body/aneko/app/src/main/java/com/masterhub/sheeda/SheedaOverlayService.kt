package com.masterhub.sheeda

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Calendar

class SheedaOverlayService : Service() {

    companion object {
        private const val TAG = "SheedaOverlay"
    }

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var ivSheeda: ImageView
    private lateinit var tvBubble: TextView

    private lateinit var client: JarvisTailnetClient
    private lateinit var menuManager: SheedaMenuManager
    private lateinit var audioPlayer: SheedaAudioPlayer

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var speechEndTime = 0L

    private var currentState = SheedaState.IDLE
    private var currentFrameIdx = 0
    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    private var hasLongClicked = false

    private lateinit var windowParams: WindowManager.LayoutParams

    private val longPressRunnable = Runnable {
        if (!isDragging) {
            hasLongClicked = true
            vibrateFeedback()
            menuManager.showMenu(windowParams.x, windowParams.y)
        }
    }

    private val animationRunnable = object : Runnable {
        override fun run() {
            checkQuietHoursState()
            advanceAnimationFrame()
            handler.postDelayed(this, 180)
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        client = JarvisTailnetClient(this)

        audioPlayer = SheedaAudioPlayer(
            context = this,
            client = client,
            onSpeechStateChanged = { speaking ->
                if (speaking) {
                    setState(SheedaState.SPEAKING)
                } else {
                    if (currentState == SheedaState.SPEAKING) {
                        setState(SheedaState.IDLE)
                    }
                }
            },
            onSentenceSpoken = { sentence ->
                tvBubble.text = sentence
            }
        )

        menuManager = SheedaMenuManager(
            context = this,
            windowManager = windowManager,
            client = client,
            onBubbleMessage = { msg, isUserTalk ->
                showSpeechBubble(msg, isUserTalk = isUserTalk)
            },
            onStateChange = { newState -> setState(newState) },
            onTalkRequested = { startSpeechListening() }
        )

        initOverlayView()
        handler.post(animationRunnable)
    }

    private fun startForegroundNotification() {
        val channelId = "sheeda_overlay_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Sheeda Floating Companion",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Foreground floating screen pet overlay"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Sheeda is Active")
            .setContentText("Tap cat to Talk aloud • Long-press for menu")
            .setSmallIcon(R.drawable.sheeda_stand_0)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(1001, notification)
    }

    private fun initOverlayView() {
        val inflater = LayoutInflater.from(this)
        overlayView = inflater.inflate(R.layout.overlay_pet, null)
        ivSheeda = overlayView.findViewById(R.id.iv_sheeda_pet)
        tvBubble = overlayView.findViewById(R.id.tv_speech_bubble)

        windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 500
        }

        overlayView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = windowParams.x
                    initialY = windowParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    hasLongClicked = false
                    handler.postDelayed(longPressRunnable, 500)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 12 || Math.abs(dy) > 12) {
                        isDragging = true
                        handler.removeCallbacks(longPressRunnable)
                        setState(SheedaState.DRAG)
                        windowParams.x = initialX + dx
                        windowParams.y = initialY + dy
                        windowManager.updateViewLayout(overlayView, windowParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPressRunnable)
                    if (isDragging) {
                        setState(SheedaState.ONFLOOR)
                        handler.postDelayed({ setState(SheedaState.IDLE) }, 1000)
                    } else if (!hasLongClicked) {
                        // CLEAN TAP = TALK (start listening immediately)
                        // Barge-in: stop any audio playback immediately
                        if (audioPlayer.isSpeaking) {
                            audioPlayer.stop()
                        }
                        startSpeechListening()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
                    true
                }
                else -> false
            }
        }

        windowManager.addView(overlayView, windowParams)
    }

    private fun vibrateFeedback() {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(45, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(45)
            }
        } catch (_: Exception) {}
    }

    fun startSpeechListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            showSpeechBubble("Microphone permission required! Open app.", durationMs = 3000, skipAudio = true)
            return
        }

        if (isListening) {
            stopSpeechListening()
        }

        audioPlayer.stop()
        setState(SheedaState.LISTENING)
        showSpeechBubble("Listening...", durationMs = 10000, skipAudio = true)

        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        isListening = true
                        setState(SheedaState.LISTENING)
                    }

                    override fun onBeginningOfSpeech() {
                        setState(SheedaState.LISTENING)
                    }

                    override fun onRmsChanged(rmsdB: Float) {}

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        isListening = false
                        speechEndTime = System.currentTimeMillis()
                        setState(SheedaState.THINKING)
                        showSpeechBubble("Thinking...", durationMs = 12000, skipAudio = true)
                    }

                    override fun onError(error: Int) {
                        isListening = false
                        Log.w(TAG, "SpeechRecognizer error: $error")
                        handler.post {
                            if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                                hideSpeechBubble()
                                setState(SheedaState.IDLE)
                            } else {
                                showSpeechBubble("Speech error ($error)", durationMs = 2500, skipAudio = true)
                                handler.postDelayed({ setState(SheedaState.IDLE) }, 2500)
                            }
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        isListening = false
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val spokenQuery = matches?.firstOrNull()?.trim() ?: ""

                        if (spokenQuery.isNotBlank()) {
                            showSpeechBubble(spokenQuery, durationMs = 12000, skipAudio = true)
                            handleUserVoiceTurn(spokenQuery)
                        } else {
                            hideSpeechBubble()
                            setState(SheedaState.IDLE)
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val partial = matches?.firstOrNull()?.trim()
                        if (!partial.isNullOrBlank()) {
                            tvBubble.text = partial
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                putExtra(RecognizerIntent.EXTRA_ADDITIONAL_LANGUAGES, arrayOf("ur-PK", "ur", "en-PK"))
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-US")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }

            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start speech recognition: $e")
            showSpeechBubble("Speech recognition unavailable", durationMs = 3000, skipAudio = true)
            setState(SheedaState.IDLE)
        }
    }

    private fun stopSpeechListening() {
        isListening = false
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        speechRecognizer = null
    }

    private fun handleUserVoiceTurn(userText: String) {
        setState(SheedaState.THINKING)
        serviceScope.launch {
            val result = client.sendChatCompletion(userText)
            val reply = result.getOrElse {
                "Meow! Could not connect to Jarvis brain on tailnet."
            }

            val latencyMs = System.currentTimeMillis() - speechEndTime
            Log.i(TAG, "End of speech to reply received: ${latencyMs}ms | Reply: $reply")

            // User's own talk is ALWAYS spoken aloud (bypasses quiet hours)
            showSpeechBubble(reply, durationMs = 6000, isUserTalk = true)
        }
    }

    private fun advanceAnimationFrame() {
        val frames = SheedaSkin.getFramesForState(currentState)
        if (frames.isNotEmpty()) {
            currentFrameIdx = (currentFrameIdx + 1) % frames.size
            ivSheeda.setImageResource(frames[currentFrameIdx])
        }
    }

    fun setState(state: SheedaState) {
        if (currentState != state) {
            currentState = state
            currentFrameIdx = 0
            val frames = SheedaSkin.getFramesForState(currentState)
            if (frames.isNotEmpty()) {
                ivSheeda.setImageResource(frames[0])
            }
        }
    }

    fun isQuietHours(): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return hour >= 23 || hour < 6
    }

    private fun checkQuietHoursState() {
        // Quiet hours: 23:00 to 06:00 -> auto curl up to sleep unless actively speaking/listening/dragging
        if (isQuietHours()) {
            if (currentState != SheedaState.SLEEP &&
                currentState != SheedaState.DRAG &&
                currentState != SheedaState.SPEAKING &&
                currentState != SheedaState.LISTENING &&
                currentState != SheedaState.THINKING
            ) {
                setState(SheedaState.SLEEP)
            }
        } else if (currentState == SheedaState.SLEEP) {
            setState(SheedaState.IDLE)
        }
    }

    fun showSpeechBubble(
        text: String,
        durationMs: Long = 5000,
        isUserTalk: Boolean = false,
        skipAudio: Boolean = false
    ) {
        tvBubble.text = text
        tvBubble.visibility = View.VISIBLE
        handler.removeCallbacksAndMessages(tvBubble)

        if (!skipAudio && !menuManager.isMuted) {
            audioPlayer.speakText(
                text = text,
                isUserTalk = isUserTalk,
                isQuietHours = isQuietHours()
            )
        }

        handler.postDelayed({
            hideSpeechBubble()
            if (currentState == SheedaState.SPEAKING || currentState == SheedaState.THINKING) {
                setState(SheedaState.IDLE)
            }
        }, durationMs)
    }

    fun hideSpeechBubble() {
        tvBubble.visibility = View.GONE
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(animationRunnable)
        handler.removeCallbacks(longPressRunnable)
        stopSpeechListening()
        audioPlayer.release()
        menuManager.dismissMenu()
        if (::overlayView.isInitialized) {
            windowManager.removeView(overlayView)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
