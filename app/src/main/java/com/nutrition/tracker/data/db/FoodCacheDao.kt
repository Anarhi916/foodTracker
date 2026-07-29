package com.nutrition.tracker.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface FoodCacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: FoodCacheEntity)

    @Query("SELECT * FROM food_cache WHERE keyNormalized = :key LIMIT 1")
    suspend fun findByNormalizedKey(key: String): FoodCacheEntity?

    @Query("SELECT * FROM food_cache WHERE keyEnNormalized = :keyEnNormalized LIMIT 1")
    suspend fun findByKeyEnNormalized(keyEnNormalized: String): FoodCacheEntity?

    @Query("SELECT * FROM food_cache ORDER BY keyOriginal ASC")
    fun getAll(): Flow<List<FoodCacheEntity>>

    @Delete
    suspend fun delete(entry: FoodCacheEntity)

    @Query("DELETE FROM food_cache WHERE keyOriginal LIKE 'barcode:%' AND keyEn = :keyEn")
    suspend fun deleteBarcodeEntriesByKeyEn(keyEn: String)

    @Query("UPDATE food_cache SET nutrientsPer100gJson = :json WHERE id = :id")
    suspend fun updateNutrients(id: Long, json: String)

    @Query("UPDATE food_cache SET keyOriginal = :keyOriginal, keyNormalized = :keyNormalized, keyEn = :keyEn, keyEnNormalized = :keyEnNormalized WHERE id = :id")
    suspend fun updateKeys(id: Long, keyOriginal: String, keyNormalized: String, keyEn: String, keyEnNormalized: String)

    @Query("DELETE FROM food_cache")
    suspend fun deleteAll()

    @Query("""
        DELETE FROM food_cache WHERE keyEn IN (
            SELECT keyEn FROM food_cache WHERE keyOriginal LIKE 'barcode:%'
        )
    """)
    suspend fun deleteAllBarcodeEntries()

    // --- Синхронизация ---
    @Query("SELECT * FROM food_cache WHERE updatedAt > :since")
    suspend fun getChangedSince(since: Long): List<FoodCacheEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: FoodCacheEntity)

    // Активные (не удалённые) — для UI.
    @Query("SELECT * FROM food_cache WHERE deletedAt IS NULL ORDER BY keyOriginal ASC")
    fun getAllActive(): Flow<List<FoodCacheEntity>>

    // Soft-delete варианты (синхронизируемые tombstone).
    @Query("UPDATE food_cache SET deletedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: Long, now: Long)

    @Query("UPDATE food_cache SET deletedAt = :now, updatedAt = :now WHERE deletedAt IS NULL")
    suspend fun softDeleteAll(now: Long)

    @Query("UPDATE food_cache SET deletedAt = :now, updatedAt = :now WHERE keyOriginal LIKE 'barcode:%' AND keyEn = :keyEn AND deletedAt IS NULL")
    suspend fun softDeleteBarcodeByKeyEn(keyEn: String, now: Long)

    @Query("""
        UPDATE food_cache SET deletedAt = :now, updatedAt = :now WHERE deletedAt IS NULL AND keyEn IN (
            SELECT keyEn FROM food_cache WHERE keyOriginal LIKE 'barcode:%'
        )
    """)
    suspend fun softDeleteAllBarcode(now: Long)

    @Query("SELECT * FROM food_cache WHERE keyNormalized = :key LIMIT 1")
    suspend fun findByNormalizedKeyAny(key: String): FoodCacheEntity?

    @Query("UPDATE food_cache SET updatedAt = :now WHERE id = :id")
    suspend fun touchUpdatedAt(id: Long, now: Long)
}
