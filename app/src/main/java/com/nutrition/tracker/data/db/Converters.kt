package com.nutrition.tracker.data.db

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.nutrition.tracker.data.model.NutrientData

class Converters {
    private val gson = Gson()

    @TypeConverter
    fun fromNutrientData(data: NutrientData): String = gson.toJson(data)

    @TypeConverter
    fun toNutrientData(json: String): NutrientData =
        gson.fromJson(json, NutrientData::class.java) ?: NutrientData()
}
