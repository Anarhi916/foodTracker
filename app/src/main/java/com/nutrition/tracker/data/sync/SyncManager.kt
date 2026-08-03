package com.nutrition.tracker.data.sync

import android.content.Context
import androidx.core.content.edit
import com.nutrition.tracker.data.auth.AuthManager
import com.nutrition.tracker.data.repository.NutritionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

// Data synchronization between devices (see sync-architecture).
// PULL: full on login (busy indicator) + 1/day silent on open.
// PUSH: delta 1/day silent (only what changed since last_push_at).
class SyncManager(
    private val context: Context,
    private val repo: NutritionRepository,
    private val authManager: AuthManager
) {
    private val prefs = context.getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)

    // Shown on LoginScreen/gate: a "Loading data..." indicator.
    private val _isInitialSyncing = MutableStateFlow(false)
    val isInitialSyncing: StateFlow<Boolean> = _isInitialSyncing

    private var lastPushAt: Long
        get() = prefs.getLong(KEY_LAST_PUSH, 0)
        set(v) = prefs.edit { putLong(KEY_LAST_PUSH, v) }
    private var lastPullAt: Long
        get() = prefs.getLong(KEY_LAST_PULL, 0)
        set(v) = prefs.edit { putLong(KEY_LAST_PULL, v) }

    /** Full pull on login (with busy indicator). */
    suspend fun pullOnLogin() {
        _isInitialSyncing.value = true
        try {
            // 1) Pull everything from the server and merge (LWW).
            val resp = repo.syncPullRequest(null) ?: return
            repo.applyPulled(resp)
            lastPullAt = resp.serverTime
            // 2) Upload ALL local data (since=0) — important for upgrading old
            //    users: their pre-login history/products get pushed to the server.
            val allLocal = repo.collectChanges(0)
            val nonEmpty = allLocal.profile != null || allLocal.norms != null ||
                allLocal.entries.isNotEmpty() || allLocal.foodCache.isNotEmpty()
            if (nonEmpty) {
                val pushResp = repo.syncPushRequest(allLocal)
                lastPushAt = pushResp?.serverTime ?: resp.serverTime
            } else {
                lastPushAt = resp.serverTime
            }
        } catch (_: Exception) {
            // Silently — we'll enter the app with local data.
        } finally {
            _isInitialSyncing.value = false
        }
    }

    /** Daily background sync — at most once a day. */
    suspend fun dailySyncIfNeeded() {
        if (!authManager.authState.value) return
        val today = LocalDate.now().toString()
        if (prefs.getString(KEY_LAST_DAILY, null) == today) return
        backgroundSync()
        prefs.edit { putString(KEY_LAST_DAILY, today) }
    }

    /** Push the delta, then pull the delta. Silently. */
    suspend fun backgroundSync() {
        sync(pushSince = lastPushAt)
    }

    /** Forced full sync (button in the profile): push EVERYTHING (since=0),
     *  then pull the delta. Guarantees upload of data created before accounts existed. */
    suspend fun forceSyncNow() {
        sync(pushSince = 0)
    }

    private suspend fun sync(pushSince: Long) {
        if (!authManager.authState.value) return
        // 1) PUSH
        try {
            val changes = repo.collectChanges(pushSince)
            val nonEmpty = changes.profile != null || changes.norms != null ||
                changes.entries.isNotEmpty() || changes.foodCache.isNotEmpty()
            if (nonEmpty) {
                val resp = repo.syncPushRequest(changes)
                if (resp != null) lastPushAt = resp.serverTime
            }
        } catch (_: Exception) {}
        // 2) PULL
        try {
            val since = if (lastPullAt > 0) lastPullAt else null
            val resp = repo.syncPullRequest(since)
            if (resp != null) {
                repo.applyPulled(resp)
                lastPullAt = resp.serverTime
            }
        } catch (_: Exception) {}
    }

    /** Reset markers on sign-out — a new user syncs from scratch. */
    fun resetOnSignOut() {
        prefs.edit { clear() }
    }

    companion object {
        private const val KEY_LAST_PUSH = "lastPushAt"
        private const val KEY_LAST_PULL = "lastPullAt"
        private const val KEY_LAST_DAILY = "lastDailySyncDay"
    }
}
