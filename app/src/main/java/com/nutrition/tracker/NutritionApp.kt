package com.nutrition.tracker

import android.app.Application
import com.nutrition.tracker.data.db.AppDatabase
import com.nutrition.tracker.data.repository.NutritionRepository

class NutritionApp : Application() {
    lateinit var repository: NutritionRepository
        private set

    override fun onCreate() {
        super.onCreate()
        val db = AppDatabase.getInstance(this)
        repository = NutritionRepository(db)
    }
}
