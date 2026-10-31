package com.masterhub.sheeda

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SheedaMenuManager(
    private val context: Context,
    private val windowManager: WindowManager,
    private val client: JarvisTailnetClient,
    private val onBubbleMessage: (String, Boolean) -> Unit,
    private val onStateChange: (SheedaState) -> Unit,
    private val onTalkRequested: () -> Unit,
    private val onResizeRequested: () -> Unit = {}
) {
    private var menuView: View? = null
    private var isMenuShowing = false
    private var alarmPlayer: MediaPlayer? = null
    var isMuted = false
        private set

    fun toggleMenu(petX: Int, petY: Int) {
        if (isMenuShowing) {
            dismissMenu()
        } else {
            showMenu(petX, petY)
        }
    }

    fun showMenu(petX: Int, petY: Int) {
        if (isMenuShowing) {
            dismissMenu()
            return
        }

        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.floating_menu, null)
        menuView = view

        val tvInbox = view.findViewById<TextView>(R.id.btn_menu_inbox)
        val tvMute = view.findViewById<TextView>(R.id.btn_menu_mute)

        // 1. Talk: Direct speech recognition and round-trip dialogue
        view.findViewById<View>(R.id.btn_menu_talk).setOnClickListener {
            dismissMenu()
            onTalkRequested()
        }

        // 2. Today's Brief
        view.findViewById<View>(R.id.btn_menu_brief).setOnClickListener {
            dismissMenu()
            onStateChange(SheedaState.THINKING)
            CoroutineScope(Dispatchers.Main).launch {
                val brief = client.getCircadianBrief().getOrDefault("Circadian brief unavailable.")
                onBubbleMessage(brief, false)
            }
        }

        // 3. Inbox N
        view.findViewById<View>(R.id.btn_menu_inbox).setOnClickListener {
            dismissMenu()
            CoroutineScope(Dispatchers.Main).launch {
                val count = client.getInboxCount()
                onBubbleMessage("Inbox: $count routine items waiting in vault.", false)
            }
        }

        // 4. Feline Trick / Gesture (Wink, Scratch, Clean, Point)
        view.findViewById<View>(R.id.btn_menu_trick).setOnClickListener {
            dismissMenu()
            val tricks = listOf(SheedaState.WINK, SheedaState.SCRATCH, SheedaState.CLEAN, SheedaState.POINT)
            val chosen = tricks.random()
            onStateChange(chosen)
            val msg = when (chosen) {
                SheedaState.WINK -> "*winks warmly with one amber eye*"
                SheedaState.SCRATCH -> "*scratches playfully against the edge*"
                SheedaState.CLEAN -> "*licks paw and grooms whiskers*"
                SheedaState.POINT -> "*points paw forward at your mission*"
                else -> "*meow*"
            }
            onBubbleMessage(msg, false)
        }

        // 4b. Resize Cat (Cycles Small, Normal, Large, Extra Large)
        view.findViewById<View>(R.id.btn_menu_resize)?.setOnClickListener {
            dismissMenu()
            onResizeRequested()
        }

        // 5. Loud Wake-up Alarm Test
        view.findViewById<View>(R.id.btn_menu_alarm).setOnClickListener {
            dismissMenu()
            playLoudAlarm()
            onBubbleMessage("🚨 LOUD WAKE-UP ALARM TESTING (Max Decibel Feline Alert)", false)
        }

        // 6. Queue Job
        view.findViewById<View>(R.id.btn_menu_queue).setOnClickListener {
            dismissMenu()
            onStateChange(SheedaState.THINKING)
            onBubbleMessage("Listening for task to queue, Comrade...", false)
            onTalkRequested()
        }

        // 7. Mute Audio
        tvMute.text = if (isMuted) "Unmute Audio" else "Mute Audio"
        tvMute.setOnClickListener {
            isMuted = !isMuted
            tvMute.text = if (isMuted) "Unmute Audio" else "Mute Audio"
            Toast.makeText(context, if (isMuted) "Audio Muted" else "Audio Active", Toast.LENGTH_SHORT).show()
        }

        // 8. Open Jarvis
        view.findViewById<View>(R.id.btn_menu_open).setOnClickListener {
            dismissMenu()
            val url = client.baseServerUrl
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }

        // Query background inbox count
        CoroutineScope(Dispatchers.Main).launch {
            val count = client.getInboxCount()
            tvInbox.text = "Inbox ($count)"
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (petX - 40).coerceAtLeast(20)
            y = (petY - 260).coerceAtLeast(80)
        }

        view.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                dismissMenu()
                true
            } else {
                false
            }
        }

        windowManager.addView(view, params)
        isMenuShowing = true
    }

    private fun playLoudAlarm() {
        try {
            alarmPlayer?.release()
            alarmPlayer = MediaPlayer.create(context, R.raw.sheeda_alarm_loud)?.apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setVolume(1.0f, 1.0f)
                start()
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Alarm audio error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun dismissMenu() {
        if (isMenuShowing && menuView != null) {
            try {
                windowManager.removeView(menuView)
            } catch (_: Exception) {
            }
            menuView = null
            isMenuShowing = false
        }
    }
}
