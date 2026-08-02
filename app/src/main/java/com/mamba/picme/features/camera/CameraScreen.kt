@file:Suppress("OPT_IN_USAGE_ERROR")

package com.mamba.picme.features.camera

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.SoundEffectConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.mamba.picme.R
import com.mamba.picme.agent.core.remote.config.RemoteModelConfigs
import com.mamba.picme.agent.core.facade.AgentOrchestrator
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.agent.core.model.config.AiAgentInferencePreference
import com.mamba.picme.agent.core.model.config.AiAgentMode
import com.mamba.picme.mnn.MnnResourceManager
import com.mamba.picme.agent.core.platform.voice.AsrEngine
import com.mamba.picme.agent.core.platform.voice.SherpaOnnxAsrEngine
import com.mamba.picme.agent.core.runtime.state.SceneManager
import com.mamba.picme.beauty.api.BeautyPerfStats
import com.mamba.picme.beauty.api.BeautySettings
import com.mamba.picme.beauty.api.FilterType
import com.mamba.picme.beauty.api.facedetect.DetectionPipelineConfig
import com.mamba.picme.beauty.api.facedetect.EngineType
import com.mamba.picme.beauty.api.facedetect.FaceWarpParams
import com.mamba.picme.beauty.internal.facedetect.FaceDetectorManager
import com.mamba.picme.beauty.recorder.BeautyVideoRecorder
import com.mamba.picme.beauty.render.GlBeautyPreviewProvider
import com.mamba.picme.core.common.Logger
import com.mamba.picme.di.BeautyEngineRuntimeState
import com.mamba.picme.domain.model.AiAgentCommand
import com.mamba.picme.domain.model.BeautyStrategy
import com.mamba.picme.domain.model.CameraMemoryState
import com.mamba.picme.domain.model.DetectionModelType
import com.mamba.picme.domain.model.InferenceEngineType
import com.mamba.picme.domain.model.VoiceCommandMode
import com.mamba.picme.domain.usecase.AiAgentUseCase
import com.mamba.picme.features.camera.capability.CameraCapability
import com.mamba.picme.features.camera.facedetect.ImageUtils
import com.mamba.picme.features.camera.state.CameraStateMachine
import com.mamba.picme.features.camera.state.CameraStateManager
import com.mamba.picme.features.camera.thread.CameraThreadRegistry
import com.mamba.picme.features.camera.voice.SystemAsrEngine
import com.mamba.picme.features.camera.voice.VoiceCommandCoordinator
import com.mamba.picme.features.camera.voice.createDefaultAsrEngine
import com.mamba.picme.features.common.chat.AgentMessage
import com.mamba.picme.features.gallery.MediaViewModel
import com.mamba.picme.features.settings.SettingsViewModel
import com.mamba.picme.PoLangApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.sqrt

private const val TAG = "Camera"
private const val TAG_AI_AGENT = "AiAgent"

private const val PROVIDER_VIEW_BIND_TIMEOUT_MS = 5000L
private const val DEBUG_RELEASE_TOAST_DELAY_MS = 350L

private var activeReleaseDialog: AlertDialog? = null

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun captureMnnMemoryStats(context: Context): MnnResourceManager.MemoryStats? = runCatching {
    MnnResourceManager.getInstance(context).getMemoryStats()
}.getOrNull()

private suspend fun sampleMemoryAfterRelease(context: Context): MnnResourceManager.MemoryStats? {
    delay(DEBUG_RELEASE_TOAST_DELAY_MS)
    Runtime.getRuntime().gc()
    System.runFinalization()
    delay(120)
    return captureMnnMemoryStats(context)
}

private fun showReleaseMemoryToast(
    context: Context,
    target: String,
    releaseMode: String,
    before: MnnResourceManager.MemoryStats?,
    after: MnnResourceManager.MemoryStats?
) {
    val message = if (before == null || after == null) {
        "$target($releaseMode) 已触发释放\n内存采样失败，请查看日志"
    } else {
        val releasedNative = (before.nativeHeapMB - after.nativeHeapMB).coerceAtLeast(0)
        val releasedJava = (before.javaHeapUsedMB - after.javaHeapUsedMB).coerceAtLeast(0)
        "$target($releaseMode) ✓ 释放完成\n" +
            "当前: Native ${after.nativeHeapMB}MB | Java ${after.javaHeapUsedMB}MB\n" +
            "释放: Native -${releasedNative}MB | Java -${releasedJava}MB"
    }

    Logger.i(TAG, "🔓 [内存释放] $target($releaseMode)\n$message")

    val activity = context.findActivity()
    if (activity == null || activity.isFinishing || activity.isDestroyed) {
        Logger.w(TAG, "[内存释放] Activity 不可用，弹窗未显示: $target($releaseMode)")
        return
    }

    activity.runOnUiThread {
        activeReleaseDialog?.dismiss()
        activeReleaseDialog = AlertDialog.Builder(activity)
            .setTitle("内存释放结果")
            .setMessage(message)
            .setCancelable(true)
            .setPositiveButton("知道了") { dialog, _ ->
                dialog.dismiss()
            }
            .create()

        activeReleaseDialog?.show()
    }
}

private fun BeautySettings.requiresFaceDetection(): Boolean {
    return slimFace != 0f || bigEyes > 0f || lipColor > 0f || blush > 0f || eyebrow > 0f
}

/**
 * RD 人脸坐标转换函数 - 简化版（分离关注点）
 *
 * 【核心思路】将复杂的变换分解为三个独立步骤：
 * 1. 归一化：ML Kit 坐标 → [0,1] 归一化坐标
 * 2. 镜像处理：前置摄像头需要水平翻转
 * 3. 旋转补偿：根据设备旋转角度调整坐标系
 *
 * 【关键发现】所有坐标系都使用左上角原点，X 向右，Y 向下
 * 因此只需要关注旋转和镜像，不需要考虑坐标轴翻转
 */
internal fun transformFaceCoordinate(
    faceX: Float,
    faceY: Float,
    imageProxyWidth: Int,
    imageProxyHeight: Int,
    previewView: PreviewView,
    rotationDegrees: Int,
    lensFacing: Int
): Offset {
    // ========== Step 1: 归一化 ==========
    val (rotatedWidth, rotatedHeight) = when (rotationDegrees) {
        90, 270 -> Pair(imageProxyHeight, imageProxyWidth)
        else -> Pair(imageProxyWidth, imageProxyHeight)
    }

    val normX = faceX / rotatedWidth
    val normY = faceY / rotatedHeight

    Logger.d(
        TAG,
        "Step1 [归一化]: face=($faceX,$faceY), rotatedSize=${rotatedWidth}x${rotatedHeight}, " +
            "norm=($normX,$normY)"
    )

    // ========== Step 2: 镜像处理（前置摄像头） ==========
    // 在旋转之前先镜像 X 轴（传感器坐标系）
    val mirroredX = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
        1f - normX
    } else {
        normX
    }

    Logger.d(
        TAG,
        "Step2 [镜像]: lens=${if (lensFacing == CameraSelector.LENS_FACING_FRONT) "前" else "后"}, " +
            "norm=($normX,$normY), mirrored=($mirroredX,$normY)"
    )

    // ========== Step 3: 旋转补偿 ==========
    // 根据旋转角度调整坐标方向
    val (adjustedX, adjustedY) = when (rotationDegrees) {
        0 -> Pair(mirroredX, normY)      // 竖屏：不需要调整
        90 -> Pair(mirroredX, normY)     // 顺时针 90°: 不交换 XY
        180 -> Pair(1f - mirroredX, 1f - normY) // 倒立：XY 都翻转
        270 -> Pair(mirroredX, normY)    // 逆时针 90°: 不交换 XY
        else -> Pair(mirroredX, normY)
    }

    Logger.d(
        TAG,
        "Step3 [旋转补偿]: rot=$rotationDegrees, mirrored=($mirroredX,$normY), " +
            "adjusted=($adjustedX,$adjustedY)"
    )

    // ========== Step 4: 转换为像素坐标 ==========
    // 将归一化坐标转换为 PreviewView 的物理像素坐标
    val previewWidth = previewView.width.toFloat()
    val previewHeight = previewView.height.toFloat()

    val screenX = adjustedX * previewWidth
    val screenY = adjustedY * previewHeight

    Logger.d(
        TAG,
        "Step4 [像素转换]: adj=($adjustedX,$adjustedY), previewSize=${previewWidth.toInt()}x${previewHeight.toInt()}, " +
            "screen=($screenX,$screenY)"
    )

    return Offset(screenX, screenY)
}

