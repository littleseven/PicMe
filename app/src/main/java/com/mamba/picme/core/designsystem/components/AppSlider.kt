package com.mamba.picme.core.designsystem.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import android.content.res.Configuration
import com.mamba.picme.core.designsystem.PoLangTheme
import com.mamba.picme.core.designsystem.SliderThumbColor

private val TrackHeight = 6.dp
private val TrackShape = RoundedCornerShape(percent = 50)
private val ThumbSize = 18.dp
private const val THUMB_PRESSED_SCALE = 1.15f

/**
 * 全 app 统一滑杆（HyperOS 风）：胶囊轨道 + 白圆点 primary 描边 thumb + 按压放大。
 *
 * API 对齐 M3 [Slider] 的最小必要子集。不提供配色参数——颜色固定从
 * `MaterialTheme.colorScheme` 取（primary / onSurface 12%），深浅色自适应，
 * 防止各页面再次自定义导致样式碎片化。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val thumbScale by animateFloatAsState(
        targetValue = if (isPressed) THUMB_PRESSED_SCALE else 1f,
        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
        label = "thumbScale"
    )
    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)

    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        valueRange = valueRange,
        steps = steps,
        onValueChangeFinished = onValueChangeFinished,
        interactionSource = interactionSource,
        thumb = {
            Spacer(
                modifier = Modifier
                    .size(ThumbSize)
                    .scale(thumbScale)
                    .shadow(elevation = 2.dp, shape = CircleShape)
                    .background(SliderThumbColor, CircleShape)
                    .border(2.dp, activeColor, CircleShape)
            )
        },
        track = { sliderState ->
            val fraction = sliderState.valueRange.run {
                ((value - start) / (endInclusive - start)).coerceIn(0f, 1f)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(TrackHeight)
                    .clip(TrackShape)
                    .background(inactiveColor)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .background(activeColor)
                )
            }
        }
    )
}

@Preview(name = "Light", showBackground = true)
@Composable
private fun AppSliderPreviewLight() {
    PoLangTheme {
        AppSliderPreviewContent()
    }
}

@Preview(
    name = "Dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    showBackground = true,
    backgroundColor = 0xFF1C1B1F
)
@Composable
private fun AppSliderPreviewDark() {
    PoLangTheme {
        AppSliderPreviewContent()
    }
}

@Composable
private fun AppSliderPreviewContent() {
    Column(modifier = Modifier.padding(16.dp)) {
        AppSlider(value = 0.3f, onValueChange = {})
        Spacer(modifier = Modifier.height(16.dp))
        AppSlider(value = 0.7f, onValueChange = {})
    }
}
