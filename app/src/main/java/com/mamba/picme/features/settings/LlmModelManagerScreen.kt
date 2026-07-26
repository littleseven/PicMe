package com.mamba.picme.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow


import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Functions
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults


import androidx.compose.material3.ExperimentalMaterial3Api




import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface




import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.mamba.picme.R
import com.mamba.picme.data.download.DownloadStatus
import com.mamba.picme.data.download.ModelConfig
import com.mamba.picme.domain.model.ModelCategory
import androidx.compose.ui.text.font.FontWeight
import java.util.Locale
import kotlinx.coroutines.launch
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import com.mamba.picme.data.download.DownloadState

/**
 * 根据标签获取对应的图标
 */
@Composable
internal fun getCategoryIcon(tag: String): ImageVector {
    return when (tag.lowercase()) {
        "must-have" -> Icons.Outlined.Star
        "recommended" -> Icons.Outlined.Download
        "chat" -> Icons.AutoMirrored.Outlined.Chat
        "photo-tagging" -> Icons.Outlined.Photo
        "beauty-camera" -> Icons.Outlined.CameraAlt
        // 保留旧标签兼容
        "voice" -> Icons.Outlined.Mic
        "vision" -> Icons.Outlined.Visibility
        "think" -> Icons.Outlined.SmartToy
        "audio", "audiogen" -> Icons.Outlined.Audiotrack
        "imagegen" -> Icons.Outlined.Image
        "code" -> Icons.Outlined.Code
        "math" -> Icons.Outlined.Functions
        "face" -> Icons.Outlined.Face
        else -> Icons.Outlined.Palette
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelCenterScreen(
    viewModel: SettingsViewModel,
    initialCategoryTag: String = "",
    onNavigateBack: () -> Unit
) {
    val groupedModels by viewModel.groupedModels.collectAsState()
    val currentTab by viewModel.currentTab.collectAsState()
    val modelTypeLabels = viewModel.getModelTypeLabels()
    val downloadStates by viewModel.downloadStates.collectAsState()
    val downloadedModels by viewModel.downloadedModels.collectAsState()
    val tagTranslations by viewModel.tagTranslations.collectAsState()
    var modelToDelete by remember { mutableStateOf<ModelConfig?>(null) }
    var modelToShowProperties by remember { mutableStateOf<ModelConfig?>(null) }
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val coroutineScope = rememberCoroutineScope()
    val isMustHaveTab = currentTab.tag.equals("must-have", ignoreCase = true)

    // 根据传入的初始标签设置当前 Tab（按服务功能映射）
    LaunchedEffect(initialCategoryTag, modelTypeLabels) {
        if (initialCategoryTag.isNotBlank() && modelTypeLabels.isNotEmpty()) {
            val targetCategory = when (initialCategoryTag) {
                "Chat", "Audio" -> modelTypeLabels.keys.find { it.tag.equals("chat", ignoreCase = true) }
                "Vision" -> modelTypeLabels.keys.find { it.tag.equals("beauty-camera", ignoreCase = true) }
                else -> modelTypeLabels.keys.find { it.tag.equals(initialCategoryTag, ignoreCase = true) }
            }
            if (targetCategory != null) {
                viewModel.switchTab(targetCategory)
            }
        }
    }

    // 同步 Tab 索引
    LaunchedEffect(currentTab, modelTypeLabels) {
        val index = modelTypeLabels.keys.indexOf(currentTab)
        selectedTabIndex = index.coerceAtLeast(0)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.model_center)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            // 可滚动的分类 Tab 栏
            ScrollableCategoryTabs(
                categories = modelTypeLabels,
                selectedIndex = selectedTabIndex,
                onCategorySelected = { index, category ->
                    selectedTabIndex = index
                    viewModel.switchTab(category)
                }
            )

            // 模型列表
            val currentModels = groupedModels[currentTab] ?: emptyList()

            if (currentModels.isEmpty()) {
                EmptyModelList()
            } else {
                val downloadedIds = downloadedModels.map { it.id }.toSet()
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (isMustHaveTab) {
                        item(key = "must-have-header") {
                            val missingCount = currentModels.count { it.id !in downloadedIds }
                            MustHaveHeaderCard(
                                requiredCount = currentModels.size,
                                missingCount = missingCount,
                                onDownloadAll = { viewModel.downloadAllRequiredModels() }
                            )
                        }
                    }

                    items(currentModels) { model ->
                        val downloadState = downloadStates[model.id]
                        val isPaused = downloadState?.status == DownloadStatus.PAUSED
                        val isDownloaded = model.id in downloadedIds
                        ModelCardWithBadge(
                            model = model,
                            downloadState = downloadState,
                            isDownloaded = isDownloaded,
                            tagTranslations = tagTranslations,
                            onDownload = {
                                if (isPaused) {
                                    viewModel.resumeModelDownload(model.id, model)
                                } else {
                                    viewModel.downloadModel(model.id, model)
                                }
                            },
                            onCancel = {
                                viewModel.cancelModelDownload(model.id)
                            },
                            onPause = {
                                viewModel.pauseModelDownload(model.id)
                            },
                            onDelete = { modelToDelete = model },
                            onShowProperties = { modelToShowProperties = model }
                        )
                    }
                }
            }
        }
    }

    // 删除确认对话框
    if (modelToDelete != null) {
        AlertDialog(
            onDismissRequest = { modelToDelete = null },
            title = { Text(stringResource(R.string.delete_model_title)) },
            text = { Text(stringResource(R.string.delete_model_confirm, modelToDelete?.name ?: "")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val deletingModel = modelToDelete ?: return@TextButton
                        modelToDelete = null
                        coroutineScope.launch {
                            viewModel.cancelModelDownload(deletingModel.id)
                            viewModel.deleteDownloadedModel(deletingModel.id)
                            viewModel.refreshModels()
                        }
                    }
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { modelToDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // 模型属性对话框
    if (modelToShowProperties != null) {
        ModelPropertiesDialog(
            model = modelToShowProperties!!,
            onDismiss = { modelToShowProperties = null }
        )
    }
}

/**
 * 可滚动的分类 Tab 栏 - 使用 Chip 风格替代 TabRow，避免文字截断
 */
@Composable
private fun ScrollableCategoryTabs(
    categories: Map<ModelCategory, String>,
    selectedIndex: Int,
    onCategorySelected: (Int, ModelCategory) -> Unit
) {
    val scrollState = rememberScrollState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        categories.entries.forEachIndexed { index, entry ->
            val category = entry.key
            val label = entry.value
            val isSelected = selectedIndex == index

            Surface(
                onClick = { onCategorySelected(index, category) },
                modifier = Modifier.height(36.dp),
                shape = CircleShape,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                },
                shadowElevation = if (isSelected) 2.dp else 0.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 0.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = getCategoryIcon(category.tag),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }
    }
}

@Composable
internal fun EmptyModelList() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Outlined.SmartToy,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.no_models_available),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 必须模型分类顶部卡片：展示必须模型汇总并提供一键下载
 */
@Composable
internal fun MustHaveHeaderCard(
    requiredCount: Int,
    missingCount: Int,
    onDownloadAll: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = stringResource(R.string.model_label_required),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.must_have_models_summary, requiredCount, missingCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
            )
            if (missingCount > 0) {
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onDownloadAll,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.download_all_missing))
                }
            }
        }
    }
}

