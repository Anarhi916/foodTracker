package com.nutrition.tracker.data.api

import com.nutrition.tracker.data.model.NutrientData
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

// Единственный сетевой интерфейс: клиент ходит на backend-прокси (/v1/*).
// AI/USDA-ключи переехали на сервер (см. backend/ARCHITECTURE.md).
// NutrientData десериализуется напрямую (snake_case @SerializedName совпадает с backend).
// Backend отдаёт нутриенты на 100г (supplement — на порцию); клиент масштабирует сам.
interface BackendApiService {

    @POST("v1/food/analyze")
    suspend fun analyze(
        @Header("X-Dev-Auth") auth: String,
        @Header("X-Platform") platform: String = "android",
        @Body request: AnalyzeRequest
    ): Response<AnalyzeResponse>

    @POST("v1/food/dish")
    suspend fun dish(
        @Header("X-Dev-Auth") auth: String,
        @Header("X-Platform") platform: String = "android",
        @Body request: DishRequest
    ): Response<DishResponse>

    @POST("v1/food/photo")
    suspend fun photo(
        @Header("X-Dev-Auth") auth: String,
        @Header("X-Platform") platform: String = "android",
        @Body request: PhotoRequest
    ): Response<PhotoResponse>

    @POST("v1/food/enrich")
    suspend fun enrich(
        @Header("X-Dev-Auth") auth: String,
        @Header("X-Platform") platform: String = "android",
        @Body request: EnrichRequest
    ): Response<EnrichResponse>

    @POST("v1/food/supplement")
    suspend fun supplement(
        @Header("X-Dev-Auth") auth: String,
        @Header("X-Platform") platform: String = "android",
        @Body request: SupplementRequest
    ): Response<SupplementResponse>

    @POST("v1/norms")
    suspend fun norms(
        @Header("X-Dev-Auth") auth: String,
        @Header("X-Platform") platform: String = "android",
        @Body request: NormsRequest
    ): Response<NormsResponse>
}

// ─── Request DTO ───

data class AnalyzeItem(val name: String, val grams: Double)
data class AnalyzeRequest(val items: List<AnalyzeItem>, val uiLang: String, val useCache: Boolean = true)
data class DishRequest(val dishName: String)
data class PhotoRequest(val imageBase64: String, val uiLang: String)
data class EnrichRequest(val name: String, val nutrientsPer100g: NutrientData)
data class SupplementRequest(val name: String, val servingSize: String, val barcode: String)
data class NormsRequest(
    val gender: String, val age: Int, val weight: Double, val height: Double, val goals: String
)

// ─── Response DTO (camelCase — как отдаёт backend) ───

data class BackendFoodResult(
    val foodName: String = "",
    val foodNameEn: String = "",
    val weightGrams: Double = 0.0,
    val nutrientsPer100g: NutrientData = NutrientData(),
    val fromCache: Boolean = false
)
data class AnalyzeResponse(val results: List<BackendFoodResult> = emptyList())
data class DishResponse(val foodNameEn: String = "", val nutrientsPer100g: NutrientData = NutrientData())
data class PhotoResponse(
    val foodName: String = "",
    val foodNameEn: String = "",
    val weightGrams: Double = 0.0,
    val nutrientsPer100g: NutrientData = NutrientData()
)
data class EnrichResponse(val name: String = "", val nutrientsPer100g: NutrientData = NutrientData())
data class SupplementResponse(
    val name: String = "",
    val servingSize: String = "",
    val nutrientsPerServing: NutrientData = NutrientData()
)
data class NormsResponse(val norms: NutrientData = NutrientData())
