package com.nutrition.tracker.data.api

import com.google.gson.annotations.SerializedName

data class UsdaSearchResponse(
    val foods: List<UsdaFood>? = null,
    val totalHits: Int? = null
)

data class UsdaFood(
    val fdcId: Int? = null,
    val description: String? = null,
    val foodNutrients: List<UsdaFoodNutrient>? = null
)

data class UsdaFoodNutrient(
    val nutrientId: Int? = null,
    val nutrientName: String? = null,
    val nutrientNumber: String? = null,
    val unitName: String? = null,
    val value: Double? = null
) {
    companion object {
        // USDA nutrient IDs
        const val ENERGY = 1008
        const val PROTEIN = 1003
        const val FAT = 1004
        const val CARBS = 1005
        const val FIBER = 1079
        const val VITAMIN_A = 1106  // RAE, mcg
        const val VITAMIN_B1 = 1165 // Thiamin, mg
        const val VITAMIN_B2 = 1166 // Riboflavin, mg
        const val VITAMIN_B3 = 1167 // Niacin, mg
        const val VITAMIN_B5 = 1170 // Pantothenic acid, mg
        const val VITAMIN_B6 = 1175 // mg
        const val VITAMIN_B7 = 1176 // Biotin, mcg
        const val VITAMIN_B9 = 1177 // Folate total, mcg
        const val VITAMIN_B12 = 1178 // mcg
        const val VITAMIN_C = 1162  // mg
        const val VITAMIN_D = 1114  // mcg
        const val VITAMIN_E = 1109  // mg
        const val VITAMIN_K = 1185  // mcg
        const val CALCIUM = 1087    // mg
        const val IRON = 1089       // mg
        const val MAGNESIUM = 1090  // mg
        const val PHOSPHORUS = 1091 // mg
        const val POTASSIUM = 1092  // mg
        const val SODIUM = 1093     // mg
        const val ZINC = 1095       // mg
        const val COPPER = 1098     // mg
        const val MANGANESE = 1101  // mg
        const val SELENIUM = 1103   // mcg
        const val IODINE = 1100     // mcg
        const val CHROMIUM = 1096   // mcg
    }
}