package com.mamba.picme.features.person.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.mamba.picme.R
import com.mamba.picme.core.image.faceAwareVerticalAlignment
import com.mamba.picme.data.local.entity.PersonEntity
import com.mamba.picme.domain.person.RelationDisplayItem
import com.mamba.picme.features.common.personRelationLabelRes
import com.mamba.picme.features.person.PersonCover

@Composable
fun PersonListItem(
    person: PersonEntity,
    cover: PersonCover?,
    relation: RelationDisplayItem?,
    isEditingName: Boolean,
    onCoverClick: () -> Unit,
    onNameClick: () -> Unit,
    onNameSave: (String) -> Unit,
    onNameCancel: () -> Unit,
    onInfoClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(96.dp)
    ) {
        CoverThumbnail(
            cover = cover,
            contentDescription = person.name ?: stringResource(R.string.people_default_name, person.personId),
            onClick = onCoverClick
        )
        Spacer(modifier = Modifier.width(16.dp))
        InfoColumn(
            person = person,
            relation = relation,
            isEditingName = isEditingName,
            onNameClick = onNameClick,
            onNameSave = onNameSave,
            onNameCancel = onNameCancel,
            onInfoClick = onInfoClick,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onInfoClick) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.person_info_title)
            )
        }
    }
}

@Composable
private fun CoverThumbnail(
    cover: PersonCover?,
    contentDescription: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(76.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        val uri = cover?.coverUri
        if (uri != null) {
            AsyncImage(
                model = uri,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                alignment = faceAwareVerticalAlignment(cover.faceFocusY),
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
        }
    }
}

@Composable
private fun InfoColumn(
    person: PersonEntity,
    relation: RelationDisplayItem?,
    isEditingName: Boolean,
    onNameClick: () -> Unit,
    onNameSave: (String) -> Unit,
    onNameCancel: () -> Unit,
    onInfoClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        if (isEditingName) {
            NameEditor(
                initialName = person.name.orEmpty(),
                onSave = onNameSave,
                onCancel = onNameCancel
            )
        } else {
            val hasName = !person.name.isNullOrBlank()
            Text(
                text = if (hasName) person.name!! else stringResource(R.string.person_edit_name_hint),
                color = if (hasName) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(onClick = onNameClick)
            )
        }
        Text(
            text = stringResource(R.string.people_photos_count, person.faceCount),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
        if (relation != null) {
            val label = relation.customLabel
                ?: stringResource(personRelationLabelRes(relation.predicate))
            AssistChip(
                onClick = onInfoClick,
                label = { Text(label, fontSize = 12.sp) },
                colors = if (person.isSelf) {
                    AssistChipDefaults.assistChipColors(
                        labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                } else {
                    AssistChipDefaults.assistChipColors()
                },
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun NameEditor(
    initialName: String,
    onSave: (String) -> Unit,
    onCancel: () -> Unit
) {
    var text by remember(initialName) { mutableStateOf(initialName) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onSave(text) }),
        trailingIcon = {
            Row {
                IconButton(onClick = { onSave(text) }) {
                    Icon(Icons.Default.Check, contentDescription = stringResource(R.string.save))
                }
                IconButton(onClick = onCancel) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
                }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
    )
}
