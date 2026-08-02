package com.mamba.picme.features.person.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import com.mamba.picme.R
import com.mamba.picme.core.image.faceAwareVerticalAlignment
import com.mamba.picme.data.local.entity.PersonEntity
import com.mamba.picme.data.model.MediaEntity
import com.mamba.picme.domain.person.RelationDisplayItem
import com.mamba.picme.domain.person.RelationPredicate
import com.mamba.picme.features.common.PersonRelationPicker
import com.mamba.picme.features.common.topbar.AppTopBar
import com.mamba.picme.features.common.topbar.AppTopBarNavBack
import com.mamba.picme.features.person.PersonCover

/**
 * 人物信息编辑全屏页。
 *
 * 顶部显示封面、名字与簇 ID；点击封面进入封面选择 Sheet；
 * 下方编辑关系；右上角提供「不设置」与「保存」。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PersonInfoScreen(
    person: PersonEntity,
    relation: RelationDisplayItem?,
    cover: PersonCover?,
    photos: List<MediaEntity>,
    onSave: (RelationPredicate?, String, Boolean) -> Unit,
    onNavigateBack: () -> Unit,
    onUpdateCover: (MediaEntity) -> Unit,
    onUpdateName: (String) -> Unit,
    /** 仅给该人物聚类（重新）打美学/人脸画质分 + 刷新封面；null=不显示该入口。 */
    onRescore: (suspend () -> Unit)? = null
) {
    var currentRelation by remember(relation) {
        mutableStateOf(relation?.predicate)
    }
    var customLabel by remember(relation) {
        mutableStateOf(relation?.customLabel.orEmpty())
    }
    var currentIsSelf by remember(person.isSelf) { mutableStateOf(person.isSelf) }
    var showCoverPicker by remember { mutableStateOf(false) }
    var isEditingName by remember(person.personId) { mutableStateOf(false) }
    var nameText by remember(person.personId, person.name) { mutableStateOf(person.name.orEmpty()) }
    val nameFocusRequester = remember { FocusRequester() }
    val rescoreScope = rememberCoroutineScope()
    var rescoring by remember { mutableStateOf(false) }
    LaunchedEffect(isEditingName) {
        if (isEditingName) nameFocusRequester.requestFocus()
    }

    val doSave = {
        val effectiveRelation = if (customLabel.isNotBlank()) {
            RelationPredicate.OTHER
        } else {
            currentRelation
        }
        val trimmedName = nameText.trim()
        if (trimmedName.isNotBlank()) onUpdateName(trimmedName)
        onSave(effectiveRelation, customLabel, currentIsSelf)
        onNavigateBack()
    }

    // 系统返回键由顶栏 AppTopBarNavBack 统一注册的 BackHandler 处理（与返回箭头同回调）。

    Scaffold(
        topBar = {
            AppTopBar(
                title = {
                    Text(
                        text = stringResource(R.string.person_cluster_id, person.personId)
                    )
                },
                navigationIcon = {
                    AppTopBarNavBack(onClick = onNavigateBack)
                },
                actions = {
                    // 仅给该聚类（重新）打美学/人脸画质分 + 刷新封面
                    if (onRescore != null) {
                        IconButton(
                            enabled = !rescoring,
                            onClick = {
                                rescoreScope.launch {
                                    rescoring = true
                                    try {
                                        onRescore()
                                    } finally {
                                        rescoring = false
                                    }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = stringResource(R.string.people_rescore)
                            )
                        }
                    }
                    // 不设置：清空关系与自定义称呼
                    IconButton(
                        onClick = {
                            currentRelation = null
                            customLabel = ""
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.RestartAlt,
                            contentDescription = stringResource(R.string.person_relation_none)
                        )
                    }
                    // 保存
                    IconButton(onClick = doSave) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = stringResource(R.string.save)
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
                if (isEditingName) {
                    BasicTextField(
                        value = nameText,
                        onValueChange = { nameText = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = { isEditingName = false }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                            .focusRequester(nameFocusRequester)
                    )
                } else {
                    Text(
                        text = person.name?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.person_edit_name_hint),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .clickable { isEditingName = true }
                    )
                }
            }

            // 关系编辑
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = currentIsSelf,
                        onClick = { currentIsSelf = !currentIsSelf },
                        label = { Text(stringResource(R.string.person_is_self)) }
                    )
                }
                PersonRelationPicker(
                    selectedPredicate = currentRelation,
                    customLabel = customLabel,
                    onPredicateChange = { currentRelation = it },
                    onCustomLabelChange = { customLabel = it },
                    modifier = Modifier.fillMaxWidth(),
                    showNoneChip = false,
                    showTitle = false
                )
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
