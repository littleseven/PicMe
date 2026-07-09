package com.mamba.picme.features.camera.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.LineStyle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mamba.picme.R
import com.mamba.picme.beauty.api.BeautySettings
import com.mamba.picme.features.camera.MakeupEntry

@Composable
fun BeautySelector(settings: BeautySettings, onSettingsChanged: (BeautySettings) -> Unit) {
    BeautyPanelContent(settings = settings, onSettingsChanged = onSettingsChanged)
}

@Composable
internal fun BeautyPanelContent(settings: BeautySettings, onSettingsChanged: (BeautySettings) -> Unit) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = BeautyTab.values()

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            tabs.forEach { tab ->
                val index = tab.ordinal
                val isSelected = selectedTab == index
                val tabLabel = stringResource(tab.labelRes)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            else Color.Transparent
                        )
                        .clickable { selectedTab = index }
                        .padding(vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tabLabel,
                        tint = if (isSelected) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        when (tabs[selectedTab]) {
            BeautyTab.FACE -> FacialRefinementContent(settings, onSettingsChanged)
            BeautyTab.MAKEUP -> MakeupAdjustmentContent(settings, onSettingsChanged)
        }
    }
}

@Composable
fun FacialRefinementSelector(settings: BeautySettings, onSettingsChanged: (BeautySettings) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        FacialRefinementContent(settings, onSettingsChanged)
    }
}

@Composable
internal fun MakeupAdjustmentSelector(
    settings: BeautySettings,
    activeEntry: MakeupEntry? = null,
    onSettingsChanged: (BeautySettings) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        when (activeEntry) {
            MakeupEntry.LIP_COLOR -> {
                LipColorSelector(
                    strength = settings.lipColor,
                    colorIndex = settings.lipColorIndex,
                    onStrengthChanged = { onSettingsChanged(settings.copy(lipColor = it)) },
                    onColorIndexChanged = { onSettingsChanged(settings.copy(lipColorIndex = it)) },
                    onReset = {
                        onSettingsChanged(
                            settings.copy(
                                lipColor = BeautySettings.DEFAULT_LIP_COLOR,
                                lipColorIndex = 0
                            )
                        )
                    }
                )
            }
            MakeupEntry.BLUSH -> {
                BlushColorFamilySelector(
                    selectedFamily = settings.blushColorFamily,
                    onFamilyChanged = { family ->
                        onSettingsChanged(settings.copy(blushColorFamily = family))
                    }
                )
                BeautySlider(
                    icon = Icons.Rounded.FavoriteBorder,
                    label = stringResource(R.string.blush),
                    value = settings.blush,
                    valueRange = 0f..100f,
                    onValueChange = { onSettingsChanged(settings.copy(blush = it)) },
                    onReset = { onSettingsChanged(settings.copy(blush = BeautySettings.DEFAULT_BLUSH)) }
                )
            }
            MakeupEntry.EYEBROW -> {
                BeautySlider(
                    icon = Icons.Rounded.LineStyle,
                    label = stringResource(R.string.eyebrow),
                    value = settings.eyebrow,
                    valueRange = 0f..100f,
                    onValueChange = { onSettingsChanged(settings.copy(eyebrow = it)) },
                    onReset = { onSettingsChanged(settings.copy(eyebrow = BeautySettings.DEFAULT_EYEBROW)) }
                )
            }
            null -> {
                LipColorSelector(
                    strength = settings.lipColor,
                    colorIndex = settings.lipColorIndex,
                    onStrengthChanged = { onSettingsChanged(settings.copy(lipColor = it)) },
                    onColorIndexChanged = { onSettingsChanged(settings.copy(lipColorIndex = it)) },
                    onReset = {
                        onSettingsChanged(
                            settings.copy(
                                lipColor = BeautySettings.DEFAULT_LIP_COLOR,
                                lipColorIndex = 0
                            )
                        )
                    }
                )
                BlushColorFamilySelector(
                    selectedFamily = settings.blushColorFamily,
                    onFamilyChanged = { family ->
                        onSettingsChanged(settings.copy(blushColorFamily = family))
                    }
                )
                BeautySlider(
                    icon = Icons.Rounded.FavoriteBorder,
                    label = stringResource(R.string.blush),
                    value = settings.blush,
                    valueRange = 0f..100f,
                    onValueChange = { onSettingsChanged(settings.copy(blush = it)) },
                    onReset = { onSettingsChanged(settings.copy(blush = BeautySettings.DEFAULT_BLUSH)) }
                )
                BeautySlider(
                    icon = Icons.Rounded.LineStyle,
                    label = stringResource(R.string.eyebrow),
                    value = settings.eyebrow,
                    valueRange = 0f..100f,
                    onValueChange = { onSettingsChanged(settings.copy(eyebrow = it)) },
                    onReset = { onSettingsChanged(settings.copy(eyebrow = BeautySettings.DEFAULT_EYEBROW)) }
                )
            }
        }
    }
}


