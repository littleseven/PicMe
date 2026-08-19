package com.mamba.picme.features.camera

import android.bluetooth.BluetoothHeadset
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.KeyboardVoice
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mamba.picme.R
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.agent.core.platform.voice.AudioRecorder
import com.mamba.picme.agent.core.platform.voice.InputAudioDevice
import com.mamba.picme.beauty.api.facedetect.FaceDetectionSource
import com.mamba.picme.core.designsystem.CameraTokens
import com.mamba.picme.domain.model.AiAgentCommand
import com.mamba.picme.domain.usecase.AiAgentUseCase
import com.mamba.picme.features.camera.components.BeautyPanel
import com.mamba.picme.features.camera.components.CameraBackButton
import com.mamba.picme.features.camera.components.CameraBottomControls
import com.mamba.picme.features.camera.components.CameraLeftControls
import com.mamba.picme.features.camera.components.CameraOverlays
import com.mamba.picme.features.camera.components.CameraTopToolBar
import com.mamba.picme.features.camera.components.DocumentDetectionOverlay
import com.mamba.picme.features.camera.components.InlineControlPanel
import com.mamba.picme.features.camera.components.ProModeControlsContent
import com.mamba.picme.features.camera.components.SelectorChip
import com.mamba.picme.features.camera.components.SelectorChipRow
import com.mamba.picme.features.camera.components.UnifiedFilterSelector
import com.mamba.picme.features.camera.voice.VoiceCommandCoordinator
import com.mamba.picme.features.camera.voice.VoiceWakeIndicator
import com.mamba.picme.features.common.chat.AgentMessage
import com.mamba.picme.features.common.chat.AiChatScreen
import kotlinx.coroutines.launch

// [常量定义] 调试文本颜色
private val INSIGHTFACE_DEBUG_TEXT_COLOR = Color(0xFFFFAB91)
private val MEDIAPIPE_DEBUG_TEXT_COLOR = Color(0xFF80CBC4)
private val MNN_DEBUG_TEXT_COLOR = Color(0xFFCE93D8)
private val NONE_DEBUG_TEXT_COLOR = Color(0xFFA5D6A7)
private val LIP_HIGHLIGHT_COLOR = Color(0xFFFF80AB)

