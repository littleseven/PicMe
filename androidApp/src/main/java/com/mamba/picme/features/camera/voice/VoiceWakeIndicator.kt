package com.mamba.picme.features.camera.voice

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp

private const val PULSE_DURATION_MS = 1200
private const val ALPHA_INITIAL = 0.3f
private const val ALPHA_TARGET = 1.0f
private const val SCALE_INITIAL = 0.95f
private const val SCALE_TARGET = 1.0f
private const val INDICATOR_HEIGHT_DP = 4
private const val CORNER_RADIUS_DP = 2
private const val HORIZONTAL_PADDING_DP = 48
private const val VERTICAL_PADDING_DP = 8

/**
 * 唤醒词监听状态指示器
 *
 * 当 WakeWord 模式激活时，在预览界面顶部显示呼吸灯效果，
 * 提示用户当前正在监听语音指令。
 *
 * @param isListening 是否正在监听
 * @param modifier Modifier
 */
@Suppress("FunctionNaming")
@Composable
fun VoiceWakeIndicator(
    isListening: Boolean,
    modifier: Modifier = Modifier
) {
    if (!isListening) return

    val infiniteTransition = rememberInfiniteTransition(label = "voiceWakePulse")

    val alpha by infiniteTransition.animateFloat(
        initialValue = ALPHA_INITIAL,
        targetValue = ALPHA_TARGET,
        animationSpec = infiniteRepeatable(
            animation = tween(PULSE_DURATION_MS, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val scale by infiniteTransition.animateFloat(
        initialValue = SCALE_INITIAL,
        targetValue = SCALE_TARGET,
        animationSpec = infiniteRepeatable(
            animation = tween(PULSE_DURATION_MS, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = HORIZONTAL_PADDING_DP.dp, vertical = VERTICAL_PADDING_DP.dp)
            .height(INDICATOR_HEIGHT_DP.dp)
            .scale(scale)
            .alpha(alpha)
            .clip(RoundedCornerShape(CORNER_RADIUS_DP.dp))
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center
    ) {
        // 呼吸灯条
    }
}
