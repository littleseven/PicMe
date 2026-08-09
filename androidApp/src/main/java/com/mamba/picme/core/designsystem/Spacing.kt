package com.mamba.picme.core.designsystem

import androidx.compose.ui.unit.dp

/**
 * 全局间距令牌（双端 SSOT: `shared/src/commonMain/resources/design-tokens.json`）。
 *
 * 新增/修改尺寸时：先更新 JSON 源文件，再同步此 object。
 * 引用方式：`MaterialTheme.spacing.sm` 或直接 `Spacing.sm`。
 */
object Spacing {
    val xs = 4.dp   // 微间距（分割线内边距、badge 边距）
    val sm = 8.dp   // 小间距（同一控件组内元素间距）
    val md = 12.dp  // 中间距（控件组间距）
    val lg = 16.dp  // 大间距（页面内边距）
    val xl = 24.dp  // 特大间距（面板圆角、卡片间距）
    val xxl = 32.dp // 超大间距（页面水平内边距、图标尺寸）
}