@Composable
internal fun CameraPreviewContent(
    previewView: @Composable () -> Unit,
    uiState: CameraPreviewUiState,
    actions: CameraPreviewActions,
    aiAgentUseCase: AiAgentUseCase? = null,
    aiAgentChatVisible: Boolean = false,
    aiAgentMessages: List<AgentMessage> = emptyList(),
    aiAgentIsProcessing: Boolean = false,
    onAiAgentChatVisibleChange: (Boolean) -> Unit = {},
    onAiAgentMessagesChange: (List<AgentMessage>) -> Unit = {},
    onAiAgentIsProcessingChange: (Boolean) -> Unit = {},
    voiceCoordinator: VoiceCommandCoordinator? = null,
    isWakeWordActive: Boolean = false,
    onAiAgentCommand: ((AiAgentCommand) -> Unit)? = null,
    onUpdateVoiceCoordinatorState: (() -> Unit)? = null
) {
    // 非美颜类面板开启状态（美颜面板用独立的 BeautyPanel 底部抽屉渲染；场景面板已下线）
    val isAnyPanelOpen = uiState.showFilterSelector || uiState.showRatioSelector || uiState.showGridSelector
    val isBeautyPanelOpen = uiState.showBeautySelector
    val isProPanelOpen = uiState.showProPanel

    // Back 键优先关闭已打开的面板（camera.yaml §2/§17 back_button → close_all_panels）
    BackHandler(enabled = isAnyPanelOpen || isBeautyPanelOpen || isProPanelOpen) {
        actions.onDismissPanels()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // 点击取景区空白处关闭所有面板
            .clickable(
                enabled = isAnyPanelOpen || isBeautyPanelOpen || isProPanelOpen,
                onClick = actions.onDismissPanels
            ),
        contentAlignment = Alignment.Center
    ) {
        previewView()

        // 唤醒词监听状态指示器
        VoiceWakeIndicator(
            isListening = isWakeWordActive,
            modifier = Modifier.align(Alignment.TopCenter)
        )

        CameraOverlays(
            isStable = uiState.isStable,
            gridType = uiState.currentGrid,
            facePoint = uiState.facePoint,
            focusAlpha = uiState.focusIndicatorAlpha,
            showInfo = uiState.showCameraInfo,
            lensFacing = uiState.lensFacing,
            captureMode = uiState.captureMode,
            zoomRatio = uiState.zoomRatio,
            aspectRatio = uiState.aspectRatio,
            selectedFilter = uiState.selectedFilter,
            beautySettings = uiState.beautySettings,
            exposureCompensation = uiState.exposureCompensation,
            whiteBalanceMode = uiState.whiteBalanceMode,
            currentScene = uiState.currentScene
        )

        if (uiState.showFaceDebugOverlay) {
            FaceDebugOverlay(
                faceWarpParams = uiState.faceWarpParams,
                slimFaceValue = uiState.beautySettings.slimFace,
                aspectRatio = uiState.aspectRatio
            )
        }

        CameraPreviewDebugStatus(uiState = uiState)
        CameraPreviewSideControls(uiState = uiState, actions = actions)
        CameraTopControls(uiState = uiState, actions = actions)

        CameraBottomControls(
            lastMedia = uiState.lastMedia,
            zoomRatio = uiState.zoomRatio,
            minZoomRatio = uiState.minZoomRatio,
            maxZoomRatio = uiState.maxZoomRatio,
            captureMode = uiState.captureMode,
            isRecording = uiState.isRecording,
            onZoomPresetClick = actions.onZoomPresetClick,
            onGalleryClick = actions.onGalleryClick,
            onCaptureClick = actions.onCaptureClick,
            onFlipCamera = actions.onFlipCamera,
            onModeChange = actions.onModeChange,
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        // 美颜面板（底部矮抽屉：覆盖底栏而非顶起；统一入口：美图秀秀风格 Tab 标签页）
        AnimatedVisibility(
            visible = isBeautyPanelOpen,
            enter = slideInVertically(initialOffsetY = { offsetY -> offsetY }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { offsetY -> offsetY }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            BeautyPanel(
                settings = uiState.beautySettings,
                onSettingsChanged = actions.onBeautySettingsChanged,
                onDismiss = actions.onDismissPanels
            )
        }

        if (uiState.captureMode == MediaType.DOCUMENT && !isAnyPanelOpen) {
            DocumentDetectionOverlay(
                documentBounds = Rect.Zero,
                modifier = Modifier.fillMaxSize()
            )
        }

        // 同步语音协调器状态
        onUpdateVoiceCoordinatorState?.invoke()

        // AI Agent 和语音控制浮动按钮 - 右下角，方便拇指点击
        CameraFloatingActionButtons(
            onToggleAiAgentPanel = actions.onToggleAiAgentPanel,
            onToggleVoiceControl = actions.onToggleVoiceControl,
            isVoiceControlEnabled = uiState.isVoiceControlEnabled,
            showVoiceEntry = uiState.voiceEntryEnabled,
            showAiChatEntry = uiState.aiChatEntryEnabled,
            modifier = Modifier.align(Alignment.BottomEnd)
        )

        // AI Agent 面板：使用统一的 AiChatScreen
        // 必须在根 Box 内部作为浮层组合：CameraScreen 宿主于 HorizontalPager，
        // Pager 会把页内多个同级子项沿主轴（横向）依次摆放，Box 外的 AiChatScreen
        // 会被放到屏外（组合但不渲染），表现为「点击 chat 入口无反应」
        if (aiAgentUseCase != null && onAiAgentCommand != null) {
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            AiChatScreen(
                visible = aiAgentChatVisible,
                messages = aiAgentMessages,
                isProcessing = aiAgentIsProcessing,
                onVisibleChange = onAiAgentChatVisibleChange,
                voiceCoordinator = voiceCoordinator,
                onSendMessage = { input ->
                    onAiAgentMessagesChange(aiAgentMessages + AgentMessage.UserText(content = input))
                    onAiAgentIsProcessingChange(true)
                    scope.launch {
                        val currentState = AiAgentUseCase.CameraStateSnapshot(
                            beautySettings = uiState.beautySettings,
                            filterType = uiState.selectedFilter,
                            styleFilter = uiState.selectedStyleFilter,
                            zoomRatio = uiState.zoomRatio,
                            exposureCompensation = uiState.exposureCompensation,
                            captureMode = uiState.captureMode,
                            isRecording = uiState.isRecording
                        )
                        val result = aiAgentUseCase.processInput(input, currentState)
                        onAiAgentIsProcessingChange(false)
                        result.onSuccess { command ->
                            val executionMessages = commandToExecutionMessages(command)
                            onAiAgentMessagesChange(
                                aiAgentMessages +
                                    AgentMessage.UserText(content = input) +
                                    executionMessages
                            )
                            onAiAgentCommand(command)
                        }.onFailure { error ->
                            onAiAgentMessagesChange(
                                aiAgentMessages + AgentMessage.UserText(content = input) + AgentMessage.AgentText(
                                    content = "处理出错了：${error.message ?: "未知错误"}"
                                )
                            )
                        }
                    }
                },
                onCommand = onAiAgentCommand
            )
        }
    }
}

@Composable
private fun BoxScope.CameraPreviewDebugStatus(uiState: CameraPreviewUiState) {
    if (!uiState.debugUiEnabled) {
        return
    }

    val statusText = if (uiState.beautyDebugState.status == BeautyPreviewStatus.ACTIVE) {
        "Beauty: ACTIVE"
    } else {
        "Beauty: SKIPPED"
    }
    val statusColor = if (uiState.beautyDebugState.status == BeautyPreviewStatus.ACTIVE) {
        Color(0xFF00C853)
    } else {
        Color(0xFFFFA000)
    }

    val activeEffects = mutableListOf<String>()
    if (uiState.beautySettings.smoothing > 0f) {
        activeEffects.add("SMOOTH")
    }
    if (uiState.beautySettings.whitening > 0f) {
        activeEffects.add("WHITE")
    }
    if (uiState.beautySettings.slimFace != 0f) {
        activeEffects.add("SLIM")
    }
    if (uiState.beautySettings.bigEyes > 0f) {
        activeEffects.add("EYE")
    }
    // 妆容调节
    if (uiState.beautySettings.lipColor > 0f) {
        activeEffects.add("LIP(${uiState.beautySettings.lipColor.toInt()})#${uiState.beautySettings.lipColorIndex}")
    }
    if (uiState.beautySettings.blush > 0f) {
        activeEffects.add("BLUSH")
    }

    val nowMs = System.currentTimeMillis()
    val hasPersistedFallback = uiState.beautyDebugState.recoveryAvailableAtMs > 0L
    val fallbackStateText = if (hasPersistedFallback) {
        val reasonText = uiState.beautyDebugState.persistedFallbackReason ?: "runtime failure"
        val remainingMs = (uiState.beautyDebugState.recoveryAvailableAtMs - nowMs).coerceAtLeast(0L)
        val remainingSec = remainingMs / 1000L
        if (remainingMs > 0L) {
            "Fallback: PERSISTED (${remainingSec}s, $reasonText)"
        } else {
            "Fallback: READY_TO_RECOVER ($reasonText)"
        }
    } else {
        "Fallback: NONE"
    }

    val lipRealtimePreviewSupported = uiState.beautyDebugState.providerRenderActive

    val lipCompactText = buildString {
        append("LIP ${uiState.beautySettings.lipColor.toInt()}% #${uiState.beautySettings.lipColorIndex}")
        append(" M:${uiState.faceWarpParams.lipOuterContourPoints.size}/${uiState.faceWarpParams.lipInnerContourPoints.size}")
        append(" P:${if (lipRealtimePreviewSupported) "OK" else "FB"}")
    }

    val hasFace = uiState.faceWarpParams.hasFace
    val faceCompactText = if (hasFace) {
        "Face OK C(${"%.2f".format(uiState.faceWarpParams.faceCenterX)},${"%.2f".format(uiState.faceWarpParams.faceCenterY)}) R${"%.2f".format(uiState.faceWarpParams.faceRadius)}"
    } else {
        "Face NONE"
    }

    val effectsCompact = if (activeEffects.isEmpty()) {
        "FX None"
    } else {
        "FX ${activeEffects.joinToString("/")}"
    }
    val perfCompact = "FPS ${"%.1f".format(uiState.beautyDebugState.fps)} | ${uiState.beautyDebugState.processingMs}ms/${uiState.beautyDebugState.delayMs}ms | D${uiState.beautyDebugState.nullFrames}"
    val roiEngineLabel = uiState.roiStageConfig.engineType.name
    val landmarkEngineLabel = uiState.landmarkStageConfig.engineType.name
    val activeSourceLabel = when (uiState.faceWarpParams.detectionSource) {
        FaceDetectionSource.NONE -> "NONE"
        FaceDetectionSource.MEDIAPIPE -> "MEDIAPIPE"
        FaceDetectionSource.MNN -> "MNN GPU"
    }
    val detectionCompact = buildString {
        append("Detect ")
        append("ROI=${uiState.faceWarpParams.roiDetectorName}")
        append(if (uiState.faceWarpParams.useGpuForRoi) "[GPU] " else "[CPU] ")
        append("LMK=${uiState.faceWarpParams.landmarkDetectorName}")
        append(if (uiState.faceWarpParams.useGpuForLandmark) "[GPU] " else "[CPU] ")
        append("-> ${activeSourceLabel}")
    }
    val rendererErrorCompact = if (uiState.beautyDebugState.rendererErrorCategory.isNotBlank()) {
        "RendererErr ${uiState.beautyDebugState.rendererErrorCategory}: ${uiState.beautyDebugState.rendererErrorReason.ifBlank { "unknown" }}"
    } else {
        "RendererErr NONE"
    }

    var debugExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .statusBarsPadding()
            .padding(top = 14.dp)
            .width(248.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.42f))
            .clickable { debugExpanded = !debugExpanded }
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        val compactTitle = "$statusText  ${"%.1f".format(uiState.beautyDebugState.fps)}fps"
        Text(
            text = if (debugExpanded) "$compactTitle  ▲" else "$compactTitle  ▼",
            color = statusColor,
            fontSize = 10.sp
        )

        AnimatedVisibility(visible = debugExpanded) {
            Column {
                Text(text = effectsCompact, color = Color.White.copy(alpha = 0.9f), fontSize = 9.sp)
                Text(text = perfCompact, color = Color.White.copy(alpha = 0.9f), fontSize = 9.sp)
                Text(
                    text = rendererErrorCompact,
                    color = if (uiState.beautyDebugState.rendererErrorCategory.isNotBlank()) {
                        Color(0xFFFF8A80)
                    } else {
                        Color.White.copy(alpha = 0.6f)
                    },
                    fontSize = 9.sp
                )
                Text(
                    text = fallbackStateText,
                    color = if (hasPersistedFallback || uiState.beautyDebugState.persistedFallback) {
                        Color(0xFFFFE082)
                    } else {
                        Color.White.copy(alpha = 0.9f)
                    },
                    fontSize = 9.sp
                )
                Text(
                    text = detectionCompact,
                    color = when (uiState.faceWarpParams.detectionSource) {
                        FaceDetectionSource.MEDIAPIPE -> MEDIAPIPE_DEBUG_TEXT_COLOR
                        FaceDetectionSource.MNN -> MNN_DEBUG_TEXT_COLOR  // [性能优化] MNN GPU
                        FaceDetectionSource.NONE -> NONE_DEBUG_TEXT_COLOR
                    },
                    fontSize = 9.sp
                )

                Text(
                    text = lipCompactText,
                    color = if (uiState.beautySettings.lipColor > 0) LIP_HIGHLIGHT_COLOR else Color.White.copy(alpha = 0.6f),
                    fontSize = 9.sp
                )
                Text(
                    text = faceCompactText,
                    color = if (hasFace) Color(0xFF80D8FF) else Color(0xFFFFA000),
                    fontSize = 9.sp
                )
            }
        }
    }
}

