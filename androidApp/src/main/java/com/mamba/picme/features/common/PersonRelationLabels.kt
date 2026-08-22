package com.mamba.picme.features.common

import com.mamba.picme.R
import com.mamba.picme.domain.person.RelationPredicate

/**
 * 关系谓词 → 三语 string 资源映射（UI 层映射，不用领域层内置标签，满足 [I18N]）。
 *
 * Gallery 命名对话框与设置页「AI 记忆」共用；非 @Composable，调用方自行 stringResource。
 */
fun personRelationLabelRes(predicate: RelationPredicate?): Int = when (predicate) {
    null -> R.string.person_relation_none
    RelationPredicate.SPOUSE -> R.string.person_relation_spouse
    RelationPredicate.PARTNER -> R.string.person_relation_partner
    RelationPredicate.CHILD -> R.string.person_relation_child
    RelationPredicate.SON -> R.string.person_relation_son
    RelationPredicate.DAUGHTER -> R.string.person_relation_daughter
    RelationPredicate.PARENT -> R.string.person_relation_parent
    RelationPredicate.FATHER -> R.string.person_relation_father
    RelationPredicate.MOTHER -> R.string.person_relation_mother
    RelationPredicate.SIBLING -> R.string.person_relation_sibling
    RelationPredicate.ELDER_BROTHER -> R.string.person_relation_elder_brother
    RelationPredicate.ELDER_SISTER -> R.string.person_relation_elder_sister
    RelationPredicate.YOUNGER_BROTHER -> R.string.person_relation_younger_brother
    RelationPredicate.YOUNGER_SISTER -> R.string.person_relation_younger_sister
    RelationPredicate.GRANDPARENT -> R.string.person_relation_grandparent
    RelationPredicate.GRANDFATHER -> R.string.person_relation_grandfather
    RelationPredicate.GRANDMOTHER -> R.string.person_relation_grandmother
    RelationPredicate.GRANDCHILD -> R.string.person_relation_grandchild
    RelationPredicate.OTHER_FAMILY -> R.string.person_relation_other_family
    RelationPredicate.FRIEND -> R.string.person_relation_friend
    RelationPredicate.CLASSMATE -> R.string.person_relation_classmate
    RelationPredicate.COLLEAGUE -> R.string.person_relation_colleague
    RelationPredicate.IDOL -> R.string.person_relation_idol
    RelationPredicate.OTHER -> R.string.person_relation_other
}

/** 家庭关系谓词（chips 分组与人物卡 chip 配色共用的单一事实来源） */
val FAMILY_RELATION_PREDICATES: List<RelationPredicate> = listOf(
    RelationPredicate.FATHER,
    RelationPredicate.MOTHER,
    RelationPredicate.SON,
    RelationPredicate.DAUGHTER,
    RelationPredicate.ELDER_BROTHER,
    RelationPredicate.ELDER_SISTER,
    RelationPredicate.YOUNGER_BROTHER,
    RelationPredicate.YOUNGER_SISTER,
    RelationPredicate.GRANDFATHER,
    RelationPredicate.GRANDMOTHER,
    RelationPredicate.SPOUSE,
    RelationPredicate.PARTNER
)

/** 社会关系谓词 */
val SOCIAL_RELATION_PREDICATES: List<RelationPredicate> = listOf(
    RelationPredicate.FRIEND,
    RelationPredicate.CLASSMATE,
    RelationPredicate.COLLEAGUE,
    RelationPredicate.IDOL
)

/** 是否家庭关系（含 PARENT/SIBLING 等不在快捷 chips 里的宽泛谓词） */
fun RelationPredicate.isFamilyRelation(): Boolean = when (this) {
    RelationPredicate.SPOUSE, RelationPredicate.PARTNER,
    RelationPredicate.CHILD, RelationPredicate.SON, RelationPredicate.DAUGHTER,
    RelationPredicate.PARENT, RelationPredicate.FATHER, RelationPredicate.MOTHER,
    RelationPredicate.SIBLING,
    RelationPredicate.ELDER_BROTHER, RelationPredicate.ELDER_SISTER,
    RelationPredicate.YOUNGER_BROTHER, RelationPredicate.YOUNGER_SISTER,
    RelationPredicate.GRANDPARENT, RelationPredicate.GRANDFATHER, RelationPredicate.GRANDMOTHER,
    RelationPredicate.GRANDCHILD, RelationPredicate.OTHER_FAMILY -> true
    RelationPredicate.FRIEND, RelationPredicate.CLASSMATE,
    RelationPredicate.COLLEAGUE, RelationPredicate.IDOL,
    RelationPredicate.OTHER -> false
}
