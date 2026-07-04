package com.mamba.picme.beauty.render

import android.content.Context
import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import com.mamba.picme.beauty.api.BeautyParams
import com.mamba.picme.beauty.api.FaceData
import com.mamba.picme.beauty.api.Logger
import com.mamba.picme.beauty.api.PhotoProcessException
import com.mamba.picme.beauty.api.PhotoProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 拍照 GPU 化处理实现
 *
 * 核心设计：
 * 1. 创建独立 EGL 上下文（Pbuffer Surface），与预览上下文隔离
 * 2. Bitmap → GL_TEXTURE_2D（通过 texImage2D）
 * 3. 复用 BeautyRenderer 多 Pass 管线，但输入从 OES 外部纹理改为 2D 纹理
 * 4. FBO 输出通过 glReadPixels 读取为 Bitmap
 * 5. 完整资源释放
 *
 * 线程安全：此类的所有方法应在同一线程调用（建议后台线程）
 */
class PhotoProcessorImpl(private val context: Context) : PhotoProcessor {

    companion object {
        private const val TAG = "PhotoProcessor"
    }

    // EGL 资源
    private val eglCore = EGLCore()
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var isEglInitialized = false

    // 美颜渲染器（复用预览的 BeautyRenderer）
    private var beautyRenderer: BeautyRenderer? = null

    // FBO 资源（离屏渲染输出）
    private var fboId: Int = 0
    private var fboTextureId: Int = 0
    private var fboWidth: Int = 0
    private var fboHeight: Int = 0

    // PBO 资源（异步读取优化）- 注意：PBO 需要 OpenGL ES 3.0+
    // 当前使用 glReadPixels 直接读取，后续可升级到 PBO
    private var usePbo: Boolean = false

    // 输入纹理
    private var inputTextureId: Int = 0

