package com.mamba.picme.beauty.render

import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES20
import com.mamba.picme.beauty.api.Logger

/**
 * 大美丽 - 美颜渲染器
 *
 * 功能：
 * 1. 使用美颜 Shader 渲染相机预览帧
 * 2. 支持实时调整磨皮、美白、大眼、瘦脸、唇色、腮红
 * 3. Phase 2: 支持风格特效多 Pass 渲染（Toon/Sketch/Posterize/Emboss/Crosshatch）
 */
class BeautyRenderer(private val context: Context) : GLRenderer() {
    companion object {
        private const val TAG = "BeautyRenderer"

        const val MODE_DEBUG_RED = 0
        const val MODE_DEBUG_TEXTURE_R = 1
        const val MODE_BEAUTY = 2
        const val MODE_ADVANCED = 3

        private const val MAX_LIP_CONTOUR_POINTS = 20
    }

    /**
     * [帧同步] 与 CameraPreviewRenderer.frameSyncEnabled 保持同步。
     * 决定 FaceMakeupPass 使用帧同步路径（updateFaceLandmarksSynced）还是旧路径（updateFaceLandmarks）。
     *
     * 在 feature/frame-sync-makeup 分支上默认启用 true。
     */
    @Volatile
    var frameSyncEnabled: Boolean = true

    private var renderMode: Int = MODE_BEAUTY

    private var smoothingStrength: Float = 0.5f
    private var whiteningStrength: Float = 0.5f
    private var sharpenStrength: Float = 0.0f
    private var bigEyesStrength: Float = 0f
    private var slimFaceStrength: Float = 0f
    private var lipColorStrength: Float = 0f
    private var lipColorIndex: Int = 0
    private var blushStrength: Float = 0f
    private var blushColorFamily: Int = 0

    private var colorMatrix: FloatArray? = null

    private var faceCenterX: Float = 0.5f
    private var faceCenterY: Float = 0.5f
    private var leftEyeX: Float = 0.4f
    private var leftEyeY: Float = 0.45f
    private var rightEyeX: Float = 0.6f
    private var rightEyeY: Float = 0.45f
    private var mouthCenterX: Float = 0.5f
    private var mouthCenterY: Float = 0.62f
    private var mouthLeftX: Float = 0.42f
    private var mouthLeftY: Float = 0.62f
    private var mouthRightX: Float = 0.58f
    private var mouthRightY: Float = 0.62f
    private var upperLipCenterX: Float = 0.5f
    private var upperLipCenterY: Float = 0.60f
    private var lowerLipCenterX: Float = 0.5f
    private var lowerLipCenterY: Float = 0.66f
    private val lipOuterContourBuffer = FloatArray(MAX_LIP_CONTOUR_POINTS * 2)
    private var lipOuterContourCount: Int = 0
    private val lipInnerContourBuffer = FloatArray(MAX_LIP_CONTOUR_POINTS * 2)
    private var lipInnerContourCount: Int = 0
    private var faceRadius: Float = 0.18f
    private var hasFace: Float = 0f

    private val leftCheekContourBuffer = FloatArray(MAX_LIP_CONTOUR_POINTS * 2)
    private var leftCheekContourCount: Int = 0
    private val rightCheekContourBuffer = FloatArray(MAX_LIP_CONTOUR_POINTS * 2)
    private var rightCheekContourCount: Int = 0

    private var warmthStrength: Float = 0.0f
    private var contrast: Float = 1.0f
    private var exposureStrength: Float = 0.0f
    private var contrastStrength: Float = 1.0f
    private var saturationStrength: Float = 1.0f
    private var temperatureStrength: Float = 0.0f
    private var tintStrength: Float = 0.0f
    private var brightnessStrength: Float = 0.0f
    private var redAdjustment: Float = 1.0f
    private var greenAdjustment: Float = 1.0f
    private var blueAdjustment: Float = 1.0f
    private var contourThinFaceStrength: Float = 0.0f
    private var texelSizeX: Float = 0.0015f
    private var texelSizeY: Float = 0.0015f

    // GPUPixel 风格瘦脸/大眼：106点人脸关键点
    private val facePointsBuffer = FloatArray(106 * 2)
    private val faceSyncLock = Any()  // [帧同步 P0] 保护 facePointsBuffer 与 hasFace 的线程安全
    private var useGpupixelWarp: Int = 1  // 默认启用GPUPixel风格warp
    private var viewportWidth: Int = 0
    private var viewportHeight: Int = 0
    private var isFrontCamera: Int = 0

    private var renderFrameCount: Long = 0
    private var lastErrorCategory: String = ""
    private var lastErrorReason: String = ""

    // Phase 2: 风格特效多 Pass 支持
    private val styleEffectShader = StyleEffectShader(context)
    private var intermediateFbo: Framebuffer? = null
    private var fboWidth: Int = 0
    private var fboHeight: Int = 0

    // Phase 3: GPUPixel 风格多 Pass 美颜支持
    private val fboPool = FramebufferPool()
    private val lutTextureLoader = LutTextureLoader(context)

    // Pass 0: OES外部纹理 -> 2D纹理
    private val copyPass = BeautyPass(context)
    private var copyPassCompiled = false

    // Pass 1: BeautyUnitPass -> 磨皮+美白+LUT（合并Pass）
    private val beautyUnitPass = BeautyPass(context)
    private var beautyUnitPassCompiled = false

    // Phase 4: GPUPixel 风格妆容 Pass（三角网格 + 纹理贴图）
    private val faceMakeupPass = FaceMakeupPass(context)
    private var faceMakeupPassCompiled = false
    private var faceMakeupEnabled: Boolean = true

    // FBO 池中的 ping/pong FBO（供妆容 Pass 复用）
    private var fboPing: Framebuffer? = null
    private var fboPong: Framebuffer? = null

    private var uSmoothingLocation: Int = -1
    private var uWhiteningLocation: Int = -1
    private var uSharpenLocation: Int = -1
    private var uBigEyesLocation: Int = -1
    private var uSlimFaceLocation: Int = -1
    private var uFaceCenterLocation: Int = -1
    private var uLeftEyeLocation: Int = -1
    private var uRightEyeLocation: Int = -1
    private var uFaceRadiusLocation: Int = -1
    private var uHasFaceLocation: Int = -1
    private var uMouthCenterLocation: Int = -1
    private var uMouthLeftLocation: Int = -1
    private var uMouthRightLocation: Int = -1
    private var uUpperLipCenterLocation: Int = -1
    private var uLowerLipCenterLocation: Int = -1
    private var uLipOuterContourPointsLocation: Int = -1
    private var uLipOuterContourCountLocation: Int = -1
    private var uLipInnerContourPointsLocation: Int = -1
    private var uLipInnerContourCountLocation: Int = -1
    private var uLeftCheekContourPointsLocation: Int = -1
    private var uLeftCheekContourCountLocation: Int = -1
    private var uRightCheekContourPointsLocation: Int = -1
    private var uRightCheekContourCountLocation: Int = -1
    private var uWarmthLocation: Int = -1
    private var uContrastLocation: Int = -1
    private var uLipColorLocation: Int = -1
    private var uLipColorIndexLocation: Int = -1
    private var uBlushLocation: Int = -1
    private var uBlushColorFamilyLocation: Int = -1
    private var uCMRow0Location: Int = -1
    private var uCMRow1Location: Int = -1
    private var uCMRow2Location: Int = -1
    private var uCMRow3Location: Int = -1
    private var uCMOffsetLocation: Int = -1
    private var uHasColorMatrixLocation: Int = -1
    private var uExposureLocation: Int = -1
    private var uSaturationLocation: Int = -1
    private var uTemperatureLocation: Int = -1
    private var uTintLocation: Int = -1
    private var uBrightnessLocation: Int = -1
    private var uRedAdjLocation: Int = -1
    private var uGreenAdjLocation: Int = -1
    private var uBlueAdjLocation: Int = -1
    private var uContourThinFaceLocation: Int = -1
    private var uDebugModeLocation: Int = -1
    private var uFacePointsLocation: Int = -1
    private var uAspectRatioLocation: Int = -1
    private var uUseGpupixelWarpLocation: Int = -1

    // [GC 优化] 预分配 Viewport 数组，避免每帧 new IntArray(4)
    private val viewportArray = IntArray(4)
    private val singleIntBuffer = IntArray(1)

    // [GC 优化] 预分配 Staging 缓冲区，替代 toStandardUvFacePoints() 的 clone()
    private val stagingUvFacePoints = FloatArray(106 * 2)
    // [GC 优化] 预分配 Staging 缓冲区，替代轮廓点 buffer 的 clone()
    private val stagingLipOuter = FloatArray(MAX_LIP_CONTOUR_POINTS * 2)
    private val stagingLipInner = FloatArray(MAX_LIP_CONTOUR_POINTS * 2)
    private val stagingLeftCheek = FloatArray(MAX_LIP_CONTOUR_POINTS * 2)
    private val stagingRightCheek = FloatArray(MAX_LIP_CONTOUR_POINTS * 2)

    private var debugMode: Int = 0  // 0=正常渲染

    fun setRenderMode(mode: Int) {
        if (renderMode != mode) {
            Logger.d(TAG, "Render mode changed: $renderMode -> $mode")
            renderMode = mode
            isInitialized = false
            shaderProgram.release()

            if (onCompileShader()) {
                aPositionLocation = shaderProgram.getAttribLocation("aPosition")
                aTextureCoordLocation = shaderProgram.getAttribLocation("aTextureCoord")
                uTextureLocation = shaderProgram.getUniformLocation("uTexture")
                uTextureTransformLocation = shaderProgram.getUniformLocation("uTextureTransform")
                initUniformLocations()
                isInitialized = true
            }
        }
    }

