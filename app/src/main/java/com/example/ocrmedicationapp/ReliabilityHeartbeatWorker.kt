package com.example.ocrmedicationapp

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

class ReliabilityHeartbeatWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val database = AppDatabase.getDatabase(applicationContext)
        val medications = database.medicationDao().getAllMedicationsSync()

        medications.forEach { medication ->
            // Re-schedule alarm to ensure it's still in the system's AlarmManager
            // Exact alarms are persistent, but some OEM systems clear them on force-stop
            ReliabilityUtils.scheduleAlarm(applicationContext, medication)
        }

        return Result.success()
    }

    companion object {
        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .build()

            val request = PeriodicWorkRequestBuilder<ReliabilityHeartbeatWorker>(1, TimeUnit.HOURS)
                .setConstraints(constraints)
                .addTag("ReliabilityHeartbeat")
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "ReliabilityHeartbeat",
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
