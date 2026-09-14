package com.nutrition.tracker

import android.app.Application
import android.provider.Settings
import com.nutrition.tracker.data.api.ApiClient
import com.nutrition.tracker.data.auth.AuthManager
import com.nutrition.tracker.data.auth.IntegrityService
import com.nutrition.tracker.data.auth.TokenStore
import com.nutrition.tracker.data.db.AppDatabase
import com.nutrition.tracker.data.repository.NutritionRepository
import com.nutrition.tracker.data.sync.SyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NutritionApp : Application() {
    lateinit var repository: NutritionRepository
        private set
    lateinit var tokenStore: TokenStore
        private set
    lateinit var authManager: AuthManager
        private set
    lateinit var syncManager: SyncManager
        private set

    private val appScope = CoroutineScope(Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        val db = AppDatabase.getInstance(this)
        repository = NutritionRepository(db, this)
        tokenStore = TokenStore(this)
        authManager = AuthManager(tokenStore, this)
        syncManager = SyncManager(this, repository, authManager)

        // Play Integrity: cloud project number from BuildConfig (0 = unconfigured → skipped).
        // ANDROID_ID is a stable per-device/app id used only for server-side rate-limiting
        // (Play Integrity intentionally exposes no device identifier).
        @Suppress("HardwareIds")
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            ?: "unknown"
        IntegrityService.init(this, BuildConfig.PLAY_INTEGRITY_CLOUD_PROJECT_NUMBER, deviceId)
        // Bearer injection + refresh-on-401. onExpired → session invalid: drop tokens
        // and return to Login (don't wipe local data — same user usually re-logs in).
        // onDeleted → account deleted from another device: wipe local data + logout.
        ApiClient.init(
            tokenStore,
            onExpired = {
                tokenStore.clear()
                authManager.refreshAuthState()
            },
            onDeleted = {
                appScope.launch {
                    repository.wipeAllLocalData()
                    syncManager.resetOnSignOut()
                    tokenStore.clear()
                    authManager.setAccountDeletedNotice(true)
                    authManager.refreshAuthState()
                }
            },
        )
    }
}
