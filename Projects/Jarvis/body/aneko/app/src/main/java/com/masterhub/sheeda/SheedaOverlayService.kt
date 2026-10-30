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
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar

class SheedaOverlayService : Service() {

    companion object {
        private const val TAG = "SheedaOverlay"
        private const val CHANNEL_ID = "sheeda_overlay_channel"
        private const val NOTIFICATION_ID = 1001

        fun isReminderQuery(text: String): Boolean {
            val low = text.lowercase().trim()
            val reminderTriggers = listOf(
                "remind", "reminder", "snooze", "cancel", "what are my reminders",
                "list reminders", "show reminders", "check reminders"
            )
            if (reminderTriggers.any { low.contains(it) }) return true
            return Regex("^(in\\s+\\d+|tomorrow\\s+morning|at\\s+\\d+|every\\s+weekday)").containsMatchIn(low)
        }
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
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    private var hasLongClicked = false

    private lateinit var windowParams: WindowManager.LayoutParams
    private var wakeLock: PowerManager.WakeLock? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var presenceJob: Job? = null

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

        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sheeda:presence_event")

        audioPlayer = SheedaAudioPlayer(
            context = this,
            client = client,
            onSpeechStateChanged = { speaking: Boolean ->
                if (speaking) {
                    setState(SheedaState.SPEAKING)
                } else {
                    if (currentState == SheedaState.SPEAKING) {
                        setState(SheedaState.IDLE)
                    }
                }
            },
            onSentenceSpoken = { sentence: String ->
                tvBubble.text = sentence
            }
        )

        menuManager = SheedaMenuManager(
            context = this,
            windowManager = windowManager,
            client = client,
            onBubbleMessage = { msg: String, isUserTalk: Boolean ->
                showSpeechBubble(msg, isUserTalk = isUserTalk)
            },
            onStateChange = { newState: SheedaState -> setState(newState) },
            onTalkRequested = { startSpeechListening() }
        )

        initOverlayView()
        handler.post(animationRunnable)

        // Register Network Callback to survive network switches (Tailscale / Wi-Fi / LTE)
        setupNetworkObserver()

        // 1. Start presence long-poll receiver loop for reminders and briefs
        startPresencePollingLoop()

        // 4. Check for self-update on start or daily
        SheedaUpdater.checkOnStartOrDaily(this, client)
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Sheeda Floating Companion",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Foreground floating screen pet overlay"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sheeda is here")
            .setContentText("Tap cat to talk • Long-press for menu")
            .setSmallIcon(R.drawable.sheeda_stand_0)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun setupNetworkObserver() {
        try {
            connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.i(TAG, "Network restored. Triggering immediate presence poll check.")
                    startPresencePollingLoop()
                }
            }
            connectivityManager?.registerNetworkCallback(request, networkCallback!!)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register network observer: $e")
        }
    }

    /**
     * Long-poll presence client for :8777/api/presence/poll.
     * Reconnects with back-off; handles incoming reminders and briefs, speaks aloud, and acks back to PC.
     */
    private fun startPresencePollingLoop() {
        presenceJob?.cancel()
        presenceJob = serviceScope.launch(Dispatchers.IO) {
            var backoffMs = 2000L

            while (isActive) {
                try {
                    val pollResult = client.pollPresenceEvents(timeoutSeconds = 15)

                    if (pollResult.isSuccess) {
                        backoffMs = 2000L // Reset back-off on successful connection
                        val events = pollResult.getOrNull() ?: emptyList()

                        if (events.isNotEmpty()) {
                            // Hold partial wake lock so phone CPU stays alive while displaying and speaking
                            try {
                                wakeLock?.acquire(15000L)
                            } catch (_: Exception) {}

                            for (evt in events) {
                                Log.i(TAG, "Received presence event from PC: id=${evt.id} type=${evt.type} text=${evt.text}")
                                if (evt.text.isNotBlank()) {
                                    handler.post {
                                        // Display bubble and speak via SheedaAudioPlayer (uses /api/tts with on-device fallback)
                                        showSpeechBubble(evt.text, durationMs = 7000, isUserTalk = true)
                                    }

                                    // Acknowledge back to PC so 10-minute repeat / escalation watchdog is informed
                                    val ackRes = client.acknowledgeEvent(evt.id)
                                    Log.i(TAG, "Ack sent for event ${evt.id}: success=${ackRes.isSuccess}")
                                }
                            }

                            try {
                                if (wakeLock?.isHeld == true) wakeLock?.release()
                            } catch (_: Exception) {}
                        }
                    } else {
                        // Connection failed (PC asleep, Tailnet reconnecting, or offline)
                        Log.w(TAG, "Presence poll error: ${pollResult.exceptionOrNull()?.message}; backing off ${backoffMs}ms")
                        delay(backoffMs)
                        backoffMs = (backoffMs * 2).coerceAtMost(30000L)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Presence loop exception: $e; backing off ${backoffMs}ms")
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(30000L)
                }
            }
        }
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
                putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("ur-PK", "ur", "en-PK"))
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

    /**
     * Handles user speech turn.
     * If text is a reminder query ("remind me...", "in 20 minutes...", "snooze...", "cancel..."),
     * routes through Phase 10B reminder engine with origin='phone'.
     */
    private fun handleUserVoiceTurn(userText: String) {
        setState(SheedaState.THINKING)
        serviceScope.launch {
            val reply: String
            if (isReminderQuery(userText)) {
                Log.i(TAG, "Routing phone voice reminder through 10B engine: $userText")
                val remResult = client.sendReminderVoice(userText)
                reply = remResult.getOrElse {
                    "Reminder received, but could not confirm with PC brain."
                }
            } else {
                val chatResult = client.sendChatCompletion(userText)
                reply = chatResult.getOrElse {
                    "Meow! Could not connect to Jarvis brain on tailnet."
                }
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
        presenceJob?.cancel()
        serviceJob.cancel()
        try {
            if (networkCallback != null && connectivityManager != null) {
                connectivityManager?.unregisterNetworkCallback(networkCallback!!)
            }
        } catch (_: Exception) {}
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
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
