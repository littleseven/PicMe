package com.mamba.picme.features.camera

import androidx.camera.core.CameraSelector
import com.mamba.picme.beauty.api.BeautySettings

internal fun nextLensFacing(currentLensFacing: Int): Int {
    return if (currentLensFacing == CameraSelector.LENS_FACING_BACK) {
        CameraSelector.LENS_FACING_FRONT
    } else {
        CameraSelector.LENS_FACING_BACK
    }
}

internal fun togglePrimaryPanel(
    isCurrentlyVisible: Boolean,
    closePrimaryPanels: () -> Unit,
    onPanelVisibilityChanged: (Boolean) -> Unit
) {
    val nextVisibility = !isCurrentlyVisible
    closePrimaryPanels()
    onPanelVisibilityChanged(nextVisibility)
}

internal fun resolveNextBeautySettings(
    currentSettings: BeautySettings,
    updatedSettings: BeautySettings
): BeautySettings {
    val onlyToggleChanged =
        currentSettings.copy(enabled = updatedSettings.enabled) == updatedSettings &&
            currentSettings.enabled != updatedSettings.enabled

    return when {
        onlyToggleChanged -> updatedSettings
        updatedSettings.hasAnyEffect() -> updatedSettings.copy(enabled = true)
        else -> updatedSettings.copy(enabled = false)
    }
}

internal fun toCameraAspectRatio(aspectRatio: Int): Int {
    return when (aspectRatio) {
        AspectRatio.RATIO_4_3 -> androidx.camera.core.AspectRatio.RATIO_4_3
        AspectRatio.RATIO_16_9, AspectRatio.RATIO_FULL -> androidx.camera.core.AspectRatio.RATIO_16_9
        else -> androidx.camera.core.AspectRatio.RATIO_4_3
    }
}

