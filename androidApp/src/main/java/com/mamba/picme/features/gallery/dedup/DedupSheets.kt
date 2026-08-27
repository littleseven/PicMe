package com.mamba.picme.features.gallery.dedup

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mamba.picme.R
import com.mamba.picme.domain.dedup.DedupGroup
import com.mamba.picme.domain.dedup.DedupMember
import com.mamba.picme.domain.dedup.KeepPolicy
import com.mamba.picme.domain.dedup.VersionRole
import java.text.DateFormat
import java.util.Date

private val HintOrange = Color(0xFFFF9800)

/**
 * 组详情弹层：标题 + 组 meta + 成员两列对比（缩略图 + 大小/时间 + 「保留这张」radio）
 * + EDITED 提示条 + 确认按钮。组数据经 uiState 重组实时取，setKeep 后立即刷新。
 * Scanning 态为只读预览（setKeep 仅 Results 生效）：隐藏 radio 与底部 CTA，不假装可交互。
 * 点缩略图经 [onPreview]（成员下标）进全屏对比预览，比较后再改选。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DedupGroupDetailSheet(
    groupId: String,
    viewModel: DedupViewModel,
    onDismiss: () -> Unit,
    onPreview: (Int) -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val group = remember(uiState) { viewModel.getGroup(groupId) }
    val editable = uiState is DedupUiState.Results

    ModalBottomSheet(onDismissRequest = onDismiss) {
        if (group == null) {
            // 组已消失（状态机已离开 Results/Scanning）：直接收起
            LaunchedEffect(Unit) { onDismiss() }
        } else {
            DedupGroupDetailContent(
                group = group,
                editable = editable,
                onKeep = { uri -> viewModel.setKeep(groupId, uri) },
                onPreview = onPreview,
                onConfirm = onDismiss,
            )
        }
    }
}

@Composable
private fun DedupGroupDetailContent(
    group: DedupGroup,
    editable: Boolean,
    onKeep: (String) -> Unit,
    onPreview: (Int) -> Unit,
    onConfirm: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.dedup_detail_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LevelBadge(level = group.level)
            Text(
                text = dedupGroupMetaText(group),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (group.members.any { member -> member.role == VersionRole.EDITED }) {
            Text(
                text = stringResource(R.string.dedup_hint_edited),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(HintOrange.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                color = HintOrange,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium
            )
        }

        group.members.chunked(2).forEachIndexed { chunkIndex, pair ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                pair.forEachIndexed { indexInPair, member ->
                    DedupMemberColumn(
                        member = member,
                        isKept = member.uri == group.keepUri,
                        editable = editable,
                        onKeep = onKeep,
                        onPreview = { onPreview(chunkIndex * 2 + indexInPair) },
                        modifier = Modifier.weight(1f)
                    )
                }
                if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
            }
        }

        // Scanning 态只读：不展示「确认删除」CTA
        if (editable) {
            Button(
                onClick = onConfirm,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            ) {
                // 未预选且未改选组 deleteUris 为空：按钮不显示「删除其余 0 张」，
                // 改为「确认本组选择」中性文案（spec §10.4 逐组确认语义）
                Text(
                    if (group.deleteUris.isEmpty()) {
                        stringResource(R.string.dedup_confirm_selection)
                    } else {
                        stringResource(R.string.dedup_confirm_keep, group.deleteUris.size)
                    }
                )
            }
        } else {
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/** 成员对比列：大图 + 大小/时间行 + 「保留这张」radio；点击任意处即改选保留项，
 *  点缩略图本身则进全屏对比预览（比较后再决策）。只读态无 radio、不可改选但可预览。 */
@Composable
private fun DedupMemberColumn(
    member: DedupMember,
    isKept: Boolean,
    editable: Boolean,
    onKeep: (String) -> Unit,
    onPreview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .then(if (editable) Modifier.clickable { onKeep(member.uri) } else Modifier),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        DedupThumb(
            uri = member.uri,
            isKept = isKept,
            role = member.role,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            onClick = onPreview
        )
        // DedupMember 无分辨率字段（仅 pixelArea）：统一显示大小 + 本地化日期
        Text(
            text = stringResource(
                R.string.dedup_member_meta,
                formatBytes(member.sizeBytes),
                dateFormat.format(Date(member.captureDate))
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (editable) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                RadioButton(selected = isKept, onClick = { onKeep(member.uri) })
                Text(
                    text = stringResource(R.string.dedup_keep_this),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

/** 保留规则弹层：四个 policy 单选行 + 说明 + 应用按钮（应用并对 Results 组重算）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeepRulesSheet(
    viewModel: DedupViewModel,
    onDismiss: () -> Unit,
) {
    val currentPolicy by viewModel.policy.collectAsState()
    var selected by remember { mutableStateOf(currentPolicy) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.dedup_rules_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            KeepPolicy.entries.forEach { policy ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { selected = policy }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(keepPolicyLabelRes(policy)),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = stringResource(keepPolicyDescRes(policy)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    RadioButton(
                        selected = selected == policy,
                        onClick = { selected = policy }
                    )
                }
            }
            Text(
                text = stringResource(R.string.dedup_rules_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = {
                    viewModel.applyPolicy(selected)
                    onDismiss()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            ) {
                Text(stringResource(R.string.dedup_apply))
            }
        }
    }
}
