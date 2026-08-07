package com.mamba.picme.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 层级地理实体 —— 行政区划 + POI
 *
 * 从 Android Geocoder 逆向地理编码结果中提取 country/province/city/district/poi。
 * 相同坐标（4 位小数精度）去重，[latitude] 和 [longitude] 仅用于去重和显示。
 */
@Entity(
    tableName = "location_hierarchy",
    indices = [
        Index("city"),
        Index("province")
    ]
)
data class LocationHierarchyEntity(
    @PrimaryKey(autoGenerate = true)
    val locationId: Long = 0,
    val country: String? = null,
    val province: String? = null,
    val city: String? = null,
    val district: String? = null,
    val poi: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null
)
