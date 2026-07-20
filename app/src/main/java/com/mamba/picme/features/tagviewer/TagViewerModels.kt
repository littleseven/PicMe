package com.mamba.picme.features.tagviewer

/** 从 labels JSON 解析出的人脸信息（对齐 FaceTagInfo） */
data class ParsedFaceInfo(
    val count: Int = 0,
    val selfie: Boolean = false,
    val groupPhoto: Boolean = false,
    val personIds: List<Long> = emptyList()
)

/** 从 labels JSON 解析出的单张照片标签（字段对齐 UnifiedTagResult） */
data class ParsedTags(
    val scene: String = "",
    val activity: String = "",
    val objects: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val summary: String = "",
    val face: ParsedFaceInfo? = null
)

/** 照片列表 Tab 的单行数据 */
data class PhotoTagsItem(
    val mediaId: Long,
    val uri: String,
    val fileName: String,
    val parsed: ParsedTags?,
    val rawJson: String,
    val feedbackType: String? = null
) {
    val hasLabels: Boolean get() = parsed != null
    val isDisliked: Boolean get() = feedbackType == "dislike"
}

/** 单个标签的聚合计数 */
data class TagCount(val label: String, val count: Int)

/** 按字段分组的聚合结果（标签聚合 Tab） */
data class TagAggregates(
    val scenes: List<TagCount>,
    val objects: List<TagCount>,
    val tags: List<TagCount>
)

/** TagViewer 页面状态 */
sealed interface TagViewerUiState {
    data object Loading : TagViewerUiState
    data class Ready(
        val photos: List<PhotoTagsItem>,
        val filteredPhotos: List<PhotoTagsItem>,
        val aggregates: TagAggregates,
        val showOnlyDisliked: Boolean = false
    ) : TagViewerUiState
    data class Error(val message: String) : TagViewerUiState
}
