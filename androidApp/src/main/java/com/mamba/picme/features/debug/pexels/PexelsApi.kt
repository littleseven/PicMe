package com.mamba.picme.features.debug.pexels

import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

/**
 * Pexels 官方 API（https://www.pexels.com/api/documentation/）。
 * Key 以 @Header 逐请求传入，支持运行时换 Key。
 * 免费档限流：200 次/小时、20,000 次/月（429 由 ViewModel 处理）。
 */
interface PexelsApi {

    @GET("v1/search")
    suspend fun search(
        @Header("Authorization") apiKey: String,
        @Query("query") query: String,
        @Query("page") page: Int,
        @Query("per_page") perPage: Int = PER_PAGE
    ): PexelsSearchResponse

    @GET("v1/curated")
    suspend fun curated(
        @Header("Authorization") apiKey: String,
        @Query("page") page: Int,
        @Query("per_page") perPage: Int = PER_PAGE
    ): PexelsSearchResponse

    companion object {
        const val PER_PAGE = 30

        fun create(): PexelsApi = Retrofit.Builder()
            .baseUrl("https://api.pexels.com/")
            .addConverterFactory(MoshiConverterFactory.create())
            .build()
            .create(PexelsApi::class.java)
    }
}
