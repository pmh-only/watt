package com.example.watt

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class BatteryWebhookWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : Worker(appContext, workerParams) {

    override fun doWork(): Result {
        val webhookUrl = BatteryReporter.readWebhookUrl(applicationContext)
        if (webhookUrl.isBlank()) {
            return Result.success()
        }

        val snapshot = BatteryReporter.readBatterySnapshot(applicationContext) ?: return Result.retry()

        return try {
            val responseCode = BatteryReporter.postBatterySnapshot(webhookUrl, snapshot, "periodic")
            if (responseCode in 200..299) {
                Result.success()
            } else {
                Result.retry()
            }
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<BatteryWebhookWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                BatteryReporter.periodicWorkName,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(BatteryReporter.periodicWorkName)
        }
    }
}
