package com.mamba.picme.features.camera.components

import android.graphics.BitmapFactory
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mamba.picme.beauty.api.FilterType
import com.mamba.picme.beauty.api.StyleFilter
import com.mamba.picme.beauty.api.displayNameRes

@Composable
fun UnifiedFilterSelector(
    selectedFilter: FilterType,
    selectedStyleFilter: StyleFilter,
    onFilterSelected: (FilterType) -> Unit,
    onStyleFilterSelected: (StyleFilter) -> Unit
) {
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    val itemWidth = (screenWidth - 20.dp) / 5

    val allFilters = remember {
        listOf(
            FilterItemData(FilterType.NONE.displayNameRes, FilterKind.COLOR, FilterType.NONE.ordinal),
            FilterItemData(FilterType.LEICA_CLASSIC.displayNameRes, FilterKind.COLOR, FilterType.LEICA_CLASSIC.ordinal),
            FilterItemData(FilterType.LEICA_VIBRANT.displayNameRes, FilterKind.COLOR, FilterType.LEICA_VIBRANT.ordinal),
            FilterItemData(FilterType.LEICA_BW.displayNameRes, FilterKind.COLOR, FilterType.LEICA_BW.ordinal),
            FilterItemData(FilterType.FILM_GOLD.displayNameRes, FilterKind.COLOR, FilterType.FILM_GOLD.ordinal),
            FilterItemData(FilterType.FILM_FUJI.displayNameRes, FilterKind.COLOR, FilterType.FILM_FUJI.ordinal),
            FilterItemData(FilterType.VINTAGE.displayNameRes, FilterKind.COLOR, FilterType.VINTAGE.ordinal),
            FilterItemData(FilterType.COOL.displayNameRes, FilterKind.COLOR, FilterType.COOL.ordinal),
            FilterItemData(FilterType.WARM.displayNameRes, FilterKind.COLOR, FilterType.WARM.ordinal),
            FilterItemData(StyleFilter.TOON.displayNameRes, FilterKind.STYLE, StyleFilter.TOON.ordinal),
            FilterItemData(StyleFilter.SKETCH.displayNameRes, FilterKind.STYLE, StyleFilter.SKETCH.ordinal),
            FilterItemData(StyleFilter.POSTERIZE.displayNameRes, FilterKind.STYLE, StyleFilter.POSTERIZE.ordinal),
            FilterItemData(StyleFilter.EMBOSS.displayNameRes, FilterKind.STYLE, StyleFilter.EMBOSS.ordinal),
            FilterItemData(StyleFilter.CROSSHATCH.displayNameRes, FilterKind.STYLE, StyleFilter.CROSSHATCH.ordinal),
        )
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(5),
        state = rememberLazyGridState(),
        modifier = Modifier
            .fillMaxWidth()
            .height(280.dp)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 12.dp)
    ) {
        items(allFilters.size) { index ->
            val item = allFilters[index]
            when (item.kind) {
                FilterKind.COLOR -> {
                    val filter = FilterType.values()[item.ordinal]
                    val isSelected = selectedFilter == filter && selectedStyleFilter == StyleFilter.NONE
                    FilterItem(
                        label = stringResource(item.nameRes),
                        isSelected = isSelected,
                        assetPath = filterAssetPath(filter),
                        itemWidth = itemWidth,
                        onClick = {
                            onFilterSelected(filter)
                            onStyleFilterSelected(StyleFilter.NONE)
                        }
                    )
                }
                FilterKind.STYLE -> {
                    val style = StyleFilter.values()[item.ordinal]
                    val isSelected = selectedStyleFilter == style
                    FilterItem(
                        label = stringResource(item.nameRes),
                        isSelected = isSelected,
                        assetPath = styleAssetPath(style),
                        itemWidth = itemWidth,
                        onClick = {
                            onStyleFilterSelected(style)
                            onFilterSelected(FilterType.NONE)
                        }
                    )
                }
            }
        }
    }
}

private enum class FilterKind { COLOR, STYLE }

private data class FilterItemData(
    val nameRes: Int,
    val kind: FilterKind,
    val ordinal: Int
)

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

@Composable
private fun FilterItem(
    label: String,
    isSelected: Boolean,
    assetPath: String,
    itemWidth: Dp,
    onClick: () -> Unit
) {
    val scale by animateFloatAsState(if (isSelected) 1.08f else 1.0f, label = "scale")
    val imageSize = itemWidth * 0.72f
    val context = LocalContext.current

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(itemWidth)
            .clickable { onClick() }
            .scale(scale)
    ) {
        Box(
            modifier = Modifier
                .size(imageSize)
                .clip(CircleShape)
                .border(
                    width = if (isSelected) 2.5.dp else 1.dp,
                    brush = if (isSelected) {
                        Brush.linearGradient(
                            listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onSurface)
                        )
                    } else {
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.onSurface.copy(0.3f),
                                MaterialTheme.colorScheme.onSurface.copy(0.1f)
                            )
                        )
                    },
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            val bitmap = remember(assetPath) {
                try {
                    context.assets.open(assetPath).use { stream ->
                        BitmapFactory.decodeStream(stream)
                    }
                } catch (e: Exception) {
                    null
                }
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
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
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                )
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(imageSize * 0.38f)
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            color = if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
            },
            fontSize = 10.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1
        )
    }
}
