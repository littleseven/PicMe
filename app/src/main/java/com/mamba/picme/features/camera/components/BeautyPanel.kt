package com.mamba.picme.features.camera.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.ColorLens
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.FaceRetouchingNatural
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.LineStyle
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mamba.picme.R
import com.mamba.picme.beauty.api.BeautySettings

private const val PANEL_HEIGHT_RATIO = 0.5f

internal enum class BeautyTab(val labelRes: Int, val icon: ImageVector) {
    FACE(R.string.facial_refinement, Icons.Rounded.FaceRetouchingNatural),
    MAKEUP(R.string.makeup_adjustment, Icons.Rounded.ColorLens),
    BODY(R.string.body_management, Icons.Rounded.SelfImprovement)
}

@Composable
fun BeautyPanel(
    settings: BeautySettings,
    onSettingsChanged: (BeautySettings) -> Unit,
    onDismiss: () -> Unit,
    maxHeightRatio: Float = PANEL_HEIGHT_RATIO
) {
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val panelMaxHeight = screenHeight * maxHeightRatio.coerceIn(0.2f, 0.75f)
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = BeautyTab.values()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(panelMaxHeight + 24.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.55f),
                            Color.Black.copy(alpha = 0.82f)
                        )
                    )
                )
        )

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .heightIn(max = panelMaxHeight),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            shadowElevation = 16.dp,
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 10.dp, bottom = 4.dp)
                        .size(width = 36.dp, height = 4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    when (tabs[selectedTab]) {
                        BeautyTab.FACE -> FacialRefinementContent(settings, onSettingsChanged)
                        BeautyTab.MAKEUP -> MakeupAdjustmentContent(settings, onSettingsChanged)
                        BeautyTab.BODY -> BodyManagementContent(settings, onSettingsChanged)
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(vertical = 8.dp)
                ) {
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
            }
        }
    }
}

@Composable
internal fun FacialRefinementContent(
    settings: BeautySettings,
    onSettingsChanged: (BeautySettings) -> Unit
) {
    BeautySlider(
        icon = Icons.Rounded.Face,
        label = stringResource(R.string.smoothing),
        value = settings.smoothing,
        valueRange = 0f..100f,
        onValueChange = { onSettingsChanged(settings.copy(smoothing = it)) },
        onReset = { onSettingsChanged(settings.copy(smoothing = 0f)) }
    )
    BeautySlider(
        icon = Icons.Rounded.AutoFixHigh,
        label = stringResource(R.string.whitening),
        value = settings.whitening,
        valueRange = 0f..100f,
        onValueChange = { onSettingsChanged(settings.copy(whitening = it)) },
        onReset = { onSettingsChanged(settings.copy(whitening = 0f)) }
    )
    BeautySlider(
        icon = Icons.Rounded.FaceRetouchingNatural,
        label = stringResource(R.string.slim_face),
        value = settings.slimFace,
        valueRange = -50f..50f,
        onValueChange = { onSettingsChanged(settings.copy(slimFace = it)) },
        onReset = { onSettingsChanged(settings.copy(slimFace = 0f)) }
    )
    BeautySlider(
        icon = Icons.Rounded.Visibility,
        label = stringResource(R.string.big_eyes),
        value = settings.bigEyes,
        valueRange = 0f..100f,
        onValueChange = { onSettingsChanged(settings.copy(bigEyes = it)) },
        onReset = { onSettingsChanged(settings.copy(bigEyes = 0f)) }
    )
}

@Composable
internal fun MakeupAdjustmentContent(
    settings: BeautySettings,
    onSettingsChanged: (BeautySettings) -> Unit
) {
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

@Composable
internal fun BodyManagementContent(
    settings: BeautySettings,
    onSettingsChanged: (BeautySettings) -> Unit
) {
    BeautySlider(
        icon = Icons.Rounded.SelfImprovement,
        label = stringResource(R.string.body_enhancement),
        value = settings.bodyEnhancement,
        valueRange = -30f..30f,
        onValueChange = { onSettingsChanged(settings.copy(bodyEnhancement = it)) },
        onReset = { onSettingsChanged(settings.copy(bodyEnhancement = 0f)) }
    )
    BeautySlider(
        icon = Icons.Rounded.Timeline,
        label = stringResource(R.string.leg_extension),
        value = settings.legExtension,
        valueRange = 0f..50f,
        onValueChange = { onSettingsChanged(settings.copy(legExtension = it)) },
        onReset = { onSettingsChanged(settings.copy(legExtension = 0f)) }
    )
}
