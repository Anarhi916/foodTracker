package com.nutrition.tracker.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

// Поля синхронизации (updatedAt/deletedAt) есть у всех таблиц — см. sync-architecture.
// FoodEntry дополнительно несёт clientId (uuid) — идемпотентный ключ на бэкенде.

@Entity(tableName = "user_profile")
data class UserProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val gender: String,
    val age: Int = 25,
    val weightKg: Double,
    val heightCm: Double,
    val goalsText: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val deletedAt: Long? = null
)

@Entity(tableName = "daily_norms")
data class DailyNormsEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nutrientsJson: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val deletedAt: Long? = null
)

@Entity(
    tableName = "food_entries",
    indices = [Index(value = ["clientId"], unique = true)]
)
data class FoodEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clientId: String = UUID.randomUUID().toString(),
    val date: String,
    val foodName: String,
    val foodNameEn: String = "",
    val weightGrams: Double,
    val nutrientsJson: String,
    val source: String = "manual",
    val fromCache: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val deletedAt: Long? = null
)

@Entity(
    tableName = "food_cache",
    indices = [Index(value = ["keyNormalized"], unique = true), Index(value = ["keyEnNormalized"])]
)
data class FoodCacheEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val keyOriginal: String,
    val keyNormalized: String,
    val keyEn: String,
    /** Normalized English name — language-neutral canonical key for cross-language matching. */
    val keyEnNormalized: String = "",
    val nutrientsPer100gJson: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val deletedAt: Long? = null
)
