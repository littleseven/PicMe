package com.mamba.picme.data.indexing.geo

import kotlin.math.PI
import kotlin.math.cos

/**
 * 离线地名索引：暴力最近邻（~340 地级市，微秒级，无需 KD-tree）。
 * 纯 Kotlin、无 Android 依赖，便于 JVM 单测。
 */
class AdminCentroidIndex(private val centroids: List<Centroid>) {

    /** 距 (lat, lon) 最近的质心；库空返回 null。 */
    fun nearest(lat: Double, lon: Double): Centroid? {
        if (centroids.isEmpty()) return null
        var best = centroids[0]
        var bestD = distanceSq(lat, lon, best.lat, best.lon)
        for (i in 1 until centroids.size) {
            val c = centroids[i]
            val d = distanceSq(lat, lon, c.lat, c.lon)
            if (d < bestD) {
                bestD = d
                best = c
            }
        }
        return best
    }

    /** 等距矩形近似距离平方（省略常数因子，仅用于比大小）。 */
    private fun distanceSq(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val la1 = lat1 * PI / 180.0
        val la2 = lat2 * PI / 180.0
        val dLat = la2 - la1
        val dLon = (lon2 - lon1) * PI / 180.0 * cos((la1 + la2) / 2.0)
        return dLat * dLat + dLon * dLon
    }
}
