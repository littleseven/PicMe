package com.mamba.picme.features.gallery.dedup

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mamba.picme.R
import com.mamba.picme.domain.dedup.DedupGroup
import com.mamba.picme.domain.dedup.DedupLevel
import com.mamba.picme.domain.dedup.DedupScanConfig
import com.mamba.picme.domain.dedup.KeepPolicy
import com.mamba.picme.domain.dedup.VersionRole

private val KeepGreen = Color(0xFF4CAF50)

/** 去重 2.0 主页：单路由内 Config → Scanning → Results → Cleaned 四态切换；组详情与保留规则为内部弹层。 */
@Composable
fun DedupHomeRoute(
    viewModel: DedupViewModel,
    onNavigateBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val pendingTrash by viewModel.pendingTrash.collectAsState()
    val pendingRestore by viewModel.pendingRestore.collectAsState()
    val partialTrashNotice by viewModel.partialTrashNotice.collectAsState()
    val policy by viewModel.policy.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showKeepRules by remember { mutableStateOf(false) }
    var detailGroupId by remember { mutableStateOf<String?>(null) }

    // 部分拒绝一次性提示（B5）：VM 置位 → 消费 → 弹 snackbar
    val partialTrashMessage = stringResource(R.string.dedup_partial_trash_notice)
    LaunchedEffect(partialTrashNotice) {
        if (partialTrashNotice) {
            viewModel.consumePartialTrashNotice()
            snackbarHostState.showSnackbar(partialTrashMessage)
        }
    }

    val trashLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result: ActivityResult ->
        viewModel.onTrashResult(result.resultCode == Activity.RESULT_OK)
    }
    val restoreLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result: ActivityResult ->
        viewModel.onRestoreResult(result.resultCode == Activity.RESULT_OK)
    }

    LaunchedEffect(pendingTrash) {
        pendingTrash?.let { pending ->
            trashLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
        }
    }
    LaunchedEffect(pendingRestore) {
        pendingRestore?.let { pending ->
            restoreLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            DedupTopBar(
                state = uiState,
                onNavigateBack = onNavigateBack,
                onCancelScan = { viewModel.cancelScan() },
                onOpenKeepRules = { showKeepRules = true },
            )
        },
        bottomBar = {
            when (val state = uiState) {
                is DedupUiState.Scanning -> DedupScanningBottomBar(
                    paused = state.paused,
                    onPauseResume = {
                        if (state.paused) viewModel.resumeScan() else viewModel.pauseScan()
                    },
                    onRunBackground = onNavigateBack,
                )
                is DedupUiState.Results -> {
                    // 底部 CTA 与 VM 删除流同一口径：SCENE 组不参与批量删除（spec §4）
                    val batchUris = viewModel.batchDeleteUris(state.groups)
                    DedupResultsBottomBar(
                        deleteCount = batchUris.size,
                        deleteBytes = viewModel.batchReclaimBytes(state.groups, batchUris),
                        onDelete = { viewModel.deleteSelected() },
                    )
                }
                is DedupUiState.Cleaned -> DedupCleanedBottomBar(
                    onDone = { viewModel.resetToConfig() },
                )
                is DedupUiState.Config -> Unit
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            when (val state = uiState) {
                is DedupUiState.Config -> DedupConfigContent(
                    config = state.config,
                    policy = policy,
                    onStartScan = { config -> viewModel.startScan(config) },
                    onOpenKeepRules = { showKeepRules = true },
                )
                is DedupUiState.Scanning -> DedupScanningContent(
                    state = state,
                    onOpenGroupDetail = { groupId -> detailGroupId = groupId },
                )
                is DedupUiState.Results -> DedupResultsContent(
                    state = state,
                    onSmartSelectAll = { viewModel.smartSelectAll() },
                    onSelectTab = { level -> viewModel.selectTab(level) },
                    onOpenGroupDetail = { groupId -> detailGroupId = groupId },
                )
                is DedupUiState.Cleaned -> DedupCleanedContent(
                    state = state,
                    onUndoAll = { viewModel.undoTrash() },
                )
            }
        }
    }

    if (showKeepRules) {
        KeepRulesSheet(
            viewModel = viewModel,
            onDismiss = { showKeepRules = false },
        )
    }
    detailGroupId?.let { groupId ->
        DedupGroupDetailSheet(
            groupId = groupId,
            viewModel = viewModel,
            onDismiss = { detailGroupId = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DedupTopBar(
    state: DedupUiState,
    onNavigateBack: () -> Unit,
    onCancelScan: () -> Unit,
    onOpenKeepRules: () -> Unit,
) {
    TopAppBar(
        title = {
            Text(
                text = stringResource(
                    if (state is DedupUiState.Scanning) {
                        R.string.dedup_scanning_title
                    } else {
                        R.string.dedup_title
                    }
                )
            )
        },
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                )
            }
        },
        actions = {
            when (state) {
                is DedupUiState.Scanning -> TextButton(onClick = onCancelScan) {
                    Text(stringResource(R.string.cancel))
                }
                is DedupUiState.Results -> IconButton(onClick = onOpenKeepRules) {
                    Icon(Icons.Rounded.Tune, contentDescription = stringResource(R.string.dedup_keep_rule))
                }
                else -> Unit
            }
        }
    )
}

// ---------- Config ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DedupConfigContent(
    config: DedupScanConfig,
    policy: KeepPolicy,
    onStartScan: (DedupScanConfig) -> Unit,
    onOpenKeepRules: () -> Unit,
) {
    var selectedLevels by remember { mutableStateOf(config.levels) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Hero 卡：V1 无上次数值，简单显示「从未扫描」
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.dedup_estimate_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.dedup_never_scanned),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Text(
            text = stringResource(R.string.dedup_scale_section),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        DedupLevel.entries.forEach { level ->
            DedupLevelRow(
                level = level,
                selected = level in selectedLevels,
                onToggle = { checked ->
                    selectedLevels = if (checked) {
                        selectedLevels + level
                    } else {
                        selectedLevels - level
                    }
                }
            )
        }

        // 保留规则行（Task 8：打开规则弹层，当前值为 VM 级 policy）
        Card(
            onClick = onOpenKeepRules,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.dedup_keep_rule),
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = stringResource(keepPolicyLabelRes(policy)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Button(
            onClick = { onStartScan(config.copy(levels = selectedLevels)) },
            modifier = Modifier.fillMaxWidth(),
            enabled = selectedLevels.isNotEmpty()
        ) {
            Text(stringResource(R.string.dedup_start_scan))
        }

        Text(
            text = stringResource(R.string.dedup_privacy_caption),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DedupLevelRow(
    level: DedupLevel,
    selected: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Card(
        onClick = { onToggle(!selected) },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = dedupLevelIcon(level),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(dedupLevelLabelRes(level)),
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = stringResource(dedupLevelDescRes(level)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Checkbox(checked = selected, onCheckedChange = onToggle)
        }
    }
}

// ---------- Scanning ----------

@Composable
private fun DedupScanningContent(
    state: DedupUiState.Scanning,
    onOpenGroupDetail: (String) -> Unit,
) {
    val progress = if (state.total > 0) {
        state.scanned.toFloat() / state.total.toFloat()
    } else {
        0f
    }
    val percent = (progress * 100).toInt()

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(
                            R.string.dedup_phase_format,
                            state.phaseIndex,
                            state.phaseCount,
                            stringResource(dedupLevelLabelRes(state.phase))
                        ),
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = stringResource(R.string.dedup_progress_percent, percent),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = stringResource(R.string.dedup_scan_progress, state.scanned, state.total),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 三段阶段条：完成=绿 / 进行中=primary / 未开始=surfaceVariant
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                repeat(state.phaseCount) { index ->
                    val color = when {
                        index < state.phaseIndex - 1 -> KeepGreen
                        index == state.phaseIndex - 1 -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(color)
                    )
                }
            }

            Text(
                text = stringResource(R.string.dedup_live_found, state.foundGroups.size),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(
                items = state.foundGroups,
                key = { group -> group.id }
            ) { group ->
                DedupScanningGroupRow(
                    group = group,
                    onClick = { onOpenGroupDetail(group.id) }
                )
            }
        }
    }
}

/** 扫描中实时发现组（紧凑版：前 2 张缩略 + 级别 badge + meta + chevron，只展示不改选）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DedupScanningGroupRow(
    group: DedupGroup,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            group.members.take(2).forEach { member ->
                DedupThumb(
                    uri = member.uri,
                    isKept = member.uri == group.keepUri,
                    role = member.role,
                    modifier = Modifier.size(48.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                LevelBadge(level = group.level)
                Text(
                    text = stringResource(
                        R.string.dedup_group_meta,
                        group.members.size,
                        formatBytes(group.reclaimBytes)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DedupScanningBottomBar(
    paused: Boolean,
    onPauseResume: () -> Unit,
    onRunBackground: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            onClick = onPauseResume,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.size(6.dp))
            Text(
                text = stringResource(
                    if (paused) R.string.dedup_resume else R.string.dedup_pause
                )
            )
        }
        TextButton(onClick = onRunBackground) {
            Text(stringResource(R.string.dedup_run_background))
        }
    }
}

// ---------- Results ----------

@Composable
private fun DedupResultsContent(
    state: DedupUiState.Results,
    onSmartSelectAll: () -> Unit,
    onSelectTab: (DedupLevel) -> Unit,
    onOpenGroupDetail: (String) -> Unit,
) {
    val policyLabel = stringResource(keepPolicyLabelRes(state.policy))
    // summary 与底部 CTA 同口径（spec §4）：SCENE 组不参与批量操作，不计入「可释放」
    val batchGroups = state.groups.filter { group -> group.level != DedupLevel.SCENE }
    val totalReclaim = batchGroups.sumOf { group -> group.reclaimBytes }
    val filteredGroups = state.groups.filter { group -> group.level == state.selectedTab }

    Column(modifier = Modifier.fillMaxSize()) {
        // summary 行：文本占满剩余宽度、最多 2 行折行；右侧「智能全选」chip 单行不折
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(
                    R.string.dedup_results_summary,
                    batchGroups.size,
                    formatBytes(totalReclaim)
                ),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            FilterChip(
                selected = false,
                onClick = onSmartSelectAll,
                label = {
                    Text(
                        text = stringResource(R.string.dedup_smart_select_all),
                        maxLines = 1,
                        softWrap = false
                    )
                }
            )
        }

        // tabs 行：三个级别 chip 等宽三分，内部竖排两行（级别标签 + 计数）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DedupLevel.entries.forEach { level ->
                val selected = state.selectedTab == level
                val count = state.groups.count { group -> group.level == level }
                FilterChip(
                    selected = selected,
                    onClick = { onSelectTab(level) },
                    modifier = Modifier.weight(1f),
                    label = {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = stringResource(dedupLevelLabelRes(level)),
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "$count",
                                style = MaterialTheme.typography.labelMedium,
                                // 未选中 tab 的计数用次要色，选中态跟随 chip 内容色（稍弱）
                                color = if (selected) {
                                    LocalContentColor.current.copy(alpha = 0.8f)
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1
                            )
                        }
                    }
                )
            }
        }

        if (filteredGroups.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.dedup_no_groups),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(
                    items = filteredGroups,
                    key = { group -> group.id }
                ) { group ->
                    DedupGroupCard(
                        group = group,
                        policyLabel = policyLabel,
                        onOpenDetail = { onOpenGroupDetail(group.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DedupResultsBottomBar(
    deleteCount: Int,
    deleteBytes: Long,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Button(
            onClick = onDelete,
            modifier = Modifier.fillMaxWidth(),
            enabled = deleteCount > 0,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error
            )
        ) {
            Text(
                text = stringResource(
                    R.string.dedup_delete_cta,
                    deleteCount,
                    formatBytes(deleteBytes)
                )
            )
        }
        Text(
            text = stringResource(R.string.dedup_recycle_caption),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ---------- Cleaned ----------

@Composable
private fun DedupCleanedContent(
    state: DedupUiState.Cleaned,
    onUndoAll: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        Icon(
            Icons.Rounded.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = KeepGreen
        )
        Text(
            text = stringResource(R.string.dedup_cleaned_title, state.deletedCount),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = stringResource(R.string.dedup_cleaned_sub, formatBytes(state.reclaimedBytes)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.dedup_recycle_bin),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = stringResource(R.string.dedup_auto_clear),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    state.trashedUris.take(3).forEach { uri ->
                        DedupThumb(
                            uri = uri,
                            isKept = false,
                            role = VersionRole.UNKNOWN,
                            modifier = Modifier.size(64.dp)
                        )
                    }
                    val extra = state.trashedUris.size - 3
                    if (extra > 0) {
                        Text(
                            text = stringResource(R.string.dedup_more_count, extra),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                OutlinedButton(
                    onClick = onUndoAll,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.dedup_undo_all))
                }
            }
        }
    }
}

@Composable
private fun DedupCleanedBottomBar(
    onDone: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            onClick = onDone,
            modifier = Modifier.weight(1f)
        ) {
            Text(stringResource(R.string.done))
        }
        // 查看回收站：V1 占位，后续接系统回收站入口
        TextButton(onClick = { }) {
            Text(stringResource(R.string.dedup_view_recycle))
        }
    }
}

// ---------- 资源映射 ----------

private fun dedupLevelIcon(level: DedupLevel): ImageVector = when (level) {
    DedupLevel.EXACT -> Icons.Rounded.ContentCopy
    DedupLevel.VISUAL -> Icons.Rounded.Image
    DedupLevel.SCENE -> Icons.Rounded.PhotoLibrary
}

private fun dedupLevelLabelRes(level: DedupLevel): Int = when (level) {
    DedupLevel.EXACT -> R.string.dedup_level_exact
    DedupLevel.VISUAL -> R.string.dedup_level_visual
    DedupLevel.SCENE -> R.string.dedup_level_scene
}

private fun dedupLevelDescRes(level: DedupLevel): Int = when (level) {
    DedupLevel.EXACT -> R.string.dedup_scale_exact_desc
    DedupLevel.VISUAL -> R.string.dedup_scale_visual_desc
    DedupLevel.SCENE -> R.string.dedup_scale_scene_desc
}
