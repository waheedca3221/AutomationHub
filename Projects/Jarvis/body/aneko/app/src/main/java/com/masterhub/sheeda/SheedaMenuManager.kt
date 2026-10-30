package com.masterhub.sheeda

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.view.Gravity
import android.view.LayoutInflater
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
    private val onTalkRequested: () -> Unit
) {
    private var menuView: View? = null
    private var isMenuShowing = false
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
        if (isMenuShowing) return

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

        // 4. Queue Job
        view.findViewById<View>(R.id.btn_menu_queue).setOnClickListener {
            dismissMenu()
            onStateChange(SheedaState.THINKING)
            onBubbleMessage("Listening for task to queue...", false)
            onTalkRequested()
        }

        // 5. Mute Audio
        tvMute.text = if (isMuted) "Unmute Audio" else "Mute Audio"
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
}
