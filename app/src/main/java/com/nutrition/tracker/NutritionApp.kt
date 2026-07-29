package com.nutrition.tracker

import android.app.Application
import com.nutrition.tracker.data.api.ApiClient
import com.nutrition.tracker.data.auth.AuthManager
import com.nutrition.tracker.data.auth.TokenStore
import com.nutrition.tracker.data.db.AppDatabase
import com.nutrition.tracker.data.repository.NutritionRepository
import com.nutrition.tracker.data.sync.SyncManager

class NutritionApp : Application() {
    lateinit var repository: NutritionRepository
        private set
    lateinit var tokenStore: TokenStore
        private set
    lateinit var authManager: AuthManager
        private set
    lateinit var syncManager: SyncManager
        private set

    override fun onCreate() {
        super.onCreate()
        val db = AppDatabase.getInstance(this)
        repository = NutritionRepository(db)
        tokenStore = TokenStore(this)
        authManager = AuthManager(tokenStore)
        syncManager = SyncManager(this, repository, authManager)
        // Bearer-инъекция + refresh-на-401. onExpired → сброс auth-состояния.
        ApiClient.init(tokenStore) {
            authManager.refreshAuthState()
        }
    }
}
