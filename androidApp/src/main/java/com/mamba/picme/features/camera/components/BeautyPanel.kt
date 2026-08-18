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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.ColorLens
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.FaceRetouchingNatural
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Icon
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
import com.mamba.picme.core.designsystem.BeautyPanelTokens
import com.mamba.picme.core.designsystem.CameraTokens

internal enum class BeautyTab(val labelRes: Int, val icon: ImageVector) {
    FACE(R.string.facial_refinement, Icons.Rounded.FaceRetouchingNatural),
    MAKEUP(R.string.makeup_adjustment, Icons.Rounded.ColorLens)
}

/**
 * 美颜底部抽屉（2026-08-18 三修：几何全回老版，仅色系换 cameraAccent 深青玉 #0F766E）。
 * 壳：顶部圆角 24 + surface@0.95 + 0.5dp 描边 + 16dp 阴影；高 40% 屏高；
 * scrim：黑色渐变（透明→黑55→黑82）；Tab：底部 icon-only 栏，选中 accent@12% 底。
 */
@Composable
fun BeautyPanel(
    settings: BeautySettings,
    onSettingsChanged: (BeautySettings) -> Unit,
    onDismiss: () -> Unit,
    maxHeightRatio: Float = BeautyPanelTokens.heightRatio
) {
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val panelMaxHeight = screenHeight * maxHeightRatio.coerceIn(
        BeautyPanelTokens.heightRatioMin,
        BeautyPanelTokens.heightRatioMax
    )
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = BeautyTab.entries

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = panelMaxHeight + 24.dp)
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
            color = Color(0xFF1C1A1F).copy(alpha = 0.95f),
            shape = RoundedCornerShape(
                topStart = BeautyPanelTokens.topCornerRadius,
                topEnd = BeautyPanelTokens.topCornerRadius
            ),
            shadowElevation = 16.dp,
            border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.12f))
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 6.dp, bottom = 2.dp)
                        .size(width = 36.dp, height = 4.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.2f))
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    when (tabs[selectedTab]) {
                        BeautyTab.FACE -> FacialRefinementContent(settings, onSettingsChanged)
                        BeautyTab.MAKEUP -> MakeupAdjustmentContent(settings, onSettingsChanged)
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1C1A1F))
                        .padding(vertical = 4.dp)
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
                                    if (isSelected) {
                                        CameraTokens.cameraAccent.copy(alpha = 0.12f)
                                    } else {
                                        Color.Transparent
                                    }
                                )
                                .clickable { selectedTab = index }
                                .padding(vertical = 8.dp)
                        ) {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tabLabel,
                                tint = if (isSelected) {
                                    CameraTokens.cameraAccent
                                } else {
                                    Color.White.copy(alpha = 0.5f)
                                },
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
}
