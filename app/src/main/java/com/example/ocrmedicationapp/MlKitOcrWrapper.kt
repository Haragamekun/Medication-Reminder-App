package com.example.ocrmedicationapp

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Clean, lightweight OCR engine wrapper using Google ML Kit Text Recognition.
 */
class MlKitOcrWrapper {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun recognizeText(bitmap: Bitmap): String = suspendCancellableCoroutine { continuation ->
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                if (continuation.isActive) {
                    continuation.resume(visionText.text)
                }
            }
            .addOnFailureListener { e ->
                Log.e("MlKitOCR", "ML Kit Text Recognition error: ${e.message}", e)
                if (continuation.isActive) {
                    continuation.resume("")
                }
            }
    }

    fun release() {
        try {
            recognizer.close()
        } catch (e: Exception) {
            Log.e("MlKitOCR", "Error closing ML Kit recognizer: ${e.message}")
        }
    }
}
