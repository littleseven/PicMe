package com.mamba.picme.features.gallery.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import coil.compose.AsyncImage
import kotlin.math.abs

/**
 * 可双指缩放/平移的全屏图片（1x~4x，缩放中禁用外层 Pager 滚动手势由调用方经
 * [onZoomStateChanged] 联动）。自 MediaPager 抽出共享：相册查看器与去重组对比预览同源。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ZoomableImage(
    uri: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onZoomStateChanged: (Float) -> Unit,
) {
    var scale by remember(uri) { mutableStateOf(1f) }
    var offset by remember(uri) { mutableStateOf(Offset.Zero) }
    var containerSize by remember(uri) { mutableStateOf(IntSize.Zero) }

    fun clampOffset(nextOffset: Offset, nextScale: Float): Offset {
        if (nextScale <= 1f || containerSize.width == 0 || containerSize.height == 0) {
            return Offset.Zero
        }
        val maxX = (containerSize.width * (nextScale - 1f)) / 2f
        val maxY = (containerSize.height * (nextScale - 1f)) / 2f
        return Offset(
            x = nextOffset.x.coerceIn(-maxX, maxX),
            y = nextOffset.y.coerceIn(-maxY, maxY)
        )
    }

    SideEffect {
        onZoomStateChanged(scale)
    }

    val isZoomed = scale > 1.02f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                containerSize = size
                offset = clampOffset(offset, scale)
            }
            .then(
                if (isZoomed) {
                    Modifier.pointerInput(uri) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val nextScale = (scale * zoom).coerceIn(1f, 4f)
                            val nextOffset = clampOffset(offset + pan, nextScale)
                            scale = nextScale
                            offset = if (abs(nextScale - 1f) < 0.01f) {
                                Offset.Zero
                            } else {
                                nextOffset
                            }
                        }
                    }
                } else {
                    Modifier
                }
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        AsyncImage(
            model = uri,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
            contentScale = ContentScale.Fit
        )
    }
}
