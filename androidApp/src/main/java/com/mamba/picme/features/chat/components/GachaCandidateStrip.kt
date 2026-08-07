package com.mamba.picme.features.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mamba.picme.R
import com.mamba.picme.features.chat.OptimizeCandidateGroup

/**
 * chat 对话内的 AI 优化候选卡条（spec §3.2）。
 *
 * - 点卡 = 选中高亮 + 全屏预览（预览由调用方处理）
 * - 「就用这张」：有选中卡且该卡未被护栏淘汰时可用
 * - [rerolling] = true（换一组进行中）时显示局部 loading 并禁用两个按钮
 * - [interactive] = false（进程重建后内存态丢失）时降级只读：隐藏按钮，提示已过期
 */
@Composable
fun GachaCandidateStrip(
    group: OptimizeCandidateGroup,
    interactive: Boolean,
    selectedIndex: Int,
    rerolling: Boolean,
    onSelect: (Int) -> Unit,
    onReroll: () -> Unit,
    onConfirm: () -> Unit
) {
    val hint = when {
        !interactive -> stringResource(R.string.chat_gacha_expired)
        group.recommendedIndex < 0 -> stringResource(R.string.ai_optimize_keep_hint)
        else -> stringResource(R.string.chat_gacha_pick_hint)
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                group.candidates.forEachIndexed { index, candidate ->
                    CandidateCard(
                        candidate = candidate,
                        recommended = index == group.recommendedIndex,
                        selected = index == selectedIndex,
                        enabled = interactive && !candidate.rejected,
                        onClick = { onSelect(index) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            if (interactive) {
                val selectedRejected = group.candidates
                    .getOrNull(selectedIndex)?.rejected != false
                Row(
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (rerolling) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    TextButton(onClick = onReroll, enabled = !rerolling) {
                        Text(stringResource(R.string.ai_optimize_reroll))
                    }
                    TextButton(
                        onClick = onConfirm,
                        enabled = !rerolling && selectedIndex >= 0 && !selectedRejected
                    ) {
                        Text(stringResource(R.string.chat_gacha_use_this))
                    }
                }
            }
        }
    }
}

@Composable
private fun CandidateCard(
    candidate: OptimizeCandidateGroup.Candidate,
    recommended: Boolean,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp))
                .then(
                    if (selected) {
                        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                    } else {
                        Modifier
                    }
                )
                .alpha(if (candidate.rejected) 0.4f else 1f)
                .clickable(enabled = enabled, onClick = onClick)
        ) {
            if (candidate.thumbPath.isNotBlank()) {
                AsyncImage(
                    model = candidate.thumbPath,
                    contentDescription = candidate.direction,
                    contentScale = ContentScale.Crop,
                    placeholder = ColorPainter(MaterialTheme.colorScheme.surface),
                    error = ColorPainter(MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .background(MaterialTheme.colorScheme.surface)
                )
            }
            if (recommended) {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(bottomEnd = 6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.ai_optimize_recommended),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }
        }
        Text(
            text = candidate.direction,
            style = MaterialTheme.typography.labelSmall,
            color = if (candidate.rejected) {
                MaterialTheme.colorScheme.outline
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}
