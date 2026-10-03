package com.obdscanner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** Keeps the process alive while a session records (adapter or phone sensors only), so it goes on with the screen off. */
class ObdService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, tr("Подключение OBD", "OBD connection"), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val device = intent?.getStringExtra("device") ?: ""
        val sensorsOnly = intent?.getBooleanExtra("sensorsOnly", false) == true
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(if (sensorsOnly) android.R.drawable.ic_menu_compass else android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("OBD Scanner")
            .setContentText(if (sensorsOnly) tr("Идёт запись датчиков телефона", "Recording phone sensors")
                else tr("Подключено: $device — идёт запись сессии", "Connected: $device — recording session"))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        try {
            when {
                // No Bluetooth link to point to: Android 14+ wants another type for that.
                sensorsOnly && Build.VERSION.SDK_INT >= 34 -> startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                sensorsOnly -> startForeground(1, n)
                Build.VERSION.SDK_INT >= 29 -> startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                else -> startForeground(1, n)
            }
        } catch (e: Exception) {
            // Demo mode without Bluetooth permission on Android 14+ — just run without foreground.
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "obd"

        fun start(context: Context, device: String, sensorsOnly: Boolean = false) {
            runCatching {
                context.startForegroundService(Intent(context, ObdService::class.java).putExtra("device", device).putExtra("sensorsOnly", sensorsOnly))
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, ObdService::class.java)) }
        }
    }
}
