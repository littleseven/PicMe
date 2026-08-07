package com.mamba.picme.capability

import android.graphics.Bitmap
import com.mamba.picme.beauty.api.BeautyProcessor
import com.mamba.picme.beauty.api.BeautySettings
import com.mamba.picme.beauty.api.Face
import com.mamba.picme.beauty.api.facedetect.FaceDetector
import kotlinx.coroutines.runBlocking
import android.graphics.Rect
import android.graphics.PointF

/**
 * 美颜业务能力接口
 *
 * 职责：提供美颜调节的标准化能力，支持生产和测试环境调用。
 * 
 * 设计原则：
 * 1. 独立运行 - 不依赖 LLM，按协议发送指令即可执行
 * 2. 类型安全 - 所有参数和返回值都有明确类型
 * 3. 可组合性 - 单个操作可组合成复杂测试流程
 *
 * 使用示例：
 * ```kotlin
 * val capability = BeautyCapability(beautyProcessor, faceDetector)
 * 
 * // 调整磨皮参数
 * val result1 = capability.adjustSmoothing(smoothness = 80f)
 * assert(result1.success)
 * 
 * // 批量测试多组参数
 * val results = capability.batchTest(listOf(
 *     BeautyParameters(smooth = 50f),
 *     BeautyParameters(smooth = 70f),
 *     BeautyParameters(smooth = 90f)
 * ))
 * ```
 */
class BeautyCapability(
    private val beautyProcessor: BeautyProcessor,
    private val faceDetector: FaceDetector? = null
) {

    /**
     * 调整磨皮参数
     *
     * @param smoothness 磨皮强度 0-100
     * @param inputBitmap 输入图像（可选，默认创建测试图）
     * @return 处理结果
     */
    fun adjustSmoothing(
        smoothness: Float,
        inputBitmap: Bitmap? = null
    ): CapabilityResult<Bitmap> = runCatching {
        val bitmap = inputBitmap ?: createTestBitmap()
        val faces = detectFaces(bitmap)

        val result = runBlocking {
            beautyProcessor.applySmoothing(bitmap, smoothness, faces)
        }

        CapabilityResult.success(result)
    }.getOrElse { exception ->
        CapabilityResult.failure("Adjust smoothing failed: ${exception.message}", exception)
    }

    /**
     * 调整美白参数
     */
    fun adjustWhitening(
        whitening: Float,
        inputBitmap: Bitmap? = null
    ): CapabilityResult<Bitmap> = runCatching {
        val bitmap = inputBitmap ?: createTestBitmap()
        val faces = detectFaces(bitmap)

        val result = runBlocking {
            beautyProcessor.applyWhitening(bitmap, whitening, faces)
        }

        CapabilityResult.success(result)
    }.getOrElse { exception ->
        CapabilityResult.failure("Adjust whitening failed: ${exception.message}", exception)
    }

    /**
     * 调整瘦脸参数
     */
    fun adjustSlimFace(
        slimFace: Float,
        inputBitmap: Bitmap? = null
    ): CapabilityResult<Bitmap> = runCatching {
        val bitmap = inputBitmap ?: createTestBitmap()
        val faces = detectFaces(bitmap)

        val result = runBlocking {
            beautyProcessor.applySlimFace(bitmap, slimFace, faces, isFrontCamera = false)
        }

        CapabilityResult.success(result)
    }.getOrElse { exception ->
        CapabilityResult.failure("Adjust slim face failed: ${exception.message}", exception)
    }

    /**
     * 调整大眼参数
     */
    fun adjustBigEyes(
        bigEyes: Float,
        inputBitmap: Bitmap? = null
    ): CapabilityResult<Bitmap> = runCatching {
        val bitmap = inputBitmap ?: createTestBitmap()
        val faces = detectFaces(bitmap)

        val result = runBlocking {
            beautyProcessor.applyBigEyes(bitmap, bigEyes, faces)
        }

        CapabilityResult.success(result)
    }.getOrElse { exception ->
        CapabilityResult.failure("Adjust big eyes failed: ${exception.message}", exception)
    }

    /**
     * 应用所有美颜效果
     */
    fun applyAllEffects(
        settings: BeautySettings,
        inputBitmap: Bitmap? = null
    ): CapabilityResult<Bitmap> = runCatching {
        val bitmap = inputBitmap ?: createTestBitmap()
        val faces = detectFaces(bitmap)

        val result = runBlocking {
            beautyProcessor.applyAllEffects(bitmap, settings, faces)
        }

        CapabilityResult.success(result)
    }.getOrElse { exception ->
        CapabilityResult.failure("Apply all effects failed: ${exception.message}", exception)
    }

    /**
     * 批量测试多组参数
     */
    fun batchTest(paramSets: List<BeautySettings>): List<CapabilityResult<Bitmap>> {
        return paramSets.map { settings ->
            applyAllEffects(settings)
        }
    }

    /**
     * 人脸检测
     */
    fun detectFaces(inputBitmap: Bitmap): List<Face> {
        if (faceDetector == null) {
            return emptyList()
        }

        return runCatching {
            val result = faceDetector!!.detect(inputBitmap, rotationDegrees = 0, lensFacing = 0)
            if (result != null) {
                // 从 landmarks106 FloatArray 构建 landmarks Map
                val landmarks = mutableMapOf<Int, PointF>()
                val landmarks106 = result.landmarks106
                for (i in landmarks106.indices step 2) {
                    if (i + 1 < landmarks106.size) {
                        landmarks[i / 2] = PointF(landmarks106[i], landmarks106[i + 1])
                    }
                }
                // 构建一个默认的 boundingBox（使用全图范围作为占位）
                val boundingBox = Rect(0, 0, inputBitmap.width, inputBitmap.height)
                listOf(
                    Face(
                        boundingBox = boundingBox,
                        landmarks = landmarks
                    )
                )
            } else {
                emptyList()
            }
        }.getOrElse { emptyList() }
    }

    /**
     * 创建测试用 Bitmap
     */
    private fun createTestBitmap(): Bitmap {
        return Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888).apply {
            eraseColor(0xFF808080.toInt())
        }
    }
}

/**
 * 能力执行结果
 *
 * 用于标准化能力调用的返回结果，支持成功/失败状态。
 */
data class CapabilityResult<out T>(
    val success: Boolean,
    val data: T? = null,
    val error: String? = null,
    val stackTrace: String? = null
) {
    companion object {
        fun <T> success(data: T): CapabilityResult<T> = CapabilityResult(
            success = true,
            data = data
        )
        
        fun <T> failure(error: String, exception: Throwable? = null): CapabilityResult<T> = CapabilityResult(
            success = false,
            error = error,
            stackTrace = exception?.stackTraceToString()
        )
    }
    
    fun errorOrNull(): String? = error
    fun dataOrNull(): T? = data
}
