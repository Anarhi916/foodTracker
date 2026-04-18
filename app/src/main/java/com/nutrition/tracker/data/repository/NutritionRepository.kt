package com.nutrition.tracker.data.repository

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
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
    private val geminiApi: GeminiApiService = ApiClient.geminiApi,
    private val offApi: OpenFoodFactsApiService = ApiClient.openFoodFactsApi
) {
    private val gson = Gson()
    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    private val apiKey = BuildConfig.GEMINI_API_KEY

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

        val nutrients = callGeminiText(prompt)
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

    suspend fun analyzeFoodText(foodDescription: String): FoodAnalysisResult {
        val prompt = """
You are a nutrition expert. Analyze the following food item and its portion, and provide detailed nutritional information.

Food: $foodDescription

Return ONLY a JSON object with this EXACT structure:
{
  "food_name": "<concise name of the food>",
  "nutrients": {
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
}
""".trimIndent()

        return callGeminiForFood(prompt)
    }

    suspend fun analyzeFoodPhoto(imageBytes: ByteArray): FoodAnalysisResult {
        val base64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
        val prompt = """
You are a nutrition expert. Analyze this food photo. Identify the food items and estimate the portion sizes and their nutritional content.

Return ONLY a JSON object with this EXACT structure:
{
  "food_name": "<description of identified food>",
  "nutrients": {
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
}
""".trimIndent()

        val request = GeminiRequest(
            contents = listOf(
                GeminiContent(
                    parts = listOf(
                        GeminiPart(text = prompt),
                        GeminiPart(inlineData = GeminiInlineData(mimeType = "image/jpeg", data = base64))
                    )
                )
            )
        )

        val response = geminiApi.generateContent(apiKey, request)
        val text = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
            ?: throw Exception("Пустой ответ от ИИ")
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

    // --- Gemini helpers ---
    private suspend fun callGeminiText(prompt: String): NutrientData {
        val request = GeminiRequest(
            contents = listOf(GeminiContent(parts = listOf(GeminiPart(text = prompt))))
        )
        val response = geminiApi.generateContent(apiKey, request)
        val text = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
            ?: throw Exception("Пустой ответ от ИИ")
        return parseNutrientData(text)
    }

    private suspend fun callGeminiForFood(prompt: String): FoodAnalysisResult {
        val request = GeminiRequest(
            contents = listOf(GeminiContent(parts = listOf(GeminiPart(text = prompt))))
        )
        val response = geminiApi.generateContent(apiKey, request)
        val text = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
            ?: throw Exception("Пустой ответ от ИИ")
        return parseFoodResult(text)
    }

    private fun parseNutrientData(text: String): NutrientData {
        val json = extractJson(text)
        return gson.fromJson(json, NutrientData::class.java)
    }

    private fun parseFoodResult(text: String): FoodAnalysisResult {
        val json = extractJson(text)
        return gson.fromJson(json, FoodAnalysisResult::class.java)
    }

    private fun extractJson(text: String): String {
        val trimmed = text.trim()
        if (trimmed.startsWith("{")) return trimmed
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start >= 0 && end > start) return trimmed.substring(start, end + 1)
        return trimmed
    }

    fun parseNutrients(json: String): NutrientData {
        return try {
            gson.fromJson(json, NutrientData::class.java) ?: NutrientData()
        } catch (e: Exception) {
            NutrientData()
        }
    }
}
