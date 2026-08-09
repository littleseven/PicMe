package com.mamba.picme.core.designsystem

import androidx.compose.ui.graphics.Color

// Vibrant, Energetic Color Palette
val PrimaryLight = Color(0xFF6750A4)
val OnPrimaryLight = Color(0xFFFFFFFF)
val PrimaryContainerLight = Color(0xFFEADDFF)
val OnPrimaryContainerLight = Color(0xFF21005D)

val SecondaryLight = Color(0xFF625B71)
val OnSecondaryLight = Color(0xFFFFFFFF)
val SecondaryContainerLight = Color(0xFFE8DEF8)
val OnSecondaryContainerLight = Color(0xFF1D192B)

val TertiaryLight = Color(0xFF7D5260)
val OnTertiaryLight = Color(0xFFFFFFFF)
val TertiaryContainerLight = Color(0xFFFFD8E4)
val OnTertiaryContainerLight = Color(0xFF31111D)

val ErrorLight = Color(0xB32610)
val OnErrorLight = Color(0xFFFFFFFF)
val ErrorContainerLight = Color(0xFFF9DEDC)
val OnErrorContainerLight = Color(0xFF410E0B)

val BackgroundLight = Color(0xFFFFFBFE)
val OnBackgroundLight = Color(0xFF1C1B1F)
val SurfaceLight = Color(0xFFFFFBFE)
val OnSurfaceLight = Color(0xFF1C1B1F)

// Dark Palette
val PrimaryDark = Color(0xFFD0BCFF)
val OnPrimaryDark = Color(0xFF381E72)
val PrimaryContainerDark = Color(0xFF4F378B)
val OnPrimaryContainerDark = Color(0xFFEADDFF)

val SecondaryDark = Color(0xFFCCC2DC)
val OnSecondaryDark = Color(0xFF332D41)
val SecondaryContainerDark = Color(0xFF4A4458)
val OnSecondaryContainerDark = Color(0xFFE8DEF8)

val TertiaryDark = Color(0xFFEFB8C8)
val OnTertiaryDark = Color(0xFF492532)
val TertiaryContainerDark = Color(0xFF633B48)
val OnTertiaryContainerDark = Color(0xFFFFD8E4)

val ErrorDark = Color(0xFFF2B8B5)
val OnErrorDark = Color(0xFF601410)
val ErrorContainerDark = Color(0xFF8C1D18)
val OnErrorContainerDark = Color(0xFFF9DEDC)

val BackgroundDark = Color(0xFF1C1B1F)
val OnBackgroundDark = Color(0xFFE6E1E5)
val SurfaceDark = Color(0xFF1C1B1F)
val OnSurfaceDark = Color(0xFFE6E1E5)

// Custom vibrant accents for "Energetic" feel
val VibrantGreen = Color(0xFF00E676)
val VibrantBlue = Color(0xFF2979FF)
val VibrantOrange = Color(0xFFFF9100)
val VibrantPink = Color(0xFFFF4081)

// Component tokens (fixed component-level colors, not part of ColorScheme)
val SliderThumbColor = Color(0xFFFFFFFF)

// ── 功能色（双端 SSOT: design-tokens.json）─────────────────────────────────────────
// 相机/相册等功能场景使用的固定颜色，不随主题切换。引用方式：MaterialTheme.appColors.focusRing

/** 对焦框青色环（相机对焦成功反馈）。 */
val FocusRingColor = Color(0xFF00E5FF)

/** 底部弹出面板半透明黑色背景。 */
val PanelBackgroundColor = Color(0xCC000000)

/** 快门外环颜色。 */
val ShutterRingColor = Color(0xFFFFFFFF)

/**
 * 功能色集合，通过 [MaterialTheme.appColors] 访问。
 * 不随 Light/Dark 主题切换的固定色值。
 */
object AppColors {
    val focusRing = FocusRingColor
    val panelBackground = PanelBackgroundColor
    val shutterRing = ShutterRingColor
    val sliderThumb = SliderThumbColor
    val vibrantGreen = VibrantGreen
    val vibrantBlue = VibrantBlue
    val vibrantOrange = VibrantOrange
    val vibrantPink = VibrantPink
}
