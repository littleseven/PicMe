package com.mamba.picme.features.camera

import android.graphics.RectF
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import androidx.camera.view.PreviewView
import androidx.compose.ui.geometry.Offset
import com.mamba.picme.beauty.api.FrameId
import com.mamba.picme.beauty.api.facedetect.EngineType
import com.mamba.picme.beauty.api.facedetect.FaceDetectionResult
import com.mamba.picme.beauty.api.facedetect.FaceDetector
import com.mamba.picme.beauty.api.facedetect.FaceWarpParams
import com.mamba.picme.beauty.internal.facedetect.Face106ToWarpParams
import com.mamba.picme.beauty.internal.facedetect.FaceDetectorManager
import com.mamba.picme.beauty.internal.framesync.FrameSyncBridge
import com.mamba.picme.beauty.internal.framesync.FrameSyncManager
import com.mamba.picme.core.common.Logger
import com.mamba.picme.domain.model.FaceDetectIntervalProfile
import com.mamba.picme.features.camera.facedetect.ImageUtils
import java.nio.ByteBuffer

/**
 * InsightFace 性能优化: 智能帧跳过管理器
 * 平衡性能与响应速度,避免人脸跟随延迟
 */
private object FaceDetectionFrameCounter {
    // [默认均衡档] 用户未配置时默认使用 BALANCED。
    // detectionIntervalFrames=1: 每帧都触发检测，由 isDetecting 自然限流
    // minDetectionIntervalMs=0: 不设冷却，检测耗时(~33ms)自然限制帧率
    private const val BALANCED_DETECTION_INTERVAL = 1
    private const val BALANCED_MIN_INTERVAL_MS = 0L
    private const val BALANCED_MOTION_THRESHOLD = 0.05f
    private const val BALANCED_MAX_CONSECUTIVE_MOTION = 2

    // [硬策略] ROI keyframe 间隔：静止低频ROI，运动中中频ROI
    private const val HARD_ROI_KEYFRAME_STABLE_INTERVAL = 8
    private const val HARD_ROI_KEYFRAME_MOTION_INTERVAL = 3
    private const val HARD_ROI_MOTION_THRESHOLD = 0.035f

    private var counter = 0
    private var detectionIntervalFrames = BALANCED_DETECTION_INTERVAL
    private var minDetectionIntervalMs = BALANCED_MIN_INTERVAL_MS
    private var motionThreshold = BALANCED_MOTION_THRESHOLD
    private var maxConsecutiveMotion = BALANCED_MAX_CONSECUTIVE_MOTION

    private var adaptiveEnabled = true
    private var activeProfile = FaceDetectIntervalProfile.BALANCED

    // [过热修复] 时间冷却：最小检测间隔，防止检测线程持续满载导致发热
    private var lastDetectTimeMs: Long = 0L

    // [过热修复] 检测进行中标志，防止 Handler 队列积压导致重复检测排队
    @Volatile
    private var isDetecting = false

    // 运动检测: 记录上次人脸中心点
    private var lastFaceCenterX: Float = -1f
    private var lastFaceCenterY: Float = -1f

    // [过热修复] 连续运动检测计数器，防止快移场景下每帧都触发检测
    private var consecutiveMotionDetects = 0
    private var lastDetectionTriggeredByMotion = false

    // [硬策略] ROI keyframe 计数
    private var hardFrameCounter = 0
    private var lastHardRoiKeyframeCounter = 0

    // [常量定义] 人脸关键点索引（106 点模型）
    internal const val LANDMARK_106_INDEX_X = 48
    internal const val LANDMARK_106_INDEX_Y = 97

