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

    // Dedicated Daily Calendar Records (Isolated from Clear History)
    fun getAllCalendarRecords(): Flow<List<CalendarRecord>> {
        return dao.getAllCalendarRecords()
    }

    suspend fun getCalendarRecords(): List<CalendarRecord> {
        return dao.getAllCalendarRecordsSync()
    }

    suspend fun getCalendarRecordsForDate(dateStr: String): List<CalendarRecord> {
        return dao.getCalendarRecordsForDate(dateStr)
    }

    private fun formatDateString(timestamp: Long): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        return sdf.format(java.util.Date(timestamp))
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

        val calendarRecord = CalendarRecord(
            medicationId = medication.id,
            name = medication.name,
            dosage = medication.dosage,
            quantity = medication.quantity,
            frequency = medication.frequency,
            time = slotTime,
            takenTimestamp = timestamp,
            status = "TAKEN",
            dateString = formatDateString(timestamp)
        )
        dao.insertCalendarRecord(calendarRecord)
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

        val calendarRecord = CalendarRecord(
            medicationId = medication.id,
            name = medication.name,
            dosage = medication.dosage,
            quantity = medication.quantity,
            frequency = medication.frequency,
            time = slotTime,
            takenTimestamp = timestamp,
            status = "MISSED",
            dateString = formatDateString(timestamp)
        )
        dao.insertCalendarRecord(calendarRecord)
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

        val calendarRecord = CalendarRecord(
            medicationId = medication.id,
            name = medication.name,
            dosage = medication.dosage,
            quantity = medication.quantity,
            frequency = medication.frequency,
            time = slotTime,
            takenTimestamp = timestamp,
            status = "SKIPPED",
            dateString = formatDateString(timestamp)
        )
        dao.insertCalendarRecord(calendarRecord)
    }

    suspend fun syncHistoryToCalendarRecordsIfEmpty() {
        val existingCalendarRecords = dao.getAllCalendarRecordsSync()
        if (existingCalendarRecords.isEmpty()) {
            val historyRecords = dao.getAllHistoryRecordsSync()
            if (historyRecords.isNotEmpty()) {
                val calendarRecords = historyRecords.map { h ->
                    CalendarRecord(
                        id = h.id,
                        medicationId = h.medicationId,
                        name = h.name,
                        dosage = h.dosage,
                        quantity = h.quantity,
                        frequency = h.frequency,
                        time = h.time,
                        takenTimestamp = h.takenTimestamp,
                        status = h.status,
                        dateString = formatDateString(h.takenTimestamp)
                    )
                }
                dao.insertCalendarRecords(calendarRecords)
            }
        }
    }

    suspend fun clearHistoryRecords() {
        dao.clearAllHistoryRecords()
    }

    suspend fun getMedicationsForTimeSlot(timeSlot: String): List<Medication> {
        val all = dao.getAllMedicationsSync()
        val targetSlot = timeSlot.trim()
        if (targetSlot.isBlank()) return emptyList()
        return all.filter { med ->
            med.time.split(",").map { it.trim() }.any { it.equals(targetSlot, ignoreCase = true) }
        }
    }

    suspend fun markAllTakenForTimeSlot(targetMedication: Medication?, specificTime: String?, timestamp: Long = System.currentTimeMillis()) {
        val allMeds = dao.getAllMedicationsSync()
        val targetSlot = if (!specificTime.isNullOrBlank()) specificTime.trim() else targetMedication?.time?.split(",")?.firstOrNull()?.trim()

        val matchingMeds = if (!targetSlot.isNullOrBlank()) {
            allMeds.filter { med ->
                med.time.split(",").map { it.trim() }.any { it.equals(targetSlot, ignoreCase = true) }
            }
        } else listOfNotNull(targetMedication)

        val finalMeds = if (matchingMeds.isNotEmpty()) matchingMeds else listOfNotNull(targetMedication)

        finalMeds.forEach { med ->
            val updated = med.copy(isTaken = true, takenTimestamp = timestamp)
            dao.updateMedication(updated)
            recordIntake(updated, timestamp, targetSlot)
        }
    }

    suspend fun markAllMissedForTimeSlot(targetMedication: Medication?, specificTime: String?, timestamp: Long = System.currentTimeMillis()) {
        val allMeds = dao.getAllMedicationsSync()
        val targetSlot = if (!specificTime.isNullOrBlank()) specificTime.trim() else targetMedication?.time?.split(",")?.firstOrNull()?.trim()

        val matchingMeds = if (!targetSlot.isNullOrBlank()) {
            allMeds.filter { med ->
                med.time.split(",").map { it.trim() }.any { it.equals(targetSlot, ignoreCase = true) }
            }
        } else listOfNotNull(targetMedication)

        val finalMeds = if (matchingMeds.isNotEmpty()) matchingMeds else listOfNotNull(targetMedication)

        finalMeds.forEach { med ->
            recordMissed(med, timestamp, targetSlot)
        }
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