/**
 * 根据标签获取对应的颜色
 */
@Composable
internal fun getTagColor(tag: String): Color {
    return when (tag.lowercase()) {
        "must-have" -> Color(0xFFE53935)
        "recommended" -> MaterialTheme.colorScheme.tertiary
        "chat" -> MaterialTheme.colorScheme.primary
        "photo-tagging" -> Color(0xFF9C27B0)
        "beauty-camera" -> MaterialTheme.colorScheme.tertiary
        // 保留旧标签兼容
        "voice" -> MaterialTheme.colorScheme.secondary
        "think" -> MaterialTheme.colorScheme.primary
        "vision" -> MaterialTheme.colorScheme.tertiary
        "audio", "audiogen" -> MaterialTheme.colorScheme.secondary
        "imagegen" -> Color(0xFF9C27B0)
        "code" -> Color(0xFF2196F3)
        "math" -> Color(0xFFFF9800)
        "face" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.outline
    }
}

@Composable
internal fun ModelCardWithBadge(
    model: ModelConfig,
    downloadState: DownloadState?,
    isDownloaded: Boolean = false,
    tagTranslations: Map<String, String>,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onPause: () -> Unit,
    onDelete: () -> Unit,
    onShowProperties: () -> Unit
) {
    val isDownloading = downloadState?.status == DownloadStatus.DOWNLOADING
    val isPaused = downloadState?.status == DownloadStatus.PAUSED
    val progress = if (downloadState != null && downloadState.totalBytes > 0) {
        downloadState.downloadedBytes.toFloat() / downloadState.totalBytes.toFloat()
    } else {
        0f
    }

    val primaryTag = model.tags.firstOrNull() ?: "Chat"
    val tagColor = getTagColor(primaryTag)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = { onShowProperties() }
                )
            },
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // 顶部：模型名 + 操作按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                // 左侧：名称和标签
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = model.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )

                        // 标签徽章 - 紧凑样式
                        val tagLabel = tagTranslations[primaryTag] ?: primaryTag
                        TagBadge(label = tagLabel, color = tagColor)
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // 描述
                    Text(
                        text = model.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    // 底部信息行：大小 + 轻量版标签 + 必须标签
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = formatFileSize(model.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )

                        if (model.isSmallModel) {
                            LightweightBadge()
                        }

                        if (model.isRequired) {
                            RequiredBadge()
                        }
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))

                // 右侧：操作按钮
                ModelActionButton(
                    downloadState = downloadState,
                    isDownloaded = isDownloaded,
                    isDownloading = isDownloading,
                    isPaused = isPaused,
                    onDownload = onDownload,
                    onCancel = onCancel,
                    onPause = onPause,
                    onDelete = onDelete
                )
            }

            // 下载进度条
            if (isDownloading || isPaused) {
                Spacer(modifier = Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = if (isPaused) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                val statusText = if (isPaused) {
                    "${(progress * 100).toInt()}% — ${stringResource(R.string.pause_download)}"
                } else {
                    "${(progress * 100).toInt()}%"
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isPaused) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (downloadState?.status == DownloadStatus.FAILED) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.download_failed),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/**
 * 紧凑的标签徽章
 */
@Composable
internal fun TagBadge(label: String, color: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1
        )
    }
}

