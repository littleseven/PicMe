package com.mamba.picme.domain.dedup

enum class DedupLevel { EXACT, VISUAL, SCENE }

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
)

data class DedupGroup(
    val id: String,
    val level: DedupLevel,
    val members: List<DedupMember>,
    val keepUri: String,
    val userOverride: Boolean = false,
) {
    val deleteUris: List<String> get() = members.map { member -> member.uri }.filter { uri -> uri != keepUri }
    val reclaimBytes: Long get() = members.filter { member -> member.uri != keepUri }.sumOf { member -> member.sizeBytes }

    companion object {
        fun stableId(level: DedupLevel, uris: List<String>): String =
            level.name.lowercase() + ":" + uris.sorted().joinToString("|").hashCode().toString(36)
    }
}

data class DedupScanConfig(
    val levels: Set<DedupLevel> = setOf(DedupLevel.EXACT, DedupLevel.VISUAL),
    val visualThreshold: Int = 5,
    val sceneThreshold: Int = 8,
    val sceneTimeWindowMs: Long = 10_000L,
)
