package com.mamba.picme.beauty.render

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import com.mamba.picme.beauty.api.Logger
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import com.mamba.picme.beauty.api.BeautyPerfStats

/**
 * 大美丽预览视图：
 * - CameraX 输入 Surface 与显示 Surface 明确分离
 * - 显示层使用 SurfaceView，确保 Surface 创建可靠
 */
class BeautyPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    companion object {
        private const val TAG = "BeautyPreviewView"
    }

    private val renderer: CameraPreviewRenderer = CameraPreviewRenderer(context)
    private val surfaceView: SurfaceView = SurfaceView(context)

    private var cameraSurface: Surface? = null
    private var displaySurface: Surface? = null
    private var isRendererInitialized = false
    private var cameraInputWidth: Int = 1280
    private var cameraInputHeight: Int = 720
    private var isFillCenter: Boolean = true
    private var isFrontCamera: Boolean = false

    var smoothingStrength: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            updateBeautyParamsInternal()
        }

    var whiteningStrength: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            updateBeautyParamsInternal()
        }

    var bigEyesStrength: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            updateBeautyParamsInternal()
        }

    var slimFaceStrength: Float = 0f
        set(value) {
            field = value.coerceIn(-1f, 1f)
            updateBeautyParamsInternal()
        }

    var lipColorStrength: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            updateBeautyParamsInternal()
        }

    var lipColorIndex: Int = 0
        set(value) {
            field = value.coerceIn(0, 11)
            updateBeautyParamsInternal()
        }

    var blushStrength: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            updateBeautyParamsInternal()
        }

    var blushColorFamily: Int = 0
        set(value) {
            field = value.coerceIn(0, 2)
            updateBeautyParamsInternal()
        }

    var colorMatrix: FloatArray? = null
        set(value) {
            field = value
            if (isRendererInitialized) renderer.updateColorMatrix(value)
        }

    var renderMode: Int = BeautyRenderer.MODE_BEAUTY
        set(value) {
            field = value
            renderer.setRenderMode(value)
        }

    // 专业调色参数（GPUPixel 移植到大美丽）
    var exposureStrength: Float = 0f
        set(value) {
            field = value.coerceIn(-10f, 10f)
            updateColorGradeInternal()
        }

    var contrastStrength: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 4f)
            updateColorGradeInternal()
        }

    var saturationStrength: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 2f)
            updateColorGradeInternal()
        }

    var temperatureStrength: Float = 0f
        set(value) {
            field = value.coerceIn(-1f, 1f)
            updateColorGradeInternal()
        }

    var tintStrength: Float = 0f
        set(value) {
            field = value.coerceIn(-1f, 1f)
            updateColorGradeInternal()
        }

    var brightnessStrength: Float = 0f
        set(value) {
            field = value.coerceIn(-1f, 1f)
            updateColorGradeInternal()
        }

    var redAdjustment: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 2f)
            updateColorGradeInternal()
        }

    var greenAdjustment: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 2f)
            updateColorGradeInternal()
        }

    var blueAdjustment: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 2f)
            updateColorGradeInternal()
        }

    // Phase 2: 风格特效属性
    var styleEffect: StyleEffect = StyleEffect.NONE
        set(value) {
            field = value
            updateStyleEffectInternal()
        }

    var styleIntensity: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
            updateStyleEffectInternal()
        }

    var toonThreshold: Float = 0.2f
        set(value) {
            field = value.coerceIn(0f, 1f)
            updateStyleEffectInternal()
        }

    var toonQuantizationLevels: Float = 10f
        set(value) {
            field = value.coerceIn(1f, 256f)
            updateStyleEffectInternal()
        }

    var sketchEdgeStrength: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 4f)
            updateStyleEffectInternal()
        }

    var posterizeColorLevels: Float = 10f
        set(value) {
            field = value.coerceIn(1f, 256f)
            updateStyleEffectInternal()
        }

    var embossIntensity: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 4f)
            updateStyleEffectInternal()
        }

    var crosshatchSpacing: Float = 0.03f
        set(value) {
            field = value.coerceIn(0.001f, 0.5f)
            updateStyleEffectInternal()
        }

    var crosshatchLineWidth: Float = 0.003f
        set(value) {
            field = value.coerceIn(0.0001f, 0.1f)
            updateStyleEffectInternal()
        }

    /**
     * 设置 Shader 调试模式
     * @param mode 0=正常, 1=显示 Skin Mask, 2=显示 Warp 偏移
     */
    fun setDebugMode(mode: Int) {
        renderer.setDebugMode(mode)
    }

    init {
        surfaceView.layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT
        )
        surfaceView.setZOrderMediaOverlay(true)
        addView(surfaceView)

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                val surface = holder.surface
                Logger.d(TAG, "Display SurfaceView ready: ${surfaceView.width}x${surfaceView.height}")
                surfaceCheckRunnable?.let { removeCallbacks(it) }
                surfaceCheckRunnable = null
                ensureRendererInitialized()
                bindDisplaySurface(surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                Logger.d(TAG, "Display SurfaceView changed: ${width}x${height}")
                bindDisplaySurface(holder.surface)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Logger.d(TAG, "Display SurfaceView destroyed")
                displaySurface = null
                renderer.clearRenderSurface(holder.surface)
            }
        })

        Logger.d(TAG, "BeautyPreviewView created")
    }

    fun ensureOffscreenReady() {
        ensureRendererInitialized()
    }

    private fun ensureRendererInitialized() {
        if (isRendererInitialized) return
        renderer.init(surfaceView)
        renderer.setCameraInputBufferSize(cameraInputWidth, cameraInputHeight)
        renderer.setScaleMode(isFillCenter)
        renderer.isFrontCamera = isFrontCamera
        isRendererInitialized = true
        updateBeautyParamsInternal()
        updateColorGradeInternal()
        updateStyleEffectInternal()
        renderer.updateColorMatrix(colorMatrix)
        Logger.d(TAG, "Renderer initialized")
    }

    private fun bindDisplaySurface(surface: Surface) {
        if (!isRendererInitialized || !surface.isValid) return
        if (displaySurface === surface) return
        displaySurface = surface
        renderer.setRenderSurface(surface)
    }

    fun getPerfStats(): BeautyPerfStats = renderer.getPerfStats()

    fun getSurfaceTexture(): SurfaceTexture? = renderer.getSurfaceTexture()

    fun getSurfaceForCamera(): Surface? {
        ensureRendererInitialized()
        renderer.setCameraInputBufferSize(cameraInputWidth, cameraInputHeight)
        renderer.getSurfaceTexture()?.setDefaultBufferSize(cameraInputWidth, cameraInputHeight)
        // [关键修复] 不再缓存 cameraSurface，每次调用都创建新的 Surface。
        // 原因：GlBeautyPreviewStrategy 在 SurfaceRequest 完成回调中会 release Surface，
        // 而 BeautyPreviewView.cameraSurface 与 GlBeautyPreviewProvider.previewSurface 引用同一个对象。
        // Surface.isValid() 在 release() 后不能 100% 可靠地返回 false，导致缓存命中返回已废弃的 Surface，
        // CameraX 配置相机会话时抛出 "Surface was abandoned"。
        // [修复2] 不再主动 release 旧 Surface，让 GC 自然回收，避免 CameraX 正在使用时被释放
        val oldSurface = cameraSurface
        cameraSurface = renderer.getSurfaceForCamera()
        oldSurface?.let { surface ->
            // 延迟释放旧 Surface，确保 CameraX 已完成使用
            pendingSurfaceReleaseRunnable?.let { removeCallbacks(it) }
            val releaseRunnable = Runnable {
                if (surface.isValid) surface.release()
                pendingSurfaceReleaseRunnable = null
            }
            pendingSurfaceReleaseRunnable = releaseRunnable
            postDelayed(releaseRunnable, 500)
        }
        Logger.d(TAG, "Created camera input surface: ${cameraSurface?.hashCode()}")
        return cameraSurface
    }

    private fun updateBeautyParamsInternal() {
        if (!isRendererInitialized) return
        renderer.updateBeautyParams(
            smoothing = smoothingStrength,
            whitening = whiteningStrength,
            bigEyes = bigEyesStrength,
            slimFace = slimFaceStrength,
            lipColor = lipColorStrength,
            lipColorIndex = lipColorIndex,
            blush = blushStrength,
            blushColorFamily = blushColorFamily
        )
    }

    private fun updateColorGradeInternal() {
        if (!isRendererInitialized) return
        renderer.setColorGradeParams(
            exposure = exposureStrength,
            contrast = contrastStrength,
            saturation = saturationStrength,
            temperature = temperatureStrength,
            tint = tintStrength,
            brightness = brightnessStrength,
            redAdj = redAdjustment,
            greenAdj = greenAdjustment,
            blueAdj = blueAdjustment
        )
    }

    private fun updateStyleEffectInternal() {
        if (!isRendererInitialized) return
        renderer.setStyleEffect(styleEffect)
        renderer.setStyleParams(
            intensity = styleIntensity,
            toonThreshold = toonThreshold,
            toonQuantizationLevels = toonQuantizationLevels,
            sketchEdgeStrength = sketchEdgeStrength,
            posterizeColorLevels = posterizeColorLevels,
            embossIntensity = embossIntensity,
            crosshatchSpacing = crosshatchSpacing,
            crosshatchLineWidth = crosshatchLineWidth
        )
    }

    fun setCameraInputBufferSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        cameraInputWidth = width
        cameraInputHeight = height
        if (isRendererInitialized) {
            renderer.setCameraInputBufferSize(width, height)
            renderer.getSurfaceTexture()?.setDefaultBufferSize(width, height)
        }
    }

    fun setScaleMode(isFillCenter: Boolean) {
        this.isFillCenter = isFillCenter
        if (!isRendererInitialized) return
        renderer.setScaleMode(isFillCenter)
    }

    fun setIsFrontCamera(isFront: Boolean) {
        this.isFrontCamera = isFront
        if (!isRendererInitialized) return
        renderer.isFrontCamera = isFront
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
        hasFace: Boolean,
        facePoints106: FloatArray? = null
    ) {
        if (!isRendererInitialized) return
        renderer.updateFaceWarpParams(
            faceCenterX = faceCenterX,
            faceCenterY = faceCenterY,
            leftEyeX = leftEyeX,
            leftEyeY = leftEyeY,
            rightEyeX = rightEyeX,
            rightEyeY = rightEyeY,
            mouthCenterX = mouthCenterX,
            mouthCenterY = mouthCenterY,
            mouthLeftX = mouthLeftX,
            mouthLeftY = mouthLeftY,
            mouthRightX = mouthRightX,
            mouthRightY = mouthRightY,
            upperLipCenterX = upperLipCenterX,
            upperLipCenterY = upperLipCenterY,
            lowerLipCenterX = lowerLipCenterX,
            lowerLipCenterY = lowerLipCenterY,
            faceRadius = faceRadius,
            hasFace = hasFace
        )
        // 传递106点关键点给GPUPixel风格warp
        if (facePoints106 != null) {
            renderer.updateFacePoints106(facePoints106)
        }
    }

    /**
     * 更新106点人脸关键点（GPUPixel风格瘦脸/大眼使用）
     */
    fun updateFacePoints106(landmarks106: FloatArray) {
        if (!isRendererInitialized) return
        renderer.updateFacePoints106(landmarks106)
    }

    fun updateLipMaskPoints(
        outerPoints: List<Pair<Float, Float>>,
        innerPoints: List<Pair<Float, Float>>
    ) {
        if (!isRendererInitialized) return
        renderer.updateLipMaskPoints(outerPoints, innerPoints)
    }

    fun updateCheekContourPoints(
        leftCheekPoints: List<Pair<Float, Float>>,
        rightCheekPoints: List<Pair<Float, Float>>
    ) {
        if (!isRendererInitialized) return
        renderer.updateCheekContourPoints(leftCheekPoints, rightCheekPoints)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Logger.d(TAG, "BeautyPreviewView attached to window")
        post {
            tryBindSurfaceFromHolder()
        }
    }

    private fun tryBindSurfaceFromHolder() {
        val holder = surfaceView.holder
        val surface = holder.surface
        if (surface != null && surface.isValid) {
            Logger.d(TAG, "Surface valid in onAttachedToWindow post, binding directly")
            ensureRendererInitialized()
            bindDisplaySurface(surface)
        } else {
            Logger.w(TAG, "Surface not yet valid, waiting for surfaceCreated callback")
            // SurfaceView 在动态添加时 surfaceCreated 可能延迟，启动轮询兜底
            postDelayedSurfaceCheck()
        }
    }

    private var surfaceCheckRunnable: Runnable? = null

    /** getSurfaceForCamera() 中延迟释放旧 Surface 的 Runnable，detach/release 时需移除以防持有 View */
    private var pendingSurfaceReleaseRunnable: Runnable? = null

    /** 统一摘除挂在 View Handler 上的延迟任务，避免匿名 Runnable 持有本 View → Activity 引用链 */
    private fun removePendingCallbacks() {
        surfaceCheckRunnable?.let { removeCallbacks(it) }
        surfaceCheckRunnable = null
        pendingSurfaceReleaseRunnable?.let { removeCallbacks(it) }
        pendingSurfaceReleaseRunnable = null
    }

    private fun postDelayedSurfaceCheck() {
        surfaceCheckRunnable?.let { removeCallbacks(it) }
        val runnable = object : Runnable {
            private var attemptCount = 0
            override fun run() {
                attemptCount++
                val holder = surfaceView.holder
                val surface = holder.surface
                if (surface != null && surface.isValid) {
                    Logger.d(TAG, "Delayed surface check succeeded at attempt=$attemptCount")
                    ensureRendererInitialized()
                    bindDisplaySurface(surface)
                    surfaceCheckRunnable = null
                    return
                }
                if (attemptCount < 30) {
                    postDelayed(this, 50)
                } else {
                    Logger.e(TAG, "Delayed surface check failed after 30 attempts (~1.5s)")
                    surfaceCheckRunnable = null
                }
            }
        }
        surfaceCheckRunnable = runnable
        postDelayed(runnable, 50)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        Logger.d(TAG, "BeautyPreviewView detached from window")
        removePendingCallbacks()
        displaySurface = null
        renderer.clearRenderSurface()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        Logger.d(TAG, "Visibility changed: $visibility")
    }

    fun startRecording(encoderSurface: Surface, width: Int, height: Int) {
        if (!isRendererInitialized) return
        renderer.startRecording(encoderSurface, width, height)
    }

    fun stopRecording() {
        if (!isRendererInitialized) return
        renderer.stopRecording()
    }

    fun release() {
        removePendingCallbacks()
        displaySurface = null
        cameraSurface?.release()
        cameraSurface = null
        if (isRendererInitialized) {
            renderer.release()
            isRendererInitialized = false
        }
    }
}

