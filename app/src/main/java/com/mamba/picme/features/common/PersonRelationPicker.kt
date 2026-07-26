package com.mamba.picme.features.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mamba.picme.R
import com.mamba.picme.domain.person.RelationPredicate

/**
 * 人物关系选择器（公共组件）—— 两层关系模型的统一编辑入口
 *
 * 组成：
 * - 关系快捷 chips（FlowRow 流式布局，分「家庭」/「社会」两组）：选中即写入具体谓词
 * - "自定义"输入框：填了则以输入为准（写入 customLabel，谓词由调用方记 OTHER）
 * - "不设置"：清除谓词与自定义称呼
 *
 * 无状态组件：选择状态由调用方持有（[selectedPredicate] / [customLabel]），
 * 变更通过 [onPredicateChange] / [onCustomLabelChange] 回调。
 * Gallery 人物编辑对话框与「AI 记忆」页关系编辑共用。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PersonRelationPicker(
    selectedPredicate: RelationPredicate?,
    customLabel: String,
    onPredicateChange: (RelationPredicate?) -> Unit,
    onCustomLabelChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // 自定义称呼非空时以其为准，chips 全部不选中
    val customActive = customLabel.isNotBlank()

    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.person_relation_label),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        RelationChipGroup(
            titleRes = R.string.person_relation_group_family,
            predicates = FAMILY_RELATIONS,
            selectedPredicate = selectedPredicate,
            customActive = customActive,
            onPredicateChange = onPredicateChange,
            onCustomLabelChange = onCustomLabelChange
        )
        RelationChipGroup(
            titleRes = R.string.person_relation_group_social,
            predicates = SOCIAL_RELATIONS,
            selectedPredicate = selectedPredicate,
            customActive = customActive,
            onPredicateChange = onPredicateChange,
            onCustomLabelChange = onCustomLabelChange
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = !customActive && selectedPredicate == null,
                onClick = {
                    onCustomLabelChange("")
                    onPredicateChange(null)
                },
                label = { Text(stringResource(R.string.person_relation_none)) }
            )
        }
        OutlinedTextField(
            value = customLabel,
            onValueChange = onCustomLabelChange,
            label = { Text(stringResource(R.string.person_relation_custom_label)) },
            placeholder = { Text(stringResource(R.string.person_relation_custom_hint)) },
            supportingText = {
                Text(stringResource(R.string.person_relation_custom_supporting))
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        )
    }
}

/** 一组关系 chips（分组小标题 + FlowRow） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RelationChipGroup(
    titleRes: Int,
    predicates: List<RelationPredicate>,
    selectedPredicate: RelationPredicate?,
    customActive: Boolean,
    onPredicateChange: (RelationPredicate?) -> Unit,
    onCustomLabelChange: (String) -> Unit
) {
    Text(
        text = stringResource(titleRes),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp)
    )
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        predicates.forEach { predicate ->
            FilterChip(
                selected = !customActive && selectedPredicate == predicate,
                onClick = {
                    onCustomLabelChange("")
                    onPredicateChange(predicate)
                },
                label = { Text(stringResource(personRelationLabelRes(predicate))) }
            )
        }
    }
}

/** 家庭区 chips（具体谓词，选择即写具体值） */
private val FAMILY_RELATIONS = listOf(
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

/** 社会区 chips */
private val SOCIAL_RELATIONS = listOf(
    RelationPredicate.FRIEND,
    RelationPredicate.CLASSMATE,
    RelationPredicate.COLLEAGUE
)
