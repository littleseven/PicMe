package com.mamba.picme.beauty.render

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGLContext
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.view.Surface
import android.view.View
import com.mamba.picme.beauty.api.BeautyPerfStats
import com.mamba.picme.beauty.api.FrameId
import com.mamba.picme.beauty.api.FrameSyncResult
import com.mamba.picme.beauty.api.Logger
import com.mamba.picme.beauty.internal.framesync.FrameSyncBridge
import com.mamba.picme.beauty.internal.framesync.FrameSyncManager
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 大美丽 - 相机预览渲染器
 *
 * 功能：
 * 1. 管理 EGL 上下文和渲染线程
 * 2. 绑定 SurfaceTexture（来自 CameraX）
 * 3. 使用 BeautyRenderer 渲染美颜效果
 * 4. 输出到 SurfaceView
 */
class CameraPreviewRenderer(private val context: Context) {
    companion object {
        private const val TAG = "CameraPreview"

        const val DEFAULT_WIDTH = 1280
        const val DEFAULT_HEIGHT = 720
    }

    private val eglCore = EGLCore()
    private var eglContext: EGLContext? = null
    private var windowSurface: WindowSurface? = null
    private var recordingWindowSurface: WindowSurface? = null
    private lateinit var beautyRenderer: BeautyRenderer
    private var renderThread: Thread? = null

    @Volatile
    var isRendering = false
        private set

    private var surfaceTexture: SurfaceTexture? = null
    private var textureId: Int = -1

    @Volatile
    private var cameraInputWidth: Int = DEFAULT_WIDTH

    @Volatile
    private var cameraInputHeight: Int = DEFAULT_HEIGHT

    @Volatile
    private var isFillCenter: Boolean = true

    @Volatile
    var isFrontCamera: Boolean = false

    private var renderView: View? = null

    @Volatile
    private var frameAvailable: Boolean = false

    @Volatile
    private var currentOutputWidth: Int = DEFAULT_WIDTH

    @Volatile
    private var currentOutputHeight: Int = DEFAULT_HEIGHT

    @Volatile
    private var currentViewportX: Int = 0

    @Volatile
    private var currentViewportY: Int = 0

    @Volatile
    private var currentViewportWidth: Int = DEFAULT_WIDTH

    @Volatile
    private var currentViewportHeight: Int = DEFAULT_HEIGHT

    @Volatile
    private var isRecordingVideo: Boolean = false

    @Volatile
    private var recordingViewportWidth: Int = 1920

    @Volatile
    private var recordingViewportHeight: Int = 1080

    private var recordingStartTimeNs: Long = 0
    private var recordedFrameCount: Long = 0
    private val recordingFrameIntervalNs: Long = 33_333_333L // 30fps

    private val textureMatrixLock = Any()
    private val latestTextureTransformMatrix = FloatArray(16).apply {
        Matrix.setIdentityM(this, 0)
    }

    // [性能优化] 复用纹理变换矩阵缓冲区，避免每帧 new FloatArray(16)
    private val transformMatrixBuffer = FloatArray(16)

    @Volatile
    private var latestPerfStats: BeautyPerfStats = BeautyPerfStats()

    // GL 线程任务队列：用于将需要在 GL 线程执行的操作从 UI 线程投递过来
    private val glEventQueue = ConcurrentLinkedQueue<() -> Unit>()

    private val frameSyncManager = FrameSyncManager.getInstance()
    private var currentFrameId: FrameId = FrameId.INVALID

    /**
     * [帧同步] 当启用帧同步时，旧路径（UI线程回调）不再写入 hasFace 和 facePointsBuffer，
     * 避免双路径竞争导致妆容/瘦脸效果抖动。
     *
     * 在 feature/frame-sync-makeup 分支上默认启用，实现妆容与人脸按帧对齐合成。
     */
    @Volatile
    var frameSyncEnabled: Boolean = true

