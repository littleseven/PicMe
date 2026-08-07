package com.mamba.picme.beauty.render

import android.content.Context
import android.os.SystemClock
import com.mamba.picme.beauty.api.Logger
import android.view.Surface
import com.mamba.picme.beauty.api.BeautyParams
import com.mamba.picme.beauty.api.BeautyPerfStats
import com.mamba.picme.beauty.api.BeautyPreviewEngine

/**
 * GL 渲染美颜预览提供者
 *
 * 实现 [BeautyPreviewProvider] 和 [BeautyPreviewCapability]，
 * 封装 EGL/GL Shader 渲染管线。
 *
 * 参数接受 [BeautyParams]，解耦对 app 层 BeautySettings 的依赖，
 * app 层负责将 BeautySettings 转换后传入。
 */
class GlBeautyPreviewProvider(
    context: Context
) : BeautyPreviewEngine {

    companion object {
        private const val TAG = "GlBeautyProvider"
    }

    private val appContext: Context = context.applicationContext
    private var beautyPreviewView: BeautyPreviewView? = null
    // [关键修复] 不再缓存 previewSurface。每次 createPreviewSurface() 直接调用 view.getSurfaceForCamera()，
    // 确保返回全新的 Surface，避免返回已被 GlBeautyPreviewStrategy 回调 release() 的废弃 Surface。
    private var isInitialized = false
    private var lastParams: BeautyParams = BeautyParams.EMPTY
    private var cameraInputWidth: Int = 1280
    private var cameraInputHeight: Int = 720
    private var isFillCenter: Boolean = true

    override fun initialize() {
        if (isInitialized) return
        beautyPreviewView = BeautyPreviewView(appContext).apply {
            ensureOffscreenReady()
        }
        applyParams(lastParams)
        isInitialized = true
        Logger.i(TAG, "GlBeautyPreviewProvider initialized")
    }

    override fun createPreviewSurface(): Surface {
        beautyPreviewView?.setScaleMode(isFillCenter)
        beautyPreviewView?.setCameraInputBufferSize(cameraInputWidth, cameraInputHeight)
        if (!isInitialized) initialize()

        val view = beautyPreviewView
            ?: throw IllegalStateException("GlBeautyPreviewProvider not initialized")

        repeat(120) { attemptIndex ->
            val surface = view.getSurfaceForCamera()
            if (surface != null && surface.isValid) {
                Logger.i(TAG, "GL beauty preview surface ready on attempt=${attemptIndex + 1}")
                return surface
            }
            SystemClock.sleep(30)
        }

        throw IllegalStateException("GL beauty preview surface not ready")
    }

    override fun updateFilters(params: BeautyParams) {
        lastParams = params
        applyParams(params)
        // 高频调用不打日志，避免日志洪水
    }

    override fun updateFaceWarpParams(
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
        beautyPreviewView?.updateFaceWarpParams(
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
    }

    /**
     * 更新106点人脸关键点（GPUPixel风格瘦脸/大眼使用）
     */
    override fun updateFacePoints106(landmarks106: FloatArray) {
        beautyPreviewView?.updateFacePoints106(landmarks106)
    }

    override fun updateLipMaskPoints(
        outerPoints: List<Pair<Float, Float>>,
        innerPoints: List<Pair<Float, Float>>
    ) {
        beautyPreviewView?.updateLipMaskPoints(outerPoints, innerPoints)
    }

    override fun updateCheekContourPoints(
        leftCheekPoints: List<Pair<Float, Float>>,
        rightCheekPoints: List<Pair<Float, Float>>
    ) {
        beautyPreviewView?.updateCheekContourPoints(leftCheekPoints, rightCheekPoints)
    }

    override fun release() {
        beautyPreviewView?.release()
        beautyPreviewView = null
        isInitialized = false
        Logger.i(TAG, "GlBeautyPreviewProvider released")
    }

    override fun isReady(): Boolean {
        // [关键修复] 不再检查 previewSurface?.isValid。
        // SurfaceRequest 完成后 previewSurface 会被释放，isValid 变为 false。
        // 若此处检查 isValid，会导致 useProviderRenderView 被错误回退。
        // createPreviewSurface() 会在 bindPreview 时重新创建新的 Surface。
        return isInitialized
    }

    override fun getView(): BeautyPreviewView = beautyPreviewView
        ?: throw IllegalStateException("GlBeautyPreviewProvider view not ready")

    override fun setCameraInputBufferSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        cameraInputWidth = width
        cameraInputHeight = height
        beautyPreviewView?.setCameraInputBufferSize(width, height)
    }

    override fun setScaleMode(isFillCenter: Boolean) {
        this.isFillCenter = isFillCenter
        beautyPreviewView?.setScaleMode(isFillCenter)
    }

    override fun setIsFrontCamera(isFront: Boolean) {
        beautyPreviewView?.setIsFrontCamera(isFront)
    }

    override fun getPerfStats(): BeautyPerfStats = beautyPreviewView?.getPerfStats() ?: BeautyPerfStats.EMPTY

    override fun startRecording(encoderSurface: Surface, width: Int, height: Int) {
        beautyPreviewView?.startRecording(encoderSurface, width, height)
    }

    override fun stopRecording() {
        beautyPreviewView?.stopRecording()
    }

    /**
     * 设置 Shader 调试模式
     * @param mode 0=正常, 1=显示 Skin Mask, 2=显示 Warp 偏移
     */
    fun setDebugMode(mode: Int) {
        beautyPreviewView?.setDebugMode(mode)
    }

    private fun applyParams(params: BeautyParams) {
        val view = beautyPreviewView ?: return

        // 色调滤镜矩阵独立于美颜开关，始终同步
        view.colorMatrix = params.colorMatrix

        // 专业调色参数独立于美颜开关，始终同步
        view.exposureStrength = params.exposure
        view.contrastStrength = params.contrast
        view.saturationStrength = params.saturation
        view.temperatureStrength = params.temperature
        view.tintStrength = params.tint
        view.brightnessStrength = params.brightness
        view.redAdjustment = params.redAdjustment
        view.greenAdjustment = params.greenAdjustment
        view.blueAdjustment = params.blueAdjustment

        // 风格特效参数独立于美颜开关，始终同步
        view.styleEffect = params.styleEffect
        view.styleIntensity = params.styleIntensity
        view.toonThreshold = params.toonThreshold
        view.toonQuantizationLevels = params.toonQuantizationLevels
        view.sketchEdgeStrength = params.sketchEdgeStrength
        view.posterizeColorLevels = params.posterizeColorLevels
        view.embossIntensity = params.embossIntensity
        view.crosshatchSpacing = params.crosshatchSpacing
        view.crosshatchLineWidth = params.crosshatchLineWidth

        if (!params.enabled) {
            view.smoothingStrength = 0f
            view.whiteningStrength = 0f
            view.bigEyesStrength = 0f
            view.slimFaceStrength = 0f
            view.lipColorStrength = 0f
            view.lipColorIndex = 0
            view.blushStrength = 0f
            view.blushColorFamily = 0
            return
        }

        view.smoothingStrength = params.smoothing.coerceIn(0f, 1f)
        view.whiteningStrength = params.whitening.coerceIn(0f, 1f)
        view.bigEyesStrength = params.bigEyes.coerceIn(0f, 1f)
        view.slimFaceStrength = params.slimFace.coerceIn(-1f, 1f)
        view.lipColorStrength = params.lipColor.coerceIn(0f, 1f)
        view.lipColorIndex = params.lipColorIndex.coerceIn(0, 11)
        view.blushStrength = params.blush.coerceIn(0f, 1f)
        view.blushColorFamily = params.blushColorFamily.coerceIn(0, 2)
    }
}

