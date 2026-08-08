package com.mamba.picme.features.camera

import androidx.compose.ui.geometry.Offset
import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.beauty.api.BeautySettings
import com.mamba.picme.beauty.api.FilterType
import com.mamba.picme.beauty.api.StyleFilter
import com.mamba.picme.beauty.api.facedetect.EngineType
import com.mamba.picme.beauty.api.facedetect.FaceWarpParams
import com.mamba.picme.domain.model.BeautyStrategy
import com.mamba.picme.domain.model.CameraAspectRatioMode
import com.mamba.picme.domain.model.CameraGridMode
import com.mamba.picme.domain.model.CameraSceneMode
import com.mamba.picme.domain.model.FaceDetectionEngineMode
import com.mamba.picme.domain.model.StageConfig

enum class ScenePreset { NONE, NIGHT, MOON }
enum class GridType { NONE, THIRDS, GOLDEN }
enum class BeautyPreviewStatus { ACTIVE, SKIPPED }
enum class CameraAspectRatio { RATIO_4_3, RATIO_16_9, RATIO_FULL }

object AspectRatio {
    const val RATIO_4_3 = 0
    const val RATIO_16_9 = 1
    const val RATIO_FULL = 2
}

internal fun CameraSceneMode.toScenePreset(): ScenePreset = when (this) {
    CameraSceneMode.NONE -> ScenePreset.NONE
    CameraSceneMode.NIGHT -> ScenePreset.NIGHT
    CameraSceneMode.MOON -> ScenePreset.MOON
}

internal fun ScenePreset.toCameraSceneMode(): CameraSceneMode = when (this) {
    ScenePreset.NONE -> CameraSceneMode.NONE
    ScenePreset.NIGHT -> CameraSceneMode.NIGHT
    ScenePreset.MOON -> CameraSceneMode.MOON
}

internal fun CameraGridMode.toGridType(): GridType = when (this) {
    CameraGridMode.NONE -> GridType.NONE
    CameraGridMode.THIRDS -> GridType.THIRDS
    CameraGridMode.GOLDEN -> GridType.GOLDEN
}

internal fun GridType.toCameraGridMode(): CameraGridMode = when (this) {
    GridType.NONE -> CameraGridMode.NONE
    GridType.THIRDS -> CameraGridMode.THIRDS
    GridType.GOLDEN -> CameraGridMode.GOLDEN
}

internal fun CameraAspectRatioMode.toAspectRatio(): Int = when (this) {
    CameraAspectRatioMode.RATIO_4_3 -> AspectRatio.RATIO_4_3
    CameraAspectRatioMode.RATIO_16_9 -> AspectRatio.RATIO_16_9
    CameraAspectRatioMode.FULL -> AspectRatio.RATIO_FULL
}

internal fun Int.toCameraAspectRatioMode(): CameraAspectRatioMode = when (this) {
    AspectRatio.RATIO_4_3 -> CameraAspectRatioMode.RATIO_4_3
    AspectRatio.RATIO_16_9 -> CameraAspectRatioMode.RATIO_16_9
    else -> CameraAspectRatioMode.FULL
}

internal data class BeautyDebugState(
    val status: BeautyPreviewStatus,
    val fps: Float,
    val processingMs: Int,
    val delayMs: Int,
    val cpuUsage: Float,
    val nullFrames: Int,
    val rendererErrorCategory: String,
    val rendererErrorReason: String,
    val persistedFallback: Boolean,
    val persistedFallbackReason: String?,
    val strategy: BeautyStrategy,
    val recoveryAvailableAtMs: Long,
    val providerRenderActive: Boolean
)