    fun applyPolicy(enabled: Boolean, profile: FaceDetectIntervalProfile) {
        val targetProfile = if (enabled) profile else FaceDetectIntervalProfile.BALANCED
        if (adaptiveEnabled == enabled && activeProfile == targetProfile) {
            return
        }

        adaptiveEnabled = enabled
        activeProfile = targetProfile

        when (targetProfile) {
            FaceDetectIntervalProfile.CONSERVATIVE -> {
                detectionIntervalFrames = 2
                minDetectionIntervalMs = 33L
                motionThreshold = 0.09f
                maxConsecutiveMotion = 1
            }
            FaceDetectIntervalProfile.BALANCED -> {
                detectionIntervalFrames = BALANCED_DETECTION_INTERVAL
                minDetectionIntervalMs = BALANCED_MIN_INTERVAL_MS
                motionThreshold = BALANCED_MOTION_THRESHOLD
                maxConsecutiveMotion = BALANCED_MAX_CONSECUTIVE_MOTION
            }
            FaceDetectIntervalProfile.AGGRESSIVE -> {
                detectionIntervalFrames = 1
                minDetectionIntervalMs = 0L
                motionThreshold = 0.035f
                maxConsecutiveMotion = 5
            }
        }

        Logger.i(
            "Camera",
            "Face detection policy applied: adaptive=$enabled, profile=$targetProfile, " +
                "interval=$detectionIntervalFrames, minIntervalMs=$minDetectionIntervalMs, " +
                "motionThreshold=$motionThreshold, maxMotion=$maxConsecutiveMotion"
        )
    }

    /**
     * 判断是否应该执行人脸检测
     * @param currentFaceCenter 当前人脸中心(归一化坐标),如果为 null 表示未检测到人脸
     * @return true=需要检测, false=可以跳过
     */
    fun shouldDetect(currentFaceCenter: Offset? = null): Boolean {
        counter++

        // [过热修复-1] 时间冷却：距离上次检测不足 minDetectionIntervalMs 时直接跳过
        val now = SystemClock.elapsedRealtime()
        if (now - lastDetectTimeMs < minDetectionIntervalMs) {
            return false
        }

        // [过热修复-2] 检测进行中时跳过，防止 Handler 队列积压导致背靠背检测
        if (isDetecting) {
            Logger.dThrottled("Camera", "skip_detecting", "[Perf] Skip frame: detection in progress")
            return false
        }

        // 规则1: 每隔 N 帧必须检测一次
        if (counter % detectionIntervalFrames == 0) {
            lastDetectionTriggeredByMotion = false
            return true
        }

        // 规则2: 如果从未检测到人脸,需要检测（但受时间冷却约束）
        if (currentFaceCenter == null || lastFaceCenterX < 0) {
            lastDetectionTriggeredByMotion = false
            return true
        }

        // 规则3: 检测快速移动,但限制连续运动检测次数防止过热
        val deltaX = kotlin.math.abs(currentFaceCenter.x - lastFaceCenterX)
        val deltaY = kotlin.math.abs(currentFaceCenter.y - lastFaceCenterY)
        if (deltaX > motionThreshold || deltaY > motionThreshold) {
            if (consecutiveMotionDetects < maxConsecutiveMotion) {
                Logger.d("Camera", "[Perf] Fast motion detected (dx=$deltaX, dy=$deltaY), force re-detect")
                lastDetectionTriggeredByMotion = true
                return true
            }
            // 超过连续运动检测上限，退回间隔模式冷却
            lastDetectionTriggeredByMotion = false
            return false
        }

        // 运动停止时重置连续运动计数
        consecutiveMotionDetects = 0
        lastDetectionTriggeredByMotion = false

        return false
    }

    /**
     * ROI 复用硬策略：true=执行 FULL(ROI+Landmark)，false=执行 LANDMARK_ONLY（复用 ROI）
     */
    fun shouldForceRoiKeyframeHard(
        currentFaceCenter: Offset?,
        canReuseRoi: Boolean,
        hasExistingFace: Boolean
    ): Boolean {
        hardFrameCounter++

        if (!hasExistingFace || !canReuseRoi) {
            return true
        }

        if (currentFaceCenter == null || lastFaceCenterX < 0f) {
            return true
        }

        val deltaX = kotlin.math.abs(currentFaceCenter.x - lastFaceCenterX)
        val deltaY = kotlin.math.abs(currentFaceCenter.y - lastFaceCenterY)
        val isFastMotion = deltaX > HARD_ROI_MOTION_THRESHOLD || deltaY > HARD_ROI_MOTION_THRESHOLD

        val keyframeInterval = if (isFastMotion) {
            HARD_ROI_KEYFRAME_MOTION_INTERVAL
        } else {
            HARD_ROI_KEYFRAME_STABLE_INTERVAL
        }

        val framesSinceLastRoi = hardFrameCounter - lastHardRoiKeyframeCounter
        return framesSinceLastRoi >= keyframeInterval
    }