private data class PreviewTargetDecision(
    val targetView: View,
    val scheduleProviderFallback: Boolean
)

private fun resolvePreviewTargetView(
    useProviderRenderView: Boolean,
    activeStrategy: BeautyStrategy,
    runtimeProviderView: View?,
    previewView: View
): PreviewTargetDecision {
    if (!useProviderRenderView) {
        return PreviewTargetDecision(
            targetView = previewView,
            scheduleProviderFallback = false
        )
    }

    val providerView = runtimeProviderView

    val requiresProviderView = activeStrategy == BeautyStrategy.BIG_BEAUTY

    if (requiresProviderView && providerView == null) {
        return PreviewTargetDecision(
            targetView = previewView,
            scheduleProviderFallback = true
        )
    }

    return PreviewTargetDecision(
        targetView = providerView ?: previewView,
        scheduleProviderFallback = false
    )
}

@OptIn(ExperimentalPermissionsApi::class)
@ExperimentalGetImage
@Composable
fun CameraScreen(
    onNavigateToGallery: () -> Unit,
    onNavigateBack: () -> Unit = {},
    viewModel: MediaViewModel,
    settingsViewModel: SettingsViewModel? = null,
    /** 是否为主页面 Pager 中的当前活跃页：非活跃时门控相机绑定/沉浸式/语音与模型加载 */
    isActivePage: Boolean = true
) {
    // RD 沉浸式模式：隐藏系统栏（仅活跃页生效，避免 Pager 常驻组合时全局隐藏系统栏）
    val view = LocalView.current
    val context = LocalContext.current

    DisposableEffect(isActivePage) {
        if (!isActivePage) return@DisposableEffect onDispose {}
        val activity = context.findActivity() ?: return@DisposableEffect onDispose {}
        val window = activity.window
        val previousSoftInputMode = window.attributes.softInputMode
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        val insetsController = WindowCompat.getInsetsController(window, view)

        // 隐藏状态栏和导航栏
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        // 设置沉浸式模式，滑动边缘时显示系统栏
        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        Logger.d("Camera", "Immersive mode enabled")

        onDispose {
            window.setSoftInputMode(previousSoftInputMode)
            // 恢复系统栏显示
            insetsController.show(WindowInsetsCompat.Type.systemBars())
            Logger.d("Camera", "Immersive mode disabled")
        }
    }

    val permissionsState = rememberMultiplePermissionsState(
        permissions = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
    )

    if (permissionsState.allPermissionsGranted) {
        android.util.Log.i("CameraDebug", "CameraScreen: permissions granted, calling CameraContent")
        CameraContent(
            viewModel = viewModel,
            onNavigateToGallery = onNavigateToGallery,
            onNavigateBack = onNavigateBack,
            settingsViewModel = settingsViewModel,
            isActivePage = isActivePage
        )
    } else {
        android.util.Log.i("CameraDebug", "CameraScreen: permissions NOT granted")
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Videocam,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.size(20.dp))
                Text(
                    text = stringResource(R.string.camera_permission_title),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = stringResource(R.string.camera_permission_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.size(24.dp))
                Button(
                    onClick = { permissionsState.launchMultiplePermissionRequest() },
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) {
                    Text(stringResource(R.string.grant_permissions))
                }
            }
        }
    }
}

