package com.nutrition.tracker.data.repository

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.nutrition.tracker.BuildConfig
import com.nutrition.tracker.data.api.*
import com.nutrition.tracker.data.db.*
import com.nutrition.tracker.data.model.FoodAnalysisResult
import com.nutrition.tracker.data.model.NutrientData
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class NutritionRepository(
    private val db: AppDatabase,
    private val openRouterApi: OpenRouterApiService = ApiClient.openRouterApi,
    private val offApi: OpenFoodFactsApiService = ApiClient.openFoodFactsApi,
    private val usdaApi: UsdaFdcApiService = ApiClient.usdaApi
) {
    private val gson = GsonBuilder().setLenient().create()
    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    private val apiKey = BuildConfig.OPENROUTER_API_KEY

    // Text model (paid, fast, reliable JSON, $0.10/$0.40 per 1M tokens)
    private val textModels = listOf(
        "google/gemini-2.5-flash-lite"
    )

    // Vision model (paid, supports image_url input)
    private val visionModels = listOf(
        "google/gemini-2.5-flash-lite"
    )

    // Paid model for photo recognition ($0.30/$2.50 per 1M tokens)
    private val photoModels = listOf(
        "google/gemini-2.5-flash"
    )

    // Paid model for daily norms calculation (needs high reasoning quality)
    private val normsModels = listOf(
        "google/gemini-2.5-pro-preview"
    )

    fun todayDate(): String = LocalDate.now().format(dateFormatter)

    // --- User Profile ---
    fun getUserProfile(): Flow<UserProfileEntity?> = db.userProfileDao().getProfile()

    suspend fun getUserProfileSync(): UserProfileEntity? = db.userProfileDao().getProfileSync()

    suspend fun saveUserProfile(gender: String, age: Int, weight: Double, height: Double, goals: String) {
        db.userProfileDao().insert(
            UserProfileEntity(gender = gender, age = age, weightKg = weight, heightCm = height, goalsText = goals)
        )
    }

    // --- Daily Norms ---
    fun getDailyNorms(): Flow<DailyNormsEntity?> = db.dailyNormsDao().getNorms()

    suspend fun getDailyNormsSync(): NutrientData? {
        val entity = db.dailyNormsDao().getNormsSync() ?: return null
        return parseNutrients(entity.nutrientsJson)
    }

    suspend fun saveDailyNorms(nutrients: NutrientData) {
        db.dailyNormsDao().deleteAll()
        db.dailyNormsDao().insert(DailyNormsEntity(nutrientsJson = gson.toJson(nutrients)))
    }

    suspend fun calculateAndSaveNorms(gender: String, age: Int, weight: Double, height: Double, goals: String): NutrientData {
        val prompt = """
You are a professional nutrition expert. Based on the following user data, calculate the recommended DAILY nutritional intake to achieve their goals.

User data:
- Gender: $gender
- Age: $age years
- Weight: $weight kg
- Height: $height cm
- Goals and activity level: $goals

IMPORTANT: All values MUST be in the units specified. Pay special attention:
- copper is in MG (milligrams), NOT mcg. Typical adult RDA is 0.9 mg.
- manganese is in MG. Typical adult AI is 2.3 mg.
- selenium, iodine are in MCG (micrograms).

Calculate daily norms and return ONLY a JSON object with this EXACT structure (all numbers, no text):
{
  "calories": <number>,
  "protein": <grams>,
  "fat": <grams>,
  "saturated_fat": <grams>,
  "monounsaturated_fat": <grams>,
  "polyunsaturated_fat": <grams>,
  "cholesterol": <mg>,
  "carbs": <grams>,
  "fiber": <grams>,
  "vitamin_a": <mcg>,
  "vitamin_b1": <mg>,
  "vitamin_b2": <mg>,
  "vitamin_b3": <mg>,
  "vitamin_b5": <mg>,
  "vitamin_b6": <mg>,
  "vitamin_b7": <mcg>,
  "vitamin_b9": <mcg>,
  "vitamin_b12": <mcg>,
  "vitamin_c": <mg>,
  "vitamin_d": <mcg>,
  "vitamin_e": <mg>,
  "vitamin_k": <mcg>,
  "calcium": <mg>,
  "iron": <mg>,
  "magnesium": <mg>,
  "phosphorus": <mg>,
  "potassium": <mg>,
  "sodium": <mg>,
  "zinc": <mg>,
  "copper": <mg, e.g. 0.9>,
  "manganese": <mg, e.g. 2.3>,
  "selenium": <mcg>,
  "iodine": <mcg>
}
""".trimIndent()

        val normsText = callOpenRouterWithRetry(
            messages = listOf(OpenRouterMessage(role = "user", content = prompt)),
            models = normsModels
        )
        val nutrients = try {
            sanitizeNormUnits(parseNutrientData(normsText))
        } catch (e: Exception) {
            Log.w("Repository", "Failed to parse norms response, retrying once. Text: ${normsText.take(200)}")
            // Retry once if parsing fails (model returned malformed response)
            val retryText = callOpenRouterWithRetry(
                messages = listOf(OpenRouterMessage(role = "user", content = prompt)),
                models = normsModels
            )
            sanitizeNormUnits(parseNutrientData(retryText))
        }
        db.dailyNormsDao().deleteAll()
        db.dailyNormsDao().insert(DailyNormsEntity(nutrientsJson = gson.toJson(nutrients)))
        return nutrients
    }

    /**
     * Fix common AI unit mistakes in daily norms.
     * E.g. copper RDA is 0.9 mg but AI often returns 900 (mcg value).
     * If a value is wildly out of expected range, convert it.
     */
    private fun sanitizeNormUnits(n: NutrientData): NutrientData {
        var result = n
        // Copper: RDA ~0.9 mg. If AI returns >10, it likely used mcg → divide by 1000
        if (result.copper > 10) result = result.copy(copper = result.copper / 1000.0)
        // Manganese: AI ~2.3 mg. If >50, likely used mcg
        if (result.manganese > 50) result = result.copy(manganese = result.manganese / 1000.0)
        // Selenium should be mcg (55-70). If <1, AI probably used mg
        if (result.selenium > 0 && result.selenium < 1) result = result.copy(selenium = result.selenium * 1000.0)
        return result
    }

    // --- Food Entries ---
    fun getTodayEntries(): Flow<List<FoodEntryEntity>> =
        db.foodEntryDao().getEntriesForDate(todayDate())

    fun getEntriesForDate(date: String): Flow<List<FoodEntryEntity>> =
        db.foodEntryDao().getEntriesForDate(date)

    suspend fun getEntriesForDateSync(date: String): List<FoodEntryEntity> =
        db.foodEntryDao().getEntriesForDateSync(date)

    suspend fun getEntriesForDateRange(startDate: String, endDate: String): List<FoodEntryEntity> =
        db.foodEntryDao().getEntriesForDateRange(startDate, endDate)

    fun getRecentDates(): Flow<List<String>> = db.foodEntryDao().getRecentDates()

    // --- Food Cache ---
    fun getAllCachedFoods() = db.foodCacheDao().getAll()

    suspend fun deleteCachedFood(entry: FoodCacheEntity) {
        db.foodCacheDao().delete(entry)
        // Also delete hidden barcode: and supplement: entries that reference the same product
        if (!entry.keyOriginal.startsWith("barcode:") && !entry.keyOriginal.startsWith("supplement:")) {
            db.foodCacheDao().deleteBarcodeEntriesByKeyEn(entry.keyEn)
            db.foodCacheDao().deleteSupplementEntriesByKeyEn(entry.keyEn)
        }
    }

    suspend fun deleteAllCachedFoods() = db.foodCacheDao().deleteAll()

    suspend fun deleteAllBarcodeAndSupplementEntries() = db.foodCacheDao().deleteAllBarcodeAndSupplementEntries()

    suspend fun updateCachedFood(id: Long, nutrients: NutrientData) {
        db.foodCacheDao().updateNutrients(id, gson.toJson(nutrients))
    }

    suspend fun updateCachedFoodFull(id: Long, keyOriginal: String, keyEn: String, nutrients: NutrientData) {
        val normalized = normalizeKey(keyOriginal)
        db.foodCacheDao().updateKeys(id, keyOriginal, normalized, keyEn)
        db.foodCacheDao().updateNutrients(id, gson.toJson(nutrients))
    }

    suspend fun addManualCachedFood(keyOriginal: String, keyEn: String, nutrients: NutrientData) {
        val normalized = normalizeKey(keyOriginal)
        val normalizedEn = normalizeKey(keyEn)
        // Don't duplicate
        val existing = db.foodCacheDao().findByKeyEn(normalizedEn)
        if (existing != null) throw Exception("Продукт с таким английским названием уже существует")
        db.foodCacheDao().insert(
            FoodCacheEntity(
                keyOriginal = keyOriginal,
                keyNormalized = normalized,
                keyEn = keyEn,
                nutrientsPer100gJson = gson.toJson(nutrients)
            )
        )
    }

    private fun normalizeKey(name: String): String =
        name.lowercase().trim().replace(Regex("\\s+"), " ")
            .split(" ").sorted().joinToString(" ")

    private suspend fun findInCache(key: String): Pair<FoodCacheEntity, NutrientData>? {
        val normalized = normalizeKey(key)
        // First try exact normalized key match
        val entry = db.foodCacheDao().findByNormalizedKey(normalized)
        // If not found, try matching by English key
            ?: db.foodCacheDao().findByKeyEn(normalized)
            ?: return null
        val nutrients = gson.fromJson(entry.nutrientsPer100gJson, NutrientData::class.java)
        return entry to nutrients
    }

    private suspend fun saveToCache(keyOriginal: String, keyEn: String, nutrientsPer100g: NutrientData) {
        val normalized = normalizeKey(keyOriginal)
        // Don't duplicate if same normalized key already exists
        val existingByKey = db.foodCacheDao().findByNormalizedKey(normalized)
        if (existingByKey != null) return
        // For non-technical keys, also check by English name to avoid product duplicates
        if (!keyOriginal.startsWith("barcode:") && !keyOriginal.startsWith("supplement:")) {
            val normalizedEn = normalizeKey(keyEn)
            val existingByEn = db.foodCacheDao().findByKeyEn(normalizedEn)
            if (existingByEn != null) return
        }
        db.foodCacheDao().insert(
            FoodCacheEntity(
                keyOriginal = keyOriginal,
                keyNormalized = normalized,
                keyEn = keyEn,
                nutrientsPer100gJson = gson.toJson(nutrientsPer100g)
            )
        )
    }

    // ─── Dairy fat % correction (ГОСТ standard) ────────────────────────────────
    // For Russian/Ukrainian dairy products like "творог 5%", "молоко 2.5%", the
    // percentage in the name means grams of fat per 100g of the final product.
    // USDA doesn't index these grades and would pick a wrong variant (e.g. lowfat 2%
    // for творог 5%, with understated protein), so we strip the % before USDA search
    // and afterwards correct macros via a dedicated AI call.

    /** Keywords for dairy products that follow the GOST X% = X g fat per 100g standard.
     * Hard cheeses are excluded (their % is fat in dry matter, not in product). */
    private val dairyWithFatPercentKeywords: List<String> = listOf(
        "творог", "творожн", "сирок", "сырок", "сир знежирен", "сир нежирн", "сир кисломолочн",
        "молоко", "сметана", "кефир", "ряженка", "ряжанка", "йогурт", "сливки", "вершки",
        "простокваша", "ацидофилин", "айран", "тан", "мацони", "снежок", "бифидок"
    )

    /** Extracts the fat percentage from a product name ("творог 5%" → 5.0). */
    private fun extractFatPercent(foodName: String): Double? {
        val regex = Regex("""(\d+(?:[.,]\d+)?)\s*%""")
        val match = regex.find(foodName) ?: return null
        val raw = match.groupValues[1].replace(",", ".")
        val percent = raw.toDoubleOrNull() ?: return null
        if (percent < 0 || percent > 100) return null
        return percent
    }

    /** Removes the fat % from a product name so we can search USDA for the base product.
     * "cottage cheese 5%" → "cottage cheese", "молоко 2.5%" → "молоко". */
    private fun stripFatPercent(foodName: String): String {
        return foodName.replace(Regex("""\s*\d+(?:[.,]\d+)?\s*%\s*"""), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /** True if the name is a dairy product with an explicit fat % (GOST standard).
     * Hard cheeses ("сыр 45%") return false — there % is fat in dry matter. */
    private fun isDairyWithFatPercent(foodName: String): Boolean {
        val lower = foodName.lowercase()
        val isHardCheese = (lower.contains("сыр") || lower.contains("сир") ||
                            (lower.contains("cheese") && !lower.contains("cottage")))
                           && dairyWithFatPercentKeywords.none { lower.contains(it) }
        if (isHardCheese) return false
        if (dairyWithFatPercentKeywords.none { lower.contains(it) }) return false
        return extractFatPercent(foodName) != null
    }

    /**
     * Corrects PROTEIN/FAT/CARBS for a dairy product with an explicit % via an AI call.
     * USDA returned data for a different fat grade (e.g. cottage cheese 2% for "творог 5%"),
     * which understates the protein. We override macros using GOST/DSTU reference values
     * provided to the AI through prompt examples; micronutrients from USDA are preserved.
     */
    private suspend fun correctDairyMacrosWithAI(
        nutrients: NutrientData,
        foodNameRu: String
    ): NutrientData {
        val percent = extractFatPercent(foodNameRu) ?: return nutrients

        val prompt = """
You are a professional nutritionist. The user entered a dairy product following the Russian/Ukrainian GOST standard, where the percentage in the name is grams of fat per 100g of the final product (NOT % of milkfat in the source milk, NOT USDA cottage cheese variants).

Product: "$foodNameRu"
Fat percentage from name: $percent% (= $percent g of fat per 100g)

Return ONLY a JSON object with macronutrients PER 100 GRAMS of this product, according to GOST/DSTU reference data:
{"protein": <g>, "fat": <g>, "carbs": <g>, "calories": <kcal>}

Examples for calibration (per 100g):
- "творог 0%": {"protein": 18.0, "fat": 0.0, "carbs": 1.8, "calories": 71}
- "творог 5%": {"protein": 17.2, "fat": 5.0, "carbs": 1.8, "calories": 121}
- "творог 9%": {"protein": 16.7, "fat": 9.0, "carbs": 2.0, "calories": 156}
- "молоко 2.5%": {"protein": 2.9, "fat": 2.5, "carbs": 4.7, "calories": 52}
- "сметана 20%": {"protein": 2.5, "fat": 20.0, "carbs": 3.2, "calories": 206}
- "кефир 1%": {"protein": 2.8, "fat": 1.0, "carbs": 4.0, "calories": 37}
- "йогурт 3.2%": {"protein": 5.0, "fat": 3.2, "carbs": 8.5, "calories": 82}

The fat value MUST equal $percent. Calories MUST satisfy: protein*4 + fat*9 + carbs*4 ≈ calories.
""".trimIndent()

        return try {
            val text = callOpenRouterWithRetry(
                messages = listOf(OpenRouterMessage(role = "user", content = prompt)),
                models = textModels
            )
            val json = extractJson(text)
            val map = gson.fromJson(json, Map::class.java) as? Map<String, Any>
            if (map == null) {
                Log.w("Repository", "AI macro correction returned non-map for '$foodNameRu'")
                return nutrients
            }
            fun num(key: String): Double? {
                return when (val raw = map[key]) {
                    is Number -> raw.toDouble()
                    is String -> raw.toDoubleOrNull()
                    else -> null
                }
            }
            val protein = num("protein") ?: return nutrients
            val fat = num("fat") ?: return nutrients
            val carbs = num("carbs") ?: return nutrients
            if (protein <= 0) {
                Log.w("Repository", "AI macro correction returned invalid protein for '$foodNameRu'")
                return nutrients
            }

            // Safety net: fat MUST match the percent from the name (AI sometimes drifts).
            val finalFat = if (kotlin.math.abs(fat - percent) / kotlin.math.max(percent, 0.5) > 0.15) percent else fat
            val rawCalories = num("calories") ?: (protein * 4 + fat * 9 + carbs * 4)
            val finalCalories = if (kotlin.math.abs(finalFat - fat) > 0.01)
                protein * 4 + finalFat * 9 + carbs * 4
            else rawCalories

            val oldFat = nutrients.fat
            // Scale fat fractions proportionally to the new total fat.
            val scale = if (oldFat > 0) finalFat / oldFat else 0.0
            val corrected = nutrients.copy(
                calories = finalCalories,
                protein = protein,
                fat = finalFat,
                carbs = carbs,
                saturatedFat = if (oldFat > 0) nutrients.saturatedFat * scale else nutrients.saturatedFat,
                monounsaturatedFat = if (oldFat > 0) nutrients.monounsaturatedFat * scale else nutrients.monounsaturatedFat,
                polyunsaturatedFat = if (oldFat > 0) nutrients.polyunsaturatedFat * scale else nutrients.polyunsaturatedFat,
                cholesterol = if (oldFat > 0) nutrients.cholesterol * scale else nutrients.cholesterol
            )
            Log.d("Repository", "AI macro correction for '$foodNameRu': protein ${nutrients.protein}→$protein, fat $oldFat→$finalFat, carbs ${nutrients.carbs}→$carbs")
            corrected
        } catch (e: Exception) {
            Log.w("Repository", "AI macro correction failed for '$foodNameRu': ${e.message}")
            nutrients
        }
    }

    /**
     * Enriches fat details (saturated, mono, poly, cholesterol) for cached products
     * that have total fat > 0 but incomplete fat breakdown.
     * Triggers if both mono and poly are 0 (even if saturated is known from OFF).
     * Tries USDA first (free), falls back to AI.
     * Returns enriched NutrientData and updates cache in-place.
     */
    private suspend fun enrichFatDetailsIfNeeded(
        nutrients: NutrientData,
        foodNameEn: String,
        cacheEntityId: Long?
    ): NutrientData {
        // Only enrich if fat > 0 and both mono AND poly are missing (0.0)
        if (nutrients.fat <= 0.0) return nutrients
        if (nutrients.monounsaturatedFat != 0.0 || nutrients.polyunsaturatedFat != 0.0) return nutrients

        Log.d("Repository", "Enriching fat details for '$foodNameEn' (fat=${nutrients.fat}, sat=${nutrients.saturatedFat}, chol=${nutrients.cholesterol})")

        // Working copy — accumulates data from each source, only filling zeros
        var current = nutrients

        try {
            // --- Step 1: USDA — fill only fields that are still 0 ---
            val usdaResult = usdaApi.searchFoods(query = foodNameEn)
            val food = usdaResult.foods?.firstOrNull { f ->
                f.foodNutrients?.any { it.nutrientId == UsdaFoodNutrient.ENERGY && (it.value ?: 0.0) > 0 } == true
                    && f.description?.lowercase()?.contains(
                        foodNameEn.lowercase().split("\\s+".toRegex())
                            .filter { it.length >= 3 }
                            .maxByOrNull { it.length } ?: ""
                    ) == true
            }

            if (food?.foodNutrients != null) {
                val nMap = mutableMapOf<Int, Double>()
                for (fn in food.foodNutrients) {
                    if (fn.nutrientId != null && fn.value != null) nMap[fn.nutrientId] = fn.value
                }
                val uSat = nMap[UsdaFoodNutrient.SATURATED_FAT] ?: 0.0
                val uMono = nMap[UsdaFoodNutrient.MONOUNSATURATED_FAT] ?: 0.0
                val uPoly = nMap[UsdaFoodNutrient.POLYUNSATURATED_FAT] ?: 0.0
                val uChol = nMap[UsdaFoodNutrient.CHOLESTEROL] ?: 0.0

                current = current.copy(
                    saturatedFat = if (current.saturatedFat == 0.0 && uSat > 0) uSat else current.saturatedFat,
                    monounsaturatedFat = if (current.monounsaturatedFat == 0.0 && uMono > 0) uMono else current.monounsaturatedFat,
                    polyunsaturatedFat = if (current.polyunsaturatedFat == 0.0 && uPoly > 0) uPoly else current.polyunsaturatedFat,
                    cholesterol = if (current.cholesterol == 0.0 && uChol > 0) uChol else current.cholesterol
                )
                Log.d("Repository", "After USDA for '$foodNameEn': sat=${current.saturatedFat} mono=${current.monounsaturatedFat} poly=${current.polyunsaturatedFat} chol=${current.cholesterol}")
            }

            // --- Step 2: If mono or poly still 0, ask AI to fill remaining gaps ---
            if (current.monounsaturatedFat == 0.0 || current.polyunsaturatedFat == 0.0) {
                Log.d("Repository", "Asking AI for remaining fat details for '$foodNameEn'")
                val aiPrompt = """
For the food product "$foodNameEn" with total fat ${nutrients.fat}g per 100g, estimate the fat breakdown.
Return ONLY a JSON object:
{"saturated_fat": <grams>, "monounsaturated_fat": <grams>, "polyunsaturated_fat": <grams>, "cholesterol": <mg>}
Rules:
- CRITICAL: saturated_fat + monounsaturated_fat + polyunsaturated_fat MUST be <= ${nutrients.fat}g (total fat)
- Each value must be >= 0 and individually less than total fat (${nutrients.fat}g)
- cholesterol is in mg (milligrams), typical range 0-300mg per 100g
- Use established nutritional data for this food
""".trimIndent()

                val aiText = callOpenRouterWithRetry(
                    messages = listOf(OpenRouterMessage(role = "user", content = aiPrompt)),
                    models = textModels
                )
                val jsonStr = aiText.replace(Regex("```json\\s*|```\\s*"), "").trim()
                val map = gson.fromJson(jsonStr, Map::class.java) as? Map<String, Any>
                if (map != null) {
                    val aiSat = (map["saturated_fat"] as? Number)?.toDouble() ?: 0.0
                    val aiMono = (map["monounsaturated_fat"] as? Number)?.toDouble() ?: 0.0
                    val aiPoly = (map["polyunsaturated_fat"] as? Number)?.toDouble() ?: 0.0
                    val aiChol = (map["cholesterol"] as? Number)?.toDouble() ?: 0.0

                    current = current.copy(
                        saturatedFat = if (current.saturatedFat == 0.0 && aiSat > 0) aiSat else current.saturatedFat,
                        monounsaturatedFat = if (current.monounsaturatedFat == 0.0 && aiMono > 0) aiMono else current.monounsaturatedFat,
                        polyunsaturatedFat = if (current.polyunsaturatedFat == 0.0 && aiPoly > 0) aiPoly else current.polyunsaturatedFat,
                        cholesterol = if (current.cholesterol == 0.0 && aiChol > 0) aiChol else current.cholesterol
                    )
                    Log.d("Repository", "After AI for '$foodNameEn': sat=${current.saturatedFat} mono=${current.monounsaturatedFat} poly=${current.polyunsaturatedFat} chol=${current.cholesterol}")
                }
            }
        } catch (e: Exception) {
            Log.w("Repository", "Fat enrichment failed for '$foodNameEn': ${e.message}")
        }

        // --- Step 3: Validate fat breakdown does not exceed total fat ---
        val totalFat = current.fat
        val fatSum = current.saturatedFat + current.monounsaturatedFat + current.polyunsaturatedFat
        if (fatSum > totalFat && totalFat > 0) {
            val scale = totalFat / fatSum
            current = current.copy(
                saturatedFat = current.saturatedFat * scale,
                monounsaturatedFat = current.monounsaturatedFat * scale,
                polyunsaturatedFat = current.polyunsaturatedFat * scale
            )
        }
        // Each component individually capped
        current = current.copy(
            saturatedFat = minOf(current.saturatedFat, totalFat),
            monounsaturatedFat = minOf(current.monounsaturatedFat, totalFat),
            polyunsaturatedFat = minOf(current.polyunsaturatedFat, totalFat)
        )

        // --- Step 4: Sentinel — if mono/poly are still 0 after all sources, set 0.0001 to prevent re-enrichment ---
        if (current.monounsaturatedFat == 0.0 && current.polyunsaturatedFat == 0.0) {
            current = current.copy(monounsaturatedFat = 0.0001, polyunsaturatedFat = 0.0001)
            Log.d("Repository", "Set sentinel for '$foodNameEn' — no sources provided mono/poly data")
        }

        // Save to cache if changed
        if (current != nutrients && cacheEntityId != null) {
            db.foodCacheDao().updateNutrients(cacheEntityId, gson.toJson(current))
        }
        Log.d("Repository", "Fat enrichment complete for '$foodNameEn': sat=${current.saturatedFat} mono=${current.monounsaturatedFat} poly=${current.polyunsaturatedFat} chol=${current.cholesterol}")

        return current
    }

    suspend fun cacheFoodData(name: String, nameEn: String, per100g: NutrientData) {
        try {
            saveToCache(name, nameEn, per100g)
        } catch (e: Exception) {
            Log.w("Repository", "Failed to cache food: ${e.message}")
        }
    }

    /**
     * Public wrapper: parse nutrients from a cache entry and enrich fat details if needed.
     */
    suspend fun enrichFatDetailsForCachedEntry(entry: FoodCacheEntity): NutrientData {
        val nutrients = parseNutrients(entry.nutrientsPer100gJson)
        return enrichFatDetailsIfNeeded(nutrients, entry.keyEn, entry.id)
    }

    /**
     * Parse food input locally: split by comma only.
     * Conjunctions "и"/"і" are NOT used as separators because they appear
     * inside compound dish names (e.g. "салат из тунца и авокадо").
     * The AI step handles multi-item disambiguation correctly.
     * Each item: "food name 150г" or "food name 150 г" or "food name"
     */
    private fun parseLocalFoodInput(input: String): List<Pair<String, Double>> {
        // Split by comma only — "и"/"і" can be part of a dish name
        val items = input.split(Regex("""\s*,\s*"""))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        return items.map { item ->
            // Extract weight: number followed by optional space and unit (г, гр, грамм, g, ml, мл, кг, kg)
            val weightRegex = Regex("""(\d+(?:[.,]\d+)?)\s*(г|гр|грамм|g|ml|мл|кг|kg)\b""", RegexOption.IGNORE_CASE)
            val match = weightRegex.find(item)
            val weight = if (match != null) {
                var w = match.groupValues[1].replace(",", ".").toDoubleOrNull() ?: 0.0
                val unit = match.groupValues[2].lowercase()
                if (unit == "кг" || unit == "kg") w *= 1000
                w
            } else 0.0
            // Food name = item minus the weight part
            val name = if (match != null) {
                item.removeRange(match.range).trim()
            } else {
                item.trim()
            }
            name to weight
        }.filter { it.first.isNotBlank() }
    }

    // ─── AI-driven USDA candidate selection ─────────────────────────────────
    // Instead of a hand-tuned scoreFood() heuristic, we ask the AI to pick the
    // best USDA hit for the query. The AI sees only a compact candidate list
    // (fdcId + description + dataType + macros) and returns a single fdcId —
    // nutrient values are still taken from the USDA JSON, so the model cannot
    // "invent" data from its own sources.

    /** Compact form of a USDA hit used to build the AI selection prompt. */
    private data class UsdaCandidateBrief(
        val fdcId: Int,
        val description: String,
        val dataType: String,
        val calories: Double,
        val protein: Double,
        val fat: Double,
        val carbs: Double
    )

    private fun usdaCandidateBrief(f: UsdaFood): UsdaCandidateBrief? {
        val id = f.fdcId ?: return null
        val nMap = mutableMapOf<Int, Double>()
        for (fn in f.foodNutrients ?: emptyList()) {
            if (fn.nutrientId != null && fn.value != null) nMap[fn.nutrientId] = fn.value
        }
        val cal = nMap[UsdaFoodNutrient.ENERGY] ?: 0.0
        if (cal <= 0.0) return null
        return UsdaCandidateBrief(
            fdcId = id,
            description = f.description ?: "",
            dataType = f.dataType ?: "",
            calories = cal,
            protein = nMap[UsdaFoodNutrient.PROTEIN] ?: 0.0,
            fat = nMap[UsdaFoodNutrient.FAT] ?: 0.0,
            carbs = nMap[UsdaFoodNutrient.CARBS] ?: 0.0
        )
    }

    /**
     * Ask the AI to pick the best USDA candidate for a query.
     * Returns the chosen fdcId, or null if no candidate is a good match.
     * The returned id is verified against the candidate list to guard against hallucinated IDs.
     */
    private suspend fun askAiToPickUsdaCandidate(
        queryRu: String,
        queryEn: String,
        candidates: List<UsdaCandidateBrief>
    ): Int? {
        if (candidates.isEmpty()) return null
        val list = candidates.mapIndexed { i, c ->
            "${i + 1}. [fdcId=${c.fdcId}] [${c.dataType}] \"${c.description}\" — " +
                "cal=${"%.0f".format(c.calories)}, P=${"%.1f".format(c.protein)}, " +
                "F=${"%.1f".format(c.fat)}, C=${"%.1f".format(c.carbs)}"
        }.joinToString("\n")

        val prompt = """
You are a nutrition expert selecting the single best USDA Food Data Central entry that matches a user's food query.

User query (original language): "$queryRu"
User query (English): "$queryEn"

USDA candidates (values are per 100g):
$list

Selection rules — in order of importance:

1. **BIOLOGICAL IDENTITY is non-negotiable.** The candidate MUST be the SAME species / product as the query — not a lexically similar but biologically different food. If none of the candidates is the same product, return fdc_id = null.
   Common traps to REJECT:
   - "черемша" / "wild garlic" / "ramps" / "wild leek" (Allium ursinum / Allium tricoccum) is NOT the same as "garlic" (Allium sativum) — garlic bulbs have ~33g carbs, wild garlic leaves have ~3-6g. Never accept "Garlic, raw" for a "wild garlic" / "ramps" / "черемша" query.
   - "cashew" is NOT "chestnut"; "chestnut" is NOT "water chestnut".
   - "cilantro" / "coriander leaf" is NOT "coriander seed"; "parsley" is NOT "cilantro".
   - "sweet potato" / "yam" is NOT "potato".
   - "sour cherry" / "вишня" is NOT "sweet cherry" / "черешня".
   - "buckwheat" is NOT "wheat"; "millet" is NOT "corn".
   - "quinoa" is NOT "couscous"; "spelt" is NOT "wheat".
   - "kohlrabi" is NOT "cabbage"; "bok choy" is NOT "cabbage".
   - "veal" is NOT "beef"; "mutton" is NOT "lamb".
   - "salmon" is NOT "trout"; "cod" is NOT "haddock" (different species — check the description carefully).
   - Frozen / canned / dried / juice / pie / jam / chips / cereal / candy / powder / ice cream forms are NOT the raw whole product.

2. **Macronutrient sanity check.** For the query's food family (leafy green, root vegetable, fruit, meat, grain, dairy...), the candidate's macros must be plausible. A "leafy green vegetable" query with >20g carbs per 100g is almost certainly the wrong product (leaves rarely exceed 5-8g carbs). A "raw fruit" query with 0g fiber and >30g carbs is likely juice or dried fruit.

3. **Preparation state must match:**
   - For raw fruits / vegetables / berries with no cooking method mentioned — pick "raw" / whole product.
   - For cooked / boiled / porridge — pick an entry with matching preparation state ("cooked", NOT "from raw" which means dry-weight equivalent).
   - Frozen / canned / dried / juice / pie / jam / chips / cereal / candy / powder / ice cream are different products — do NOT accept unless the query explicitly asked for that form.

4. **Data-type preference:** Prefer "Survey (FNDDS)", "SR Legacy", "Foundation" over "Branded" for generic ingredients.

5. **Implausible Branded macros filter:** Reject Branded entries where protein > 40g (unless protein powder / whey / jerky / parmesan), carbs > 75g (unless sugar / jam / flour / cereal / dried), fat > 70g (unless oil / butter / ghee / lard / mayonnaise).

6. **Atwater check:** Reject entries where 4·protein + 9·fat + 4·carbs > 1.3 × calories.

7. **Poultry:** For chicken / turkey breast or thigh, prefer "skinless" / "meat only" unless the query mentions skin or coating.

8. **Generic over variety:** Prefer generic entries over variety-specific ones when the query has no variety qualifier ("tomatoes, raw" over "tomatoes, green, raw").

9. **No hallucination:** Only return an fdcId from the numbered list above. If nothing is a good match, return null — DO NOT force a pick.

Return ONLY a JSON object:
{"fdc_id": <chosen id, or null if none of the candidates match well>, "reason": "<one short sentence explaining the pick or why nothing fit>"}
""".trimIndent()

        return try {
            val text = callOpenRouterWithRetry(
                messages = listOf(OpenRouterMessage(role = "user", content = prompt)),
                models = textModels
            )
            val json = extractJson(text)
            val map = gson.fromJson(json, Map::class.java) as? Map<String, Any>
            if (map == null) {
                Log.w("Repository", "AI USDA pick returned non-map for '$queryEn'")
                return null
            }
            val id = when (val raw = map["fdc_id"]) {
                is Number -> raw.toInt()
                is String -> raw.toIntOrNull()
                else -> null
            }
            Log.d("Repository", "AI USDA pick for '$queryEn': fdcId=$id, reason=${map["reason"]}")
            // Guard against hallucination: the id must exist in the candidate list.
            if (id != null && candidates.any { it.fdcId == id }) id else null
        } catch (e: Exception) {
            Log.w("Repository", "AI USDA pick failed for '$queryEn': ${e.message}")
            null
        }
    }

    /**
     * Adversarial post-verification: after askAiToPickUsdaCandidate picks a candidate,
     * ask the AI a fresh, sharply-focused question — "is this REALLY the same biological
     * product as the query?" — with an instruction to default to REJECT on any doubt.
     * Catches lexical-similarity traps that a permissive selection prompt might slip
     * through (e.g. AI picks "Garlic, raw" for a "черемша"/"wild garlic" query).
     */
    private suspend fun verifyUsdaPick(
        queryRu: String,
        queryEn: String,
        pick: UsdaCandidateBrief
    ): Boolean {
        val prompt = """
You are a nutrition-safety reviewer. Someone selected a USDA entry as the match for a user's food query. Your job is to REJECT it if the entry is NOT the same biological product / species / dish, even if the names are lexically similar.

User query (original language): "$queryRu"
User query (English): "$queryEn"

Selected USDA entry:
  description: "${pick.description}"
  dataType:    "${pick.dataType}"
  per 100g:    cal=${"%.0f".format(pick.calories)}, P=${"%.1f".format(pick.protein)}, F=${"%.1f".format(pick.fat)}, C=${"%.1f".format(pick.carbs)}

Answer TWO questions:
1. is_same_product: is this USDA entry the SAME biological product / species / dish as the user asked for? Different species (garlic vs wild garlic, cashew vs chestnut, cilantro vs parsley, sour vs sweet cherry, sweet potato vs potato, veal vs beef, salmon vs trout, buckwheat vs wheat, etc.) → false. Different form (juice / jam / pie / chips / dried / candied / powder / ice cream when the user asked for the whole raw product) → false.
2. macros_plausible: are the per-100g macros plausible for the QUERIED product's food family? A leafy green with >15g carbs is suspicious. A raw fruit with 0g fiber and >30g carbs is likely juice or dried. Meat with 0g protein is wrong.

Default to false if unsure. It's better to reject a correct pick than accept a wrong one — the caller will fall back to a different data source.

Return ONLY a JSON object:
{"is_same_product": <true|false>, "macros_plausible": <true|false>, "reason": "<one short sentence>"}
""".trimIndent()
        return try {
            val text = callOpenRouterWithRetry(
                messages = listOf(OpenRouterMessage(role = "user", content = prompt)),
                models = textModels
            )
            val json = extractJson(text)
            val map = gson.fromJson(json, Map::class.java) as? Map<*, *>
            if (map == null) {
                Log.w("Repository", "verifyUsdaPick: non-map response for '$queryEn' — treating as REJECT")
                return false
            }
            fun bool(key: String): Boolean = when (val v = map[key]) {
                is Boolean -> v
                is String -> v.equals("true", ignoreCase = true)
                else -> false
            }
            val ok = bool("is_same_product") && bool("macros_plausible")
            Log.d("Repository", "verifyUsdaPick '${pick.description}' for '$queryEn': ok=$ok reason=${map["reason"]}")
            ok
        } catch (e: Exception) {
            Log.w("Repository", "verifyUsdaPick failed for '$queryEn': ${e.message} — treating as REJECT")
            false
        }
    }

    /**
     * Ask the AI for 2-3 alternative English USDA search queries when the initial
     * search returned no acceptable matches. Used for localised / transliterated
     * dishes where a direct translation doesn't hit USDA descriptions.
     */
    private suspend fun generateAltUsdaQueries(queryRu: String, queryEn: String): List<String> {
        val prompt = """
The USDA Food Data Central search for "$queryEn" returned no good matches for user query "$queryRu".
Suggest 2-3 alternative English search queries that USDA is more likely to index for this exact food.

Rules:
- Use American English (beet not beetroot, eggplant not aubergine, cilantro not coriander leaf).
- Use USDA-style naming: singular plain nouns, "raw" / "cooked" if applicable.
- Include synonyms, common names, and simpler forms (e.g. "farmers cheese" for "curd").
- Keep the meaning of the original query — do NOT return a different food.

Examples:
- "wild garlic raw" (черемша) → ["ramps raw", "wild leek raw", "allium tricoccum raw"]
- "kefir 1%" → ["kefir low fat", "cultured milk kefir"]
- "green buckwheat" → ["buckwheat groats raw", "buckwheat kernels raw"]
- "curd 5%" → ["cottage cheese lowfat", "farmers cheese"]
- "borscht" → ["beet soup", "borscht russian"]

Return ONLY a JSON array of English strings, e.g. ["query1", "query2", "query3"].
""".trimIndent()

        return try {
            val text = callOpenRouterWithRetry(
                messages = listOf(OpenRouterMessage(role = "user", content = prompt)),
                models = textModels
            )
            val json = extractJsonContent(text)
            val type = object : TypeToken<List<String>>() {}.type
            // Try as raw array first; fall back to looking for a nested list inside a wrapper object
            val list: List<String> = try {
                gson.fromJson<List<String>>(json, type) ?: emptyList()
            } catch (_: Exception) {
                val map = gson.fromJson(json, Map::class.java) as? Map<*, *>
                val firstList = map?.values?.firstOrNull { it is List<*> } as? List<*>
                firstList?.mapNotNull { it as? String } ?: emptyList()
            }
            // Cap at 2 to keep sequential AI cost bounded (2 alt rounds max).
            list.filter { it.isNotBlank() }.distinct().take(2)
        } catch (e: Exception) {
            Log.w("Repository", "AI alt-query generation failed for '$queryEn': ${e.message}")
            emptyList()
        }
    }

    suspend fun analyzeFoodText(foodDescription: String, useCache: Boolean = true): List<FoodAnalysisResult> {
        // Step 0: Try local parse + cache lookup first
        val localParsed = parseLocalFoodInput(foodDescription)
        Log.d("Repository", "Local parse: ${localParsed.size} items: $localParsed")

        data class PendingFood(
            val foodNameRu: String,
            val foodNameEn: String,
            val weight: Double,
            var nutrientsPer100g: NutrientData? = null,
            var fromCache: Boolean = false,
            var cacheEntityId: Long? = null
        )

        val cachedResults = mutableListOf<PendingFood>()
        val uncachedItems = mutableListOf<Pair<String, Double>>()

        for ((name, weight) in localParsed) {
            val cached = if (useCache) findInCache(name) else null
            if (cached != null) {
                val n = cached.second
                // Reject cached data with implausible macros: 0 protein + 0 carbs but has fat
                // (valid only for pure fats/oils)
                val isFatOnlyKey = listOf("масло", "олія", "oil", "butter", "lard", "ghee", "жир", "сало")
                    .any { name.lowercase().contains(it) }
                val cacheImplausible = !isFatOnlyKey && n.protein == 0.0 && n.carbs == 0.0 && n.fat > 0
                if (cacheImplausible) {
                    Log.w("Repository", "Cache REJECTED for '$name' (implausible: p=${n.protein} f=${n.fat} c=${n.carbs})")
                    try { db.foodCacheDao().delete(cached.first) } catch (_: Exception) {}
                    uncachedItems.add(name to weight)
                } else {
                    Log.d("Repository", "Cache HIT for '$name'")
                    cachedResults.add(PendingFood(
                        foodNameRu = name,
                        foodNameEn = cached.first.keyEn,
                        weight = if (weight > 0) weight else 100.0,
                        nutrientsPer100g = cached.second,
                        fromCache = true,
                        cacheEntityId = cached.first.id
                    ))
                }
            } else {
                uncachedItems.add(name to weight)
            }
        }

        // Enrich fat details for cached items (legacy data without fat breakdown)
        for (item in cachedResults) {
            item.nutrientsPer100g = enrichFatDetailsIfNeeded(
                item.nutrientsPer100g!!, item.foodNameEn, item.cacheEntityId
            )
        }

        // If everything was cached, return immediately
        if (uncachedItems.isEmpty() && cachedResults.isNotEmpty()) {
            Log.d("Repository", "All ${cachedResults.size} items from cache!")
            return cachedResults.map { item ->
                val factor = item.weight / 100.0
                FoodAnalysisResult(
                    foodName = item.foodNameRu,
                    foodNameEn = item.foodNameEn,
                    weightGrams = item.weight,
                    nutrients = item.nutrientsPer100g!! * factor,
                    fromCache = true
                )
            }
        }

        // Step 1: AI identifies uncached foods with names (RU + EN) and weights
        val aiPending = mutableListOf<PendingFood>()

        if (uncachedItems.isNotEmpty()) {
        val descriptionForAi = uncachedItems.joinToString(", ") { (name, w) ->
                if (w > 0) "$name ${w.toInt()}г" else name
            }

        val identifyPrompt = """
Определи ВСЕ продукты и их вес из описания. Описание может быть на русском, украинском или другом языке.
Если указано количество штук — рассчитай общий вес. Если вес не указан — оцени типичную порцию.

ВАЖНО:
- food_name — сохрани название НА ЯЗЫКЕ ВВОДА (не переводи на другой язык)
- food_name_en — ТОЧНЫЙ перевод на английский для поиска в USDA базе данных

Примеры правильного перевода:
- "куриная отбивная" / "куряча відбивна" → "chicken breast cutlet"
- "гречневая каша" / "гречана каша" → "buckwheat porridge"
- "ячневая каша вареная" / "ячнева каша варена" / "ячневая каша варенная" → "barley porridge cooked"
- "овсяная каша" / "вівсяна каша" → "oatmeal cooked"
- "творог нежирный" / "сир кисломолочний нежирний" → "low-fat cottage cheese"

ВАЖНО: слова "вареная"/"варена"/"варенная"/"варёная"/"отварная" ВСЕ означают "cooked" — всегда добавляй "cooked" в перевод!
- "борщ" → "borscht"
- "вареники с картошкой" / "вареники з картоплею" → "potato pierogi"
- "вареники с творогом" / "вареники з сиром" → "cottage cheese pierogi"
- "пельмени" → "pelmeni meat dumplings"
- "сырники" / "сирники" → "cottage cheese pancakes"
- "окрошка" → "okroshka cold soup"
- "оливье" / "олів'є" → "russian potato salad"
- "винегрет" / "вінегрет" → "vinaigrette beet salad"
- "млинці" / "блины" → "crepes"
- "голубці" / "голубцы" → "stuffed cabbage rolls"
- "банош" → "banosh cornmeal porridge"
- "деруни" / "драники" → "potato pancakes"
- "холодець" / "холодец" → "head cheese"
- "заливне" / "заливное" → "jellied meat"
- "лосось слабосоленный" / "лосось слабосолений" → "salmon salted"
- "сёмга слабосоленная" → "salmon salted"
- "селедка" / "оселедець" → "herring salted"
- "скумбрия копченая" → "mackerel smoked"
- "тунец консервированный" → "tuna canned"
- "паштет печеночный" / "паштет печінковий" → "liver pate"
- "паштет" → "pate"
- "кава" / "кофе" → "coffee brewed"
- "кава з молоком" / "кофе с молоком" → "coffee with milk"
- "капучіно" / "капучино" → "coffee cappuccino"
- "латте" / "лате" → "coffee latte"
- "чай" / "чай чорний" / "чай черный" → "tea brewed"
- "чай зелений" / "чай зеленый" → "green tea brewed"
- "шарлотка" → "apple sponge cake"
- "запіканка" / "запеканка" → "cottage cheese casserole"
- "черешня" / "черешні" → "sweet cherry raw"
- "вишня" / "вишні" → "sour cherry raw"
- "клубника" / "полуниця" → "strawberry raw"
- "малина" / "малини" → "raspberry raw"
- "голубика" / "лохина" → "blueberry raw"
- "смородина" → "currant raw"
- "абрикос" / "абрикоси" → "apricot raw"
- "персик" / "персики" → "peach raw"
- "слива" / "сливи" → "plum raw"
- "виноград" → "grape raw"

ВАЖНО для свежих овощей/фруктов/ягод/зелени:
Если продукт — свежий овощ, фрукт, ягода, зелень или листовой салат,
и в названии НЕ указан способ приготовления (вареный/жареный/тушёный/печёный/квашеный/маринованный/сушёный и т.п.),
обязательно добавь "raw" в food_name_en. USDA по умолчанию выдаёт салаты и обработанные варианты вместо свежего продукта.

Примеры:
- "капуста" → "cabbage raw"
- "морковь" / "морква" → "carrot raw"
- "яблоко" / "яблуко" → "apple raw"
- "помидор" / "помідор" → "tomato raw"
- "огурец" / "огірок" → "cucumber raw"
- "лук" / "цибуля" → "onion raw"
- "шпинат" → "spinach raw"
- "банан" → "banana raw"
- "брокколи" → "broccoli raw"
- "перец болгарский" / "перець солодкий" → "bell pepper raw"
- "свекла" / "буряк" / "свёкла" → "beet raw"
- "репа" / "ріпа" → "turnip raw"
- "редька" / "редька чёрная" → "radish raw"
- "тыква" / "гарбуз" → "pumpkin raw"
- "кабачок" / "цукіні" → "zucchini raw"
- "баклажан" → "eggplant raw"
- "сельдерей" / "селера" → "celery raw"
- "петрушка" / "петрушка свіжа" → "parsley raw"
- "укроп" / "кріп" → "dill raw"
- "виноград" → "grapes raw"
- "черешня" / "черешні" → "sweet cherry raw"
- "вишня" / "вишні" → "sour cherry raw"
- "черника" / "чорниця" → "blueberry raw"
- "голубика" / "лохина" → "blueberry raw"
- "смородина чёрная" / "чорна смородина" → "blackcurrant raw"
- "смородина красная" / "червона смородина" → "redcurrant raw"
- "кукуруза" / "кукурудза" → "corn raw"
- "горох свежий" / "горох" → "peas raw"
- "фасоль стручковая" / "зелена квасоля" → "green beans raw"
- "цветная капуста" / "цвітна капуста" → "cauliflower raw"
- "авокадо" → "avocado raw"
- "ананас" → "pineapple raw"
- "манго" → "mango raw"
- "киви" → "kiwi raw"

ВАЖНО для USDA — используй АМЕРИКАНСКИЙ английский, не британский:
- "beet", НЕ "beetroot" (beetroot = только чипсы в USDA)
- "eggplant", НЕ "aubergine"
- "zucchini", НЕ "courgette"
- "cilantro", НЕ "coriander" (для зелени)
- "bell pepper", НЕ "capsicum"

НЕ добавляй "raw" для:
- мяса/рыбы/птицы/яиц (без указания способа — подразумевается приготовленное)
- круп, макарон, бобовых, хлеба
- молочных продуктов, сыров, орехов, семян, масел
- готовых блюд, консервов, продуктов прошедших обработку
- если в названии уже есть способ приготовления или "сырой"/"свіжий"/"raw"/"fresh"

ВАЖНО для составных блюд (салаты, супы):
- НЕ перечисляй все ингредиенты в food_name_en — используй КОРОТКОЕ узнаваемое название
- "салат з крабових паличок" / "салат из крабовых палочек" → "imitation crab salad"
- "салат з яєць крабових паличок кукурудзи і огірка" → "imitation crab egg salad"
- "салат цезар" / "салат цезарь" → "caesar salad"
- "грецький салат" / "греческий салат" → "greek salad"
- "салат з тунцем" / "салат с тунцом" → "tuna salad"
- Если блюдо "без заправки" / "без майонеза" — НЕ включай "without dressing" в food_name_en
- Для неизвестных составных салатов используй краткое описание: "mixed vegetable egg salad"

Описание: $descriptionForAi

Верни ТОЛЬКО JSON массив (даже если продукт один):
[{"food_name": "<название НА ЯЗЫКЕ ВВОДА>", "food_name_en": "<EXACT English translation for USDA search>", "weight_grams": <число>}]
""".trimIndent()

        val identifyText = callOpenRouterWithRetry(
            messages = listOf(OpenRouterMessage(role = "user", content = identifyPrompt)),
            models = textModels
        )

        Log.d("Repository", "AI identify response: $identifyText")

        val identities: List<FoodIdentity> = parseIdentityList(identifyText)

        if (identities.isEmpty() && cachedResults.isEmpty()) {
            throw Exception("Не удалось распознать продукты из описания")
        }

        // Check cache again by English key (AI may have translated to a cached key)
        for (id in identities) {
            // Strip weight from AI food_name — AI sometimes includes it
            val rawName = id.foodName.ifBlank { descriptionForAi }
            val nameRu = rawName.replace(Regex("""\s*\d+(?:[.,]\d+)?\s*(г|гр|грамм|g|ml|мл|кг|kg)\b""", RegexOption.IGNORE_CASE), "").trim()
            val nameEn = id.foodNameEn.ifBlank { id.foodName }
            val weight = if (id.weightGrams > 0) id.weightGrams else 100.0

            // Try cache by English key
            val cachedByEn = if (useCache) findInCache(nameEn) else null
            if (cachedByEn != null) {
                Log.d("Repository", "Cache HIT by EN key '$nameEn' for '$nameRu'")
                val enrichedNutrients = enrichFatDetailsIfNeeded(cachedByEn.second, nameEn, cachedByEn.first.id)
                // Also save under original name for next time
                try { saveToCache(nameRu, nameEn, enrichedNutrients) } catch (_: Exception) {}
                cachedResults.add(PendingFood(
                    foodNameRu = nameRu, foodNameEn = nameEn,
                    weight = weight, nutrientsPer100g = enrichedNutrients, fromCache = true
                ))
            } else {
                aiPending.add(PendingFood(
                    foodNameRu = nameRu, foodNameEn = nameEn, weight = weight
                ))
            }
        }

        // Step 2: USDA lookup for uncached foods — AI picks the best candidate
        for ((idx, item) in aiPending.withIndex()) {
            Log.d("Repository", "Processing: '${item.foodNameRu}' / '${item.foodNameEn}', weight=${item.weight}g")
            try {
                // For dairy with explicit % fat (e.g. "творог 5%"), strip the percent from the
                // English query — USDA doesn't index RU/UA fat grades, so we search the base
                // product and later correct macros via AI.
                val isDairyWithPercent = isDairyWithFatPercent(item.foodNameRu)
                val foodNameEnForSearch = run {
                    var name = if (isDairyWithPercent) stripFatPercent(item.foodNameEn) else item.foodNameEn
                    // Normalise British English → American English so USDA finds the right entry
                    val britishToAmerican = listOf(
                        "beetroot" to "beet", "aubergine" to "eggplant", "courgette" to "zucchini",
                        "coriander leaf" to "cilantro", "capsicum" to "bell pepper",
                        "rocket" to "arugula", "mangetout" to "snow peas", "swede" to "rutabaga",
                        "broad bean" to "fava bean", "chickpea" to "garbanzo bean",
                        "maize" to "corn", "prawn" to "shrimp"
                    )
                    for ((british, american) in britishToAmerican) {
                        name = name.replace(british, american, ignoreCase = true)
                    }
                    name
                }

                // Strip negation phrases ("without X", "no X") — they describe ABSENCE
                // of an ingredient and pollute USDA search (e.g. "without dressing" → matches dressings)
                val negationCleaned = foodNameEnForSearch.replace(
                    Regex("""\b(without|no|not|minus|free\s+from)\s+\w+""", RegexOption.IGNORE_CASE), ""
                ).replace(Regex("\\s+"), " ").trim()

                // Skip USDA for multi-ingredient composite dishes (AI handles them better)
                // e.g. "egg crab stick corn cucumber salad" has 6 significant words
                val significantWords = negationCleaned.lowercase().split("\\s+".toRegex())
                    .filter { it.length >= 3 && it !in setOf("with", "and", "the", "from", "for") }
                if (significantWords.size > 5) {
                    Log.d("Repository", "Skipping USDA for composite dish (${significantWords.size} words): '$negationCleaned'")
                    continue
                }

                // Round 1: search USDA with the direct English name, ask AI to pick the best match.
                val primaryQuery = negationCleaned.ifBlank { foodNameEnForSearch }
                // The query shown to the AI must match what USDA was actually searched for —
                // otherwise for dairy-with-% ("творог 5%" → foodNameEnForSearch="curd") the AI
                // sees "curd 5%" and rejects the base-product hits as fat-mismatched.
                val queryRuForAi = if (isDairyWithPercent) stripFatPercent(item.foodNameRu) else item.foodNameRu
                val queryEnForAi = if (isDairyWithPercent) stripFatPercent(item.foodNameEn) else item.foodNameEn
                val allCandidates = mutableMapOf<Int, UsdaFood>() // fdcId → UsdaFood, cumulative across rounds
                var selectedFood: UsdaFood? = null

                // Runs one USDA search round: fetches, dedups, then asks AI to pick from the FULL
                // cumulative candidate set (not just this round's delta) — so a later alt query
                // doesn't hide a good round-1 hit from the AI. After the pick, a second AI call
                // adversarially verifies the pick is the same biological product.
                suspend fun runOneRound(query: String): UsdaFood? {
                    val res = usdaApi.searchFoods(query = query)
                    val fresh = res.foods.orEmpty().mapNotNull { f ->
                        val id = f.fdcId ?: return@mapNotNull null
                        if (allCandidates.containsKey(id)) null else f
                    }
                    for (f in fresh) allCandidates[f.fdcId!!] = f
                    val briefsById = allCandidates.values.mapNotNull { usdaCandidateBrief(it) }
                        .associateBy { it.fdcId }
                    if (briefsById.isEmpty()) {
                        Log.d("Repository", "USDA query='$query' — no candidates with calories in cumulative set")
                        return null
                    }
                    Log.d("Repository", "USDA query='$query': +${fresh.size} new, ${briefsById.size} total fed to AI")
                    // Up to 2 selection attempts: if the pick fails post-verification, ask AI
                    // to choose from the remaining candidates.
                    val excluded = mutableSetOf<Int>()
                    repeat(2) {
                        val remaining = briefsById.values.filter { it.fdcId !in excluded }
                        if (remaining.isEmpty()) return@repeat
                        val pickedId = askAiToPickUsdaCandidate(queryRuForAi, queryEnForAi, remaining) ?: return@repeat
                        val pickedBrief = briefsById[pickedId] ?: return@repeat
                        if (verifyUsdaPick(queryRuForAi, queryEnForAi, pickedBrief)) {
                            return allCandidates[pickedId]
                        }
                        Log.d("Repository", "verifyUsdaPick REJECTED '${pickedBrief.description}' for '$queryEnForAi' — retrying selection")
                        excluded.add(pickedId)
                    }
                    return null
                }

                selectedFood = runOneRound(primaryQuery)

                // Round 2 (fallback): ask AI for alternative queries, retry.
                // Skip alt queries for dairy-with-% — GOST correction path takes over anyway.
                // We DON'T skip based on candidate count anymore — the black case (черемша →
                // 25 garlic candidates, all wrong) is exactly when alt queries save us.
                val shouldTryAlt = selectedFood == null && !isDairyWithPercent
                if (shouldTryAlt) {
                    val altQueries = generateAltUsdaQueries(queryRuForAi, queryEnForAi)
                    Log.d("Repository", "AI suggested alt queries for '$queryEnForAi': $altQueries")
                    for (alt in altQueries) {
                        if (alt.equals(primaryQuery, ignoreCase = true) ||
                            alt.equals(foodNameEnForSearch, ignoreCase = true)) continue
                        selectedFood = runOneRound(alt)
                        if (selectedFood != null) break
                    }
                }

                // Safety net: if AI rejected everything but candidates exist, fall through to Step 3 (AI nutrients).
                if (selectedFood == null) {
                    Log.d("Repository", "AI rejected all USDA candidates for '${item.foodNameEn}' — falling through to Step 3")
                }
                Log.d("Repository", "USDA selected: '${selectedFood?.description}' (${selectedFood?.dataType}) from ${allCandidates.size} unique candidates")
                if (selectedFood?.foodNutrients != null) {
                    val nMap = mutableMapOf<Int, Double>()
                    for (fn in selectedFood.foodNutrients) {
                        if (fn.nutrientId != null && fn.value != null) nMap[fn.nutrientId] = fn.value
                    }
                    val N = UsdaFoodNutrient
                    // Store per 100g for caching
                    val per100g = NutrientData(
                        calories = nMap[N.ENERGY] ?: 0.0, protein = nMap[N.PROTEIN] ?: 0.0,
                        fat = nMap[N.FAT] ?: 0.0,
                        saturatedFat = nMap[N.SATURATED_FAT] ?: 0.0,
                        monounsaturatedFat = nMap[N.MONOUNSATURATED_FAT] ?: 0.0,
                        polyunsaturatedFat = nMap[N.POLYUNSATURATED_FAT] ?: 0.0,
                        cholesterol = nMap[N.CHOLESTEROL] ?: 0.0,
                        carbs = nMap[N.CARBS] ?: 0.0,
                        fiber = nMap[N.FIBER] ?: 0.0, vitaminA = nMap[N.VITAMIN_A] ?: 0.0,
                        vitaminB1 = nMap[N.VITAMIN_B1] ?: 0.0, vitaminB2 = nMap[N.VITAMIN_B2] ?: 0.0,
                        vitaminB3 = nMap[N.VITAMIN_B3] ?: 0.0, vitaminB5 = nMap[N.VITAMIN_B5] ?: 0.0,
                        vitaminB6 = nMap[N.VITAMIN_B6] ?: 0.0, vitaminB7 = nMap[N.VITAMIN_B7] ?: 0.0,
                        vitaminB9 = nMap[N.VITAMIN_B9] ?: 0.0, vitaminB12 = nMap[N.VITAMIN_B12] ?: 0.0,
                        vitaminC = nMap[N.VITAMIN_C] ?: 0.0, vitaminD = nMap[N.VITAMIN_D] ?: 0.0,
                        vitaminE = nMap[N.VITAMIN_E] ?: 0.0, vitaminK = nMap[N.VITAMIN_K] ?: 0.0,
                        calcium = nMap[N.CALCIUM] ?: 0.0, iron = nMap[N.IRON] ?: 0.0,
                        magnesium = nMap[N.MAGNESIUM] ?: 0.0, phosphorus = nMap[N.PHOSPHORUS] ?: 0.0,
                        potassium = nMap[N.POTASSIUM] ?: 0.0, sodium = nMap[N.SODIUM] ?: 0.0,
                        zinc = nMap[N.ZINC] ?: 0.0, copper = nMap[N.COPPER] ?: 0.0,
                        manganese = nMap[N.MANGANESE] ?: 0.0, selenium = nMap[N.SELENIUM] ?: 0.0,
                        iodine = nMap[N.IODINE] ?: 0.0
                    )
                    // Correct US enrichment bias for flour-based foods
                    // In the US, flour is fortified with B9, B1, B2, B3, iron — not typical for Eastern Europe
                    val flourKeywords = listOf(
                        "pierogi", "dumpling", "pelmeni", "ravioli", "wonton",
                        "bread", "roll", "bun", "bagel", "tortilla", "pita", "naan", "flatbread",
                        "pasta", "noodle", "spaghetti", "macaroni", "lasagna",
                        "pancake", "crepe", "waffle", "blini", "blintz",
                        "cake", "cookie", "biscuit", "muffin", "pie", "pastry", "croissant", "doughnut",
                        "flour", "cereal", "cornmeal", "porridge"
                    )
                    val foodDesc = (selectedFood.description ?: "").lowercase() + " " + item.foodNameEn.lowercase()
                    val isFlourBased = flourKeywords.any { foodDesc.contains(it) }
                    val corrected = if (isFlourBased) {
                        Log.d("Repository", "Applying enrichment correction for flour-based: ${selectedFood.description}")
                        per100g.copy(
                            vitaminB1 = per100g.vitaminB1 * 0.17,
                            vitaminB2 = per100g.vitaminB2 * 0.10,
                            vitaminB3 = per100g.vitaminB3 * 0.22,
                            vitaminB9 = per100g.vitaminB9 * 0.17,
                            iron = per100g.iron * 0.26
                        )
                    } else per100g

                    // Sanity check: macros from fat+protein+carbs shouldn't exceed reported calories by >30%
                    val macroCalories = corrected.protein * 4 + corrected.fat * 9 + corrected.carbs * 4
                    val reportedCalories = corrected.calories
                    // Also: 0 protein + 0 carbs is implausible for anything except pure fats/oils
                    val isPureFat = listOf("oil", "butter", "lard", "ghee", "shortening", "fat", "grease")
                        .any { negationCleaned.lowercase().contains(it) }
                    val macroPlausible = isPureFat || !(corrected.protein == 0.0 && corrected.carbs == 0.0 && corrected.fat > 0)
                    val sane = reportedCalories > 0 && (macroCalories <= reportedCalories * 1.3) && macroPlausible
                    Log.d("Repository", "USDA '${selectedFood.description}' [${selectedFood.dataType}]: " +
                        "cal=${corrected.calories}, p=${corrected.protein}, f=${corrected.fat}, c=${corrected.carbs} " +
                        "sane=$sane flourCorrected=$isFlourBased")
                    if (sane && (corrected.calories > 0 || corrected.protein > 0 || corrected.fat > 0 || corrected.carbs > 0)) {
                        item.nutrientsPer100g = corrected
                    }
                }
            } catch (e: Exception) {
                Log.w("Repository", "USDA search failed for '${item.foodNameEn}': ${e.message}")
            }
        }

        // Step 3: ONE batch AI call for ALL items that need nutrients (failed USDA)
        val needAi = aiPending.filter { it.nutrientsPer100g == null }
        if (needAi.isNotEmpty()) {
            Log.d("Repository", "Batch AI for ${needAi.size} items without USDA data")
            try {
                val foodsList = needAi.mapIndexed { i, item ->
                    "${i + 1}. ${item.foodNameEn} (per 100g)"
                }.joinToString("\n")
                val batchPrompt = """
You are a professional nutritionist. Provide nutritional values PER 100 GRAMS for EACH food below.
Return ONLY a JSON array with one object per food, in the SAME ORDER.

Foods:
$foodsList

Return format (array of ${needAi.size} objects):
[
  {
    "calories": <kcal>, "protein": <g>, "fat": <g>,
    "saturated_fat": <g>, "monounsaturated_fat": <g>, "polyunsaturated_fat": <g>, "cholesterol": <mg>,
    "carbs": <g>, "fiber": <g>,
    "vitamin_a": <mcg>, "vitamin_b1": <mg>, "vitamin_b2": <mg>, "vitamin_b3": <mg>,
    "vitamin_b5": <mg>, "vitamin_b6": <mg>, "vitamin_b7": <mcg>, "vitamin_b9": <mcg>,
    "vitamin_b12": <mcg>, "vitamin_c": <mg>, "vitamin_d": <mcg>, "vitamin_e": <mg>,
    "vitamin_k": <mcg>, "calcium": <mg>, "iron": <mg>, "magnesium": <mg>,
    "phosphorus": <mg>, "potassium": <mg>, "sodium": <mg>, "zinc": <mg>,
    "copper": <mg>, "manganese": <mg>, "selenium": <mcg>, "iodine": <mcg>
  },
  ...
]
""".trimIndent()
                val text = callOpenRouterWithRetry(
                    messages = listOf(OpenRouterMessage(role = "user", content = batchPrompt)),
                    models = textModels
                )
                val json = extractJsonContent(text)
                Log.d("Repository", "Batch AI response: $json")
                val parsed = parseBatchNutrientArray(json)
                Log.d("Repository", "Batch AI parsed ${parsed.size} items for ${needAi.size} foods")
                for ((listIdx, item) in needAi.withIndex()) {
                    if (listIdx < parsed.size) {
                        val m = parsed[listIdx]
                        fun v(key: String): Double {
                            val raw = m[key]
                            return when (raw) {
                                is Number -> raw.toDouble()
                                is String -> raw.toDoubleOrNull() ?: 0.0
                                else -> 0.0
                            }
                        }
                        item.nutrientsPer100g = NutrientData(
                            calories = v("calories"), protein = v("protein"),
                            fat = v("fat"),
                            saturatedFat = v("saturated_fat"), monounsaturatedFat = v("monounsaturated_fat"),
                            polyunsaturatedFat = v("polyunsaturated_fat"), cholesterol = v("cholesterol"),
                            carbs = v("carbs"), fiber = v("fiber"),
                            vitaminA = v("vitamin_a"), vitaminB1 = v("vitamin_b1"),
                            vitaminB2 = v("vitamin_b2"), vitaminB3 = v("vitamin_b3"),
                            vitaminB5 = v("vitamin_b5"), vitaminB6 = v("vitamin_b6"),
                            vitaminB7 = v("vitamin_b7"), vitaminB9 = v("vitamin_b9"),
                            vitaminB12 = v("vitamin_b12"), vitaminC = v("vitamin_c"),
                            vitaminD = v("vitamin_d"), vitaminE = v("vitamin_e"),
                            vitaminK = v("vitamin_k"), calcium = v("calcium"),
                            iron = v("iron"), magnesium = v("magnesium"),
                            phosphorus = v("phosphorus"), potassium = v("potassium"),
                            sodium = v("sodium"), zinc = v("zinc"),
                            copper = v("copper"), manganese = v("manganese"),
                            selenium = v("selenium"), iodine = v("iodine")
                        )
                        Log.d("Repository", "Batch AI '${item.foodNameEn}': cal/100g=${item.nutrientsPer100g!!.calories}")
                    }
                }
            } catch (e: Exception) {
                Log.w("Repository", "Batch AI failed: ${e.message}")
            }
        }

        } // end if (uncachedItems.isNotEmpty())

        // Step 4: ONE batch AI call to fill missing micros for NEW items from USDA only
        // Cached items are NOT re-filled — user may have manually edited them
        val usdaItemsWithMissingMicros = aiPending.filter { item ->
            val n = item.nutrientsPer100g ?: return@filter false
            // Only fill if this came from USDA (not from batch AI which already has all fields)
            n.iodine < 0.01
        }
        if (usdaItemsWithMissingMicros.isNotEmpty()) {
                Log.d("Repository", "Batch micro fill for ${usdaItemsWithMissingMicros.size} new USDA items")
                try {
                    val foodsList = usdaItemsWithMissingMicros.mapIndexed { i, item ->
                        "${i + 1}. ${item.foodNameEn}"
                    }.joinToString("\n")
                    val microPrompt = """
For each food below, provide ALL micronutrients PER 100 GRAMS using USDA reference values.
Return ONLY a JSON array with one object per food, in the SAME ORDER.

IMPORTANT: iodine is REQUIRED.
Typical iodine values: seafood 30-160 mcg, dairy 20-50 mcg, egg 24 mcg, buckwheat 3.3 mcg.
Do NOT return 0 for iodine unless the food truly has none (like pure sugar or oil).

Foods:
$foodsList

Return format (array of ${usdaItemsWithMissingMicros.size} objects):
[
  {
    "vitamin_a": <mcg>, "vitamin_b1": <mg>, "vitamin_b2": <mg>, "vitamin_b3": <mg>,
    "vitamin_b5": <mg>, "vitamin_b6": <mg>, "vitamin_b7": <mcg>, "vitamin_b9": <mcg>,
    "vitamin_b12": <mcg>, "vitamin_c": <mg>, "vitamin_d": <mcg>, "vitamin_e": <mg>,
    "vitamin_k": <mcg>, "calcium": <mg>, "iron": <mg>, "magnesium": <mg>,
    "phosphorus": <mg>, "potassium": <mg>, "sodium": <mg>, "zinc": <mg>,
    "copper": <mg>, "manganese": <mg>, "selenium": <mcg>, "iodine": <mcg>
  },
  ...
]
""".trimIndent()
                    val text = callOpenRouterWithRetry(
                        messages = listOf(OpenRouterMessage(role = "user", content = microPrompt)),
                        models = textModels
                    )
                    val json = extractJsonContent(text)
                    Log.d("Repository", "Batch micro response: $json")
                    val parsed = parseBatchNutrientArray(json)
                    for ((listIdx, item) in usdaItemsWithMissingMicros.withIndex()) {
                        if (listIdx < parsed.size) {
                            val m = parsed[listIdx]
                            fun v(key: String): Double {
                                val raw = m[key]
                                return when (raw) {
                                    is Number -> raw.toDouble()
                                    is String -> raw.toDoubleOrNull() ?: 0.0
                                    else -> 0.0
                                }
                            }
                            val n = item.nutrientsPer100g!!
                            item.nutrientsPer100g = n.copy(
                                vitaminA = if (n.vitaminA == 0.0) v("vitamin_a") else n.vitaminA,
                                vitaminB1 = if (n.vitaminB1 == 0.0) v("vitamin_b1") else n.vitaminB1,
                                vitaminB2 = if (n.vitaminB2 == 0.0) v("vitamin_b2") else n.vitaminB2,
                                vitaminB3 = if (n.vitaminB3 == 0.0) v("vitamin_b3") else n.vitaminB3,
                                vitaminB5 = if (n.vitaminB5 == 0.0) v("vitamin_b5") else n.vitaminB5,
                                vitaminB6 = if (n.vitaminB6 == 0.0) v("vitamin_b6") else n.vitaminB6,
                                vitaminB7 = if (n.vitaminB7 == 0.0) v("vitamin_b7") else n.vitaminB7,
                                vitaminB9 = if (n.vitaminB9 == 0.0) v("vitamin_b9") else n.vitaminB9,
                                vitaminB12 = if (n.vitaminB12 == 0.0) v("vitamin_b12") else n.vitaminB12,
                                vitaminC = if (n.vitaminC == 0.0) v("vitamin_c") else n.vitaminC,
                                vitaminD = if (n.vitaminD == 0.0) v("vitamin_d") else n.vitaminD,
                                vitaminE = if (n.vitaminE == 0.0) v("vitamin_e") else n.vitaminE,
                                vitaminK = if (n.vitaminK == 0.0) v("vitamin_k") else n.vitaminK,
                                calcium = if (n.calcium == 0.0) v("calcium") else n.calcium,
                                iron = if (n.iron == 0.0) v("iron") else n.iron,
                                magnesium = if (n.magnesium == 0.0) v("magnesium") else n.magnesium,
                                phosphorus = if (n.phosphorus == 0.0) v("phosphorus") else n.phosphorus,
                                potassium = if (n.potassium == 0.0) v("potassium") else n.potassium,
                                sodium = if (n.sodium == 0.0) v("sodium") else n.sodium,
                                zinc = if (n.zinc == 0.0) v("zinc") else n.zinc,
                                copper = if (n.copper == 0.0) v("copper") else n.copper,
                                manganese = if (n.manganese == 0.0) v("manganese") else n.manganese,
                                selenium = if (n.selenium == 0.0) v("selenium") else n.selenium,
                                iodine = if (n.iodine < 0.01) v("iodine") else n.iodine
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.w("Repository", "Batch micro fill failed: ${e.message}")
                }
            // No need to update cache here — new items will be cached in Step 5 with filled micros
        }

        // Step 5: Cache all newly resolved foods and build final results
        // Also do a last-resort individual AI call for any item that STILL has no nutrients
        for (item in aiPending) {
            if (item.nutrientsPer100g == null) {
                Log.w("Repository", "Fallback individual AI for '${item.foodNameEn}' (all previous steps failed)")
                try {
                    val fallbackPrompt = """
You are a professional nutritionist. Provide nutritional values PER 100 GRAMS for: ${item.foodNameEn}
Return ONLY a JSON object:
{"calories": <kcal>, "protein": <g>, "fat": <g>,
"saturated_fat": <g>, "monounsaturated_fat": <g>, "polyunsaturated_fat": <g>, "cholesterol": <mg>,
"carbs": <g>, "fiber": <g>,
"vitamin_a": <mcg>, "vitamin_c": <mg>, "calcium": <mg>, "iron": <mg>,
"vitamin_b1": <mg>, "vitamin_b2": <mg>, "vitamin_b3": <mg>, "vitamin_b5": <mg>,
"vitamin_b6": <mg>, "vitamin_b7": <mcg>, "vitamin_b9": <mcg>, "vitamin_b12": <mcg>,
"vitamin_d": <mcg>, "vitamin_e": <mg>, "vitamin_k": <mcg>,
"magnesium": <mg>, "phosphorus": <mg>, "potassium": <mg>, "sodium": <mg>,
"zinc": <mg>, "copper": <mg>, "manganese": <mg>, "selenium": <mcg>, "iodine": <mcg>}
""".trimIndent()
                    val text = callOpenRouterWithRetry(
                        messages = listOf(OpenRouterMessage(role = "user", content = fallbackPrompt)),
                        models = textModels
                    )
                    val json = extractJson(text)
                    val map = gson.fromJson(json, Map::class.java) as? Map<String, Any>
                    if (map != null) {
                        fun v(key: String): Double {
                            val raw = map[key]
                            return when (raw) {
                                is Number -> raw.toDouble()
                                is String -> raw.toDoubleOrNull() ?: 0.0
                                else -> 0.0
                            }
                        }
                        item.nutrientsPer100g = NutrientData(
                            calories = v("calories"), protein = v("protein"),
                            fat = v("fat"),
                            saturatedFat = v("saturated_fat"), monounsaturatedFat = v("monounsaturated_fat"),
                            polyunsaturatedFat = v("polyunsaturated_fat"), cholesterol = v("cholesterol"),
                            carbs = v("carbs"), fiber = v("fiber"),
                            vitaminA = v("vitamin_a"), vitaminB1 = v("vitamin_b1"),
                            vitaminB2 = v("vitamin_b2"), vitaminB3 = v("vitamin_b3"),
                            vitaminB5 = v("vitamin_b5"), vitaminB6 = v("vitamin_b6"),
                            vitaminB7 = v("vitamin_b7"), vitaminB9 = v("vitamin_b9"),
                            vitaminB12 = v("vitamin_b12"), vitaminC = v("vitamin_c"),
                            vitaminD = v("vitamin_d"), vitaminE = v("vitamin_e"),
                            vitaminK = v("vitamin_k"), calcium = v("calcium"),
                            iron = v("iron"), magnesium = v("magnesium"),
                            phosphorus = v("phosphorus"), potassium = v("potassium"),
                            sodium = v("sodium"), zinc = v("zinc"),
                            copper = v("copper"), manganese = v("manganese"),
                            selenium = v("selenium"), iodine = v("iodine")
                        )
                        Log.d("Repository", "Fallback AI success for '${item.foodNameEn}': cal=${item.nutrientsPer100g!!.calories}")
                    }
                } catch (e: Exception) {
                    Log.w("Repository", "Fallback AI also failed for '${item.foodNameEn}': ${e.message}")
                }
            }

            // Last resort: use analyzeSingleDish which asks AI for complete nutrients
            if (item.nutrientsPer100g == null || (item.nutrientsPer100g!!.calories == 0.0 && item.nutrientsPer100g!!.protein == 0.0)) {
                Log.w("Repository", "Last resort analyzeSingleDish for '${item.foodNameRu}'")
                try {
                    val dishResult = analyzeSingleDish(item.foodNameRu, 100.0, useCache = false)
                    if (dishResult.nutrients.calories > 0) {
                        item.nutrientsPer100g = dishResult.nutrients  // already per 100g
                        Log.d("Repository", "Last resort success for '${item.foodNameRu}': cal=${dishResult.nutrients.calories}")
                    }
                } catch (e: Exception) {
                    Log.w("Repository", "Last resort also failed for '${item.foodNameRu}': ${e.message}")
                }
            }

            val per100g = item.nutrientsPer100g ?: continue
            // For dairy with explicit %, override macros via AI using GOST reference data
            // (USDA gave us micronutrients for the base product, but macros for the wrong fat grade).
            val correctedPer100g = if (isDairyWithFatPercent(item.foodNameRu)) {
                correctDairyMacrosWithAI(per100g, item.foodNameRu).also { item.nutrientsPer100g = it }
            } else per100g
            try {
                saveToCache(item.foodNameRu, item.foodNameEn, correctedPer100g)
                Log.d("Repository", "Cached '${item.foodNameRu}' / '${item.foodNameEn}'")
            } catch (e: Exception) {
                Log.w("Repository", "Failed to cache '${item.foodNameRu}': ${e.message}")
            }
        }

        val allItems = cachedResults + aiPending
        return allItems.mapNotNull { item ->
            val per100g = item.nutrientsPer100g
            // Skip items where all nutrients are zero (all resolution steps failed)
            if (per100g == null || (per100g.calories == 0.0 && per100g.protein == 0.0 && per100g.fat == 0.0 && per100g.carbs == 0.0)) {
                Log.w("Repository", "Dropping '${item.foodNameRu}' — zero nutrients after all steps")
                null
            } else {
                val factor = item.weight / 100.0
                FoodAnalysisResult(
                    foodName = item.foodNameRu,
                    foodNameEn = item.foodNameEn,
                    weightGrams = item.weight,
                    nutrients = per100g * factor,
                    fromCache = item.fromCache
                )
            }
        }.ifEmpty {
            // If everything was filtered out, throw error instead of silently returning empty list
            throw Exception("Не удалось получить данные о нутриентах для введённых продуктов")
        }
    }

    /**
     * Parse AI response that should contain an array of food identities.
     * Handles: JSON array, single JSON object, or multiple JSON objects concatenated.
     */
    private fun parseIdentityList(text: String): List<FoodIdentity> {
        val cleaned = extractJsonContent(text)
        Log.d("Repository", "Cleaned identify JSON: $cleaned")

        // Try as array first
        if (cleaned.trimStart().startsWith("[")) {
            try {
                val type = object : TypeToken<List<FoodIdentity>>() {}.type
                val reader = JsonReader(java.io.StringReader(cleaned))
                reader.isLenient = true
                return gson.fromJson(reader, type)
            } catch (e: Exception) {
                Log.w("Repository", "Failed to parse as array: ${e.message}")
            }
        }

        // Try as single object
        try {
            val reader = JsonReader(java.io.StringReader(cleaned))
            reader.isLenient = true
            val single: FoodIdentity = gson.fromJson(reader, FoodIdentity::class.java)
            if (single.foodName.isNotBlank() || single.foodNameEn.isNotBlank()) {
                return listOf(single)
            }
        } catch (e: Exception) {
            Log.w("Repository", "Failed to parse as single object: ${e.message}")
        }

        // Try to find multiple JSON objects in text: {...}{...}
        val objects = mutableListOf<FoodIdentity>()
        var searchFrom = 0
        while (searchFrom < cleaned.length) {
            val objStart = cleaned.indexOf('{', searchFrom)
            if (objStart < 0) break
            val objEnd = findMatchingBrace(cleaned, objStart)
            if (objEnd < 0) break
            try {
                val objStr = cleaned.substring(objStart, objEnd + 1)
                val reader = JsonReader(java.io.StringReader(objStr))
                reader.isLenient = true
                val item: FoodIdentity = gson.fromJson(reader, FoodIdentity::class.java)
                if (item.foodName.isNotBlank() || item.foodNameEn.isNotBlank()) {
                    objects.add(item)
                }
            } catch (e: Exception) {
                Log.w("Repository", "Failed to parse object at $objStart: ${e.message}")
            }
            searchFrom = objEnd + 1
        }
        return objects
    }

    data class FoodIdentity(
        @com.google.gson.annotations.SerializedName("food_name") val foodName: String = "",
        @com.google.gson.annotations.SerializedName("food_name_en") val foodNameEn: String = "",
        @com.google.gson.annotations.SerializedName("weight_grams") val weightGrams: Double = 0.0
    )

    /** Find the matching closing brace for an opening brace, respecting nesting and strings. */
    private fun findMatchingBrace(text: String, openPos: Int): Int {
        var depth = 0
        var inString = false
        var escape = false
        for (i in openPos until text.length) {
            val c = text[i]
            if (escape) { escape = false; continue }
            if (c == '\\' && inString) { escape = true; continue }
            if (c == '"') { inString = !inString; continue }
            if (inString) continue
            if (c == '{') depth++
            if (c == '}') { depth--; if (depth == 0) return i }
        }
        return -1
    }

    /** Parse batch AI response into a list of nutrient maps. Handles JSON array, numbered objects, etc. */
    @Suppress("UNCHECKED_CAST")
    private fun parseBatchNutrientArray(json: String): List<Map<String, Any>> {
        // Try as JSON array first
        if (json.trimStart().startsWith("[")) {
            try {
                val type = object : com.google.gson.reflect.TypeToken<List<Map<String, Any>>>() {}.type
                val reader = JsonReader(java.io.StringReader(json))
                reader.isLenient = true
                return gson.fromJson(reader, type)
            } catch (e: Exception) {
                Log.w("Repository", "Batch parse as array failed: ${e.message}")
            }
        }
        // Fallback: extract individual {...} objects from text
        val objects = mutableListOf<Map<String, Any>>()
        var searchFrom = 0
        while (searchFrom < json.length) {
            val objStart = json.indexOf('{', searchFrom)
            if (objStart < 0) break
            val objEnd = findMatchingBrace(json, objStart)
            if (objEnd < 0) break
            try {
                val objStr = json.substring(objStart, objEnd + 1)
                val reader = JsonReader(java.io.StringReader(objStr))
                reader.isLenient = true
                val map: Map<String, Any> = gson.fromJson(reader, Map::class.java) as Map<String, Any>
                if (map.containsKey("calories") || map.containsKey("protein") || map.containsKey("vitamin_a")) {
                    objects.add(map)
                }
            } catch (e: Exception) {
                Log.w("Repository", "Batch parse object at $objStart failed: ${e.message}")
            }
            searchFrom = objEnd + 1
        }
        return objects
    }

    private suspend fun fillMissingMicrosWithAI(nutrients: NutrientData, foodNameEn: String, weight: Double): NutrientData {
        val missing = mutableListOf<String>()
        if (nutrients.vitaminA == 0.0) missing.add("vitamin_a (mcg RAE)")
        if (nutrients.vitaminB1 == 0.0) missing.add("vitamin_b1 (mg)")
        if (nutrients.vitaminB2 == 0.0) missing.add("vitamin_b2 (mg)")
        if (nutrients.vitaminB3 == 0.0) missing.add("vitamin_b3 (mg)")
        if (nutrients.vitaminB5 == 0.0) missing.add("vitamin_b5 (mg)")
        if (nutrients.vitaminB6 == 0.0) missing.add("vitamin_b6 (mg)")
        if (nutrients.vitaminB7 == 0.0) missing.add("vitamin_b7 (mcg)")
        if (nutrients.vitaminB9 == 0.0) missing.add("vitamin_b9 (mcg)")
        if (nutrients.vitaminB12 == 0.0) missing.add("vitamin_b12 (mcg)")
        if (nutrients.vitaminC == 0.0) missing.add("vitamin_c (mg)")
        if (nutrients.vitaminD == 0.0) missing.add("vitamin_d (mcg)")
        if (nutrients.vitaminE == 0.0) missing.add("vitamin_e (mg)")
        if (nutrients.vitaminK == 0.0) missing.add("vitamin_k (mcg)")
        if (nutrients.calcium == 0.0) missing.add("calcium (mg)")
        if (nutrients.iron == 0.0) missing.add("iron (mg)")
        if (nutrients.magnesium == 0.0) missing.add("magnesium (mg)")
        if (nutrients.phosphorus == 0.0) missing.add("phosphorus (mg)")
        if (nutrients.potassium == 0.0) missing.add("potassium (mg)")
        if (nutrients.sodium == 0.0) missing.add("sodium (mg)")
        if (nutrients.zinc == 0.0) missing.add("zinc (mg)")
        if (nutrients.copper == 0.0) missing.add("copper (mg)")
        if (nutrients.manganese == 0.0) missing.add("manganese (mg)")
        if (nutrients.selenium == 0.0) missing.add("selenium (mcg)")
        if (nutrients.iodine == 0.0) missing.add("iodine (mcg)")
        if (missing.isEmpty()) return nutrients
        try {
            val prompt = """
For "$foodNameEn" per 100g, provide ONLY these nutrients using USDA reference values.
IMPORTANT: Values must be PER 100 GRAMS of product, NOT for ${weight.toInt()}g.
${missing.joinToString(", ")}
Return ONLY JSON, e.g.: {"vitamin_a": 45, "calcium": 11}
""".trimIndent()
            val text = callOpenRouterWithRetry(listOf(OpenRouterMessage(role = "user", content = prompt)), textModels)
            val json = extractJson(text)
            val map = gson.fromJson(json, Map::class.java) as? Map<String, Any> ?: return nutrients
            val f = weight / 100.0
            fun v(key: String) = ((map[key] as? Number)?.toDouble() ?: 0.0) * f
            return nutrients.copy(
                vitaminA = if (nutrients.vitaminA == 0.0) v("vitamin_a") else nutrients.vitaminA,
                vitaminB1 = if (nutrients.vitaminB1 == 0.0) v("vitamin_b1") else nutrients.vitaminB1,
                vitaminB2 = if (nutrients.vitaminB2 == 0.0) v("vitamin_b2") else nutrients.vitaminB2,
                vitaminB3 = if (nutrients.vitaminB3 == 0.0) v("vitamin_b3") else nutrients.vitaminB3,
                vitaminB5 = if (nutrients.vitaminB5 == 0.0) v("vitamin_b5") else nutrients.vitaminB5,
                vitaminB6 = if (nutrients.vitaminB6 == 0.0) v("vitamin_b6") else nutrients.vitaminB6,
                vitaminB7 = if (nutrients.vitaminB7 == 0.0) v("vitamin_b7") else nutrients.vitaminB7,
                vitaminB9 = if (nutrients.vitaminB9 == 0.0) v("vitamin_b9") else nutrients.vitaminB9,
                vitaminB12 = if (nutrients.vitaminB12 == 0.0) v("vitamin_b12") else nutrients.vitaminB12,
                vitaminC = if (nutrients.vitaminC == 0.0) v("vitamin_c") else nutrients.vitaminC,
                vitaminD = if (nutrients.vitaminD == 0.0) v("vitamin_d") else nutrients.vitaminD,
                vitaminE = if (nutrients.vitaminE == 0.0) v("vitamin_e") else nutrients.vitaminE,
                vitaminK = if (nutrients.vitaminK == 0.0) v("vitamin_k") else nutrients.vitaminK,
                calcium = if (nutrients.calcium == 0.0) v("calcium") else nutrients.calcium,
                iron = if (nutrients.iron == 0.0) v("iron") else nutrients.iron,
                magnesium = if (nutrients.magnesium == 0.0) v("magnesium") else nutrients.magnesium,
                phosphorus = if (nutrients.phosphorus == 0.0) v("phosphorus") else nutrients.phosphorus,
                potassium = if (nutrients.potassium == 0.0) v("potassium") else nutrients.potassium,
                sodium = if (nutrients.sodium == 0.0) v("sodium") else nutrients.sodium,
                zinc = if (nutrients.zinc == 0.0) v("zinc") else nutrients.zinc,
                copper = if (nutrients.copper == 0.0) v("copper") else nutrients.copper,
                manganese = if (nutrients.manganese == 0.0) v("manganese") else nutrients.manganese,
                selenium = if (nutrients.selenium == 0.0) v("selenium") else nutrients.selenium,
                iodine = if (nutrients.iodine == 0.0) v("iodine") else nutrients.iodine
            )
        } catch (e: Exception) {
            Log.w("Repository", "AI micro fill failed: ${e.message}")
            return nutrients
        }
    }

    suspend fun identifyFoodFromPhoto(imageBytes: ByteArray, usePaidModel: Boolean = false): Pair<String, Double> {
        val base64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
        val prompt = """
Ты профессиональный диетолог. Посмотри на фото еды и определи:
1. Название блюда/продуктов НА РУССКОМ языке (подробно, включая ингредиенты)
2. Оценку общего веса порции в граммах

Примеры названий:
- "салат из помидоров и огурцов с майонезом"
- "гречка с куриной котлетой"
- "борщ со сметаной"

Верни ТОЛЬКО JSON:
{"food_name": "<название на русском>", "weight_grams": <число>}
""".trimIndent()

        val contentParts = listOf(
            OpenRouterContentPart(type = "text", text = prompt),
            OpenRouterContentPart(
                type = "image_url",
                imageUrl = OpenRouterImageUrl(url = "data:image/jpeg;base64,$base64")
            )
        )

        val models = if (usePaidModel) normsModels else visionModels
        val messages = listOf(OpenRouterMessage(role = "user", content = contentParts))
        val text = callOpenRouterWithRetry(messages = messages, models = models)
        val json = extractJsonContent(text)
        Log.d("Repository", "Photo identify response: $json")

        return try {
            val map = gson.fromJson(json, Map::class.java) as Map<String, Any>
            val name = (map["food_name"] as? String) ?: "Блюдо"
            val weight = (map["weight_grams"] as? Number)?.toDouble() ?: 200.0
            Pair(name, weight)
        } catch (e: Exception) {
            Log.w("Repository", "Failed to parse photo identify: ${e.message}")
            Pair("Блюдо", 200.0)
        }
    }

    suspend fun identifyAndAnalyzeFoodFromPhoto(imageBytes: ByteArray): FoodAnalysisResult {
        val base64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
        val prompt = """
You are a professional nutritionist. Look at this food photo and:
1. Identify the dish/food name IN RUSSIAN (detailed, including ingredients)
2. Estimate total portion weight in grams
3. Provide nutritional values PER 100 GRAMS for this complete dish

Return ONLY a JSON object:
{"food_name": "<название на русском>", "food_name_en": "<English translation>", "weight_grams": <number>,
"calories": <kcal>, "protein": <g>, "fat": <g>,
"saturated_fat": <g>, "monounsaturated_fat": <g>, "polyunsaturated_fat": <g>, "cholesterol": <mg>,
"carbs": <g>, "fiber": <g>,
"vitamin_a": <mcg>, "vitamin_b1": <mg>, "vitamin_b2": <mg>, "vitamin_b3": <mg>,
"vitamin_b5": <mg>, "vitamin_b6": <mg>, "vitamin_b7": <mcg>, "vitamin_b9": <mcg>,
"vitamin_b12": <mcg>, "vitamin_c": <mg>, "vitamin_d": <mcg>, "vitamin_e": <mg>,
"vitamin_k": <mcg>, "calcium": <mg>, "iron": <mg>, "magnesium": <mg>,
"phosphorus": <mg>, "potassium": <mg>, "sodium": <mg>, "zinc": <mg>,
"copper": <mg>, "manganese": <mg>, "selenium": <mcg>, "iodine": <mcg>}
""".trimIndent()

        val contentParts = listOf(
            OpenRouterContentPart(type = "text", text = prompt),
            OpenRouterContentPart(
                type = "image_url",
                imageUrl = OpenRouterImageUrl(url = "data:image/jpeg;base64,$base64")
            )
        )

        val messages = listOf(OpenRouterMessage(role = "user", content = contentParts))
        val text = callOpenRouterWithRetry(messages = messages, models = photoModels)
        var json = extractJsonContent(text)
        // Fallback: if extractJsonContent didn't find valid JSON, try extracting from raw text
        if (!json.trimStart().startsWith("{")) {
            val rawStart = text.indexOf('{')
            val rawEnd = text.lastIndexOf('}')
            if (rawStart >= 0 && rawEnd > rawStart) {
                json = text.substring(rawStart, rawEnd + 1)
            }
        }
        Log.d("Repository", "Paid photo full analysis response: $json")

        val reader = JsonReader(java.io.StringReader(json))
        reader.isLenient = true
        val map = gson.fromJson<Map<String, Any>>(reader, Map::class.java) as? Map<String, Any>
            ?: throw Exception("Не удалось распознать блюдо по фото")

        fun v(key: String): Double {
            val raw = map[key]
            return when (raw) {
                is Number -> raw.toDouble()
                is String -> raw.toDoubleOrNull() ?: 0.0
                else -> 0.0
            }
        }

        val foodName = (map["food_name"] as? String) ?: "Блюдо"
        val nameEn = (map["food_name_en"] as? String) ?: foodName
        val weightGrams = (map["weight_grams"] as? Number)?.toDouble() ?: 200.0

        val per100g = NutrientData(
            calories = v("calories"), protein = v("protein"),
            fat = v("fat"),
            saturatedFat = v("saturated_fat"), monounsaturatedFat = v("monounsaturated_fat"),
            polyunsaturatedFat = v("polyunsaturated_fat"), cholesterol = v("cholesterol"),
            carbs = v("carbs"), fiber = v("fiber"),
            vitaminA = v("vitamin_a"), vitaminB1 = v("vitamin_b1"),
            vitaminB2 = v("vitamin_b2"), vitaminB3 = v("vitamin_b3"),
            vitaminB5 = v("vitamin_b5"), vitaminB6 = v("vitamin_b6"),
            vitaminB7 = v("vitamin_b7"), vitaminB9 = v("vitamin_b9"),
            vitaminB12 = v("vitamin_b12"), vitaminC = v("vitamin_c"),
            vitaminD = v("vitamin_d"), vitaminE = v("vitamin_e"),
            vitaminK = v("vitamin_k"), calcium = v("calcium"),
            iron = v("iron"), magnesium = v("magnesium"),
            phosphorus = v("phosphorus"), potassium = v("potassium"),
            sodium = v("sodium"), zinc = v("zinc"),
            copper = v("copper"), manganese = v("manganese"),
            selenium = v("selenium"), iodine = v("iodine")
        )

        return FoodAnalysisResult(
            foodName = foodName,
            foodNameEn = nameEn,
            weightGrams = weightGrams,
            nutrients = per100g,
            fromCache = false
        )
    }

    suspend fun analyzeFoodPhoto(imageBytes: ByteArray): FoodAnalysisResult {
        val base64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
        val prompt = """
Ты профессиональный диетолог. Проанализируй фото еды. Определи продукты и оцени размер порции.
Название еды напиши НА РУССКОМ языке. Оцени общий вес порции в граммах.
Все нутриенты укажи В РАСЧЁТЕ НА 100 ГРАММОВ продукта.

Верни ТОЛЬКО JSON объект с ТОЧНО такой структурой:
{
  "food_name": "<описание еды НА РУССКОМ>",
  "weight_grams": <оценка общего веса порции в граммах>,
  "nutrients": {
    "calories": <на 100г>, "protein": <на 100г>, "fat": <на 100г>, "carbs": <на 100г>, "fiber": <на 100г>,
    "vitamin_a": <mcg на 100г>, "vitamin_b1": <mg на 100г>, "vitamin_b2": <mg на 100г>, "vitamin_b3": <mg на 100г>,
    "vitamin_b5": <mg на 100г>, "vitamin_b6": <mg на 100г>, "vitamin_b7": <mcg на 100г>, "vitamin_b9": <mcg на 100г>,
    "vitamin_b12": <mcg на 100г>, "vitamin_c": <mg на 100г>, "vitamin_d": <mcg на 100г>, "vitamin_e": <mg на 100г>,
    "vitamin_k": <mcg на 100г>, "calcium": <mg на 100г>, "iron": <mg на 100г>, "magnesium": <mg на 100г>,
    "phosphorus": <mg на 100г>, "potassium": <mg на 100г>, "sodium": <mg на 100г>, "zinc": <mg на 100г>,
    "copper": <mg на 100г>, "manganese": <mg на 100г>, "selenium": <mcg на 100г>, "iodine": <mcg на 100г>
  }
}
""".trimIndent()

        val contentParts = listOf(
            OpenRouterContentPart(type = "text", text = prompt),
            OpenRouterContentPart(
                type = "image_url",
                imageUrl = OpenRouterImageUrl(url = "data:image/jpeg;base64,$base64")
            )
        )

        val messages = listOf(OpenRouterMessage(role = "user", content = contentParts))
        val text = callOpenRouterWithRetry(messages = messages, models = visionModels)
        return parseFoodResult(text)
    }

    suspend fun enrichNutrientsWithAI(foodName: String, nutrients: NutrientData, weight: Double): NutrientData {
        return fillMissingMicrosWithAI(nutrients, foodName, weight)
    }

    /**
     * Analyze a single dish as a whole (not splitting into ingredients).
     * Used for photo analysis where user confirmed the dish name and weight.
     * Returns a single FoodAnalysisResult.
     */
    suspend fun analyzeSingleDish(dishName: String, weightGrams: Double, useCache: Boolean = false): FoodAnalysisResult {
        // Try cache first
        if (useCache) {
            val cached = findInCache(dishName)
            if (cached != null) {
                val enrichedNutrients = enrichFatDetailsIfNeeded(cached.second, cached.first.keyEn, cached.first.id)
                val factor = weightGrams / 100.0
                return FoodAnalysisResult(
                    foodName = dishName,
                    foodNameEn = cached.first.keyEn,
                    weightGrams = weightGrams,
                    nutrients = enrichedNutrients * factor,
                    fromCache = true
                )
            }
        }

        // Ask AI for nutrients of this exact dish per 100g
        val prompt = """
You are a professional nutritionist. Provide nutritional values PER 100 GRAMS for this COMPLETE DISH (do NOT split into ingredients):
"$dishName"

Return ONLY a JSON object with these fields:
{"food_name_en": "<English translation>", "calories": <kcal>, "protein": <g>, "fat": <g>,
"saturated_fat": <g>, "monounsaturated_fat": <g>, "polyunsaturated_fat": <g>, "cholesterol": <mg>,
"carbs": <g>, "fiber": <g>,
"vitamin_a": <mcg>, "vitamin_b1": <mg>, "vitamin_b2": <mg>, "vitamin_b3": <mg>,
"vitamin_b5": <mg>, "vitamin_b6": <mg>, "vitamin_b7": <mcg>, "vitamin_b9": <mcg>,
"vitamin_b12": <mcg>, "vitamin_c": <mg>, "vitamin_d": <mcg>, "vitamin_e": <mg>,
"vitamin_k": <mcg>, "calcium": <mg>, "iron": <mg>, "magnesium": <mg>,
"phosphorus": <mg>, "potassium": <mg>, "sodium": <mg>, "zinc": <mg>,
"copper": <mg>, "manganese": <mg>, "selenium": <mcg>, "iodine": <mcg>}
""".trimIndent()

        val text = callOpenRouterWithRetry(
            messages = listOf(OpenRouterMessage(role = "user", content = prompt)),
            models = textModels
        )
        val json = extractJson(text)
        Log.d("Repository", "Single dish AI response: $json")

        val map = gson.fromJson(json, Map::class.java) as? Map<String, Any>
            ?: throw Exception("Не удалось получить нутриенты для блюда")
        fun v(key: String): Double {
            val raw = map[key]
            return when (raw) {
                is Number -> raw.toDouble()
                is String -> raw.toDoubleOrNull() ?: 0.0
                else -> 0.0
            }
        }
        val nameEn = (map["food_name_en"] as? String) ?: dishName

        val per100g = NutrientData(
            calories = v("calories"), protein = v("protein"),
            fat = v("fat"),
            saturatedFat = v("saturated_fat"), monounsaturatedFat = v("monounsaturated_fat"),
            polyunsaturatedFat = v("polyunsaturated_fat"), cholesterol = v("cholesterol"),
            carbs = v("carbs"), fiber = v("fiber"),
            vitaminA = v("vitamin_a"), vitaminB1 = v("vitamin_b1"),
            vitaminB2 = v("vitamin_b2"), vitaminB3 = v("vitamin_b3"),
            vitaminB5 = v("vitamin_b5"), vitaminB6 = v("vitamin_b6"),
            vitaminB7 = v("vitamin_b7"), vitaminB9 = v("vitamin_b9"),
            vitaminB12 = v("vitamin_b12"), vitaminC = v("vitamin_c"),
            vitaminD = v("vitamin_d"), vitaminE = v("vitamin_e"),
            vitaminK = v("vitamin_k"), calcium = v("calcium"),
            iron = v("iron"), magnesium = v("magnesium"),
            phosphorus = v("phosphorus"), potassium = v("potassium"),
            sodium = v("sodium"), zinc = v("zinc"),
            copper = v("copper"), manganese = v("manganese"),
            selenium = v("selenium"), iodine = v("iodine")
        )

        // For dairy with explicit %, override macros via AI using GOST reference data
        val correctedPer100g = if (isDairyWithFatPercent(dishName)) {
            correctDairyMacrosWithAI(per100g, dishName)
        } else per100g

        // Cache it
        try {
            saveToCache(dishName, nameEn, correctedPer100g)
            Log.d("Repository", "Cached single dish '$dishName' / '$nameEn'")
        } catch (e: Exception) {
            Log.w("Repository", "Failed to cache single dish: ${e.message}")
        }

        val factor = weightGrams / 100.0
        return FoodAnalysisResult(
            foodName = dishName,
            foodNameEn = nameEn,
            weightGrams = weightGrams,
            nutrients = correctedPer100g * factor,
            fromCache = false
        )
    }

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
                nutrientsJson = gson.toJson(newNutrients)
            )
        )
    }

    suspend fun deleteFoodEntry(entry: FoodEntryEntity) {
        db.foodEntryDao().delete(entry)
    }

    suspend fun cleanupOldEntries() {
        val cutoff = LocalDate.now().minusDays(14).format(dateFormatter)
        db.foodEntryDao().deleteOlderThan(cutoff)
    }

    // --- Barcode ---
    suspend fun lookupBarcode(barcode: String): Pair<String, NutrientData>? {
        return try {
            val response = offApi.getProduct(barcode)
            val product = response.product ?: return null
            val name = listOf(product.productNameRu, product.productNameUk, product.productNameEn, product.productName, product.brands)
                .firstOrNull { !it.isNullOrBlank() } ?: "Неизвестный продукт"
            val n = product.nutriments ?: return Pair(name, NutrientData())
            // OFF API returns values per 100g in standard units (kcal, g, mg, mcg)
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

    /**
     * Full barcode flow: cache check → OFF API → AI enrich micros → cache enriched result.
     * Returns (name, enrichedNutrientsPer100g) or null if not found.
     */
    suspend fun lookupBarcodeWithCache(barcode: String): Triple<String, NutrientData, Boolean>? {
        // 1. Check cache by barcode
        val cachedByBarcode = findInCache("barcode:$barcode")
        if (cachedByBarcode != null) {
            Log.d("Repository", "Barcode cache HIT for $barcode: ${cachedByBarcode.first.keyEn}")
            val enriched = enrichFatDetailsIfNeeded(cachedByBarcode.second, cachedByBarcode.first.keyEn, cachedByBarcode.first.id)
            return Triple(cachedByBarcode.first.keyEn, enriched, true)
        }

        // 2. OFF API lookup
        val result = lookupBarcode(barcode) ?: return null
        val (name, per100g) = result

        // 3. If OFF returned zero macros, get all nutrients from AI instead
        val basePer100g = if (per100g.calories == 0.0 && per100g.protein == 0.0 && per100g.fat == 0.0 && per100g.carbs == 0.0) {
            Log.d("Repository", "Barcode $barcode: OFF returned zero macros, falling back to AI for all nutrients")
            try {
                val aiResult = analyzeSingleDish(name, 100.0, useCache = false)
                aiResult.nutrients  // already per 100g since weight=100
            } catch (e: Exception) {
                Log.w("Repository", "AI fallback for barcode failed: ${e.message}")
                per100g
            }
        } else {
            per100g
        }

        // 4. AI enrich missing micros (per 100g)
        val enrichedPer100g = try {
            enrichNutrientsWithAI(name, basePer100g, 100.0)
        } catch (e: Exception) {
            Log.w("Repository", "AI enrichment failed for barcode $barcode: ${e.message}")
            basePer100g
        }

        // 5. Enrich fat details if mono/poly are missing
        val fatEnrichedPer100g = try {
            enrichFatDetailsIfNeeded(enrichedPer100g, name, null)
        } catch (e: Exception) {
            Log.w("Repository", "Fat enrichment failed for barcode $barcode: ${e.message}")
            enrichedPer100g
        }

        // 6. Cache the enriched result
        try {
            saveToCache(name, name, fatEnrichedPer100g)
            saveToCache("barcode:$barcode", name, fatEnrichedPer100g)
            Log.d("Repository", "Cached enriched barcode $barcode as '$name'")
        } catch (e: Exception) {
            Log.w("Repository", "Failed to cache barcode: ${e.message}")
        }

        return Triple(name, fatEnrichedPer100g, false)
    }

    // --- Supplement (BAD) barcode lookup ---

    /**
     * Result of supplement barcode lookup.
     * Contains per-serving nutrients and serving info for the UI dialog.
     */
    data class SupplementResult(
        val name: String,
        val nutrientsPerServing: NutrientData,
        val servingSize: String,   // e.g. "1 capsule", "2 tablets"
        val fromCache: Boolean
    )

    /**
     * Checks if an OFF product is a dietary supplement by its categories.
     */
    private fun isSupplementProduct(product: OFFProduct): Boolean {
        val tags = product.categoriesTags ?: return false
        return tags.any { tag ->
            tag.contains("supplement", ignoreCase = true) ||
            tag.contains("complément", ignoreCase = true) ||
            tag.contains("витамин", ignoreCase = true) ||
            tag.contains("dietary-supplement", ignoreCase = true)
        }
    }

    /**
     * Lookup a supplement by barcode. Returns per-serving nutrients.
     * Flow: cache → OFF API (for name & serving size) → AI (for accurate per-serving nutrients) → cache.
     * OFF nutrient data for supplements is unreliable (unit mismatches), so we use AI
     * to determine per-serving values based on product name and serving info.
     */
    suspend fun lookupSupplementBarcode(barcode: String): SupplementResult? {
        // 1. Check cache by barcode (supplement-prefixed key)
        val cacheKey = "supplement:$barcode"
        val cachedByBarcode = findInCache(cacheKey)
        if (cachedByBarcode != null) {
            Log.d("Repository", "Supplement cache HIT for $barcode: ${cachedByBarcode.first.keyEn}")
            val servingSize = cachedByBarcode.first.keyOriginal
                .removePrefix("supplement:$barcode:")
                .ifBlank { "1 порция" }
            return SupplementResult(
                name = cachedByBarcode.first.keyEn,
                nutrientsPerServing = cachedByBarcode.second,
                servingSize = servingSize,
                fromCache = true
            )
        }

        // 2. OFF API lookup — only for product name and serving size
        return try {
            val response = offApi.getProduct(barcode)
            val product = response.product ?: return null
            val name = listOf(product.productNameRu, product.productNameUk, product.productNameEn, product.productName, product.brands)
                .firstOrNull { !it.isNullOrBlank() } ?: "Dietary supplement (barcode: $barcode)"
            val servingSize = product.servingSize ?: "1 порция"

            // 3. AI determines accurate per-serving nutrients
            //    OFF data for supplements has unreliable units (stores everything in grams),
            //    so we ask AI which knows standard supplement dosages.
            //    We pass both name and barcode so AI has maximum context.
            val perServing = getSupplementNutrientsFromAI(name, servingSize, barcode)

            // 4. Cache the result
            try {
                saveToCache("supplement:$barcode:$servingSize", name, perServing)
                Log.d("Repository", "Cached supplement barcode $barcode as '$name', serving=$servingSize")
            } catch (e: Exception) {
                Log.w("Repository", "Failed to cache supplement: ${e.message}")
            }

            SupplementResult(
                name = name,
                nutrientsPerServing = perServing,
                servingSize = servingSize,
                fromCache = false
            )
        } catch (e: Exception) {
            Log.e("Repository", "Supplement barcode lookup failed", e)
            null
        }
    }

    /**
     * Ask AI for accurate per-serving nutrients of a dietary supplement.
     * AI knows standard supplement dosages and uses correct units (mcg, mg).
     */
    private suspend fun getSupplementNutrientsFromAI(productName: String, servingSize: String, barcode: String = ""): NutrientData {
        val barcodeHint = if (barcode.isNotBlank()) "\nBarcode: $barcode" else ""
        val prompt = """
You are a nutrition database expert. For the dietary supplement described below, provide the nutrients PER ONE SERVING.
If the product name is generic or unclear, use the barcode to identify the exact product.

Product: $productName$barcodeHint
Serving size: $servingSize

IMPORTANT UNITS — use these EXACT units:
- calories: kcal
- protein, fat, saturated_fat, monounsaturated_fat, polyunsaturated_fat, carbs, fiber: grams (g)
- cholesterol: mg
- vitamin_a: mcg RAE
- vitamin_b1, vitamin_b2, vitamin_b3, vitamin_b5, vitamin_b6: mg
- vitamin_b7: mcg
- vitamin_b9: mcg DFE
- vitamin_b12: mcg
- vitamin_c: mg
- vitamin_d: mcg (NOT IU! 1 IU = 0.025 mcg)
- vitamin_e: mg
- vitamin_k: mcg (NOT mg! NOT g!)
- calcium, iron, magnesium, phosphorus, potassium, sodium, zinc: mg
- copper: mg
- manganese: mg
- selenium, iodine: mcg

Return ONLY a JSON object with numeric values PER SERVING. If a nutrient is not present in this supplement, use 0.
Example for "Vitamin K2 100mcg": {"vitamin_k": 100, "calories": 0, ...}

JSON:
""".trimIndent()

        val text = callOpenRouterWithRetry(
            messages = listOf(OpenRouterMessage(role = "user", content = prompt)),
            models = textModels
        )
        val json = extractJson(text)
        Log.d("Repository", "Supplement AI response: $json")

        return try {
            val map = gson.fromJson(json, Map::class.java) as? Map<String, Any> ?: return NutrientData()
            fun v(key: String) = (map[key] as? Number)?.toDouble() ?: 0.0
            NutrientData(
                calories = v("calories"),
                protein = v("protein"),
                fat = v("fat"),
                saturatedFat = v("saturated_fat"),
                monounsaturatedFat = v("monounsaturated_fat"),
                polyunsaturatedFat = v("polyunsaturated_fat"),
                cholesterol = v("cholesterol"),
                carbs = v("carbs"),
                fiber = v("fiber"),
                vitaminA = v("vitamin_a"),
                vitaminB1 = v("vitamin_b1"),
                vitaminB2 = v("vitamin_b2"),
                vitaminB3 = v("vitamin_b3"),
                vitaminB5 = v("vitamin_b5"),
                vitaminB6 = v("vitamin_b6"),
                vitaminB7 = v("vitamin_b7"),
                vitaminB9 = v("vitamin_b9"),
                vitaminB12 = v("vitamin_b12"),
                vitaminC = v("vitamin_c"),
                vitaminD = v("vitamin_d"),
                vitaminE = v("vitamin_e"),
                vitaminK = v("vitamin_k"),
                calcium = v("calcium"),
                iron = v("iron"),
                magnesium = v("magnesium"),
                phosphorus = v("phosphorus"),
                potassium = v("potassium"),
                sodium = v("sodium"),
                zinc = v("zinc"),
                copper = v("copper"),
                manganese = v("manganese"),
                selenium = v("selenium"),
                iodine = v("iodine")
            )
        } catch (e: Exception) {
            Log.w("Repository", "Failed to parse supplement AI response: ${e.message}")
            NutrientData()
        }
    }

    // --- OpenRouter helpers with retry ---
    private suspend fun callOpenRouterWithRetry(
        messages: List<OpenRouterMessage>,
        models: List<String>
    ): String {
        val model = models.first()
        var lastError: Exception? = null
        val maxRetries = 5

        for (attempt in 1..maxRetries) {
            try {
                Log.d("Repository", "Trying model: $model (attempt $attempt/$maxRetries)")
                val request = OpenRouterRequest(
                    model = model,
                    messages = messages
                )
                val httpResponse = openRouterApi.chatCompletion("Bearer $apiKey", request)
                val httpCode = httpResponse.code()

                if (!httpResponse.isSuccessful) {
                    val errorBody = httpResponse.errorBody()?.string() ?: "No error body"
                    Log.w("Repository", "Model $model HTTP $httpCode: $errorBody")
                    lastError = Exception("HTTP $httpCode: $errorBody")
                    if (attempt < maxRetries) {
                        kotlinx.coroutines.delay(1000)
                        continue
                    }
                    throw lastError!!
                }

                val response = httpResponse.body()
                if (response == null) {
                    Log.w("Repository", "Model $model: null response body")
                    lastError = Exception("Пустой ответ от сервера")
                    if (attempt < maxRetries) {
                        kotlinx.coroutines.delay(1000)
                        continue
                    }
                    throw lastError!!
                }

                if (response.error != null) {
                    Log.w("Repository", "Model $model error ${response.error.code}: ${response.error.message}")
                    lastError = Exception("API: ${response.error.message}")
                    if (attempt < maxRetries) {
                        kotlinx.coroutines.delay(1000)
                        continue
                    }
                    throw lastError!!
                }

                val choice = response.choices?.firstOrNull()

                // Handle choice-level errors (e.g. provider timeout with HTTP 200)
                if (choice?.error != null) {
                    Log.w("Repository", "Model $model choice error ${choice.error.code}: ${choice.error.message}")
                    lastError = Exception("Provider: ${choice.error.message}")
                    if (attempt < maxRetries) {
                        kotlinx.coroutines.delay(2000)
                        continue
                    }
                    throw lastError!!
                }

                val msg = choice?.message
                val text = msg?.content
                if (text.isNullOrBlank()) {
                    val reasoning = msg?.reasoning
                    if (!reasoning.isNullOrBlank()) {
                        Log.w("Repository", "Model $model: content empty, only reasoning (${reasoning.length} chars).")
                    } else {
                        Log.w("Repository", "Model $model returned empty content")
                    }
                    lastError = Exception("Пустой ответ от модели")
                    if (attempt < maxRetries) {
                        kotlinx.coroutines.delay(1000)
                        continue
                    }
                    throw lastError!!
                }
                // Validate that response contains JSON structure
                if (!text.contains('{') && !text.contains('[')) {
                    Log.w("Repository", "Model $model: response has no JSON (${text.take(100)})")
                    lastError = Exception("Ответ модели не содержит JSON")
                    if (attempt < maxRetries) {
                        kotlinx.coroutines.delay(1000)
                        continue
                    }
                    throw lastError!!
                }
                Log.d("Repository", "Success with model: $model (attempt $attempt)")
                return text
            } catch (e: Exception) {
                lastError = e
                Log.w("Repository", "Attempt $attempt/$maxRetries failed: ${e.message}")
                if (attempt < maxRetries) {
                    kotlinx.coroutines.delay(1000)
                    continue
                }
            }
        }
        throw lastError ?: Exception("Сервер недоступен после $maxRetries попыток. Проверьте интернет.")
    }

    private suspend fun callOpenRouterText(prompt: String): NutrientData {
        val text = callOpenRouterWithRetry(
            messages = listOf(OpenRouterMessage(role = "user", content = prompt)),
            models = textModels
        )
        return parseNutrientData(text)
    }

    private suspend fun callOpenRouterForFood(prompt: String): FoodAnalysisResult {
        val text = callOpenRouterWithRetry(
            messages = listOf(OpenRouterMessage(role = "user", content = prompt)),
            models = textModels
        )
        return parseFoodResult(text)
    }

    private fun parseNutrientData(text: String): NutrientData {
        val json = extractJson(text)
        return lenientFromJson(json, NutrientData::class.java)
    }

    private fun parseFoodResult(text: String): FoodAnalysisResult {
        val json = extractJson(text)
        return lenientFromJson(json, FoodAnalysisResult::class.java)
    }

    private fun <T> lenientFromJson(json: String, clazz: Class<T>): T {
        val reader = JsonReader(java.io.StringReader(json))
        reader.isLenient = true
        return gson.fromJson(reader, clazz)
    }

    /** Extract JSON content (array or object) from AI response, stripping markdown fences and surrounding text. */
    private fun extractJsonContent(text: String): String {
        val trimmed = text.trim()
        // Strip markdown code fences
        val fencePattern = Regex("```(?:json)?\\s*([\\s\\S]*?)\\s*```")
        val fenceMatch = fencePattern.find(trimmed)
        val inner = fenceMatch?.groupValues?.get(1)?.trim() ?: trimmed

        // Find first [ or { and last ] or }
        val arrStart = inner.indexOf('[')
        val objStart = inner.indexOf('{')
        val start = when {
            arrStart >= 0 && objStart >= 0 -> minOf(arrStart, objStart)
            arrStart >= 0 -> arrStart
            objStart >= 0 -> objStart
            else -> return inner
        }
        val arrEnd = inner.lastIndexOf(']')
        val objEnd = inner.lastIndexOf('}')
        val end = maxOf(arrEnd, objEnd)
        if (end <= start) return inner

        var json = inner.substring(start, end + 1)
        val trailingCommaObj = Regex(",[ \\t\\r\\n]*\\}")
        val trailingCommaArr = Regex(",[ \\t\\r\\n]*\\]")
        val lineComment = Regex("//[^\\n]*")
        json = trailingCommaObj.replace(json, "}")
        json = trailingCommaArr.replace(json, "]")
        json = lineComment.replace(json, "")
        return json
    }

    private fun extractJson(text: String): String {
        val trimmed = text.trim()
        // Find first { and last }
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start < 0 || end <= start) return trimmed

        var json = trimmed.substring(start, end + 1)
        // Remove trailing commas before } or ] using simple patterns
        val trailingCommaObj = Regex(",[ \\t\\r\\n]*\\}")
        val trailingCommaArr = Regex(",[ \\t\\r\\n]*\\]")
        val lineComment = Regex("//[^\\n]*")
        json = trailingCommaObj.replace(json, "}")
        json = trailingCommaArr.replace(json, "]")
        json = lineComment.replace(json, "")
        return json
    }

    fun parseNutrients(json: String): NutrientData {
        return try {
            lenientFromJson(json, NutrientData::class.java)
        } catch (e: Exception) {
            NutrientData()
        }
    }
}
