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
    val nutriments: OFFNutriments? = null
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
    @SerializedName("chromium_100g") val chromium100g: Double? = null
)
