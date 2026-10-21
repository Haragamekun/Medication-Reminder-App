package com.example.ocrmedicationapp

import androidx.room.Entity
import androidx.room.PrimaryKey

import java.util.UUID

@Entity(tableName = "medications")
data class Medication(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val dosage: String,
    val quantity: String,
    val frequency: String,
    val time: String,
    var isTaken: Boolean = false,
    var takenTimestamp: Long = 0L,
    var ringtoneUri: String? = null,
    
    // Verification Data
    val isVerified: Boolean = false,
    val confidenceScores: Map<String, Double> = emptyMap(),
    val verificationMessage: String? = null,
    val flaggedFields: List<String> = emptyList(),

    // Display order for drag & drop reordering
    var displayOrder: Int = 0
) {
    val details: String
        get() = "$dosage • Qty: $quantity • $frequency"
}
