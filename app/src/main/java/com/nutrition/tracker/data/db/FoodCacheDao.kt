package com.nutrition.tracker.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface FoodCacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: FoodCacheEntity)

    @Query("SELECT * FROM food_cache WHERE keyNormalized = :key LIMIT 1")
    suspend fun findByNormalizedKey(key: String): FoodCacheEntity?

    @Query("SELECT * FROM food_cache WHERE LOWER(TRIM(keyEn)) = LOWER(TRIM(:keyEn)) LIMIT 1")
    suspend fun findByKeyEn(keyEn: String): FoodCacheEntity?

    @Query("SELECT * FROM food_cache ORDER BY keyOriginal ASC")
    fun getAll(): Flow<List<FoodCacheEntity>>

    @Delete
    suspend fun delete(entry: FoodCacheEntity)

    @Query("UPDATE food_cache SET nutrientsPer100gJson = :json WHERE id = :id")
    suspend fun updateNutrients(id: Long, json: String)

    @Query("UPDATE food_cache SET keyOriginal = :keyOriginal, keyNormalized = :keyNormalized, keyEn = :keyEn WHERE id = :id")
    suspend fun updateKeys(id: Long, keyOriginal: String, keyNormalized: String, keyEn: String)

    @Query("DELETE FROM food_cache")
    suspend fun deleteAll()
}
