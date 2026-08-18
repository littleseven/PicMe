package com.mamba.picme.features.camera.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mamba.picme.R
import com.mamba.picme.core.designsystem.CameraTokens

@OptIn(ExperimentalLayoutApi::class) // statusBarsIgnoringVisibility：沉浸式下仍避让刘海
@Composable
fun CameraLeftControls(
    onToggleLogOverlay: () -> Unit,
    debugUiEnabled: Boolean,
    showLogOverlay: Boolean,
    onLlmRelease: () -> Unit = {},
    onFaceDetectRelease: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (!debugUiEnabled) return
    Column(
        modifier = modifier
            .padding(16.dp)
            // 🔴 相机页沉浸式隐藏系统栏后 statusBars inset 归零（statusBarsPadding 失效），
            // 必须用 IgnoringVisibility 才能稳定避让刘海/挖孔区域
            .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ControlButton(
            icon = Icons.Rounded.Terminal,
            onClick = onToggleLogOverlay,
            contentDescription = stringResource(R.string.a11y_log_overlay),
            isActive = showLogOverlay
        )

        // LLM 全量释放
        ControlButton(
            icon = Icons.Rounded.Psychology,
            onClick = onLlmRelease,
            contentDescription = stringResource(R.string.a11y_release_llm),
            tint = Color(0xFF64B5F6),
            modifier = Modifier.size(36.dp)
        )

        // Face Detection 全量释放
        ControlButton(
            icon = Icons.Rounded.Face,
            onClick = onFaceDetectRelease,
            contentDescription = stringResource(R.string.a11y_release_face),
            tint = Color(0xFF81C784),
            modifier = Modifier.size(36.dp)
        )
    }
}

/**
 * 左上角返回箭头（2026-08-18 Ardot 定稿；camera.yaml §3 back_button）：
 * 幽灵样式细 chevron（KeyboardArrowLeft，对应 SF chevron.left——无尾粗箭头），
 * 融入顶部工具栏行：icon 中心 x=28dp、距「美颜」胶囊 8dp、与胶囊行垂直居中。
 * （重置相机入口已迁至设置页「相机」分类。）
 */
@Composable
fun CameraBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
            contentDescription = stringResource(R.string.back),
            tint = CameraTokens.cameraAccentOn,
            modifier = Modifier.size(24.dp)
        )
    }
}

/**
 * 顶部居中文字工具栏（iOS 系统相机风格；specs/screens/camera.yaml §4 top_tool_bar）。
 * 5 个文字胶囊入口：美颜 / 比例 / 辅助线 / 滤镜 / 专业。
 * 选中态 = cameraAccent(#0F766E 深青玉) 胶囊底 + cameraAccentOn 字，未选中 = 透明底 + 白字
 * （2026-08-18 Ardot 定稿：固定深青玉替代 colorScheme.primary，白字对比度保障）。
 */
@Composable
fun CameraTopToolBar(
    isBeautySelected: Boolean,
    isRatioSelected: Boolean,
    isGridSelected: Boolean,
    isFilterSelected: Boolean,
    isProSelected: Boolean,
    onToggleBeauty: () -> Unit,
    onToggleRatio: () -> Unit,
    onToggleGrid: () -> Unit,
    onToggleFilter: () -> Unit,
    onToggleProPanel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(CameraTokens.topToolBarSpacing)
    ) {
        TopToolBarItem(stringResource(R.string.beauty), isBeautySelected, onToggleBeauty)
        TopToolBarItem(stringResource(R.string.camera_tool_ratio), isRatioSelected, onToggleRatio)
        TopToolBarItem(stringResource(R.string.grid), isGridSelected, onToggleGrid)
        TopToolBarItem(stringResource(R.string.a11y_filter), isFilterSelected, onToggleFilter)
        TopToolBarItem(stringResource(R.string.camera_tool_pro), isProSelected, onToggleProPanel)
    }
}

