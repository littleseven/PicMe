package com.mamba.picme.data.indexing.geo

/** 离线行政区划质心（地名库一行）。 */
data class Centroid(
    val province: String,
    val city: String,
    val district: String,
    val lat: Double,
    val lon: Double
)
