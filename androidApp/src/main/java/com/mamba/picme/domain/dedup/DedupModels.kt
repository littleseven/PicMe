package com.mamba.picme.domain.dedup

enum class DedupLevel { EXACT, VISUAL, SCENE }

/**
 * 内容类型（spec §10.2）：取数阶段顺带识别，零额外推理。
 * 优先级 SCREENSHOT > DOCUMENT > PORTRAIT > GENERAL；TAG 未覆盖照片一律 GENERAL（退化原则）。
 */
enum class DedupContentType { SCREENSHOT, DOCUMENT, PORTRAIT, GENERAL }

enum class VersionRole { ORIGINAL, EDITED, COMPRESSED, UNKNOWN }

enum class KeepPolicy { BEST_QUALITY, ORIGINAL, EDITED, LATEST }

data class DedupMember(
    val uri: String,
    val sizeBytes: Long,
    val mime: String,
    val captureDate: Long,
    val modifiedAt: Long,
    val pixelArea: Int,
    val aestheticScore: Float?,
    val role: VersionRole,
    val md5: String?,
    val phash: Long?,
    val contentType: DedupContentType = DedupContentType.GENERAL,
    /** eDifFIQA 人脸质量分（TAG 产出，null=未评分）；人像组保留排序的 tiebreak。 */
    val faceQualityScore: Float? = null,
)

data class DedupGroup(
    val id: String,
    val level: DedupLevel,
    val members: List<DedupMember>,
    val keepUri: String,
    val userOverride: Boolean = false,
    val contentType: DedupContentType = DedupContentType.GENERAL,
    /**
     * 是否默认预选待删项（spec §10.3）：EXACT→true；VISUAL 的 SCREENSHOT/DOCUMENT→false；
     * SCENE→false。false 时 deleteUris 为空、不进批量 CTA；用户改选（userOverride=true）后
     * deleteUris 正常派生、可参与删除。
     */
    val autoPreselected: Boolean = true,
) {
    init {
        require(members.isEmpty() || members.any { member -> member.uri == keepUri }) {
            "keepUri must be one of members' uris"
        }
    }

    val deleteUris: List<String>
        get() = if (!autoPreselected && !userOverride) {
            emptyList()
        } else {
            members.map { member -> member.uri }.filter { uri -> uri != keepUri }
        }
    val reclaimBytes: Long
        get() = if (!autoPreselected && !userOverride) {
            0L
        } else {
            members.filter { member -> member.uri != keepUri }.sumOf { member -> member.sizeBytes }
        }

    /** 批量操作参与口径：SCENE 逐组确认不参与；未预选组仅在用户改选后参与。 */
    val batchEligible: Boolean
        get() = level != DedupLevel.SCENE && (autoPreselected || userOverride)

    companion object {
        fun stableId(level: DedupLevel, uris: List<String>): String =
            level.name.lowercase() + ":" + uris.sorted().joinToString("|").hashCode().toString(36)
    }
}

data class DedupScanConfig(
    val levels: Set<DedupLevel> = setOf(DedupLevel.EXACT, DedupLevel.VISUAL),
    val visualThreshold: Int = 5,
    /** SCREENSHOT 桶的收紧视觉阈值（spec §10.3：截图视觉相似误报率高，仅 ≤3 成组）。 */
    val screenshotVisualThreshold: Int = 3,
    val sceneThreshold: Int = 8,
    val sceneTimeWindowMs: Long = 10_000L,
)
