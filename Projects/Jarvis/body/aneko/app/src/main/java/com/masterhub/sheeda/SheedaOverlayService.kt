package com.masterhub.sheeda

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import java.util.Calendar

class SheedaOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var ivSheeda: ImageView
    private lateinit var tvBubble: TextView

    private lateinit var client: JarvisTailnetClient
    private lateinit var menuManager: SheedaMenuManager

    private var currentState = SheedaState.IDLE
    private var currentFrameIdx = 0
    private val handler = Handler(Looper.getMainLooper())

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false

    private lateinit var windowParams: WindowManager.LayoutParams

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

        menuManager = SheedaMenuManager(
            context = this,
            windowManager = windowManager,
            client = client,
            onBubbleMessage = { msg -> showSpeechBubble(msg) },
            onStateChange = { newState -> setState(newState) }
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
            .setContentText("Orange tabby companion running over apps • Tap to configure")
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
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        isDragging = true
                        setState(SheedaState.DRAG)
                        windowParams.x = initialX + dx
                        windowParams.y = initialY + dy
                        windowManager.updateViewLayout(overlayView, windowParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        setState(SheedaState.ONFLOOR)
                        handler.postDelayed({ setState(SheedaState.IDLE) }, 1000)
                    } else {
                        // Clean Tap: toggle floating menu
                        menuManager.toggleMenu(windowParams.x, windowParams.y)
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(overlayView, windowParams)
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

    private fun checkQuietHoursState() {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        // Quiet hours: 23:00 to 06:30 -> automatically curl up to sleep
        if (hour >= 23 || hour < 6) {
            if (currentState != SheedaState.SLEEP && currentState != SheedaState.DRAG) {
                setState(SheedaState.SLEEP)
            }
        } else if (currentState == SheedaState.SLEEP) {
            setState(SheedaState.IDLE)
        }
    }

    fun showSpeechBubble(text: String, durationMs: Long = 4000) {
        tvBubble.text = text
        tvBubble.visibility = View.VISIBLE
        handler.removeCallbacksAndMessages(tvBubble)
        handler.postDelayed({
            tvBubble.visibility = View.GONE
            setState(SheedaState.IDLE)
        }, durationMs)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(animationRunnable)
        menuManager.dismissMenu()
        if (::overlayView.isInitialized) {
            windowManager.removeView(overlayView)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
