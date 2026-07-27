package com.mamba.picme.agent.core.model.context

/**
 * 媒体资源基础模型
 */
data class MediaAsset(
    val id: Long = 0,
    val uri: String,
    val type: MediaType,
    val captureDate: Long,
    val fileName: String,
    val duration: Long? = null,
    val hasFace: Boolean = false,
    val faceId: String? = null,
    val source: String? = null,
    // 元数据索引字段（自然语言搜索）
    val labels: String? = null,
    val ocrText: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationName: String? = null,
    val city: String? = null,
    val indexedAt: Long? = null
)

enum class MediaType {
    PHOTO,
    VIDEO,
    DOCUMENT
}
