package com.example.ocrmedicationapp

import android.content.Context
import kotlinx.coroutines.flow.Flow
import java.util.Calendar
import kotlin.math.abs

class MedicationRepository(context: Context) {
    private val dao = AppDatabase.getDatabase(context).medicationDao()

    fun getAllMedications(): Flow<List<Medication>> {
        return dao.getAllMedications()
    }

    suspend fun getMedications(): List<Medication> {
        return dao.getAllMedicationsSync()
    }

    suspend fun addMedication(medication: Medication) {
        dao.insertMedication(medication)
    }

    suspend fun updateMedication(medication: Medication) {
        dao.updateMedication(medication)
    }

    suspend fun updateMedicationsOrder(medications: List<Medication>) {
        dao.updateMedications(medications)
    }

    suspend fun deleteMedication(medication: Medication) {
        dao.deleteMedication(medication)
    }

    suspend fun deleteMedicationById(id: String) {
        dao.deleteMedicationById(id)
    }

    // History Records
    fun getAllHistoryRecords(): Flow<List<MedicationRecord>> {
        return dao.getAllHistoryRecords()
    }

    suspend fun getHistoryRecords(): List<MedicationRecord> {
        return dao.getAllHistoryRecordsSync()
    }

    suspend fun recordIntake(medication: Medication, timestamp: Long = System.currentTimeMillis(), specificTime: String? = null) {
        val slotTime = if (!specificTime.isNullOrBlank()) specificTime else getClosestSlotTime(medication.time, timestamp)
        val record = MedicationRecord(
            medicationId = medication.id,
            name = medication.name,
            dosage = medication.dosage,
            quantity = medication.quantity,
            frequency = medication.frequency,
            time = slotTime,
            takenTimestamp = timestamp,
            status = "TAKEN"
        )
        dao.insertHistoryRecord(record)
    }

    suspend fun recordMissed(medication: Medication, timestamp: Long = System.currentTimeMillis(), specificTime: String? = null) {
        val slotTime = if (!specificTime.isNullOrBlank()) specificTime else getClosestSlotTime(medication.time, timestamp)
        val record = MedicationRecord(
            medicationId = medication.id,
            name = medication.name,
            dosage = medication.dosage,
            quantity = medication.quantity,
            frequency = medication.frequency,
            time = slotTime,
            takenTimestamp = timestamp,
            status = "MISSED"
        )
        dao.insertHistoryRecord(record)
    }

    suspend fun recordSkipped(medication: Medication, timestamp: Long = System.currentTimeMillis(), specificTime: String? = null) {
        val slotTime = if (!specificTime.isNullOrBlank()) specificTime else getClosestSlotTime(medication.time, timestamp)
        val record = MedicationRecord(
            medicationId = medication.id,
            name = medication.name,
            dosage = medication.dosage,
            quantity = medication.quantity,
            frequency = medication.frequency,
            time = slotTime,
            takenTimestamp = timestamp,
            status = "SKIPPED"
        )
        dao.insertHistoryRecord(record)
    }

    suspend fun clearHistoryRecords() {
        dao.clearAllHistoryRecords()
    }

    fun getClosestSlotTime(medicationTime: String, timestamp: Long = System.currentTimeMillis()): String {
        val slots = medicationTime.split(",").map { it.trim() }.filter { it.isNotBlank() }
        if (slots.size <= 1) return medicationTime

        val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
        val currentMins = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

        var closestSlot = slots.first()
        var minDiff = Int.MAX_VALUE

        for (slot in slots) {
            val slotMins = parseTimeToMinutes(slot)
            if (slotMins != null) {
                val diff = abs(slotMins - currentMins)
                if (diff < minDiff) {
                    minDiff = diff
                    closestSlot = slot
                }
            }
        }
        return closestSlot
    }

    private fun parseTimeToMinutes(timeStr: String): Int? {
        try {
            val parts = timeStr.split(" ")
            if (parts.size >= 2) {
                val hms = parts[0].split(":")
                if (hms.size >= 2) {
                    var hour = hms[0].toInt()
                    val minute = hms[1].toInt()
                    val ampm = parts[1]
                    if (ampm.equals("PM", ignoreCase = true) && hour < 12) hour += 12
                    if (ampm.equals("AM", ignoreCase = true) && hour == 12) hour = 0
                    return hour * 60 + minute
                }
            }
        } catch (_: Exception) {}
        return null
    }
}
