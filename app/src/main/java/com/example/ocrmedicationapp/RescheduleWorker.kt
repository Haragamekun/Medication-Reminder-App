package com.example.ocrmedicationapp

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.util.Calendar

class RescheduleWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val database = AppDatabase.getDatabase(applicationContext)
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
