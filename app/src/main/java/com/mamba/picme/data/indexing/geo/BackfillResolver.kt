package com.mamba.picme.data.indexing.geo

/**
 * 位置回填纯逻辑：用离线结果派生 (city, locationName)。
 *
 * @return (city, 规范层级串 locationName)；坐标缺失或离线无命中返回 null
 */
object BackfillResolver {
    fun resolve(
        latitude: Double?,
        longitude: Double?,
        offline: OfflineGeocoder
    ): Pair<String?, String?>? {
        val lat = latitude ?: return null
        val lon = longitude ?: return null
        val resolved = offline.lookup(lat, lon) ?: return null
        return resolved.city to resolved.toDisplayString()
    }
}
