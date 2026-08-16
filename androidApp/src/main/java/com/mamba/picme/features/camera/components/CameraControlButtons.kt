package com.mamba.picme.features.camera.components

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
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
 * 左上角返回箭头（iOS 系统相机风格；camera.yaml §3 back_button）：
 * 幽灵样式——无圆形底，纯白色 chevron 与顶部文字工具栏同基线。
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
            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = stringResource(R.string.back),
            tint = CameraTokens.cameraAccentOn,
            modifier = Modifier.size(24.dp)
        )
    }
}

/**
 * 顶部居中文字工具栏（iOS 系统相机风格；specs/screens/camera.yaml §4 top_tool_bar）。
 * 5 个文字胶囊入口：美颜 / 比例 / 辅助线 / 滤镜 / 专业。
 * 选中态 = primary 胶囊底 + cameraAccentOn 字（camera.toolBarSelectedBg/cameraAccentOn），
 * 未选中 = 透明底 + 白字（camera.toolBarUnselectedBg）。
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
                if (isSelected) MaterialTheme.colorScheme.primary else CameraTokens.toolBarUnselectedBg
            )
            .clickable(onClick = onClick)
            .padding(
                horizontal = CameraTokens.topToolBarItemPaddingH,
                vertical = CameraTokens.topToolBarItemPaddingV
            )
    )
}

/**
 * 顶部内联面板外壳（camera.yaml §4 inline_panels）：
 * maxWidth 420、圆角 camera.panelCornerRadius(16)、surface@0.85 + 0.5dp 描边 + 12dp 阴影
 * （iOS 侧为 ultraThinMaterial 玻璃，平台材质差异见 spec allowed_differences.panel_material）。
 */
@Composable
fun InlineControlPanel(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier
            .padding(horizontal = CameraTokens.panelPaddingH)
            .widthIn(max = CameraTokens.panelMaxWidth)
            .fillMaxWidth(),
        shape = RoundedCornerShape(CameraTokens.panelCornerRadius),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        shadowElevation = CameraTokens.panelShadowElevation,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
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
 * 顶部内联选项 chip 胶囊行（camera.yaml §4 inline_panels chip 行样式）：
 * 尺寸全走 token（camera.chipHeight/chipPaddingH/chipSpacing），字号 13 Medium；
 * 选中 = primary 底 + cameraAccentOn 字，未选中 = cameraAccentOn 15% 底 + cameraAccentOn 字。
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
                            MaterialTheme.colorScheme.primary
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
                    fontSize = 13.sp,
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
