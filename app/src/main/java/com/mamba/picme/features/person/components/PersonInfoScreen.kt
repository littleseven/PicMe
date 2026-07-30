package com.mamba.picme.features.person.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.mamba.picme.R
import com.mamba.picme.core.image.faceAwareVerticalAlignment
import com.mamba.picme.data.local.entity.PersonEntity
import com.mamba.picme.data.model.MediaEntity
import com.mamba.picme.domain.person.RelationDisplayItem
import com.mamba.picme.domain.person.RelationPredicate
import com.mamba.picme.features.common.PersonRelationPicker
import com.mamba.picme.features.person.PersonCover

/**
 * 人物信息编辑全屏页。
 *
 * 顶部显示封面、名字与簇 ID；点击封面进入封面选择 Sheet；
 * 下方编辑关系与「我」标记；底部固定取消/保存。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonInfoScreen(
    person: PersonEntity,
    relation: RelationDisplayItem?,
    cover: PersonCover?,
    photos: List<MediaEntity>,
    onSave: (RelationPredicate?, String, Boolean) -> Unit,
    onNavigateBack: () -> Unit,
    onUpdateCover: (MediaEntity) -> Unit
) {
    var currentRelation by remember(relation) {
        mutableStateOf(relation?.predicate)
    }
    var customLabel by remember(relation) {
        mutableStateOf(relation?.customLabel.orEmpty())
    }
    var currentIsSelf by remember(person.isSelf) { mutableStateOf(person.isSelf) }
    var showCoverPicker by remember { mutableStateOf(false) }

    BackHandler(onBack = onNavigateBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.person_info_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
        ) {
            // 头部：封面 + 名字 + 簇 ID
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                CoverHeader(
                    cover = cover,
                    contentDescription = person.name ?: stringResource(
                        R.string.people_default_name,
                        person.personId
                    ),
                    onClick = { showCoverPicker = true }
                )
                Text(
                    text = person.name?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.person_edit_name_hint),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 12.dp)
                )
                Text(
                    text = stringResource(R.string.person_cluster_id, person.personId),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // 关系编辑
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                PersonRelationPicker(
                    selectedPredicate = currentRelation,
                    customLabel = customLabel,
                    onPredicateChange = { currentRelation = it },
                    onCustomLabelChange = { customLabel = it },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // 「我」标记
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
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

            Spacer(modifier = Modifier.weight(1f))

            HorizontalDivider()

            // 底部操作栏
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                TextButton(onClick = onNavigateBack) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    onClick = {
                        val effectiveRelation = if (customLabel.isNotBlank()) {
                            RelationPredicate.OTHER
                        } else {
                            currentRelation
                        }
                        onSave(effectiveRelation, customLabel, currentIsSelf)
                        onNavigateBack()
                    }
                ) {
                    Text(stringResource(R.string.save))
                }
            }
        }
    }

    if (showCoverPicker && photos.isNotEmpty()) {
        PersonCoverPickerSheet(
            photos = photos,
            onSelect = { photo ->
                onUpdateCover(photo)
                showCoverPicker = false
            },
            onDismiss = { showCoverPicker = false }
        )
    }
}

@Composable
private fun CoverHeader(
    cover: PersonCover?,
    contentDescription: String,
    onClick: () -> Unit
) {
    val alignment = remember(cover?.faceFocusY) {
        faceAwareVerticalAlignment(cover?.faceFocusY)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth(0.55f)
            .height(180.dp)
            .clip(RoundedCornerShape(16.dp))
    ) {
        val uri = cover?.coverUri
        if (uri != null) {
            AsyncImage(
                model = uri,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                alignment = alignment,
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(onClick = onClick)
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(onClick = onClick)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.person_set_cover_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp
                )
            }
        }
    }
}

