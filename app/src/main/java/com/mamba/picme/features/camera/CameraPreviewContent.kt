package com.mamba.picme.features.camera

import android.bluetooth.BluetoothHeadset
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
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
import com.mamba.picme.domain.model.AiAgentCommand
import com.mamba.picme.domain.usecase.AiAgentUseCase
import com.mamba.picme.features.camera.components.BeautyPanel
import com.mamba.picme.features.camera.components.CameraBottomControls
import com.mamba.picme.features.camera.components.CameraLeftControls
import com.mamba.picme.features.camera.components.CameraOverlays
import com.mamba.picme.features.camera.components.CameraRightControls
import com.mamba.picme.features.camera.components.ControlPanel
import com.mamba.picme.features.camera.components.DocumentDetectionOverlay
import com.mamba.picme.features.camera.components.GridSelector
import com.mamba.picme.features.camera.components.ProModeControls
import com.mamba.picme.features.camera.components.RatioSelector
import com.mamba.picme.features.camera.components.SceneSelector
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
    // 非美颜类面板开启状态（美颜面板用独立的 BeautyPanel 渲染，不走 PrimaryControlPanels）
    val isAnyPanelOpen = uiState.showFilterSelector || uiState.showRatioSelector ||
        uiState.showSceneSelector || uiState.showGridSelector
    val isBeautyPanelOpen = uiState.showBeautySelector
    val isProPanelOpen = uiState.showProPanel

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // 点击取景区空白处关闭所有面板
            .clickable(
                enabled = isAnyPanelOpen || isBeautyPanelOpen || isProPanelOpen,
                onClick = {
                    if (isProPanelOpen) {
                        actions.onToggleProPanel()
                    } else {
                        actions.onDismissPanels()
                    }
                }
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

        CameraBottomControls(
            lastMedia = uiState.lastMedia,
            zoomRatio = uiState.zoomRatio,
            minZoomRatio = uiState.minZoomRatio,
            maxZoomRatio = uiState.maxZoomRatio,
            captureMode = uiState.captureMode,
            isRecording = uiState.isRecording,
            isAnyPanelOpen = isAnyPanelOpen,
            onZoomPresetClick = actions.onZoomPresetClick,
            onGalleryClick = actions.onGalleryClick,
            onCaptureClick = actions.onCaptureClick,
            onFlipCamera = actions.onFlipCamera,
            onModeChange = actions.onModeChange,
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        // Pro Mode Controls - 底部 Sheet 风格，与其他面板保持一致
        // 必须在 CameraBottomControls 之后声明，确保浮层盖在固定按钮之上
        AnimatedVisibility(
            visible = isProPanelOpen && !isAnyPanelOpen,
            enter = slideInVertically(initialOffsetY = { offsetY -> offsetY }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { offsetY -> offsetY }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            ProModeControls(
                exposure = uiState.exposureCompensation,
                exposureRange = uiState.exposureRange,
                onExposureChange = actions.onExposureChange,
                whiteBalance = uiState.whiteBalanceMode,
                onWhiteBalanceChange = actions.onWhiteBalanceChange,
                onClose = { actions.onToggleProPanel() },
                beautySettings = uiState.beautySettings,
                onBeautySettingsChanged = actions.onBeautySettingsChanged,
            )
        }

        // 美颜面板（统一入口：使用美图秀秀风格 Tab 标签页）
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

        PrimaryControlPanels(
            uiState = uiState,
            actions = actions,
            isAnyPanelOpen = isAnyPanelOpen
        )

        // 同步语音协调器状态
        onUpdateVoiceCoordinatorState?.invoke()

        // AI Agent 和语音控制浮动按钮 - 右下角，方便拇指点击
        CameraFloatingActionButtons(
            onToggleAiAgentPanel = actions.onToggleAiAgentPanel,
            onToggleVoiceControl = actions.onToggleVoiceControl,
            isVoiceControlEnabled = uiState.isVoiceControlEnabled,
            modifier = Modifier.align(Alignment.BottomEnd)
        )
    }

    // AI Agent 面板：使用统一的 AiChatScreen
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
    if (uiState.beautySettings.eyebrow > 0f) {
        activeEffects.add("BROW")
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
private fun BoxScope.CameraPreviewSideControls(
    uiState: CameraPreviewUiState,
    actions: CameraPreviewActions
) {
    CameraLeftControls(
        onResetCameraMemoryState = actions.onResetCameraMemoryState,
        onToggleLogOverlay = actions.onToggleLogs,
        debugUiEnabled = uiState.debugUiEnabled,
        showLogOverlay = uiState.showLogOverlay,
        onAsrRelease = actions.onAsrRelease,
        onLlmRelease = actions.onLlmRelease,
        onFaceDetectRelease = actions.onFaceDetectRelease,
        onNavigateBack = actions.onNavigateBack,
        modifier = Modifier.align(Alignment.TopStart)
    )

    CameraRightControls(
        onToggleBeauty = actions.onToggleBeauty,
        onToggleFilter = actions.onToggleFilter,
        onToggleRatio = actions.onToggleRatio,
        onToggleScene = actions.onToggleScene,
        onToggleGrid = actions.onToggleGrid,
        onToggleProPanel = actions.onToggleProPanel,
        onToggleBeautyEnabled = {
            actions.onBeautySettingsChanged(
                uiState.beautySettings.copy(enabled = !uiState.beautySettings.enabled)
            )
        },
        isBeautySelected = uiState.showBeautySelector,
        isFilterSelected = uiState.showFilterSelector,
        isRatioSelected = uiState.showRatioSelector,
        isSceneActive = uiState.currentScene != ScenePreset.NONE,
        isGridActive = uiState.showGridSelector,
        isProPanelOpen = uiState.showProPanel,
        isBeautyEnabled = uiState.beautySettings.enabled,
        currentRatio = uiState.aspectRatio,
        modifier = Modifier.align(Alignment.TopEnd)
    )
}

@Composable
private fun BoxScope.PrimaryControlPanels(
    uiState: CameraPreviewUiState,
    actions: CameraPreviewActions,
    isAnyPanelOpen: Boolean
) {
    AnimatedVisibility(
        visible = isAnyPanelOpen,
        enter = slideInVertically(initialOffsetY = { offsetY -> offsetY }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { offsetY -> offsetY }) + fadeOut(),
        modifier = Modifier.align(Alignment.BottomCenter)
    ) {
        ControlPanel(onDismiss = actions.onDismissPanels) {
            when {
                uiState.showFilterSelector -> {
                    UnifiedFilterSelector(
                        selectedFilter = uiState.selectedFilter,
                        selectedStyleFilter = uiState.selectedStyleFilter,
                        onFilterSelected = actions.onFilterSelected,
                        onStyleFilterSelected = actions.onStyleFilterSelected
                    )
                }
                uiState.showRatioSelector -> {
                    RatioSelector(
                        selectedRatio = when (uiState.aspectRatio) {
                            AspectRatio.RATIO_4_3 -> CameraAspectRatio.RATIO_4_3
                            AspectRatio.RATIO_16_9 -> CameraAspectRatio.RATIO_16_9
                            AspectRatio.RATIO_FULL -> CameraAspectRatio.RATIO_FULL
                            else -> CameraAspectRatio.RATIO_FULL
                        },
                        onRatioSelected = { selectedRatio ->
                            actions.onRatioSelected(
                                when (selectedRatio) {
                                    CameraAspectRatio.RATIO_4_3 -> AspectRatio.RATIO_4_3
                                    CameraAspectRatio.RATIO_16_9 -> AspectRatio.RATIO_16_9
                                    CameraAspectRatio.RATIO_FULL -> AspectRatio.RATIO_FULL
                                }
                            )
                        }
                    )
                }
                uiState.showSceneSelector -> {
                    SceneSelector(uiState.currentScene) { selectedScene ->
                        actions.onSceneSelected(selectedScene)
                    }
                }
                uiState.showGridSelector -> {
                    GridSelector(uiState.currentGrid) { selectedGrid ->
                        actions.onGridSelected(selectedGrid)
                    }
                }
            }
        }
    }
}

/**
 * 相机预览页右下角浮动按钮组
 * - AI Chat 入口：使用 KeyboardVoice icon（与 Gallery/Settings 一致）
 * - 语音控制入口：使用 RecordVoiceOver icon（区别于 Chat 入口）
 */
@Composable
private fun CameraFloatingActionButtons(
    onToggleAiAgentPanel: () -> Unit,
    onToggleVoiceControl: () -> Unit,
    isVoiceControlEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var inputDevice by remember { mutableStateOf(AudioRecorder(context).currentInputDevice) }

    // 初始检测
    LaunchedEffect(Unit) {
        inputDevice = AudioRecorder(context).currentInputDevice
    }

    // 注册系统广播监听耳机连接/断开（替代轮询）
    DisposableEffect(Unit) {
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

    Column(
        modifier = modifier
            .padding(end = 16.dp, bottom = 180.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.End
    ) {
        // 语音控制按钮 - 使用 RecordVoiceOver 区别于 Chat 入口
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
            if (isHeadsetConnected) {
                CameraHeadsetBadge(
                    device = inputDevice,
                    modifier = Modifier.align(Alignment.TopEnd)
                )
            }
        }

        // AI Chat 入口按钮 - 使用 KeyboardVoice（与 Gallery/Settings 一致）
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



