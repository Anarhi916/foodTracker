package com.nutrition.tracker.data.model

import com.google.gson.annotations.SerializedName

data class NutrientData(
    val calories: Double = 0.0,
    val protein: Double = 0.0,
    val fat: Double = 0.0,
    @SerializedName("saturated_fat") val saturatedFat: Double = 0.0,
    @SerializedName("monounsaturated_fat") val monounsaturatedFat: Double = 0.0,
    @SerializedName("polyunsaturated_fat") val polyunsaturatedFat: Double = 0.0,
    val cholesterol: Double = 0.0,
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
    val iodine: Double = 0.0
) {
    operator fun plus(other: NutrientData) = NutrientData(
        calories = calories + other.calories,
        protein = protein + other.protein,
        fat = fat + other.fat,
        saturatedFat = saturatedFat + other.saturatedFat,
        monounsaturatedFat = monounsaturatedFat + other.monounsaturatedFat,
        polyunsaturatedFat = polyunsaturatedFat + other.polyunsaturatedFat,
        cholesterol = cholesterol + other.cholesterol,
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
        iodine = iodine + other.iodine
    )

    operator fun times(factor: Double) = NutrientData(
        calories = calories * factor,
        protein = protein * factor,
        fat = fat * factor,
        saturatedFat = saturatedFat * factor,
        monounsaturatedFat = monounsaturatedFat * factor,
        polyunsaturatedFat = polyunsaturatedFat * factor,
        cholesterol = cholesterol * factor,
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
        iodine = iodine * factor
    )

    fun macrosList(): List<Pair<Int, Double>> = listOf(
        com.nutrition.tracker.R.string.calories_kcal to calories,
        com.nutrition.tracker.R.string.protein_g to protein,
        com.nutrition.tracker.R.string.fat_g to fat,
        com.nutrition.tracker.R.string.carbs_g to carbs,
        com.nutrition.tracker.R.string.fiber_g to fiber
    )

    fun fatDetailsList(): List<Pair<Int, Double>> = listOf(
        com.nutrition.tracker.R.string.saturated_fat_g to saturatedFat,
        com.nutrition.tracker.R.string.monounsaturated_fat_g to monounsaturatedFat,
        com.nutrition.tracker.R.string.polyunsaturated_fat_g to polyunsaturatedFat,
        com.nutrition.tracker.R.string.cholesterol_mg to cholesterol
    )

    fun vitaminsList(): List<Pair<Int, Double>> = listOf(
        com.nutrition.tracker.R.string.vitamin_a_mcg to vitaminA,
        com.nutrition.tracker.R.string.vitamin_b1_mg to vitaminB1,
        com.nutrition.tracker.R.string.vitamin_b2_mg to vitaminB2,
        com.nutrition.tracker.R.string.vitamin_b3_mg to vitaminB3,
        com.nutrition.tracker.R.string.vitamin_b5_mg to vitaminB5,
        com.nutrition.tracker.R.string.vitamin_b6_mg to vitaminB6,
        com.nutrition.tracker.R.string.vitamin_b7_mcg to vitaminB7,
        com.nutrition.tracker.R.string.vitamin_b9_mcg to vitaminB9,
        com.nutrition.tracker.R.string.vitamin_b12_mcg to vitaminB12,
        com.nutrition.tracker.R.string.vitamin_c_mg to vitaminC,
        com.nutrition.tracker.R.string.vitamin_d_mcg to vitaminD,
        com.nutrition.tracker.R.string.vitamin_e_mg to vitaminE,
        com.nutrition.tracker.R.string.vitamin_k_mcg to vitaminK
    )

    fun mineralsList(): List<Pair<Int, Double>> = listOf(
        com.nutrition.tracker.R.string.calcium_mg to calcium,
        com.nutrition.tracker.R.string.iron_mg to iron,
        com.nutrition.tracker.R.string.magnesium_mg to magnesium,
        com.nutrition.tracker.R.string.phosphorus_mg to phosphorus,
        com.nutrition.tracker.R.string.potassium_mg to potassium,
        com.nutrition.tracker.R.string.sodium_mg to sodium,
        com.nutrition.tracker.R.string.zinc_mg to zinc,
        com.nutrition.tracker.R.string.copper_mg to copper,
        com.nutrition.tracker.R.string.manganese_mg to manganese,
        com.nutrition.tracker.R.string.selenium_mcg to selenium,
        com.nutrition.tracker.R.string.iodine_mcg to iodine
    )

    fun getByKey(key: String): Double = when (key) {
        "calories" -> calories
        "protein" -> protein
        "fat" -> fat
        "saturatedFat" -> saturatedFat
        "monounsaturatedFat" -> monounsaturatedFat
        "polyunsaturatedFat" -> polyunsaturatedFat
        "cholesterol" -> cholesterol
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
        else -> 0.0
    }

    fun withUpdatedKey(key: String, value: Double): NutrientData = when (key) {
        "calories" -> copy(calories = value)
        "protein" -> copy(protein = value)
        "fat" -> copy(fat = value)
        "saturatedFat" -> copy(saturatedFat = value)
        "monounsaturatedFat" -> copy(monounsaturatedFat = value)
        "polyunsaturatedFat" -> copy(polyunsaturatedFat = value)
        "cholesterol" -> copy(cholesterol = value)
        "carbs" -> copy(carbs = value)
        "fiber" -> copy(fiber = value)
        "vitaminA" -> copy(vitaminA = value)
        "vitaminB1" -> copy(vitaminB1 = value)
        "vitaminB2" -> copy(vitaminB2 = value)
        "vitaminB3" -> copy(vitaminB3 = value)
        "vitaminB5" -> copy(vitaminB5 = value)
        "vitaminB6" -> copy(vitaminB6 = value)
        "vitaminB7" -> copy(vitaminB7 = value)
        "vitaminB9" -> copy(vitaminB9 = value)
        "vitaminB12" -> copy(vitaminB12 = value)
        "vitaminC" -> copy(vitaminC = value)
        "vitaminD" -> copy(vitaminD = value)
        "vitaminE" -> copy(vitaminE = value)
        "vitaminK" -> copy(vitaminK = value)
        "calcium" -> copy(calcium = value)
        "iron" -> copy(iron = value)
        "magnesium" -> copy(magnesium = value)
        "phosphorus" -> copy(phosphorus = value)
        "potassium" -> copy(potassium = value)
        "sodium" -> copy(sodium = value)
        "zinc" -> copy(zinc = value)
        "copper" -> copy(copper = value)
        "manganese" -> copy(manganese = value)
        "selenium" -> copy(selenium = value)
        "iodine" -> copy(iodine = value)
        else -> this
    }

    fun allNutrientsList(): List<Triple<String, Int, Double>> = listOf(
        Triple("calories", com.nutrition.tracker.R.string.calories_kcal, calories),
        Triple("protein", com.nutrition.tracker.R.string.protein_g, protein),
        Triple("fat", com.nutrition.tracker.R.string.fat_g, fat),
        Triple("saturatedFat", com.nutrition.tracker.R.string.saturated_fat_g, saturatedFat),
        Triple("monounsaturatedFat", com.nutrition.tracker.R.string.monounsaturated_fat_g, monounsaturatedFat),
        Triple("polyunsaturatedFat", com.nutrition.tracker.R.string.polyunsaturated_fat_g, polyunsaturatedFat),
        Triple("cholesterol", com.nutrition.tracker.R.string.cholesterol_mg, cholesterol),
        Triple("carbs", com.nutrition.tracker.R.string.carbs_g, carbs),
        Triple("fiber", com.nutrition.tracker.R.string.fiber_g, fiber),
        Triple("vitaminA", com.nutrition.tracker.R.string.vitamin_a_mcg, vitaminA),
        Triple("vitaminB1", com.nutrition.tracker.R.string.vitamin_b1_mg, vitaminB1),
        Triple("vitaminB2", com.nutrition.tracker.R.string.vitamin_b2_mg, vitaminB2),
        Triple("vitaminB3", com.nutrition.tracker.R.string.vitamin_b3_mg, vitaminB3),
        Triple("vitaminB5", com.nutrition.tracker.R.string.vitamin_b5_mg, vitaminB5),
        Triple("vitaminB6", com.nutrition.tracker.R.string.vitamin_b6_mg, vitaminB6),
        Triple("vitaminB7", com.nutrition.tracker.R.string.vitamin_b7_mcg, vitaminB7),
        Triple("vitaminB9", com.nutrition.tracker.R.string.vitamin_b9_mcg, vitaminB9),
        Triple("vitaminB12", com.nutrition.tracker.R.string.vitamin_b12_mcg, vitaminB12),
        Triple("vitaminC", com.nutrition.tracker.R.string.vitamin_c_mg, vitaminC),
        Triple("vitaminD", com.nutrition.tracker.R.string.vitamin_d_mcg, vitaminD),
        Triple("vitaminE", com.nutrition.tracker.R.string.vitamin_e_mg, vitaminE),
        Triple("vitaminK", com.nutrition.tracker.R.string.vitamin_k_mcg, vitaminK),
        Triple("calcium", com.nutrition.tracker.R.string.calcium_mg, calcium),
        Triple("iron", com.nutrition.tracker.R.string.iron_mg, iron),
        Triple("magnesium", com.nutrition.tracker.R.string.magnesium_mg, magnesium),
        Triple("phosphorus", com.nutrition.tracker.R.string.phosphorus_mg, phosphorus),
        Triple("potassium", com.nutrition.tracker.R.string.potassium_mg, potassium),
        Triple("sodium", com.nutrition.tracker.R.string.sodium_mg, sodium),
        Triple("zinc", com.nutrition.tracker.R.string.zinc_mg, zinc),
        Triple("copper", com.nutrition.tracker.R.string.copper_mg, copper),
        Triple("manganese", com.nutrition.tracker.R.string.manganese_mg, manganese),
        Triple("selenium", com.nutrition.tracker.R.string.selenium_mcg, selenium),
        Triple("iodine", com.nutrition.tracker.R.string.iodine_mcg, iodine)
    )
}

data class FoodAnalysisResult(
    @SerializedName("food_name") val foodName: String = "",
    @SerializedName("food_name_en") val foodNameEn: String = "",
    @SerializedName("weight_grams") val weightGrams: Double = 0.0,
    val nutrients: NutrientData = NutrientData(),
    val fromCache: Boolean = false
)
