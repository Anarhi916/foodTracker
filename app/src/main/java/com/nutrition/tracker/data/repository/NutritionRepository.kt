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
import kotlinx.coroutines.delay
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
    private val textModels = listOf(
        "google/gemma-4-31b-it:free",
        "nvidia/nemotron-3-super-120b-a12b:free",
        "openai/gpt-oss-120b:free",
        "google/gemma-3-27b-it:free",
        "google/gemma-3-12b-it:free",
        "z-ai/glm-4.5-air:free",
        "minimax/minimax-m2.5:free",
        "nvidia/nemotron-nano-9b-v2:free",
        "meta-llama/llama-3.3-70b-instruct:free"
    )

    // Fallback vision models
    private val visionModels = listOf(
        "nvidia/nemotron-nano-12b-v2-vl:free",
        "google/gemma-4-31b-it:free",
        "google/gemma-3-12b-it:free"
    )

    // Paid model for initial daily norms calculation only.
    private val normsModels = listOf(
        "openai/gpt-4o-mini"
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

    suspend fun analyzeFoodText(foodDescription: String): List<FoodAnalysisResult> {
        // Step 1: AI identifies all foods with names (RU + EN) and weights
        val identifyPrompt = """
Определи ВСЕ продукты и их вес из описания. Описание может содержать один или несколько продуктов.
Если указано количество штук — рассчитай общий вес. Если вес не указан — оцени типичную порцию.

ВАЖНО: food_name_en — это ТОЧНЫЙ перевод названия блюда на английский для поиска в USDA базе данных.
Примеры правильного перевода:
- "куриная отбивная" → "chicken breast cutlet"
- "гречневая каша" → "buckwheat porridge"  
- "творог нежирный" → "low-fat cottage cheese"
- "борщ" → "borscht"

Описание: $foodDescription

Верни ТОЛЬКО JSON массив (даже если продукт один):
[{"food_name": "<название НА РУССКОМ>", "food_name_en": "<EXACT English translation for USDA search>", "weight_grams": <число>}]
""".trimIndent()

        val identifyText = callOpenRouterWithRetry(
            messages = listOf(OpenRouterMessage(role = "user", content = identifyPrompt)),
            models = textModels
        )

        Log.d("Repository", "AI identify response: $identifyText")

        val identities: List<FoodIdentity> = parseIdentityList(identifyText)

        if (identities.isEmpty()) {
            throw Exception("Не удалось распознать продукты из описания")
        }

        // Step 2: USDA lookup for each food (REST calls, no AI)
        data class PendingFood(
            val foodNameRu: String,
            val foodNameEn: String,
            val weight: Double,
            var usdaNutrients: NutrientData? = null
        )

        val pending = identities.map { id ->
            PendingFood(
                foodNameRu = id.foodName.ifBlank { foodDescription },
                foodNameEn = id.foodNameEn.ifBlank { id.foodName },
                weight = if (id.weightGrams > 0) id.weightGrams else 100.0
            )
        }

        for ((idx, item) in pending.withIndex()) {
            Log.d("Repository", "Processing: '${item.foodNameRu}' / '${item.foodNameEn}', weight=${item.weight}g")
            try {
                val usdaResult = usdaApi.searchFoods(query = item.foodNameEn)
                // Pick best USDA result: prefer one with calories > 0
                val food = usdaResult.foods?.firstOrNull { f ->
                    f.foodNutrients?.any { it.nutrientId == UsdaFoodNutrient.ENERGY && (it.value ?: 0.0) > 0 } == true
                } ?: usdaResult.foods?.firstOrNull()
                if (food?.foodNutrients != null) {
                    val nMap = mutableMapOf<Int, Double>()
                    for (fn in food.foodNutrients) {
                        if (fn.nutrientId != null && fn.value != null) nMap[fn.nutrientId] = fn.value
                    }
                    val N = UsdaFoodNutrient
                    val f = item.weight / 100.0
                    val n = NutrientData(
                        calories = (nMap[N.ENERGY] ?: 0.0) * f, protein = (nMap[N.PROTEIN] ?: 0.0) * f,
                        fat = (nMap[N.FAT] ?: 0.0) * f, carbs = (nMap[N.CARBS] ?: 0.0) * f,
                        fiber = (nMap[N.FIBER] ?: 0.0) * f, vitaminA = (nMap[N.VITAMIN_A] ?: 0.0) * f,
                        vitaminB1 = (nMap[N.VITAMIN_B1] ?: 0.0) * f, vitaminB2 = (nMap[N.VITAMIN_B2] ?: 0.0) * f,
                        vitaminB3 = (nMap[N.VITAMIN_B3] ?: 0.0) * f, vitaminB5 = (nMap[N.VITAMIN_B5] ?: 0.0) * f,
                        vitaminB6 = (nMap[N.VITAMIN_B6] ?: 0.0) * f, vitaminB7 = (nMap[N.VITAMIN_B7] ?: 0.0) * f,
                        vitaminB9 = (nMap[N.VITAMIN_B9] ?: 0.0) * f, vitaminB12 = (nMap[N.VITAMIN_B12] ?: 0.0) * f,
                        vitaminC = (nMap[N.VITAMIN_C] ?: 0.0) * f, vitaminD = (nMap[N.VITAMIN_D] ?: 0.0) * f,
                        vitaminE = (nMap[N.VITAMIN_E] ?: 0.0) * f, vitaminK = (nMap[N.VITAMIN_K] ?: 0.0) * f,
                        calcium = (nMap[N.CALCIUM] ?: 0.0) * f, iron = (nMap[N.IRON] ?: 0.0) * f,
                        magnesium = (nMap[N.MAGNESIUM] ?: 0.0) * f, phosphorus = (nMap[N.PHOSPHORUS] ?: 0.0) * f,
                        potassium = (nMap[N.POTASSIUM] ?: 0.0) * f, sodium = (nMap[N.SODIUM] ?: 0.0) * f,
                        zinc = (nMap[N.ZINC] ?: 0.0) * f, copper = (nMap[N.COPPER] ?: 0.0) * f,
                        manganese = (nMap[N.MANGANESE] ?: 0.0) * f, selenium = (nMap[N.SELENIUM] ?: 0.0) * f,
                        iodine = (nMap[N.IODINE] ?: 0.0) * f, chromium = (nMap[N.CHROMIUM] ?: 0.0) * f
                    )
                    if (n.calories > 0 || n.protein > 0 || n.fat > 0 || n.carbs > 0) {
                        item.usdaNutrients = n
                        Log.d("Repository", "USDA '${food.description}': cal=${n.calories}")
                    }
                }
            } catch (e: Exception) {
                Log.w("Repository", "USDA search failed for '${item.foodNameEn}': ${e.message}")
            }
        }

        // Step 3: ONE batch AI call for ALL items that need nutrients (failed USDA)
        val needAi = pending.filter { it.usdaNutrients == null }
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
                        val f = item.weight / 100.0
                        item.usdaNutrients = NutrientData(
                            calories = v("calories") * f, protein = v("protein") * f,
                            fat = v("fat") * f, carbs = v("carbs") * f, fiber = v("fiber") * f,
                            vitaminA = v("vitamin_a") * f, vitaminB1 = v("vitamin_b1") * f,
                            vitaminB2 = v("vitamin_b2") * f, vitaminB3 = v("vitamin_b3") * f,
                            vitaminB5 = v("vitamin_b5") * f, vitaminB6 = v("vitamin_b6") * f,
                            vitaminB7 = v("vitamin_b7") * f, vitaminB9 = v("vitamin_b9") * f,
                            vitaminB12 = v("vitamin_b12") * f, vitaminC = v("vitamin_c") * f,
                            vitaminD = v("vitamin_d") * f, vitaminE = v("vitamin_e") * f,
                            vitaminK = v("vitamin_k") * f, calcium = v("calcium") * f,
                            iron = v("iron") * f, magnesium = v("magnesium") * f,
                            phosphorus = v("phosphorus") * f, potassium = v("potassium") * f,
                            sodium = v("sodium") * f, zinc = v("zinc") * f,
                            copper = v("copper") * f, manganese = v("manganese") * f,
                            selenium = v("selenium") * f, iodine = v("iodine") * f,
                            chromium = v("chromium") * f
                        )
                        Log.d("Repository", "Batch AI '${item.foodNameEn}': cal=${item.usdaNutrients!!.calories}")
                    }
                }
            } catch (e: Exception) {
                Log.w("Repository", "Batch AI failed: ${e.message}")
            }
        }

        // Step 4: ONE batch AI call to fill missing micros for ALL items that have macros
        val needMicros = pending.filter { it.usdaNutrients != null }
        if (needMicros.isNotEmpty()) {
            val itemsWithMissing = needMicros.filter { item ->
                val n = item.usdaNutrients!!
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
                            val f = item.weight / 100.0
                            val n = item.usdaNutrients!!
                            item.usdaNutrients = n.copy(
                                vitaminA = if (n.vitaminA == 0.0) v("vitamin_a") * f else n.vitaminA,
                                vitaminB1 = if (n.vitaminB1 == 0.0) v("vitamin_b1") * f else n.vitaminB1,
                                vitaminB2 = if (n.vitaminB2 == 0.0) v("vitamin_b2") * f else n.vitaminB2,
                                vitaminB3 = if (n.vitaminB3 == 0.0) v("vitamin_b3") * f else n.vitaminB3,
                                vitaminB5 = if (n.vitaminB5 == 0.0) v("vitamin_b5") * f else n.vitaminB5,
                                vitaminB6 = if (n.vitaminB6 == 0.0) v("vitamin_b6") * f else n.vitaminB6,
                                vitaminB7 = if (n.vitaminB7 == 0.0) v("vitamin_b7") * f else n.vitaminB7,
                                vitaminB9 = if (n.vitaminB9 == 0.0) v("vitamin_b9") * f else n.vitaminB9,
                                vitaminB12 = if (n.vitaminB12 == 0.0) v("vitamin_b12") * f else n.vitaminB12,
                                vitaminC = if (n.vitaminC == 0.0) v("vitamin_c") * f else n.vitaminC,
                                vitaminD = if (n.vitaminD == 0.0) v("vitamin_d") * f else n.vitaminD,
                                vitaminE = if (n.vitaminE == 0.0) v("vitamin_e") * f else n.vitaminE,
                                vitaminK = if (n.vitaminK == 0.0) v("vitamin_k") * f else n.vitaminK,
                                calcium = if (n.calcium == 0.0) v("calcium") * f else n.calcium,
                                iron = if (n.iron == 0.0) v("iron") * f else n.iron,
                                magnesium = if (n.magnesium == 0.0) v("magnesium") * f else n.magnesium,
                                phosphorus = if (n.phosphorus == 0.0) v("phosphorus") * f else n.phosphorus,
                                potassium = if (n.potassium == 0.0) v("potassium") * f else n.potassium,
                                sodium = if (n.sodium == 0.0) v("sodium") * f else n.sodium,
                                zinc = if (n.zinc == 0.0) v("zinc") * f else n.zinc,
                                copper = if (n.copper == 0.0) v("copper") * f else n.copper,
                                manganese = if (n.manganese == 0.0) v("manganese") * f else n.manganese,
                                selenium = if (n.selenium == 0.0) v("selenium") * f else n.selenium,
                                iodine = if (n.iodine == 0.0) v("iodine") * f else n.iodine,
                                chromium = if (n.chromium == 0.0) v("chromium") * f else n.chromium
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.w("Repository", "Batch micro fill failed: ${e.message}")
                }
            }
        }

        // Build final results
        return pending.map { item ->
            FoodAnalysisResult(
                foodName = item.foodNameRu,
                foodNameEn = item.foodNameEn,
                weightGrams = item.weight,
                nutrients = item.usdaNutrients ?: NutrientData()
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

    suspend fun addFoodEntry(foodName: String, weightGrams: Double, nutrients: NutrientData, source: String = "manual") {
        db.foodEntryDao().insert(
            FoodEntryEntity(
                date = todayDate(),
                foodName = foodName,
                weightGrams = weightGrams,
                nutrientsJson = gson.toJson(nutrients),
                source = source
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
                        delay(1500)
                        continue // try next model
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
                        delay(1500)
                        continue
                    }
                    throw Exception("API ошибка: ${response.error.message}")
                }

                val text = response.choices?.firstOrNull()?.message?.content
                if (text.isNullOrBlank()) {
                    Log.w("Repository", "Model $model returned empty content, trying next")
                    lastError = Exception("$model: пустой ответ")
                    continue
                }
                Log.d("Repository", "Success with model: $model")
                return text
            } catch (e: Exception) {
                if (lastError == null) lastError = e
                val msg = e.message ?: ""
                if (msg.contains("429") || msg.contains("503") || msg.contains("502") || msg.contains("524")) {
                    delay(1500)
                    continue
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
