package com.mamba.picme.features.common.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * 主页面边缘横滑切换包装器。
 *
 * 仅在屏幕左右边缘的窄带内响应水平拖动，触发后通过 [onPageChanged] 通知外层切换页面。
 * 实际触发带会向屏幕内侧偏移系统手势区宽度，避免与系统返回手势冲突。
 *
 * @param enabled 是否启用横滑检测
 * @param currentIndex 当前页面索引
 * @param pageCount 总页面数
 * @param onPageChanged 切换请求回调，参数为目标页面索引
 * @param edgeWidth 边缘检测带宽度
 * @param swipeThreshold 触发切换的最小拖动距离
 * @param content 子内容
 */
@Composable
fun MainPageSwipeWrapper(
    enabled: Boolean,
    currentIndex: Int,
    pageCount: Int,
    onPageChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
    edgeWidth: Dp = 24.dp,
    swipeThreshold: Dp = 40.dp,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val edgeWidthPx = with(density) { edgeWidth.toPx() }
    val swipeThresholdPx = with(density) { swipeThreshold.toPx() }

    // 在 Composable 上下文中获取系统手势 insets，再传入 pointerInput
    val systemInsets = WindowInsets.systemGestures
    val leftInsetPx = systemInsets.getLeft(density, LayoutDirection.Ltr).toFloat()
    val rightInsetPx = systemInsets.getRight(density, LayoutDirection.Ltr).toFloat()

    Box(
        modifier = modifier
            .pointerInput(
                enabled,
                currentIndex,
                pageCount,
                edgeWidthPx,
                swipeThresholdPx,
                leftInsetPx,
                rightInsetPx
            ) {
                awaitEachGesture {
                    if (!enabled) return@awaitEachGesture

                    val down = awaitFirstDown()
                    val x = down.position.x
                    val width = size.width.toFloat()

                    val inLeftEdge = x in leftInsetPx..(leftInsetPx + edgeWidthPx)
                    val inRightEdge = x in (width - rightInsetPx - edgeWidthPx)..(width - rightInsetPx)
                    if (!inLeftEdge && !inRightEdge) return@awaitEachGesture

                    val change = awaitTouchSlopOrCancellation(down.id) { pointerChange, _ ->
                        val dx = pointerChange.positionChange().x
                        if (abs(dx) > 0) {
                            pointerChange.consume()
                        }
                    } ?: return@awaitEachGesture

                    var totalDrag = 0f
                    val dragConsumed = horizontalDrag(change.id) { pointerChange ->
                        val dx = pointerChange.positionChange().x
                        totalDrag += dx
                        pointerChange.consume()
                    }

                    if (!dragConsumed) return@awaitEachGesture

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
                }
            }
    ) {
        content()
    }
}
