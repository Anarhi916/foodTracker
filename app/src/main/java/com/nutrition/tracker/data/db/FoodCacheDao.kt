package com.nutrition.tracker.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface FoodCacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: FoodCacheEntity)

    @Query("SELECT * FROM food_cache WHERE keyNormalized = :key LIMIT 1")
    suspend fun findByNormalizedKey(key: String): FoodCacheEntity?

    @Query("SELECT * FROM food_cache ORDER BY keyOriginal ASC")
    fun getAll(): Flow<List<FoodCacheEntity>>

    @Delete
    suspend fun delete(entry: FoodCacheEntity)

    @Query("DELETE FROM food_cache")
    suspend fun deleteAll()
}
