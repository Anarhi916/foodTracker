package com.nutrition.tracker.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [UserProfileEntity::class, DailyNormsEntity::class, FoodEntryEntity::class, FoodCacheEntity::class],
    version = 4,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userProfileDao(): UserProfileDao
    abstract fun dailyNormsDao(): DailyNormsDao
    abstract fun foodEntryDao(): FoodEntryDao
    abstract fun foodCacheDao(): FoodCacheDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE user_profile ADD COLUMN age INTEGER NOT NULL DEFAULT 25")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE food_entries ADD COLUMN fromCache INTEGER NOT NULL DEFAULT 0")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS food_cache (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        keyOriginal TEXT NOT NULL,
                        keyNormalized TEXT NOT NULL,
                        keyEn TEXT NOT NULL,
                        nutrientsPer100gJson TEXT NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                """)
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_food_cache_keyNormalized ON food_cache (keyNormalized)")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Language-neutral canonical cache key + English name on entries (i18n).
                db.execSQL("ALTER TABLE food_entries ADD COLUMN foodNameEn TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE food_cache ADD COLUMN keyEnNormalized TEXT NOT NULL DEFAULT ''")
                // Backfill keyEnNormalized: lowercase, collapse spaces, sort words (matches normalizeKey).
                val cursor = db.query("SELECT id, keyEn FROM food_cache")
                val idIdx = cursor.getColumnIndexOrThrow("id")
                val enIdx = cursor.getColumnIndexOrThrow("keyEn")
                val updates = ArrayList<Pair<Long, String>>()
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIdx)
                    val keyEn = cursor.getString(enIdx) ?: ""
                    val norm = keyEn.lowercase().trim().replace(Regex("\\s+"), " ")
                        .split(" ").filter { it.isNotEmpty() }.sorted().joinToString(" ")
                    updates.add(id to norm)
                }
                cursor.close()
                for ((id, norm) in updates) {
                    db.execSQL("UPDATE food_cache SET keyEnNormalized = ? WHERE id = ?", arrayOf<Any>(norm, id))
                }
                db.execSQL("CREATE INDEX IF NOT EXISTS index_food_cache_keyEnNormalized ON food_cache (keyEnNormalized)")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "nutrition_tracker_db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build().also { INSTANCE = it }
            }
        }
    }
}
