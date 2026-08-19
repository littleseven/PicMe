@file:OptIn(ExperimentalLayoutApi::class)

package com.mamba.picme.features.gallery.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import com.mamba.picme.core.designsystem.ChatBubbleTokens
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mamba.picme.PoLangApplication
import com.mamba.picme.R
import com.mamba.picme.data.local.AppDatabase
import com.mamba.picme.data.local.entity.TagScanPass
import com.mamba.picme.domain.aesthetic.AestheticScoreWorker
import com.mamba.picme.domain.tag.TagCategory
import com.mamba.picme.domain.tag.scan.ScanSessionState
import com.mamba.picme.domain.tag.scan.TagScanSessionProgress
import com.mamba.picme.domain.tag.scan.TagScanOrchestrator
import com.mamba.picme.features.common.topbar.AppTopBar
import com.mamba.picme.features.common.topbar.AppTopBarNavBack
import com.mamba.picme.service.tag.TagGenerationService
import com.mamba.picme.util.permission.BackgroundScanGuard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

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
    onNavigateBack: () -> Unit,
    onNavigateToTagViewer: () -> Unit = {},
    header: @Composable (() -> Unit)? = null
) {
    val context = LocalContext.current
    val app = context.applicationContext as PoLangApplication
    val db = remember { AppDatabase.getDatabase(context) }
    val coroutineScope = rememberCoroutineScope()

    // ── 通过 AppContainer 观察 TAG 生成状态（Service 内部分发） ───
    val sessionProgress by app.container.tagGenerationSessionProgress.collectAsState()
    val isScanning by app.container.tagGenerationIsScanning.collectAsState()
    // 附属打分器（美学/人脸画质）进度：非会话制，null = 空闲；顶部进度卡优先显示活跃任务
    val aestheticProgress by app.container.aestheticScoreWorker.progress.collectAsState()
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
    var photoCount by remember { mutableIntStateOf(0) }
    var aestheticScored by remember { mutableIntStateOf(0) }

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
                photoCount = stats.photoCount
                aestheticScored = stats.aestheticScoredCount
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

    // ── 后台保活引导(HyperOS 等会冻结后台进程,引导用户加白名单/开通知/自启动) ──
    var guardIssues by remember { mutableStateOf<List<BackgroundScanGuard.Issue>>(emptyList()) }
    var pendingStart by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun startScanWithGuard(startAction: () -> Unit) {
        val issues = runCatching { BackgroundScanGuard.diagnose(context.applicationContext) }
            .getOrDefault(emptyList())
        if (issues.isNotEmpty() && BackgroundScanGuard.shouldShowDialog(context.applicationContext)) {
            guardIssues = issues
            pendingStart = startAction
        } else {
            startAction()
        }
    }

    if (guardIssues.isNotEmpty()) {
        BackgroundScanGuardDialog(
            issues = guardIssues,
            onGoSettings = {
                val first = guardIssues.first()
                guardIssues = emptyList()
                pendingStart = null
                first.openFix(context)
            },
            onContinue = {
                val pending = pendingStart
                guardIssues = emptyList()
                pendingStart = null
                pending?.invoke()
            },
            onDontRemind = {
                BackgroundScanGuard.doNotShowAgain(context.applicationContext)
                guardIssues = emptyList()
                pendingStart = null
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            AppTopBar(
                title = { Text(stringResource(R.string.gallery_settings)) },
                navigationIcon = { AppTopBarNavBack(onClick = onNavigateBack) }
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
            // ── 当前任务进度卡片（统一槽位）─────────────────────
            // 扫描会话活跃时优先显示扫描进度（美学打分此时已被 Service 互斥取消，
            // 此处再兜底防竞态）；空闲时美学评分运行则显示打分进度，否则显示会话终态。
            // 美学评分非会话制（不进 TagScanOrchestrator），会话卡片只反映扫描本身。
            val scanActive = isScanning
            AnimatedVisibility(visible = aestheticProgress != null && !scanActive) {
                aestheticProgress?.let { AestheticProgressCard(it) }
            }
            AnimatedVisibility(visible = sessionProgress != null && (scanActive || aestheticProgress == null)) {
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
                remainingPass3 = remainingPass3,
                onNavigateToTagViewer = onNavigateToTagViewer
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
                            Text(stringResource(R.string.tag_pass_step_face), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                stringResource(
                                    R.string.tag_pass_overview_face,
                                    totalMedia - remainingPass1,
                                    remainingPass1,
                                    withFace,
                                    withSemantic
                                ),
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
                            Text(stringResource(R.string.tag_pass_step_cluster), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                stringResource(R.string.tag_pass_overview_cluster, personCount),
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
                            Text(stringResource(R.string.tag_pass_step_content), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                stringResource(
                                    R.string.tag_pass_overview_content,
                                    totalMedia - remainingPass3,
                                    remainingPass3
                                ),
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
                                startScanWithGuard {
                                    context.startForegroundService(TagGenerationService.intentScanAll(context))
                                }
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
                                startScanWithGuard {
                                    context.startForegroundService(TagGenerationService.intentScanIncremental(context))
                                }
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

                    val pass1Progress = tagPassProgress(totalMedia, remainingPass1)
                    val pass1Text = stringResource(
                        R.string.tag_pass_progress_p1,
                        pass1Progress.processed,
                        pass1Progress.remaining,
                        withFace
                    )
                    PassControlCard(
                        title = stringResource(R.string.tag_pass_title_face),
                        description = stringResource(R.string.tag_pass_desc_face),
                        progress = pass1Progress,
                        progressText = if (pass1Progress.isEmpty) "" else pass1Text,
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

                    val clusterText = if (personCount > 0) {
                        stringResource(R.string.tag_pass_cluster_done, personCount, embeddingCount)
                    } else {
                        stringResource(R.string.tag_pass_cluster_pending, embeddingCount)
                    }
                    PassControlCard(
                        title = stringResource(R.string.tag_pass_title_cluster),
                        description = stringResource(R.string.tag_pass_desc_cluster),
                        progress = null,
                        progressText = clusterText,
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

                    val pass3Progress = tagPassProgress(totalMedia, remainingPass3)
                    val pass3Text = stringResource(
                        R.string.tag_pass_progress_p3,
                        pass3Progress.processed,
                        pass3Progress.remaining
                    )
                    PassControlCard(
                        title = stringResource(R.string.tag_pass_title_content),
                        description = stringResource(R.string.tag_pass_desc_content),
                        progress = pass3Progress,
                        progressText = if (pass3Progress.isEmpty) "" else pass3Text,
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

                    // 美学评分（NIMA + eDifFIQA）：独立于扫描会话，直接驱动 AestheticScoreWorker 排空积压
                    val aestheticProgress = tagPassProgress(photoCount, photoCount - aestheticScored)
                    PassControlCard(
                        title = stringResource(R.string.tag_pass_title_aesthetic),
                        description = stringResource(R.string.tag_pass_desc_aesthetic),
                        progress = aestheticProgress,
                        progressText = if (aestheticProgress.isEmpty) "" else stringResource(
                            R.string.tag_pass_progress_aesthetic,
                            aestheticScored,
                            aestheticProgress.remaining
                        ),
                        onIncremental = {
                            refreshStats()
                            context.startForegroundService(TagGenerationService.intentScoreAesthetic(context))
                        },
                        onFull = {
                            refreshStats()
                            context.startForegroundService(TagGenerationService.intentScoreAestheticFull(context))
                        }
                    )

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

            // ── 标签与重复图管理（相册工具）──
            header?.invoke()

            // ── 后台保活缺失项提示(非阻断,点击跳设置) ────────
            BackgroundScanGuardBanner()
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

@Composable
private fun AestheticProgressCard(progress: AestheticScoreWorker.AestheticProgress) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.tag_aesthetic_running),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = {
                    if (progress.total > 0) progress.processed.toFloat() / progress.total else 0f
                },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                trackColor = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.2f)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.tag_aesthetic_progress, progress.processed, progress.total),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    }
}

private fun passDisplayName(pass: TagScanPass?): String = when (pass) {
    TagScanPass.FACE_DETECTION -> "第一步：人脸检测与语义编码"
    TagScanPass.DBSCAN -> "第二步：人物聚类"
    TagScanPass.IMAGE_TAGGING -> "第三步：图片内容理解"
    TagScanPass.MOBILE_CLIP_ENCODING -> "语义编码（单独）"
    null -> "准备中"
}

@Composable
private fun PassControlCard(
    title: String,
    description: String,
    progress: TagPassProgress?,
    progressText: String,
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
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                if (progress != null) {
                    Spacer(Modifier.height(6.dp))
                    if (progress.isEmpty) {
                        Text(
                            stringResource(R.string.tag_pass_no_media),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LinearProgressIndicator(
                                progress = { progress.fraction },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(6.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            if (progress.isComplete) {
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = Color(0xFF4CAF50)
                                )
                            } else {
                                Text(
                                    "${(progress.fraction * 100).roundToInt()}%",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
                if (progressText.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = progressText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
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
    remainingPass3: Int,
    onNavigateToTagViewer: () -> Unit
) {
    // 设计稿 gallery/settings「相册统计」定稿：渐变大数字 + 语义覆盖率圆环 + 2×2 彩色指标瓦片
    // + 双阶段渐变进度条（替代旧的分组数字卡/表格）
    val semanticPct = if (totalMedia > 0) withSemantic * 100 / totalMedia else 0
    val pass1Pct = if (totalMedia > 0) (totalMedia - remainingPass1) * 100 / totalMedia else 0
    val pass3Pct = if (totalMedia > 0) (totalMedia - remainingPass3) * 100 / totalMedia else 0

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 头部：标题 + 标签查看入口（设计稿 header 行）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "数据库累计统计",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onNavigateToTagViewer() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.tag_viewer_open_entry),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(Modifier.height(14.dp))

            // ── Hero：渐变大数字 + 语义覆盖率渐变圆环 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "%,d".format(Locale.ROOT, totalMedia),
                        style = TextStyle(
                            fontSize = 34.sp,
                            fontWeight = FontWeight.SemiBold,
                            brush = Brush.linearGradient(
                                listOf(
                                    ChatBubbleTokens.brandGradientStart,
                                    ChatBubbleTokens.brandGradientEnd
                                )
                            )
                        )
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.stats_hero_caption, semanticPct),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                StatsProgressRing(progress = semanticPct)
            }
            Spacer(Modifier.height(14.dp))

            // ── 2×2 彩色指标瓦片（设计稿 tile 阵列） ──
            Row(modifier = Modifier.fillMaxWidth()) {
                StatsMetricTile(
                    icon = Icons.Rounded.Face,
                    iconColor = Color(0xFFFF7EB0),
                    label = "含人脸照片",
                    value = withFace,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(10.dp))
                StatsMetricTile(
                    icon = Icons.Rounded.Fingerprint,
                    iconColor = Color(0xFF22D3EE),
                    label = "人脸 Embedding",
                    value = embeddingCount,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                StatsMetricTile(
                    icon = Icons.Rounded.Person,
                    iconColor = Color(0xFF9B8CFF),
                    label = "识别出的人",
                    value = personCount,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(10.dp))
                StatsMetricTile(
                    icon = Icons.Rounded.VerifiedUser,
                    iconColor = Color(0xFF4ADE80),
                    label = "已命名人",
                    value = namedPersonCount,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(14.dp))

            // ── 阶段进度（渐变细进度条替代旧表格） ──
            StatsStageBar(
                label = stringResource(R.string.tag_pass_row_face),
                progressPct = pass1Pct
            )
            Spacer(Modifier.height(10.dp))
            StatsStageBar(
                label = stringResource(R.string.tag_pass_row_content),
                progressPct = pass3Pct
            )
        }
    }
}

/** 72dp 渐变进度圆环（设计稿 ringSvg）：底环 surfaceVariant + 品牌渐变前景弧 + 中心百分比。 */
@Composable
private fun StatsProgressRing(progress: Int) {
    val sweep = 360f * (progress.coerceIn(0, 100) / 100f)
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier = Modifier.size(72.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 6.dp.toPx()
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            if (sweep > 0f) {
                drawArc(
                    brush = Brush.sweepGradient(
                        listOf(
                            ChatBubbleTokens.brandGradientStart,
                            ChatBubbleTokens.brandGradientEnd,
                            ChatBubbleTokens.brandGradientStart
                        )
                    ),
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
        }
        Text(
            text = "$progress%",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** 指标瓦片：彩色图标 + 数值 + 标签（surfaceVariant@0.5 底 r12 高 64）。 */
@Composable
private fun StatsMetricTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconColor: Color,
    label: String,
    value: Int,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(64.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(18.dp)
            )
            Column {
                Text(
                    text = value.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

/** 阶段进度条：标签 + 百分比 + 6dp 渐变轨道（设计稿 stageTrack/stageHead）。 */
@Composable
private fun StatsStageBar(label: String, progressPct: Int) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "$progressPct%",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progressPct.coerceIn(0, 100) / 100f)
                    .height(6.dp)
                    .background(
                        Brush.linearGradient(
                            listOf(
                                ChatBubbleTokens.brandGradientStart,
                                ChatBubbleTokens.brandGradientEnd
                            )
                        )
                    )
            )
        }
    }
}
