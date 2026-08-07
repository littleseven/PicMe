package com.mamba.picme.data.indexing.geo

/**
 * 逆地理编码统一结果：系统 Geocoder 与离线质心库都产出此类型。
 */
data class ResolvedLocation(
    val country: String? = null,
    val province: String? = null,
    val city: String? = null,
    val district: String? = null,
    val poi: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null
) {
    /**
     * 规范层级展示串（如「北京市 海淀区 中关村」）。
     * [distinct] 去掉直辖市 province==city 的重复；全空返回 null。
     */
    fun toDisplayString(): String? =
        listOfNotNull(province, city, district, poi)
            .takeIf { it.isNotEmpty() }
            ?.distinct()
            ?.joinToString(" ")
}
