package com.masterhub.sheeda

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class SheedaMenuManager(
    private val context: Context,
    private val windowManager: WindowManager,
    private val client: JarvisTailnetClient,
    private val onBubbleMessage: (String) -> Unit,
    private val onStateChange: (SheedaState) -> Unit
) {
    private var menuView: View? = null
    private var isMenuShowing = false
    private var isMuted = false

    fun toggleMenu(petX: Int, petY: Int) {
        if (isMenuShowing) {
            dismissMenu()
        } else {
            showMenu(petX, petY)
        }
    }

    fun showMenu(petX: Int, petY: Int) {
        if (isMenuShowing) return

        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.floating_menu, null)
        menuView = view

        val tvInbox = view.findViewById<TextView>(R.id.btn_menu_inbox)
        val tvMute = view.findViewById<TextView>(R.id.btn_menu_mute)

        // 1. Talk: Hands off to Kai 9000 assist intent or SpeechRecognizer
        view.findViewById<View>(R.id.btn_menu_talk).setOnClickListener {
            dismissMenu()
            onStateChange(SheedaState.LISTENING)
            launchKaiOrSpeechRecognizer()
        }

        // 2. Today's Brief
        view.findViewById<View>(R.id.btn_menu_brief).setOnClickListener {
            dismissMenu()
            onStateChange(SheedaState.THINKING)
            CoroutineScope(Dispatchers.Main).launch {
                val brief = client.getCircadianBrief().getOrDefault("Circadian brief unavailable.")
                onStateChange(SheedaState.SPEAKING)
                onBubbleMessage(brief)
            }
        }

        // 3. Inbox N
        view.findViewById<View>(R.id.btn_menu_inbox).setOnClickListener {
            dismissMenu()
            CoroutineScope(Dispatchers.Main).launch {
                val count = client.getInboxCount()
                onBubbleMessage("Inbox: $count routine items waiting in vault.")
            }
        }

        // 4. Queue Job
        view.findViewById<View>(R.id.btn_menu_queue).setOnClickListener {
            dismissMenu()
            onStateChange(SheedaState.THINKING)
            onBubbleMessage("Listening for task to queue...")
            launchKaiOrSpeechRecognizer()
        }

        // 5. Mute Audio
        tvMute.setOnClickListener {
            isMuted = !isMuted
            tvMute.text = if (isMuted) "Unmute Audio" else "Mute Audio"
            Toast.makeText(context, if (isMuted) "Audio Muted" else "Audio Active", Toast.LENGTH_SHORT).show()
        }

        // 6. Open Jarvis
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
            y = (petY - 240).coerceAtLeast(80)
        }

        view.setOnTouchListener { _, _ ->
            // Outside touch will be detected
            false
        }

        windowManager.addView(view, params)
        isMenuShowing = true
    }

    fun dismissMenu() {
        if (isMenuShowing && menuView != null) {
            try {
                windowManager.removeView(menuView)
            } catch (e: Exception) {
                // View already detached
            }
            menuView = null
            isMenuShowing = false
        }
    }

    private fun launchKaiOrSpeechRecognizer() {
        try {
            // First attempt Kai 9000 Assist Intent
            val kaiIntent = Intent(Intent.ACTION_ASSIST).apply {
                setPackage("com.masterhub.kai")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(kaiIntent)
        } catch (e: Exception) {
            // Fallback: Standard Android SpeechRecognizer
            try {
                val speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "Talk to Sheeda (Jarvis :8777)...")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(speechIntent)
            } catch (ex: Exception) {
                onBubbleMessage("Voice assistant active on http://100.72.183.79:8777")
            }
        }
    }
}
