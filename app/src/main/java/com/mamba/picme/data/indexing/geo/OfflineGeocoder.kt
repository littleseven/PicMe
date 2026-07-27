package com.mamba.picme.data.indexing.geo

import android.content.Context
import com.mamba.picme.core.common.Logger
import org.json.JSONArray

/**
 * 端侧离线逆地理编码：经纬度 → 最近行政区划质心 → [ResolvedLocation]。
 */
class OfflineGeocoder(private val index: AdminCentroidIndex) {

    /** 最近邻匹配；库空或无命中返回 null。 */
    fun lookup(lat: Double, lon: Double): ResolvedLocation? =
        index.nearest(lat, lon)?.let { c ->
            ResolvedLocation(
                province = c.province,
                city = c.city,
                district = c.district,
                latitude = lat,
                longitude = lon
            )
        }

    companion object {
        private const val TAG = "PoLang:OfflineGeocoder"
        private const val ASSET = "geo/admin_centroids_zh.json"

        /** 从 assets 装载；资产缺失/损坏返回空库（lookup 恒为 null）。 */
        fun fromAssets(context: Context): OfflineGeocoder {
            val centroids = try {
                context.assets.open(ASSET).use { stream ->
                    parseCentroids(stream.bufferedReader().readText())
                }
            } catch (e: Exception) {
                Logger.w(TAG, "offline centroid asset unavailable: ${e.message}")
                emptyList()
            }
            return OfflineGeocoder(AdminCentroidIndex(centroids))
        }

        /** 解析 JSON 数组为质心列表（纯函数，便于 JVM 单测）。 */
        fun parseCentroids(json: String): List<Centroid> {
            val arr = JSONArray(json)
            return buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        Centroid(
                            province = o.getString("province"),
                            city = o.getString("city"),
                            district = o.getString("district"),
                            lat = o.getDouble("lat"),
                            lon = o.getDouble("lon")
                        )
                    )
                }
            }
        }
    }
}
