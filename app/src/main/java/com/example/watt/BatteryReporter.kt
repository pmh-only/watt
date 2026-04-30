package com.example.watt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.concurrent.Executors

data class BatterySnapshot(
    val percent: Int,
    val isCharging: Boolean,
    val status: String,
)

enum class BatteryEventType {
    CHARGER_CONNECTED,
    CHARGER_DISCONNECTED,
    BATTERY_LOW,
    BATTERY_EXTREMELY_LOW,
    PHONE_RESTARTED,
}

private enum class BatteryAlertState {
    NONE,
    LOW,
    EXTREME,
}

object BatteryReporter {
    const val prefsName = "watt_prefs"
    const val webhookUrlKey = "webhook_url"

    private const val batteryAlertStateKey = "battery_alert_state"
    private const val lowBatteryThreshold = 15
    private const val extremeLowBatteryThreshold = 5

    private val webhookExecutor = Executors.newSingleThreadExecutor()

    fun readWebhookUrl(context: Context): String {
        return context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .getString(webhookUrlKey, "")
            .orEmpty()
    }

    fun saveWebhookUrl(context: Context, webhookUrl: String) {
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit()
            .putString(webhookUrlKey, webhookUrl)
            .apply()
    }

    fun readBatterySnapshot(context: Context): BatterySnapshot? {
        val batteryIntent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        ) ?: return null

        val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) {
            return null
        }

        val statusCode = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val percent = (level * 100) / scale

        return BatterySnapshot(
            percent = percent,
            isCharging = statusCode == BatteryManager.BATTERY_STATUS_CHARGING ||
                statusCode == BatteryManager.BATTERY_STATUS_FULL,
            status = batteryStatus(statusCode),
        )
    }

    fun initializeBatteryAlertState(context: Context, snapshot: BatterySnapshot) {
        saveBatteryAlertState(context, alertStateFor(snapshot))
    }

    fun resetBatteryAlertState(context: Context) {
        saveBatteryAlertState(context, BatteryAlertState.NONE)
    }

    fun handleBatteryLevelChange(context: Context, snapshot: BatterySnapshot) {
        if (snapshot.isCharging) {
            if (snapshot.percent > lowBatteryThreshold) {
                saveBatteryAlertState(context, BatteryAlertState.NONE)
            }
            return
        }

        val currentAlertState = alertStateFor(snapshot)
        val previousAlertState = readBatteryAlertState(context)

        when {
            currentAlertState == BatteryAlertState.EXTREME && previousAlertState != BatteryAlertState.EXTREME -> {
                saveBatteryAlertState(context, BatteryAlertState.EXTREME)
                sendBatteryEventAsync(context, BatteryEventType.BATTERY_EXTREMELY_LOW, snapshot)
            }

            currentAlertState == BatteryAlertState.LOW && previousAlertState == BatteryAlertState.NONE -> {
                saveBatteryAlertState(context, BatteryAlertState.LOW)
                sendBatteryEventAsync(context, BatteryEventType.BATTERY_LOW, snapshot)
            }

            currentAlertState == BatteryAlertState.NONE && previousAlertState != BatteryAlertState.NONE -> {
                saveBatteryAlertState(context, BatteryAlertState.NONE)
            }
        }
    }

    fun sendBatteryEventAsync(
        context: Context,
        eventType: BatteryEventType,
        snapshot: BatterySnapshot,
        pendingResult: BroadcastReceiver.PendingResult? = null,
    ) {
        val appContext = context.applicationContext
        webhookExecutor.execute {
            try {
                val webhookUrl = readWebhookUrl(appContext)
                if (webhookUrl.isNotBlank()) {
                    postBatteryEvent(webhookUrl, snapshot, eventType)
                }
            } finally {
                pendingResult?.finish()
            }
        }
    }

    private fun postBatteryEvent(
        webhookUrl: String,
        snapshot: BatterySnapshot,
        eventType: BatteryEventType,
    ): Int {
        val embeds = JSONArray().put(
            JSONObject()
                .put("timestamp", Instant.now().toString())
                .put("description", eventTitle(eventType, snapshot.percent))
                .put("color", eventColor(eventType)),
        )

        val payload = JSONObject()
            .put("embeds", embeds)
            .put("flags", 4096)
            .toString()

        val connection = (URL(webhookUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }

        connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(payload)
        }

        return try {
            val responseCode = connection.responseCode
            val responseStream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            responseStream?.close()
            responseCode
        } finally {
            connection.disconnect()
        }
    }

    private fun alertStateFor(snapshot: BatterySnapshot): BatteryAlertState {
        return when {
            snapshot.percent <= extremeLowBatteryThreshold -> BatteryAlertState.EXTREME
            snapshot.percent <= lowBatteryThreshold -> BatteryAlertState.LOW
            else -> BatteryAlertState.NONE
        }
    }

    private fun readBatteryAlertState(context: Context): BatteryAlertState {
        val rawValue = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .getString(batteryAlertStateKey, BatteryAlertState.NONE.name)
            .orEmpty()

        return BatteryAlertState.entries.firstOrNull { it.name == rawValue } ?: BatteryAlertState.NONE
    }

    private fun saveBatteryAlertState(context: Context, state: BatteryAlertState) {
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit()
            .putString(batteryAlertStateKey, state.name)
            .apply()
    }

    private fun batteryStatus(statusCode: Int): String {
        return when (statusCode) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
            else -> "unknown"
        }
    }

    private fun eventTitle(eventType: BatteryEventType, percent: Int): String {
        return when (eventType) {
            BatteryEventType.CHARGER_CONNECTED -> "⚡  Charger connected (${percent}%)"
            BatteryEventType.CHARGER_DISCONNECTED -> "🔋   Charger disconnected (${percent}%)"
            BatteryEventType.BATTERY_LOW -> "🪫 Battery low (${percent}%)"
            BatteryEventType.BATTERY_EXTREMELY_LOW -> "🪫 Battery extremely low (${percent}%)"
            BatteryEventType.PHONE_RESTARTED -> "✅ Phone restarted (${percent}%)"
        }
    }

    private fun eventColor(eventType: BatteryEventType): Int {
        return when (eventType) {
            BatteryEventType.CHARGER_CONNECTED -> 16773929
            BatteryEventType.CHARGER_DISCONNECTED -> 2752332
            BatteryEventType.BATTERY_LOW -> 15760896
            BatteryEventType.BATTERY_EXTREMELY_LOW -> 15728640
            BatteryEventType.PHONE_RESTARTED -> 18928
        }
    }
}