internal data class CameraPreviewUiState(
    val faceDetectionEngineMode: FaceDetectionEngineMode,
    val selectedFilter: FilterType,
    val selectedStyleFilter: StyleFilter,
    val facePoint: Offset?,
    val faceWarpParams: FaceWarpParams,
    val showFaceDebugOverlay: Boolean,
    val focusIndicatorAlpha: Float,
    val lastMedia: MediaAsset?,
    val zoomRatio: Float,
    val minZoomRatio: Float,
    val maxZoomRatio: Float,
    val captureMode: MediaType,
    val isRecording: Boolean,
    val isStable: Boolean,
    val showFilterSelector: Boolean,
    val showBeautySelector: Boolean,
    val showRatioSelector: Boolean,
    val showCameraInfo: Boolean,
    val showSceneSelector: Boolean,
    val showGridSelector: Boolean,
    val debugUiEnabled: Boolean,
    val showFacialRefinement: Boolean,
    val showMakeupAdjustment: Boolean,
    val activeMakeupEntry: MakeupEntry,
    val showBodyManagement: Boolean,
    val currentScene: ScenePreset,
    val currentGrid: GridType,
    val beautySettings: BeautySettings,
    val beautyDebugState: BeautyDebugState,
    val aspectRatio: Int,
    val lensFacing: Int,
    val exposureCompensation: Int,
    val exposureRange: IntRange,
    val whiteBalanceMode: Int,
    val beautyStrategy: BeautyStrategy,
    val isVoiceControlEnabled: Boolean,
    /** 相机页语音入口（悬浮 FAB）显隐，由设置开关控制，默认 false */
    val voiceEntryEnabled: Boolean,
    val roiStageConfig: StageConfig,
    val landmarkStageConfig: StageConfig,
    val showProPanel: Boolean,
    val showLogOverlay: Boolean
)

internal data class CameraPreviewActions(
    val onResetCameraMemoryState: () -> Unit,
    val onNavigateToDebug: () -> Unit,
    val onFlipCamera: () -> Unit,
    val onToggleBeauty: () -> Unit,
    val onToggleFilter: () -> Unit,
    val onToggleRatio: () -> Unit,
    val onToggleCameraInfo: () -> Unit,
    val onToggleScene: () -> Unit,
    val onToggleGrid: () -> Unit,
    val onToggleLogs: () -> Unit,
    val onToggleFaceDebugOverlay: () -> Unit,
    val onToggleFacialRefinement: () -> Unit,
    val onToggleMakeupAdjustment: () -> Unit,
    val onToggleLipColor: () -> Unit,
    val onToggleBlush: () -> Unit,
    val onToggleBodyManagement: () -> Unit,
    val onZoomPresetClick: (Float) -> Unit,
    val onExposureChange: (Int) -> Unit,
    val onWhiteBalanceChange: (Int) -> Unit,
    val onSceneSelected: (ScenePreset) -> Unit,
    val onGridSelected: (GridType) -> Unit,
    val onGalleryClick: () -> Unit,
    val onCaptureClick: () -> Unit,
    val onModeChange: (MediaType) -> Unit,
    val onFilterSelected: (FilterType) -> Unit,
    val onStyleFilterSelected: (StyleFilter) -> Unit,
    val onBeautySettingsChanged: (BeautySettings) -> Unit,
    val onRatioSelected: (Int) -> Unit,
    val onDismissPanels: () -> Unit,
    val onToggleVoiceControl: () -> Unit,
    val onToggleAiAgentPanel: () -> Unit,
    val onToggleProPanel: () -> Unit,
    val onLlmRelease: () -> Unit = {},
    val onFaceDetectRelease: () -> Unit = {},
    val onNavigateBack: () -> Unit = {}
)

internal fun FaceDetectionEngineMode.toEngineType(): EngineType = when (this) {
    FaceDetectionEngineMode.MEDIAPIPE -> EngineType.MEDIAPIPE
    FaceDetectionEngineMode.MNN -> EngineType.MNN
    FaceDetectionEngineMode.CUSTOM -> EngineType.MEDIAPIPE
}

