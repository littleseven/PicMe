package com.mamba.picme.features.person

import com.mamba.picme.data.local.entity.PersonEntity

/** 人物封面解析结果：封面 uri + 人脸纵向聚焦点（供 faceAwareVerticalAlignment）。 */
data class PersonCover(val coverUri: String?, val faceFocusY: Float?)

/**
 * 纯映射：persons × (coverMediaId→uri) × (coverMediaId→faceFocusY) → personId→[PersonCover]。
 * 便于 JVM 单测；DB 读取在 [PersonViewModel] 中完成。
 */
object PersonCoverResolver {
    fun resolve(
        persons: List<PersonEntity>,
        uriByMediaId: Map<Long, String>,
        focusYByMediaId: Map<Long, Float?>
    ): Map<Long, PersonCover> {
        return persons.associate { person ->
            val mid = person.coverMediaId
            person.personId to PersonCover(
                coverUri = mid?.let { uriByMediaId[it] },
                faceFocusY = mid?.let { focusYByMediaId[it] }
            )
        }
    }

    /**
     * 过滤出封面可解析（coverUri 非空）的人物。
     *
     * 人物页不展示封面媒体已删/缺失的聚类（否则渲染空白格）。
     * reconcile 会先行清理，此处为兜底；逻辑下沉为纯函数便于 JVM 单测。
     */
    fun filterCoverable(
        persons: List<PersonEntity>,
        covers: Map<Long, PersonCover>
    ): List<PersonEntity> = persons.filter { person -> covers[person.personId]?.coverUri != null }
}
