package com.mamba.picme.domain.person

/**
 * 跨端 UI 消费 [RelationPredicate] 的友好门面（Swift/Kotlin 共用）。
 *
 * iOS 侧经 SharedKit 消费本对象的数据类 [RelationOption]，避免直接操作 Kotlin enum
 * 带来的 K/N 互操作差异；谓词的单一事实来源仍是 [RelationPredicate]。
 */
data class RelationOption(
    val id: String,
    val labelZh: String,
    val labelEn: String,
    val labelJa: String,
    /** 是否为亲属类谓词（用于 UI 分组：亲属 / 非亲属） */
    val isFamily: Boolean,
)

object PersonRelationSupport {
    /** 亲属谓词族（用于 [RelationOption.isFamily] 判定，与 KinshipLexicon 谓词族对齐） */
    private val FAMILY_PREDICATES: Set<RelationPredicate> = setOf(
        RelationPredicate.SPOUSE,
        RelationPredicate.PARTNER,
        RelationPredicate.CHILD,
        RelationPredicate.SON,
        RelationPredicate.DAUGHTER,
        RelationPredicate.PARENT,
        RelationPredicate.FATHER,
        RelationPredicate.MOTHER,
        RelationPredicate.SIBLING,
        RelationPredicate.ELDER_BROTHER,
        RelationPredicate.ELDER_SISTER,
        RelationPredicate.YOUNGER_BROTHER,
        RelationPredicate.YOUNGER_SISTER,
        RelationPredicate.GRANDPARENT,
        RelationPredicate.GRANDFATHER,
        RelationPredicate.GRANDMOTHER,
        RelationPredicate.GRANDCHILD,
        RelationPredicate.OTHER_FAMILY,
    )

    /** 全部关系选项（UI 选择器渲染用，顺序即枚举顺序） */
    fun allOptions(): List<RelationOption> =
        RelationPredicate.values().map { predicate ->
            RelationOption(
                id = predicate.name,
                labelZh = predicate.labelZh,
                labelEn = predicate.labelEn,
                labelJa = predicate.labelJa,
                isFamily = predicate in FAMILY_PREDICATES,
            )
        }

    /** 谓词 id → 中文标签（关系 chip 兜底展示） */
    fun labelZh(id: String): String? = RelationPredicate.fromStored(id)?.labelZh

    /** 谓词 id → 英文标签 */
    fun labelEn(id: String): String? = RelationPredicate.fromStored(id)?.labelEn

    /** 谓词 id 是否合法（存储写入前校验） */
    fun isValid(id: String): Boolean = RelationPredicate.fromStored(id) != null
}
