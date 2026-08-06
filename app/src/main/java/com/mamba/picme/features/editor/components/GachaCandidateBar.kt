package com.mamba.picme.features.editor.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
 * AI 优化抽卡结果条。
 *
 * 收起态：自动选优/保持原图说明 +「换一组」+「关闭」；
 * 展开态（换一组后）：4 卡缩略图对比，点选应用；被淘汰的卡置灰不可点。
 */
@Composable
fun GachaCandidateBar(
    run: PhotoEditorViewModel.GachaRunUiState,
    onReroll: () -> Unit,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        if (run.expanded) {
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    text = stringResource(R.string.ai_optimize_pick_hint),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    run.candidates.forEach { scored ->
                        val selected = scored.candidate.index == run.selectedIndex
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(enabled = !scored.rejected) {
                                    onPick(scored.candidate.index)
                                }
                                .border(
                                    width = if (selected) 2.dp else 0.dp,
                                    color = if (selected) MaterialTheme.colorScheme.primary
                                    else Color.Transparent,
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .padding(4.dp)
                        ) {
                            scored.thumbnail?.let { bmp ->
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = scored.candidate.direction,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(72.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                )
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
                Row(modifier = Modifier.align(Alignment.End)) {
                    TextButton(onClick = onReroll) {
                        Text(stringResource(R.string.ai_optimize_reroll))
                    }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.ai_optimize_dismiss))
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(
                        if (run.keepOriginal) R.string.ai_optimize_keep_original
                        else R.string.ai_optimize_best_applied
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
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