/**
 * 轻量版标签
 */
@Composable
internal fun LightweightBadge() {
    Text(
        text = stringResource(R.string.model_label_lightweight),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

/**
 * 必须模型标签
 */
@Composable
internal fun RequiredBadge() {
    Text(
        text = stringResource(R.string.model_label_required),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFFE53935))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

/**
 * 模型操作按钮
 */
@Composable
internal fun ModelActionButton(
    downloadState: DownloadState?,
    isDownloaded: Boolean = false,
    isDownloading: Boolean,
    isPaused: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onPause: () -> Unit,
    onDelete: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        when {
            isDownloaded && downloadState == null -> {
                // 已下载且无活跃下载状态：显示已下载图标
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = stringResource(R.string.model_downloaded),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            isDownloading -> {
                // 下载中：显示暂停和取消按钮
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(
                        onClick = onPause,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Pause,
                            contentDescription = stringResource(R.string.pause_download),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = onCancel,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.cancel_download),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            isPaused -> {
                // 暂停中：显示继续按钮
                IconButton(
                    onClick = onDownload,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = stringResource(R.string.resume_download),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            downloadState?.status == DownloadStatus.COMPLETED -> {
                Icon(
                    Icons.Default.Check,
                    contentDescription = stringResource(R.string.model_downloaded),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
            else -> {
                IconButton(
                    onClick = onDownload,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = stringResource(R.string.download),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                Icons.Default.Delete,
                contentDescription = stringResource(R.string.delete),
                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

internal fun formatFileSize(bytes: Long): String {
    return when {
        bytes >= 1024 * 1024 * 1024 -> String.format(Locale.getDefault(), "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024 * 1024 -> String.format(Locale.getDefault(), "%.2f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> String.format(Locale.getDefault(), "%.2f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}

/**
 * 模型属性对话框 —— 以 JSON 格式展示模型信息，支持复制
 */
@Composable
internal fun ModelPropertiesDialog(
    model: ModelConfig,
    onDismiss: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    var showCopiedToast by remember { mutableStateOf(false) }

    val propertiesJson = remember(model) {
        buildString {
            appendLine("{")
            appendLine("  \"id\": \"${model.id}\",")
            appendLine("  \"name\": \"${model.name}\",")
            appendLine("  \"description\": \"${model.description}\",")
            appendLine("  \"size\": ${model.size},")
            appendLine("  \"sizeFormatted\": \"${formatFileSize(model.size)}\",")
            appendLine("  \"tags\": ${model.tags},")
            appendLine("  \"files\": ${model.files},")
            appendLine("  \"sources\": {")
            model.sources.entries.forEachIndexed { index, entry ->
                val comma = if (index < model.sources.size - 1) "," else ""
                appendLine("    \"${entry.key}\": \"${entry.value}\"$comma")
            }
            appendLine("  },")
            appendLine("  \"isSmallModel\": ${model.isSmallModel}")
            appendLine("}")
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                // 标题栏
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.model_properties),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    IconButton(onClick = {
                        clipboardManager.setText(AnnotatedString(propertiesJson))
                        showCopiedToast = true
                    }) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = stringResource(R.string.copy),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // JSON 内容区域
                OutlinedTextField(
                    value = propertiesJson,
                    onValueChange = { },
                    readOnly = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 200.dp, max = 400.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        disabledTextColor = MaterialTheme.colorScheme.onSurface,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    ),
                    enabled = false
                )

                if (showCopiedToast) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.copied_to_clipboard),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 关闭按钮
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(stringResource(R.string.close))
                }
            }
        }
    }
}