    fun markRoiKeyframeUsed() {
        lastHardRoiKeyframeCounter = hardFrameCounter
    }

    /**
     * 标记检测开始（由分析线程在 YUV 转换前调用）
     */
    fun markDetectionStart() {
        isDetecting = true
    }

    /**
     * 标记检测完成（由分析线程在推理和坐标转换完成后调用）
     */
    fun markDetectionComplete() {
        isDetecting = false
        lastDetectTimeMs = SystemClock.elapsedRealtime()
        if (lastDetectionTriggeredByMotion) {
            consecutiveMotionDetects++
        } else {
            consecutiveMotionDetects = 0
        }
        lastDetectionTriggeredByMotion = false
    }

    /**
     * 更新人脸中心点记录
     */
    fun updateLastFaceCenter(x: Float, y: Float) {
        lastFaceCenterX = x
        lastFaceCenterY = y
    }

    fun reset() {
        counter = 0
        lastFaceCenterX = -1f
        lastFaceCenterY = -1f
        lastDetectTimeMs = 0L
        isDetecting = false
        consecutiveMotionDetects = 0
        lastDetectionTriggeredByMotion = false
        hardFrameCounter = 0
        lastHardRoiKeyframeCounter = 0

        // 重置回默认均衡档
        adaptiveEnabled = true
        activeProfile = FaceDetectIntervalProfile.BALANCED
        detectionIntervalFrames = BALANCED_DETECTION_INTERVAL
        minDetectionIntervalMs = BALANCED_MIN_INTERVAL_MS
        motionThreshold = BALANCED_MOTION_THRESHOLD
        maxConsecutiveMotion = BALANCED_MAX_CONSECUTIVE_MOTION
        FaceDetectionRuntimeStats.reset()
    }
}

private object FaceDetectionRuntimeStats {
    private const val LOG_INTERVAL_MS = 1000L

    private var windowStartMs = SystemClock.elapsedRealtime()
    private var frameCount = 0
    private var fullPlanCount = 0
    private var landmarkOnlyPlanCount = 0
    private var skipPlanCount = 0
    private var roiExecCount = 0
    private var roiReuseCount = 0
    private var detectSuccessCount = 0
    private var detectFailCount = 0

    fun markPlan(shouldSkip: Boolean, shouldRunLandmarkOnly: Boolean) {
        frameCount++
        when {
            shouldSkip -> skipPlanCount++
            shouldRunLandmarkOnly -> landmarkOnlyPlanCount++
            else -> fullPlanCount++
        }
        maybeLogWindow()
    }

    fun markRoiExecuted() {
        roiExecCount++
    }

    fun markRoiReused() {
        roiReuseCount++
    }

    fun markDetectionResult(success: Boolean) {
        if (success) {
            detectSuccessCount++
        } else {
            detectFailCount++
        }
        maybeLogWindow()
    }

    fun reset() {
        windowStartMs = SystemClock.elapsedRealtime()
        frameCount = 0
        fullPlanCount = 0
        landmarkOnlyPlanCount = 0
        skipPlanCount = 0
        roiExecCount = 0
        roiReuseCount = 0
        detectSuccessCount = 0
        detectFailCount = 0
    }

    private fun maybeLogWindow() {
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - windowStartMs
        if (elapsed < LOG_INTERVAL_MS) return

        val reuseRate = if (roiExecCount + roiReuseCount > 0) {
            roiReuseCount * 100f / (roiExecCount + roiReuseCount).toFloat()
        } else {
            0f
        }

        Logger.i(
            "Camera",
            "[Perf][ROI-Stats] frames=$frameCount, plan(full=$fullPlanCount, lmOnly=$landmarkOnlyPlanCount, skip=$skipPlanCount), " +
                "roi(exec=$roiExecCount, reuse=$roiReuseCount, reuseRate=${"%.1f".format(reuseRate)}%), " +
                "detect(ok=$detectSuccessCount, fail=$detectFailCount), window=${elapsed}ms"
        )

        windowStartMs = now
        frameCount = 0
        fullPlanCount = 0
        landmarkOnlyPlanCount = 0
        skipPlanCount = 0
        roiExecCount = 0
        roiReuseCount = 0
        detectSuccessCount = 0
        detectFailCount = 0
    }
}