@SuppressLint("MissingPermission", "UnusedMaterial3ScaffoldPaddingParameter")
@OptIn(ExperimentalMaterial3Api::class)
@ExperimentalGetImage
@Composable
fun CameraContent(
    viewModel: MediaViewModel,
    onNavigateToGallery: () -> Unit,
    onNavigateBack: () -> Unit = {},
    settingsViewModel: SettingsViewModel? = null,
    /** 是否为主页面 Pager 中的当前活跃页：非活跃时解绑相机并暂停语音/模型加载 */
    isActivePage: Boolean = true
) {
    val context = LocalContext.current
    val runtimeContext = rememberCameraRuntimeContext(context)
    val imageProcessor = runtimeContext.imageProcessor
    val userPreferencesRepository = runtimeContext.userPreferencesRepository
    val coroutineScope = runtimeContext.coroutineScope
    val scope = rememberCoroutineScope()
    val beautyStrategy = runtimeContext.beautyStrategy
    val debugUiEnabled = runtimeContext.debugUiEnabled
    val showCameraInfoInPreview = runtimeContext.showCameraInfoInPreview
    val showFaceDebugOverlay = runtimeContext.showFaceDebugOverlay
    val showLogOverlay = runtimeContext.showLogOverlay
    val faceDetectionEngineMode = runtimeContext.faceDetectionEngineMode
    val faceLandmarkModeEnabled = runtimeContext.faceLandmarkModeEnabled
    val adaptiveFaceDetectionIntervalEnabled = runtimeContext.adaptiveFaceDetectionIntervalEnabled
    val faceDetectIntervalProfile = runtimeContext.faceDetectIntervalProfile
    val glRecoveryAvailableAtMs = runtimeContext.glRecoveryAvailableAtMs
    val lifecycleOwner = runtimeContext.lifecycleOwner
    LaunchedEffect(beautyStrategy) {
        val fallbackReason = BeautyEngineRuntimeState.consumeGlEngineFallbackReason()
        if (fallbackReason != null) {
            Logger.w(
                "Camera",
                "Beauty engine fallback active: strategy=${beautyStrategy.name}, reason=$fallbackReason"
            )
        } else {
            Logger.i("Camera", "Beauty engine strategy active: ${beautyStrategy.name}")
        }
    }
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    // [Day1 线程隔离] 初始化线程注册表（幂等，可安全多次调用）
    LaunchedEffect(Unit) {
        CameraThreadRegistry.initialize()
    }



    // [Day1 线程隔离] 分析线程与拍照线程分离，避免人脸检测阻塞拍照回调
    val analysisExecutor = remember {
        Executor {
            CameraThreadRegistry.getAnalysisHandler().post(it)
        }
    }
    val captureExecutor = remember {
        Executor {
            CameraThreadRegistry.getCameraHandler().post(it)
        }
    }
    // [三位一体反馈] 黑场动画状态
    val shutterFlashAlpha = remember { Animatable(0f) }
    var isShutterFlashing by remember { mutableStateOf(false) }

    var previewRebindSignal by remember { mutableIntStateOf(0) }

    // [Day2 状态机硬编码] 统一管理相机状态，替换分散的 isRecording/captureMode 等布尔标志
    val cameraStateManager = remember { CameraStateManager() }

    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var captureMode by remember { mutableStateOf(MediaType.PHOTO) }
    var isRecording by remember { mutableStateOf(false) }
    var selectedFilter by remember { mutableStateOf(FilterType.NONE) }
    var beautySettings by remember { mutableStateOf(BeautySettings(enabled = false)) }
    var aspectRatio by remember { mutableIntStateOf(AspectRatio.RATIO_FULL) }
    var exposureCompensation by remember { mutableIntStateOf(0) }
    var whiteBalanceMode by remember { mutableIntStateOf(0) }

    var faceWarpParams by remember { mutableStateOf(FaceWarpParams()) }
    var previewFaceWarpParams by remember { mutableStateOf(FaceWarpParams()) }
    var lastFaceWarpDetectedAtMs by remember { mutableStateOf(0L) }
    var isFacePipelineInitialized by remember { mutableStateOf(false) }

    val previewRuntimeViews = rememberPreviewRuntimeViews(
        context = context,
        aspectRatio = aspectRatio,
        beautyStrategy = beautyStrategy
    )
    val previewView = previewRuntimeViews.previewView
    val glPreviewProvider = previewRuntimeViews.glPreviewProvider

    val recoveryState = rememberGlRecoveryState(
        beautyStrategy = beautyStrategy,
        glRecoveryAvailableAtMs = glRecoveryAvailableAtMs,
        userPreferencesRepository = userPreferencesRepository,
        coroutineScope = coroutineScope
    )
    val persistedGlFallback = recoveryState.persistedFallback
    val persistedGlFallbackReason = recoveryState.persistedFallbackReason

    val previewStrategyBundle = rememberPreviewStrategyBundle(
        beautyStrategy = beautyStrategy,
        previewView = previewView,
        glPreviewProvider = glPreviewProvider,
        onGlWarmUpFallback = recoveryState.onGlWarmUpFallback
    )
    val activePreviewStrategy = previewStrategyBundle.activeStrategy

    var useProviderRenderView by remember(beautyStrategy) {
        mutableStateOf(beautyStrategy == BeautyStrategy.BIG_BEAUTY)
    }
    var lipRealtimeRecoveryRequested by remember { mutableStateOf(false) }
    var lastLipPreviewRebindRequestMs by remember { mutableStateOf(0L) }

    val bindPreviewSurfaceProvider: (Preview) -> Unit = { previewUseCase ->
        useProviderRenderView = activePreviewStrategy.bindPreview(previewUseCase, aspectRatio)
    }

    LaunchedEffect(useProviderRenderView, beautyStrategy, previewRebindSignal) {
        if (!useProviderRenderView) {
            return@LaunchedEffect
        }

        if (beautyStrategy != BeautyStrategy.BIG_BEAUTY && beautyStrategy != BeautyStrategy.BIG_BEAUTY) {
            return@LaunchedEffect
        }

        delay(PROVIDER_VIEW_BIND_TIMEOUT_MS)
        if (!useProviderRenderView) {
            return@LaunchedEffect
        }

        if (glPreviewProvider?.isReady() != true) {
            Logger.w(
                "Camera",
                "Provider view bind timeout, fallback to PreviewView and request rebind"
            )
            useProviderRenderView = false
            previewRebindSignal += 1
        }
    }

    // RD 快路径：参数变更立即下发到当前预览引擎，保证滑杆跟手性。
    LaunchedEffect(beautySettings, beautyStrategy) {
        val settings = beautySettings
        activePreviewStrategy.applyBeautySettings(settings)

        Logger.d(
            "Camera",
            "Beauty params updated: engine=${activePreviewStrategy.strategy.name}, smoothing=${settings.smoothing}, " +
                "whitening=${settings.whitening}, bigEyes=${settings.bigEyes}, slimFace=${settings.slimFace}"
        )
    }

    LaunchedEffect(faceWarpParams, beautyStrategy, beautySettings.enabled, beautySettings.lipColor) {
        val lipRealtimeRequired = beautySettings.enabled && beautySettings.lipColor > 0f
        val nowMs = System.currentTimeMillis()

        val nextPreviewParams = when {
            faceWarpParams.hasFace -> {
                lastFaceWarpDetectedAtMs = nowMs
                faceWarpParams
            }
            lipRealtimeRequired && previewFaceWarpParams.hasFace &&
                nowMs - lastFaceWarpDetectedAtMs <= 320L -> {
                previewFaceWarpParams
            }
            else -> faceWarpParams
        }

        if (nextPreviewParams != previewFaceWarpParams) {
            previewFaceWarpParams = nextPreviewParams
        }

        activePreviewStrategy.applyFaceWarpParams(nextPreviewParams)
    }

    LaunchedEffect(
        beautyStrategy,
        beautySettings.lipColor,
        beautySettings.enabled,
        useProviderRenderView
    ) {
        val lipRealtimeRequired = beautySettings.enabled && beautySettings.lipColor > 0f
        if (!lipRealtimeRequired) {
            lipRealtimeRecoveryRequested = false
            return@LaunchedEffect
        }

        if (useProviderRenderView) {
            lipRealtimeRecoveryRequested = false
            return@LaunchedEffect
        }

        val nowMs = System.currentTimeMillis()
        if (nowMs - lastLipPreviewRebindRequestMs >= 1500L) {
            lastLipPreviewRebindRequestMs = nowMs
            Logger.w(
                "Camera",
                "Lip realtime preview unavailable, request provider rebind: strategy=${beautyStrategy.name}"
            )
            previewRebindSignal += 1
        }
    }

    // RD 监听比例变化，动态调整 ScaleType
    LaunchedEffect(aspectRatio) {
        previewView.scaleType = when (aspectRatio) {
            AspectRatio.RATIO_FULL -> PreviewView.ScaleType.FILL_CENTER
            else -> PreviewView.ScaleType.FIT_CENTER
        }
        Logger.d("Camera",
            "ScaleType updated to ${previewView.scaleType} for aspectRatio=$aspectRatio"
        )
    }

    val panelState = rememberCameraPanelState()
    var aiAgentChatVisible by remember { mutableStateOf(false) }
    var aiAgentMessages by remember { mutableStateOf<List<AgentMessage>>(emptyList()) }
    var aiAgentIsProcessing by remember { mutableStateOf(false) }
    val aiAgentLocalModel by userPreferencesRepository.aiAgentLocalModelFlow.collectAsState(initial = "")
    val aiAgentLocalUseOpencl by userPreferencesRepository.aiAgentLocalUseOpenclFlow.collectAsState(initial = false)

    val aiAgentRemoteModelConfigs by userPreferencesRepository.aiAgentRemoteModelConfigsFlow.collectAsState(initial = "")
    val aiAgentSelectedRemoteModel by userPreferencesRepository.aiAgentSelectedRemoteModelFlow.collectAsState(initial = "deepseek-v4-flash")
    val aiAgentInferencePreference by userPreferencesRepository.aiAgentInferencePreferenceFlow.collectAsState(initial = AiAgentInferencePreference.FORCE_LOCAL)
    val aiAgentMode by userPreferencesRepository.aiAgentModeFlow.collectAsState(initial = AiAgentMode.LOCAL)
    val voiceCommandMode by userPreferencesRepository.voiceCommandModeFlow.collectAsState(
        initial = VoiceCommandMode.DISABLED
    )
    val voiceEntryEnabled by userPreferencesRepository.voiceEntryEnabledFlow.collectAsState(
        initial = false
    )

    // 解析远程模型配置
    // 注意：aiAgentSelectedRemoteModel 保存的是 uniqueKey（providerId:modelId），
    // 优先按 uniqueKey 查找；找不到再按 modelId 查找。
    // 用户未配置时返回 null，让 AiAgentUseCase 使用 SCF 兜底配置。
    val remoteConfig = remember(aiAgentRemoteModelConfigs, aiAgentSelectedRemoteModel) {
        val configs = if (aiAgentRemoteModelConfigs.isNotBlank()) {
            RemoteModelConfigs.fromJson(aiAgentRemoteModelConfigs)
        } else {
            RemoteModelConfigs()
        }
        configs.getConfig(aiAgentSelectedRemoteModel)
            ?: configs.getConfigByModelId(aiAgentSelectedRemoteModel)
    }

    // 读取服务端认证 token（邮箱注册）
    val serverAuthToken by userPreferencesRepository.serverAuthTokenFlow.collectAsState(initial = "")

    // 当关键配置变化时重新创建 UseCase（mode/remoteConfig/forceRemote/gatewayToken 等）
    val aiAgentUseCase = remember(
        context,
        aiAgentMode,
        remoteConfig,
        aiAgentInferencePreference,
        aiAgentLocalUseOpencl,
        serverAuthToken
    ) {
        AiAgentUseCase(
            context = context,
            agentMode = aiAgentMode,
            localModelId = "qwen3_5_2b",
            localUseOpencl = aiAgentLocalUseOpencl,
            remoteConfig = remoteConfig,
            forceRemote = aiAgentInferencePreference == AiAgentInferencePreference.FORCE_REMOTE,
            gatewayToken = serverAuthToken.takeIf { it.isNotBlank() }
        )
    }

    // 监听 Agent 模型加载状态，用于在相机页显示 "Agent 启动中"
    val aiAgentModelLoading by aiAgentUseCase.isLocalModelLoading.collectAsState(initial = false)

    // LLM 按需加载：仅在 AI Chat 打开或语音控制打开时加载本地模型。
    val resolvedModelId = remember(aiAgentLocalModel) {
        aiAgentLocalModel.takeIf { it.isNotBlank() } ?: "qwen3_5_2b"
    }
    val shouldLoadLocalLlm = remember(
        aiAgentChatVisible,
        voiceCommandMode,
        aiAgentMode,
        aiAgentInferencePreference,
        isActivePage
    ) {
        val localModeEnabled = aiAgentMode == AiAgentMode.LOCAL || aiAgentMode == AiAgentMode.OFF
        val forceRemote = aiAgentInferencePreference == AiAgentInferencePreference.FORCE_REMOTE
        isActivePage && localModeEnabled && !forceRemote &&
            (aiAgentChatVisible || voiceCommandMode != VoiceCommandMode.DISABLED)
    }
    LaunchedEffect(resolvedModelId, aiAgentLocalUseOpencl, shouldLoadLocalLlm) {
        if (!shouldLoadLocalLlm) {
            if (aiAgentUseCase.isLocalModelLoaded) {
                Logger.i(TAG_AI_AGENT, "No active AI entry, unload local MNN-LLM model")
                aiAgentUseCase.unloadLocalModel()
            }
            return@LaunchedEffect
        }

        if (!aiAgentUseCase.isLocalModelLoaded) {
            Logger.i(TAG_AI_AGENT, "Loading local MNN-LLM model on demand: $resolvedModelId, opencl=$aiAgentLocalUseOpencl")
            val result = aiAgentUseCase.loadLocalModel(resolvedModelId, aiAgentLocalUseOpencl)
            Logger.i(TAG_AI_AGENT, "Local MNN-LLM model load result: $result")
        } else {
            Logger.d(TAG_AI_AGENT, "Local MNN-LLM model already loaded, skip")
        }
    }

    // 场景切换由 MainActivity 统一管理，此处不再重复设置
    // 保留 DisposableEffect 以防止 key 变化导致不必要的重组
    DisposableEffect(Unit) {
        // no-op: scene management handled by MainActivity
        onDispose { }
    }

    // 语音命令协调器

    // ASR 按需加载：仅在语音入口（唤醒词/AI Chat）激活时初始化本地 ASR。
    val localAsrModel by userPreferencesRepository.localAsrModelFlow.collectAsState(initial = "")
    var asrEngine by remember(context) {
        mutableStateOf<AsrEngine>(SystemAsrEngine(context))
    }
    val shouldLoadLocalAsr = remember(voiceCommandMode, aiAgentChatVisible, isActivePage) {
        isActivePage && (voiceCommandMode != VoiceCommandMode.DISABLED || aiAgentChatVisible)
    }
    LaunchedEffect(context, localAsrModel, shouldLoadLocalAsr) {
        if (!shouldLoadLocalAsr) {
            asrEngine.release()
            asrEngine = SystemAsrEngine(context)
            Logger.d(TAG, "ASR entry inactive, keep system ASR only")
            return@LaunchedEffect
        }

        val engine = withContext(Dispatchers.IO) {
            if (localAsrModel.isNotBlank()) {
                val modelDir = context.filesDir.resolve("llm_models/$localAsrModel")
                val modelDirPath = modelDir.absolutePath

                // 参考 VoiceModelsChecker: 先检查模型目录和文件是否存在
                val isModelReady = if (localAsrModel.contains("zipformer", ignoreCase = true)) {
                    modelDir.exists() && modelDir.isDirectory &&
                        modelDir.walkTopDown().any { it.name.endsWith(".onnx") } &&
                        File(modelDir, "tokens.txt").exists()
                } else {
                    modelDir.exists() && modelDir.isDirectory
                }

                if (!isModelReady) {
                    Logger.w(TAG, "ASR model not ready: $localAsrModel (dir exists=${modelDir.exists()})")
                    SystemAsrEngine(context)
                } else {
                    // 统一使用 SherpaOnnxAsrEngine（支持所有 ONNX 模型格式）
                    val sherpaOnnxAsr = SherpaOnnxAsrEngine(context, modelDirPath)
                    if (sherpaOnnxAsr.isAvailable()) {
                        Logger.i(TAG, "Using Sherpa-ONNX ASR engine with model: $localAsrModel")
                        sherpaOnnxAsr
                    } else {
                        Logger.w(TAG, "Sherpa-ONNX ASR init failed, falling back to system ASR")
                        SystemAsrEngine(context)
                    }
                }
            } else {
                Logger.d(TAG, "No local ASR model configured, using default ASR (system or downloaded model)")
                createDefaultAsrEngine(context)
            }
        }

        val previousEngine = asrEngine
        asrEngine = engine
        if (previousEngine !== engine) {
            previousEngine.release()
        }
    }
    // 声控开关切换：DISABLED ↔ WAKE_WORD
    val onToggleVoiceControl = remember(
        coroutineScope,
        voiceCommandMode,
        aiAgentChatVisible,
        aiAgentUseCase,
        asrEngine
    ) {
        {
            val nextMode = if (voiceCommandMode == VoiceCommandMode.DISABLED) {
                VoiceCommandMode.WAKE_WORD
            } else {
                VoiceCommandMode.DISABLED
            }
            coroutineScope.launch {
                userPreferencesRepository.updateVoiceCommandMode(nextMode)
                Logger.i(TAG, "Voice control toggled to: $nextMode")
            }

            if (nextMode == VoiceCommandMode.DISABLED && !aiAgentChatVisible) {
                asrEngine.release()
                if (aiAgentUseCase.isLocalModelLoaded) {
                    aiAgentUseCase.unloadLocalModel()
                }
                Logger.i(TAG, "Voice feature closed, release ASR/LLM")
            }
            Unit
        }
    }

    val onCommandRef = remember { mutableStateOf<(AiAgentCommand) -> Unit>({}) }
    val app = context.applicationContext as PoLangApplication
    val kwsEngine = remember { app.container.kwsEngine }
    val voiceCoordinator = remember(asrEngine, aiAgentUseCase, kwsEngine) {
        VoiceCommandCoordinator(
            asrEngine = asrEngine,
            aiAgentUseCase = aiAgentUseCase,
            onCommand = { command ->
                onCommandRef.value(command)
            },
            scope = coroutineScope,
            onTranscript = { transcript ->
                aiAgentMessages = aiAgentMessages + AgentMessage.UserText(content = transcript)
            },
            onAgentResponse = { result ->
                result.onSuccess { command ->
                    val newMessages = aiAgentMessages + commandToExecutionMessages(command)
                    aiAgentMessages = newMessages
                }.onFailure { error ->
                    aiAgentMessages = aiAgentMessages + AgentMessage.AgentText(
                        content = "处理出错了：${error.message ?: "未知错误"}"
                    )
                }
            },
            kwsEngine = kwsEngine,  // 【新增】启用 KWS 低功耗唤醒词检测
            context = context
        )
    }

    // 同步相机状态到语音协调器
    voiceCoordinator.mode = voiceCommandMode

    // 根据模式启停唤醒词监听
    // 同时监听 voiceCoordinator 变化，防止 asrEngine 切换导致 coordinator 重建后 KWS 丢失
    // 语音入口开关关闭时停止监听（语音为非刚需，入口隐藏后不在后台监听）
    LaunchedEffect(voiceCommandMode, voiceCoordinator, isActivePage, voiceEntryEnabled) {
        if (isActivePage && voiceEntryEnabled && voiceCommandMode == VoiceCommandMode.WAKE_WORD) {
            voiceCoordinator.startWakeWordListening()
        } else {
            voiceCoordinator.stopWakeWordListening()
        }
    }

// voiceCoordinator 变化或页面退出时释放语音资源
// 修复 P0-1：不应该完全释放 voiceCoordinator，因为它在多个 Chat 屏幕间共享
// 而应该只进行"软释放"（停止监听但保留引擎）
DisposableEffect(voiceCoordinator) {
onDispose {
Logger.i("PoLang:Camera", "Camera screen disposed - performing soft release of voice coordinator")
voiceCoordinator.stopWakeWordListening()
voiceCoordinator.stopPushToTalk()
// 注意：不调用 voiceCoordinator.release() 以避免破坏 ASR 引擎状态
}
}

    // 应用前后台生命周期监听，联动 MnnResourceManager
    val cameraLifecycleOwner = LocalLifecycleOwner.current
    val resourceManager = remember { MnnResourceManager.getInstance(context) }
    DisposableEffect(cameraLifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> resourceManager.onAppForeground()
                Lifecycle.Event.ON_PAUSE -> resourceManager.onAppBackground()
                else -> {}
            }
        }
        cameraLifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            cameraLifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // 场景感知：进入/离开相机页时通知 ResourceManager 和 SceneManager
    val faceDetectorManager = runtimeContext.faceDetectorManager
    DisposableEffect(Unit) {
        MnnResourceManager.getInstance(context).setScene(MnnResourceManager.Scene.CAMERA)
        SceneManager.getInstance().transitionTo(SceneManager.Scene.CAMERA)
        onDispose {
            MnnResourceManager.getInstance(context).setScene(MnnResourceManager.Scene.OTHER)
            SceneManager.getInstance().leaveScene(SceneManager.Scene.CAMERA)
        }
    }

    // FaceDetection 按需加载：美颜开关打开即加载（满足“开即生效”），关闭即卸载。
    val roiStageConfig = runtimeContext.roiStageConfig
    val landmarkStageConfig = runtimeContext.landmarkStageConfig

    // 从用户实际选择的 landmark stage config 推导有效引擎模式
    // 而非使用 faceDetectionEngineMode 的默认值 MEDIAPIPE
    val effectiveEngineMode = remember(landmarkStageConfig) {
        if (landmarkStageConfig.modelType == DetectionModelType.MEDIAPIPE) {
            EngineType.MEDIAPIPE
        } else {
            when (landmarkStageConfig.engineType) {
                InferenceEngineType.MNN -> EngineType.MNN
                InferenceEngineType.TFLITE -> EngineType.MEDIAPIPE
            }
        }
    }

    val shouldEnableFaceDetection = remember(beautySettings.enabled) {
        beautySettings.enabled
    }
    LaunchedEffect(
        shouldEnableFaceDetection,
        roiStageConfig,
        landmarkStageConfig,
        faceDetectorManager
    ) {
        val manager = faceDetectorManager as? FaceDetectorManager
        if (manager == null) {
            Logger.w(TAG, "FaceDetectorManager unavailable, skip lazy init")
            return@LaunchedEffect
        }

        if (!shouldEnableFaceDetection) {
            if (isFacePipelineInitialized) {
                Logger.i(TAG, "Face detection disabled by feature switch, release pipeline")
                manager.release()
                isFacePipelineInitialized = false
                faceWarpParams = FaceWarpParams(
                    requestedDetectionEngineMode = faceDetectionEngineMode.toEngineType()
                )
                previewFaceWarpParams = faceWarpParams
            }
            return@LaunchedEffect
        }

        val config = DetectionPipelineConfig(
            roiDetector = roiStageConfig.modelType.toRoiDetectorType(),
            landmarkDetector = landmarkStageConfig.modelType.toLandmarkDetectorType(),
            roiEngine = roiStageConfig.engineType.toInferenceBackendType(),
            landmarkEngine = landmarkStageConfig.engineType.toInferenceBackendType(),
            roiDevice = roiStageConfig.devicePreference.toDevicePreference(),
            landmarkDevice = landmarkStageConfig.devicePreference.toDevicePreference()
        )
        manager.updatePipelineConfig(config)
        isFacePipelineInitialized = true
        Logger.d(
            TAG,
            "Face detection pipeline initialized lazily: roi=${roiStageConfig.engineType}, " +
                "landmark=${landmarkStageConfig.engineType}"
        )
    }

    // 移除本地 state,改用设置页配置
    // var showCameraInfo by remember { mutableStateOf(false) }
    // var showLogOverlay by remember { mutableStateOf(false) }
    // var showFaceDebugOverlay by remember { mutableStateOf(false) }

    var currentScene by remember { mutableStateOf(ScenePreset.NONE) }
    var currentGrid by remember { mutableStateOf(GridType.NONE) }

    var cameraControl: CameraControl? by remember { mutableStateOf(null) }
    var zoomRatio by remember { mutableFloatStateOf(1f) }
    var minZoomRatio by remember { mutableFloatStateOf(1f) }
    var maxZoomRatio by remember { mutableFloatStateOf(1f) }

    val cameraMemoryState by userPreferencesRepository.cameraMemoryStateFlow.collectAsState(initial = null)
    var isCameraMemoryHydrated by remember { mutableStateOf(false) }

    LaunchedEffect(cameraMemoryState) {
        val memoryState = cameraMemoryState ?: return@LaunchedEffect

        lensFacing = if (memoryState.useFrontCamera) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        captureMode = memoryState.captureMode
        selectedFilter = memoryState.selectedFilter
        beautySettings = resolveNextBeautySettings(
            currentSettings = beautySettings,
            updatedSettings = memoryState.beautySettings.copy(
                colorFilter = memoryState.selectedFilter,
                styleFilter = memoryState.selectedStyleFilter
            )
        )
        aspectRatio = memoryState.aspectRatio.toAspectRatio()
        zoomRatio = memoryState.zoomRatio
        exposureCompensation = memoryState.exposureCompensation
        whiteBalanceMode = memoryState.whiteBalanceMode
        currentScene = memoryState.sceneMode.toScenePreset()
        currentGrid = memoryState.gridMode.toGridType()

        isCameraMemoryHydrated = true
    }

    LaunchedEffect(
        isCameraMemoryHydrated,
        lensFacing,
        captureMode,
        selectedFilter,
        beautySettings,
        aspectRatio,
        zoomRatio,
        exposureCompensation,
        whiteBalanceMode,
        currentScene,
        currentGrid
    ) {
        if (!isCameraMemoryHydrated) {
            return@LaunchedEffect
        }

        userPreferencesRepository.updateCameraMemoryState(
            CameraMemoryState(
                useFrontCamera = lensFacing == CameraSelector.LENS_FACING_FRONT,
                captureMode = captureMode,
                selectedFilter = selectedFilter,
                selectedStyleFilter = beautySettings.styleFilter,
                beautySettings = beautySettings.copy(
                    colorFilter = selectedFilter,
                    styleFilter = beautySettings.styleFilter
                ),
                aspectRatio = aspectRatio.toCameraAspectRatioMode(),
                zoomRatio = zoomRatio,
                exposureCompensation = exposureCompensation,
                whiteBalanceMode = whiteBalanceMode,
                sceneMode = currentScene.toCameraSceneMode(),
                gridMode = currentGrid.toCameraGridMode()
            )
        )
    }

    var isStable by remember { mutableStateOf(true) }
    val sensorManager = remember {
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }
    val accelerometer = remember { sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) }

    DisposableEffect(Unit) {
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                event?.let {
                    val x = it.values[0]
                    val y = it.values[1]
                    val z = it.values[2]
                    val g = sqrt(x * x + y * y + z * z)
                    val diff = abs(g - SensorManager.GRAVITY_EARTH)
                    isStable = diff < 0.5f
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sensorManager.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_UI)
        onDispose {
            sensorManager.unregisterListener(listener)
        }
    }

    // RD ImageCapture需要在LaunchedEffect中创建，以便1:1模式可以正确配置ViewPort
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    val recorder = remember {
        Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HIGHEST)).build()
    }
    val videoCapture = remember { VideoCapture.withOutput(recorder) }
    var recording: Recording? by remember { mutableStateOf(null) }

    val beautyVideoRecorder = remember { BeautyVideoRecorder() }

    // 页面级 CameraCapability：随页面进入注册到全局 CapabilityRegistry、退出注销
    // （2026-07-29 单轨收敛，Compose CapabilityHost 已退役；实例仍为页面级，持有相机状态）
    val cameraCapability = remember { CameraCapability() }
    DisposableEffect(cameraCapability) {
        val orchestrator = AgentOrchestrator.getInstance(context.applicationContext)
        orchestrator.registerCapability(cameraCapability)
        onDispose { orchestrator.unregisterCapability(cameraCapability) }
    }

    // 初始化 Agent 命令处理器
    val agentCommandHandler = remember(
        context, viewModel, imageProcessor, beautyVideoRecorder,
        glPreviewProvider, videoCapture, cameraStateManager, coroutineScope,
        onNavigateToGallery
    ) {
        CameraAgentCommandHandler(
            context = context,
            viewModel = viewModel,
            imageProcessor = imageProcessor,
            beautyVideoRecorder = beautyVideoRecorder,
            glPreviewProvider = glPreviewProvider,
            videoCapture = videoCapture,
            cameraStateManager = cameraStateManager,
            coroutineScope = coroutineScope,
            onNavigateToGallery = onNavigateToGallery
        )
    }

    // 统一命令入口：处理 BatchExecute、Delay、普通命令，UI 和语音两路共用
    val handleCommand: (AiAgentCommand) -> Unit = remember(agentCommandHandler, coroutineScope, cameraStateManager) {
        { command ->
            when {
                command is AiAgentCommand.BatchExecute -> {
                    Logger.i(TAG, "BatchExecute: ${command.commands.size} commands, launching sequentially")
                    coroutineScope.launch {
                        command.commands.forEachIndexed { index, subCmd ->
                            Logger.i(TAG, "BatchExecute [$index/${command.commands.size}]: ${subCmd.javaClass.simpleName}")
                            if (subCmd is AiAgentCommand.Delay) {
                                Logger.i(TAG, "BatchExecute: delaying ${subCmd.delayMs}ms")
                                delay(subCmd.delayMs)
                            } else {
                                agentCommandHandler.handleCommand(subCmd)
                            }
                            if (subCmd is AiAgentCommand.CapturePhoto && index < command.commands.size - 1) {
                                Logger.i(TAG, "BatchExecute: waiting for capture to complete...")
                                var waitCount = 0
                                while (cameraStateManager.isBusy() && waitCount < 50) {
                                    delay(100)
                                    waitCount++
                                }
                                Logger.i(TAG, "BatchExecute: capture completed, state=${cameraStateManager.getState().name}")
                            } else if (index < command.commands.size - 1 && subCmd !is AiAgentCommand.Delay) {
                                delay(200)
                            }
                        }
                        Logger.i(TAG, "BatchExecute: all commands completed")
                    }
                }
                command is AiAgentCommand.Delay -> {
                    coroutineScope.launch {
                        Logger.i(TAG, "Delay: ${command.delayMs}ms")
                        delay(command.delayMs)
                    }
                }
                else -> agentCommandHandler.handleCommand(command)
            }
        }
    }
    onCommandRef.value = handleCommand

    // 同步可变状态到 Handler
    agentCommandHandler.lensFacing = lensFacing
    agentCommandHandler.captureMode = captureMode
    agentCommandHandler.isRecording = isRecording
    agentCommandHandler.recording = recording
    agentCommandHandler.imageCapture = imageCapture
    agentCommandHandler.selectedFilter = selectedFilter
    agentCommandHandler.beautySettings = beautySettings
    agentCommandHandler.beautyStrategy = beautyStrategy
    agentCommandHandler.exposureCompensation = exposureCompensation
    agentCommandHandler.zoomRatio = zoomRatio
    agentCommandHandler.minZoomRatio = minZoomRatio
    agentCommandHandler.maxZoomRatio = maxZoomRatio
    agentCommandHandler.cameraControl = cameraControl
    agentCommandHandler.currentScene = currentScene
    agentCommandHandler.aspectRatio = aspectRatio

    // 绑定回调
    agentCommandHandler.onLensFacingChanged = { lensFacing = it }
    agentCommandHandler.onCaptureModeChanged = { captureMode = it }
    agentCommandHandler.onIsRecordingChanged = { isRecording = it }
    agentCommandHandler.onRecordingChanged = { recording = it }
    agentCommandHandler.onSelectedFilterChanged = { selectedFilter = it }
    agentCommandHandler.onBeautySettingsChanged = { beautySettings = it }
    agentCommandHandler.onExposureCompensationChanged = { exposureCompensation = it }
    agentCommandHandler.onZoomRatioChanged = { zoomRatio = it }
    agentCommandHandler.onAspectRatioChanged = { aspectRatio = it }
    agentCommandHandler.onCurrentSceneChanged = { currentScene = it }

    // 绑定 CameraCapability 状态变更到本地状态
    DisposableEffect(cameraCapability) {
        cameraCapability.setOnStateChangedListener { change ->
            when (change) {
                is CameraCapability.StateChange.AspectRatioChanged -> {
                    aspectRatio = change.ratio
                }
                is CameraCapability.StateChange.BeautySettingsChanged -> {
                    beautySettings = change.settings
                }
                is CameraCapability.StateChange.FilterChanged -> {
                    selectedFilter = change.filter
                    beautySettings = beautySettings.copy(colorFilter = change.filter)
                }
                is CameraCapability.StateChange.StyleChanged -> {
                    beautySettings = beautySettings.copy(styleFilter = change.style)
                }
                is CameraCapability.StateChange.SceneChanged -> {
                    currentScene = when (change.scene) {
                        CameraCapability.SceneMode.NIGHT -> ScenePreset.NIGHT
                        CameraCapability.SceneMode.MOON -> ScenePreset.MOON
                        else -> ScenePreset.NONE
                    }
                }
                is CameraCapability.StateChange.ExposureChanged -> {
                    exposureCompensation = change.exposure
                    cameraControl?.setExposureCompensationIndex(change.exposure)
                }
                is CameraCapability.StateChange.ZoomChanged -> {
                    cameraControl?.setZoomRatio(change.zoom)
                }
                is CameraCapability.StateChange.LensFacingChanged -> {
                    lensFacing = change.facing
                }
                is CameraCapability.StateChange.CaptureModeChanged -> {
                    captureMode = change.mode
                }
                is CameraCapability.StateChange.CaptureRequested -> {
                    agentCommandHandler.handleCommand(AiAgentCommand.CapturePhoto)
                }
                is CameraCapability.StateChange.RecordingToggled -> {
                    agentCommandHandler.handleCommand(AiAgentCommand.ToggleRecording)
                }
            }
        }
        onDispose {
            cameraCapability.setOnStateChangedListener(null)
        }
    }

    // 绑定命令处理器到语音协调器的回调引用
    DisposableEffect(Unit) {
        onCommandRef.value = agentCommandHandler::handleCommand
        onDispose { }
    }

    var facePoint by remember { mutableStateOf(Offset.Zero) }
    var isFaceLocked by remember { mutableStateOf(false) }
    var lastAutoFocusAtMs by remember { mutableStateOf(0L) }
    var lastFocusPoint by remember { mutableStateOf<Offset?>(null) }
    val focusIndicatorAlpha = remember { Animatable(0f) }

    val mediaAssets by viewModel.allMedia.collectAsState()
    val lastMedia = mediaAssets.firstOrNull()

    // Shader debug mode: 强制为 0（正常渲染），不从 DataStore 读取，避免调试模式导致蓝屏
    val debugShaderMode = 0

    val beautyPreviewStatus = if (beautySettings.enabled && beautySettings.hasAnyEffect()) {
        BeautyPreviewStatus.ACTIVE
    } else {
        BeautyPreviewStatus.SKIPPED
    }
    var renderPerfStats by remember {
        mutableStateOf(BeautyPerfStats())
    }

    // 监听 Shader Debug Mode 变化，同步到 GL Provider
    LaunchedEffect(debugShaderMode, glPreviewProvider) {
        val provider = glPreviewProvider as? GlBeautyPreviewProvider
        provider?.setDebugMode(debugShaderMode)
        Logger.d("Camera", "Shader debug mode updated: $debugShaderMode")
    }

    LaunchedEffect(beautyStrategy, useProviderRenderView, previewRebindSignal) {
        while (isActive) {
            renderPerfStats = if ((beautyStrategy == BeautyStrategy.BIG_BEAUTY || beautyStrategy == BeautyStrategy.BIG_BEAUTY) && useProviderRenderView) {
                glPreviewProvider?.getPerfStats() ?: BeautyPerfStats()
            } else {
                BeautyPerfStats()
            }
            delay(250)
        }
    }

    LaunchedEffect(currentScene) {
        cameraControl?.let { control ->
            Logger.i("Camera", "Applying scene: $currentScene")
            when (currentScene) {
                ScenePreset.NIGHT -> {
                    control.setExposureCompensationIndex(1)
                }
                ScenePreset.MOON -> {
                    control.setExposureCompensationIndex(-2)
                    control.setZoomRatio(3.2f)
                }
                ScenePreset.NONE -> {
                    control.setExposureCompensationIndex(0)
                }
            }
        }
    }



    // faceDetectionEngineMode 变化时仅同步 debug 展示字段。
    LaunchedEffect(faceDetectionEngineMode) {
        val engineType = faceDetectionEngineMode.toEngineType()
        faceWarpParams = faceWarpParams.copy(requestedDetectionEngineMode = engineType)
        previewFaceWarpParams = previewFaceWarpParams.copy(requestedDetectionEngineMode = engineType)
    }

    LaunchedEffect(lensFacing) {
        glPreviewProvider?.setIsFrontCamera(lensFacing == CameraSelector.LENS_FACING_FRONT)
        Logger.d("Camera", "Switched to lensFacing=$lensFacing, isFront=${lensFacing == androidx.camera.core.CameraSelector.LENS_FACING_FRONT}")
    }

    LaunchedEffect(
        lensFacing,
        captureMode,
        aspectRatio,
        beautyStrategy,
        effectiveEngineMode,
        adaptiveFaceDetectionIntervalEnabled,
        faceDetectIntervalProfile,
        previewRebindSignal,
        isActivePage
    ) {
        if (!isActivePage) {
            // 非活跃页（Pager 常驻组合）：解绑相机，释放摄像头资源
            runCatching { cameraProviderFuture.get().unbindAll() }
            Logger.d("Camera", "Camera page inactive, unbind all use cases")
            return@LaunchedEffect
        }
        Logger.d("Camera", "Rebinding camera use cases for face engine mode=${effectiveEngineMode.name}")
        bindCameraUseCases(
            context = context,
            lifecycleOwner = lifecycleOwner,
            cameraProviderFuture = cameraProviderFuture,
            lensFacing = lensFacing,
            captureMode = captureMode,
            aspectRatio = aspectRatio,
            previewView = previewView,
            bindPreviewSurfaceProvider = bindPreviewSurfaceProvider,
            cameraExecutor = analysisExecutor,
            isBeautyEnabled = { shouldEnableFaceDetection },
            beautyStrategy = beautyStrategy,
            detectionEngineMode = effectiveEngineMode,
            adaptiveFaceDetectionIntervalEnabled = adaptiveFaceDetectionIntervalEnabled,
            faceDetectIntervalProfile = faceDetectIntervalProfile,
            videoCapture = videoCapture,
            faceDetector = runtimeContext.faceDetectorManager,
            onImageCaptureChanged = { capture -> imageCapture = capture },
            onCameraControlChanged = { control ->
                cameraControl = control
                // [Day2 状态机] 相机绑定成功，进入 Previewing 状态
                if (cameraStateManager.getState() is CameraStateMachine.Idle ||
                    cameraStateManager.getState() is CameraStateMachine.Rebinding) {
                    cameraStateManager.transition(
                        CameraStateMachine.Previewing(lensFacing, captureMode.ordinal)
                    )
                }
            },
            onZoomRatioChanged = { ratio -> zoomRatio = ratio },
            onZoomRangeChanged = { minZoom, maxZoom ->
                minZoomRatio = minZoom
                maxZoomRatio = maxZoom
            },
            onActualLensFacingChanged = { ignoredLensFacing ->
                Logger.d("Camera", "Actual lens changed: $ignoredLensFacing")
            },
            onFacePointChanged = { point -> facePoint = point },
            onFaceWarpParamsChanged = { params ->
                faceWarpParams = params
            },
            onShowFocusIndicatorChanged = { show ->
                isFaceLocked = show
            }
        )
    }

    // 第二阶段瘦身后：实时预览在 runRealtimeBeautyPreviewLoop 中统一处理。

    LaunchedEffect(cameraControl, minZoomRatio, maxZoomRatio, isCameraMemoryHydrated) {
        val control = cameraControl ?: return@LaunchedEffect
        if (!isCameraMemoryHydrated) {
            return@LaunchedEffect
        }

        val clampedZoom = zoomRatio.coerceIn(minZoomRatio, maxZoomRatio)
        if (clampedZoom != zoomRatio) {
            zoomRatio = clampedZoom
        }
        runCatching {
            control.setZoomRatio(clampedZoom)
            control.setExposureCompensationIndex(exposureCompensation.coerceIn(-2, 2))
        }.onFailure { error ->
            Logger.w("Camera", "Failed to apply persisted camera control values", error)
        }
    }

    LaunchedEffect(cameraControl, previewView.width, previewView.height, lensFacing) {
        val control = cameraControl ?: return@LaunchedEffect
        if (previewView.width <= 0 || previewView.height <= 0) {
            return@LaunchedEffect
        }

        delay(320)
        val centerPoint = previewView.meteringPointFactory.createPoint(
            previewView.width * 0.5f,
            previewView.height * 0.5f
        )
        val centerAction = FocusMeteringAction.Builder(centerPoint, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(2, TimeUnit.SECONDS)
            .build()

        runCatching {
            control.startFocusAndMetering(centerAction)
        }.onSuccess {
            lastAutoFocusAtMs = System.currentTimeMillis()
            lastFocusPoint = Offset(previewView.width * 0.5f, previewView.height * 0.5f)
            Logger.d("Camera", "Initial autofocus triggered at preview center")
        }.onFailure { error ->
            Logger.w("Camera", "Initial autofocus request failed", error)
        }
    }

    LaunchedEffect(isFaceLocked, facePoint, cameraControl, previewView.width, previewView.height) {
        val control = cameraControl ?: return@LaunchedEffect
        if (!isFaceLocked || previewView.width <= 0 || previewView.height <= 0) {
            return@LaunchedEffect
        }

        val nowMs = System.currentTimeMillis()
        val minIntervalMs = 1100L
        if (nowMs - lastAutoFocusAtMs < minIntervalMs) {
            return@LaunchedEffect
        }

        val clampedX = facePoint.x.coerceIn(0f, previewView.width.toFloat())
        val clampedY = facePoint.y.coerceIn(0f, previewView.height.toFloat())
        val nextPoint = Offset(clampedX, clampedY)
        val previousPoint = lastFocusPoint
        val distance = if (previousPoint == null) {
            Float.MAX_VALUE
        } else {
            kotlin.math.hypot(nextPoint.x - previousPoint.x, nextPoint.y - previousPoint.y)
        }
        if (distance < 42f) {
            return@LaunchedEffect
        }

        val meteringPoint = previewView.meteringPointFactory.createPoint(clampedX, clampedY)
        val focusAction = FocusMeteringAction.Builder(meteringPoint, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(2, TimeUnit.SECONDS)
            .build()

        runCatching {
            control.startFocusAndMetering(focusAction)
        }.onSuccess {
            lastAutoFocusAtMs = nowMs
            lastFocusPoint = nextPoint
            Logger.d("Camera", "Autofocus triggered: x=$clampedX, y=$clampedY, distance=$distance")
        }.onFailure { error ->
            Logger.w("Camera", "Autofocus request failed", error)
        }
    }

    LaunchedEffect(isFaceLocked, isStable) {
        if (!isFaceLocked) {
            focusIndicatorAlpha.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 220)
            )
            return@LaunchedEffect
        }

        if (!isStable) {
            focusIndicatorAlpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 160)
            )
            return@LaunchedEffect
        }

        delay(320)
        focusIndicatorAlpha.animateTo(
            targetValue = 0f,
            animationSpec = tween(durationMillis = 420)
        )
    }

    DisposableEffect(activePreviewStrategy) {
        onDispose {
            activePreviewStrategy.release()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // [Day1 线程隔离] 释放专用线程
            CameraThreadRegistry.release()
            // 释放相机分析路径复用缓存，避免退出后常驻内存拖高 RSS
            ImageUtils.release()
            // 清空最近一帧人脸缓存，避免页面退出后保留数组引用
            FaceDetectionCache.clear()
        }
    }

