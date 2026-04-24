package com.nutrition.tracker.data.api

import com.google.gson.annotations.SerializedName

data class OpenFoodFactsResponse(
    val status: Int? = null,
    @SerializedName("status_verbose") val statusVerbose: String? = null,
    val product: OFFProduct? = null
)

data class OFFProduct(
    @SerializedName("product_name") val productName: String? = null,
    @SerializedName("product_name_en") val productNameEn: String? = null,
    @SerializedName("brands") val brands: String? = null,
    val nutriments: OFFNutriments? = null,
    @SerializedName("serving_size") val servingSize: String? = null,
    @SerializedName("serving_quantity") val servingQuantity: Double? = null,
    @SerializedName("categories_tags") val categoriesTags: List<String>? = null
)

// Search response models
data class OFFSearchResponse(
    val count: Int? = null,
    val products: List<OFFProduct>? = null
)

data class OFFNutriments(
    @SerializedName("energy-kcal_100g") val energyKcal100g: Double? = null,
    @SerializedName("proteins_100g") val proteins100g: Double? = null,
    @SerializedName("fat_100g") val fat100g: Double? = null,
    @SerializedName("carbohydrates_100g") val carbohydrates100g: Double? = null,
    @SerializedName("fiber_100g") val fiber100g: Double? = null,
    @SerializedName("vitamin-a_100g") val vitaminA100g: Double? = null,
    @SerializedName("vitamin-b1_100g") val vitaminB1100g: Double? = null,
    @SerializedName("vitamin-b2_100g") val vitaminB2100g: Double? = null,
    @SerializedName("vitamin-pp_100g") val vitaminB3100g: Double? = null,
    @SerializedName("pantothenic-acid_100g") val vitaminB5100g: Double? = null,
    @SerializedName("vitamin-b6_100g") val vitaminB6100g: Double? = null,
    @SerializedName("biotin_100g") val vitaminB7100g: Double? = null,
    @SerializedName("vitamin-b9_100g") val vitaminB9100g: Double? = null,
    @SerializedName("vitamin-b12_100g") val vitaminB12100g: Double? = null,
    @SerializedName("vitamin-c_100g") val vitaminC100g: Double? = null,
    @SerializedName("vitamin-d_100g") val vitaminD100g: Double? = null,
    @SerializedName("vitamin-e_100g") val vitaminE100g: Double? = null,
    @SerializedName("vitamin-k_100g") val vitaminK100g: Double? = null,
    @SerializedName("calcium_100g") val calcium100g: Double? = null,
    @SerializedName("iron_100g") val iron100g: Double? = null,
    @SerializedName("magnesium_100g") val magnesium100g: Double? = null,
    @SerializedName("phosphorus_100g") val phosphorus100g: Double? = null,
    @SerializedName("potassium_100g") val potassium100g: Double? = null,
    @SerializedName("sodium_100g") val sodium100g: Double? = null,
    @SerializedName("zinc_100g") val zinc100g: Double? = null,
    @SerializedName("copper_100g") val copper100g: Double? = null,
    @SerializedName("manganese_100g") val manganese100g: Double? = null,
    @SerializedName("selenium_100g") val selenium100g: Double? = null,
    @SerializedName("iodine_100g") val iodine100g: Double? = null,
    @SerializedName("chromium_100g") val chromium100g: Double? = null,
    // Per-serving values (used for supplements/BADs)
    @SerializedName("energy-kcal_serving") val energyKcalServing: Double? = null,
    @SerializedName("proteins_serving") val proteinsServing: Double? = null,
    @SerializedName("fat_serving") val fatServing: Double? = null,
    @SerializedName("carbohydrates_serving") val carbohydratesServing: Double? = null,
    @SerializedName("fiber_serving") val fiberServing: Double? = null,
    @SerializedName("vitamin-a_serving") val vitaminAServing: Double? = null,
    @SerializedName("vitamin-b1_serving") val vitaminB1Serving: Double? = null,
    @SerializedName("vitamin-b2_serving") val vitaminB2Serving: Double? = null,
    @SerializedName("vitamin-pp_serving") val vitaminB3Serving: Double? = null,
    @SerializedName("pantothenic-acid_serving") val vitaminB5Serving: Double? = null,
    @SerializedName("vitamin-b6_serving") val vitaminB6Serving: Double? = null,
    @SerializedName("biotin_serving") val vitaminB7Serving: Double? = null,
    @SerializedName("vitamin-b9_serving") val vitaminB9Serving: Double? = null,
    @SerializedName("vitamin-b12_serving") val vitaminB12Serving: Double? = null,
    @SerializedName("vitamin-c_serving") val vitaminCServing: Double? = null,
    @SerializedName("vitamin-d_serving") val vitaminDServing: Double? = null,
    @SerializedName("vitamin-e_serving") val vitaminEServing: Double? = null,
    @SerializedName("vitamin-k_serving") val vitaminKServing: Double? = null,
    @SerializedName("calcium_serving") val calciumServing: Double? = null,
    @SerializedName("iron_serving") val ironServing: Double? = null,
    @SerializedName("magnesium_serving") val magnesiumServing: Double? = null,
    @SerializedName("phosphorus_serving") val phosphorusServing: Double? = null,
    @SerializedName("potassium_serving") val potassiumServing: Double? = null,
    @SerializedName("sodium_serving") val sodiumServing: Double? = null,
    @SerializedName("zinc_serving") val zincServing: Double? = null,
    @SerializedName("copper_serving") val copperServing: Double? = null,
    @SerializedName("manganese_serving") val manganeseServing: Double? = null,
    @SerializedName("selenium_serving") val seleniumServing: Double? = null,
    @SerializedName("iodine_serving") val iodineServing: Double? = null,
    @SerializedName("chromium_serving") val chromiumServing: Double? = null
)
