package com.mamba.picme.features.editor.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mamba.picme.beauty.api.FilterType
import com.mamba.picme.beauty.api.StyleFilter
import com.mamba.picme.beauty.api.displayNameRes

/**
 * 编辑页滤镜面板。
 *
 * 使用横向滚动的圆形滤镜列表，占用更少的垂直空间，让预览图更大。
 * 支持色调滤镜（FilterType）与风格特效（StyleFilter），两者互斥。
 */
@Composable
fun FilterPanel(
    colorFilter: FilterType,
    styleFilter: StyleFilter,
    onChange: (FilterType, StyleFilter) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val items = remember {
        listOf(
            FilterListItem.Color(FilterType.NONE),
            FilterListItem.Color(FilterType.LEICA_CLASSIC),
            FilterListItem.Color(FilterType.LEICA_VIBRANT),
            FilterListItem.Color(FilterType.LEICA_BW),
            FilterListItem.Color(FilterType.FILM_GOLD),
            FilterListItem.Color(FilterType.FILM_FUJI),
            FilterListItem.Color(FilterType.VINTAGE),
            FilterListItem.Color(FilterType.COOL),
            FilterListItem.Color(FilterType.WARM),
            FilterListItem.Style(StyleFilter.TOON),
            FilterListItem.Style(StyleFilter.SKETCH),
            FilterListItem.Style(StyleFilter.POSTERIZE),
            FilterListItem.Style(StyleFilter.EMBOSS),
            FilterListItem.Style(StyleFilter.CROSSHATCH)
        )
    }

    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .height(120.dp)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(items, key = { it.key }) { item ->
            val isSelected = when (item) {
                is FilterListItem.Color -> colorFilter == item.filter && styleFilter == StyleFilter.NONE
                is FilterListItem.Style -> styleFilter == item.style && colorFilter == FilterType.NONE
            }
            FilterChip(
                item = item,
                isSelected = isSelected,
                onClick = {
                    when (item) {
                        is FilterListItem.Color -> onChange(item.filter, StyleFilter.NONE)
                        is FilterListItem.Style -> onChange(FilterType.NONE, item.style)
                    }
                },
                context = context
            )
        }
    }
}

private sealed class FilterListItem {
    abstract val key: String

    data class Color(val filter: FilterType) : FilterListItem() {
        override val key: String = "color_${filter.name}"
    }

    data class Style(val style: StyleFilter) : FilterListItem() {
        override val key: String = "style_${style.name}"
    }
}

@Composable
private fun FilterChip(
    item: FilterListItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    context: android.content.Context
) {
    val label = when (item) {
        is FilterListItem.Color -> stringResource(item.filter.displayNameRes)
        is FilterListItem.Style -> stringResource(item.style.displayNameRes)
    }
    val assetPath = when (item) {
        is FilterListItem.Color -> filterAssetPath(item.filter)
        is FilterListItem.Style -> styleAssetPath(item.style)
    }
    val thumbnail = remember(assetPath) {
        try {
            context.assets.open(assetPath).use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) {
            null
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(72.dp)
            .clickable { onClick() }
            .padding(horizontal = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .border(
                    width = if (isSelected) 3.dp else 1.dp,
                    brush = if (isSelected) {
                        Brush.linearGradient(
                            listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onSurface)
                        )
                    } else {
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                            )
                        )
                    },
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail.asImageBitmap(),
                    contentDescription = label,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                                )
                            )
                        )
                )
            }
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                )
            }
        }
        Text(
            text = label,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
            fontSize = 10.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

private fun filterAssetPath(filter: FilterType): String {
    return when (filter) {
        FilterType.NONE -> "filters/filter_none.jpg"
        FilterType.LEICA_CLASSIC -> "filters/filter_leica_classic.jpg"
        FilterType.LEICA_VIBRANT -> "filters/filter_leica_vibrant.jpg"
        FilterType.LEICA_BW -> "filters/filter_leica_bw.jpg"
        FilterType.FILM_GOLD -> "filters/filter_film_gold.jpg"
        FilterType.FILM_FUJI -> "filters/filter_film_fuji.jpg"
        FilterType.VINTAGE -> "filters/filter_vintage.jpg"
        FilterType.COOL -> "filters/filter_cool.jpg"
        FilterType.WARM -> "filters/filter_warm.jpg"
    }
}

private fun styleAssetPath(style: StyleFilter): String {
    return when (style) {
        StyleFilter.TOON -> "filters/style_toon.jpg"
        StyleFilter.SKETCH -> "filters/style_sketch.jpg"
        StyleFilter.POSTERIZE -> "filters/style_posterize.jpg"
        StyleFilter.EMBOSS -> "filters/style_emboss.jpg"
        StyleFilter.CROSSHATCH -> "filters/style_crosshatch.jpg"
        else -> "filters/style_toon.jpg"
    }
}
