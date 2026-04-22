package com.nutrition.tracker.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "user_profile")
data class UserProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val gender: String,
    val age: Int = 25,
    val weightKg: Double,
    val heightCm: Double,
    val goalsText: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "daily_norms")
data class DailyNormsEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nutrientsJson: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "food_entries")
data class FoodEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val foodName: String,
    val weightGrams: Double,
    val nutrientsJson: String,
    val source: String = "manual",
    val fromCache: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "food_cache",
    indices = [Index(value = ["keyNormalized"], unique = true)]
)
data class FoodCacheEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val keyOriginal: String,
    val keyNormalized: String,
    val keyEn: String,
    val nutrientsPer100gJson: String,
    val createdAt: Long = System.currentTimeMillis()
)