@Composable
private fun TopToolBarItem(label: String, isSelected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = CameraTokens.cameraAccentOn,
        fontSize = 12.sp,
        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(CameraTokens.topToolBarItemRadius))
            .background(
                if (isSelected) CameraTokens.cameraAccent else CameraTokens.toolBarUnselectedBg
            )
            .clickable(onClick = onClick)
            .padding(
                horizontal = CameraTokens.topToolBarItemPaddingH,
                vertical = CameraTokens.topToolBarItemPaddingV
            )
    )
}

/**
 * 顶部内联面板外壳（2026-08-18 Ardot 五修，camera.yaml §4 inline_panels）：
 * - filter/pro（fillWidth=true）：统一宽度 = 屏宽 − 2×panelSideMargin(28)，上限 panelMaxWidth(420)；
 * - ratio/grid（fillWidth=false）：hug 内容宽（紧凑精致，chip 行包裹即止，2026-08-18 用户决策）；
 * 共同：圆角 panelCornerRadius(16)、#1C1A1F@72% 底、无描边、12dp 阴影。
 */
@Composable
fun InlineControlPanel(
    modifier: Modifier = Modifier,
    fillWidth: Boolean = true,
    content: @Composable () -> Unit
) {
    val widthModifier = if (fillWidth) {
        Modifier
            .padding(horizontal = CameraTokens.panelSideMargin)
            .widthIn(max = CameraTokens.panelMaxWidth)
            .fillMaxWidth()
    } else {
        Modifier.widthIn(max = CameraTokens.panelMaxWidth)
    }
    Surface(
        modifier = modifier.then(widthModifier),
        shape = RoundedCornerShape(CameraTokens.panelCornerRadius),
        color = CameraTokens.panelBackground,
        shadowElevation = CameraTokens.panelShadowElevation
    ) {
        Box(
            modifier = Modifier
                .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
                .padding(horizontal = CameraTokens.panelPaddingH, vertical = CameraTokens.panelPaddingV),
            contentAlignment = Alignment.Center
        ) {
            content()
        }
    }
}

/** 内联选项 chip 数据（比例/辅助线选择行用）。 */
class SelectorChip(
    val label: String,
    val isSelected: Boolean,
    val onClick: () -> Unit
)

/**
 * 顶部内联选项 chip 胶囊行（2026-08-18 Ardot：高 40 全圆胶囊、15sp Medium）：
 * 尺寸全走 token（camera.chipHeight/chipPaddingH/chipSpacing/chipFontSize）；
 * 选中 = cameraAccent(#0F766E) 底 + cameraAccentOn 字，未选中 = cameraAccentOn 15% 底。
 */
@Composable
fun SelectorChipRow(vararg chips: SelectorChip) {
    Row(horizontalArrangement = Arrangement.spacedBy(CameraTokens.chipSpacing)) {
        chips.forEach { chip ->
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(
                        if (chip.isSelected) {
                            CameraTokens.cameraAccent
                        } else {
                            CameraTokens.cameraAccentOn.copy(alpha = 0.15f)
                        }
                    )
                    .clickable(onClick = chip.onClick)
                    .height(CameraTokens.chipHeight)
                    .padding(horizontal = CameraTokens.chipPaddingH),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = chip.label,
                    color = CameraTokens.cameraAccentOn,
                    fontSize = CameraTokens.chipFontSize.value.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
fun ControlButton(
    icon: ImageVector,
    onClick: () -> Unit,
    contentDescription: String,
    isActive: Boolean = false,
    tint: Color? = null,
    modifier: Modifier = Modifier
) {
    FilledIconButton(
        onClick = onClick,
        modifier = modifier.size(48.dp),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = if (isActive) {
                MaterialTheme.colorScheme.primary
            } else {
                Color.Black.copy(alpha = 0.5f)
            },
            contentColor = tint ?: if (isActive) Color.Black else Color.White
        )
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription, modifier = Modifier.size(24.dp))
    }
}