    fun updateBeautyParams(
        smoothing: Float,
        whitening: Float,
        sharpen: Float = 0.0f,
        bigEyes: Float = 0f,
        slimFace: Float = 0f,
        lipColor: Float = 0f,
        lipColorIndex: Int = 0,
        blush: Float = 0f,
        blushColorFamily: Int = 0
    ) {
        smoothingStrength = smoothing.coerceIn(0f, 1f)
        whiteningStrength = whitening.coerceIn(0f, 1f)
        sharpenStrength = sharpen.coerceIn(0f, 1f)
        bigEyesStrength = bigEyes.coerceIn(0f, 1f)
        // [敏感度调整] 瘦脸形变范围缩减为原来的 1/5，避免过度变形
        slimFaceStrength = (slimFace * 0.2f).coerceIn(-0.2f, 0.2f)
        lipColorStrength = lipColor.coerceIn(0f, 1f)
        this.lipColorIndex = lipColorIndex.coerceIn(0, 11)
        blushStrength = blush.coerceIn(0f, 1f)
        this.blushColorFamily = blushColorFamily.coerceIn(0, 2)
        // [Perf] 参数更新日志仅在 Debug 构建输出，避免每帧滑块拖拽产生大量日志
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
        hasFace: Boolean?
    ) {
        this.faceCenterX = faceCenterX.coerceIn(0f, 1f)
        this.faceCenterY = faceCenterY.coerceIn(0f, 1f)
        this.leftEyeX = leftEyeX.coerceIn(0f, 1f)
        this.leftEyeY = leftEyeY.coerceIn(0f, 1f)
        this.rightEyeX = rightEyeX.coerceIn(0f, 1f)
        this.rightEyeY = rightEyeY.coerceIn(0f, 1f)
        this.mouthCenterX = mouthCenterX.coerceIn(0f, 1f)
        this.mouthCenterY = mouthCenterY.coerceIn(0f, 1f)
        this.mouthLeftX = mouthLeftX.coerceIn(0f, 1f)
        this.mouthLeftY = mouthLeftY.coerceIn(0f, 1f)
        this.mouthRightX = mouthRightX.coerceIn(0f, 1f)
        this.mouthRightY = mouthRightY.coerceIn(0f, 1f)
        this.upperLipCenterX = upperLipCenterX.coerceIn(0f, 1f)
        this.upperLipCenterY = upperLipCenterY.coerceIn(0f, 1f)
        this.lowerLipCenterX = lowerLipCenterX.coerceIn(0f, 1f)
        this.lowerLipCenterY = lowerLipCenterY.coerceIn(0f, 1f)
        this.faceRadius = faceRadius.coerceIn(0.08f, 0.45f)
        // [帧同步] hasFace 为 null 表示由帧同步路径独占控制，旧路径不修改
        if (hasFace != null) {
            synchronized(faceSyncLock) {
                this.hasFace = if (hasFace) 1f else 0f
            }
        }
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
        exposureStrength = exposure.coerceIn(-10f, 10f)
        contrastStrength = contrast.coerceIn(0f, 4f)
        saturationStrength = saturation.coerceIn(0f, 2f)
        temperatureStrength = temperature.coerceIn(-1f, 1f)
        tintStrength = tint.coerceIn(-1f, 1f)
        brightnessStrength = brightness.coerceIn(-1f, 1f)
        redAdjustment = redAdj.coerceIn(0f, 2f)
        greenAdjustment = greenAdj.coerceIn(0f, 2f)
        blueAdjustment = blueAdj.coerceIn(0f, 2f)
    }

    /**
     * GPUPixel 风格轮廓瘦脸强度设置。
     * @param strength 0.0~1.0，0.0 为关闭
     */
    fun setContourThinFace(strength: Float) {
        contourThinFaceStrength = strength.coerceIn(0f, 1f)
    }

    fun updateLipMaskPoints(
        outerPoints: List<Pair<Float, Float>>,
        innerPoints: List<Pair<Float, Float>>
    ) {
        lipOuterContourCount = outerPoints.size.coerceIn(0, MAX_LIP_CONTOUR_POINTS)
        lipInnerContourCount = innerPoints.size.coerceIn(0, MAX_LIP_CONTOUR_POINTS)

        for (index in 0 until MAX_LIP_CONTOUR_POINTS) {
            val base = index * 2
            if (index < lipOuterContourCount) {
                val point = outerPoints[index]
                lipOuterContourBuffer[base] = point.first.coerceIn(0f, 1f)
                lipOuterContourBuffer[base + 1] = point.second.coerceIn(0f, 1f)
            } else {
                lipOuterContourBuffer[base] = 0f
                lipOuterContourBuffer[base + 1] = 0f
            }

            if (index < lipInnerContourCount) {
                val point = innerPoints[index]
                lipInnerContourBuffer[base] = point.first.coerceIn(0f, 1f)
                lipInnerContourBuffer[base + 1] = point.second.coerceIn(0f, 1f)
            } else {
                lipInnerContourBuffer[base] = 0f
                lipInnerContourBuffer[base + 1] = 0f
            }
        }
    }

    fun updateCheekContourPoints(
        leftCheekPoints: List<Pair<Float, Float>>,
        rightCheekPoints: List<Pair<Float, Float>>
    ) {
        leftCheekContourCount = leftCheekPoints.size.coerceIn(0, MAX_LIP_CONTOUR_POINTS)
        rightCheekContourCount = rightCheekPoints.size.coerceIn(0, MAX_LIP_CONTOUR_POINTS)

        for (index in 0 until MAX_LIP_CONTOUR_POINTS) {
            val base = index * 2
            if (index < leftCheekContourCount) {
                val point = leftCheekPoints[index]
                leftCheekContourBuffer[base] = point.first.coerceIn(0f, 1f)
                leftCheekContourBuffer[base + 1] = point.second.coerceIn(0f, 1f)
            } else {
                leftCheekContourBuffer[base] = 0f
                leftCheekContourBuffer[base + 1] = 0f
            }

            if (index < rightCheekContourCount) {
                val point = rightCheekPoints[index]
                rightCheekContourBuffer[base] = point.first.coerceIn(0f, 1f)
                rightCheekContourBuffer[base + 1] = point.second.coerceIn(0f, 1f)
            } else {
                rightCheekContourBuffer[base] = 0f
                rightCheekContourBuffer[base + 1] = 0f
            }
        }
    }

