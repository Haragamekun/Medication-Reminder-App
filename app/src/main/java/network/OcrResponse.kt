package com.example.ocrmedicationapp.network

data class OcrResponse(
    val ParsedResults: List<ParsedResult>?
)

data class ParsedResult(
    val ParsedText: String?
)