internal fun buildCameraPreviewUiState(
    selectedFilter: FilterType,
    selectedStyleFilter: StyleFilter,
    faceDetectionEngineMode: FaceDetectionEngineMode,
    facePoint: Offset?,
    faceWarpParams: FaceWarpParams,
    showFaceDebugOverlay: Boolean,
    focusIndicatorAlpha: Float,
    lastMedia: MediaAsset?,
    zoomRatio: Float,
    minZoomRatio: Float,
    maxZoomRatio: Float,
    captureMode: MediaType,
    isRecording: Boolean,
    isStable: Boolean,
    panelState: CameraPanelState,
    showCameraInfo: Boolean,
    debugUiEnabled: Boolean,
    currentScene: ScenePreset,
    currentGrid: GridType,
    beautySettings: BeautySettings,
    beautyDebugState: BeautyDebugState,
    aspectRatio: Int,
    lensFacing: Int,
    exposureCompensation: Int,
    exposureRange: IntRange,
    whiteBalanceMode: Int,
    beautyStrategy: BeautyStrategy,
    isVoiceControlEnabled: Boolean,
    voiceEntryEnabled: Boolean,
    roiStageConfig: StageConfig,
    landmarkStageConfig: StageConfig,
    showLogOverlay: Boolean
): CameraPreviewUiState {
    return CameraPreviewUiState(
        selectedFilter = selectedFilter,
        selectedStyleFilter = selectedStyleFilter,
        faceDetectionEngineMode = faceDetectionEngineMode,
        facePoint = facePoint,
        faceWarpParams = faceWarpParams,
        showFaceDebugOverlay = showFaceDebugOverlay,
        focusIndicatorAlpha = focusIndicatorAlpha,
        lastMedia = lastMedia,
        zoomRatio = zoomRatio,
        minZoomRatio = minZoomRatio,
        maxZoomRatio = maxZoomRatio,
        captureMode = captureMode,
        isRecording = isRecording,
        isStable = isStable,
        showFilterSelector = panelState.showFilterSelector,
        showBeautySelector = panelState.showBeautySelector,
        showRatioSelector = panelState.showRatioSelector,
        showCameraInfo = showCameraInfo,
        showSceneSelector = panelState.showSceneSelector,
        showGridSelector = panelState.showGridSelector,
        debugUiEnabled = debugUiEnabled,
        showFacialRefinement = panelState.showFacialRefinement,
        showMakeupAdjustment = panelState.showMakeupAdjustment,
        activeMakeupEntry = panelState.activeMakeupEntry,
        showBodyManagement = panelState.showBodyManagement,
        currentScene = currentScene,
        currentGrid = currentGrid,
        beautySettings = beautySettings,
        beautyDebugState = beautyDebugState,
        aspectRatio = aspectRatio,
        lensFacing = lensFacing,
        exposureCompensation = exposureCompensation,
        exposureRange = exposureRange,
        whiteBalanceMode = whiteBalanceMode,
        beautyStrategy = beautyStrategy,
        isVoiceControlEnabled = isVoiceControlEnabled,
        voiceEntryEnabled = voiceEntryEnabled,
        roiStageConfig = roiStageConfig,
        landmarkStageConfig = landmarkStageConfig,
        showProPanel = panelState.showProPanel,
        showLogOverlay = showLogOverlay
    )
}

