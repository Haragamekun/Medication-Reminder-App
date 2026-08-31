package com.example.ocrmedicationapp

import android.content.Context
import kotlinx.coroutines.flow.Flow

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

    suspend fun deleteMedication(medication: Medication) {
        dao.deleteMedication(medication)
    }

    suspend fun deleteMedicationById(id: String) {
        dao.deleteMedicationById(id)
    }
}
