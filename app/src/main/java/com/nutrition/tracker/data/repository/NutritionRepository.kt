package com.nutrition.tracker.data.repository

import android.util.Base64
import android.util.Log
import com.google.gson.GsonBuilder
import com.nutrition.tracker.BuildConfig
import com.nutrition.tracker.data.api.*
import com.nutrition.tracker.data.db.*
import com.nutrition.tracker.data.model.FoodAnalysisResult
import com.nutrition.tracker.data.model.NutrientData
import com.nutrition.tracker.util.AppLocale
import com.nutrition.tracker.util.Gender
import com.nutrition.tracker.util.WeightParser
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.format.DateTimeFormatter

// Репозиторий тонкого клиента: локальная Room-БД/кэш + один вызов backend на действие.
// Вся многошаговая AI/USDA-логика переехала на сервер (см. backend/ARCHITECTURE.md).
// Что осталось локально: Room (профиль, нормы, записи, кэш), WeightParser, пересчёт на вес,
// сохранённые продукты, UX-диалоги (в MainViewModel), OFF-запрос по штрихкоду.
// Backend отдаёт нутриенты на 100г; клиент масштабирует сам.
class NutritionRepository(
    private val db: AppDatabase,
    private val backendApi: BackendApiService = ApiClient.backendApi,
    private val offApi: OpenFoodFactsApiService = ApiClient.openFoodFactsApi
) {
    private val gson = GsonBuilder().setLenient().create()
    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    private val auth = BuildConfig.DEV_AUTH_SECRET

    fun todayDate(): String = LocalDate.now().format(dateFormatter)

    // ─── Общий helper: развернуть Response или бросить с сообщением backend ───
    private fun <T> unwrap(resp: retrofit2.Response<T>): T {
        if (resp.isSuccessful) {
            return resp.body() ?: throw Exception("Пустой ответ сервера")
        }
        val errBody = resp.errorBody()?.string() ?: ""
        val msg = try {
            com.google.gson.JsonParser.parseString(errBody).asJsonObject
                .get("message")?.asString
        } catch (e: Exception) { null }
        throw Exception(msg ?: "Ошибка сервера ${resp.code()}")
    }

    // --- User Profile ---
    fun getUserProfile(): Flow<UserProfileEntity?> = db.userProfileDao().getProfile()

    suspend fun getUserProfileSync(): UserProfileEntity? = db.userProfileDao().getProfileSync()

    suspend fun saveUserProfile(gender: String, age: Int, weight: Double, height: Double, goals: String) {
        // Сохраняем id/createdAt существующего ряда, обновляя updatedAt (для синхронизации).
        val existing = db.userProfileDao().getProfileSync()
        db.userProfileDao().insert(
            UserProfileEntity(
                id = existing?.id ?: 0,
                gender = gender, age = age, weightKg = weight, heightCm = height, goalsText = goals,
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                deletedAt = null
            )
        )
    }

    // --- Daily Norms ---
    fun getDailyNorms(): Flow<DailyNormsEntity?> = db.dailyNormsDao().getNorms()

    suspend fun getDailyNormsSync(): NutrientData? {
        val entity = db.dailyNormsDao().getNormsSync() ?: return null
        return parseNutrients(entity.nutrientsJson)
    }

    suspend fun saveDailyNorms(nutrients: NutrientData) {
        upsertNorms(nutrients)
    }

    /** Расчёт суточных норм через backend (/v1/norms). Локально сохраняем результат. */
    suspend fun calculateAndSaveNorms(gender: String, age: Int, weight: Double, height: Double, goals: String): NutrientData {
        val genderForPrompt = Gender.fromStored(gender).promptValue
        val resp = backendApi.norms(auth, request = NormsRequest(genderForPrompt, age, weight, height, goals))
        val nutrients = unwrap(resp).norms
        upsertNorms(nutrients)
        return nutrients
    }

    // Обновляем единственный ряд норм in-place (сохраняя id/createdAt), бампим updatedAt.
    private suspend fun upsertNorms(nutrients: NutrientData) {
        val existing = db.dailyNormsDao().getNormsSync()
        db.dailyNormsDao().insert(
            DailyNormsEntity(
                id = existing?.id ?: 0,
                nutrientsJson = gson.toJson(nutrients),
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                deletedAt = null
            )
        )
    }

    // --- Food Entries ---
    fun getTodayEntries(): Flow<List<FoodEntryEntity>> =
        db.foodEntryDao().getActiveEntriesForDate(todayDate())

    fun getEntriesForDate(date: String): Flow<List<FoodEntryEntity>> =
        db.foodEntryDao().getActiveEntriesForDate(date)

    suspend fun getEntriesForDateSync(date: String): List<FoodEntryEntity> =
        db.foodEntryDao().getEntriesForDateSync(date).filter { it.deletedAt == null }

    suspend fun getEntriesForDateRange(startDate: String, endDate: String): List<FoodEntryEntity> =
        db.foodEntryDao().getEntriesForDateRange(startDate, endDate).filter { it.deletedAt == null }

    fun getRecentDates(): Flow<List<String>> = db.foodEntryDao().getRecentDates()

    // --- Food Cache ---
    fun getAllCachedFoods() = db.foodCacheDao().getAllActive()

    suspend fun deleteCachedFood(entry: FoodCacheEntity) {
        val now = System.currentTimeMillis()
        db.foodCacheDao().softDelete(entry.id, now)
        if (!entry.keyOriginal.startsWith("barcode:")) {
            db.foodCacheDao().softDeleteBarcodeByKeyEn(entry.keyEn, now)
        }
    }

    suspend fun deleteAllCachedFoods() = db.foodCacheDao().softDeleteAll(System.currentTimeMillis())

    suspend fun deleteAllBarcodeEntries() = db.foodCacheDao().softDeleteAllBarcode(System.currentTimeMillis())

    suspend fun updateCachedFood(id: Long, nutrients: NutrientData) {
        db.foodCacheDao().updateNutrients(id, gson.toJson(nutrients))
        db.foodCacheDao().touchUpdatedAt(id, System.currentTimeMillis())
    }

    suspend fun updateCachedFoodFull(id: Long, keyOriginal: String, keyEn: String, nutrients: NutrientData) {
        val normalized = normalizeKey(keyOriginal)
        db.foodCacheDao().updateKeys(id, keyOriginal, normalized, keyEn, normalizeKey(keyEn))
        db.foodCacheDao().updateNutrients(id, gson.toJson(nutrients))
        db.foodCacheDao().touchUpdatedAt(id, System.currentTimeMillis())
    }

    suspend fun addManualCachedFood(keyOriginal: String, keyEn: String, nutrients: NutrientData) {
        val normalized = normalizeKey(keyOriginal)
        val normalizedEn = normalizeKey(keyEn)
        val existing = db.foodCacheDao().findByKeyEnNormalized(normalizedEn)
        if (existing != null) throw Exception("Продукт с таким английским названием уже существует")
        db.foodCacheDao().insert(
            FoodCacheEntity(
                keyOriginal = keyOriginal,
                keyNormalized = normalized,
                keyEn = keyEn,
                keyEnNormalized = normalizedEn,
                nutrientsPer100gJson = gson.toJson(nutrients)
            )
        )
    }

    // Нормализация ключа (порядок слов не важен) — джойн backend-ответов и Room-кэша.
    private fun normalizeKey(name: String): String =
        name.lowercase().trim().replace(Regex("\\s+"), " ")
            .split(" ").sorted().joinToString(" ")

    private suspend fun findInCache(key: String): Pair<FoodCacheEntity, NutrientData>? {
        val normalized = normalizeKey(key)
        val entry = db.foodCacheDao().findByNormalizedKey(normalized)
            ?: db.foodCacheDao().findByKeyEnNormalized(normalized)
            ?: return null
        val nutrients = gson.fromJson(entry.nutrientsPer100gJson, NutrientData::class.java)
        return entry to nutrients
    }

    private suspend fun saveToCache(keyOriginal: String, keyEn: String, nutrientsPer100g: NutrientData) {
        val normalized = normalizeKey(keyOriginal)
        val normalizedEn = normalizeKey(keyEn)
        val existingByKey = db.foodCacheDao().findByNormalizedKey(normalized)
        if (existingByKey != null) return
        if (!keyOriginal.startsWith("barcode:")) {
            val existingByEn = db.foodCacheDao().findByKeyEnNormalized(normalizedEn)
            if (existingByEn != null) return
        }
        db.foodCacheDao().insert(
            FoodCacheEntity(
                keyOriginal = keyOriginal,
                keyNormalized = normalized,
                keyEn = keyEn,
                keyEnNormalized = normalizedEn,
                nutrientsPer100gJson = gson.toJson(nutrientsPer100g)
            )
        )
    }

    suspend fun cacheFoodData(name: String, nameEn: String, per100g: NutrientData) {
        try {
            saveToCache(name, nameEn, per100g)
        } catch (e: Exception) {
            Log.w("Repository", "Failed to cache food: ${e.message}")
        }
    }

    /** Быстрое добавление сохранённого продукта. Fat-details теперь заполняет backend
     *  при первом анализе, поэтому здесь просто читаем кэш (без сети). */
    suspend fun enrichFatDetailsForCachedEntry(entry: FoodCacheEntity): NutrientData {
        return parseNutrients(entry.nutrientsPer100gJson)
    }

    // --- Local input parse ---
    private fun parseLocalFoodInput(input: String): List<Pair<String, Double>> {
        val items = input.split(Regex("""\s*,\s*"""))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        return items.map { item -> WeightParser.parse(item) }
            .filter { it.first.isNotBlank() }
    }

    // ─── Main Food Analysis Pipeline (текст) ───
    // Локальный парс + кэш → один вызов backend /v1/food/analyze. Вес парсит клиент.
    // Нутриенты backend отдаёт на 100г → масштабируем.
    suspend fun analyzeFoodText(foodDescription: String, useCache: Boolean = true): List<FoodAnalysisResult> {
        val localParsed = parseLocalFoodInput(foodDescription)

        val cachedResults = mutableListOf<FoodAnalysisResult>()
        val uncachedItems = mutableListOf<Pair<String, Double>>()

        // Локальный кэш: попадание → берём с устройства (сети нет).
        for ((name, weight) in localParsed) {
            val cached = if (useCache) findInCache(name) else null
            if (cached != null) {
                val w = if (weight > 0) weight else 100.0
                val factor = w / 100.0
                cachedResults.add(
                    FoodAnalysisResult(
                        foodName = name,
                        foodNameEn = cached.first.keyEn,
                        weightGrams = w,
                        nutrients = cached.second * factor,
                        fromCache = true
                    )
                )
            } else {
                uncachedItems.add(name to weight)
            }
        }

        // Всё из локального кэша.
        if (uncachedItems.isEmpty() && cachedResults.isNotEmpty()) {
            return cachedResults
        }

        val results = cachedResults.toMutableList()
        if (uncachedItems.isNotEmpty()) {
            val items = uncachedItems.map { AnalyzeItem(it.first, it.second) }
            val resp = backendApi.analyze(
                auth,
                request = AnalyzeRequest(items, AppLocale.languageEnglishName, useCache)
            )
            val backendResults = unwrap(resp).results
            if (backendResults.isEmpty() && cachedResults.isEmpty()) {
                throw Exception("Не удалось распознать продукты из описания")
            }
            for (r in backendResults) {
                // Локальный кэш на устройстве по введённому имени + англ. ключу.
                cacheFoodData(r.foodName, r.foodNameEn, r.nutrientsPer100g)
                val factor = r.weightGrams / 100.0
                results.add(
                    FoodAnalysisResult(
                        foodName = r.foodName,
                        foodNameEn = r.foodNameEn,
                        weightGrams = r.weightGrams,
                        nutrients = r.nutrientsPer100g * factor,
                        fromCache = r.fromCache
                    )
                )
            }
        }

        if (results.isEmpty()) {
            throw Exception("Не удалось получить данные о нутриентах для введённых продуктов")
        }
        return results
    }

    /** Целое блюдо (фото со сменой имени) через backend /v1/food/dish. Без USDA. */
    suspend fun analyzeSingleDish(dishName: String, weightGrams: Double, useCache: Boolean = false): FoodAnalysisResult {
        if (useCache) {
            val cached = findInCache(dishName)
            if (cached != null) {
                val factor = weightGrams / 100.0
                return FoodAnalysisResult(dishName, cached.first.keyEn, weightGrams, cached.second * factor, true)
            }
        }
        val resp = backendApi.dish(auth, request = DishRequest(dishName))
        val body = unwrap(resp)
        cacheFoodData(dishName, body.foodNameEn, body.nutrientsPer100g)
        val factor = weightGrams / 100.0
        return FoodAnalysisResult(dishName, body.foodNameEn, weightGrams, body.nutrientsPer100g * factor, false)
    }

    // ─── Photo ───
    // Фото → backend /v1/food/photo. Нутриенты на 100г (клиент масштабирует на вес).
    suspend fun identifyAndAnalyzeFoodFromPhoto(imageBytes: ByteArray): FoodAnalysisResult {
        val base64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
        val resp = backendApi.photo(auth, request = PhotoRequest(base64, AppLocale.languageEnglishName))
        val body = unwrap(resp)
        cacheFoodData(body.foodName, body.foodNameEn, body.nutrientsPer100g)
        return FoodAnalysisResult(body.foodName, body.foodNameEn, body.weightGrams, body.nutrientsPer100g, false)
    }

    // ─── Barcode (OFF на клиенте, обогащение на backend) ───
    // Локальный кэш → клиент сам идёт в OFF → backend обогащает (/v1/food/enrich). На 100г.
    suspend fun lookupBarcodeWithCache(barcode: String): Triple<String, NutrientData, Boolean>? {
        val cachedByBarcode = findInCache("barcode:$barcode")
        if (cachedByBarcode != null) {
            return Triple(cachedByBarcode.first.keyEn, cachedByBarcode.second, true)
        }

        // Клиент сам ходит в OFF (свой IP → лимит не схлопывается).
        val result = lookupBarcode(barcode) ?: return null
        val (name, offPer100g) = result

        // Backend обогащает недостающие микро/жиры (данные от клиента → в общий кэш НЕ пишет).
        val enrichedPer100g = try {
            val resp = backendApi.enrich(auth, request = EnrichRequest(name, offPer100g))
            unwrap(resp).nutrientsPer100g
        } catch (e: Exception) {
            Log.w("Repository", "Backend enrich failed for barcode $barcode: ${e.message}")
            offPer100g
        }

        // Локальный кэш (только на устройстве).
        try {
            saveToCache(name, name, enrichedPer100g)
            saveToCache("barcode:$barcode", name, enrichedPer100g)
        } catch (e: Exception) {
            Log.w("Repository", "Failed to cache barcode: ${e.message}")
        }

        return Triple(name, enrichedPer100g, false)
    }

    // OFF-запрос по штрихкоду (остаётся на клиенте). Маппинг OFF → NutrientData (на 100г).
    private suspend fun lookupBarcode(barcode: String): Pair<String, NutrientData>? {
        return try {
            val response = offApi.getProduct(barcode)
            val product = response.product ?: return null
            val name = listOf(product.productNameRu, product.productNameUk, product.productNameEn, product.productName, product.brands)
                .firstOrNull { !it.isNullOrBlank() } ?: "Неизвестный продукт"
            val n = product.nutriments ?: return Pair(name, NutrientData())
            val per100g = NutrientData(
                calories = n.energyKcal100g ?: 0.0,
                protein = n.proteins100g ?: 0.0,
                fat = n.fat100g ?: 0.0,
                saturatedFat = n.saturatedFat100g ?: 0.0,
                monounsaturatedFat = n.monounsaturatedFat100g ?: 0.0,
                polyunsaturatedFat = n.polyunsaturatedFat100g ?: 0.0,
                cholesterol = n.cholesterol100g ?: 0.0,
                carbs = n.carbohydrates100g ?: 0.0,
                fiber = n.fiber100g ?: 0.0,
                vitaminA = n.vitaminA100g ?: 0.0,
                vitaminB1 = n.vitaminB1100g ?: 0.0,
                vitaminB2 = n.vitaminB2100g ?: 0.0,
                vitaminB3 = n.vitaminB3100g ?: 0.0,
                vitaminB5 = n.vitaminB5100g ?: 0.0,
                vitaminB6 = n.vitaminB6100g ?: 0.0,
                vitaminB7 = n.vitaminB7100g ?: 0.0,
                vitaminB9 = n.vitaminB9100g ?: 0.0,
                vitaminB12 = n.vitaminB12100g ?: 0.0,
                vitaminC = n.vitaminC100g ?: 0.0,
                vitaminD = n.vitaminD100g ?: 0.0,
                vitaminE = n.vitaminE100g ?: 0.0,
                vitaminK = n.vitaminK100g ?: 0.0,
                calcium = n.calcium100g ?: 0.0,
                iron = n.iron100g ?: 0.0,
                magnesium = n.magnesium100g ?: 0.0,
                phosphorus = n.phosphorus100g ?: 0.0,
                potassium = n.potassium100g ?: 0.0,
                sodium = n.sodium100g ?: 0.0,
                zinc = n.zinc100g ?: 0.0,
                copper = n.copper100g ?: 0.0,
                manganese = n.manganese100g ?: 0.0,
                selenium = n.selenium100g ?: 0.0,
                iodine = n.iodine100g ?: 0.0
            )
            Pair(name, per100g)
        } catch (e: Exception) {
            Log.e("Repository", "Barcode lookup failed", e)
            null
        }
    }

    // --- Food Entries write ---
    suspend fun addFoodEntry(foodName: String, weightGrams: Double, nutrients: NutrientData, source: String = "manual", fromCache: Boolean = false) {
        db.foodEntryDao().insert(
            FoodEntryEntity(
                date = todayDate(),
                foodName = foodName,
                weightGrams = weightGrams,
                nutrientsJson = gson.toJson(nutrients),
                source = source,
                fromCache = fromCache
            )
        )
    }

    suspend fun updateFoodEntryWeight(entryId: Long, newWeight: Double) {
        val entry = db.foodEntryDao().getById(entryId) ?: return
        val oldNutrients = gson.fromJson(entry.nutrientsJson, NutrientData::class.java)
        val factor = if (entry.weightGrams > 0) newWeight / entry.weightGrams else 1.0
        val newNutrients = oldNutrients * factor
        db.foodEntryDao().update(
            entry.copy(
                weightGrams = newWeight,
                nutrientsJson = gson.toJson(newNutrients),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun deleteFoodEntry(entry: FoodEntryEntity) {
        // Soft delete — синхронизируется как tombstone (см. sync-architecture).
        db.foodEntryDao().update(
            entry.copy(deletedAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis())
        )
    }

    fun parseNutrients(json: String): NutrientData {
        return try {
            gson.fromJson(json, NutrientData::class.java) ?: NutrientData()
        } catch (e: Exception) {
            NutrientData()
        }
    }

    // ─── Синхронизация (см. sync-architecture) ───

    /** Собрать локальную дельту (всё изменённое после `since` ms). since=0 → всё. */
    suspend fun collectChanges(since: Long): SyncPushRequest {
        val profileEntity = db.userProfileDao().getChangedSince(since)
        val profileDto = profileEntity?.let {
            SyncProfileDto(it.gender, it.age, it.weightKg, it.heightCm, it.goalsText, it.updatedAt, it.deletedAt)
        }
        val normsEntity = db.dailyNormsDao().getChangedSince(since)
        val normsDto = normsEntity?.let {
            SyncNormsDto(it.nutrientsJson, it.updatedAt, it.deletedAt)
        }
        val entries = db.foodEntryDao().getChangedSince(since).map {
            SyncEntryDto(it.clientId, it.date, it.foodName, it.foodNameEn, it.weightGrams,
                it.nutrientsJson, it.source, it.fromCache, it.createdAt, it.updatedAt, it.deletedAt)
        }
        val cache = db.foodCacheDao().getChangedSince(since).map {
            SyncCacheDto(it.keyNormalized, it.keyOriginal, it.keyEn, it.keyEnNormalized,
                it.nutrientsPer100gJson, it.createdAt, it.updatedAt, it.deletedAt)
        }
        return SyncPushRequest(profileDto, normsDto, entries, cache)
    }

    /** Применить данные с сервера (last-write-wins по updatedAt). */
    suspend fun applyPulled(resp: SyncPullResponse) {
        resp.profile?.let { dto ->
            val existing = db.userProfileDao().getProfileSync()
            if (existing == null || dto.updatedAt >= existing.updatedAt) {
                db.userProfileDao().insert(UserProfileEntity(
                    id = existing?.id ?: 0,
                    gender = dto.gender, age = dto.age, weightKg = dto.weightKg,
                    heightCm = dto.heightCm, goalsText = dto.goalsText,
                    createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                    updatedAt = dto.updatedAt, deletedAt = dto.deletedAt
                ))
            }
        }
        resp.norms?.let { dto ->
            val existing = db.dailyNormsDao().getNormsSync()
            if (existing == null || dto.updatedAt >= existing.updatedAt) {
                db.dailyNormsDao().insert(DailyNormsEntity(
                    id = existing?.id ?: 0,
                    nutrientsJson = dto.nutrientsJson,
                    createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                    updatedAt = dto.updatedAt, deletedAt = dto.deletedAt
                ))
            }
        }
        for (dto in resp.entries) {
            val existing = db.foodEntryDao().getByClientId(dto.clientId)
            if (existing == null || dto.updatedAt >= existing.updatedAt) {
                db.foodEntryDao().upsert(FoodEntryEntity(
                    id = existing?.id ?: 0,
                    clientId = dto.clientId, date = dto.date, foodName = dto.foodName,
                    foodNameEn = dto.foodNameEn, weightGrams = dto.weightGrams,
                    nutrientsJson = dto.nutrientsJson, source = dto.source, fromCache = dto.fromCache,
                    createdAt = dto.createdAt ?: existing?.createdAt ?: System.currentTimeMillis(),
                    updatedAt = dto.updatedAt, deletedAt = dto.deletedAt
                ))
            }
        }
        for (dto in resp.foodCache) {
            val existing = db.foodCacheDao().findByNormalizedKeyAny(dto.keyNormalized)
            if (existing == null || dto.updatedAt >= existing.updatedAt) {
                db.foodCacheDao().upsert(FoodCacheEntity(
                    id = existing?.id ?: 0,
                    keyOriginal = dto.keyOriginal, keyNormalized = dto.keyNormalized,
                    keyEn = dto.keyEn, keyEnNormalized = dto.keyEnNormalized,
                    nutrientsPer100gJson = dto.nutrientsJson,
                    createdAt = dto.createdAt ?: existing?.createdAt ?: System.currentTimeMillis(),
                    updatedAt = dto.updatedAt, deletedAt = dto.deletedAt
                ))
            }
        }
    }

    /** Прямой доступ к push/pull бэкенда для SyncManager. */
    suspend fun syncPushRequest(req: SyncPushRequest): SyncPushResponse? {
        val resp = backendApi.syncPush(auth, request = req)
        return if (resp.isSuccessful) resp.body() else null
    }

    suspend fun syncPullRequest(since: Long?): SyncPullResponse? {
        val resp = backendApi.syncPull(auth, since = since)
        return if (resp.isSuccessful) resp.body() else null
    }
}
