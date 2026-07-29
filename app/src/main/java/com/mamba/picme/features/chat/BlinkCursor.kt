package com.mamba.picme.features.chat

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * 流式打字光标：与 AI 文本同色的细竖线，alpha 周期闪烁（≈530ms 一周期）。
 * 纯装饰、无字符串资源（非语义文本）。
 */
@Composable
fun BlinkCursor(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "blink")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 265, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "blinkAlpha"
    )
    val cursorColor = MaterialTheme.colorScheme.onSurface
    Box(
        modifier = modifier
            .padding(start = 2.dp, top = 2.dp, bottom = 2.dp)
            .size(width = 2.dp, height = 14.dp)
            .clip(RoundedCornerShape(1.dp))
            .background(cursorColor)
            .alpha(alpha),
        contentAlignment = Alignment.Center
    ) {}
}
