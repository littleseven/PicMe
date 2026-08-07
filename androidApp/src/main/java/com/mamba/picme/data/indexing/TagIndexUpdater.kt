package com.mamba.picme.data.indexing

import com.mamba.picme.core.common.Logger
import com.mamba.picme.data.local.dao.TagDao
import com.mamba.picme.data.local.entity.MediaTagCrossRef
import com.mamba.picme.data.local.entity.TagEntity
import org.json.JSONArray

/**
 * 场景标签索引更新器
 *
 * 将 ML Kit 图像标注的原始标签规范化写入 [tags] + [media_tag_cross_ref] 表。
 * 同时将标签按语义分类（scene/animal/object/food/person/other）。
 */
class TagIndexUpdater(private val tagDao: TagDao) {

    companion object {
        private const val TAG = "PoLang:TagIndex"

        // 简易标签分类映射（基于常见 ML Kit 标签）
        private val CATEGORY_MAP = mapOf(
            "animal" to setOf("猫", "狗", "鸟", "鱼", "马", "兔子", "仓鼠", "动物",
                "cat", "dog", "bird", "fish", "horse", "rabbit", "animal"),
            "food" to setOf("食物", "水果", "蔬菜", "肉", "饮料", "咖啡", "茶", "酒",
                "food", "fruit", "vegetable", "meat", "drink", "coffee", "tea"),
            "person" to setOf("人", "人物", "人脸", "肖像", "自拍", "合照",
                "person", "face", "portrait", "selfie"),
            "scene" to setOf("户外", "室内", "天空", "海洋", "山", "森林", "城市", "建筑",
                "街道", "公园", "海滩", "夜景", "日落", "日出",
                "outdoor", "indoor", "sky", "sea", "mountain", "forest",
                "city", "building", "street", "park", "beach", "night", "sunset"),
            "object" to setOf("车", "手机", "电脑", "书", "花", "植物", "家具",
                "car", "phone", "computer", "book", "flower", "plant", "furniture")
        )
    }

    /**
     * 更新指定媒体的标签索引。
     *
     * @param mediaId 媒体 ID
     * @param labelsJson JSON 数组格式的标签列表，如 ["猫","户外","食物"]
     */
    suspend fun updateIndex(mediaId: Long, labelsJson: String?) {
        tagDao.clearTagsForMedia(mediaId)
        if (labelsJson.isNullOrBlank()) return

        val rawLabels = try {
            val arr = JSONArray(labelsJson)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to parse labels JSON: $labelsJson")
            return
        }

        if (rawLabels.isEmpty()) return

        for (label in rawLabels) {
            if (label.isBlank()) continue
            try {
                val category = classifyTag(label)
                // 先查是否已存在，避免 INSERT OR IGNORE 返回 0 导致 FK 约束失败
                val existing = tagDao.getTagByName(label)
                val tagId = if (existing != null) {
                    existing.tagId
                } else {
                    tagDao.insertTag(TagEntity(name = label, category = category))
                }
                if (tagId > 0) {
                    tagDao.insertMediaTag(
                        MediaTagCrossRef(mediaId = mediaId, tagId = tagId)
                    )
                }
            } catch (e: Exception) {
                Logger.w(TAG, "Failed to insert tag '$label': ${e.message}")
            }
        }

        Logger.d(TAG, "Tag index updated: ${rawLabels.size} tags for media $mediaId")
    }

    /**
     * 根据标签文本推断分类
     */
    private fun classifyTag(tagName: String): String {
        for ((category, keywords) in CATEGORY_MAP) {
            if (keywords.any { kw -> tagName.contains(kw, ignoreCase = true) }) {
                return category
            }
        }
        return "other"
    }
}
