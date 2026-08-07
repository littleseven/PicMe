package com.mamba.picme.features.debug.pexels

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class PexelsPhoto(
    val id: Long,
    val width: Int = 0,
    val height: Int = 0,
    val photographer: String = "",
    val alt: String = "",
    val src: PexelsSrc
)

@JsonClass(generateAdapter = true)
data class PexelsSrc(
    val original: String = "",
    @Json(name = "large2x") val large2x: String = "",
    val large: String = "",
    val medium: String = "",
    val small: String = ""
)

@JsonClass(generateAdapter = true)
data class PexelsSearchResponse(
    val photos: List<PexelsPhoto> = emptyList(),
    val page: Int = 1,
    @Json(name = "per_page") val perPage: Int = 0,
    @Json(name = "total_results") val totalResults: Int = 0,
    @Json(name = "next_page") val nextPage: String? = null
)
