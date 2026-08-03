package com.nutrition.tracker.data.api

import com.nutrition.tracker.data.model.NutrientData
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.HTTP
import retrofit2.http.Header
import retrofit2.http.POST

// The single network interface: the client talks to the backend proxy (/v1/*).
// AI/USDA keys moved to the server (see backend/ARCHITECTURE.md).
// NutrientData is deserialized directly (snake_case @SerializedName matches the backend).
// Backend returns nutrients per 100g; the client scales them itself.
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

    @POST("v1/norms")
    suspend fun norms(
        @Header("X-Dev-Auth") auth: String,
        @Header("X-Platform") platform: String = "android",
        @Body request: NormsRequest
    ): Response<NormsResponse>

    // ─── Auth (/v1/auth/*) ───

    @POST("v1/auth/google")
    suspend fun authGoogle(@Body request: GoogleAuthRequest): Response<TokenResponse>

    @POST("v1/auth/google")
    suspend fun authGoogleCode(@Body request: GoogleCodeRequest): Response<TokenResponse>

    @POST("v1/auth/apple")
    suspend fun authApple(@Body request: AppleAuthRequest): Response<TokenResponse>

    @POST("v1/auth/refresh")
    suspend fun refresh(@Body request: RefreshRequest): Response<TokenResponse>

    @POST("v1/auth/logout")
    suspend fun logout(@Body request: RefreshRequest): Response<OkResponse>

    @HTTP(method = "DELETE", path = "v1/auth/account", hasBody = false)
    suspend fun deleteAccount(@Header("Authorization") bearer: String): Response<OkResponse>

    // ─── Sync (/v1/sync/*) ───

    @POST("v1/sync/push")
    suspend fun syncPush(
        @Header("X-Dev-Auth") auth: String,
        @Header("X-Platform") platform: String = "android",
        @Body request: SyncPushRequest
    ): Response<SyncPushResponse>

    @retrofit2.http.GET("v1/sync/pull")
    suspend fun syncPull(
        @Header("X-Dev-Auth") auth: String,
        @Header("X-Platform") platform: String = "android",
        @retrofit2.http.Query("since") since: Long? = null
    ): Response<SyncPullResponse>
}

// ─── Request DTO ───

data class AnalyzeItem(val name: String, val grams: Double)
data class AnalyzeRequest(val items: List<AnalyzeItem>, val uiLang: String, val useCache: Boolean = true)
data class DishRequest(val dishName: String)
data class PhotoRequest(val imageBase64: String, val uiLang: String)
data class EnrichRequest(val name: String, val nutrientsPer100g: NutrientData)
data class NormsRequest(
    val gender: String, val age: Int, val weight: Double, val height: Double, val goals: String
)

// ─── Response DTO (camelCase — as the backend returns) ───

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
data class NormsResponse(val norms: NutrientData = NutrientData())

// ─── Auth DTO ───

data class GoogleAuthRequest(val idToken: String, val nonce: String)
data class GoogleCodeRequest(val code: String, val codeVerifier: String, val redirectUri: String, val clientId: String)
data class AppleAuthRequest(val identityToken: String? = null, val code: String? = null, val nonce: String? = null)
data class RefreshRequest(val refreshToken: String)
data class TokenResponse(val accessToken: String = "", val refreshToken: String = "", val expiresIn: Int = 0)
data class OkResponse(val ok: Boolean = false)

// ─── Sync DTO (time — epoch milliseconds, matches the backend and iOS) ───

data class SyncProfileDto(
    val gender: String, val age: Int, val weightKg: Double, val heightCm: Double,
    val goalsText: String, val updatedAt: Long, val deletedAt: Long? = null
)
data class SyncNormsDto(val nutrientsJson: String, val updatedAt: Long, val deletedAt: Long? = null)
data class SyncEntryDto(
    val clientId: String, val date: String, val foodName: String, val foodNameEn: String,
    val weightGrams: Double, val nutrientsJson: String, val source: String, val fromCache: Boolean,
    val createdAt: Long? = null, val updatedAt: Long, val deletedAt: Long? = null
)
data class SyncCacheDto(
    val keyNormalized: String, val keyOriginal: String, val keyEn: String, val keyEnNormalized: String,
    val nutrientsJson: String, val createdAt: Long? = null, val updatedAt: Long, val deletedAt: Long? = null
)
data class SyncPushRequest(
    val profile: SyncProfileDto? = null,
    val norms: SyncNormsDto? = null,
    val entries: List<SyncEntryDto> = emptyList(),
    val foodCache: List<SyncCacheDto> = emptyList()
)
data class SyncPushResponse(val ok: Boolean = false, val serverTime: Long = 0)
data class SyncPullResponse(
    val profile: SyncProfileDto? = null,
    val norms: SyncNormsDto? = null,
    val entries: List<SyncEntryDto> = emptyList(),
    val foodCache: List<SyncCacheDto> = emptyList(),
    val serverTime: Long = 0
)
