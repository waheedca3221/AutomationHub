package com.masterhub.sheeda

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object SheedaUpdater {

    private const val TAG = "SheedaUpdater"
    private const val CHANNEL_ID = "sheeda_update_channel"
    private const val NOTIFICATION_ID = 2002
    private const val PREFS_NAME = "sheeda_config"
    private const val KEY_LAST_CHECK = "last_update_check_epoch"
    private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L // Daily check

    fun checkOnStartOrDaily(
        context: Context,
        client: JarvisTailnetClient,
        force: Boolean = false
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastCheck = prefs.getLong(KEY_LAST_CHECK, 0L)
        val now = System.currentTimeMillis()

        if (!force && (now - lastCheck) < CHECK_INTERVAL_MS) {
            Log.d(TAG, "Update check skipped (checked recently).")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val currentVersionCode = try {
                    val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        pInfo.longVersionCode.toInt()
                    } else {
                        @Suppress("DEPRECATION")
                        pInfo.versionCode
                    }
                } catch (_: Exception) {
                    2
                }

                val updateRes = client.checkUpdate()
                val updateInfo = updateRes.getOrNull()

                prefs.edit().putLong(KEY_LAST_CHECK, now).apply()

                if (updateInfo != null && updateInfo.versionCode > currentVersionCode) {
                    Log.i(TAG, "Newer Sheeda APK discovered: v${updateInfo.versionName} (build ${updateInfo.versionCode})")
                    showUpdateNotification(context, updateInfo)
                } else {
                    Log.d(TAG, "Sheeda is up to date (current build: $currentVersionCode).")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Update check failed: $e")
            }
        }
    }

    private fun showUpdateNotification(context: Context, updateInfo: UpdateInfo) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Sheeda App Updates",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifies when an updated Sheeda APK is available on Tailnet"
            }
            nm.createNotificationChannel(channel)
        }

        val downloadIntent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(updateInfo.apkUrl)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            downloadIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Update Sheeda")
            .setContentText("Version ${updateInfo.versionName} ready • Tap to install")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "Version ${updateInfo.versionName} is ready on Tailnet.\n${updateInfo.releaseNotes}\nTap to download and update."
            ))
            .setSmallIcon(R.drawable.sheeda_stand_0)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        nm.notify(NOTIFICATION_ID, notification)
    }
}
