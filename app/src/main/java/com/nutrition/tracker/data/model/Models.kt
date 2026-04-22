package com.nutrition.tracker.data.model

import com.google.gson.annotations.SerializedName

data class NutrientData(
    val calories: Double = 0.0,
    val protein: Double = 0.0,
    val fat: Double = 0.0,
    val carbs: Double = 0.0,
    val fiber: Double = 0.0,
    @SerializedName("vitamin_a") val vitaminA: Double = 0.0,
    @SerializedName("vitamin_b1") val vitaminB1: Double = 0.0,
    @SerializedName("vitamin_b2") val vitaminB2: Double = 0.0,
    @SerializedName("vitamin_b3") val vitaminB3: Double = 0.0,
    @SerializedName("vitamin_b5") val vitaminB5: Double = 0.0,
    @SerializedName("vitamin_b6") val vitaminB6: Double = 0.0,
    @SerializedName("vitamin_b7") val vitaminB7: Double = 0.0,
    @SerializedName("vitamin_b9") val vitaminB9: Double = 0.0,
    @SerializedName("vitamin_b12") val vitaminB12: Double = 0.0,
    @SerializedName("vitamin_c") val vitaminC: Double = 0.0,
    @SerializedName("vitamin_d") val vitaminD: Double = 0.0,
    @SerializedName("vitamin_e") val vitaminE: Double = 0.0,
    @SerializedName("vitamin_k") val vitaminK: Double = 0.0,
    val calcium: Double = 0.0,
    val iron: Double = 0.0,
    val magnesium: Double = 0.0,
    val phosphorus: Double = 0.0,
    val potassium: Double = 0.0,
    val sodium: Double = 0.0,
    val zinc: Double = 0.0,
    val copper: Double = 0.0,
    val manganese: Double = 0.0,
    val selenium: Double = 0.0,
    val iodine: Double = 0.0,
    val chromium: Double = 0.0
) {
    operator fun plus(other: NutrientData) = NutrientData(
        calories = calories + other.calories,
        protein = protein + other.protein,
        fat = fat + other.fat,
        carbs = carbs + other.carbs,
        fiber = fiber + other.fiber,
        vitaminA = vitaminA + other.vitaminA,
        vitaminB1 = vitaminB1 + other.vitaminB1,
        vitaminB2 = vitaminB2 + other.vitaminB2,
        vitaminB3 = vitaminB3 + other.vitaminB3,
        vitaminB5 = vitaminB5 + other.vitaminB5,
        vitaminB6 = vitaminB6 + other.vitaminB6,
        vitaminB7 = vitaminB7 + other.vitaminB7,
        vitaminB9 = vitaminB9 + other.vitaminB9,
        vitaminB12 = vitaminB12 + other.vitaminB12,
        vitaminC = vitaminC + other.vitaminC,
        vitaminD = vitaminD + other.vitaminD,
        vitaminE = vitaminE + other.vitaminE,
        vitaminK = vitaminK + other.vitaminK,
        calcium = calcium + other.calcium,
        iron = iron + other.iron,
        magnesium = magnesium + other.magnesium,
        phosphorus = phosphorus + other.phosphorus,
        potassium = potassium + other.potassium,
        sodium = sodium + other.sodium,
        zinc = zinc + other.zinc,
        copper = copper + other.copper,
        manganese = manganese + other.manganese,
        selenium = selenium + other.selenium,
        iodine = iodine + other.iodine,
        chromium = chromium + other.chromium
    )

    operator fun times(factor: Double) = NutrientData(
        calories = calories * factor,
        protein = protein * factor,
        fat = fat * factor,
        carbs = carbs * factor,
        fiber = fiber * factor,
        vitaminA = vitaminA * factor,
        vitaminB1 = vitaminB1 * factor,
        vitaminB2 = vitaminB2 * factor,
        vitaminB3 = vitaminB3 * factor,
        vitaminB5 = vitaminB5 * factor,
        vitaminB6 = vitaminB6 * factor,
        vitaminB7 = vitaminB7 * factor,
        vitaminB9 = vitaminB9 * factor,
        vitaminB12 = vitaminB12 * factor,
        vitaminC = vitaminC * factor,
        vitaminD = vitaminD * factor,
        vitaminE = vitaminE * factor,
        vitaminK = vitaminK * factor,
        calcium = calcium * factor,
        iron = iron * factor,
        magnesium = magnesium * factor,
        phosphorus = phosphorus * factor,
        potassium = potassium * factor,
        sodium = sodium * factor,
        zinc = zinc * factor,
        copper = copper * factor,
        manganese = manganese * factor,
        selenium = selenium * factor,
        iodine = iodine * factor,
        chromium = chromium * factor
    )

    fun macrosList(): List<Pair<String, Double>> = listOf(
        "Калории (ккал)" to calories,
        "Белки (г)" to protein,
        "Жиры (г)" to fat,
        "Углеводы (г)" to carbs,
        "Клетчатка (г)" to fiber
    )

    fun vitaminsList(): List<Pair<String, Double>> = listOf(
        "Витамин A (мкг)" to vitaminA,
        "Витамин B1 (мг)" to vitaminB1,
        "Витамин B2 (мг)" to vitaminB2,
        "Витамин B3 (мг)" to vitaminB3,
        "Витамин B5 (мг)" to vitaminB5,
        "Витамин B6 (мг)" to vitaminB6,
        "Витамин B7 (мкг)" to vitaminB7,
        "Витамин B9 (мкг)" to vitaminB9,
        "Витамин B12 (мкг)" to vitaminB12,
        "Витамин C (мг)" to vitaminC,
        "Витамин D (мкг)" to vitaminD,
        "Витамин E (мг)" to vitaminE,
        "Витамин K (мкг)" to vitaminK
    )

    fun mineralsList(): List<Pair<String, Double>> = listOf(
        "Кальций (мг)" to calcium,
        "Железо (мг)" to iron,
        "Магний (мг)" to magnesium,
        "Фосфор (мг)" to phosphorus,
        "Калий (мг)" to potassium,
        "Натрий (мг)" to sodium,
        "Цинк (мг)" to zinc,
        "Медь (мг)" to copper,
        "Марганец (мг)" to manganese,
        "Селен (мкг)" to selenium,
        "Йод (мкг)" to iodine,
        "Хром (мкг)" to chromium
    )

    fun getByKey(key: String): Double = when (key) {
        "calories" -> calories
        "protein" -> protein
        "fat" -> fat
        "carbs" -> carbs
        "fiber" -> fiber
        "vitaminA" -> vitaminA
        "vitaminB1" -> vitaminB1
        "vitaminB2" -> vitaminB2
        "vitaminB3" -> vitaminB3
        "vitaminB5" -> vitaminB5
        "vitaminB6" -> vitaminB6
        "vitaminB7" -> vitaminB7
        "vitaminB9" -> vitaminB9
        "vitaminB12" -> vitaminB12
        "vitaminC" -> vitaminC
        "vitaminD" -> vitaminD
        "vitaminE" -> vitaminE
        "vitaminK" -> vitaminK
        "calcium" -> calcium
        "iron" -> iron
        "magnesium" -> magnesium
        "phosphorus" -> phosphorus
        "potassium" -> potassium
        "sodium" -> sodium
        "zinc" -> zinc
        "copper" -> copper
        "manganese" -> manganese
        "selenium" -> selenium
        "iodine" -> iodine
        "chromium" -> chromium
        else -> 0.0
    }
}

data class FoodAnalysisResult(
    @SerializedName("food_name") val foodName: String = "",
    @SerializedName("food_name_en") val foodNameEn: String = "",
    @SerializedName("weight_grams") val weightGrams: Double = 0.0,
    val nutrients: NutrientData = NutrientData(),
    val fromCache: Boolean = false
)
