package com.example.ocrmedicationapp.network

import retrofit2.Call
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
}