/**
 * 将 ML Kit 人脸坐标转换为纹理归一化坐标（0~1），直接对应 Shader 纹理坐标系。
 *
 * 纹理坐标系定义：
 * - (0,0) = 纹理左上角
 * - (1,1) = 纹理右下角
 * - 基于相机输入帧旋转后的尺寸（与 Shader 纹理一致）
 *
 * @return Offset(x, y) 其中 x 和 y 都在 [0, 1] 范围内
 */
internal fun transformFaceCoordinateToNormalized(
    faceX: Float,
    faceY: Float,
    imageProxyWidth: Int,
    imageProxyHeight: Int,
    rotationDegrees: Int,
    lensFacing: Int
): Offset {
    val (rotatedWidth, rotatedHeight) = when (rotationDegrees) {
        90, 270 -> Pair(imageProxyHeight, imageProxyWidth)
        else -> Pair(imageProxyWidth, imageProxyHeight)
    }

    val normX = faceX / rotatedWidth
    val normY = faceY / rotatedHeight

    val mirroredX = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
        1f - normX
    } else {
        normX
    }

    val (adjustedX, adjustedY) = when (rotationDegrees) {
        0 -> Pair(mirroredX, normY)
        90 -> Pair(mirroredX, normY)
        180 -> Pair(1f - mirroredX, 1f - normY)
        270 -> Pair(mirroredX, normY)
        else -> Pair(mirroredX, normY)
    }

    return Offset(
        x = adjustedX.coerceIn(0f, 1f),
        y = adjustedY.coerceIn(0f, 1f)
    )
}

/**
 * 将 ML Kit 人脸坐标转换为 PreviewView 屏幕像素坐标（用于调试UI绘制）。
 *
 * @deprecated 新代码应优先使用 [transformFaceCoordinateToNormalized]，
 *             调试UI绘制时自行将归一化坐标映射到屏幕。
 */
internal fun transformFaceCoordinateSimple(
    faceX: Float,
    faceY: Float,
    imageProxyWidth: Int,
    imageProxyHeight: Int,
    previewWidth: Float,
    previewHeight: Float,
    rotationDegrees: Int,
    lensFacing: Int
): Offset {
    val normPoint = transformFaceCoordinateToNormalized(
        faceX = faceX,
        faceY = faceY,
        imageProxyWidth = imageProxyWidth,
        imageProxyHeight = imageProxyHeight,
        rotationDegrees = rotationDegrees,
        lensFacing = lensFacing
    )
    return Offset(
        x = normPoint.x * previewWidth,
        y = normPoint.y * previewHeight
    )
}

/**
 * [帧同步] 停止全局 FaceDetectionWorker
 * （供 CameraPreviewRenderer.release() 调用，兼容清理残留 Worker）
 */
internal fun stopFaceDetectionWorker() {
    // Phase 1 已移除异步检测 Worker，此处保留空实现供外部兼容调用
}

/**
 * MediaPipe 版本的人脸分析帧处理
 * 使用 MediaPipe Face Landmarker 检测 468 点，映射为 106 点
 *
 * @param imageProxy CameraX ImageProxy
 * @param previewView 预览视图（用于调试UI坐标映射）
 * @param faceDetectorManager 人脸检测管理器（支持多引擎）
 * @param lensFacing 镜头方向
 * @param onFacePointChanged 人脸中心点回调（屏幕坐标，用于聚焦指示器）
 * @param onFaceWarpParamsChanged FaceWarpParams 回调
 * @param onShowFocusIndicatorChanged 聚焦指示器显示回调
 * @param adaptiveFaceDetectionIntervalEnabled 是否启用动态检测间隔
 * @param faceDetectIntervalProfile 动态检测间隔档位（默认均衡）
 * @param beautyEnabled 美颜是否启用，未启用时跳过人脸检测以节省性能
 */
