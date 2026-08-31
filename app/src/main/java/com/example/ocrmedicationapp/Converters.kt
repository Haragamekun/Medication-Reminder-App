package com.example.ocrmedicationapp

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class Converters {
    private val gson = Gson()

    @TypeConverter
    fun fromStringMap(value: String?): Map<String, Double> {
        val type = object : TypeToken<Map<String, Double>>() {}.type
        return gson.fromJson(value ?: "{}", type)
    }

    @TypeConverter
    fun toStringMap(map: Map<String, Double>?): String {
        return gson.toJson(map ?: emptyMap<String, Double>())
    }

    @TypeConverter
    fun fromStringList(value: String?): List<String> {
        val type = object : TypeToken<List<String>>() {}.type
        return gson.fromJson(value ?: "[]", type)
    }

    @TypeConverter
    fun toStringList(list: List<String>?): String {
        return gson.toJson(list ?: emptyList<String>())
    }
}