CameraPreviewContent(
    previewView = {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                factory = { context ->
                    Log.d(TAG, "AndroidView factory creating FrameLayout")
                    FrameLayout(context)
                },
                modifier = Modifier.fillMaxSize(),
                update = { container ->
                    val runtimeProviderView = runCatching {
                        glPreviewProvider?.getView() ?: run {
                            glPreviewProvider?.initialize()
                            glPreviewProvider?.getView()
                        }
                    }.getOrNull()

                    val targetDecision = resolvePreviewTargetView(
                        useProviderRenderView = useProviderRenderView,
                        activeStrategy = activePreviewStrategy.strategy,
                        runtimeProviderView = runtimeProviderView,
                        previewView = previewView
                    )

                    // [Perf] Compose recomposition logs removed; kept only error paths

                    if (targetDecision.scheduleProviderFallback) {
                        Logger.w("Camera", "Provider render view missing, fallback to PreviewView and request rebind")
                        container.post {
                            if (useProviderRenderView) {
                                useProviderRenderView = false
                                previewRebindSignal += 1
                            }
                        }
                    }

                    val targetView = targetDecision.targetView

                    if (targetView.parent !== container) {
                        (targetView.parent as? ViewGroup)?.removeView(targetView)
                        container.removeAllViews()
                        container.addView(
                            targetView,
                            FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT
                            )
                        )
                    } else {
                        // view already attached, skip
                    }
                }
            )

            // [三位一体反馈] 黑场闪屏覆盖层
            if (shutterFlashAlpha.value > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = shutterFlashAlpha.value))
                )
            }
        }
    },
    uiState = buildCameraPreviewUiState(
        selectedFilter = selectedFilter,
        selectedStyleFilter = beautySettings.styleFilter,
        faceDetectionEngineMode = faceDetectionEngineMode,
        facePoint = facePoint,
        faceWarpParams = faceWarpParams,
        showFaceDebugOverlay = showFaceDebugOverlay,
        focusIndicatorAlpha = focusIndicatorAlpha.value,
        lastMedia = lastMedia,
        zoomRatio = zoomRatio,
        minZoomRatio = minZoomRatio,
        maxZoomRatio = maxZoomRatio,
        captureMode = captureMode,
        isRecording = isRecording,
        isStable = isStable,
        panelState = panelState,
        showCameraInfo = showCameraInfoInPreview,
        debugUiEnabled = debugUiEnabled,
        currentScene = currentScene,
        currentGrid = currentGrid,
        beautySettings = beautySettings,
        beautyDebugState = BeautyDebugState(
            status = beautyPreviewStatus,
            fps = renderPerfStats.fps,
            processingMs = renderPerfStats.processingMs,
            delayMs = renderPerfStats.delayMs,
            cpuUsage = renderPerfStats.cpuUsage,
            nullFrames = renderPerfStats.nullFrames,
            rendererErrorCategory = renderPerfStats.errorCategory,
            rendererErrorReason = renderPerfStats.errorReason,
            persistedFallback = persistedGlFallback,
            persistedFallbackReason = persistedGlFallbackReason,
            strategy = beautyStrategy,
            recoveryAvailableAtMs = glRecoveryAvailableAtMs,
            providerRenderActive = useProviderRenderView
        ),
        aspectRatio = aspectRatio,
        lensFacing = lensFacing,
        exposureCompensation = exposureCompensation,
        exposureRange = -2..2,
        whiteBalanceMode = whiteBalanceMode,
        beautyStrategy = beautyStrategy,
        isVoiceControlEnabled = voiceCommandMode != VoiceCommandMode.DISABLED,
        voiceEntryEnabled = voiceEntryEnabled,
        roiStageConfig = runtimeContext.roiStageConfig,
        landmarkStageConfig = runtimeContext.landmarkStageConfig,
        showLogOverlay = showLogOverlay
    ),
    aiAgentUseCase = aiAgentUseCase,
    aiAgentChatVisible = aiAgentChatVisible,
    aiAgentMessages = aiAgentMessages,
    aiAgentIsProcessing = aiAgentIsProcessing,
    onAiAgentChatVisibleChange = { aiAgentChatVisible = it },
    onAiAgentMessagesChange = { aiAgentMessages = it },
    onAiAgentIsProcessingChange = { aiAgentIsProcessing = it },
    voiceCoordinator = voiceCoordinator,
    isModelLoading = aiAgentModelLoading,
    isWakeWordActive = voiceCommandMode == VoiceCommandMode.WAKE_WORD,
    onAiAgentCommand = { command ->
        Logger.i(TAG, "onAiAgentCommand received: ${command.javaClass.simpleName}")
        onCommandRef.value(command)
    },
    onUpdateVoiceCoordinatorState = {
        voiceCoordinator.currentCameraState = VoiceCommandCoordinator.CameraStateSnapshot(
            beautySettings = beautySettings,
            filterType = selectedFilter,
            styleFilter = beautySettings.styleFilter,
            zoomRatio = zoomRatio,
            exposureCompensation = exposureCompensation,
            captureMode = captureMode,
            isRecording = isRecording
        )
    },
            actions = run {
                val currentView = LocalView.current
                buildCameraPreviewActions(
                onNavigateBack = onNavigateBack,
                onResetCameraMemoryState = {
                    val defaultState = CameraMemoryState()
                    lensFacing = if (defaultState.useFrontCamera) {
                        CameraSelector.LENS_FACING_FRONT
                    } else {
                        CameraSelector.LENS_FACING_BACK
                    }
                    captureMode = defaultState.captureMode
                    selectedFilter = defaultState.selectedFilter
                    beautySettings = resolveNextBeautySettings(
                        currentSettings = beautySettings,
                        updatedSettings = defaultState.beautySettings.copy(
                            colorFilter = defaultState.selectedFilter,
                            styleFilter = defaultState.selectedStyleFilter
                        )
                    )
                    aspectRatio = defaultState.aspectRatio.toAspectRatio()
                    zoomRatio = defaultState.zoomRatio
                    exposureCompensation = defaultState.exposureCompensation
                    whiteBalanceMode = defaultState.whiteBalanceMode
                    currentScene = defaultState.sceneMode.toScenePreset()
                    currentGrid = defaultState.gridMode.toGridType()
                    panelState.closeAllPanels()
                    isCameraMemoryHydrated = true

                    coroutineScope.launch {
                        userPreferencesRepository.resetCameraMemoryState()
                    }
                },
                lensFacing = lensFacing,

        onLensFacingChanged = { updatedLensFacing -> lensFacing = updatedLensFacing },
        onActualLensFacingChanged = { updatedLensFacing ->
            Logger.d("Camera", "Action lens sync: $updatedLensFacing")
        },
        panelState = panelState,
        cameraControl = cameraControl,
        onCurrentSceneChanged = { scene -> currentScene = scene },
        onCurrentGridChanged = { grid -> currentGrid = grid },
        onNavigateToGallery = onNavigateToGallery,
        onCaptureClick = {
            // [状态机保护] 仅在 Previewing 状态允许拍照
            if (!cameraStateManager.canCapture()) {
                Logger.w(TAG, "Capture click rejected: state=${cameraStateManager.getState().name}")
                return@buildCameraPreviewActions
            }

            // [三位一体反馈] 立即触发：触感 + 音效 + 黑场动画
            currentView.performHapticFeedback(
                HapticFeedbackConstants.LONG_PRESS,
                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
            )
            currentView.playSoundEffect(SoundEffectConstants.CLICK)
            coroutineScope.launch {
                shutterFlashAlpha.snapTo(0.6f)
                shutterFlashAlpha.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = 80)
                )
            }

            handleCaptureClick(
                context = context,
                captureMode = captureMode,
                isRecording = isRecording,
                recording = recording,
                videoCapture = videoCapture,
                viewModel = viewModel,
                imageCapture = imageCapture,
                imageProcessor = imageProcessor,
                selectedFilter = selectedFilter,
                beautySettings = beautySettings,
                lensFacing = lensFacing,
                cachedFaces = emptyList(),
                beautyStrategy = beautyStrategy,
                glPreviewProvider = glPreviewProvider,
                beautyVideoRecorder = beautyVideoRecorder,
                onRecordingChanged = { updated -> recording = updated },
                onIsRecordingChanged = { recordingFlag -> isRecording = recordingFlag },
                coroutineScope = coroutineScope,
                cameraStateManager = cameraStateManager
            )
        },
        onCaptureModeChanged = { mode -> captureMode = mode },
        onSelectedFilterChanged = { filter ->
            selectedFilter = filter
            // 同步到 beautySettings，确保预览引擎能收到色调矩阵变化
            beautySettings = beautySettings.copy(colorFilter = filter)
        },
        onStyleFilterSelected = { style ->
            beautySettings = beautySettings.copy(styleFilter = style)
        },
        onBeautySettingsChanged = { updatedSettings ->
            beautySettings = resolveNextBeautySettings(
                currentSettings = beautySettings,
                updatedSettings = updatedSettings
            )
        },
        onAspectRatioChanged = { ratio -> aspectRatio = ratio },
        onExposureCompensationChanged = { exposure -> exposureCompensation = exposure },
        onWhiteBalanceModeChanged = { wb -> whiteBalanceMode = wb },
        onToggleVoiceControl = onToggleVoiceControl,
        onToggleAiAgentPanel = {
            aiAgentChatVisible = !aiAgentChatVisible
            if (!aiAgentChatVisible) {
                voiceCoordinator.stopWakeWordListening()
                if (voiceCommandMode == VoiceCommandMode.DISABLED) {
                    asrEngine.release()
                    if (aiAgentUseCase.isLocalModelLoaded) {
                        aiAgentUseCase.unloadLocalModel()
                    }
                    Logger.i(TAG, "AI panel closed, release ASR/LLM")
                }
            }
        },
        onToggleLogs = {
            coroutineScope.launch {
                userPreferencesRepository.updateShowLogOverlay(!showLogOverlay)
            }
        },

        // LLM 全量释放
        onLlmRelease = {
            val before = captureMnnMemoryStats(context)
            Logger.i(TAG, "🧠 [LLM释放] 开始")
            Logger.i(TAG, "📊 [释放前内存] Native ${before?.nativeHeapMB}MB | Java ${before?.javaHeapUsedMB}MB")
            aiAgentUseCase.unloadLocalModel()
            coroutineScope.launch {
                val after = sampleMemoryAfterRelease(context)
                showReleaseMemoryToast(context, "LLM", "full", before, after)
            }
        },

        // Face Detection 全量释放
        onFaceDetectRelease = {
            val before = captureMnnMemoryStats(context)
            Logger.i(TAG, "👤 [FaceDetect释放] 开始")
            Logger.i(TAG, "📊 [释放前内存] Native ${before?.nativeHeapMB}MB | Java ${before?.javaHeapUsedMB}MB")
            (faceDetectorManager as? FaceDetectorManager)?.release()
            coroutineScope.launch {
                val after = sampleMemoryAfterRelease(context)
                showReleaseMemoryToast(context, "FaceDetection", "full", before, after)
            }
        }
    )
    }
)

}
