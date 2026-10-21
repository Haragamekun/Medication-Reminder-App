package com.example.ocrmedicationapp.network

import kotlinx.serialization.json.Json
import retrofit2.Call
import retrofit2.Response
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST

interface OcrApiService {
    @FormUrlEncoded
    @POST("parse/image")
    fun parseImage(
        @Field("apikey") apiKey: String = "helloworld",
        @Field("base64Image") base64Image: String,
        @Field("ocrEngine") engine: String = "3",
        @Field("language") language: String = "eng"
    ): Call<OcrResponse>

    companion object {
        val jsonConfig = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

        suspend fun safeApiCall(call: suspend () -> Response<OcrResponse>): OcrResponse? {
            return try {
                val response = call()
                if (response.isSuccessful) {
                    response.body()
                } else {
                    val errorBody = response.errorBody()?.string()
                    if (!errorBody.isNullOrEmpty()) {
                        try {
                            jsonConfig.decodeFromString<OcrResponse>(errorBody)
                        } catch (_: Exception) {
                            null
                        }
                    } else null
                }
            } catch (_: Exception) {
                null
            }
        }
    }
}
