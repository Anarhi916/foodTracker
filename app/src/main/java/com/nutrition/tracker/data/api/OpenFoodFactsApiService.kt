package com.nutrition.tracker.data.api

import retrofit2.http.GET
import retrofit2.http.Path

interface OpenFoodFactsApiService {

    @GET("api/v2/product/{barcode}.json")
    suspend fun getProduct(
        @Path("barcode") barcode: String
    ): OpenFoodFactsResponse
}
