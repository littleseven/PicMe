package com.mamba.picme.features.editor.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mamba.picme.R
import com.mamba.picme.features.editor.PhotoEditorViewModel

/**
 * AI 优化抽卡对比导航条（先预览后应用）。
 *
 * 缩略图仅作导航识别；真正的效果对比在编辑器主预览区（点卡片全质量渲染，
 * 长按预览区可与原图对比）。NIMA 推荐卡带「推荐」角标；被淘汰的卡置灰不可点。
 * 「应用」提交当前预览的卡，「关闭」放弃并回退原图，「换一组」重抽。
 */
@Composable
fun GachaCandidateBar(
    run: PhotoEditorViewModel.GachaRunUiState,
    onApply: () -> Unit,
    onReroll: () -> Unit,
    onPreview: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = stringResource(
                    if (run.keepOriginal) R.string.ai_optimize_keep_hint
                    else R.string.ai_optimize_pick_hint
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                run.candidates.forEach { scored ->
                    key(scored.candidate.index) {
                        val previewing = scored.candidate.index == run.previewedIndex
                        val recommended = scored.candidate.index == run.recommendedIndex
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(enabled = !scored.rejected) {
                                    onPreview(scored.candidate.index)
                                }
                                .border(
                                    width = if (previewing) 2.dp else 0.dp,
                                    color = if (previewing) MaterialTheme.colorScheme.primary
                                    else Color.Transparent,
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .padding(4.dp)
                        ) {
                            val thumbModifier = Modifier
                                .size(84.dp)
                                .clip(RoundedCornerShape(6.dp))
                            Box {
                                val bmp = scored.thumbnail
                                if (bmp != null) {
                                    Image(
                                        bitmap = bmp.asImageBitmap(),
                                        contentDescription = scored.candidate.direction,
                                        contentScale = ContentScale.Crop,
                                        modifier = thumbModifier
                                    )
                                } else {
                                    // 渲染失败的候选：占位块保证布局一致，方向名仍可读、可点选
                                    Box(
                                        modifier = thumbModifier.background(
                                            MaterialTheme.colorScheme.surfaceContainerHighest
                                        )
                                    )
                                }
                                if (recommended) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.primary,
                                        shape = RoundedCornerShape(bottomEnd = 6.dp),
                                        modifier = Modifier.align(Alignment.TopStart)
                                    ) {
                                        Text(
                                            text = stringResource(R.string.ai_optimize_recommended),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.padding(horizontal = 4.dp)
                                        )
                                    }
                                }
                            }
                            Text(
                                text = scored.candidate.direction,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (scored.rejected) MaterialTheme.colorScheme.outline
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            Row(modifier = Modifier.align(Alignment.End)) {
                TextButton(
                    onClick = onApply,
                    enabled = run.previewedIndex >= 0
                ) {
                    Text(stringResource(R.string.ai_optimize_apply))
                }
                TextButton(onClick = onReroll) {
                    Text(stringResource(R.string.ai_optimize_reroll))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.ai_optimize_dismiss))
                }
            }
        }
    }
}
