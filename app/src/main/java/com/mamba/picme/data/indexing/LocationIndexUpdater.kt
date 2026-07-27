package com.mamba.picme.data.indexing

import com.mamba.picme.core.common.Logger
import com.mamba.picme.data.indexing.geo.ResolvedLocation
import com.mamba.picme.data.local.dao.LocationDao
import com.mamba.picme.data.local.entity.LocationHierarchyEntity
import com.mamba.picme.data.local.entity.MediaLocationEntity

/**
 * 层级地理索引更新器
 *
 * 将经纬度 + 逆地理编码结果写入 [location_hierarchy] + [media_locations] 表。
 * 按坐标去重（4 位小数精度），避免重复存储相同位置的层级信息。
 */
class LocationIndexUpdater(private val locationDao: LocationDao) {

    companion object {
        private const val TAG = "PoLang:LocIndex"
        // 约 11m 精度，足以区分不同建筑物
        private const val COORDINATE_PRECISION = 0.0001
    }

    /**
     * 更新指定媒体的地理索引。
     *
     * @param mediaId 媒体 ID
     * @param resolved 逆地理编码结果（含省/市/区/POI 与坐标）；为 null 或无坐标则清空并返回
     */
    suspend fun updateIndex(
        mediaId: Long,
        resolved: ResolvedLocation?
    ) {
        locationDao.clearLocationsForMedia(mediaId)
        val lat = resolved?.latitude ?: return
        val lon = resolved.longitude ?: return

        try {
            // 按坐标去重：同一位置只存一份层级信息
            val existingLoc = locationDao.findByCoordinate(lat, lon)
            val locationId: Long = if (existingLoc != null) {
                existingLoc.locationId
            } else {
                locationDao.insertLocation(
                    LocationHierarchyEntity(
                        country = resolved.country,
                        province = resolved.province,
                        city = resolved.city,
                        district = resolved.district,
                        poi = resolved.poi,
                        latitude = roundCoordinate(lat),
                        longitude = roundCoordinate(lon)
                    )
                )
            }

            locationDao.insertMediaLocation(
                MediaLocationEntity(mediaId = mediaId, locationId = locationId)
            )
            Logger.d(TAG, "Location index updated for media $mediaId -> loc $locationId")
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to update location for media $mediaId: ${e.message}")
        }
    }

    /**
     * 将经纬度舍入到指定精度（4 位小数约 11 米），用于去重匹配。
     */
    private fun roundCoordinate(value: Double): Double {
        return kotlin.math.round(value / COORDINATE_PRECISION) * COORDINATE_PRECISION
    }
}