    interface OnTextureAvailableListener {
        fun onTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int)
        fun onTextureDestroyed()
    }

    private var textureListener: OnTextureAvailableListener? = null

    @Suppress("unused")
    fun setOnTextureAvailableListener(listener: OnTextureAvailableListener?) {
        this.textureListener = listener
    }

    fun init(view: View) {
        Logger.d(TAG, "Initializing CameraPreviewRenderer")
        this.renderView = view

        try {
            if (!eglCore.init()) {
                throw RuntimeException("Failed to initialize EGL")
            }

            eglContext = eglCore.createContext()

            val pbufferSurface = eglCore.createSurface(null, 1, 1)
            if (!eglCore.makeCurrent(pbufferSurface, eglContext!!)) {
                throw RuntimeException("Failed to make EGL context current before texture creation")
            }

            createExternalTexture()

            surfaceTexture = SurfaceTexture(textureId).apply {
                setOnFrameAvailableListener {
                    frameAvailable = true
                }
            }

            beautyRenderer = BeautyRenderer(context)
            beautyRenderer.frameSyncEnabled = frameSyncEnabled
            beautyRenderer.onInit()
            // [帧同步 P2] 预生成首个 FrameId，避免分析线程与渲染线程首次序号分叉
            FrameSyncBridge.setLatestFrameId(FrameId.next(), surfaceTexture?.timestamp ?: 0L)

            eglCore.clearCurrent()
        } catch (e: Exception) {
            // init 中途失败：release() 不会被调用（isRendererInitialized=false），
            // 这里按已完成进度做幂等清理，避免 EGL display/context/pbufferSurface/纹理泄漏
            Logger.e(TAG, "init failed, cleaning up partial GL resources: ${e.message}", e)
            runCatching { eglCore.clearCurrent() }
            if (textureId != -1) {
                runCatching { GLES20.glDeleteTextures(1, intArrayOf(textureId), 0) }
                textureId = -1
            }
            surfaceTexture?.setOnFrameAvailableListener(null)
            surfaceTexture?.release()
            surfaceTexture = null
            if (::beautyRenderer.isInitialized) {
                runCatching { beautyRenderer.release() }
            }
            runCatching { eglCore.release() }
            throw e
        }

        textureListener?.onTextureAvailable(surfaceTexture!!, DEFAULT_WIDTH, DEFAULT_HEIGHT)
        Logger.d(TAG, "CameraPreviewRenderer fully initialized")
    }

    private fun createExternalTexture() {
        val textures = intArrayOf(0)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]

        if (textureId == 0) {
            throw RuntimeException("Failed to create external texture, textureId=0")
        }

        GLES20.glBindTexture(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            textureId
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )
        Logger.d(TAG, "External texture created: $textureId")
    }

    fun setRenderSurface(surface: Surface) {
        if (!surface.isValid) {
            Logger.w(TAG, "Ignore invalid render surface")
            return
        }
        Logger.d(TAG, "Setting render surface: hash=${surface.hashCode()}")
        windowSurface?.release()
        windowSurface = WindowSurface(surface, eglCore).apply { create() }
        startRendering()
    }

    fun clearRenderSurface(surface: Surface? = null) {
        val currentWindowSurface = windowSurface ?: return
        val shouldClear = surface == null || !surface.isValid
        if (!shouldClear) {
            return
        }
        currentWindowSurface.release()
        if (windowSurface === currentWindowSurface) {
            windowSurface = null
        }
    }

    fun startRecording(encoderSurface: Surface, width: Int, height: Int) {
        glEventQueue.offer {
            // [防御性修复] 如果已经在录制中，先停止旧的录制再开始新的
            if (isRecordingVideo) {
                Logger.w(TAG, "startRecording called while already recording, stopping old first")
                recordingWindowSurface?.release()
                recordingWindowSurface = null
                isRecordingVideo = false
            }
            recordingWindowSurface = WindowSurface(encoderSurface, eglCore).apply { create() }
            recordingViewportWidth = width
            recordingViewportHeight = height
            isRecordingVideo = true
            recordingStartTimeNs = System.nanoTime()
            recordedFrameCount = 0
            Logger.d(TAG, "Recording started on GL thread: ${width}x${height}, surface=${encoderSurface.hashCode()}")
        }
    }

    fun stopRecording() {
        glEventQueue.offer {
            if (!isRecordingVideo) {
                Logger.d(TAG, "stopRecording called but not recording, ignore")
                return@offer
            }
            isRecordingVideo = false
            recordingWindowSurface?.release()
            recordingWindowSurface = null
            Logger.d(TAG, "Recording stopped on GL thread, totalFrames=$recordedFrameCount")
        }
    }

    private fun startRendering() {
        if (renderThread?.isAlive == true) {
            Logger.d(TAG, "Render thread already running")
            return
        }

        isRendering = true

        renderThread = Thread {
            Logger.d(TAG, "Render thread started")

            var frameCount = 0
            var framesReceived = 0
            var lastFrameTime = System.currentTimeMillis()
            var lastFpsLogMs = 0L
            var statsWindowStartMs = System.currentTimeMillis()
            var statsFrameCount = 0
            var statsProcessingTotalMs = 0L
            var statsNullFrames = 0

            try {
                while (isRendering && !Thread.interrupted()) {
                    if (!frameAvailable) {
                        if (!safeSleep(1)) break
                        continue
                    }

                    val frameStartNs = System.nanoTime()
                    try {
                        val ws = windowSurface
                        val context = eglContext
                        if (ws == null || context == null) {
                            if (!safeSleep(5)) break
                            continue
                        }

                        if (!eglCore.makeCurrent(ws.getEglSurface(), context)) {
                            statsNullFrames++
                            Logger.w(TAG, "eglMakeCurrent failed, waiting for next valid surface")
                            if (windowSurface === ws) {
                                ws.release()
                                windowSurface = null
                            }
                            if (!safeSleep(16)) break
                            continue
                        }

                        surfaceTexture?.updateTexImage()
                        frameAvailable = false
                        framesReceived++

                        // [帧同步 CR-P0-1] 每帧生成独立 FrameId，与相机帧严格绑定
                        currentFrameId = FrameId.next()
                        val frameTimestampNs = surfaceTexture?.timestamp ?: 0L
                        FrameSyncBridge.setLatestFrameId(currentFrameId, frameTimestampNs)
                        frameSyncManager.bindFrameId(currentFrameId, frameTimestampNs)

                        val syncResult = frameSyncManager.query(currentFrameId)
                        applySyncResultToRenderer(syncResult)

                        val currentTime = System.currentTimeMillis()
                        // 限流：fps 日志仅在 Debug 构建输出
                        if (currentTime - lastFpsLogMs >= 1_000L) {
                            lastFpsLogMs = currentTime
                            lastFrameTime = currentTime
                            framesReceived = 0
                        }

                        surfaceTexture?.getTransformMatrix(transformMatrixBuffer)
                        synchronized(textureMatrixLock) {
                            System.arraycopy(transformMatrixBuffer, 0, latestTextureTransformMatrix, 0, 16)
                        }

                        // 大美丽引擎渲染路径
                        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                        GLES20.glBindTexture(
                            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                            textureId
                        )

                        beautyRenderer.setExternalTextureId(textureId)
                        beautyRenderer.setTextureTransform(transformMatrixBuffer)
                        beautyRenderer.setIsFrontCamera(isFrontCamera)

                        val outputWidth =
                            renderView?.width?.takeIf { size -> size > 0 } ?: DEFAULT_WIDTH
                        val outputHeight =
                            renderView?.height?.takeIf { size -> size > 0 } ?: DEFAULT_HEIGHT
                        GLES20.glViewport(0, 0, outputWidth, outputHeight)
                        GLES20.glClearColor(0f, 0f, 0f, 1f)
                        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

                        // 执行 UI 线程投递过来的 GL 任务
                        var event = glEventQueue.poll()
                        while (event != null) {
                            try {
                                event()
                            } catch (e: Exception) {
                                Logger.e(TAG, "GL event error: ${e.message}", e)
                            }
                            event = glEventQueue.poll()
                        }

                        applyViewport(outputWidth, outputHeight)
                        beautyRenderer.setTexelSize(cameraInputWidth, cameraInputHeight)

                        beautyRenderer.onRender()

                        // 录制输出：将同一帧渲染到录制 Surface
                        val rs = recordingWindowSurface
                        if (rs != null && isRecordingVideo) {
                            val nowNs = System.nanoTime()
                            if (nowNs - recordingStartTimeNs >= recordedFrameCount * recordingFrameIntervalNs) {
                                val recContext = eglContext
                                if (recContext != null) {
                                    val makeCurrentOk = eglCore.makeCurrent(rs.getEglSurface(), recContext)
                                    if (makeCurrentOk) {
                                        GLES20.glViewport(0, 0, recordingViewportWidth, recordingViewportHeight)
                                        GLES20.glClearColor(0f, 0f, 0f, 1f)
                                        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

                                        beautyRenderer.onRender()

                                        // [关键] 设置帧 presentation time，使用绝对时间戳确保编码器正确消费帧
                                        val presentationTimeNs = System.nanoTime()
                                        recordedFrameCount++
                                        val ptsOk = eglCore.setPresentationTime(rs.getEglSurface(), presentationTimeNs)

                                        val glErrorAfterRender = GLES20.glGetError()
                                        if (glErrorAfterRender != GLES20.GL_NO_ERROR) {
                                            Logger.w(TAG, "GL error after beauty render to recording surface: $glErrorAfterRender")
                                        }

                                        val swapOk = rs.swapBuffers()
                                        if (recordedFrameCount <= 5L || recordedFrameCount % 30L == 0L) {
                                            // [Perf] Recording frame submission logged only in debug builds
                                        }

                                        // 切回预览 Surface
                                        val restoreOk = eglCore.makeCurrent(ws.getEglSurface(), recContext)
                                        if (!restoreOk) {
                                            Logger.e(TAG, "Failed to restore preview surface")
                                        }
                                    } else {
                                        Logger.e(TAG, "Failed to make recording surface current")
                                    }
                                }
                            }
                        }

                        // 交换缓冲区
                        if (!ws.swapBuffers()) {
                            statsNullFrames++
                            Logger.w(TAG, "swapBuffers failed, waiting for next valid surface")
                            if (windowSurface === ws) {
                                ws.release()
                                windowSurface = null
                            }
                            if (!safeSleep(16)) break
                            continue
                        }

                        frameCount++
                        statsFrameCount++
                        val frameProcessingMs =
                            ((System.nanoTime() - frameStartNs) / 1_000_000L).coerceAtLeast(0L)
                        statsProcessingTotalMs += frameProcessingMs

                        val statsNowMs = System.currentTimeMillis()
                        val statsElapsedMs = (statsNowMs - statsWindowStartMs).coerceAtLeast(1L)
                        if (statsElapsedMs >= 1000L) {
                            val fps = statsFrameCount * 1000f / statsElapsedMs.toFloat()
                            val avgProcessingMs =
                                if (statsFrameCount > 0) (statsProcessingTotalMs / statsFrameCount).toInt() else 0
                            val frameBudgetMs = if (fps > 0.1f) (1000f / fps).toInt() else 0
                            val delayMs = (frameBudgetMs - avgProcessingMs).coerceAtLeast(0)
                            val cpuUsage =
                                (statsProcessingTotalMs * 100f / statsElapsedMs.toFloat()).coerceIn(0f, 100f)
                            val lastSyncResult = frameSyncManager.getLastQueryResult()
                            latestPerfStats = BeautyPerfStats(
                                fps = fps,
                                processingMs = avgProcessingMs,
                                delayMs = delayMs,
                                cpuUsage = cpuUsage,
                                nullFrames = statsNullFrames,
                                errorCategory = beautyRenderer.getLastErrorCategory(),
                                errorReason = beautyRenderer.getLastErrorReason(),
                                detectionLatencyMs = lastSyncResult.detectionLatencyMs,
                                syncStatus = lastSyncResult.syncStatus.name,
                                predictedOffsetPx = lastSyncResult.predictedOffsetPx
                            )
                            statsWindowStartMs = statsNowMs
                            statsFrameCount = 0
                            statsProcessingTotalMs = 0L
                            statsNullFrames = 0
                        }
                    } catch (e: IllegalStateException) {
                        statsNullFrames++
                        val errorCategory = beautyRenderer.getLastErrorCategory()
                        val errorReason = beautyRenderer.getLastErrorReason().ifBlank { e.message.orEmpty() }
                        if (errorCategory.isNotBlank()) {
                            latestPerfStats = latestPerfStats.copy(
                                nullFrames = statsNullFrames,
                                errorCategory = errorCategory,
                                errorReason = errorReason
                            )
                            Logger.e(
                                TAG,
                                "Render error at frame $frameCount [category=$errorCategory]: $errorReason",
                                e
                            )
                        } else {
                            frameAvailable = false
                            if (frameCount == 0 || frameCount % 60 == 0) {
                                Logger.w(TAG, "SurfaceTexture not ready yet: ${e.message}")
                            }
                        }
                        if (!safeSleep(16)) break
                    } catch (e: Exception) {
                        statsNullFrames++
                        val errorCategory = beautyRenderer.getLastErrorCategory().ifBlank { "render_pipeline" }
                        val errorReason = beautyRenderer.getLastErrorReason().ifBlank { e.message.orEmpty() }
                        latestPerfStats = latestPerfStats.copy(
                            nullFrames = statsNullFrames,
                            errorCategory = errorCategory,
                            errorReason = errorReason
                        )
                        Logger.e(
                            TAG,
                            "Render error at frame $frameCount [category=$errorCategory]: $errorReason",
                            e
                        )
                        if (!safeSleep(16)) break
                    }
                }
            } finally {
                isRendering = false
                renderThread = null
                Logger.d(TAG, "Render thread stopped after $frameCount frames")
            }
        }.apply {
            name = "CameraPreviewRender"
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun safeSleep(ms: Long): Boolean {
        return try {
            Thread.sleep(ms)
            true
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    fun setCameraInputBufferSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        cameraInputWidth = width
        cameraInputHeight = height
    }

    fun setScaleMode(isFillCenter: Boolean) {
        this.isFillCenter = isFillCenter
    }

    private fun applyViewport(outputWidth: Int, outputHeight: Int) {
        val safeOutputWidth = outputWidth.coerceAtLeast(1)
        val safeOutputHeight = outputHeight.coerceAtLeast(1)

        val rawSourceAspect = cameraInputWidth.toFloat() / cameraInputHeight.toFloat()
        val rotatedSourceAspect = cameraInputHeight.toFloat() / cameraInputWidth.toFloat()
        val outputAspect = safeOutputWidth.toFloat() / safeOutputHeight.toFloat()

        val sourceAspect = if (
            kotlin.math.abs(rotatedSourceAspect - outputAspect) <
            kotlin.math.abs(rawSourceAspect - outputAspect)
        ) rotatedSourceAspect else rawSourceAspect

        val viewportWidth: Int
        val viewportHeight: Int

        if (isFillCenter) {
            if (sourceAspect > outputAspect) {
                viewportHeight = safeOutputHeight
                viewportWidth = (safeOutputHeight * sourceAspect).toInt().coerceAtLeast(1)
            } else {
                viewportWidth = safeOutputWidth
                viewportHeight = (safeOutputWidth / sourceAspect).toInt().coerceAtLeast(1)
            }
        } else {
            if (sourceAspect > outputAspect) {
                viewportWidth = safeOutputWidth
                viewportHeight = (safeOutputWidth / sourceAspect).toInt().coerceAtLeast(1)
            } else {
                viewportHeight = safeOutputHeight
                viewportWidth = (safeOutputHeight * sourceAspect).toInt().coerceAtLeast(1)
            }
        }

        val x = (safeOutputWidth - viewportWidth) / 2
        val y = (safeOutputHeight - viewportHeight) / 2

        currentOutputWidth = safeOutputWidth
        currentOutputHeight = safeOutputHeight
        currentViewportX = x
        currentViewportY = y
        currentViewportWidth = viewportWidth
        currentViewportHeight = viewportHeight

        GLES20.glViewport(x, y, viewportWidth, viewportHeight)
    }

    private fun mapViewNormalizedToUv(x: Float, y: Float): Pair<Float, Float> {
        val outputW = currentOutputWidth.coerceAtLeast(1)
        val outputH = currentOutputHeight.coerceAtLeast(1)
        val viewportW = currentViewportWidth.coerceAtLeast(1)
        val viewportH = currentViewportHeight.coerceAtLeast(1)

        val pixelX = x.coerceIn(0f, 1f) * outputW
        val pixelY = y.coerceIn(0f, 1f) * outputH

        val uvX = ((pixelX - currentViewportX) / viewportW).coerceIn(0f, 1f)
        val uvY = (1f - ((pixelY - currentViewportY) / viewportH)).coerceIn(0f, 1f)

        // 坐标已由上游统一为传感器原始方向（非镜像），直接映射到 FBO UV
        return Pair(uvX, uvY)
    }

    fun updateBeautyParams(
        smoothing: Float,
        whitening: Float,
        bigEyes: Float = 0f,
        slimFace: Float = 0f,
        lipColor: Float = 0f,
        lipColorIndex: Int = 0,
        blush: Float = 0f,
        blushColorFamily: Int = 0
    ) {
        beautyRenderer.updateBeautyParams(
            smoothing = smoothing,
            whitening = whitening,
            bigEyes = bigEyes,
            slimFace = slimFace,
            lipColor = lipColor,
            lipColorIndex = lipColorIndex,
            blush = blush,
            blushColorFamily = blushColorFamily
        )
    }

    fun updateFaceWarpParams(
        faceCenterX: Float,
        faceCenterY: Float,
        leftEyeX: Float,
        leftEyeY: Float,
        rightEyeX: Float,
        rightEyeY: Float,
        mouthCenterX: Float,
        mouthCenterY: Float,
        mouthLeftX: Float,
        mouthLeftY: Float,
        mouthRightX: Float,
        mouthRightY: Float,
        upperLipCenterX: Float,
        upperLipCenterY: Float,
        lowerLipCenterX: Float,
        lowerLipCenterY: Float,
        faceRadius: Float,
        hasFace: Boolean
    ) {
        val mappedFaceCenter = mapViewNormalizedToUv(faceCenterX, faceCenterY)
        val mappedLeftEye = mapViewNormalizedToUv(leftEyeX, leftEyeY)
        val mappedRightEye = mapViewNormalizedToUv(rightEyeX, rightEyeY)
        val mappedMouthCenter = mapViewNormalizedToUv(mouthCenterX, mouthCenterY)
        val mappedMouthLeft = mapViewNormalizedToUv(mouthLeftX, mouthLeftY)
        val mappedMouthRight = mapViewNormalizedToUv(mouthRightX, mouthRightY)
        val mappedUpperLipCenter = mapViewNormalizedToUv(upperLipCenterX, upperLipCenterY)
        val mappedLowerLipCenter = mapViewNormalizedToUv(lowerLipCenterX, lowerLipCenterY)

        // [帧同步] 启用帧同步时，旧路径不再写入 hasFace，避免双路径竞争抖动
        val effectiveHasFace = if (frameSyncEnabled) null else hasFace

        beautyRenderer.updateFaceWarpParams(
            faceCenterX = mappedFaceCenter.first,
            faceCenterY = mappedFaceCenter.second,
            leftEyeX = mappedLeftEye.first,
            leftEyeY = mappedLeftEye.second,
            rightEyeX = mappedRightEye.first,
            rightEyeY = mappedRightEye.second,
            mouthCenterX = mappedMouthCenter.first,
            mouthCenterY = mappedMouthCenter.second,
            mouthLeftX = mappedMouthLeft.first,
            mouthLeftY = mappedMouthLeft.second,
            mouthRightX = mappedMouthRight.first,
            mouthRightY = mappedMouthRight.second,
            upperLipCenterX = mappedUpperLipCenter.first,
            upperLipCenterY = mappedUpperLipCenter.second,
            lowerLipCenterX = mappedLowerLipCenter.first,
            lowerLipCenterY = mappedLowerLipCenter.second,
            faceRadius = faceRadius,
            hasFace = effectiveHasFace
        )
    }

    /**
     * 更新106点人脸关键点（GPUPixel风格瘦脸/大眼使用）
     * @param landmarks106 FloatArray(212) = [x0,y0, x1,y1, ..., x105,y105]
     */
    fun updateFacePoints106(landmarks106: FloatArray) {
        // [帧同步] 启用帧同步时，旧路径不再写入 facePointsBuffer，避免双路径竞争抖动
        if (frameSyncEnabled) return

        // 106点坐标需要经过与 FaceWarpParams 相同的 mapViewNormalizedToUv 映射
        // 才能与 Shader UV 坐标系对齐
        val mapped = FloatArray(landmarks106.size)
        for (i in landmarks106.indices step 2) {
            val uv = mapNormalizedToUv(landmarks106[i], landmarks106[i + 1])
            mapped[i] = uv.first
            mapped[i + 1] = uv.second
        }
        beautyRenderer.updateFacePoints106(mapped)
    }

    /**
     * 将归一化坐标 [0,1] 映射到 Shader UV 坐标系
     * 与 mapViewNormalizedToUv 相同，但跳过 viewport 裁剪（106点已在相机帧坐标系中）
     */
    private fun mapNormalizedToUv(x: Float, y: Float): Pair<Float, Float> {
        // 106点来自 bitmap 检测，Y=0在顶部；UV坐标V=0在底部，需要翻转Y
        val flippedY = 1.0f - y.coerceIn(0f, 1f)
        val normalizedX = x.coerceIn(0f, 1f)

        // 坐标已由上游统一为传感器原始方向（非镜像），直接映射到 FBO UV
        return Pair(normalizedX, flippedY)
    }

    fun updateLipMaskPoints(
        outerPoints: List<Pair<Float, Float>>,
        innerPoints: List<Pair<Float, Float>>
    ) {
        val mappedOuterPoints = outerPoints.map { point ->
            mapViewNormalizedToUv(point.first, point.second)
        }
        val mappedInnerPoints = innerPoints.map { point ->
            mapViewNormalizedToUv(point.first, point.second)
        }
        val normalizedInnerPoints = normalizeInnerLipContour(mappedOuterPoints, mappedInnerPoints)
        beautyRenderer.updateLipMaskPoints(mappedOuterPoints, normalizedInnerPoints)
    }

    fun updateCheekContourPoints(
        leftCheekPoints: List<Pair<Float, Float>>,
        rightCheekPoints: List<Pair<Float, Float>>
    ) {
        val mappedLeftPoints = leftCheekPoints.map { point ->
            mapViewNormalizedToUv(point.first, point.second)
        }
        val mappedRightPoints = rightCheekPoints.map { point ->
            mapViewNormalizedToUv(point.first, point.second)
        }
        beautyRenderer.updateCheekContourPoints(mappedLeftPoints, mappedRightPoints)
    }

    private fun normalizeInnerLipContour(
        outerPoints: List<Pair<Float, Float>>,
        innerPoints: List<Pair<Float, Float>>
    ): List<Pair<Float, Float>> {
        if (innerPoints.size < 6 || outerPoints.size < 6) return innerPoints
        val outerArea = polygonArea(outerPoints)
        val innerArea = polygonArea(innerPoints)
        if (outerArea <= 0.000001f) return innerPoints
        val areaRatio = innerArea / outerArea
        return if (areaRatio > 0.55f) {
            Logger.w(TAG, "Lip inner contour area too large (ratio=$areaRatio), fallback")
            emptyList()
        } else {
            innerPoints
        }
    }

    private fun polygonArea(points: List<Pair<Float, Float>>): Float {
        if (points.size < 3) return 0f
        var sum = 0f
        for (index in points.indices) {
            val nextIndex = (index + 1) % points.size
            val currentPoint = points[index]
            val nextPoint = points[nextIndex]
            sum += currentPoint.first * nextPoint.second - nextPoint.first * currentPoint.second
        }
        return kotlin.math.abs(sum) * 0.5f
    }

    fun updateColorMatrix(matrix: FloatArray?) {
        beautyRenderer.updateColorMatrix(matrix)
    }

    fun setColorGradeParams(
        exposure: Float = 0.0f,
        contrast: Float = 1.0f,
        saturation: Float = 1.0f,
        temperature: Float = 0.0f,
        tint: Float = 0.0f,
        brightness: Float = 0.0f,
        redAdj: Float = 1.0f,
        greenAdj: Float = 1.0f,
        blueAdj: Float = 1.0f
    ) {
        beautyRenderer.setColorGradeParams(
            exposure = exposure,
            contrast = contrast,
            saturation = saturation,
            temperature = temperature,
            tint = tint,
            brightness = brightness,
            redAdj = redAdj,
            greenAdj = greenAdj,
            blueAdj = blueAdj
        )
    }

    fun setStyleEffect(effect: StyleEffect) {
        glEventQueue.offer {
            beautyRenderer.setStyleEffect(effect)
        }
    }

    fun setStyleParams(
        intensity: Float = 1f,
        toonThreshold: Float = 0.2f,
        toonQuantizationLevels: Float = 10f,
        sketchEdgeStrength: Float = 1f,
        posterizeColorLevels: Float = 10f,
        embossIntensity: Float = 1f,
        crosshatchSpacing: Float = 0.03f,
        crosshatchLineWidth: Float = 0.003f
    ) {
        glEventQueue.offer {
            beautyRenderer.setStyleParams(
                intensity = intensity,
                toonThreshold = toonThreshold,
                toonQuantizationLevels = toonQuantizationLevels,
                sketchEdgeStrength = sketchEdgeStrength,
                posterizeColorLevels = posterizeColorLevels,
                embossIntensity = embossIntensity,
                crosshatchSpacing = crosshatchSpacing,
                crosshatchLineWidth = crosshatchLineWidth
            )
        }
    }

    fun setRenderMode(mode: Int) {
        beautyRenderer.setRenderMode(mode)
    }

    fun getPerfStats(): BeautyPerfStats = latestPerfStats

    fun setDebugMode(mode: Int) {
        beautyRenderer.setDebugMode(mode)
    }

    // [帧同步] 预分配映射缓冲区，避免每帧 GC（CR-P1-1 修复）
    private val syncMappedBuffer = FloatArray(106 * 2)

    // [帧同步] 启动期降级：前 N 帧允许旧路径提供数据，避免启动时无效果
    private var frameSyncStartupFrames: Int = 0
    private val FRAME_SYNC_STARTUP_GRACE_PERIOD = 30

    /**
     * [帧同步] 将同步结果应用到 BeautyRenderer
     */
    private fun applySyncResultToRenderer(syncResult: FrameSyncResult) {
        // [帧同步] 未启用时不干扰旧路径，避免新旧路径竞争导致 hasFace 抖动
        if (!frameSyncEnabled) return
        frameSyncStartupFrames++
        val isStartupGracePeriod = frameSyncStartupFrames <= FRAME_SYNC_STARTUP_GRACE_PERIOD

        when (syncResult.syncStatus) {
            FrameSyncResult.SyncStatus.EXACT_MATCH,
            FrameSyncResult.SyncStatus.HISTORICAL_FALLBACK,
            FrameSyncResult.SyncStatus.PREDICTED -> {
                val landmarks = syncResult.landmarks106
                if (landmarks != null && landmarks.isNotEmpty()) {
                    // 复用预分配缓冲区（CR-P1-1）
                    val count = minOf(landmarks.size, syncMappedBuffer.size)
                    for (i in 0 until count step 2) {
                        val uv = mapNormalizedToUv(landmarks[i], landmarks[i + 1])
                        syncMappedBuffer[i] = uv.first
                        syncMappedBuffer[i + 1] = uv.second
                    }
                    beautyRenderer.updateSyncedFacePoints106(syncMappedBuffer)
                    beautyRenderer.setHasFace(true)
                } else {
                    beautyRenderer.setHasFace(false)
                    Logger.w(TAG, "[FrameSync] ${syncResult.syncStatus} but landmarks empty")
                }
            }
            FrameSyncResult.SyncStatus.MISSING -> {
                // 启动期内不强制清除 hasFace，允许旧路径短暂提供数据
                if (!isStartupGracePeriod) {
                    beautyRenderer.setHasFace(false)
                }
            }
        }
    }

    fun getSurfaceTexture(): SurfaceTexture? = surfaceTexture

    fun getSurfaceForCamera(): Surface? {
        return surfaceTexture?.let { Surface(it) }
    }

    fun release() {
        Logger.d(TAG, "Releasing CameraPreviewRenderer")
        isRendering = false
        frameAvailable = false
        val activeThread = renderThread
        if (activeThread != null && activeThread !== Thread.currentThread()) {
            activeThread.interrupt()
            runCatching { activeThread.join(300) }
                .onFailure { error -> Logger.w(TAG, "Render thread join failed: ${error.message}") }
        }
        renderThread = null
        windowSurface?.release()
        windowSurface = null
        recordingWindowSurface?.release()
        recordingWindowSurface = null
        if (textureId != -1) {
            val textures = intArrayOf(textureId)
            GLES20.glDeleteTextures(1, textures, 0)
            textureId = -1
        }
        surfaceTexture?.setOnFrameAvailableListener(null)
        surfaceTexture?.release()
        surfaceTexture = null
        beautyRenderer.release()
        eglCore.release()
        // 清空待执行的 GL 事件与 View 引用，避免 lambda 捕获链在 release 后滞留
        glEventQueue.clear()
        renderView = null
        // [帧同步] 释放时清理全局状态（CR-P2-3 修复）
        frameSyncManager.clear()
        FrameSyncBridge.reset()
        // [帧同步] 重置启动期计数器，下次启动时重新进入 grace period
        frameSyncStartupFrames = 0
        textureListener?.onTextureDestroyed()
        Logger.d(TAG, "Released")
    }
}

