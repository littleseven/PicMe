package com.mamba.picme.features.editor.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** 透明抠图预览用的棋盘格背景，提示用户背景已变为透明。 */
@Composable
fun CheckerboardBackground(modifier: Modifier = Modifier) {
    val light = Color(0xFFE6E6E6)
    val dark = Color(0xFFBDBDBD)
    val cell = 16.dp
    Canvas(modifier = modifier) {
        val n = cell.toPx()
        val cols = (size.width / n).toInt() + 1
        val rows = (size.height / n).toInt() + 1
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                drawRect(
                    color = if ((r + c) % 2 == 0) light else dark,
                    topLeft = Offset(c * n, r * n),
                    size = Size(n, n)
                )
            }
        }
    }
}
