package com.mamba.picme.features.person.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
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
import com.mamba.picme.domain.person.RelationDisplayItem
import com.mamba.picme.domain.person.RelationPredicate
import com.mamba.picme.features.common.PersonRelationPicker

/**
 * 人物信息编辑 Bottom Sheet。
 *
 * 使用 [ModalBottomSheet] 并强制展开到最大高度；内容区可滚动，
 * 保存按钮固定在底部，确保所有选项和保存操作始终可见。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonInfoSheet(
    relation: RelationDisplayItem?,
    isSelf: Boolean,
    onSave: (RelationPredicate?, String, Boolean) -> Unit,
    onDismiss: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )
) {
    var currentRelation by remember(relation) {
        mutableStateOf(relation?.predicate)
    }
    var customLabel by remember(relation) {
        mutableStateOf(relation?.customLabel.orEmpty())
    }
    var currentIsSelf by remember(isSelf) { mutableStateOf(isSelf) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(modifier = Modifier.fillMaxHeight(0.92f)) {
            TopAppBar(
                title = { Text(stringResource(R.string.person_info_title)) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                PersonRelationPicker(
                    selectedPredicate = currentRelation,
                    customLabel = customLabel,
                    onPredicateChange = { currentRelation = it },
                    onCustomLabelChange = { customLabel = it },
                    modifier = Modifier.fillMaxWidth()
                )
                HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.person_is_self),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = currentIsSelf,
                        onCheckedChange = { currentIsSelf = it }
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 8.dp, bottom = 24.dp)
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
                Row(modifier = Modifier.weight(1f)) {}
                Button(
                    onClick = {
                        val effectiveRelation = if (customLabel.isNotBlank()) {
                            RelationPredicate.OTHER
                        } else {
                            currentRelation
                        }
                        onSave(effectiveRelation, customLabel, currentIsSelf)
                        onDismiss()
                    }
                ) {
                    Text(stringResource(R.string.save))
                }
            }
        }
    }
}
