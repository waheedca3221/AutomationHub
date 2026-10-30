package com.masterhub.sheeda

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvTailnet: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnOverlayPerm: Button
    private lateinit var btnMicPerm: Button

    private lateinit var client: JarvisTailnetClient
    private var isServiceRunning = false

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Toast.makeText(this, "Microphone permission granted!", Toast.LENGTH_SHORT).show()
            updateButtons()
        } else {
            Toast.makeText(this, "Microphone permission is required for Talk feature.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        client = JarvisTailnetClient(this)

        tvStatus = findViewById(R.id.tv_service_status)
        tvTailnet = findViewById(R.id.tv_tailnet_status)
        btnToggle = findViewById(R.id.btn_toggle_sheeda)
        btnOverlayPerm = findViewById(R.id.btn_overlay_perm)
        btnMicPerm = findViewById(R.id.btn_mic_perm)

        btnOverlayPerm.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } else {
                Toast.makeText(this, "Display Over Other Apps is already granted!", Toast.LENGTH_SHORT).show()
            }
        }

        btnMicPerm.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                Toast.makeText(this, "Microphone permission is already granted!", Toast.LENGTH_SHORT).show()
            }
        }

        btnToggle.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Please grant Display Over Other Apps first!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (isServiceRunning) {
                stopService(Intent(this, SheedaOverlayService::class.java))
                isServiceRunning = false
                tvStatus.text = "Status: Resting"
                btnToggle.text = "Spawn Floating Sheeda"
            } else {
                val serviceIntent = Intent(this, SheedaOverlayService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                isServiceRunning = true
                tvStatus.text = "Status: Floating Live over Screen"
                btnToggle.text = "Dismiss Sheeda"
            }
        }

        checkTailnetHealth()
    }

    override fun onResume() {
        super.onResume()
        updateButtons()
    }

    private fun updateButtons() {
        val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        btnOverlayPerm.text = if (hasOverlay) "✓ Display Over Other Apps (Granted)" else "1. Grant Display Over Other Apps"
        btnOverlayPerm.isEnabled = !hasOverlay

        btnMicPerm.text = if (hasMic) "✓ Microphone Access (Granted)" else "2. Grant Microphone Permission"
        btnMicPerm.isEnabled = !hasMic
    }

    private fun checkTailnetHealth() {
        CoroutineScope(Dispatchers.Main).launch {
            val result = client.getHealthStatus()
            if (result.isSuccess) {
                tvTailnet.text = "Brain: Connected to Jarvis :8777 (Tailnet Online)"
                tvTailnet.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.sheeda_orange))
            } else {
                tvTailnet.text = "Brain: ${client.baseServerUrl} (Tailscale verification pending)"
            }
        }
    }
}
