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
}