@ExperimentalGetImage
internal fun handleImageAnalysisFrameMediaPipe(
    imageProxy: ImageProxy,
    previewView: PreviewView,
    faceDetector: FaceDetector,
    lensFacing: Int,
    detectionEngineMode: EngineType,
    adaptiveFaceDetectionIntervalEnabled: Boolean = true,
    faceDetectIntervalProfile: FaceDetectIntervalProfile = FaceDetectIntervalProfile.BALANCED,
    showFaceDebugOverlay: Boolean = false,
    onFacePointChanged: (Offset) -> Unit,
    onFaceWarpParamsChanged: (FaceWarpParams) -> Unit,
    onShowFocusIndicatorChanged: (Boolean) -> Unit,
    existingWarpParams: FaceWarpParams? = null,
    beautyEnabled: Boolean = false
) {
    try {
        // 美颜未启用时，跳过人脸检测
        if (!beautyEnabled) {
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            return
        }

        val previewWidth = previewView.width.takeIf { width -> width > 0 }?.toFloat()
            ?: imageProxy.width.toFloat()
        val previewHeight = previewView.height.takeIf { height -> height > 0 }?.toFloat()
            ?: imageProxy.height.toFloat()
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees

        // 根据设置应用检测策略（默认均衡档）
        FaceDetectionFrameCounter.applyPolicy(
            enabled = adaptiveFaceDetectionIntervalEnabled,
            profile = faceDetectIntervalProfile
        )

        // 获取上次的人脸中心点用于运动检测
        val lastFaceCenter = existingWarpParams?.let {
            Offset(it.faceCenterX, it.faceCenterY)
        }

        val hasExistingFace = (existingWarpParams?.hasFace == true) || FaceDetectionCache.isValid()
        val canReuseRoi = FaceDetectionCache.isRoiValid()
        val supportsRoiReuse = detectionEngineMode == EngineType.MNN

        val shouldRunLandmarkOnly: Boolean
        val shouldSkipDetection: Boolean
        if (supportsRoiReuse) {
            val shouldForceRoiKeyframe = FaceDetectionFrameCounter.shouldForceRoiKeyframeHard(
                currentFaceCenter = lastFaceCenter,
                canReuseRoi = canReuseRoi,
                hasExistingFace = hasExistingFace
            )
            shouldRunLandmarkOnly = hasExistingFace && canReuseRoi && !shouldForceRoiKeyframe
            shouldSkipDetection = false
        } else {
            val shouldDetect = FaceDetectionFrameCounter.shouldDetect(lastFaceCenter)
            shouldRunLandmarkOnly = false
            shouldSkipDetection = !shouldDetect && hasExistingFace
        }

        FaceDetectionRuntimeStats.markPlan(
            shouldSkip = shouldSkipDetection,
            shouldRunLandmarkOnly = shouldRunLandmarkOnly
        )

        // [帧同步 CR-P0-3] 使用 ImageProxy 时间戳精确查询对应的 FrameId。
        // ImageProxy.imageInfo.timestamp 与 SurfaceTexture.timestamp 共享相机硬件时间基准。
        // 通过 FrameSyncBridge.getFrameIdByTimestamp() 精确关联检测输入帧与渲染帧，
        // 避免检测结果绑定到错误的 FrameId 导致 query() 时预测补偿产生甩飞。
        val imageTimestampNs = imageProxy.imageInfo.timestamp
        val frameIdByTimestamp = FrameSyncBridge.getFrameIdByTimestamp(imageTimestampNs)
        val frameId = if (frameIdByTimestamp != FrameId.INVALID) {
            frameIdByTimestamp
        } else {
            // 回退：时间戳映射未就绪时（启动期），使用最新 FrameId
            FrameSyncBridge.getLatestFrameId().takeIf { it != FrameId.INVALID } ?: FrameId.next()
        }

        if (shouldSkipDetection) {
            // 跳过检测,复用上一帧的结果
            Logger.dThrottled("Camera", "skip_frame", "[Perf] Skip frame (no reusable ROI)")
            existingWarpParams?.let { warpParams ->
                onFaceWarpParamsChanged(warpParams)
            }

            // [帧同步关键修复] 跳帧时也必须向 FrameSyncManager 存储结果，
            // 否则渲染线程查询这些帧时会得到 MISSING，导致妆容闪烁/消失。
            // 使用缓存的最新 landmarks 和当前 frameId 存储"复用结果"。
            val cachedLandmarks = FaceDetectionCache.getCachedLandmarks106()
            if (cachedLandmarks != null && existingWarpParams?.hasFace == true) {
                FrameSyncManager.getInstance().storeResult(
                    FrameSyncManager.DetectionResult(
                        frameId = frameId,
                        landmarks106 = cachedLandmarks,
                        detectionSource = existingWarpParams.detectionSource,
                        detectionLatencyMs = 0L
                    )
                )
                Logger.dThrottled("Camera", "stored_skip", "[FrameSync] Stored skip-frame result for frameId=$frameId")
            }
            return
        }

        // [过热修复] 标记检测开始，防止后续帧排队检测
        FaceDetectionFrameCounter.markDetectionStart()

        val detectionStartMs = System.currentTimeMillis()

        // [格式检测] 在 detectFromImage 之前读取 planes，避免 MediaPipe mpImage.close() 关闭底层 Image
        // YUV 输出时 planes 有 3 个 plane，RGBA 输出时 1 个 plane + pixelStride=4
        val isRgbaOutput = imageProxy.planes.size == 1 && imageProxy.planes[0].pixelStride == 4

        var detectionResult: FaceDetectionResult? = null

        // [Zero-Copy #1] MediaPipe Image 零拷贝路径
        // 仅 RGBA 输出时可用：MediaPipe AndroidPacketCreator 要求 RGBA_8888 的 android.media.Image。
        // YUV 输出时走 Bitmap 降级路径（BitmapImageBuilder 不限制格式）。
        //
        // 注意：detectFromImage() 内部 mpImage.close() 会关闭底层 Image，
        // 因此失败后无法降级到 Bitmap（Image 已关闭），直接跳过本帧。
        if (faceDetector is FaceDetectorManager && detectionEngineMode == EngineType.MEDIAPIPE && isRgbaOutput) {
            val imageStart = SystemClock.elapsedRealtime()
            try {
                detectionResult = (faceDetector as FaceDetectorManager).detectFromImage(mediaImage, rotationDegrees, lensFacing)
                val imageElapsed = SystemClock.elapsedRealtime() - imageStart
                Logger.dThrottled("Camera", "mp_image_zero", "[Perf] MediaPipe Image zero-copy: ${imageElapsed}ms, found=${detectionResult != null}")
            } catch (e: Exception) {
                Logger.w("Camera", "MediaPipe Image detection failed: ${e.message}")
            }
            // Image 已被关闭，失败则无法降级到 Bitmap 路径
            if (detectionResult == null) {
                onShowFocusIndicatorChanged(false)
                onFaceWarpParamsChanged(
                    FaceWarpParams(requestedDetectionEngineMode = detectionEngineMode)
                )
                FaceDetectionFrameCounter.markDetectionComplete()
                FaceDetectionRuntimeStats.markDetectionResult(false)
                return
            }
        }

        // [降级] 非 MediaPipe → Bitmap / NV21 路径（此时 Image 未被关闭）
        if (detectionResult == null) {
            // [Zero-Copy #2] 尝试 MNN NV21 YUV 直传路径（仅 YUV 输出时可用）
            // 避免 YUV→ARGB Bitmap（~5ms）+ Bitmap→RGB ByteBuffer（~2ms）的双重 CPU 拷贝
            var nv21Result: RectF? = null
            var nv21Buffer: ByteBuffer? = null
            if (!isRgbaOutput) {
                val useNv21Path = faceDetector is FaceDetectorManager &&
                    detectionEngineMode == EngineType.MNN
                if (useNv21Path) {
                    val nv21Start = SystemClock.elapsedRealtime()
                    nv21Buffer = ImageUtils.imageProxyToNv21(imageProxy)
                    val nv21Elapsed = SystemClock.elapsedRealtime() - nv21Start
                    if (nv21Buffer != null) {
                        Logger.dThrottled("Camera", "yuv_nv21", "[Perf] YUV→NV21 (${detectionEngineMode.name}): ${nv21Elapsed}ms, size=${imageProxy.width}x${imageProxy.height}")

                        val detectorManager = faceDetector as FaceDetectorManager
                        if (shouldRunLandmarkOnly) {
                            val cachedRoi = FaceDetectionCache.getCachedRoiNormalized()
                            if (cachedRoi != null) {
                                // [关键修复] normalizedRoi 基于旋转后尺寸归一化，
                                // 反归一化时也必须用旋转后尺寸（rotation=90/270 时宽高互换）
                                val needSwap = rotationDegrees == 90 || rotationDegrees == 270
                                val rotatedW = if (needSwap) imageProxy.height else imageProxy.width
                                val rotatedH = if (needSwap) imageProxy.width else imageProxy.height
                                nv21Result = RectF(
                                    cachedRoi.left * rotatedW,
                                    cachedRoi.top * rotatedH,
                                    cachedRoi.right * rotatedW,
                                    cachedRoi.bottom * rotatedH
                                )
                                Logger.dThrottled("Camera", "reuse_roi", "[Perf] Reuse cached ROI for landmark-only path: $nv21Result")
                                FaceDetectionRuntimeStats.markRoiReused()
                            }
                        }

                        if (nv21Result == null) {
                            FaceDetectionFrameCounter.markRoiKeyframeUsed()
                            FaceDetectionRuntimeStats.markRoiExecuted()
                            nv21Result = detectorManager.detectRoiFromNv21(
                                nv21Buffer,
                                imageProxy.width,
                                imageProxy.height,
                                rotationDegrees
                            )
                        }

                        // [Zero-Copy #3] NV21 Landmark 检测（MNN 模式：跳过 Bitmap 创建，省 ~5ms）
                        if (nv21Result != null && detectionEngineMode == EngineType.MNN) {
                            val lmStart = SystemClock.elapsedRealtime()
                            detectionResult = detectorManager.detectLandmarksFromNv21WithRoi(
                                nv21Buffer, imageProxy.width, imageProxy.height,
                                rotationDegrees, nv21Result, lensFacing)
                            val lmElapsed = SystemClock.elapsedRealtime() - lmStart
                            val modeLabel = if (shouldRunLandmarkOnly) "LANDMARK_ONLY" else "FULL"
                            Logger.dThrottled("Camera", "nv21_full",
                                "[Perf] NV21 $modeLabel (ROI+Landmark): ${lmElapsed}ms, roi=${nv21Result}")
                        }
                    }
                }
            }

            // NV21 全链路未产出结果时，降级到 Bitmap 路径
            if (detectionResult == null) {
                // Bitmap 转换：RGBA 输出时零色彩转换（直接拷贝），YUV 输出时做 YUV→ARGB 色彩转换
                val bitmapStart = SystemClock.elapsedRealtime()
                val bitmap = if (isRgbaOutput) {
                    ImageUtils.imageProxyToBitmapRgba(imageProxy)
                } else {
                    ImageUtils.imageProxyToBitmap(imageProxy)
                }
                val bitmapElapsed = SystemClock.elapsedRealtime() - bitmapStart
                if (bitmap == null) {
                    // [过热修复] Bitmap 转换失败也需标记检测完成，防止 isDetecting 永久卡住
                    FaceDetectionFrameCounter.markDetectionComplete()
                    FaceDetectionRuntimeStats.markDetectionResult(false)
                    return
                }
                val pathLabel = if (isRgbaOutput) "RGBA→Bitmap" else "YUV→Bitmap"
                Logger.dThrottled("Camera", "bitmap_convert", "[Perf] $pathLabel: ${bitmapElapsed}ms, size=${bitmap.width}x${bitmap.height}")

                // 如果 NV21 ROI 检测成功但 Landmark NV21 路径未产出，用 Bitmap 做 Landmark
                if (nv21Result != null) {
                    val lmStart = SystemClock.elapsedRealtime()
                    val landmarkResult = (faceDetector as FaceDetectorManager).detectLandmarksWithRoi(
                        bitmap, lensFacing, nv21Result,
                        nv21Width = imageProxy.width,
                        nv21Height = imageProxy.height,
                        rotationDegrees = rotationDegrees)
                    val lmElapsed = SystemClock.elapsedRealtime() - lmStart
                    Logger.dThrottled("Camera", "nv21_path", "[Perf] NV21 ROI(${detectionEngineMode.name}) + Bitmap Landmark: ${lmElapsed}ms, roi=${nv21Result}")
                    detectionResult = landmarkResult
                } else {
                    // [性能优化] 不要 recycle！ImageUtils 内部复用此 Bitmap，recycle 会导致每帧重新分配
                    detectionResult = faceDetector.detect(bitmap, 0, lensFacing)
                }
            }
        }

        if (detectionResult != null) {
            val landmarks106 = detectionResult.landmarks106
            FaceDetectionCache.updateLandmarks106(landmarks106)
            detectionResult.roiRect?.let { normalizedRoi ->
                FaceDetectionCache.updateRoiNormalized(normalizedRoi)
            }

            // [调试信息] 显示检测器类型和 GPU 状态
            if (showFaceDebugOverlay) {
                val detectorInfo = buildString {
                    append("ROI: ${detectionResult.roiDetectorName} ")
                    append(if (detectionResult.useGpuForRoi) "GPU✓" else "CPU")
                    append(" | Landmark: ${detectionResult.landmarkDetectorName} ")
                    append(if (detectionResult.useGpuForLandmark) "GPU✓" else "CPU")
                }
                // 从 landmarks 计算人脸中心点
                val centerX = landmarks106[FaceDetectionFrameCounter.LANDMARK_106_INDEX_X] // 索引 96 (第 49 个点 x 坐标)
                val centerY = landmarks106[FaceDetectionFrameCounter.LANDMARK_106_INDEX_Y] // 索引 97 (第 49 个点 y 坐标)
                Logger.d("Camera", "[Debug] $detectorInfo, faceCenter=($centerX, $centerY)")
            }

            // [帧同步 CR-P0-2] 同步结果也存入 FrameSyncManager，供渲染线程查询
            val detectionLatencyMs = System.currentTimeMillis() - detectionStartMs
            FrameSyncManager.getInstance().storeResult(
                FrameSyncManager.DetectionResult(
                    frameId = frameId,
                    landmarks106 = landmarks106.clone(),
                    detectionSource = detectionResult.detectionSource,
                    detectionLatencyMs = detectionLatencyMs
                )
            )
            Logger.dThrottled("Camera", "stored_result", "[FrameSync] Stored result for frameId=$frameId, landmarks=${landmarks106.size}")

            val convertStart = SystemClock.elapsedRealtime()
            val faceWarpParams = Face106ToWarpParams.convert(
                landmarks106 = landmarks106,
                detectionSource = detectionResult.detectionSource
            ).copy(
                requestedDetectionEngineMode = detectionEngineMode,
                roiRect = detectionResult.roiRect,
                roiDetectorName = detectionResult.roiDetectorName,
                useGpuForRoi = detectionResult.useGpuForRoi,
                landmarkDetectorName = detectionResult.landmarkDetectorName,
                useGpuForLandmark = detectionResult.useGpuForLandmark
            )
            val convertElapsed = SystemClock.elapsedRealtime() - convertStart
            Logger.dThrottled("Camera", "convert_time", "[Perf] Face106ToWarpParams.convert: ${convertElapsed}ms")

            FaceDetectionFrameCounter.updateLastFaceCenter(
                faceWarpParams.faceCenterX,
                faceWarpParams.faceCenterY
            )

            val screenPoint = Offset(
                x = faceWarpParams.faceCenterX * previewWidth,
                y = faceWarpParams.faceCenterY * previewHeight
            )
            onFacePointChanged(screenPoint)
            onShowFocusIndicatorChanged(true)

            onFaceWarpParamsChanged(faceWarpParams)

            // [过热修复] 标记检测完成，更新时间戳和运动计数
            FaceDetectionFrameCounter.markDetectionComplete()
            FaceDetectionRuntimeStats.markDetectionResult(true)
        } else {
            onShowFocusIndicatorChanged(false)
            onFaceWarpParamsChanged(
                FaceWarpParams(requestedDetectionEngineMode = detectionEngineMode)
            )

            // [过热修复] 检测完成（即使未检测到人脸也更新冷却时间）
            FaceDetectionFrameCounter.markDetectionComplete()
            FaceDetectionRuntimeStats.markDetectionResult(false)
        }
    } catch (error: Exception) {
        Logger.e("Camera", "MediaPipe face detection error", error)
        // [过热修复] 异常时重置检测状态，防止 isDetecting 永久卡住
        FaceDetectionFrameCounter.markDetectionComplete()
        FaceDetectionRuntimeStats.markDetectionResult(false)
    } finally {
        imageProxy.close()
    }
}

