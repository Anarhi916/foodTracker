package com.nutrition.tracker.data.api

import retrofit2.http.GET
import retrofit2.http.Query

interface UsdaFdcApiService {

    @GET("fdc/v1/foods/search")
    suspend fun searchFoods(
        @Query("api_key") apiKey: String = "FrYuRxWfygwuQbOzohHhbbI981ahQCGnPTJiDb35",
        @Query("query") query: String,
        @Query("pageSize") pageSize: Int = 25
    ): UsdaSearchResponse
}