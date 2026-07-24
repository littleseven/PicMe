package com.mamba.picme.features.chat

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.caverock.androidsvg.SVG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 把 SVG 字符串（端侧 JS `Chart.*` 生成）渲染成位图显示。
 *
 * 实现策略（稳健、不依赖 onSizeChanged）：
 * - 一次性按**固定较高分辨率**（原生 ×2.5，约 1600×950）栅格化，足够手机屏清晰；
 * - 由调用方用 [ContentScale] 决定铺排：
 *   - 卡片：`Modifier.fillMaxWidth().aspectRatio(...)` + [ContentScale.FillBounds]（按图宽高比给高度，整图铺满、不变形）；
 *   - 全屏：`Modifier.fillMaxSize()` + [ContentScale.Fit]（contain 进整屏，整图可见、不裁切）。
 * - 解析+栅格化在后台线程。
 */
@Composable
fun ChartSvgImage(
    svg: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit
) {
    val bitmap by produceState<Bitmap?>(initialValue = null, svg) {
        value = withContext(Dispatchers.Default) {
            runCatching { renderChartHighRes(svg) }.getOrNull()
        }
    }

    val current = bitmap
    if (current != null) {
        Image(
            bitmap = current.asImageBitmap(),
            contentDescription = null,
            contentScale = contentScale,
            modifier = modifier
        )
    } else {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                "图表渲染中…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 解析 SVG 的 width/height，返回宽高比（width / height）。解析失败回退 640/380。
 */
fun chartAspect(svg: String): Float {
    val w = Regex("""width="(\d+)"""").find(svg)?.groupValues?.get(1)?.toFloatOrNull() ?: 640f
    val h = Regex("""height="(\d+)"""").find(svg)?.groupValues?.get(1)?.toFloatOrNull() ?: 380f
    return if (w > 0f && h > 0f) w / h else (640f / 380f)
}

/**
 * 把 SVG 按固定倍率 [SCALE] 栅格化（640×380 → 1600×950），保持原始宽高比、清晰、内存可控。
 */
private fun renderChartHighRes(svg: String): Bitmap {
    val parsed = SVG.getFromString(svg)
    val sw = parsed.documentWidth.takeIf { it > 0f } ?: 640f
    val sh = parsed.documentHeight.takeIf { it > 0f } ?: 380f
    val rw = (sw * SCALE).toInt().coerceAtLeast(1)
    val rh = (sh * SCALE).toInt().coerceAtLeast(1)
    val bmp = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    canvas.scale(SCALE, SCALE)
    parsed.renderToCanvas(canvas)
    return bmp
}

private const val SCALE = 2.5f

/**
 * 聊天里的图表卡片：圆角卡片 + 按图宽高比给高度，整图铺满、不变形。
 *
 * @param onClick 非空时卡片可点击（用于全屏查看）；为 null 则纯展示。
 */
@Composable
fun ChartSvgCard(svg: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val base = modifier.fillMaxWidth()
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = if (onClick != null) base.clickable { onClick() } else base
    ) {
        ChartSvgImage(
            svg = svg,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(chartAspect(svg))
                .padding(8.dp),
            contentScale = ContentScale.FillBounds
        )
    }
}
