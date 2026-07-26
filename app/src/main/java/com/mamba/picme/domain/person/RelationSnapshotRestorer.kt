package com.mamba.picme.domain.person

/**
 * 人物关系快照条目 —— 重聚清表前按**名字 + isSelf 标记**记录一条关系
 *
 * 不记 personId（重聚后可能失效）：SELF 端以 isSelf 为准，非 SELF 端以名字为准。
 * [predicate] / [source] 为枚举名字符串（与 person_relations 表存储一致）。
 */
data class RelationSnapshotEntry(
    val subjectName: String?,
    val subjectIsSelf: Boolean,
    val objectName: String?,
    val objectIsSelf: Boolean,
    val predicate: String,
    val source: String,
    val customLabel: String? = null
)

/**
 * 已解析到重聚后 personId 的关系（可写回 person_relations）
 */
data class ResolvedRelation(
    val subjectPersonId: Long,
    val objectPersonId: Long,
    val predicate: String,
    val source: String,
    val customLabel: String? = null
)

/**
 * 恢复计划：可写回的关系 + 无法解析被丢弃的快照
 */
data class RelationRestorePlan(
    val restored: List<ResolvedRelation>,
    val dropped: List<RelationSnapshotEntry>
)

/**
 * 关系快照 → 恢复计划的纯函数映射（无 IO，便于单测）
 *
 * [resolvePersonId] 由调用方提供（查重聚后的 persons 表）：
 * - isSelf = true → 当前 is_self = 1 的人物（以标记为准，不依赖名字）
 * - 否则按名字精确匹配；查不到返回 null（该端人物消失 → 丢弃并打日志）
 */
object RelationSnapshotRestorer {

    fun buildRestorePlan(
        snapshots: List<RelationSnapshotEntry>,
        resolvePersonId: (name: String?, isSelf: Boolean) -> Long?
    ): RelationRestorePlan {
        val restored = mutableListOf<ResolvedRelation>()
        val dropped = mutableListOf<RelationSnapshotEntry>()

        for (entry in snapshots) {
            val subjectId = resolvePersonId(entry.subjectName, entry.subjectIsSelf)
            val objectId = resolvePersonId(entry.objectName, entry.objectIsSelf)
            if (subjectId == null || objectId == null) {
                dropped.add(entry)
            } else {
                restored.add(
                    ResolvedRelation(
                        subjectPersonId = subjectId,
                        objectPersonId = objectId,
                        predicate = entry.predicate,
                        source = entry.source,
                        customLabel = entry.customLabel
                    )
                )
            }
        }

        return RelationRestorePlan(restored = restored, dropped = dropped)
    }
}
