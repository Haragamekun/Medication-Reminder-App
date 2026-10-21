package com.example.ocrmedicationapp

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class RescheduleWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val database = AppDatabase.getDatabase(applicationContext)

        ReliabilityUtils.performMidnightResetIfNeeded(applicationContext)

        val medications = database.medicationDao().getAllMedicationsSync()
        medications.forEach { medication ->
            scheduleAlarm(applicationContext, medication)
        }

        return Result.success()
    }

    private fun scheduleAlarm(context: Context, medication: Medication) {
        ReliabilityUtils.scheduleAlarm(context, medication)
    }
}