    // 全屏四边形顶点缓冲
    private val vertexBuffer by lazy {
        val vertices = floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f
        )
        ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(vertices).position(0) }
    }

    private val textureBuffer by lazy {
        val coords = floatArrayOf(
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f
        )
        ByteBuffer.allocateDirect(coords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(coords).position(0) }
    }

    override fun process(bitmap: Bitmap, params: BeautyParams, faceData: FaceData?): Bitmap {
        val startTime = System.currentTimeMillis()

        try {
            // 1. 初始化 EGL 环境（如果未初始化）并绑定到当前线程
            ensureEglInitialized()

            // 确保 EGL 上下文在当前线程激活（关键修复：线程池可能导致线程切换）
            if (!eglCore.makeCurrent(eglSurface, eglContext)) {
                Logger.w(TAG, "Failed to rebind EGL context, reinitializing...")
                // 重置状态并重新初始化
                isEglInitialized = false
                ensureEglInitialized()
            }

            // 2. 检查纹理尺寸限制（必须在 EGL 上下文绑定后调用）
            val maxTextureSize = getMaxTextureSize()
            if (maxTextureSize <= 0) {
                throw PhotoProcessException("Failed to query GL_MAX_TEXTURE_SIZE (returned $maxTextureSize)")
            }
            if (bitmap.width > maxTextureSize || bitmap.height > maxTextureSize) {
                throw PhotoProcessException(
                    "Bitmap size ${bitmap.width}x${bitmap.height} exceeds max texture size $maxTextureSize"
                )
            }

            // 3. 创建/复用 FBO
            ensureFbo(bitmap.width, bitmap.height)

            // 4. 上传 Bitmap 到纹理
            uploadBitmapToTexture(bitmap)

            // 5. 初始化 BeautyRenderer（如果未初始化）
            val renderer = ensureBeautyRenderer()

            // 6. 设置美颜参数
            applyBeautyParams(renderer, params, faceData)

            // 7. 执行渲染（多 Pass）
            val outputTexture = renderPhoto(renderer, params, bitmap.width, bitmap.height)

            // 8. 读取 FBO 到 Bitmap
            val result = readPixelsToBitmap(bitmap.width, bitmap.height, outputTexture)

            return result
        } catch (e: PhotoProcessException) {
            throw e
        } catch (e: Exception) {
            Logger.e(TAG, "GPU photo processing failed", e)
            throw PhotoProcessException(e.message ?: "Unknown error", e)
        }
    }

    /**
     * 初始化 EGL 环境（Pbuffer Surface）
     *
     * [关键修复] Pbuffer Surface 尺寸必须足够大以支持离屏 FBO 渲染。
     * 某些 GPU 驱动（Mali/Adreno）要求 Pbuffer 尺寸至少与最大的 FBO 一致，
     * 否则渲染到 FBO 时会报 GL_INVALID_FRAMEBUFFER_OPERATION (1286)。
     * 使用 4096x4096 确保覆盖所有可能的拍照分辨率。
     */
    private fun ensureEglInitialized() {
        if (isEglInitialized) {
            Logger.d(TAG, "EGL already initialized, reusing context")
            return
        }

        Logger.d(TAG, "Initializing EGL for photo processing on thread ${Thread.currentThread().name}")

        // [关键修复] 上下文失效/线程切换后，旧 EGL 上下文下的 Shader/FBO/纹理全部失效，
        // 必须在创建新上下文前彻底释放，否则 BeautyRenderer 会复用无效 program ID，
        // 导致渲染黑屏。
        releaseContextDependentResources()

        if (!eglCore.init()) {
            throw PhotoProcessException("EGL initialization failed")
        }

        eglContext = eglCore.createContext()
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            throw PhotoProcessException("EGL context creation failed")
        }

        // [关键修复] Pbuffer Surface 尺寸必须 >= 最大 FBO 尺寸，
        // 否则某些 GPU 驱动会拒绝渲染到 FBO
        val pbWidth = 4096
        val pbHeight = 4096
        eglSurface = eglCore.createSurface(null, pbWidth, pbHeight)
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            throw PhotoProcessException("EGL Pbuffer surface creation failed")
        }

        val makeCurrentResult = eglCore.makeCurrent(eglSurface, eglContext)
        if (!makeCurrentResult) {
            throw PhotoProcessException("eglMakeCurrent failed")
        }

        // 验证上下文绑定成功
        val glError = GLES20.glGetError()
        if (glError != GLES20.GL_NO_ERROR) {
            throw PhotoProcessException("GL error after eglMakeCurrent: $glError")
        }

        isEglInitialized = true
        Logger.d(TAG, "EGL initialized successfully for photo processing (Pbuffer=${pbWidth}x${pbHeight})")
    }

    /**
     * 释放依赖 EGL 上下文的资源。
     *
     * 当 EGL 上下文因线程切换、驱动回收等原因失效时，必须连同 ShaderProgram、FBO、
     * 纹理一起重建，否则后续渲染会使用已失效的 OpenGL 对象句柄，输出黑屏。
     */
    private fun releaseContextDependentResources() {
        // 释放 BeautyRenderer（内部持有主 Shader 与 2D Shader）
        beautyRenderer?.release()
        beautyRenderer = null

        // 释放输入纹理
        if (inputTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(inputTextureId), 0)
            inputTextureId = 0
        }

        // 释放 FBO
        deleteFbo()

        // 释放 EGL 上下文与 Surface
        if (isEglInitialized) {
            eglCore.clearCurrent()
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglCore.eglDisplay, eglSurface)
                eglSurface = EGL14.EGL_NO_SURFACE
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglCore.eglDisplay, eglContext)
                eglContext = EGL14.EGL_NO_CONTEXT
            }
            eglCore.release()
        }
    }

    /**
     * 创建/复用 FBO
     */
    private fun ensureFbo(width: Int, height: Int) {
        if (fboId != 0 && fboWidth == width && fboHeight == height) {
            return
        }

        // 删除旧 FBO
        deleteFbo()

        // 创建 FBO 纹理
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        fboTextureId = textures[0]

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTextureId)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
            width, height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        // 创建 FBO
        val fbos = IntArray(1)
        GLES20.glGenFramebuffers(1, fbos, 0)
        fboId = fbos[0]

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, fboTextureId, 0
        )

        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            deleteFbo()
            throw PhotoProcessException("Framebuffer incomplete: $status")
        }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        fboWidth = width
        fboHeight = height

        // PBO 初始化（当前禁用，需要 OpenGL ES 3.0）
        // if (usePbo) { initPbo(width, height) }

        Logger.d(TAG, "FBO created: ${width}x${height}")
    }

    // PBO 相关方法已移除（需要 OpenGL ES 3.0）
    // 使用 glReadPixels 直接读取

    /**
     * 上传 Bitmap 到 OpenGL 2D 纹理
     */
    private fun uploadBitmapToTexture(bitmap: Bitmap) {
        if (inputTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(inputTextureId), 0)
        }

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        inputTextureId = textures[0]

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTextureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        Logger.d(TAG, "Bitmap uploaded to texture")
    }

    /**
     * 初始化 BeautyRenderer
     */
    private fun ensureBeautyRenderer(): BeautyRenderer {
        var renderer = beautyRenderer
        if (renderer == null) {
            renderer = BeautyRenderer(context)
            renderer.onInit()
            beautyRenderer = renderer
            Logger.d(TAG, "BeautyRenderer initialized for photo processing")
        }
        return renderer
    }

    /**
     * 应用美颜参数到 BeautyRenderer
     *
     * [关键修复] 照片路径**不需要**Y轴翻转。
     * GLUtils.texImage2D(bitmap) 上传时，Bitmap 左上角(Y=0)已对齐纹理左下角(V=0)，
     * 因此纹理坐标与 Bitmap 坐标天然一致。预览路径需要 flip 是因为 SurfaceTexture
     * 的 textureMatrix 自带变换，但照片路径走 2D 纹理 FBO，无 textureMatrix，
     * 若再 flip 会导致瘦脸/唇色/腮红全部上下错位。
     */
    private fun applyBeautyParams(renderer: BeautyRenderer, params: BeautyParams, faceData: FaceData?) {
        renderer.setTexelSize(fboWidth, fboHeight)

        // 基础美颜参数
        renderer.updateBeautyParams(
            smoothing = params.smoothing,
            whitening = params.whitening,
            sharpen = 0f,
            bigEyes = params.bigEyes,
            slimFace = params.slimFace,
            lipColor = params.lipColor,
            lipColorIndex = params.lipColorIndex,
            blush = params.blush,
            blushColorFamily = params.blushColorFamily
        )

        // 专业调色参数
        renderer.setColorGradeParams(
            exposure = params.exposure,
            contrast = params.contrast,
            saturation = params.saturation,
            temperature = params.temperature,
            tint = params.tint,
            brightness = params.brightness,
            redAdj = params.redAdjustment,
            greenAdj = params.greenAdjustment,
            blueAdj = params.blueAdjustment
        )

        // 色调滤镜
        renderer.updateColorMatrix(params.colorMatrix)

        // 风格特效
        renderer.setStyleEffect(params.styleEffect)
        renderer.setStyleParams(
            intensity = params.styleIntensity,
            toonThreshold = params.toonThreshold,
            toonQuantizationLevels = params.toonQuantizationLevels,
            sketchEdgeStrength = params.sketchEdgeStrength,
            posterizeColorLevels = params.posterizeColorLevels,
            embossIntensity = params.embossIntensity,
            crosshatchSpacing = params.crosshatchSpacing,
            crosshatchLineWidth = params.crosshatchLineWidth
        )

        // [DEBUG] 如需调试可视化，可临时设置为 5（GPUPixel warp 可视化）
        // renderer.setDebugMode(5)
        // Logger.d(TAG, "[DEBUG] Debug mode 5 enabled (GPUPixel warp visualization)")

        // 人脸数据
        if (faceData != null && faceData.hasFace) {
            // 照片路径：GLUtils.texImage2D 上传 Bitmap 后，纹理 T=0 对应图像顶部，
            // 与 FaceData 的图像坐标系（Y=0 在顶部）天然对齐，无需翻转。
            // 预览路径需翻转是因为 SurfaceTexture 的纹理坐标是上下颠倒的。
            renderer.updateFaceWarpParams(
                faceCenterX = faceData.faceCenterX,
                faceCenterY = faceData.faceCenterY,
                leftEyeX = faceData.leftEyeX,
                leftEyeY = faceData.leftEyeY,
                rightEyeX = faceData.rightEyeX,
                rightEyeY = faceData.rightEyeY,
                mouthCenterX = faceData.mouthCenterX,
                mouthCenterY = faceData.mouthCenterY,
                mouthLeftX = faceData.mouthLeftX,
                mouthLeftY = faceData.mouthLeftY,
                mouthRightX = faceData.mouthRightX,
                mouthRightY = faceData.mouthRightY,
                upperLipCenterX = faceData.upperLipCenterX,
                upperLipCenterY = faceData.upperLipCenterY,
                lowerLipCenterX = faceData.lowerLipCenterX,
                lowerLipCenterY = faceData.lowerLipCenterY,
                faceRadius = faceData.faceRadius,
                hasFace = true
            )

            if (faceData.lipOuterPoints.isNotEmpty() || faceData.lipInnerPoints.isNotEmpty()) {
                renderer.updateLipMaskPoints(faceData.lipOuterPoints, faceData.lipInnerPoints)
            }

            if (faceData.leftCheekPoints.isNotEmpty() || faceData.rightCheekPoints.isNotEmpty()) {
                renderer.updateCheekContourPoints(faceData.leftCheekPoints, faceData.rightCheekPoints)
            }

            if (faceData.landmarks106 != null) {
                renderer.updateFacePoints106(faceData.landmarks106)
                renderer.setUseGpupixelWarp(true)
                Logger.d(TAG, "Photo: using GPUPixel warp (landmarks106 available)")
            } else {
                renderer.setUseGpupixelWarp(false)
                Logger.d(TAG, "Photo: using traditional warp (no landmarks106)")
            }
        } else {
            // 无人脸：重置人脸状态（默认值已基于 UV 坐标系，无需翻转）
            renderer.updateFaceWarpParams(
                faceCenterX = 0.5f, faceCenterY = 0.5f,
                leftEyeX = 0.4f, leftEyeY = 0.45f,
                rightEyeX = 0.6f, rightEyeY = 0.45f,
                mouthCenterX = 0.5f, mouthCenterY = 0.62f,
                mouthLeftX = 0.42f, mouthLeftY = 0.62f,
                mouthRightX = 0.58f, mouthRightY = 0.62f,
                upperLipCenterX = 0.5f, upperLipCenterY = 0.60f,
                lowerLipCenterX = 0.5f, lowerLipCenterY = 0.66f,
                faceRadius = 0.18f, hasFace = false
            )
        }
    }

    /**
     * 执行拍照渲染
     *
     * 拍照路径专用渲染流程（与预览保持一致）：
     * 1. 输入是 2D 纹理（Bitmap 上传），跳过 CopyPass（无需 OES → 2D 转换）
     * 2. 执行 BeautyUnitPass（磨皮+美白+LUT）
     * 3. 执行 FaceMakeupPass（唇色+腮红）
     * 4. 执行 MainShader（美型+调色）→ 输出到 FBO
     */
    private fun renderPhoto(renderer: BeautyRenderer, params: BeautyParams, width: Int, height: Int): Int {
        // 设置 viewport
        GLES20.glViewport(0, 0, width, height)

        // [关键修复] 调用完整的渲染管线，确保与预览一致
        // 这会执行：BeautyUnitPass → FaceMakeupPass → MainShader

        // 设置输入纹理 ID（让 BeautyRenderer 从该纹理采样）
        renderer.setExternalTextureId(inputTextureId)

        // 绑定我们的 FBO 作为渲染目标
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)

        // 清屏
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // 判断是否需要完整多 Pass 管线
        // [关键修复] 美颜/美型/妆容在主 Shader 中已支持人脸区域 mask，不会污染黑色/暗色背景；
        // BeautyUnitPass（LUT 磨皮+美白）缺少人脸 mask，会把暗部背景误判为肤色并染成绿/青色。
        // 因此照片路径仅在需要风格特效（Toon/Sketch 等）时才走多 Pass。
        val needMultiPass = params.styleEffect != StyleEffect.NONE

        if (needMultiPass) {
            renderer.renderBeautyMultiPass(
                width = width,
                height = height,
                outputFramebufferId = fboId,
                skipCopyPass = true
            )
        } else {
            // 主 Shader 已覆盖磨皮/美白/美型/妆容/调色/滤镜，且对非人脸区域保持原色
            renderer.renderMainShaderFromFbo2D(inputTextureId, width, height)
        }

        // 检查 GL 错误
        val glError = GLES20.glGetError()
        if (glError != GLES20.GL_NO_ERROR) {
            Logger.e(TAG, "GL error after renderBeautyMultiPass: $glError")
        }

        // 解绑 FBO
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        return fboTextureId
    }

    /**
     * 读取 FBO 像素到 Bitmap
     */
    private fun readPixelsToBitmap(width: Int, height: Int, textureId: Int): Bitmap {
        return readPixelsDirect(width, height)
    }

    /**
     * 直接同步读取
     */
    private fun readPixelsDirect(width: Int, height: Int): Bitmap {
        // [关键修复] 必须绑定 FBO 才能读取其内容
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)

        val buffer = ByteBuffer.allocateDirect(width * height * 4)
        buffer.order(ByteOrder.nativeOrder())

        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)

        // 检查 GL 错误
        val glError = GLES20.glGetError()
        if (glError != GLES20.GL_NO_ERROR) {
            Logger.e(TAG, "GL error in glReadPixels: $glError")
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        bitmap.copyPixelsFromBuffer(buffer)

        // 解绑 FBO
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        return bitmap
    }

    /**
     * 获取最大纹理尺寸
     */
    private fun getMaxTextureSize(): Int {
        val size = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, size, 0)
        return size[0]
    }

    /**
     * 删除 FBO 资源
     */
    private fun deleteFbo() {
        if (fboId != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
            fboId = 0
        }
        if (fboTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(fboTextureId), 0)
            fboTextureId = 0
        }
        // PBO 资源释放（当前未使用）
        fboWidth = 0
        fboHeight = 0
    }

    override fun release() {
        Logger.d(TAG, "Releasing PhotoProcessorImpl")

        // 释放 BeautyRenderer
        beautyRenderer?.release()
        beautyRenderer = null

        // 删除输入纹理
        if (inputTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(inputTextureId), 0)
            inputTextureId = 0
        }

        // 删除 FBO
        deleteFbo()

        // 释放 EGL
        if (isEglInitialized) {
            eglCore.clearCurrent()
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglCore.eglDisplay, eglSurface)
                eglSurface = EGL14.EGL_NO_SURFACE
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglCore.eglDisplay, eglContext)
                eglContext = EGL14.EGL_NO_CONTEXT
            }
            eglCore.release()
            isEglInitialized = false
        }

        Logger.d(TAG, "PhotoProcessorImpl released")
    }
}
