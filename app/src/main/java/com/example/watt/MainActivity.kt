package com.example.watt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var currentBatteryText: TextView
    private lateinit var webhookInput: EditText
    private lateinit var statusText: TextView
    private lateinit var batteryOptimizationWarning: LinearLayout

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
        batteryOptimizationWarning = findViewById(R.id.batteryOptimizationWarning)

        webhookInput.setText(BatteryReporter.readWebhookUrl(this))

        findViewById<Button>(R.id.saveWebhookButton).setOnClickListener {
            saveWebhookConfiguration()
        }

        findViewById<Button>(R.id.disableBatteryOptimizationButton).setOnClickListener {
            requestBatteryOptimizationExemption()
        }

        ensureEventReportingMatchesSettings()
        renderBatterySnapshot(BatteryReporter.readBatterySnapshot(this))
    }

    override fun onResume() {
        super.onResume()
        updateBatteryOptimizationWarning()
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

    private fun ensureEventReportingMatchesSettings() {
        if (BatteryReporter.readWebhookUrl(this).isBlank()) {
            statusText.text = getString(R.string.status_idle)
            return
        }

        BatteryEventService.sync(this)
        statusText.text = getString(R.string.event_enabled_status)
    }

    private fun saveWebhookConfiguration() {
        val webhookUrl = webhookInput.text.toString().trim()
        BatteryReporter.saveWebhookUrl(this, webhookUrl)
        BatteryEventService.sync(this)

        statusText.text = if (webhookUrl.isBlank()) {
            getString(R.string.event_disabled_status)
        } else {
            getString(R.string.webhook_saved_status)
        }
    }

    private fun updateBatteryOptimizationWarning() {
        val pm = getSystemService(PowerManager::class.java)
        val isIgnoring = pm.isIgnoringBatteryOptimizations(packageName)
        batteryOptimizationWarning.visibility = if (isIgnoring) View.GONE else View.VISIBLE
    }

    private fun requestBatteryOptimizationExemption() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }

    private fun renderBatterySnapshot(snapshot: BatterySnapshot?) {
        if (snapshot == null) {
            currentBatteryText.text = getString(R.string.current_battery_unavailable)
            return
        }

        currentBatteryText.text = getString(
            R.string.current_battery_format,
            snapshot.percent,
            snapshot.status.replace('_', ' '),
        )
    }
}
