package com.example.ocrmedicationapp

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface MedicationDao {
    @Query("SELECT * FROM medications ORDER BY displayOrder ASC")
    fun getAllMedications(): Flow<List<Medication>>

    @Query("SELECT * FROM medications ORDER BY displayOrder ASC")
    suspend fun getAllMedicationsSync(): List<Medication>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMedication(medication: Medication)

    @Update
    suspend fun updateMedication(medication: Medication)

    @Update
    suspend fun updateMedications(medications: List<Medication>)

    @Delete
    suspend fun deleteMedication(medication: Medication)

    @Query("DELETE FROM medications WHERE id = :id")
    suspend fun deleteMedicationById(id: String)

    // Medication History Records
    @Query("SELECT * FROM medication_records ORDER BY takenTimestamp DESC")
    fun getAllHistoryRecords(): Flow<List<MedicationRecord>>

    @Query("SELECT * FROM medication_records ORDER BY takenTimestamp DESC")
    suspend fun getAllHistoryRecordsSync(): List<MedicationRecord>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistoryRecord(record: MedicationRecord)

    @Query("DELETE FROM medication_records")
    suspend fun clearAllHistoryRecords()
}
