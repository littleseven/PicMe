package com.mamba.picme.features.gallery.dedup

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val HintOrange = Color(0xFFFF9800)

/**
 * 组详情弹层：标题 + 组 meta + 成员两列对比（缩略图 + 大小/时间 + 「保留这张」radio）
 * + EDITED 提示条 + 确认按钮。组数据经 uiState 重组实时取，setKeep 后立即刷新。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DedupGroupDetailSheet(
    groupId: String,
    viewModel: DedupViewModel,
    onDismiss: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val group = remember(uiState) { viewModel.getGroup(groupId) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        if (group == null) {
            // 组已消失（状态机已离开 Results/Scanning）：直接收起
            LaunchedEffect(Unit) { onDismiss() }
        } else {
            DedupGroupDetailContent(
                group = group,
                onKeep = { uri -> viewModel.setKeep(groupId, uri) },
                onConfirm = onDismiss,
            )
        }
    }
}

@Composable
private fun DedupGroupDetailContent(
    group: DedupGroup,
    onKeep: (String) -> Unit,
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
                text = stringResource(
                    R.string.dedup_group_meta,
                    group.members.size,
                    formatBytes(group.reclaimBytes)
                ),
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

        group.members.chunked(2).forEach { pair ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                pair.forEach { member ->
                    DedupMemberColumn(
                        member = member,
                        isKept = member.uri == group.keepUri,
                        onKeep = onKeep,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
            }
        }

        Button(
            onClick = onConfirm,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
        ) {
            Text(stringResource(R.string.dedup_confirm_keep, group.deleteUris.size))
        }
    }
}

/** 成员对比列：大图 + 大小/时间行 + 「保留这张」radio；点击任意处即改选保留项。 */
@Composable
private fun DedupMemberColumn(
    member: DedupMember,
    isKept: Boolean,
    onKeep: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { onKeep(member.uri) },
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        DedupThumb(
            uri = member.uri,
            isKept = isKept,
            role = member.role,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
        )
        // DedupMember 无分辨率字段（仅 pixelArea）：统一显示大小 + 日期
        Text(
            text = stringResource(
                R.string.dedup_member_meta,
                formatBytes(member.sizeBytes),
                SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(member.captureDate))
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
