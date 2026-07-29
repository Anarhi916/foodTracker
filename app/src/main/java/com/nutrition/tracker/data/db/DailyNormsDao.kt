package com.nutrition.tracker.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface DailyNormsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(norms: DailyNormsEntity)

    @Query("SELECT * FROM daily_norms ORDER BY id DESC LIMIT 1")
    fun getNorms(): Flow<DailyNormsEntity?>

    @Query("SELECT * FROM daily_norms ORDER BY id DESC LIMIT 1")
    suspend fun getNormsSync(): DailyNormsEntity?

    @Query("DELETE FROM daily_norms")
    suspend fun deleteAll()

    // --- Синхронизация ---
    @Query("SELECT * FROM daily_norms WHERE updatedAt > :since ORDER BY id DESC LIMIT 1")
    suspend fun getChangedSince(since: Long): DailyNormsEntity?
}
