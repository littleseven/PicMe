package com.mamba.picme.features.chat

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.caverock.androidsvg.SVG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 把 SVG 字符串（由端侧 JS `Chart.*` 生成）渲染成位图显示。
 *
 * 解析+栅格化放在后台线程（[Dispatchers.Default]），避免阻塞聊天列表滚动。
 * 解析失败时显示占位提示（不影响其它消息）。
 */
@Composable
fun ChartSvgImage(svg: String, modifier: Modifier = Modifier) {
    val bitmap by produceState<Bitmap?>(initialValue = null, svg) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                val parsed = SVG.getFromString(svg)
                val w = parsed.documentWidth.toInt().coerceAtLeast(1)
                val h = parsed.documentHeight.toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                parsed.renderToCanvas(Canvas(bmp))
                bmp
            }.getOrNull()
        }
    }

    val current = bitmap
    if (current != null) {
        Image(
            bitmap = current.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.FillWidth,
            modifier = modifier.fillMaxWidth()
        )
    } else {
        Box(
            modifier = modifier.fillMaxWidth().height(120.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "图表渲染中…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 聊天里的图表卡片：把 SVG 包一层圆角卡片后渲染。
 */
@Composable
fun ChartSvgCard(svg: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        ChartSvgImage(svg = svg, modifier = Modifier.padding(8.dp))
    }
}
