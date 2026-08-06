package com.mamba.picme.features.idphoto.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mamba.picme.R
import com.mamba.picme.domain.matting.StrokeMode

/** 修补面板状态（由 Screen 从 ViewModel/本地态组装）。 */
data class RepairPanelState(
    val mode: StrokeMode,
    val brushSizePx: Float,
    val softEdge: Boolean,
    val canUndo: Boolean,
    val canRedo: Boolean,
    val hasStrokes: Boolean
)

/** 修补面板回调集合。 */
data class RepairPanelCallbacks(
    val onModeChange: (StrokeMode) -> Unit,
    val onBrushSizeChange: (Float) -> Unit,
    val onSoftEdgeChange: (Boolean) -> Unit,
    val onUndo: () -> Unit,
    val onRedo: () -> Unit,
    val onClear: () -> Unit
)

/** 修补面板：恢复/擦除模式 + 笔刷大小 + 软边 + 撤销/重做/清除。 */
@Composable
fun RepairPanel(state: RepairPanelState, callbacks: RepairPanelCallbacks) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                StrokeMode.RESTORE to R.string.id_photo_repair_restore,
                StrokeMode.ERASE to R.string.id_photo_repair_erase
            ).forEach { (m, labelRes) ->
                FilterChip(
                    selected = state.mode == m,
                    onClick = { callbacks.onModeChange(m) },
                    label = {
                        Text(
                            stringResource(labelRes),
                            color = if (state.mode == m) MaterialTheme.colorScheme.onPrimary else Color.White
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = Color(0xFF2A2A2A),
                        selectedContainerColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.id_photo_repair_brush_size),
                color = Color.White, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            Text(
                "${state.brushSizePx.toInt()}px", color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall
            )
        }
        // 笔刷尺寸无重计算开销，实时更新以保证拖动手感（区别于 EdgePanel 的松手才回调）
        Slider(
            value = state.brushSizePx,
            onValueChange = callbacks.onBrushSizeChange,
            valueRange = 8f..80f,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = Color(0xFF3A3A3A)
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.id_photo_repair_soft_edge),
                color = Color.White, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            Switch(checked = state.softEdge, onCheckedChange = callbacks.onSoftEdgeChange)
        }
        Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = callbacks.onUndo, enabled = state.canUndo) {
                Text(stringResource(R.string.id_photo_repair_undo))
            }
            TextButton(onClick = callbacks.onRedo, enabled = state.canRedo) {
                Text(stringResource(R.string.id_photo_repair_redo))
            }
            TextButton(onClick = callbacks.onClear, enabled = state.hasStrokes) {
                Text(stringResource(R.string.id_photo_repair_clear))
            }
        }
    }
}
