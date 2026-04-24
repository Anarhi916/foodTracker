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

    // Fallback text models (tried in order if rate-limited)
    // Sorted by: reliability, speed, JSON quality from real testing
    private val textModels = listOf(
        "openai/gpt-oss-120b:free",                      // fast, reliable, good JSON
        "nvidia/nemotron-3-super-120b-a12b:free",        // reliable, thinking model
        "arcee-ai/trinity-large-preview:free",           // fast, non-thinking, good JSON
        "nvidia/nemotron-3-nano-30b-a3b:free",           // fast thinking model
        "openai/gpt-oss-20b:free",                       // reliable backup
        "google/gemma-3-12b-it:free",                    // good but sometimes 429
        "google/gemma-3-27b-it:free",                    // good but sometimes 429
        "google/gemma-4-31b-it:free",                    // good but often 429
        "z-ai/glm-4.5-air:free",                         // slower but works
        "minimax/minimax-m2.5:free"                      // very slow (>60s), last resort
    )

    // Fallback vision models (support image_url input)
    private val visionModels = listOf(
        "google/gemma-4-31b-it:free",
        "google/gemma-3-27b-it:free",
        "google/gemma-3-12b-it:free",
        "google/gemma-3-4b-it:free",
        "nvidia/nemotron-nano-12b-v2-vl:free"            // thinking model, needs more tokens
    )

    // Paid model for initial daily norms calculation only.
    private val normsModels = listOf(
        "google/gemini-2.5-pro-preview"
    )

    fun todayDate(): String = LocalDate.now().format(dateFormatter)

    // --- User Profile ---
    fun getUserProfile(): Flow<UserProfileEntity?> = db.userProfileDao().getProfile()

    suspend fun saveUserProfile(gender: String, age: Int, weight: Double, height: Double, goals: String) {
        db.userProfileDao().insert(
            UserProfileEntity(gender = gender, age = age, weightKg = weight, heightCm = height, goalsText = goals)
        )
    }

    // --- Daily Norms ---
    fun getDailyNorms(): Flow<DailyNormsEntity?> = db.dailyNormsDao().getNorms()

    suspend fun calculateAndSaveNorms(gender: String, age: Int, weight: Double, height: Double, goals: String): NutrientData {
        val prompt = """
You are a professional nutrition expert. Based on the following user data, calculate the recommended DAILY nutritional intake to achieve their goals.

User data:
- Gender: $gender
- Age: $age years
- Weight: $weight kg
- Height: $height cm
- Goals and activity level: $goals

Calculate daily norms and return ONLY a JSON object with this EXACT structure (all numbers, no text):
{
  "calories": <number>,
  "protein": <grams>,
  "fat": <grams>,
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
  "copper": <mg>,
  "manganese": <mg>,
  "selenium": <mcg>,
  "iodine": <mcg>,
  "chromium": <mcg>
}
""".trimIndent()

        val normsText = callOpenRouterWithRetry(
            messages = listOf(OpenRouterMessage(role = "user", content = prompt)),
            models = normsModels
        )
        val nutrients = parseNutrientData(normsText)
        db.dailyNormsDao().deleteAll()
        db.dailyNormsDao().insert(DailyNormsEntity(nutrientsJson = gson.toJson(nutrients)))
        return nutrients
    }

    // --- Food Entries ---
    fun getTodayEntries(): Flow<List<FoodEntryEntity>> =
        db.foodEntryDao().getEntriesForDate(todayDate())

    fun getEntriesForDate(date: String): Flow<List<FoodEntryEntity>> =
        db.foodEntryDao().getEntriesForDate(date)

    suspend fun getEntriesForDateSync(date: String): List<FoodEntryEntity> =
        db.foodEntryDao().getEntriesForDateSync(date)

    fun getRecentDates(): Flow<List<String>> = db.foodEntryDao().getRecentDates()

    // --- Food Cache ---
    fun getAllCachedFoods() = db.foodCacheDao().getAll()

    suspend fun deleteCachedFood(entry: FoodCacheEntity) = db.foodCacheDao().delete(entry)

    suspend fun deleteAllCachedFoods() = db.foodCacheDao().deleteAll()

    suspend fun updateCachedFood(id: Long, nutrients: NutrientData) {
        db.foodCacheDao().updateNutrients(id, gson.toJson(nutrients))
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
        val normalizedEn = normalizeKey(keyEn)
        // Only one row per product: if same keyEn already exists, don't duplicate
        val existing = db.foodCacheDao().findByKeyEn(normalizedEn)
        if (existing != null) return
        val normalized = normalizeKey(keyOriginal)
        db.foodCacheDao().insert(
            FoodCacheEntity(
                keyOriginal = keyOriginal,
                keyNormalized = normalized,
                keyEn = keyEn,
                nutrientsPer100gJson = gson.toJson(nutrientsPer100g)
            )
        )
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

    suspend fun analyzeFoodText(foodDescription: String, useCache: Boolean = true): List<FoodAnalysisResult> {
        // Step 0: Try local parse + cache lookup first
        val localParsed = parseLocalFoodInput(foodDescription)
        Log.d("Repository", "Local parse: ${localParsed.size} items: $localParsed")

        data class PendingFood(
            val foodNameRu: String,
            val foodNameEn: String,
            val weight: Double,
            var nutrientsPer100g: NutrientData? = null,
            var fromCache: Boolean = false
        )

        val cachedResults = mutableListOf<PendingFood>()
        val uncachedItems = mutableListOf<Pair<String, Double>>()

        for ((name, weight) in localParsed) {
            val cached = if (useCache) findInCache(name) else null
            if (cached != null) {
                Log.d("Repository", "Cache HIT for '$name'")
                cachedResults.add(PendingFood(
                    foodNameRu = name,
                    foodNameEn = cached.first.keyEn,
                    weight = if (weight > 0) weight else 100.0,
                    nutrientsPer100g = cached.second,
                    fromCache = true
                ))
            } else {
                uncachedItems.add(name to weight)
            }
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
        val descriptionForAi = if (uncachedItems.isNotEmpty()) {
            uncachedItems.joinToString(", ") { (name, w) ->
                if (w > 0) "$name ${w.toInt()}г" else name
            }
        } else foodDescription

        val identifyPrompt = """
Определи ВСЕ продукты и их вес из описания. Описание может быть на русском, украинском или другом языке.
Если указано количество штук — рассчитай общий вес. Если вес не указан — оцени типичную порцию.

ВАЖНО:
- food_name — сохрани название НА ЯЗЫКЕ ВВОДА (не переводи на другой язык)
- food_name_en — ТОЧНЫЙ перевод на английский для поиска в USDA базе данных

Примеры правильного перевода:
- "куриная отбивная" / "куряча відбивна" → "chicken breast cutlet"
- "гречневая каша" / "гречана каша" → "buckwheat porridge"  
- "творог нежирный" / "сир кисломолочний нежирний" → "low-fat cottage cheese"
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
        val aiPending = mutableListOf<PendingFood>()
        for (id in identities) {
            val nameRu = id.foodName.ifBlank { descriptionForAi }
            val nameEn = id.foodNameEn.ifBlank { id.foodName }
            val weight = if (id.weightGrams > 0) id.weightGrams else 100.0

            // Try cache by English key
            val cachedByEn = if (useCache) findInCache(nameEn) else null
            if (cachedByEn != null) {
                Log.d("Repository", "Cache HIT by EN key '$nameEn' for '$nameRu'")
                // Also save under original name for next time
                try { saveToCache(nameRu, nameEn, cachedByEn.second) } catch (_: Exception) {}
                cachedResults.add(PendingFood(
                    foodNameRu = nameRu, foodNameEn = nameEn,
                    weight = weight, nutrientsPer100g = cachedByEn.second, fromCache = true
                ))
            } else {
                aiPending.add(PendingFood(
                    foodNameRu = nameRu, foodNameEn = nameEn, weight = weight
                ))
            }
        }

        // Step 2: USDA lookup for uncached foods
        for ((idx, item) in aiPending.withIndex()) {
            Log.d("Repository", "Processing: '${item.foodNameRu}' / '${item.foodNameEn}', weight=${item.weight}g")
            try {
                val usdaResult = usdaApi.searchFoods(query = item.foodNameEn)
                // Relevance filter: USDA description must contain the LONGEST query word (main food)
                // This prevents "lightly salted salmon" matching "Almonds, lightly salted"
                val queryWords = item.foodNameEn.lowercase().split("\\s+".toRegex())
                    .filter { it.length >= 3 }
                val mainWord = queryWords.maxByOrNull { it.length } // longest word is most specific
                val secondaryWords = queryWords.filter { it != mainWord }
                fun isRelevant(description: String?): Boolean {
                    if (description == null || mainWord == null) return false
                    val desc = description.lowercase()
                    // Main word MUST be present
                    if (!desc.contains(mainWord)) return false
                    // If there are secondary words, at least one should match too (if 3+ query words)
                    if (queryWords.size >= 3 && secondaryWords.isNotEmpty()) {
                        return secondaryWords.any { desc.contains(it) }
                    }
                    return true
                }
                // Pick best USDA result: prefer Survey/SR Legacy over Branded, require calories > 0, must be relevant
                // Score by: data type priority + number of matching query words + prefer "NFS"/"raw" over recipes
                val foodsWithCalories = usdaResult.foods?.filter { f ->
                    f.foodNutrients?.any { it.nutrientId == UsdaFoodNutrient.ENERGY && (it.value ?: 0.0) > 0 } == true
                        && isRelevant(f.description)
                } ?: emptyList()
                fun scoreFood(f: com.nutrition.tracker.data.api.UsdaFood): Int {
                    val desc = (f.description ?: "").lowercase()
                    // Data type priority (higher = better)
                    val typePriority = when (f.dataType) {
                        "Survey (FNDDS)" -> 200
                        "SR Legacy" -> 150
                        "Branded" -> 50
                        else -> 100
                    }
                    // Word match count bonus (each matching word = +30)
                    val wordMatchBonus = queryWords.count { desc.contains(it) } * 30
                    // Prefer generic/plain entries over recipes/mixed dishes
                    val plainBonus = when {
                        desc.contains(", nfs") -> 25       // "Not Further Specified" = generic average
                        desc.contains("raw") -> 20         // raw = plain product
                        desc.startsWith("fish,") || desc.startsWith("fish ") -> 15 // USDA standard fish entry
                        desc.contains("salted") || desc.contains("smoked") || desc.contains("canned") -> 10
                        else -> 0
                    }
                    // Penalize compound dish names (short descriptions with no comma = likely a recipe name)
                    val recipePenalty = if (f.dataType == "Survey (FNDDS)" && !desc.contains(",") && desc.split(" ").size <= 3) -40 else 0
                    return typePriority + wordMatchBonus + plainBonus + recipePenalty
                }
                val food = foodsWithCalories.maxByOrNull { scoreFood(it) }
                Log.d("Repository", "USDA selected: '${food?.description}' (${food?.dataType}), score=${food?.let { scoreFood(it) }}, from ${foodsWithCalories.size} candidates")
                if (food?.foodNutrients != null) {
                    val nMap = mutableMapOf<Int, Double>()
                    for (fn in food.foodNutrients) {
                        if (fn.nutrientId != null && fn.value != null) nMap[fn.nutrientId] = fn.value
                    }
                    val N = UsdaFoodNutrient
                    // Store per 100g for caching
                    val per100g = NutrientData(
                        calories = nMap[N.ENERGY] ?: 0.0, protein = nMap[N.PROTEIN] ?: 0.0,
                        fat = nMap[N.FAT] ?: 0.0, carbs = nMap[N.CARBS] ?: 0.0,
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
                        iodine = nMap[N.IODINE] ?: 0.0, chromium = nMap[N.CHROMIUM] ?: 0.0
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
                    val foodDesc = (food.description ?: "").lowercase() + " " + item.foodNameEn.lowercase()
                    val isFlourBased = flourKeywords.any { foodDesc.contains(it) }
                    val corrected = if (isFlourBased) {
                        Log.d("Repository", "Applying enrichment correction for flour-based: ${food.description}")
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
                    val sane = reportedCalories > 0 && (macroCalories <= reportedCalories * 1.3)
                    Log.d("Repository", "USDA '${food.description}' [${food.dataType}]: " +
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
    "calories": <kcal>, "protein": <g>, "fat": <g>, "carbs": <g>, "fiber": <g>,
    "vitamin_a": <mcg>, "vitamin_b1": <mg>, "vitamin_b2": <mg>, "vitamin_b3": <mg>,
    "vitamin_b5": <mg>, "vitamin_b6": <mg>, "vitamin_b7": <mcg>, "vitamin_b9": <mcg>,
    "vitamin_b12": <mcg>, "vitamin_c": <mg>, "vitamin_d": <mcg>, "vitamin_e": <mg>,
    "vitamin_k": <mcg>, "calcium": <mg>, "iron": <mg>, "magnesium": <mg>,
    "phosphorus": <mg>, "potassium": <mg>, "sodium": <mg>, "zinc": <mg>,
    "copper": <mg>, "manganese": <mg>, "selenium": <mcg>, "iodine": <mcg>, "chromium": <mcg>
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
                        fun v(key: String) = (m[key] as? Number)?.toDouble() ?: 0.0
                        item.nutrientsPer100g = NutrientData(
                            calories = v("calories"), protein = v("protein"),
                            fat = v("fat"), carbs = v("carbs"), fiber = v("fiber"),
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
                            selenium = v("selenium"), iodine = v("iodine"),
                            chromium = v("chromium")
                        )
                        Log.d("Repository", "Batch AI '${item.foodNameEn}': cal/100g=${item.nutrientsPer100g!!.calories}")
                    }
                }
            } catch (e: Exception) {
                Log.w("Repository", "Batch AI failed: ${e.message}")
            }
        }

        // Step 4: ONE batch AI call to fill missing micros for ALL items that have macros
        val needMicros = aiPending.filter { it.nutrientsPer100g != null }
        if (needMicros.isNotEmpty()) {
            val itemsWithMissing = needMicros.filter { item ->
                val n = item.nutrientsPer100g!!
                n.vitaminA == 0.0 || n.vitaminC == 0.0 || n.calcium == 0.0 || n.iron == 0.0
            }
            if (itemsWithMissing.isNotEmpty()) {
                Log.d("Repository", "Batch micro fill for ${itemsWithMissing.size} items")
                try {
                    val foodsList = itemsWithMissing.mapIndexed { i, item ->
                        "${i + 1}. ${item.foodNameEn}"
                    }.joinToString("\n")
                    val microPrompt = """
For each food below, provide ALL micronutrients PER 100 GRAMS using USDA reference values.
Return ONLY a JSON array with one object per food, in the SAME ORDER.

Foods:
$foodsList

Return format (array of ${itemsWithMissing.size} objects):
[
  {
    "vitamin_a": <mcg>, "vitamin_b1": <mg>, "vitamin_b2": <mg>, "vitamin_b3": <mg>,
    "vitamin_b5": <mg>, "vitamin_b6": <mg>, "vitamin_b7": <mcg>, "vitamin_b9": <mcg>,
    "vitamin_b12": <mcg>, "vitamin_c": <mg>, "vitamin_d": <mcg>, "vitamin_e": <mg>,
    "vitamin_k": <mcg>, "calcium": <mg>, "iron": <mg>, "magnesium": <mg>,
    "phosphorus": <mg>, "potassium": <mg>, "sodium": <mg>, "zinc": <mg>,
    "copper": <mg>, "manganese": <mg>, "selenium": <mcg>, "iodine": <mcg>, "chromium": <mcg>
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
                    for ((listIdx, item) in itemsWithMissing.withIndex()) {
                        if (listIdx < parsed.size) {
                            val m = parsed[listIdx]
                            fun v(key: String) = (m[key] as? Number)?.toDouble() ?: 0.0
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
                                iodine = if (n.iodine == 0.0) v("iodine") else n.iodine,
                                chromium = if (n.chromium == 0.0) v("chromium") else n.chromium
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.w("Repository", "Batch micro fill failed: ${e.message}")
                }
            }
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
{"calories": <kcal>, "protein": <g>, "fat": <g>, "carbs": <g>, "fiber": <g>,
"vitamin_a": <mcg>, "vitamin_c": <mg>, "calcium": <mg>, "iron": <mg>,
"vitamin_b1": <mg>, "vitamin_b2": <mg>, "vitamin_b3": <mg>, "vitamin_b5": <mg>,
"vitamin_b6": <mg>, "vitamin_b7": <mcg>, "vitamin_b9": <mcg>, "vitamin_b12": <mcg>,
"vitamin_d": <mcg>, "vitamin_e": <mg>, "vitamin_k": <mcg>,
"magnesium": <mg>, "phosphorus": <mg>, "potassium": <mg>, "sodium": <mg>,
"zinc": <mg>, "copper": <mg>, "manganese": <mg>, "selenium": <mcg>, "iodine": <mcg>, "chromium": <mcg>}
""".trimIndent()
                    val text = callOpenRouterWithRetry(
                        messages = listOf(OpenRouterMessage(role = "user", content = fallbackPrompt)),
                        models = textModels
                    )
                    val json = extractJson(text)
                    val map = gson.fromJson(json, Map::class.java) as? Map<String, Any>
                    if (map != null) {
                        fun v(key: String) = (map[key] as? Number)?.toDouble() ?: 0.0
                        item.nutrientsPer100g = NutrientData(
                            calories = v("calories"), protein = v("protein"),
                            fat = v("fat"), carbs = v("carbs"), fiber = v("fiber"),
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
                            selenium = v("selenium"), iodine = v("iodine"),
                            chromium = v("chromium")
                        )
                        Log.d("Repository", "Fallback AI success for '${item.foodNameEn}': cal=${item.nutrientsPer100g!!.calories}")
                    }
                } catch (e: Exception) {
                    Log.w("Repository", "Fallback AI also failed for '${item.foodNameEn}': ${e.message}")
                }
            }
            val per100g = item.nutrientsPer100g ?: continue
            try {
                saveToCache(item.foodNameRu, item.foodNameEn, per100g)
                Log.d("Repository", "Cached '${item.foodNameRu}' / '${item.foodNameEn}'")
            } catch (e: Exception) {
                Log.w("Repository", "Failed to cache '${item.foodNameRu}': ${e.message}")
            }
        }

        val allItems = cachedResults + aiPending
        return allItems.map { item ->
            val per100g = item.nutrientsPer100g ?: NutrientData()
            val factor = item.weight / 100.0
            FoodAnalysisResult(
                foodName = item.foodNameRu,
                foodNameEn = item.foodNameEn,
                weightGrams = item.weight,
                nutrients = per100g * factor,
                fromCache = item.fromCache
            )
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
        if (nutrients.chromium == 0.0) missing.add("chromium (mcg)")
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
                iodine = if (nutrients.iodine == 0.0) v("iodine") else nutrients.iodine,
                chromium = if (nutrients.chromium == 0.0) v("chromium") else nutrients.chromium
            )
        } catch (e: Exception) {
            Log.w("Repository", "AI micro fill failed: ${e.message}")
            return nutrients
        }
    }

    suspend fun identifyFoodFromPhoto(imageBytes: ByteArray): Pair<String, Double> {
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

        val messages = listOf(OpenRouterMessage(role = "user", content = contentParts))
        val text = callOpenRouterWithRetry(messages = messages, models = visionModels)
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
    "copper": <mg на 100г>, "manganese": <mg на 100г>, "selenium": <mcg на 100г>, "iodine": <mcg на 100г>, "chromium": <mcg на 100г>
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
                val factor = weightGrams / 100.0
                return FoodAnalysisResult(
                    foodName = dishName,
                    foodNameEn = cached.first.keyEn,
                    weightGrams = weightGrams,
                    nutrients = cached.second * factor,
                    fromCache = true
                )
            }
        }

        // Ask AI for nutrients of this exact dish per 100g
        val prompt = """
You are a professional nutritionist. Provide nutritional values PER 100 GRAMS for this COMPLETE DISH (do NOT split into ingredients):
"$dishName"

Return ONLY a JSON object with these fields:
{"food_name_en": "<English translation>", "calories": <kcal>, "protein": <g>, "fat": <g>, "carbs": <g>, "fiber": <g>,
"vitamin_a": <mcg>, "vitamin_b1": <mg>, "vitamin_b2": <mg>, "vitamin_b3": <mg>,
"vitamin_b5": <mg>, "vitamin_b6": <mg>, "vitamin_b7": <mcg>, "vitamin_b9": <mcg>,
"vitamin_b12": <mcg>, "vitamin_c": <mg>, "vitamin_d": <mcg>, "vitamin_e": <mg>,
"vitamin_k": <mcg>, "calcium": <mg>, "iron": <mg>, "magnesium": <mg>,
"phosphorus": <mg>, "potassium": <mg>, "sodium": <mg>, "zinc": <mg>,
"copper": <mg>, "manganese": <mg>, "selenium": <mcg>, "iodine": <mcg>, "chromium": <mcg>}
""".trimIndent()

        val text = callOpenRouterWithRetry(
            messages = listOf(OpenRouterMessage(role = "user", content = prompt)),
            models = textModels
        )
        val json = extractJson(text)
        Log.d("Repository", "Single dish AI response: $json")

        val map = gson.fromJson(json, Map::class.java) as? Map<String, Any>
            ?: throw Exception("Не удалось получить нутриенты для блюда")
        fun v(key: String) = (map[key] as? Number)?.toDouble() ?: 0.0
        val nameEn = (map["food_name_en"] as? String) ?: dishName

        val per100g = NutrientData(
            calories = v("calories"), protein = v("protein"),
            fat = v("fat"), carbs = v("carbs"), fiber = v("fiber"),
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
            selenium = v("selenium"), iodine = v("iodine"),
            chromium = v("chromium")
        )

        // Cache it
        try {
            saveToCache(dishName, nameEn, per100g)
            Log.d("Repository", "Cached single dish '$dishName' / '$nameEn'")
        } catch (e: Exception) {
            Log.w("Repository", "Failed to cache single dish: ${e.message}")
        }

        val factor = weightGrams / 100.0
        return FoodAnalysisResult(
            foodName = dishName,
            foodNameEn = nameEn,
            weightGrams = weightGrams,
            nutrients = per100g * factor,
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
            val name = product.productName ?: product.productNameEn ?: product.brands ?: "Неизвестный продукт"
            val n = product.nutriments ?: return Pair(name, NutrientData())
            // OFF API returns values per 100g in standard units (kcal, g, mg, mcg)
            val per100g = NutrientData(
                calories = n.energyKcal100g ?: 0.0,
                protein = n.proteins100g ?: 0.0,
                fat = n.fat100g ?: 0.0,
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
                iodine = n.iodine100g ?: 0.0,
                chromium = n.chromium100g ?: 0.0
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
            return Triple(cachedByBarcode.first.keyEn, cachedByBarcode.second, true)
        }

        // 2. OFF API lookup
        val result = lookupBarcode(barcode) ?: return null
        val (name, per100g) = result

        // 3. AI enrich missing micros (per 100g)
        val enrichedPer100g = try {
            val enrichedScaled = enrichNutrientsWithAI(name, per100g, 100.0)
            enrichedScaled  // already per 100g since weight=100
        } catch (e: Exception) {
            Log.w("Repository", "AI enrichment failed for barcode $barcode: ${e.message}")
            per100g
        }

        // 4. Cache the enriched result
        try {
            saveToCache(name, name, enrichedPer100g)
            saveToCache("barcode:$barcode", name, enrichedPer100g)
            Log.d("Repository", "Cached enriched barcode $barcode as '$name'")
        } catch (e: Exception) {
            Log.w("Repository", "Failed to cache barcode: ${e.message}")
        }

        return Triple(name, enrichedPer100g, false)
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
            val name = product.productName
                ?: product.productNameEn
                ?: product.brands
                ?: "Dietary supplement (barcode: $barcode)"
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
- protein, fat, carbs, fiber: grams (g)
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
- selenium, iodine, chromium: mcg

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
                iodine = v("iodine"),
                chromium = v("chromium")
            )
        } catch (e: Exception) {
            Log.w("Repository", "Failed to parse supplement AI response: ${e.message}")
            NutrientData()
        }
    }

    // --- OpenRouter helpers with retry/fallback ---
    private suspend fun callOpenRouterWithRetry(
        messages: List<OpenRouterMessage>,
        models: List<String>
    ): String {
        var lastError: Exception? = null
        for (model in models) {
            try {
                Log.d("Repository", "Trying model: $model")
                val request = OpenRouterRequest(
                    model = model,
                    messages = messages
                )
                val httpResponse = openRouterApi.chatCompletion("Bearer $apiKey", request)
                val httpCode = httpResponse.code()

                if (!httpResponse.isSuccessful) {
                    val errorBody = httpResponse.errorBody()?.string() ?: "No error body"
                    Log.w("Repository", "Model $model HTTP $httpCode: $errorBody")
                    if (httpCode in listOf(429, 502, 503, 524)) {
                        lastError = Exception("$model: HTTP $httpCode")
                        continue // try next model immediately
                    }
                    throw Exception("API ошибка HTTP $httpCode: $errorBody")
                }

                val response = httpResponse.body()
                if (response == null) {
                    Log.w("Repository", "Model $model: null response body")
                    lastError = Exception("$model: пустой ответ")
                    continue
                }

                if (response.error != null) {
                    val code = response.error.code
                    Log.w("Repository", "Model $model error $code: ${response.error.message}")
                    if (code in listOf(429, 502, 503, 524)) {
                        lastError = Exception("$model: ${response.error.message}")
                        continue // try next model immediately
                    }
                    throw Exception("API ошибка: ${response.error.message}")
                }

                val msg = response.choices?.firstOrNull()?.message
                val text = msg?.content
                if (text.isNullOrBlank()) {
                    // Thinking models may put all output in reasoning with content=null
                    // when max_tokens is too low. Skip to next model.
                    val reasoning = msg?.reasoning
                    if (!reasoning.isNullOrBlank()) {
                        Log.w("Repository", "Model $model: content empty, only reasoning (${reasoning.length} chars). Skipping.")
                    } else {
                        Log.w("Repository", "Model $model returned empty content, trying next")
                    }
                    lastError = Exception("$model: пустой ответ")
                    continue
                }
                Log.d("Repository", "Success with model: $model")
                return text
            } catch (e: Exception) {
                if (lastError == null) lastError = e
                val msg = e.message ?: ""
                if (msg.contains("429") || msg.contains("503") || msg.contains("502") || msg.contains("524")) {
                    continue // try next model immediately
                }
                throw e
            }
        }
        throw lastError ?: Exception("Все модели недоступны. Попробуйте позже.")
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