    fun setTexelSize(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            texelSizeX = 1.0f / width
            texelSizeY = 1.0f / height
            viewportWidth = width
            viewportHeight = height
        }
    }

    /**
     * [坐标系对齐] 106点与轮廓点已由 CameraPreviewRenderer.mapNormalizedToUv()
     * 直接映射到 FBO 标准 UV [0,1]，不再经过 textureMatrix 变换。
     * 因此 facePointsBuffer 与轮廓 buffer 中已经是标准 UV，
     * 无需逆矩阵还原，直接透传即可保证与 FBO 纹理坐标系一致。
     */
    private fun toStandardUvFacePoints(): FloatArray {
        synchronized(faceSyncLock) {
            // [GC 优化] 使用预分配 staging 缓冲区，避免每帧 clone() 分配
            System.arraycopy(facePointsBuffer, 0, stagingUvFacePoints, 0, facePointsBuffer.size)
            return stagingUvFacePoints
        }
    }

    private fun inverseTransformVec2(x: Float, y: Float): Pair<Float, Float> {
        return Pair(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
    }

    private fun inverseTransformContour(buffer: FloatArray, count: Int) {
        // 坐标已由上游 mapNormalizedToUv / mapViewNormalizedToUv 对齐到 FBO UV，无需变换
        for (i in 0 until count) {
            val base = i * 2
            buffer[base] = buffer[base].coerceIn(0f, 1f)
            buffer[base + 1] = buffer[base + 1].coerceIn(0f, 1f)
        }
    }

    /**
     * 更新106点人脸关键点（GPUPixel风格瘦脸/大眼使用）
     * @param landmarks106 FloatArray(212) = [x0,y0, x1,y1, ..., x105,y105]
     */
    fun updateFacePoints106(landmarks106: FloatArray?) {
        synchronized(faceSyncLock) {
            if (landmarks106 == null || landmarks106.isEmpty()) {
                hasFace = 0f
                return
            }
            val count = minOf(landmarks106.size, facePointsBuffer.size)
            System.arraycopy(landmarks106, 0, facePointsBuffer, 0, count)
            hasFace = 1f
        }
    }

    /**
     * [帧同步] 更新同步后的106点人脸关键点
     * 与 updateFacePoints106 的区别：不控制 hasFace 状态，由调用方通过 setHasFace 独立控制
     */
    fun updateSyncedFacePoints106(landmarks106: FloatArray) {
        synchronized(faceSyncLock) {
            val count = minOf(landmarks106.size, facePointsBuffer.size)
            System.arraycopy(landmarks106, 0, facePointsBuffer, 0, count)
        }
    }

    /**
     * [帧同步] 独立设置人脸存在状态
     */
    fun setHasFace(hasFace: Boolean) {
        synchronized(faceSyncLock) {
            this.hasFace = if (hasFace) 1f else 0f
        }
    }

    /**
     * 设置是否使用GPUPixel风格warp（基于106点关键点）
     * @param enabled true=使用GPUPixel风格, false=使用原有简单warp
     */
    fun setUseGpupixelWarp(enabled: Boolean) {
        useGpupixelWarp = if (enabled) 1 else 0
    }

    fun setIsFrontCamera(enabled: Boolean) {
        isFrontCamera = if (enabled) 1 else 0
    }

    fun updateAdvancedParams(warmth: Float, contrast: Float) {
        warmthStrength = warmth.coerceIn(0f, 1f)
        this.contrast = contrast.coerceIn(0.5f, 1.5f)
    }

    /**
     * 更新色调滤镜矩阵。
     * @param matrix Android ColorMatrix.values（20个float，4行×5列，行主序）；null 表示无滤镜
     */
    fun updateColorMatrix(matrix: FloatArray?) {
        colorMatrix = matrix
    }

    override fun onCompileShader(): Boolean {
        val vertexShader = BeautyShaders.VERTEX_SHADER
        val fragmentShader = ShaderModuleLoader.loadFullFragmentShader(context)
        Logger.d(TAG, "Compiling shader (modular): mode=$renderMode")
        return shaderProgram.compile(vertexShader, fragmentShader)
    }

    // ========== Phase 2: 风格特效多 Pass 支持 ==========

    /**
     * 设置风格特效类型
     */
    fun setStyleEffect(effect: StyleEffect) {
        styleEffectShader.setStyleEffect(effect)
    }

    /**
     * 设置风格特效参数
     */
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
        styleEffectShader.setToonParams(toonThreshold, toonQuantizationLevels)
        styleEffectShader.setSketchParams(sketchEdgeStrength)
        styleEffectShader.setPosterizeParams(posterizeColorLevels)
        styleEffectShader.setEmbossParams(embossIntensity)
        styleEffectShader.setCrosshatchParams(crosshatchSpacing, crosshatchLineWidth)
    }

    private fun updateFramebufferSize(width: Int, height: Int) {
        if (width != fboWidth || height != fboHeight || intermediateFbo?.isInitialized != true) {
            intermediateFbo?.release()
            val newFbo = Framebuffer(width, height)
            if (!newFbo.initialize()) {
                Logger.e(TAG, "Failed to initialize framebuffer: ${width}x${height}")
                intermediateFbo = null
                fboWidth = 0
                fboHeight = 0
                return
            }
            intermediateFbo = newFbo
            fboWidth = width
            fboHeight = height
            styleEffectShader.setRenderSize(width, height)
            Logger.d(TAG, "Framebuffer updated: ${width}x${height}")
        }
    }

    override fun onRender() {
        runCatching {
            clearLastRenderError()
            val activeStyle = styleEffectShader.getActiveStyle()

            val needTextureMakeupPass =
                faceMakeupEnabled && hasFace > 0.5f && (lipColorStrength > 0.001f || blushStrength > 0.001f)
            val needBeautyUnitPass = smoothingStrength > 0.001f || whiteningStrength > 0.001f
            val needGeometryPass =
                bigEyesStrength > 0.001f || kotlin.math.abs(slimFaceStrength) > 0.001f
            val needMultiPass = needTextureMakeupPass || needBeautyUnitPass || needGeometryPass
            if (needMultiPass) {
                renderBeautyMultiPass(activeStyle)
                return
            }

            if (activeStyle == StyleEffect.NONE) {
                super.onRender()
                return
            }
            renderStyleEffectPass()
        }.onFailure { error ->
            recordRenderFailure(classifyRenderError(error), error)
            throw error
        }
    }

    /**
     * 多Pass美颜 + 风格特效渲染
     */
    private fun renderBeautyMultiPass(activeStyle: StyleEffect) {
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, viewportArray, 0)
        val outputWidth = viewportArray[2]
        val outputHeight = viewportArray[3]

        // 步骤1: 执行磨皮/美白 Pass
        val beautyPassSuccess = executeBeautyPasses()

        // [关键修复] 如果 executeBeautyPasses 返回 false（无需 FBO 管线），使用外部纹理 ID
        val finalInputTextureId = if (beautyPassSuccess) {
            beautyPassOutputTextureId
        } else {
            externalTextureId
        }

        // 步骤2: FaceMakeupPass 妆容渲染（三角网格 + 纹理贴图）
        if (faceMakeupEnabled && hasFace > 0.5f && (lipColorStrength > 0.001f || blushStrength > 0.001f)) {
            val ping = fboPing
            val pong = fboPong
            if (ping != null && pong != null && ping.isInitialized && pong.isInitialized) {
                beautyPassOutputTextureId = renderFaceMakeupPass(beautyPassOutputTextureId, ping, pong)
            }
        }

        // 步骤3: 使用主Shader渲染美型+美妆+调色
        // 主Shader从 beautyPassOutputTextureId（FBO纹理）采样
        if (activeStyle != StyleEffect.NONE) {
            // 有风格特效：先渲染到 intermediateFbo
            updateFramebufferSize(outputWidth, outputHeight)
            val fbo = intermediateFbo
            if (fbo != null && fbo.isInitialized) {
                renderMainShaderFromFbo(beautyPassOutputTextureId, fbo, outputWidth, outputHeight)
                fbo.unbind()

                // 风格特效 -> 屏幕
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                GLES20.glViewport(viewportArray[0], viewportArray[1], outputWidth, outputHeight)
                GLES20.glDisableVertexAttribArray(aPositionLocation)
                GLES20.glDisableVertexAttribArray(aTextureCoordLocation)
                styleEffectShader.render(fbo.getTextureId(), outputWidth, outputHeight)
            } else {
                throw IllegalStateException("style_effect: intermediate FBO is not ready")
            }
        } else {
            // 无风格特效：直接渲染到屏幕
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(viewportArray[0], viewportArray[1], outputWidth, outputHeight)
            renderMainShaderFromFbo(beautyPassOutputTextureId, null, outputWidth, outputHeight)
        }
    }

    /**
     * 使用主Shader渲染，但输入纹理来自 FBO（而非外部纹理）
     */
    // 2D 纹理版本的主 Shader（用于多Pass后从FBO采样）
    private val shaderProgram2D by lazy { ShaderProgram() }
    private var shaderProgram2DCompiled = false

    private fun clearLastRenderError() {
        lastErrorCategory = ""
        lastErrorReason = ""
    }

    private fun classifyRenderError(error: Throwable): String {
        if (lastErrorCategory.isNotBlank()) {
            return lastErrorCategory
        }
        val message = error.message.orEmpty().lowercase()
        return when {
            message.contains("shader") && message.contains("compile") -> "shader_compile"
            message.contains("shader") && message.contains("link") -> "shader_link"
            message.contains("fbo") || message.contains("framebuffer") -> "fbo_pipeline"
            message.contains("texture") -> "texture_input"
            message.contains("surface") -> "surface_output"
            message.contains("egl") -> "egl_context"
            message.contains("face") && message.contains("makeup") -> "face_makeup"
            message.contains("style") -> "style_effect"
            else -> "render_pipeline"
        }
    }

    private fun recordRenderFailure(category: String, error: Throwable) {
        lastErrorCategory = category
        lastErrorReason = lastErrorReason.ifBlank {
            error.message.orEmpty().ifBlank { error::class.java.simpleName }
        }
        Logger.e(
            TAG,
            "Render failure [$category]: ${lastErrorReason.ifBlank { "unknown" }}",
            error
        )
    }

    fun getLastErrorCategory(): String = lastErrorCategory

    fun getLastErrorReason(): String = lastErrorReason

    private fun compileShaderProgram2D(): Boolean {
        if (shaderProgram2DCompiled) {
            return true
        }
        val vertexSource = context.assets.open(ShaderModuleLoader.VERTEX_SHADER_2D_PATH).bufferedReader().use { it.readText() }
        val fragmentSource = ShaderModuleLoader.loadFullFragmentShader2D(context)
        shaderProgram2DCompiled = shaderProgram2D.compile(vertexSource, fragmentSource)
        Logger.d(TAG, "ShaderProgram2D compiled: $shaderProgram2DCompiled")
        return shaderProgram2DCompiled
    }

    private fun renderMainShaderFromFbo(
        inputTextureId: Int,
        outputFbo: Framebuffer? = null,
        viewportWidth: Int = 0,
        viewportHeight: Int = 0
    ) {
        // 编译 2D Shader（如果未编译）
        if (!compileShaderProgram2D()) {
            throw IllegalStateException("shader_compile: failed to compile ShaderProgram2D")
        }

        // 绑定输出 FBO（如果有）
        if (outputFbo != null) {
            outputFbo.bind()
        } else {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        }

        // 使用 2D 主 Shader 渲染
        shaderProgram2D.use()

        // 绑定 FBO 纹理到 TEXTURE0（替代外部纹理）
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTextureId)
        val uTexLoc = shaderProgram2D.getUniformLocation("uTexture")
        if (uTexLoc >= 0) GLES20.glUniform1i(uTexLoc, 0)

        // 设置所有 uniform（复制 onBeforeRender 的逻辑）
        shaderProgram2D.setVec2("uTexelSize", texelSizeX, texelSizeY)
        // 磨皮/美白已在多Pass中完成，主Shader不再重复
        shaderProgram2D.setFloat("uSmoothing", 0.0f)
        shaderProgram2D.setFloat("uWhitening", 0.0f)
        shaderProgram2D.setFloat("uSharpen", 0.0f)
        shaderProgram2D.setFloat("uBigEyes", bigEyesStrength)
        shaderProgram2D.setFloat("uSlimFace", slimFaceStrength)
        shaderProgram2D.setFloat("uFaceRadius", faceRadius)
        shaderProgram2D.setFloat("uHasFace", hasFace)
        // 妆容已在 FaceMakeupPass 中通过纹理图渲染，主 Shader 不再重复叠加。
        shaderProgram2D.setFloat("uLipColor", 0.0f)
        shaderProgram2D.setInt("uLipColorIndex", lipColorIndex)
        shaderProgram2D.setFloat("uBlush", 0.0f)
        shaderProgram2D.setInt("uBlushColorFamily", blushColorFamily)
        // 人脸关键点逆变换到标准UV，与vWarpCoord匹配
        val invFaceCenter = inverseTransformVec2(faceCenterX, faceCenterY)
        val invMouthCenter = inverseTransformVec2(mouthCenterX, mouthCenterY)
        val invMouthLeft = inverseTransformVec2(mouthLeftX, mouthLeftY)
        val invMouthRight = inverseTransformVec2(mouthRightX, mouthRightY)
        val invUpperLip = inverseTransformVec2(upperLipCenterX, upperLipCenterY)
        val invLowerLip = inverseTransformVec2(lowerLipCenterX, lowerLipCenterY)
        val invLeftEye = inverseTransformVec2(leftEyeX, leftEyeY)
        val invRightEye = inverseTransformVec2(rightEyeX, rightEyeY)
        shaderProgram2D.setVec2("uFaceCenter", invFaceCenter.first, invFaceCenter.second)
        shaderProgram2D.setVec2("uMouthCenter", invMouthCenter.first, invMouthCenter.second)
        shaderProgram2D.setVec2("uMouthLeft", invMouthLeft.first, invMouthLeft.second)
        shaderProgram2D.setVec2("uMouthRight", invMouthRight.first, invMouthRight.second)
        shaderProgram2D.setVec2("uUpperLipCenter", invUpperLip.first, invUpperLip.second)
        shaderProgram2D.setVec2("uLowerLipCenter", invLowerLip.first, invLowerLip.second)
        // 轮廓点也需要逆变换
        // [GC 优化] 使用预分配 staging 缓冲区，替代每帧 clone() 分配
        System.arraycopy(lipOuterContourBuffer, 0, stagingLipOuter, 0, lipOuterContourBuffer.size)
        System.arraycopy(lipInnerContourBuffer, 0, stagingLipInner, 0, lipInnerContourBuffer.size)
        System.arraycopy(leftCheekContourBuffer, 0, stagingLeftCheek, 0, leftCheekContourBuffer.size)
        System.arraycopy(rightCheekContourBuffer, 0, stagingRightCheek, 0, rightCheekContourBuffer.size)
        inverseTransformContour(stagingLipOuter, lipOuterContourCount)
        inverseTransformContour(stagingLipInner, lipInnerContourCount)
        inverseTransformContour(stagingLeftCheek, leftCheekContourCount)
        inverseTransformContour(stagingRightCheek, rightCheekContourCount)
        shaderProgram2D.setFloat("uLipOuterContourCount", lipOuterContourCount.toFloat())
        shaderProgram2D.setVec2Array("uLipOuterContourPoints", stagingLipOuter, MAX_LIP_CONTOUR_POINTS)
        shaderProgram2D.setFloat("uLipInnerContourCount", lipInnerContourCount.toFloat())
        shaderProgram2D.setVec2Array("uLipInnerContourPoints", stagingLipInner, MAX_LIP_CONTOUR_POINTS)
        shaderProgram2D.setVec2("uLeftEye", invLeftEye.first, invLeftEye.second)
        shaderProgram2D.setVec2("uRightEye", invRightEye.first, invRightEye.second)
        shaderProgram2D.setFloat("uLeftCheekContourCount", leftCheekContourCount.toFloat())
        shaderProgram2D.setVec2Array("uLeftCheekContourPoints", stagingLeftCheek, MAX_LIP_CONTOUR_POINTS)
        shaderProgram2D.setFloat("uRightCheekContourCount", rightCheekContourCount.toFloat())
        shaderProgram2D.setVec2Array("uRightCheekContourPoints", stagingRightCheek, MAX_LIP_CONTOUR_POINTS)

        if (renderMode == MODE_ADVANCED) {
            shaderProgram2D.setFloat("uWarmth", warmthStrength)
            shaderProgram2D.setFloat("uContrast", contrast)
        }

        val cm = colorMatrix
        if (cm != null && cm.size >= 20) {
            shaderProgram2D.setFloat("uHasColorMatrix", 1f)
            shaderProgram2D.setVec4("uCMRow0", cm[0], cm[1], cm[2], cm[3])
            shaderProgram2D.setVec4("uCMRow1", cm[5], cm[6], cm[7], cm[8])
            shaderProgram2D.setVec4("uCMRow2", cm[10], cm[11], cm[12], cm[13])
            shaderProgram2D.setVec4("uCMRow3", cm[15], cm[16], cm[17], cm[18])
            shaderProgram2D.setVec4("uCMOffset", cm[4] / 255f, cm[9] / 255f, cm[14] / 255f, cm[19] / 255f)
        } else {
            shaderProgram2D.setFloat("uHasColorMatrix", 0f)
        }

        shaderProgram2D.setInt("uDebugMode", debugMode)

        val uExpLoc = shaderProgram2D.getUniformLocation("uExposure")
        if (uExpLoc >= 0) GLES20.glUniform1f(uExpLoc, exposureStrength)
        val uContLoc = shaderProgram2D.getUniformLocation("uContrast")
        if (uContLoc >= 0) GLES20.glUniform1f(uContLoc, contrastStrength)
        val uSatLoc = shaderProgram2D.getUniformLocation("uSaturation")
        if (uSatLoc >= 0) GLES20.glUniform1f(uSatLoc, saturationStrength)
        val uTempLoc = shaderProgram2D.getUniformLocation("uTemperature")
        if (uTempLoc >= 0) GLES20.glUniform1f(uTempLoc, temperatureStrength)
        val uTintLoc = shaderProgram2D.getUniformLocation("uTint")
        if (uTintLoc >= 0) GLES20.glUniform1f(uTintLoc, tintStrength)
        val uBrightLoc = shaderProgram2D.getUniformLocation("uBrightness")
        if (uBrightLoc >= 0) GLES20.glUniform1f(uBrightLoc, brightnessStrength)
        val uRedLoc = shaderProgram2D.getUniformLocation("uRedAdj")
        if (uRedLoc >= 0) GLES20.glUniform1f(uRedLoc, redAdjustment)
        val uGreenLoc = shaderProgram2D.getUniformLocation("uGreenAdj")
        if (uGreenLoc >= 0) GLES20.glUniform1f(uGreenLoc, greenAdjustment)
        val uBlueLoc = shaderProgram2D.getUniformLocation("uBlueAdj")
        if (uBlueLoc >= 0) GLES20.glUniform1f(uBlueLoc, blueAdjustment)
        val uContourLoc = shaderProgram2D.getUniformLocation("uContourThinFace")
        if (uContourLoc >= 0) GLES20.glUniform1f(uContourLoc, contourThinFaceStrength)

        // 106点人脸关键点逆变换到标准UV
        val uFacePtsLoc = shaderProgram2D.getUniformLocation("uFacePoints")
        if (uFacePtsLoc >= 0 && hasFace > 0.5f) {
            val standardUvFacePoints = toStandardUvFacePoints()
            GLES20.glUniform1fv(uFacePtsLoc, standardUvFacePoints.size, standardUvFacePoints, 0)
        }
        val uAspectLoc = shaderProgram2D.getUniformLocation("uAspectRatio")
        if (uAspectLoc >= 0) {
            // 使用传入的 viewport 尺寸计算 aspect ratio（避免 FBO bind 后 viewport 被修改）
            var aspectW = viewportWidth
            var aspectH = viewportHeight
            if (aspectW <= 0 || aspectH <= 0) {
                GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, viewportArray, 0)
                aspectW = viewportArray[2]
                aspectH = viewportArray[3]
            }
            if (aspectH > 0) {
                val aspect = aspectW.toFloat() / aspectH.toFloat()
                GLES20.glUniform1f(uAspectLoc, aspect)
            }
        }
        val uUseWarpLoc = shaderProgram2D.getUniformLocation("uUseGpupixelWarp")
        if (uUseWarpLoc >= 0) GLES20.glUniform1i(uUseWarpLoc, useGpupixelWarp)

        // 设置顶点数据（CopyPass已应用uTextureTransform，FBO图像方向正确，使用标准UV）
        val vb = vertexBuffer
        val tb = textureBuffer
        if (vb != null && tb != null) {
            val aPosLoc = shaderProgram2D.getAttribLocation("aPosition")
            val aTexLoc = shaderProgram2D.getAttribLocation("aTextureCoord")
            if (aPosLoc >= 0) {
                GLES20.glEnableVertexAttribArray(aPosLoc)
                vb.position(0)
                GLES20.glVertexAttribPointer(aPosLoc, 2, GLES20.GL_FLOAT, false, 0, vb)
            }
            if (aTexLoc >= 0) {
                GLES20.glEnableVertexAttribArray(aTexLoc)
                tb.position(0)
                GLES20.glVertexAttribPointer(aTexLoc, 2, GLES20.GL_FLOAT, false, 0, tb)
            }
        }

        // 绘制
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // 清理
        if (vb != null) {
            val aPosLoc = shaderProgram2D.getAttribLocation("aPosition")
            if (aPosLoc >= 0) GLES20.glDisableVertexAttribArray(aPosLoc)
        }
        if (tb != null) {
            val aTexLoc = shaderProgram2D.getAttribLocation("aTextureCoord")
            if (aTexLoc >= 0) GLES20.glDisableVertexAttribArray(aTexLoc)
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        if (outputFbo != null) {
            outputFbo.unbind()
        }

    }

    /**
     * 拍照路径专用：2D 纹理直接渲染到当前绑定的 FBO
     *
     * 与 renderMainShaderFromFbo 的区别：
     * - 输入是 2D 纹理（Bitmap 上传），不需要 inverseTransform（预览管线专用）
     * - 直接使用原始人脸坐标（已标准化为 0.0~1.0）
     * - 不绑定/解绑 FBO（由调用方 PhotoProcessorImpl 管理）
     * - 支持磨皮/美白参数（拍照路径可能启用）
     *
     * @param inputTextureId 2D 纹理 ID（Bitmap 上传）
     * @param width 渲染宽度
     * @param height 渲染高度
     */
    fun renderMainShaderFromFbo2D(inputTextureId: Int, width: Int, height: Int, skipBeautyEffects: Boolean = false) {
        // 编译 2D Shader（如果未编译）
        if (!compileShaderProgram2D()) {
            throw IllegalStateException("shader_compile: failed to compile ShaderProgram2D for photo")
        }

        // 使用 2D 主 Shader 渲染
        shaderProgram2D.use()

        // 绑定 2D 输入纹理到 TEXTURE0
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTextureId)
        val uTexLoc = shaderProgram2D.getUniformLocation("uTexture")
        if (uTexLoc >= 0) GLES20.glUniform1i(uTexLoc, 0)

        // 设置所有 uniform（使用原始坐标，不做 inverseTransform）
        shaderProgram2D.setVec2("uTexelSize", 1.0f / width, 1.0f / height)
        // [关键修复] 当多 Pass 管线已执行 BeautyUnitPass/FaceMakeupPass 时，
        // 主 Shader 必须跳过磨皮/美白/妆容，避免重复应用导致预览/拍照不一致
        if (skipBeautyEffects) {
            shaderProgram2D.setFloat("uSmoothing", 0.0f)
            shaderProgram2D.setFloat("uWhitening", 0.0f)
            shaderProgram2D.setFloat("uSharpen", 0.0f)
            shaderProgram2D.setFloat("uLipColor", 0.0f)
            shaderProgram2D.setInt("uLipColorIndex", lipColorIndex)
            shaderProgram2D.setFloat("uBlush", 0.0f)
            shaderProgram2D.setInt("uBlushColorFamily", blushColorFamily)
        } else {
            shaderProgram2D.setFloat("uSmoothing", smoothingStrength)
            shaderProgram2D.setFloat("uWhitening", whiteningStrength)
            shaderProgram2D.setFloat("uSharpen", sharpenStrength)
            shaderProgram2D.setFloat("uLipColor", lipColorStrength)
            shaderProgram2D.setInt("uLipColorIndex", lipColorIndex)
            shaderProgram2D.setFloat("uBlush", blushStrength)
            shaderProgram2D.setInt("uBlushColorFamily", blushColorFamily)
        }
        shaderProgram2D.setFloat("uBigEyes", bigEyesStrength)
        shaderProgram2D.setFloat("uSlimFace", slimFaceStrength)
        shaderProgram2D.setFloat("uFaceRadius", faceRadius)
        shaderProgram2D.setFloat("uHasFace", hasFace)
        // 直接使用原始坐标（Bitmap 坐标空间与 Shader UV 一致）
        shaderProgram2D.setVec2("uFaceCenter", faceCenterX, faceCenterY)
        shaderProgram2D.setVec2("uMouthCenter", mouthCenterX, mouthCenterY)
        shaderProgram2D.setVec2("uMouthLeft", mouthLeftX, mouthLeftY)
        shaderProgram2D.setVec2("uMouthRight", mouthRightX, mouthRightY)
        shaderProgram2D.setVec2("uUpperLipCenter", upperLipCenterX, upperLipCenterY)
        shaderProgram2D.setVec2("uLowerLipCenter", lowerLipCenterX, lowerLipCenterY)
        shaderProgram2D.setFloat("uLipOuterContourCount", lipOuterContourCount.toFloat())
        shaderProgram2D.setVec2Array("uLipOuterContourPoints", lipOuterContourBuffer, MAX_LIP_CONTOUR_POINTS)
        shaderProgram2D.setFloat("uLipInnerContourCount", lipInnerContourCount.toFloat())
        shaderProgram2D.setVec2Array("uLipInnerContourPoints", lipInnerContourBuffer, MAX_LIP_CONTOUR_POINTS)
        shaderProgram2D.setVec2("uLeftEye", leftEyeX, leftEyeY)
        shaderProgram2D.setVec2("uRightEye", rightEyeX, rightEyeY)
        shaderProgram2D.setFloat("uLeftCheekContourCount", leftCheekContourCount.toFloat())
        shaderProgram2D.setVec2Array("uLeftCheekContourPoints", leftCheekContourBuffer, MAX_LIP_CONTOUR_POINTS)
        shaderProgram2D.setFloat("uRightCheekContourCount", rightCheekContourCount.toFloat())
        shaderProgram2D.setVec2Array("uRightCheekContourPoints", rightCheekContourBuffer, MAX_LIP_CONTOUR_POINTS)

        if (renderMode == MODE_ADVANCED) {
            shaderProgram2D.setFloat("uWarmth", warmthStrength)
            shaderProgram2D.setFloat("uContrast", contrast)
        }

        val cm = colorMatrix
        if (cm != null && cm.size >= 20) {
            shaderProgram2D.setFloat("uHasColorMatrix", 1f)
            shaderProgram2D.setVec4("uCMRow0", cm[0], cm[1], cm[2], cm[3])
            shaderProgram2D.setVec4("uCMRow1", cm[5], cm[6], cm[7], cm[8])
            shaderProgram2D.setVec4("uCMRow2", cm[10], cm[11], cm[12], cm[13])
            shaderProgram2D.setVec4("uCMRow3", cm[15], cm[16], cm[17], cm[18])
            shaderProgram2D.setVec4("uCMOffset", cm[4] / 255f, cm[9] / 255f, cm[14] / 255f, cm[19] / 255f)
        } else {
            shaderProgram2D.setFloat("uHasColorMatrix", 0f)
        }

        shaderProgram2D.setInt("uDebugMode", debugMode)

        val uExpLoc = shaderProgram2D.getUniformLocation("uExposure")
        if (uExpLoc >= 0) GLES20.glUniform1f(uExpLoc, exposureStrength)
        val uContLoc = shaderProgram2D.getUniformLocation("uContrast")
        if (uContLoc >= 0) GLES20.glUniform1f(uContLoc, contrastStrength)
        val uSatLoc = shaderProgram2D.getUniformLocation("uSaturation")
        if (uSatLoc >= 0) GLES20.glUniform1f(uSatLoc, saturationStrength)
        val uTempLoc = shaderProgram2D.getUniformLocation("uTemperature")
        if (uTempLoc >= 0) GLES20.glUniform1f(uTempLoc, temperatureStrength)
        val uTintLoc = shaderProgram2D.getUniformLocation("uTint")
        if (uTintLoc >= 0) GLES20.glUniform1f(uTintLoc, tintStrength)
        val uBrightLoc = shaderProgram2D.getUniformLocation("uBrightness")
        if (uBrightLoc >= 0) GLES20.glUniform1f(uBrightLoc, brightnessStrength)
        val uRedLoc = shaderProgram2D.getUniformLocation("uRedAdj")
        if (uRedLoc >= 0) GLES20.glUniform1f(uRedLoc, redAdjustment)
        val uGreenLoc = shaderProgram2D.getUniformLocation("uGreenAdj")
        if (uGreenLoc >= 0) GLES20.glUniform1f(uGreenLoc, greenAdjustment)
        val uBlueLoc = shaderProgram2D.getUniformLocation("uBlueAdj")
        if (uBlueLoc >= 0) GLES20.glUniform1f(uBlueLoc, blueAdjustment)
        val uContourLoc = shaderProgram2D.getUniformLocation("uContourThinFace")
        if (uContourLoc >= 0) GLES20.glUniform1f(uContourLoc, contourThinFaceStrength)

        // 106点人脸关键点（直接使用原始坐标）
        val uFacePtsLoc = shaderProgram2D.getUniformLocation("uFacePoints")
        if (uFacePtsLoc >= 0 && hasFace > 0.5f) {
            GLES20.glUniform1fv(uFacePtsLoc, facePointsBuffer.size, facePointsBuffer, 0)
        }
        val uAspectLoc = shaderProgram2D.getUniformLocation("uAspectRatio")
        if (uAspectLoc >= 0 && height > 0) {
            GLES20.glUniform1f(uAspectLoc, width.toFloat() / height.toFloat())
        }
        val uUseWarpLoc = shaderProgram2D.getUniformLocation("uUseGpupixelWarp")
        if (uUseWarpLoc >= 0) GLES20.glUniform1i(uUseWarpLoc, useGpupixelWarp)

        // 设置顶点数据（标准全屏四边形）
        val vb = vertexBuffer
        val tb = textureBuffer
        if (vb != null && tb != null) {
            val aPosLoc = shaderProgram2D.getAttribLocation("aPosition")
            val aTexLoc = shaderProgram2D.getAttribLocation("aTextureCoord")
            if (aPosLoc >= 0) {
                GLES20.glEnableVertexAttribArray(aPosLoc)
                vb.position(0)
                GLES20.glVertexAttribPointer(aPosLoc, 2, GLES20.GL_FLOAT, false, 0, vb)
            }
            if (aTexLoc >= 0) {
                GLES20.glEnableVertexAttribArray(aTexLoc)
                tb.position(0)
                GLES20.glVertexAttribPointer(aTexLoc, 2, GLES20.GL_FLOAT, false, 0, tb)
            }
        }

        // 绘制
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // [调试] 检查绘制后的 GL 错误
        val drawError = GLES20.glGetError()
        if (drawError != GLES20.GL_NO_ERROR) {
            Logger.e(TAG, "GL error after glDrawArrays in renderMainShaderFromFbo2D: $drawError")
        }

        // 清理
        if (vb != null) {
            val aPosLoc = shaderProgram2D.getAttribLocation("aPosition")
            if (aPosLoc >= 0) GLES20.glDisableVertexAttribArray(aPosLoc)
        }
        if (tb != null) {
            val aTexLoc = shaderProgram2D.getAttribLocation("aTextureCoord")
            if (aTexLoc >= 0) GLES20.glDisableVertexAttribArray(aTexLoc)
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    /**
     * 多Pass美颜渲染管线（简化版）
     *
     * Pass 0: CopyPass (OES外部纹理 -> FBO 2D纹理)
     * Pass 1: BeautyUnitPass (磨皮+美白+LUT，FBO_ping -> FBO_pong)
     * 主Shader: 美型+美妆+调色 (FBO纹理 -> 屏幕)
     */
    private var beautyPassOutputTextureId: Int = 0

    /**
     * 执行多Pass美颜：CopyPass -> BeautyUnitPass
     * 返回 true 表示成功，主Shader应从FBO纹理采样
     */
    private fun executeBeautyPasses(skipCopyPass: Boolean = false): Boolean {
        val needBeautyPass = smoothingStrength > 0.001f || whiteningStrength > 0.001f
        val needGeometryPass = bigEyesStrength > 0.001f || kotlin.math.abs(slimFaceStrength) > 0.001f
        val needMakeupPass =
            faceMakeupEnabled && hasFace > 0.5f && (lipColorStrength > 0.001f || blushStrength > 0.001f)
        val needFboPipeline = needBeautyPass || needGeometryPass || needMakeupPass
        if (!needFboPipeline) {
            return false
        }

        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, viewportArray, 0)
        val outputWidth = viewportArray[2]
        val outputHeight = viewportArray[3]

        if (outputWidth <= 0 || outputHeight <= 0) {
            Logger.w(TAG, "executeBeautyPasses: invalid viewport size")
            lastErrorCategory = "surface_output"
            lastErrorReason = "Invalid viewport size ${outputWidth}x${outputHeight}"
            return false
        }

        // 编译独立 Pass Shader（延迟编译）
        if (!copyPassCompiled) {
            try {
                copyPassCompiled = copyPass.compileFromAssets(
                    "shaders/pass_vertex.glsl", "shaders/pass_copy.glsl"
                )
                Logger.d(TAG, "CopyPass compiled: $copyPassCompiled")
                if (!copyPassCompiled) {
                    Logger.e(TAG, "CopyPass compilation failed, aborting multi-pass")
                    lastErrorCategory = "shader_compile"
                    lastErrorReason = "CopyPass compilation failed"
                    return false
                }
            } catch (e: Exception) {
                Logger.e(TAG, "CopyPass compilation exception: ${e.message}", e)
                lastErrorCategory = "shader_compile"
                lastErrorReason = e.message.orEmpty().ifBlank { "CopyPass compilation exception" }
                return false
            }
        }
        if (!beautyUnitPassCompiled) {
            beautyUnitPassCompiled = beautyUnitPass.compileFromAssets(
                "shaders/pass_vertex.glsl", "shaders/pass_smoothing.glsl"
            )
            Logger.d(TAG, "BeautyUnitPass compiled: $beautyUnitPassCompiled")
            if (!beautyUnitPassCompiled && needBeautyPass) {
                lastErrorCategory = "shader_compile"
                lastErrorReason = "BeautyUnitPass compilation failed"
                return false
            }
        }

        // 加载 LUT 纹理
        if (!lutTextureLoader.isAllLoaded()) {
            val lutLoaded = lutTextureLoader.loadAllFallback()
            Logger.d(TAG, "LUT textures loaded: $lutLoaded")
            if (!lutLoaded && needBeautyPass) {
                lastErrorCategory = "texture_input"
                lastErrorReason = "LUT textures failed to load"
                return false
            }
        }

        // 确保 FBO 池已初始化（需要2个FBO：ping/pong）
        val acquiredPing = fboPool.acquire("ping", outputWidth, outputHeight)
        val acquiredPong = fboPool.acquire("pong", outputWidth, outputHeight)
        fboPing = acquiredPing
        fboPong = acquiredPong
        if (!acquiredPing.isInitialized || !acquiredPong.isInitialized) {
            Logger.w(TAG, "FBO pool not ready")
            lastErrorCategory = "fbo_pipeline"
            lastErrorReason = "FBO pool not ready"
            return false
        }

        // 获取外部纹理 ID
        val cameraTextureId = getBoundExternalTextureId()
        if (cameraTextureId == 0) {
            Logger.w(TAG, "Camera texture ID is 0, cannot execute multi-pass pipeline")
            lastErrorCategory = "texture_input"
            lastErrorReason = "Camera texture ID is 0"
            return false
        }
        var currentInputTexture = cameraTextureId
        var currentOutputFbo = acquiredPing

        // Pass 0: 将 OES 外部纹理复制到 2D FBO 纹理
        // [关键修复] 当输入已是 2D 纹理（拍照路径）时，跳过 CopyPass
        if (!skipCopyPass && copyPassCompiled) {
            val copyProgram = copyPass.getShaderProgram()
            copyPass.render(
                inputTextureId = currentInputTexture,
                outputFbo = currentOutputFbo,
                textureTarget = GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                setupUniforms = {
                    val loc = copyProgram.getUniformLocation("uCameraTexture")
                    if (loc >= 0) GLES20.glUniform1i(loc, 0)
                    val transformLoc = copyProgram.getUniformLocation("uTextureTransform")
                    if (transformLoc >= 0) {
                        GLES20.glUniformMatrix4fv(transformLoc, 1, false, textureMatrix, 0)
                    }
                    // uIsFrontCamera 已移除：SurfaceTexture.getTransformMatrix() 已经包含前后置方向差异
                    // 前置镜像统一由 MediaPipe468Adapter 在 CPU 端处理
                }
            )
            currentInputTexture = currentOutputFbo.getTextureId()
            currentOutputFbo = acquiredPong
        }

        val originalTexture = currentInputTexture  // 保存原图纹理ID

        // 确保 Pass 的输入和输出不使用同一个 FBO
        if (currentOutputFbo.getTextureId() == originalTexture) {
            currentOutputFbo = if (currentOutputFbo === acquiredPing) acquiredPong else acquiredPing
        }

        // Pass 1: BeautyPass -> 磨皮+美白+LUT（合并Pass，无需BoxBlur/BoxHighPass）
        if (beautyUnitPassCompiled && lutTextureLoader.isAllLoaded()) {
            val unitProgram = beautyUnitPass.getShaderProgram()
            beautyUnitPass.render(
                inputTextureId = originalTexture,
                outputFbo = currentOutputFbo,
                setupUniforms = {
                    // 绑定 LUT 纹理
                    val grayLoc = unitProgram.getUniformLocation("uLookUpGray")
                    if (grayLoc >= 0) {
                        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTextureLoader.getGrayTextureId())
                        GLES20.glUniform1i(grayLoc, 1)
                    }
                    val originLoc = unitProgram.getUniformLocation("uLookUpOrigin")
                    if (originLoc >= 0) {
                        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTextureLoader.getOriginTextureId())
                        GLES20.glUniform1i(originLoc, 2)
                    }
                    val skinLoc = unitProgram.getUniformLocation("uLookUpSkin")
                    if (skinLoc >= 0) {
                        GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTextureLoader.getSkinTextureId())
                        GLES20.glUniform1i(skinLoc, 3)
                    }
                    val lightLoc = unitProgram.getUniformLocation("uLookUpLight")
                    if (lightLoc >= 0) {
                        GLES20.glActiveTexture(GLES20.GL_TEXTURE4)
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTextureLoader.getLightTextureId())
                        GLES20.glUniform1i(lightLoc, 4)
                    }
                    // 设置参数（磨皮和美白同时生效）
                    setFloat("uBlurAlpha", smoothingStrength)
                    setFloat("uSharpen", 0.0f)
                    setFloat("uWhiten", whiteningStrength)
                    setFloat("uWidthOffset", texelSizeX)
                    setFloat("uHeightOffset", texelSizeY)
                }
            )
            currentInputTexture = currentOutputFbo.getTextureId()
        } else if (needBeautyPass) {
            lastErrorCategory = "render_pipeline"
            lastErrorReason = "BeautyUnitPass skipped while beauty pass is required"
            Logger.w(TAG, "BeautyUnitPass skipped: compiled=$beautyUnitPassCompiled, lutLoaded=${lutTextureLoader.isAllLoaded()}")
            return false
        }

        // 保存最终输出纹理 ID
        beautyPassOutputTextureId = currentInputTexture
        return true
    }

    /**
     * 外部纹理 ID（由 CameraPreviewRenderer 在渲染前绑定到 GL_TEXTURE0 + GL_TEXTURE_EXTERNAL_OES）
     * 不再查询 GL 状态，而是直接使用已知的外部纹理 ID
     */
    private var externalTextureId: Int = 0

    fun setExternalTextureId(id: Int) {
        externalTextureId = id
    }

    private fun getBoundExternalTextureId(): Int {
        return externalTextureId
    }

    private fun renderStyleEffectPass() {
        // 获取当前 viewport 尺寸
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, viewportArray, 0)
        val outputWidth = viewportArray[2]
        val outputHeight = viewportArray[3]

        updateFramebufferSize(outputWidth, outputHeight)

        val fbo = intermediateFbo
        if (fbo == null || !fbo.isInitialized) {
            throw IllegalStateException("style_effect: style effect FBO is not ready")
        }

        // Pass 1: 美颜 + 调色 -> FBO
        fbo.bind()
        super.onRender()
        fbo.unbind()

        // 恢复 viewport 和默认 framebuffer
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(viewportArray[0], viewportArray[1], outputWidth, outputHeight)

        // Pass 2: 风格特效 -> 屏幕
        // 禁用 Pass 1 可能遗留的 vertex attrib array，避免干扰 Pass 2
        GLES20.glDisableVertexAttribArray(aPositionLocation)
        GLES20.glDisableVertexAttribArray(aTextureCoordLocation)

        styleEffectShader.render(fbo.getTextureId(), outputWidth, outputHeight)
    }

    // ========== Phase 2 结束 ==========

    override fun onBeforeRender() {
        super.onBeforeRender()
        initUniformLocations()

        renderFrameCount++

        when (renderMode) {
            MODE_BEAUTY, MODE_ADVANCED -> {
                shaderProgram.setVec2("uTexelSize", texelSizeX, texelSizeY)
                shaderProgram.setFloat("uSmoothing", smoothingStrength)
                shaderProgram.setFloat("uWhitening", whiteningStrength)
                shaderProgram.setFloat("uSharpen", sharpenStrength)
                shaderProgram.setFloat("uBigEyes", bigEyesStrength)
                shaderProgram.setFloat("uSlimFace", slimFaceStrength)
                shaderProgram.setFloat("uFaceRadius", faceRadius)
                shaderProgram.setFloat("uHasFace", hasFace)
                shaderProgram.setFloat("uLipColor", lipColorStrength)
                shaderProgram.setInt("uLipColorIndex", lipColorIndex)
                shaderProgram.setFloat("uBlush", blushStrength)
                shaderProgram.setInt("uBlushColorFamily", blushColorFamily)
                shaderProgram.setVec2("uFaceCenter", faceCenterX, faceCenterY)
                shaderProgram.setVec2("uMouthCenter", mouthCenterX, mouthCenterY)
                shaderProgram.setVec2("uMouthLeft", mouthLeftX, mouthLeftY)
                shaderProgram.setVec2("uMouthRight", mouthRightX, mouthRightY)
                shaderProgram.setVec2("uUpperLipCenter", upperLipCenterX, upperLipCenterY)
                shaderProgram.setVec2("uLowerLipCenter", lowerLipCenterX, lowerLipCenterY)
                shaderProgram.setFloat("uLipOuterContourCount", lipOuterContourCount.toFloat())
                shaderProgram.setVec2Array(
                    "uLipOuterContourPoints",
                    lipOuterContourBuffer,
                    MAX_LIP_CONTOUR_POINTS
                )
                shaderProgram.setFloat("uLipInnerContourCount", lipInnerContourCount.toFloat())
                shaderProgram.setVec2Array(
                    "uLipInnerContourPoints",
                    lipInnerContourBuffer,
                    MAX_LIP_CONTOUR_POINTS
                )
                shaderProgram.setVec2("uLeftEye", leftEyeX, leftEyeY)
                shaderProgram.setVec2("uRightEye", rightEyeX, rightEyeY)
                shaderProgram.setFloat("uLeftCheekContourCount", leftCheekContourCount.toFloat())
                shaderProgram.setVec2Array(
                    "uLeftCheekContourPoints",
                    leftCheekContourBuffer,
                    MAX_LIP_CONTOUR_POINTS
                )
                shaderProgram.setFloat("uRightCheekContourCount", rightCheekContourCount.toFloat())
                shaderProgram.setVec2Array(
                    "uRightCheekContourPoints",
                    rightCheekContourBuffer,
                    MAX_LIP_CONTOUR_POINTS
                )

                if (renderMode == MODE_ADVANCED) {
                    shaderProgram.setFloat("uWarmth", warmthStrength)
                    shaderProgram.setFloat("uContrast", contrast)
                }

                val cm = colorMatrix
                if (cm != null && cm.size >= 20) {
                    shaderProgram.setFloat("uHasColorMatrix", 1f)
                    shaderProgram.setVec4("uCMRow0", cm[0], cm[1], cm[2], cm[3])
                    shaderProgram.setVec4("uCMRow1", cm[5], cm[6], cm[7], cm[8])
                    shaderProgram.setVec4("uCMRow2", cm[10], cm[11], cm[12], cm[13])
                    shaderProgram.setVec4("uCMRow3", cm[15], cm[16], cm[17], cm[18])
                    shaderProgram.setVec4(
                        "uCMOffset",
                        cm[4] / 255f, cm[9] / 255f, cm[14] / 255f, cm[19] / 255f
                    )
                } else {
                    shaderProgram.setFloat("uHasColorMatrix", 0f)
                }

                shaderProgram.setInt("uDebugMode", debugMode)

                GLES20.glUniform1f(uExposureLocation, exposureStrength)
                GLES20.glUniform1f(uContrastLocation, contrastStrength)
                GLES20.glUniform1f(uSaturationLocation, saturationStrength)
                GLES20.glUniform1f(uTemperatureLocation, temperatureStrength)
                GLES20.glUniform1f(uTintLocation, tintStrength)
                GLES20.glUniform1f(uBrightnessLocation, brightnessStrength)
                GLES20.glUniform1f(uRedAdjLocation, redAdjustment)
                GLES20.glUniform1f(uGreenAdjLocation, greenAdjustment)
                GLES20.glUniform1f(uBlueAdjLocation, blueAdjustment)
                GLES20.glUniform1f(uContourThinFaceLocation, contourThinFaceStrength)

                // GPUPixel 风格瘦脸/大眼：传递106点关键点和宽高比
                if (uFacePointsLocation >= 0 && hasFace > 0.5f) {
                    GLES20.glUniform1fv(uFacePointsLocation, facePointsBuffer.size, facePointsBuffer, 0)
                }
                if (uAspectRatioLocation >= 0) {
                    // 使用当前实际 viewport 尺寸计算 aspect ratio（与 GPUPixel 原始实现一致）
                    GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, viewportArray, 0)
                    val vpW = viewportArray[2]
                    val vpH = viewportArray[3]
                    if (vpH > 0) {
                        val aspect = vpW.toFloat() / vpH.toFloat()
                        GLES20.glUniform1f(uAspectRatioLocation, aspect)
                    }
                }
                if (uUseGpupixelWarpLocation >= 0) {
                    GLES20.glUniform1i(uUseGpupixelWarpLocation, useGpupixelWarp)
                }
            }
        }

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glUniform1i(uTextureLocation, 0)
    }

    private fun initUniformLocations() {
        uSmoothingLocation = shaderProgram.getUniformLocation("uSmoothing")
        uWhiteningLocation = shaderProgram.getUniformLocation("uWhitening")
        uSharpenLocation = shaderProgram.getUniformLocation("uSharpen")
        uBigEyesLocation = shaderProgram.getUniformLocation("uBigEyes")
        uSlimFaceLocation = shaderProgram.getUniformLocation("uSlimFace")
        uFaceCenterLocation = shaderProgram.getUniformLocation("uFaceCenter")
        uLeftEyeLocation = shaderProgram.getUniformLocation("uLeftEye")
        uRightEyeLocation = shaderProgram.getUniformLocation("uRightEye")
        uFaceRadiusLocation = shaderProgram.getUniformLocation("uFaceRadius")
        uHasFaceLocation = shaderProgram.getUniformLocation("uHasFace")
        uMouthCenterLocation = shaderProgram.getUniformLocation("uMouthCenter")
        uMouthLeftLocation = shaderProgram.getUniformLocation("uMouthLeft")
        uMouthRightLocation = shaderProgram.getUniformLocation("uMouthRight")
        uUpperLipCenterLocation = shaderProgram.getUniformLocation("uUpperLipCenter")
        uLowerLipCenterLocation = shaderProgram.getUniformLocation("uLowerLipCenter")
        uLipOuterContourPointsLocation =
            shaderProgram.getUniformLocation("uLipOuterContourPoints")
        uLipOuterContourCountLocation =
            shaderProgram.getUniformLocation("uLipOuterContourCount")
        uLipInnerContourPointsLocation =
            shaderProgram.getUniformLocation("uLipInnerContourPoints")
        uLipInnerContourCountLocation =
            shaderProgram.getUniformLocation("uLipInnerContourCount")
        uLeftCheekContourPointsLocation =
            shaderProgram.getUniformLocation("uLeftCheekContourPoints")
        uLeftCheekContourCountLocation =
            shaderProgram.getUniformLocation("uLeftCheekContourCount")
        uRightCheekContourPointsLocation =
            shaderProgram.getUniformLocation("uRightCheekContourPoints")
        uRightCheekContourCountLocation =
            shaderProgram.getUniformLocation("uRightCheekContourCount")
        uWarmthLocation = shaderProgram.getUniformLocation("uWarmth")
        uContrastLocation = shaderProgram.getUniformLocation("uContrast")
        uLipColorLocation = shaderProgram.getUniformLocation("uLipColor")
        uLipColorIndexLocation = shaderProgram.getUniformLocation("uLipColorIndex")
        uBlushLocation = shaderProgram.getUniformLocation("uBlush")
        uBlushColorFamilyLocation = shaderProgram.getUniformLocation("uBlushColorFamily")
        uCMRow0Location = shaderProgram.getUniformLocation("uCMRow0")
        uCMRow1Location = shaderProgram.getUniformLocation("uCMRow1")
        uCMRow2Location = shaderProgram.getUniformLocation("uCMRow2")
        uCMRow3Location = shaderProgram.getUniformLocation("uCMRow3")
        uCMOffsetLocation = shaderProgram.getUniformLocation("uCMOffset")
        uHasColorMatrixLocation = shaderProgram.getUniformLocation("uHasColorMatrix")
        uExposureLocation = shaderProgram.getUniformLocation("uExposure")
        uSaturationLocation = shaderProgram.getUniformLocation("uSaturation")
        uTemperatureLocation = shaderProgram.getUniformLocation("uTemperature")
        uTintLocation = shaderProgram.getUniformLocation("uTint")
        uBrightnessLocation = shaderProgram.getUniformLocation("uBrightness")
        uRedAdjLocation = shaderProgram.getUniformLocation("uRedAdj")
        uGreenAdjLocation = shaderProgram.getUniformLocation("uGreenAdj")
        uBlueAdjLocation = shaderProgram.getUniformLocation("uBlueAdj")
        uContourThinFaceLocation = shaderProgram.getUniformLocation("uContourThinFace")
        uDebugModeLocation = shaderProgram.getUniformLocation("uDebugMode")
        uFacePointsLocation = shaderProgram.getUniformLocation("uFacePoints")
        uAspectRatioLocation = shaderProgram.getUniformLocation("uAspectRatio")
        uUseGpupixelWarpLocation = shaderProgram.getUniformLocation("uUseGpupixelWarp")
    }

    /**
     * 设置调试模式
     * @param mode 0=正常, 1=Skin Mask, 2=Warp Offset,
     *             3=BigEye Radius, 4=ThinFace Radius, 5=All Warp
     */
    fun setDebugMode(mode: Int) {
        debugMode = mode
    }

    private fun lipTintColorByIndex(index: Int): FloatArray {
        return when (index.coerceIn(0, 11)) {
            1 -> floatArrayOf(0.77f, 0.20f, 0.26f)
            2 -> floatArrayOf(1.00f, 0.50f, 0.31f)
            3 -> floatArrayOf(0.88f, 0.32f, 0.49f)
            4 -> floatArrayOf(1.00f, 0.42f, 0.62f)
            5 -> floatArrayOf(0.61f, 0.14f, 0.21f)
            6 -> floatArrayOf(1.00f, 0.63f, 0.48f)
            7 -> floatArrayOf(0.80f, 0.36f, 0.36f)
            8 -> floatArrayOf(0.86f, 0.08f, 0.24f)
            9 -> floatArrayOf(1.00f, 0.71f, 0.76f)
            10 -> floatArrayOf(0.70f, 0.13f, 0.13f)
            11 -> floatArrayOf(1.00f, 0.08f, 0.58f)
            else -> floatArrayOf(0.83f, 0.46f, 0.49f)
        }
    }

    private fun blushTintColorByFamily(family: Int): FloatArray {
        return when (family.coerceIn(0, 2)) {
            1 -> floatArrayOf(1.00f, 0.62f, 0.45f)
            2 -> floatArrayOf(0.67f, 0.31f, 0.52f)
            else -> floatArrayOf(1.00f, 0.56f, 0.67f)
        }
    }

    /**
     * 渲染 FaceMakeupPass（唇色 + 腮红）
     * 使用 GPUPixel 风格三角网格 + 纹理贴图，并以纹理 alpha 精确控制嘴唇/脸颊区域。
     */
    private fun renderFaceMakeupPass(
        inputTextureId: Int,
        fboPing: Framebuffer,
        fboPong: Framebuffer
    ): Int {
        val lipBounds = FrameBounds(502.5f, 710f, 262.5f, 167.5f)
        val blushBounds = FrameBounds(395f, 520f, 489f, 209f)

        if (!faceMakeupPassCompiled) {
            faceMakeupPassCompiled = faceMakeupPass.compileFromAssets(
                "shaders/makeup_vertex.glsl",
                "shaders/makeup_fragment.glsl"
            )
            Logger.d(TAG, "FaceMakeupPass compiled: $faceMakeupPassCompiled")
            if (!faceMakeupPassCompiled) {
                throw IllegalStateException("face_makeup: failed to compile FaceMakeupPass")
            }

            faceMakeupPass.loadMakeupTexture(
                type = FaceMakeupPass.MakeupType.LIP,
                assetPath = "makeup/mouth.png",
                bounds = lipBounds
            )
            faceMakeupPass.loadMakeupTexture(
                type = FaceMakeupPass.MakeupType.BLUSH,
                assetPath = "makeup/blusher.png",
                bounds = blushBounds
            )
        }

        // [帧同步] FaceMakeupPass 使用帧同步后的关键点（已通过 updateSyncedFacePoints106 写入 facePointsBuffer）
        if (hasFace > 0.5f) {
            val facePoints = toStandardUvFacePoints()
            if (frameSyncEnabled) {
                // 帧同步路径：CPU 侧已完成预测补偿，直接写入
                faceMakeupPass.updateFaceLandmarksSynced(facePoints)
            } else {
                // 旧路径：使用双缓冲避免读写竞争，配合插值消除甩飞
                faceMakeupPass.resetFrameSync()
                faceMakeupPass.updateFaceLandmarks(facePoints)
            }
        }

        var currentTextureId = inputTextureId
        var nextOutputFbo = if (fboPing.getTextureId() == currentTextureId) fboPong else fboPing

        if (lipColorStrength > 0.001f) {
            faceMakeupPass.setIntensity(lipColorStrength)
            faceMakeupPass.setBlendMode(FaceMakeupPass.BLEND_MODE_MULTIPLY)
            faceMakeupPass.setTintColor(lipTintColorByIndex(lipColorIndex))
            val rendered = faceMakeupPass.render(
                inputTextureId = currentTextureId,
                outputFbo = nextOutputFbo,
                makeupType = FaceMakeupPass.MakeupType.LIP
            )
            if (!rendered) {
                throw IllegalStateException("face_makeup: lip pass render returned false")
            }
            currentTextureId = nextOutputFbo.getTextureId()
            nextOutputFbo = if (nextOutputFbo === fboPing) fboPong else fboPing
        }

        if (blushStrength > 0.001f) {
            faceMakeupPass.setIntensity(blushStrength)
            faceMakeupPass.setBlendMode(FaceMakeupPass.BLEND_MODE_OVERLAY)
            faceMakeupPass.setTintColor(blushTintColorByFamily(blushColorFamily))
            val rendered = faceMakeupPass.render(
                inputTextureId = currentTextureId,
                outputFbo = nextOutputFbo,
                makeupType = FaceMakeupPass.MakeupType.BLUSH
            )
            if (!rendered) {
                throw IllegalStateException("face_makeup: blush pass render returned false")
            }
            currentTextureId = nextOutputFbo.getTextureId()
        }

        return currentTextureId
    }

    fun setFaceMakeupEnabled(enabled: Boolean) {
        faceMakeupEnabled = enabled
        Logger.d(TAG, "FaceMakeupPass enabled: $enabled")
    }

    /**
     * 离屏渲染（用于拍照）
     *
     * 与预览保持一致的完整多 Pass 管线：
     * 1. executeBeautyPasses: CopyPass + BeautyUnitPass（磨皮+美白+LUT）
     * 2. renderFaceMakeupPass: 唇色 + 腮红
     * 3. renderMainShaderFromFbo: 美型 + 调色 → 输出到显式指定的 FBO
     *
     * @param width 渲染宽度
     * @param height 渲染高度
     * @param outputFramebufferId 输出目标 FBO，由调用方显式传入
     */
    fun renderBeautyMultiPass(
        width: Int,
        height: Int,
        outputFramebufferId: Int,
        skipCopyPass: Boolean = false
    ) {
        val targetFboId = if (outputFramebufferId > 0) outputFramebufferId else 0

        GLES20.glViewport(0, 0, width, height)
        clearLastRenderError()
        val activeStyle = styleEffectShader.getActiveStyle()

        val beautyPassSuccess = executeBeautyPasses(skipCopyPass)

        val finalInputTextureId = if (beautyPassSuccess) {
            beautyPassOutputTextureId
        } else {
            externalTextureId
        }

        var currentTextureId = finalInputTextureId
        if (faceMakeupEnabled && hasFace > 0.5f && (lipColorStrength > 0.001f || blushStrength > 0.001f)) {
            val ping = fboPing
            val pong = fboPong
            if (ping != null && pong != null && ping.isInitialized && pong.isInitialized) {
                currentTextureId = renderFaceMakeupPass(finalInputTextureId, ping, pong)
            }
        }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, targetFboId)
        GLES20.glViewport(0, 0, width, height)

        if (activeStyle != StyleEffect.NONE) {
            // 先渲染主 Shader 到中间 FBO，再把风格特效输出到显式目标 FBO
            updateFramebufferSize(width, height)
            val fbo = intermediateFbo
            if (fbo != null && fbo.isInitialized) {
                renderMainShaderFromFbo(currentTextureId, fbo, width, height)

                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, targetFboId)
                GLES20.glViewport(0, 0, width, height)
                GLES20.glDisableVertexAttribArray(aPositionLocation)
                GLES20.glDisableVertexAttribArray(aTextureCoordLocation)
                styleEffectShader.render(fbo.getTextureId(), width, height)
            } else {
                Logger.e(TAG, "Style effect FBO not ready, fallback to MainShader2D")
                renderMainShaderFromFbo2D(
                    currentTextureId,
                    width,
                    height,
                    skipBeautyEffects = beautyPassSuccess
                )
            }
        } else {
            renderMainShaderFromFbo2D(
                currentTextureId,
                width,
                height,
                skipBeautyEffects = beautyPassSuccess
            )
        }
    }

    override fun release() {
        Logger.d(TAG, "Releasing BeautyRenderer")
        intermediateFbo?.release()
        intermediateFbo = null
        fboPing = null
        fboPong = null
        styleEffectShader.release()
        copyPass.release()
        beautyUnitPass.release()
        faceMakeupPass.release()
        lutTextureLoader.release()
        fboPool.releaseAll()
        shaderProgram2D.release()
        shaderProgram2DCompiled = false
        clearLastRenderError()
        // [帧同步] 重置帧同步状态
        frameSyncEnabled = true
        super.release()
    }
}
