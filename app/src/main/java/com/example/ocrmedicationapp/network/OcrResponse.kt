package com.example.ocrmedicationapp.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class OcrResponse(
    @SerialName("ParsedResults") val ParsedResults: List<ParsedResult>? = null,
    @SerialName("OCRExitCode") val OCRExitCode: Int? = null,
    @SerialName("IsErroredOnProcessing") val IsErroredOnProcessing: Boolean? = null,
    @SerialName("ErrorMessage") val ErrorMessage: List<String>? = null,
    @SerialName("ErrorDetails") val ErrorDetails: String? = null,
    @SerialName("details") val details: String? = null
)

@Serializable
data class ParsedResult(
    @SerialName("ParsedText") val ParsedText: String? = null,
    @SerialName("ErrorMessage") val ErrorMessage: String? = null,
    @SerialName("ErrorDetails") val ErrorDetails: String? = null,
    @SerialName("details") val details: String? = null
)
