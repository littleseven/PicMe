package com.mamba.picme.features.person.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonInfoSheet(
    relation: RelationDisplayItem?,
    isSelf: Boolean,
    onSave: (RelationPredicate?, String, Boolean) -> Unit,
    onDismiss: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState()
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = stringResource(R.string.person_info_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            PersonRelationPicker(
                selectedPredicate = currentRelation,
                customLabel = customLabel,
                onPredicateChange = { currentRelation = it },
                onCustomLabelChange = { customLabel = it },
                modifier = Modifier.fillMaxWidth()
            )
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
                    checked = currentIsSelf,
                    onCheckedChange = { currentIsSelf = it }
                )
            }
            Button(
                onClick = {
                    val effectiveRelation = if (customLabel.isNotBlank()) {
                        RelationPredicate.OTHER
                    } else {
                        currentRelation
                    }
                    onSave(effectiveRelation, customLabel, currentIsSelf)
                    onDismiss()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp)
            ) {
                Text(stringResource(R.string.save))
            }
        }
    }
}
