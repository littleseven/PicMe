package com.mamba.picme.core.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * 全局圆角令牌（双端 SSOT: `shared/src/commonMain/resources/design-tokens.json`）。
 *
 * 新增/修改尺寸时：先更新 JSON 源文件，再同步此 object。
 */
object AppShapes {
    val panel = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    val card = RoundedCornerShape(12.dp)
    val button = RoundedCornerShape(10.dp)
    val small = RoundedCornerShape(8.dp)
    val thumbnail = RoundedCornerShape(2.dp)
}
