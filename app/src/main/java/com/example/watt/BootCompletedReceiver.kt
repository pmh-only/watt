package com.example.watt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }

        if (BatteryReporter.readWebhookUrl(context).isBlank()) {
            return
        }

        BatteryEventService.sync(context)

        val snapshot = BatteryReporter.readBatterySnapshot(context) ?: return
        val pendingResult = goAsync()
        BatteryReporter.sendBatteryEventAsync(
            context = context,
            eventType = BatteryEventType.PHONE_RESTARTED,
            snapshot = snapshot,
            pendingResult = pendingResult,
        )
    }
}