private fun mapProviderFailReason(reason: String): String {
    val normalizedReason = reason.lowercase()
    return when {
        normalizedReason.contains("provider view is null") -> "Provider视图缺失"
        normalizedReason.contains("surface not ready") || normalizedReason.contains("surface unavailable") -> "相机Surface未就绪"
        normalizedReason.contains("egl") -> "EGL初始化/绑定失败"
        normalizedReason.contains("timeout") -> "Provider启动超时"
        normalizedReason.contains("resolution") || normalizedReason.contains("buffer") -> "相机缓冲区配置失败"
        normalizedReason.contains("provider unavailable") -> "Provider不可用"
        normalizedReason.contains("stability mode") -> "稳定模式：使用PreviewView"
        else -> "未知Provider失败"
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun BoxScope.CameraPreviewSideControls(
    uiState: CameraPreviewUiState,
    actions: CameraPreviewActions
) {
    // 返回箭头：融入顶部工具栏行——icon 中心与胶囊行垂直中心对齐（2026-08-18 六修：
    // 胶囊 Text 有 48dp 最小触达垫高，行中心=inset+8+24；icon 中心=inset+P+20 → P=12；
    // a11y 实测校准 icon 中心 67.7→72dp 与胶囊中心重合，P 取 8.8dp）
    CameraBackButton(
        onClick = actions.onNavigateBack,
        modifier = Modifier
            .align(Alignment.TopStart)
            .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
            .padding(top = 8.8.dp, start = 8.dp)
    )
    CameraLeftControls(
        onToggleLogOverlay = actions.onToggleLogs,
        debugUiEnabled = uiState.debugUiEnabled,
        showLogOverlay = uiState.showLogOverlay,
        onLlmRelease = actions.onLlmRelease,
        onFaceDetectRelease = actions.onFaceDetectRelease,
        // 向下避让顶部工具栏+返回箭头（camera.yaml §3 top_clearance_below_toolbar = 48）
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(top = 48.dp)
    )
}

/**
 * 顶部居中工具栏 + 内联面板宿主（camera.yaml §4）。
 * 工具栏常驻；ratio/grid/filter/pro 面板以 InlineControlPanel 形式挂在工具栏下方，
 * 从顶部滑入；与美颜底部抽屉全互斥（见面板状态机 §17）。
 */
@OptIn(ExperimentalLayoutApi::class) // statusBarsIgnoringVisibility：沉浸式下仍避让刘海
@Composable
private fun BoxScope.CameraTopControls(
    uiState: CameraPreviewUiState,
    actions: CameraPreviewActions
) {
    val isInlinePanelOpen = uiState.showRatioSelector || uiState.showGridSelector ||
        uiState.showFilterSelector || uiState.showProPanel

    Column(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            // 🔴 同左列：沉浸式下 statusBarsPadding 归零，改用 IgnoringVisibility 避让刘海
            .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
            .padding(top = CameraTokens.topToolBarPaddingTop),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(CameraTokens.topToolBarSpacing)
    ) {
        CameraTopToolBar(
            isBeautySelected = uiState.showBeautySelector,
            isRatioSelected = uiState.showRatioSelector,
            isGridSelected = uiState.showGridSelector,
            isFilterSelected = uiState.showFilterSelector,
            isProSelected = uiState.showProPanel,
            onToggleBeauty = actions.onToggleBeauty,
            onToggleRatio = actions.onToggleRatio,
            onToggleGrid = actions.onToggleGrid,
            onToggleFilter = actions.onToggleFilter,
            onToggleProPanel = actions.onToggleProPanel
        )

        AnimatedVisibility(
            visible = isInlinePanelOpen,
            enter = slideInVertically(initialOffsetY = { offsetY -> -offsetY }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { offsetY -> -offsetY }) + fadeOut()
        ) {
            InlineControlPanel(fillWidth = uiState.showFilterSelector || uiState.showProPanel) {
                when {
                    uiState.showRatioSelector -> SelectorChipRow(
                        SelectorChip(
                            label = stringResource(R.string.ratio_4_3),
                            isSelected = uiState.aspectRatio == AspectRatio.RATIO_4_3,
                            onClick = { actions.onRatioSelected(AspectRatio.RATIO_4_3) }
                        ),
                        SelectorChip(
                            label = stringResource(R.string.ratio_16_9),
                            isSelected = uiState.aspectRatio == AspectRatio.RATIO_16_9,
                            onClick = { actions.onRatioSelected(AspectRatio.RATIO_16_9) }
                        ),
                        SelectorChip(
                            label = stringResource(R.string.ratio_full),
                            isSelected = uiState.aspectRatio == AspectRatio.RATIO_FULL,
                            onClick = { actions.onRatioSelected(AspectRatio.RATIO_FULL) }
                        )
                    )

                    uiState.showGridSelector -> SelectorChipRow(
                        SelectorChip(
                            label = stringResource(R.string.grid_none),
                            isSelected = uiState.currentGrid == GridType.NONE,
                            onClick = { actions.onGridSelected(GridType.NONE) }
                        ),
                        SelectorChip(
                            label = stringResource(R.string.grid_thirds),
                            isSelected = uiState.currentGrid == GridType.THIRDS,
                            onClick = { actions.onGridSelected(GridType.THIRDS) }
                        ),
                        SelectorChip(
                            label = stringResource(R.string.grid_golden),
                            isSelected = uiState.currentGrid == GridType.GOLDEN,
                            onClick = { actions.onGridSelected(GridType.GOLDEN) }
                        )
                    )

                    uiState.showFilterSelector -> UnifiedFilterSelector(
                        selectedFilter = uiState.selectedFilter,
                        selectedStyleFilter = uiState.selectedStyleFilter,
                        onFilterSelected = actions.onFilterSelected,
                        onStyleFilterSelected = actions.onStyleFilterSelected,
                        gridHeight = CameraTokens.inlineFilterPanelHeight
                    )

                    uiState.showProPanel -> ProModeControlsContent(
                        exposure = uiState.exposureCompensation,
                        exposureRange = uiState.exposureRange,
                        onExposureChange = actions.onExposureChange,
                        whiteBalance = uiState.whiteBalanceMode,
                        onWhiteBalanceChange = actions.onWhiteBalanceChange,
                        onTemperatureManualChange = actions.onTemperatureManualChange,
                        beautySettings = uiState.beautySettings,
                        onBeautySettingsChanged = actions.onBeautySettingsChanged
                    )
                }
            }
        }
    }
}

/**
 * 相机预览页右下角浮动按钮组
 * - AI Chat 入口：使用 KeyboardVoice icon（与 Gallery/Settings 一致），
 *   仅设置开启「相机页 AI 助手入口」时显示（2026-08-19 起默认隐藏）
 * - 语音控制入口：使用 RecordVoiceOver icon（区别于 Chat 入口），
 *   仅设置开启「语音控制入口」时显示
 * 两个开关均关闭（默认）时不渲染任何按钮
 */
@Composable
private fun CameraFloatingActionButtons(
    onToggleAiAgentPanel: () -> Unit,
    onToggleVoiceControl: () -> Unit,
    isVoiceControlEnabled: Boolean,
    showVoiceEntry: Boolean,
    showAiChatEntry: Boolean,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // 语音入口隐藏（默认）时保持 null，不创建 AudioRecorder、不查询音频设备
    var inputDevice by remember { mutableStateOf<InputAudioDevice?>(null) }

    // 初始检测；语音入口隐藏时无需检测
    LaunchedEffect(showVoiceEntry) {
        if (showVoiceEntry) {
            inputDevice = AudioRecorder(context).currentInputDevice
        }
    }

    // 注册系统广播监听耳机连接/断开（替代轮询）；语音入口隐藏时无需监听
    DisposableEffect(showVoiceEntry) {
        if (!showVoiceEntry) {
            return@DisposableEffect onDispose {}
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    AudioManager.ACTION_HEADSET_PLUG,
                    BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                        inputDevice = AudioRecorder(context).currentInputDevice
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(AudioManager.ACTION_HEADSET_PLUG)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
        }
        context.registerReceiver(receiver, filter)
        onDispose { context.unregisterReceiver(receiver) }
    }

    val isHeadsetConnected = inputDevice is InputAudioDevice.BluetoothSco ||
        inputDevice is InputAudioDevice.WiredHeadset

    // 两个入口均隐藏（默认）时不渲染任何浮动按钮
    if (!showVoiceEntry && !showAiChatEntry) return

    Column(
        modifier = modifier
            .padding(end = 16.dp, bottom = 180.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.End
    ) {
        // 语音控制按钮 - 使用 RecordVoiceOver 区别于 Chat 入口
        // 语音为非刚需：仅当设置中开启「语音控制入口」时显示
        if (showVoiceEntry) {
            Box {
                FloatingActionButton(
                    onClick = onToggleVoiceControl,
                    modifier = Modifier.size(52.dp),
                    shape = CircleShape,
                    containerColor = if (isVoiceControlEnabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color.Black.copy(alpha = 0.6f)
                    }
                ) {
                    Icon(
                        imageVector = Icons.Rounded.RecordVoiceOver,
                        contentDescription = stringResource(R.string.voice_control),
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
                // 耳机连接状态小标记
                val headsetDevice = inputDevice
                if (isHeadsetConnected && headsetDevice != null) {
                    CameraHeadsetBadge(
                        device = headsetDevice,
                        modifier = Modifier.align(Alignment.TopEnd)
                    )
                }
            }
        }

        // AI Chat 入口按钮 - 使用 KeyboardVoice（与 Gallery/Settings 一致）
        // 2026-08-19：语音/AI 悬浮入口全面默认隐藏，仅设置开启「相机页 AI 助手入口」时显示
        if (showAiChatEntry) {
            FloatingActionButton(
                onClick = onToggleAiAgentPanel,
                modifier = Modifier.size(52.dp),
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(
                    imageVector = Icons.Rounded.KeyboardVoice,
                    contentDescription = stringResource(R.string.ai_agent),
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

/**
 * 相机页耳机状态小标记
 */
@Composable
private fun CameraHeadsetBadge(
    device: InputAudioDevice,
    modifier: Modifier = Modifier
) {
    val tintColor = when (device) {
        is InputAudioDevice.BluetoothSco -> Color(0xFF4FC3F7)
        is InputAudioDevice.WiredHeadset -> Color(0xFF81C784)
        is InputAudioDevice.BuiltInMic -> Color.Transparent
    }

    Box(
        modifier = modifier
            .padding(top = 2.dp, end = 2.dp)
            .size(16.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
            .padding(2.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.Headphones,
            contentDescription = null,
            tint = tintColor,
            modifier = Modifier.size(12.dp)
        )
    }
}



