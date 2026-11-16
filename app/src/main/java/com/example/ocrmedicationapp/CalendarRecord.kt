package com.example.ocrmedicationapp

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "daily_calendar_records")
data class CalendarRecord(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val medicationId: String,
    val name: String,
    val dosage: String,
    val quantity: String = "1",
    val frequency: String,
    val time: String,
    val takenTimestamp: Long = System.currentTimeMillis(),
    val status: String = "TAKEN", // "TAKEN", "MISSED", "SKIPPED"
    val dateString: String // e.g. "yyyy-MM-dd"
) {
    val details: String
        get() = if (quantity.isNotBlank() && quantity != "1") {
            if (dosage.isNotBlank()) "$dosage • Qty: $quantity • $frequency" else "$frequency • Qty: $quantity"
        } else {
            if (dosage.isNotBlank()) "$dosage • $frequency" else frequency
        }
}
