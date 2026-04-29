package com.example.watt

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class BatteryEventService : Service() {
    private var receiverRegistered = false

    private val eventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val snapshot = BatteryReporter.readBatterySnapshot(context) ?: return

            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> {
                    BatteryReporter.sendBatteryEventAsync(
                        context = context,
                        eventType = BatteryEventType.CHARGER_CONNECTED,
                        snapshot = snapshot,
                    )
                }

                Intent.ACTION_POWER_DISCONNECTED -> {
                    BatteryReporter.sendBatteryEventAsync(
                        context = context,
                        eventType = BatteryEventType.CHARGER_DISCONNECTED,
                        snapshot = snapshot,
                    )
                }

                Intent.ACTION_BATTERY_CHANGED -> BatteryReporter.handleBatteryLevelChange(context, snapshot)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(notificationId, buildNotification())
        BatteryReporter.readBatterySnapshot(this)?.let { snapshot ->
            BatteryReporter.initializeBatteryAlertState(this, snapshot)
        }
        registerEventReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (BatteryReporter.readWebhookUrl(this).isBlank()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        return START_STICKY
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            unregisterReceiver(eventReceiver)
            receiverRegistered = false
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerEventReceiver() {
        if (receiverRegistered) {
            return
        }

        val intentFilter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }

        ContextCompat.registerReceiver(
            this,
            eventReceiver,
            intentFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, notificationChannelId)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle(getString(R.string.monitoring_notification_title))
            .setContentText(getString(R.string.monitoring_notification_text))
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            notificationChannelId,
            getString(R.string.monitoring_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        private const val notificationChannelId = "battery-events"
        private const val notificationId = 1

        fun sync(context: Context) {
            if (BatteryReporter.readWebhookUrl(context).isBlank()) {
                BatteryReporter.resetBatteryAlertState(context)
                context.stopService(Intent(context, BatteryEventService::class.java))
                return
            }

            ContextCompat.startForegroundService(context, Intent(context, BatteryEventService::class.java))
        }
    }
}
