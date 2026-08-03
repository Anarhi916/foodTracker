package com.nutrition.tracker

import android.app.Application
import com.nutrition.tracker.data.api.ApiClient
import com.nutrition.tracker.data.auth.AuthManager
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
        repository = NutritionRepository(db)
        tokenStore = TokenStore(this)
        authManager = AuthManager(tokenStore)
        syncManager = SyncManager(this, repository, authManager)
        // Bearer injection + refresh-on-401. onExpired → reset auth state.
        // onDeleted → account deleted from another device: wipe local data + logout.
        ApiClient.init(
            tokenStore,
            onExpired = { authManager.refreshAuthState() },
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
