package com.mamba.picme.features.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mamba.picme.R
import com.mamba.picme.domain.person.RelationPredicate

/**
 * 人物重命名/关系/自我标记公共对话框（纯 UI）。
 *
 * 相册「按人物分组」与「人物」页共用。落库由 [onConfirm] 回调收口
 * （调用方走 `PersonRepository.applyPersonEdit`）。
 *
 * @param initialName 当前名字（空 → 显示占位）
 * @param initialRelation 已有关系谓词
 * @param initialCustomLabel 已有自定义称呼
 * @param initialIsSelf 是否已是"我"
 */
@Composable
fun PersonRenameDialog(
    initialName: String,
    initialRelation: RelationPredicate?,
    initialCustomLabel: String,
    initialIsSelf: Boolean,
    onConfirm: (name: String, relation: RelationPredicate?, customLabel: String, isSelf: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var relation by remember { mutableStateOf(initialRelation) }
    var customLabel by remember { mutableStateOf(initialCustomLabel) }
    var isSelf by remember { mutableStateOf(initialIsSelf) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.person_edit_title)) },
        text = {
            // 内容可滚动：chips + 自定义输入在小屏上可能超高
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { value -> name = value },
                    label = { Text(stringResource(R.string.person_edit_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                // "TA 是我的…" 关系选择（快捷 chips + 自定义称呼，公共组件）
                PersonRelationPicker(
                    selectedPredicate = relation,
                    customLabel = customLabel,
                    onPredicateChange = { predicate -> relation = predicate },
                    onCustomLabelChange = { label -> customLabel = label },
                    modifier = Modifier.padding(top = 16.dp)
                )
                // "这是我" 开关（全局唯一，设置后旧标记自动清除）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.person_is_self),
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = isSelf,
                        onCheckedChange = { checked -> isSelf = checked }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val trimmedName = name.trim()
                    val trimmedCustom = customLabel.trim()
                    // 自定义称呼非空时以其为准（谓词记 OTHER）；否则用选中的谓词
                    val effectiveRelation = if (trimmedCustom.isNotEmpty()) {
                        RelationPredicate.OTHER
                    } else {
                        relation
                    }
                    onConfirm(trimmedName, effectiveRelation, trimmedCustom, isSelf)
                    onDismiss()
                }
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
