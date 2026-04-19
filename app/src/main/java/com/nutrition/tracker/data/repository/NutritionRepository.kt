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
        "google/gemma-3-12b-it:free",
        "google/gemma-3-27b-it:free",
        "google/gemma-3-4b-it:free",
        "meta-llama/llama-3.3-70b-instruct:free",
        "liquid/lfm-2.5-1.2b-instruct:free"
    )

    // Fallback vision models
    private val visionModels = listOf(
        "nvidia/nemotron-nano-12b-v2-vl:free",
        "google/gemma-3-12b-it:free"
    )

    fun todayDate(): String = LocalDate.now().format(dateFormatter)

    // --- User Profile ---
    fun getUserProfile(): Flow<UserProfileEntity?> = db.userProfileDao().getProfile()

    suspend fun saveUserProfile(gender: String, weight: Double, height: Double, goals: String) {
        db.userProfileDao().insert(
            UserProfileEntity(gender = gender, weightKg = weight, heightCm = height, goalsText = goals)
        )
    }

    // --- Daily Norms ---
    fun getDailyNorms(): Flow<DailyNormsEntity?> = db.dailyNormsDao().getNorms()

    suspend fun calculateAndSaveNorms(gender: String, weight: Double, height: Double, goals: String): NutrientData {
        val prompt = """
You are a professional nutrition expert. Based on the following user data, calculate the recommended DAILY nutritional intake to achieve their goals.

User data:
- Gender: $gender
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

        val nutrients = callOpenRouterText(prompt)
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

Описание: $foodDescription

Верни ТОЛЬКО JSON массив (даже если продукт один):
[{"food_name": "<название НА РУССКОМ>", "food_name_en": "<name IN ENGLISH for database>", "weight_grams": <число>}]

Пример для "хлеб 100г, масло 20г":
[{"food_name": "Хлеб белый", "food_name_en": "white bread", "weight_grams": 100}, {"food_name": "Масло сливочное", "food_name_en": "butter", "weight_grams": 20}]
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

        // Step 2: For each food, look up USDA or fall back to AI
        val results = mutableListOf<FoodAnalysisResult>()
        for (id in identities) {
            val weight = if (id.weightGrams > 0) id.weightGrams else 100.0
            val foodNameEn = id.foodNameEn.ifBlank { id.foodName }
            val foodNameRu = id.foodName.ifBlank { foodDescription }

            Log.d("Repository", "Processing: '$foodNameRu' / '$foodNameEn', weight=${weight}g")

            var dbNutrients: NutrientData? = null
            try {
                val usdaResult = usdaApi.searchFoods(query = foodNameEn)
                val food = usdaResult.foods?.firstOrNull()
                if (food?.foodNutrients != null) {
                    val nMap = mutableMapOf<Int, Double>()
                    for (fn in food.foodNutrients) {
                        if (fn.nutrientId != null && fn.value != null) nMap[fn.nutrientId] = fn.value
                    }
                    val N = UsdaFoodNutrient
                    val f = weight / 100.0
                    dbNutrients = NutrientData(
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
                    Log.d("Repository", "USDA '${food.description}': cal=${dbNutrients.calories}")
                }
            } catch (e: Exception) {
                Log.w("Repository", "USDA search failed for '$foodNameEn': ${e.message}")
            }

            if (dbNutrients != null && dbNutrients.protein > 0) {
                dbNutrients = fillMissingMicrosWithAI(dbNutrients, foodNameEn, weight)
                results.add(FoodAnalysisResult(foodName = foodNameRu, foodNameEn = foodNameEn, weightGrams = weight, nutrients = dbNutrients))
            } else {
                // Fallback: full AI analysis for this single food
                Log.d("Repository", "USDA not found for '$foodNameEn', using AI")
                val fullPrompt = """
You are a professional nutritionist. Calculate precise nutritional values for: $foodNameEn (${weight.toInt()}g).
Return ONLY JSON:
{
  "food_name": "$foodNameRu",
  "food_name_en": "$foodNameEn",
  "weight_grams": $weight,
  "nutrients": {
    "calories": <number>, "protein": <grams>, "fat": <grams>, "carbs": <grams>, "fiber": <grams>,
    "vitamin_a": <mcg>, "vitamin_b1": <mg>, "vitamin_b2": <mg>, "vitamin_b3": <mg>,
    "vitamin_b5": <mg>, "vitamin_b6": <mg>, "vitamin_b7": <mcg>, "vitamin_b9": <mcg>,
    "vitamin_b12": <mcg>, "vitamin_c": <mg>, "vitamin_d": <mcg>, "vitamin_e": <mg>,
    "vitamin_k": <mcg>, "calcium": <mg>, "iron": <mg>, "magnesium": <mg>,
    "phosphorus": <mg>, "potassium": <mg>, "sodium": <mg>, "zinc": <mg>,
    "copper": <mg>, "manganese": <mg>, "selenium": <mcg>, "iodine": <mcg>, "chromium": <mcg>
  }
}
""".trimIndent()
                try {
                    results.add(callOpenRouterForFood(fullPrompt))
                } catch (e: Exception) {
                    Log.w("Repository", "AI fallback failed for '$foodNameRu': ${e.message}")
                    results.add(FoodAnalysisResult(foodName = foodNameRu, foodNameEn = foodNameEn, weightGrams = weight, nutrients = NutrientData()))
                }
            }
        }
        return results
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

    private suspend fun fillMissingMicrosWithAI(nutrients: NutrientData, foodNameEn: String, weight: Double): NutrientData {
        val missing = mutableListOf<String>()
        if (nutrients.vitaminA == 0.0) missing.add("vitamin_a (mcg RAE)")
        if (nutrients.vitaminD == 0.0) missing.add("vitamin_d (mcg)")
        if (nutrients.vitaminE == 0.0) missing.add("vitamin_e (mg)")
        if (nutrients.vitaminK == 0.0) missing.add("vitamin_k (mcg)")
        if (nutrients.vitaminB7 == 0.0) missing.add("vitamin_b7 (mcg)")
        if (nutrients.iodine == 0.0) missing.add("iodine (mcg)")
        if (nutrients.chromium == 0.0) missing.add("chromium (mcg)")
        if (missing.isEmpty()) return nutrients
        try {
            val prompt = """
For "$foodNameEn" (${weight.toInt()}g), provide ONLY these nutrients using USDA reference values:
${missing.joinToString(", ")}
Return ONLY JSON, e.g.: {"vitamin_a": 720, "vitamin_d": 9}
""".trimIndent()
            val text = callOpenRouterWithRetry(listOf(OpenRouterMessage(role = "user", content = prompt)), textModels)
            val json = extractJson(text)
            val map = gson.fromJson(json, Map::class.java) as? Map<String, Any> ?: return nutrients
            fun v(key: String) = (map[key] as? Number)?.toDouble() ?: 0.0
            return nutrients.copy(
                vitaminA = if (nutrients.vitaminA == 0.0) v("vitamin_a") else nutrients.vitaminA,
                vitaminD = if (nutrients.vitaminD == 0.0) v("vitamin_d") else nutrients.vitaminD,
                vitaminE = if (nutrients.vitaminE == 0.0) v("vitamin_e") else nutrients.vitaminE,
                vitaminK = if (nutrients.vitaminK == 0.0) v("vitamin_k") else nutrients.vitaminK,
                vitaminB7 = if (nutrients.vitaminB7 == 0.0) v("vitamin_b7") else nutrients.vitaminB7,
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
                    if (httpCode == 429 || httpCode == 503) {
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
                    if (code == 429 || code == 503) {
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
                if (e.message?.contains("429") == true || e.message?.contains("503") == true) {
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
