@file:OptIn(ExperimentalLayoutApi::class)

package com.mamba.picme.features.gallery.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mamba.picme.PoLangApplication
import com.mamba.picme.R
import com.mamba.picme.data.local.AppDatabase
import com.mamba.picme.data.local.entity.TagScanPass
import com.mamba.picme.domain.tag.TagCategory
import com.mamba.picme.domain.tag.scan.ScanSessionState
import com.mamba.picme.domain.tag.scan.TagScanSessionProgress
import com.mamba.picme.domain.tag.scan.TagScanOrchestrator
import com.mamba.picme.service.tag.TagGenerationService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * TAG 生成精细控制子页面
 *
 * 显示三阶段混合管道的各阶段进度和数据库统计。
 * 语义编码已内联到人脸检测阶段，不再作为独立阶段显示。
 * 所有操作通过 TagGenerationService → TagScanOrchestrator 统一管理。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagGenerationControlScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as PoLangApplication
    val db = remember { AppDatabase.getDatabase(context) }
    val coroutineScope = rememberCoroutineScope()

    // ── 通过 AppContainer 观察 TAG 生成状态（Service 内部分发） ───
    val sessionProgress by app.container.tagGenerationSessionProgress.collectAsState()
    val isScanning by app.container.tagGenerationIsScanning.collectAsState()
    val currentState = sessionProgress?.state
    val isRunning = currentState == ScanSessionState.RUNNING
    val isPausing = currentState == ScanSessionState.PAUSING
    val isPaused = currentState == ScanSessionState.PAUSED
    val isCancelling = currentState == ScanSessionState.CANCELLING
    val isCancelled = currentState == ScanSessionState.CANCELLED
    val snackbarHostState = remember { SnackbarHostState() }

    // 启动前台 Service（Intent 驱动，无外部 Handle）
    LaunchedEffect(Unit) {
        TagGenerationService.startForeground(context)
    }

    // 数据库统计（每次进入时刷新）
    var totalMedia by remember { mutableIntStateOf(0) }
    var withFace by remember { mutableIntStateOf(0) }
    var withLabels by remember { mutableIntStateOf(0) }
    var withSemantic by remember { mutableIntStateOf(0) }
    var personCount by remember { mutableIntStateOf(0) }
    var namedPersonCount by remember { mutableIntStateOf(0) }
    var embeddingCount by remember { mutableIntStateOf(0) }
    var remainingPass1 by remember { mutableIntStateOf(0) }
    var remainingPass3 by remember { mutableIntStateOf(0) }

    // 精细控制：类别 / 时间范围 / 模式
    var selectedCategories by remember { mutableStateOf(setOf<TagCategory>()) }
    var selectedTimeRange by remember { mutableStateOf(TimeRangePreset.ALL) }
    var fullRegenerateMode by remember { mutableStateOf(false) }

    // 刷新统计：统一通过 TagScanOrchestrator.getDbStats(db) 获取，
    // 不依赖 Service/Orchestrator 实例，进入页面即可立即显示。
    fun refreshStats() {
        coroutineScope.launch {
            try {
                android.util.Log.d("TagGenControl", "refreshStats() called")
                val stats = TagScanOrchestrator.getDbStats(db)
                android.util.Log.d("TagGenControl", "stats=$stats")
                totalMedia = stats.totalMedia
                withFace = stats.withFace
                withLabels = stats.withLabels
                withSemantic = stats.withSemantic
                personCount = stats.personCount
                namedPersonCount = stats.namedPersonCount
                embeddingCount = stats.faceEmbeddingCount
                remainingPass1 = stats.remainingForPass1
                remainingPass3 = stats.remainingForPass3
            } catch (e: Exception) {
                android.util.Log.e("TagGenControl", "refreshStats failed", e)
            }
        }
    }

    // 显示扫描完成通知
    LaunchedEffect(sessionProgress?.messages?.lastOrNull()?.text) {
        val msg = sessionProgress?.messages?.lastOrNull()?.text ?: return@LaunchedEffect
        if (sessionProgress?.state == ScanSessionState.COMPLETED) {
            refreshStats()
            snackbarHostState.showSnackbar(msg)
        }
    }

    // 初始加载统计
    LaunchedEffect(Unit) {
        android.util.Log.d("TagGenControl", "initial refreshStats()")
        refreshStats()
    }

    // 轮询更新数据库累计统计（每秒刷新，不依赖 isScanning 状态，便于诊断）
    LaunchedEffect(Unit) {
        android.util.Log.d("TagGenControl", "poll loop started")
        while (true) {
            android.util.Log.d("TagGenControl", "poll tick, isScanning=${app.container.tagGenerationIsScanning.value}")
            refreshStats()
            delay(1000)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("TAG 生成控制") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── 扫描进度卡片 ────────────────────────────
            AnimatedVisibility(visible = sessionProgress != null) {
                sessionProgress?.let { ScanProgressCard(it) }
            }

            // ── 会话控制（扫描活跃时显示） ────────────────
            AnimatedVisibility(visible = isRunning || isPausing || isPaused) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        when {
                            isRunning -> {
                                OutlinedButton(
                                    onClick = {
                                        context.startForegroundService(TagGenerationService.intentPause(context))
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Rounded.Pause, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("暂停")
                                }
                            }
                            isPausing -> {
                                OutlinedButton(
                                    onClick = {},
                                    enabled = false,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Rounded.Pause, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("暂停中...")
                                }
                            }
                            isPaused -> {
                                Button(
                                    onClick = {
                                        context.startForegroundService(TagGenerationService.intentResume(context))
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("恢复")
                                }
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                context.startForegroundService(TagGenerationService.intentCancel(context))
                            },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.Cancel, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("取消")
                        }

                        if ((sessionProgress?.failed ?: 0) > 0) {
                            OutlinedButton(
                                onClick = {
                                    context.startForegroundService(TagGenerationService.intentRetryFailed(context))
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Rounded.Refresh, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("重试")
                            }
                        }
                    }
                }
            }

            // ── 数据库累计统计卡片 ────────────────────────────
            StatsCard(
                totalMedia = totalMedia,
                withFace = withFace,
                withLabels = withLabels,
                withSemantic = withSemantic,
                personCount = personCount,
                namedPersonCount = namedPersonCount,
                embeddingCount = embeddingCount,
                remainingPass1 = remainingPass1,
                remainingPass3 = remainingPass3
            )

            // ── 混合管道概览（只读状态展示） ────────────────
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "处理阶段概览",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (withFace > 0 || !isScanning) Icons.Rounded.CheckCircle else Icons.Rounded.HourglassEmpty,
                            null,
                            modifier = Modifier.size(20.dp),
                            tint = if (withFace > 0 || !isScanning) Color(0xFF4CAF50) else MaterialTheme.colorScheme.outline
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("第一步：人脸检测与语义编码", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "识别照片中的人脸并提取语义特征，用于人物归类与智能搜索 · $withFace / $totalMedia 张已完成 · 有语义 $withSemantic 张",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (personCount > 0 || !isScanning) Icons.Rounded.CheckCircle else Icons.Rounded.HourglassEmpty,
                            null,
                            modifier = Modifier.size(20.dp),
                            tint = if (personCount > 0 || !isScanning) Color(0xFF4CAF50) else MaterialTheme.colorScheme.outline
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("第二步：人物聚类", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "将相似人脸归为同一个人，方便按人物浏览和搜索 · 已识别 $personCount 个人物",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (withLabels > 0 || !isScanning) Icons.Rounded.CheckCircle else Icons.Rounded.HourglassEmpty,
                            null,
                            modifier = Modifier.size(20.dp),
                            tint = if (withLabels > 0 || !isScanning) Color(0xFF4CAF50) else MaterialTheme.colorScheme.outline
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("第三步：图片内容理解", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "分析画面内容，生成场景、活动、物体等标签与摘要 · $withLabels / $totalMedia 张已完成",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }

                    // 语义编码已内联到人脸检测阶段，此处不再显示。
                }
            }

            // ── 快速操作（空闲时显示） ────────────────
            AnimatedVisibility(visible = !isRunning && !isPausing && !isPaused) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                refreshStats()
                                context.startForegroundService(TagGenerationService.intentScanAll(context))
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.PlayArrow, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("全量扫描")
                        }
                        OutlinedButton(
                            onClick = {
                                refreshStats()
                                context.startForegroundService(TagGenerationService.intentScanIncremental(context))
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.Update, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("增量扫描")
                        }
                    }
                }
            }

            // ── 分阶段独立控制 ────────────────
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "分阶段独立控制",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "每个阶段均可独立增量补充或全量重新生成",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Spacer(Modifier.height(8.dp))

                    PassControlCard(
                        title = "人脸检测与语义编码",
                        subtitle = "为未处理照片识别面孔并提取语义特征 · $withFace / $totalMedia 张已完成 · 剩余 $remainingPass1 张",
                        onIncremental = {
                            refreshStats()
                            context.startForegroundService(TagGenerationService.intentScanPass1(context))
                        },
                        onFull = {
                            refreshStats()
                            context.startForegroundService(TagGenerationService.intentScanPass1Full(context))
                        }
                    )

                    Spacer(Modifier.height(8.dp))

                    PassControlCard(
                        title = "人物聚类",
                        subtitle = "按面部特征将照片分组到不同人物 · 已识别 $personCount 个人物 · $embeddingCount 条特征",
                        onIncremental = {
                            refreshStats()
                            context.startForegroundService(TagGenerationService.intentScanPass2(context))
                        },
                        onFull = {
                            refreshStats()
                            context.startForegroundService(TagGenerationService.intentScanPass2Full(context))
                        }
                    )

                    Spacer(Modifier.height(8.dp))

                    PassControlCard(
                        title = "图片内容理解",
                        subtitle = "为未处理照片生成场景、活动、物体等描述标签 · $withLabels / $totalMedia 张已完成 · 剩余 $remainingPass3 张",
                        onIncremental = {
                            refreshStats()
                            context.startForegroundService(TagGenerationService.intentScanPass3(context))
                        },
                        onFull = {
                            refreshStats()
                            context.startForegroundService(TagGenerationService.intentScanPass3Full(context))
                        }
                    )

                    // 语义编码已内联到人脸检测阶段，此处不再提供独立入口。
                    // ML Kit 英文标签已并入内容理解阶段，不再单独生成。

                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))

                    // ── 精细控制：按类别 / 时间范围重新生成 ──────────
                    Text(
                        "精细控制",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Spacer(Modifier.height(8.dp))

                    Text(
                        "选择 TAG 类别",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                    Spacer(Modifier.height(4.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        CategoryChip(
                            label = "人脸",
                            selected = TagCategory.FACE in selectedCategories,
                            onClick = {
                                selectedCategories = selectedCategories.toggle(TagCategory.FACE)
                            }
                        )
                        CategoryChip(
                            label = "场景",
                            selected = TagCategory.SCENE in selectedCategories,
                            onClick = {
                                selectedCategories = selectedCategories.toggle(TagCategory.SCENE)
                            }
                        )
                        CategoryChip(
                            label = "活动",
                            selected = TagCategory.ACTIVITY in selectedCategories,
                            onClick = {
                                selectedCategories = selectedCategories.toggle(TagCategory.ACTIVITY)
                            }
                        )
                        CategoryChip(
                            label = "物体",
                            selected = TagCategory.OBJECTS in selectedCategories,
                            onClick = {
                                selectedCategories = selectedCategories.toggle(TagCategory.OBJECTS)
                            }
                        )
                        CategoryChip(
                            label = "标签",
                            selected = TagCategory.TAGS in selectedCategories,
                            onClick = {
                                selectedCategories = selectedCategories.toggle(TagCategory.TAGS)
                            }
                        )
                        CategoryChip(
                            label = "摘要",
                            selected = TagCategory.SUMMARY in selectedCategories,
                            onClick = {
                                selectedCategories = selectedCategories.toggle(TagCategory.SUMMARY)
                            }
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    Text(
                        "时间范围",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                    Spacer(Modifier.height(4.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        TimeRangePreset.entries.forEach { preset ->
                            CategoryChip(
                                label = preset.label,
                                selected = selectedTimeRange == preset,
                                onClick = { selectedTimeRange = preset }
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "模式: ${if (fullRegenerateMode) "全量重标" else "仅补充缺失"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                        )
                        Switch(
                            checked = fullRegenerateMode,
                            onCheckedChange = { fullRegenerateMode = it }
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    Button(
                        onClick = {
                            refreshStats()
                            val categories = selectedCategories.ifEmpty { TagCategory.ALL }
                            val startTimeMs = selectedTimeRange.startTimeMs
                            context.startForegroundService(
                                TagGenerationService.intentRegenerateCategories(
                                    context = context,
                                    categories = categories.map { it.name },
                                    startTimeMs = startTimeMs,
                                    fullMode = fullRegenerateMode
                                )
                            )
                        },
                        enabled = selectedCategories.isNotEmpty() || selectedTimeRange != TimeRangePreset.ALL,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.Tune, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("按选择重新生成")
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ScanProgressCard(progress: TagScanSessionProgress) {
    val isScanning = progress.state in setOf(
        ScanSessionState.RUNNING,
        ScanSessionState.PAUSING,
        ScanSessionState.CANCELLING
    )
    val stateText = when (progress.state) {
        ScanSessionState.RUNNING -> "扫描中"
        ScanSessionState.PAUSING -> "暂停中"
        ScanSessionState.PAUSED -> "已暂停"
        ScanSessionState.CANCELLING -> "取消中"
        ScanSessionState.CANCELLED -> "已取消"
        ScanSessionState.COMPLETED -> "完成"
        ScanSessionState.IDLE -> "空闲"
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = when (progress.state) {
                ScanSessionState.PAUSED -> MaterialTheme.colorScheme.secondaryContainer
                ScanSessionState.CANCELLED -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.primaryContainer
            }
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isScanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                } else {
                    Icon(
                        Icons.Rounded.Info,
                        null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(Modifier.width(12.dp))
                val subtitle = when {
                    progress.state == ScanSessionState.CANCELLING -> "等待当前任务结束"
                    progress.state == ScanSessionState.CANCELLED -> ""
                    progress.state == ScanSessionState.COMPLETED -> ""
                    progress.currentPass == null -> "准备中"
                    else -> passDisplayName(progress.currentPass)
                }
                Text(
                    text = if (subtitle.isNotEmpty()) "$stateText · $subtitle" else stateText,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = {
                    if (progress.total > 0) progress.processed.toFloat() / progress.total else 0f
                },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "任务 ${progress.processed}/${progress.total} 完成 · 待处理 ${progress.pending} · 失败 ${progress.failed}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            if (progress.estimatedRemainingMs != null && isScanning) {
                Text(
                    "预计剩余: ${formatDuration(progress.estimatedRemainingMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )
            }
            if (progress.messages.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    progress.messages.last().text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
            }
        }
    }
}

private fun passDisplayName(pass: TagScanPass?): String = when (pass) {
    TagScanPass.FACE_DETECTION -> "第一步：人脸检测与语义编码"
    TagScanPass.DBSCAN -> "第二步：人物聚类"
    TagScanPass.QWEN_TAGGING -> "第三步：图片内容理解"
    TagScanPass.MOBILE_CLIP_ENCODING -> "语义编码（单独）"
    null -> "准备中"
}

@Composable
private fun PassControlCard(
    title: String,
    subtitle: String,
    onIncremental: () -> Unit,
    onFull: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            Column(
                modifier = Modifier.padding(start = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    modifier = Modifier
                        .clickable { onIncremental() }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AddCircleOutline,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "增量",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                }
                Row(
                    modifier = Modifier
                        .clickable { onFull() }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Replay,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "全量",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}

internal fun formatDuration(ms: Long): String {
    val seconds = ms / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24
    return when {
        days > 0 -> "${days}d ${hours % 24}h"
        hours > 0 -> "${hours}h ${minutes % 60}m"
        minutes > 0 -> "${minutes}m ${seconds % 60}s"
        else -> "${seconds}s"
    }
}

private enum class TimeRangePreset(val label: String, private val startOffsetMs: Long) {
    ALL("全部", 0),
    DAYS_7("最近7天", 7 * 24 * 60 * 60 * 1000L),
    DAYS_30("最近30天", 30 * 24 * 60 * 60 * 1000L),
    DAYS_90("最近90天", 90 * 24 * 60 * 60 * 1000L);

    val startTimeMs: Long
        get() = if (startOffsetMs > 0) System.currentTimeMillis() - startOffsetMs else 0L
}

private fun Set<TagCategory>.toggle(category: TagCategory): Set<TagCategory> {
    return if (category in this) this - category else this + category
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) }
    )
}

@Suppress("LongParameterList") // 待重构：抽 stats 数据类
@Composable
private fun StatsCard(
    totalMedia: Int,
    withFace: Int,
    withLabels: Int,
    withSemantic: Int,
    personCount: Int,
    namedPersonCount: Int,
    embeddingCount: Int,
    remainingPass1: Int,
    remainingPass3: Int
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "数据库累计统计",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(12.dp))

            // ── 媒体总量（两列卡片） ──────────────────
            StatsSectionTitle("媒体总量")
            Row(modifier = Modifier.fillMaxWidth()) {
                StatsNumberCard(
                    label = "总照片",
                    value = totalMedia.toString(),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(10.dp))
                StatsNumberCard(
                    label = "有语义向量",
                    value = withSemantic.toString(),
                    modifier = Modifier.weight(1f)
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

            // ── 人脸与人物（2×2 卡片网格） ────────────
            StatsSectionTitle("人脸与人物")
            Row(modifier = Modifier.fillMaxWidth()) {
                StatsNumberCard(
                    label = "含人脸照片",
                    value = withFace.toString(),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(10.dp))
                StatsNumberCard(
                    label = "人脸 Embedding",
                    value = embeddingCount.toString(),
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                StatsNumberCard(
                    label = "识别出的人",
                    value = personCount.toString(),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(10.dp))
                StatsNumberCard(
                    label = "已命名人",
                    value = namedPersonCount.toString(),
                    modifier = Modifier.weight(1f)
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

            // ── 各阶段进度（三列表格） ─────────────────
            StatsSectionTitle("阶段进度")
            StatsPassTableHeader()
            HorizontalDivider()
            StatsPassTableRow("人脸检测", "$withFace / $totalMedia", remainingPass1.toString())
            StatsPassTableRow("内容标签", "$withLabels / $totalMedia", remainingPass3.toString())
        }
    }
}

@Composable
private fun StatsSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 6.dp, bottom = 8.dp)
    )
}

@Composable
private fun StatsNumberCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                maxLines = 1
            )
        }
    }
}

@Composable
private fun StatsPassTableHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = "Pass",
            modifier = Modifier.weight(0.22f),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Text(
            text = "完成",
            modifier = Modifier.weight(0.46f),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            textAlign = TextAlign.End
        )
        Text(
            text = "剩余",
            modifier = Modifier.weight(0.32f),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun StatsPassTableRow(pass: String, done: String, remaining: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = pass,
            modifier = Modifier.weight(0.22f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
        )
        Text(
            text = done,
            modifier = Modifier.weight(0.46f),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
            maxLines = 1
        )
        Text(
            text = remaining,
            modifier = Modifier.weight(0.32f),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
            maxLines = 1
        )
    }
}
