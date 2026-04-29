package com.example.watt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var currentBatteryText: TextView
    private lateinit var webhookInput: EditText
    private lateinit var statusText: TextView

    private val networkExecutor = Executors.newSingleThreadExecutor()
    private var batteryReceiverRegistered = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            renderBatterySnapshot(BatteryReporter.readBatterySnapshot(context))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        currentBatteryText = findViewById(R.id.currentBatteryText)
        webhookInput = findViewById(R.id.webhookInput)
        statusText = findViewById(R.id.statusText)

        webhookInput.setText(BatteryReporter.readWebhookUrl(this))

        findViewById<Button>(R.id.saveWebhookButton).setOnClickListener {
            saveWebhookConfiguration(showSavedStatus = true)
        }

        findViewById<Button>(R.id.sendNowButton).setOnClickListener {
            sendCurrentBatteryLevel()
        }

        ensureBackgroundReportingMatchesSettings()
        renderBatterySnapshot(BatteryReporter.readBatterySnapshot(this))
    }

    override fun onStart() {
        super.onStart()

        ContextCompat.registerReceiver(
            this,
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        batteryReceiverRegistered = true

        renderBatterySnapshot(BatteryReporter.readBatterySnapshot(this))
    }

    override fun onStop() {
        if (batteryReceiverRegistered) {
            unregisterReceiver(batteryReceiver)
            batteryReceiverRegistered = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        networkExecutor.shutdown()
        super.onDestroy()
    }

    private fun ensureBackgroundReportingMatchesSettings() {
        if (BatteryReporter.readWebhookUrl(this).isBlank()) {
            statusText.text = getString(R.string.status_idle)
            return
        }

        BatteryWebhookWorker.schedule(this)
        statusText.text = getString(R.string.background_enabled_status)
    }

    private fun saveWebhookConfiguration(showSavedStatus: Boolean): String {
        val webhookUrl = webhookInput.text.toString().trim()
        BatteryReporter.saveWebhookUrl(this, webhookUrl)

        if (webhookUrl.isBlank()) {
            BatteryWebhookWorker.cancel(this)
            statusText.text = getString(R.string.webhook_disabled_status)
            return webhookUrl
        }

        BatteryWebhookWorker.schedule(this)
        if (showSavedStatus) {
            statusText.text = getString(R.string.webhook_saved_status)
        }

        return webhookUrl
    }

    private fun sendCurrentBatteryLevel() {
        val webhookUrl = saveWebhookConfiguration(showSavedStatus = false)
        if (webhookUrl.isBlank()) {
            statusText.text = getString(R.string.missing_webhook_status)
            return
        }

        val snapshot = BatteryReporter.readBatterySnapshot(this)
        if (snapshot == null) {
            statusText.text = getString(R.string.battery_unavailable_status)
            return
        }

        statusText.text = getString(R.string.sending_status, snapshot.percent)
        networkExecutor.execute {
            try {
                val responseCode = BatteryReporter.postBatterySnapshot(webhookUrl, snapshot, "manual")
                runOnUiThread {
                    statusText.text = if (responseCode in 200..299) {
                        getString(R.string.send_success_status, responseCode)
                    } else {
                        getString(R.string.send_failed_status, responseCode)
                    }
                }
            } catch (exception: Exception) {
                runOnUiThread {
                    statusText.text = getString(
                        R.string.send_error_status,
                        exception.message ?: getString(R.string.unknown_error),
                    )
                }
            }
        }
    }

    private fun renderBatterySnapshot(snapshot: BatterySnapshot?) {
        if (snapshot == null) {
            currentBatteryText.text = getString(R.string.current_battery_unavailable)
            return
        }

        currentBatteryText.text = getString(
            R.string.current_battery_format,
            snapshot.percent,
            snapshot.status,
        )
    }
}
