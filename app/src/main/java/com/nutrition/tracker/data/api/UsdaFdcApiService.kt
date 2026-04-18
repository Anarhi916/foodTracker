package com.nutrition.tracker.data.api

import retrofit2.http.GET
import retrofit2.http.Query

interface UsdaFdcApiService {

    @GET("fdc/v1/foods/search")
    suspend fun searchFoods(
        @Query("api_key") apiKey: String = "DEMO_KEY",
        @Query("query") query: String,
        @Query("pageSize") pageSize: Int = 3,
        @Query("dataType") dataType: String = "Survey (FNDDS),SR Legacy,Foundation"
    ): UsdaSearchResponse
}