internal fun buildCameraPreviewActions(
    onResetCameraMemoryState: () -> Unit,
    lensFacing: Int,
    onLensFacingChanged: (Int) -> Unit,
    onActualLensFacingChanged: (Int) -> Unit,
    panelState: CameraPanelState,
    cameraControl: androidx.camera.core.CameraControl?,
    onCurrentSceneChanged: (ScenePreset) -> Unit,
    onCurrentGridChanged: (GridType) -> Unit,
    onNavigateToGallery: () -> Unit,
    onCaptureClick: () -> Unit,
    onCaptureModeChanged: (MediaType) -> Unit,
    onSelectedFilterChanged: (FilterType) -> Unit,
    onStyleFilterSelected: (StyleFilter) -> Unit,
    onBeautySettingsChanged: (BeautySettings) -> Unit,
    onAspectRatioChanged: (Int) -> Unit,
    onExposureCompensationChanged: (Int) -> Unit,
    onWhiteBalanceModeChanged: (Int) -> Unit,
    onToggleVoiceControl: () -> Unit,
    onToggleAiAgentPanel: () -> Unit,
    onToggleLogs: () -> Unit,
    onLlmRelease: () -> Unit = {},
    onFaceDetectRelease: () -> Unit = {},
    onNavigateBack: () -> Unit = {}
): CameraPreviewActions {
    return CameraPreviewActions(
        onResetCameraMemoryState = onResetCameraMemoryState,
        onNavigateToDebug = {},
        onNavigateBack = onNavigateBack,
        onFlipCamera = {
            val nextLens = nextLensFacing(lensFacing)
            onLensFacingChanged(nextLens)
            onActualLensFacingChanged(nextLens)
        },
        onToggleBeauty = {
            togglePrimaryPanel(
                isCurrentlyVisible = panelState.showBeautySelector,
                closePrimaryPanels = panelState::closePrimaryPanels,
                onPanelVisibilityChanged = { isVisible -> panelState.showBeautySelector = isVisible }
            )
        },
        onToggleFilter = {
            togglePrimaryPanel(
                isCurrentlyVisible = panelState.showFilterSelector,
                closePrimaryPanels = panelState::closePrimaryPanels,
                onPanelVisibilityChanged = { isVisible -> panelState.showFilterSelector = isVisible }
            )
        },
        onToggleRatio = {
            togglePrimaryPanel(
                isCurrentlyVisible = panelState.showRatioSelector,
                closePrimaryPanels = panelState::closePrimaryPanels,
                onPanelVisibilityChanged = { isVisible -> panelState.showRatioSelector = isVisible }
            )
        },
        onToggleCameraInfo = {},
        onToggleScene = {
            togglePrimaryPanel(
                isCurrentlyVisible = panelState.showSceneSelector,
                closePrimaryPanels = panelState::closePrimaryPanels,
                onPanelVisibilityChanged = { isVisible -> panelState.showSceneSelector = isVisible }
            )
        },
        onToggleGrid = {
            togglePrimaryPanel(
                isCurrentlyVisible = panelState.showGridSelector,
                closePrimaryPanels = panelState::closePrimaryPanels,
                onPanelVisibilityChanged = { isVisible -> panelState.showGridSelector = isVisible }
            )
        },
        onLlmRelease = onLlmRelease,
        onFaceDetectRelease = onFaceDetectRelease,
        onToggleLogs = onToggleLogs,
        onToggleFaceDebugOverlay = {},
        onToggleFacialRefinement = panelState::toggleFacialRefinement,
        onToggleMakeupAdjustment = panelState::toggleMakeupAdjustment,
        onToggleLipColor = { panelState.openMakeupEntry(MakeupEntry.LIP_COLOR) },
        onToggleBlush = { panelState.openMakeupEntry(MakeupEntry.BLUSH) },
        onToggleBodyManagement = panelState::toggleBodyManagement,
        onZoomPresetClick = { ratio -> cameraControl?.setZoomRatio(ratio) },
        onExposureChange = { exposure ->
            onExposureCompensationChanged(exposure)
            cameraControl?.setExposureCompensationIndex(exposure)
        },
        onWhiteBalanceChange = { wb ->
            onWhiteBalanceModeChanged(wb)
        },
        onSceneSelected = { scene ->
            onCurrentSceneChanged(scene)
            panelState.showSceneSelector = false
        },
        onGridSelected = { grid ->
            onCurrentGridChanged(grid)
            panelState.showGridSelector = false
        },
        onGalleryClick = onNavigateToGallery,
        onCaptureClick = onCaptureClick,
        onModeChange = { mode -> onCaptureModeChanged(mode) },
        onFilterSelected = { filter -> onSelectedFilterChanged(filter) },
        onStyleFilterSelected = { style -> onStyleFilterSelected(style) },
        onBeautySettingsChanged = { settings -> onBeautySettingsChanged(settings) },
        onRatioSelected = { ratio ->
            onAspectRatioChanged(ratio)
            panelState.showRatioSelector = false
        },
        onDismissPanels = panelState::closeAllPanels,
        onToggleVoiceControl = onToggleVoiceControl,
        onToggleAiAgentPanel = onToggleAiAgentPanel,
        onToggleProPanel = {
            panelState.showProPanel = !panelState.showProPanel
        }
    )
}