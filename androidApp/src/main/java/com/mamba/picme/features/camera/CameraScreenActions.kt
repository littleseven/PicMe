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

/**
 * 面板统一互斥（2026-08-15 改版，camera.yaml §17）：
 * 打开任一面板前关闭其余全部（含 ProMode），同一时刻最多 1 个面板可见。
 */
internal fun togglePrimaryPanel(
    isCurrentlyVisible: Boolean,
    closeAllPanels: () -> Unit,
    onPanelVisibilityChanged: (Boolean) -> Unit
) {
    val nextVisibility = !isCurrentlyVisible
    closeAllPanels()
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

/** 色温中性点（BeautySettings.temperature 默认值） */
private const val WB_TEMPERATURE_NEUTRAL_K = 5000f
private const val WB_TEMPERATURE_SUNNY_K = 5600f // 晴天：轻微偏暖
private const val WB_TEMPERATURE_CLOUDY_K = 6200f // 阴天：偏暖
private const val WB_TEMPERATURE_INCANDESCENT_K = 3600f // 白炽灯：明显偏冷
private const val WB_TEMPERATURE_FLUORESCENT_K = 4400f // 荧光灯：轻微偏冷

/**
 * 白平衡预设 → 色温(K) 映射（camera.yaml §13）。
 *
 * 生效链路：WB 预设写入 `BeautySettings.temperature`，经 GL 调色管线（colorgrade.glsl
 * uTemperature）实时生效；预览与拍照复用同一 Shader 管线，所见即所得。
 *
 * 为什么不走 Camera2 `CONTROL_AWB_MODE`：2026-08-16 真机实测（小米 HyperOS），
 * 经 CameraX interop 写入 AWB 后 CameraX 侧上报 applied 成功，但 HAL 实际忽略，
 * 预览/成片均无变化；且 GL 方案跨设备行为一致（[PARITY] 双端同一 GLSL 源）。
 *
 * 补偿方向约定：预设描述的是「场景光源」——暖光源预设（白炽灯/荧光灯）输出偏冷（K 值下调），
 * 冷光源预设（多云）输出偏暖，与系统相机的 WB 预设行为一致。
 *
 * 0=自动(中性 5000K) 1=晴天 2=阴天 3=白炽灯 4=荧光灯。
 */
internal fun whiteBalanceTemperatureKelvin(mode: Int): Float {
    return when (mode) {
        1 -> WB_TEMPERATURE_SUNNY_K
        2 -> WB_TEMPERATURE_CLOUDY_K
        3 -> WB_TEMPERATURE_INCANDESCENT_K
        4 -> WB_TEMPERATURE_FLUORESCENT_K
        else -> WB_TEMPERATURE_NEUTRAL_K
    }
}

