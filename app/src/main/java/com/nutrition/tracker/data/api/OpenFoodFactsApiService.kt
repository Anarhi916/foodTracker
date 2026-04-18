package com.nutrition.tracker.data.api

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface OpenFoodFactsApiService {

    @GET("api/v2/product/{barcode}.json")
    suspend fun getProduct(
        @Path("barcode") barcode: String
    ): OpenFoodFactsResponse

    @GET("cgi/search.pl")
    suspend fun searchProducts(
        @Query("search_terms") searchTerms: String,
        @Query("json") json: Int = 1,
        @Query("page_size") pageSize: Int = 5,
        @Query("fields") fields: String = "product_name,product_name_en,nutriments"
    ): OFFSearchResponse
}
