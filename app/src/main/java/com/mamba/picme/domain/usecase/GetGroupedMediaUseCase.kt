package com.mamba.picme.domain.usecase

import com.mamba.picme.domain.model.GroupTitleType
import com.mamba.picme.domain.model.GroupedMedia
import com.mamba.picme.domain.model.GroupingMode
import android.util.Log
import com.mamba.picme.agent.core.model.context.MediaAsset
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GetGroupedMediaUseCase {

    companion object {
        private const val TAG = "GetGroupedMediaUseCase"
    }

    operator fun invoke(media: List<MediaAsset>, mode: GroupingMode): List<GroupedMedia> {
        return when (mode) {
            GroupingMode.NONE -> listOf(
                GroupedMedia(
                    titleType = GroupTitleType.NONE,
                    titleValue = "",
                    items = media
                )
            )

            GroupingMode.DATE -> {
                val dateFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                media.groupBy { mediaItem -> dateFormatter.format(Date(mediaItem.captureDate)) }
                    .map { entry ->
                        GroupedMedia(
                            titleType = GroupTitleType.DATE,
                            titleValue = entry.key,
                            items = entry.value
                        )
                    }
            }

            GroupingMode.FACE -> {
                val withFaces = media.filter { mediaItem -> mediaItem.hasFace }
                val noFaces = media.filter { mediaItem -> !mediaItem.hasFace }

                listOf(
                    GroupedMedia(
                        titleType = GroupTitleType.WITH_FACES,
                        titleValue = "",
                        items = withFaces
                    ),
                    GroupedMedia(
                        titleType = GroupTitleType.NO_FACES,
                        titleValue = "",
                        items = noFaces
                    )
                ).filter { group -> group.items.isNotEmpty() }
            }

            GroupingMode.PERSON -> {
                media.filter { mediaItem -> mediaItem.hasFace && mediaItem.faceId != null }
                    .groupBy { mediaItem -> mediaItem.faceId ?: "" }
                    .map { entry ->
                        GroupedMedia(
                            titleType = GroupTitleType.PERSON,
                            titleValue = entry.key,
                            items = entry.value
                        )
                    }
            }

            GroupingMode.LANDSCAPE -> {
                val landscapes = media.filter { mediaItem ->
                    mediaItem.labels?.let { extractLabels(it).matchesLandscape() } ?: false
                }
                if (landscapes.isNotEmpty()) {
                    listOf(
                        GroupedMedia(
                            titleType = GroupTitleType.LANDSCAPE,
                            titleValue = "",
                            items = landscapes
                        )
                    )
                } else {
                    emptyList()
                }
            }

            GroupingMode.SWIMWEAR -> {
                val swimwearItems = media.filter { mediaItem ->
                    mediaItem.labels?.let { extractLabels(it).matchesSwimwear() } ?: false
                }
                if (swimwearItems.isNotEmpty()) {
                    listOf(
                        GroupedMedia(
                            titleType = GroupTitleType.SWIMWEAR,
                            titleValue = "",
                            items = swimwearItems
                        )
                    )
                } else {
                    emptyList()
                }
            }

            GroupingMode.SEXY -> {
                val sexyItems = media.filter { mediaItem ->
                    mediaItem.labels?.let { extractLabels(it).matchesSexy() } ?: false
                }
                if (sexyItems.isNotEmpty()) {
                    listOf(
                        GroupedMedia(
                            titleType = GroupTitleType.SEXY,
                            titleValue = "",
                            items = sexyItems
                        )
                    )
                } else {
                    emptyList()
                }
            }
        }
    }

    /**
     * 从 labels JSON 中提取所有用于分组的文本标签。
     *
     * labels 格式：{"scene":"...","activity":"...","objects":["..."],"tags":["..."],"qwenSummary":"..."}
     */
    private fun extractLabels(labelsJson: String): GroupingLabels {
        return try {
            val root = JSONObject(labelsJson)
            val scene = root.optString("scene", "")
            val activity = root.optString("activity", "")
            val objects = root.optJSONArray("objects")?.toStringList() ?: emptyList()
            val tags = root.optJSONArray("tags")?.toStringList() ?: emptyList()
            GroupingLabels(scene, activity, objects, tags)
        } catch (e: JSONException) {
            Log.w(TAG, "Failed to parse labels JSON: $labelsJson", e)
            GroupingLabels.EMPTY
        }
    }

    private fun JSONArray.toStringList(): List<String> {
        return (0 until length()).map { getString(it) }
    }

    private data class GroupingLabels(
        val scene: String,
        val activity: String,
        val objects: List<String>,
        val tags: List<String>
    ) {
        /** 所有可搜索文本标签（小写） */
        val allLabels: List<String> by lazy {
            listOf(scene, activity).filter { it.isNotBlank() } + objects + tags
        }

        val allLabelsLowercase: Set<String> by lazy {
            allLabels.map { it.lowercase(Locale.getDefault()) }.toSet()
        }

        fun matchesLandscape(): Boolean {
            return LANDSCAPE_SCENES.any { it in allLabelsLowercase }
        }

        fun matchesSwimwear(): Boolean {
            return SWIMWEAR_LABELS.any { it in allLabelsLowercase }
        }

        fun matchesSexy(): Boolean {
            return SEXY_LABELS.any { it in allLabelsLowercase }
        }

        companion object {
            val EMPTY = GroupingLabels("", "", emptyList(), emptyList())

            /**
             * 风景相关 scene 标签集合。
             *
             * 包含通用风景词以及 controlled_vocab.json 中所有自然/城市/户外场景词。
             */
            private val LANDSCAPE_SCENES = setOf(
                "风景",
                "户外",
                "公园",
                "街道",
                "海边",
                "山脉",
                "城市",
                "乡村",
                "花园",
                "阳台",
                "河边",
                "森林",
                "雪地",
                "沙漠",
                "泳池",
                "田野",
                "草原",
                "湖边",
                "瀑布",
                "隧道",
                "桥下",
                "天桥",
                "花田",
                "竹林",
                "枫林",
                "茶园",
                "古镇",
                "寺庙",
                "教堂",
                "植物园",
                "动物园",
                "游乐园",
                "水族馆",
                "广场",
                "海滩",
                "庭院",
                "操场",
                "农家",
                "landscape",
                "outdoor",
                "park",
                "street",
                "seaside",
                "mountains",
                "city",
                "countryside",
                "garden",
                "balcony",
                "riverside",
                "forest",
                "snowfield",
                "desert",
                "swimming pool",
                "field",
                "grassland",
                "lakeside",
                "waterfall",
                "tunnel",
                "under bridge",
                "overpass",
                "flower field",
                "bamboo forest",
                "maple forest",
                "tea plantation",
                "ancient town",
                "temple",
                "church",
                "botanical garden",
                "zoo",
                "amusement park",
                "aquarium",
                "square",
                "beach",
                "courtyard",
                "playground",
                "farmhouse"
            )

            private val SWIMWEAR_LABELS = setOf(
                "泳衣", "比基尼", "泳装",
                "swimsuit", "bikini", "swimwear"
            )

            private val SEXY_LABELS = setOf(
                "性感", "sexy"
            )
        }
    }
}
