package com.nutrition.tracker.data.sync

import android.content.Context
import androidx.core.content.edit
import com.nutrition.tracker.data.auth.AuthManager
import com.nutrition.tracker.data.repository.NutritionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

// Синхронизация данных между устройствами (см. sync-architecture).
// PULL: full при логине (busy indicator) + 1/день silent при открытии.
// PUSH: delta 1/день silent (только изменённое с last_push_at).
class SyncManager(
    private val context: Context,
    private val repo: NutritionRepository,
    private val authManager: AuthManager
) {
    private val prefs = context.getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)

    // Показывается на LoginScreen/gate: индикатор «Загружаем данные...».
    private val _isInitialSyncing = MutableStateFlow(false)
    val isInitialSyncing: StateFlow<Boolean> = _isInitialSyncing

    private var lastPushAt: Long
        get() = prefs.getLong(KEY_LAST_PUSH, 0)
        set(v) = prefs.edit { putLong(KEY_LAST_PUSH, v) }
    private var lastPullAt: Long
        get() = prefs.getLong(KEY_LAST_PULL, 0)
        set(v) = prefs.edit { putLong(KEY_LAST_PULL, v) }

    /** Full pull при логине (с busy indicator). */
    suspend fun pullOnLogin() {
        _isInitialSyncing.value = true
        try {
            // 1) Тянем всё с сервера и мержим (LWW).
            val resp = repo.syncPullRequest(null) ?: return
            repo.applyPulled(resp)
            lastPullAt = resp.serverTime
            // 2) Заливаем ВСЕ локальные данные (since=0) — важно для апгрейда старых
            //    пользователей: их дологиновая история/продукты попадут на сервер.
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
            // Тихо — попадём в приложение с локальными данными.
        } finally {
            _isInitialSyncing.value = false
        }
    }

    /** Ежедневная фоновая синхронизация — не чаще 1 раза в день. */
    suspend fun dailySyncIfNeeded() {
        if (!authManager.authState.value) return
        val today = LocalDate.now().toString()
        if (prefs.getString(KEY_LAST_DAILY, null) == today) return
        backgroundSync()
        prefs.edit { putString(KEY_LAST_DAILY, today) }
    }

    /** Push дельты, затем pull дельты. Тихо. */
    suspend fun backgroundSync() {
        sync(pushSince = lastPushAt)
    }

    /** Принудительная полная синхронизация (кнопка в профиле): пушим ВСЁ (since=0),
     *  затем pull дельты. Гарантирует заливку данных, созданных до появления аккаунтов. */
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

    /** Сброс маркеров при выходе — новый юзер синкается заново. */
    fun resetOnSignOut() {
        prefs.edit { clear() }
    }

    companion object {
        private const val KEY_LAST_PUSH = "lastPushAt"
        private const val KEY_LAST_PULL = "lastPullAt"
        private const val KEY_LAST_DAILY = "lastDailySyncDay"
    }
}
