package com.nutrition.tracker.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface UserProfileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(profile: UserProfileEntity)

    @Query("SELECT * FROM user_profile ORDER BY id DESC LIMIT 1")
    fun getProfile(): Flow<UserProfileEntity?>

    @Query("SELECT * FROM user_profile ORDER BY id DESC LIMIT 1")
    suspend fun getProfileSync(): UserProfileEntity?

    // --- Synchronization ---
    @Query("SELECT * FROM user_profile WHERE updatedAt > :since ORDER BY id DESC LIMIT 1")
    suspend fun getChangedSince(since: Long): UserProfileEntity?

    @Query("DELETE FROM user_profile")
    suspend fun deleteAll()
}
