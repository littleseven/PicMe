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
    RelationPredicate.OTHER -> R.string.person_relation_other
}
