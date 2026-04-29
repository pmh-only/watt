package com.example.watt

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

data class BatterySnapshot(
    val percent: Int,
    val isCharging: Boolean,
    val status: String,
)

object BatteryReporter {
    const val prefsName = "watt_prefs"
    const val webhookUrlKey = "webhook_url"
    const val periodicWorkName = "battery-webhook"

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

    fun postBatterySnapshot(webhookUrl: String, snapshot: BatterySnapshot, source: String): Int {
        val fields = JSONArray()
            .put(discordField("Battery", "${snapshot.percent}%", true))
            .put(discordField("Status", snapshot.status.replace('_', ' '), true))
            .put(discordField("Charging", yesNo(snapshot.isCharging), true))
            .put(discordField("Source", source, true))
            .put(discordField("Device", "${Build.MANUFACTURER} ${Build.MODEL}", false))

        val embeds = JSONArray().put(
            JSONObject()
                .put("title", "Battery Update")
                .put("description", batterySummary(snapshot))
                .put("color", batteryColor(snapshot))
                .put("timestamp", Instant.now().toString())
                .put("fields", fields),
        )

        val payload = JSONObject()
            .put("username", "Watt")
            .put("embeds", embeds)
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

    private fun batteryStatus(statusCode: Int): String {
        return when (statusCode) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
            else -> "unknown"
        }
    }

    private fun batterySummary(snapshot: BatterySnapshot): String {
        return if (snapshot.isCharging) {
            "Phone battery is at ${snapshot.percent}% and charging."
        } else {
            "Phone battery is at ${snapshot.percent}% and ${snapshot.status.replace('_', ' ')}."
        }
    }

    private fun batteryColor(snapshot: BatterySnapshot): Int {
        return when {
            snapshot.isCharging -> 0x57F287
            snapshot.percent >= 50 -> 0x5865F2
            snapshot.percent >= 20 -> 0xFEE75C
            else -> 0xED4245
        }
    }

    private fun discordField(name: String, value: String, inline: Boolean): JSONObject {
        return JSONObject()
            .put("name", name)
            .put("value", value)
            .put("inline", inline)
    }

    private fun yesNo(value: Boolean): String {
        return if (value) "Yes" else "No"
    }
}
