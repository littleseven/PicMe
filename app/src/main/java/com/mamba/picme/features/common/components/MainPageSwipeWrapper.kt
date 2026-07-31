package com.mamba.picme.features.common.components

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 主页面全屏横滑切换包装器。
 *
 * 在内容区域任意位置响应水平拖动（类似多 Tab 切换），拖动距离超过阈值后通过 [onPageChanged]
 * 通知外层切换页面。无需限定在屏幕左右边缘。
 *
 * @param enabled 是否启用横滑检测
 * @param currentIndex 当前页面索引
 * @param pageCount 总页面数
 * @param onPageChanged 切换请求回调，参数为目标页面索引
 * @param swipeThreshold 触发切换的最小水平拖动距离
 * @param content 子内容
 */
@Composable
fun MainPageSwipeWrapper(
    enabled: Boolean,
    currentIndex: Int,
    pageCount: Int,
    onPageChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
    swipeThreshold: Dp = 40.dp,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { swipeThreshold.toPx() }

    Box(
        modifier = modifier
            .pointerInput(enabled, currentIndex, pageCount, swipeThresholdPx) {
                var totalDrag = 0f
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, dragAmount ->
                        totalDrag += dragAmount
                        change.consume()
                    },
                    onDragEnd = {
                        when {
                            totalDrag >= swipeThresholdPx -> {
                                val target = (currentIndex - 1 + pageCount) % pageCount
                                onPageChanged(target)
                            }

                            totalDrag <= -swipeThresholdPx -> {
                                val target = (currentIndex + 1) % pageCount
                                onPageChanged(target)
                            }
                        }
                        totalDrag = 0f
                    },
                    onDragCancel = {
                        totalDrag = 0f
                    }
                )
            }
    ) {
        content()
    }
}
