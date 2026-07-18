package com.mamba.picme.data.indexing

import android.location.Address
import com.mamba.picme.core.common.Logger
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
     * @param latitude 纬度
     * @param longitude 经度
     * @param locationName 逆地理编码后的人类可读地名（用于 POI 字段）
     */
    suspend fun updateIndex(
        mediaId: Long,
        latitude: Double?,
        longitude: Double?,
        locationName: String? = null,
        address: Address? = null
    ) {
        locationDao.clearLocationsForMedia(mediaId)
        if (latitude == null || longitude == null) return

        try {
            // 按坐标去重：同一位置只存一份层级信息
            val existingLoc = locationDao.findByCoordinate(latitude, longitude)
            val locationId: Long = if (existingLoc != null) {
                existingLoc.locationId
            } else {
                locationDao.insertLocation(
                    LocationHierarchyEntity(
                        country = address?.countryName,
                        province = address?.adminArea,
                        city = address?.locality,
                        district = address?.subLocality,
                        poi = address?.featureName ?: locationName,
                        latitude = roundCoordinate(latitude),
                        longitude = roundCoordinate(longitude)
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
