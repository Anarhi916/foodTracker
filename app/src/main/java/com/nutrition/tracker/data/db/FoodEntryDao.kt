package com.nutrition.tracker.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface FoodEntryDao {
    @Insert
    suspend fun insert(entry: FoodEntryEntity): Long

    @Update
    suspend fun update(entry: FoodEntryEntity)

    @Delete
    suspend fun delete(entry: FoodEntryEntity)

    @Query("SELECT * FROM food_entries WHERE date = :date ORDER BY createdAt DESC")
    fun getEntriesForDate(date: String): Flow<List<FoodEntryEntity>>

    @Query("SELECT DISTINCT date FROM food_entries ORDER BY date DESC")
    fun getRecentDates(): Flow<List<String>>

    @Query("SELECT * FROM food_entries WHERE date = :date ORDER BY createdAt DESC")
    suspend fun getEntriesForDateSync(date: String): List<FoodEntryEntity>

    @Query("SELECT * FROM food_entries WHERE date >= :startDate AND date <= :endDate ORDER BY date DESC, createdAt DESC")
    suspend fun getEntriesForDateRange(startDate: String, endDate: String): List<FoodEntryEntity>

    @Query("SELECT * FROM food_entries WHERE id = :id")
    suspend fun getById(id: Long): FoodEntryEntity?

    @Query("DELETE FROM food_entries")
    suspend fun deleteAll()

    // --- Synchronization ---
    @Query("SELECT * FROM food_entries WHERE updatedAt > :since")
    suspend fun getChangedSince(since: Long): List<FoodEntryEntity>

    @Query("SELECT * FROM food_entries WHERE clientId = :clientId LIMIT 1")
    suspend fun getByClientId(clientId: String): FoodEntryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: FoodEntryEntity)

    // Active (not deleted) entries for a day — for the UI, hiding tombstones.
    @Query("SELECT * FROM food_entries WHERE date = :date AND deletedAt IS NULL ORDER BY createdAt DESC")
    fun getActiveEntriesForDate(date: String): Flow<List<FoodEntryEntity>>
}
