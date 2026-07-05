package com.mamba.picme.beauty.internal.facedetect.mnn

import android.graphics.Bitmap
import com.mamba.picme.agent.core.platform.mnn.MnnGlobalReleaseLock
import com.mamba.picme.beauty.api.Logger
import java.nio.ByteBuffer

/**
 * 专用 MNN MobileFaceNet 人脸 Embedding 提取器 JNI 桥接类
 *
 * 与 [MnnFaceDetector] 解耦，直接使用原生 MnnFaceEmbedder 推理：
 * - 输入：112×112 RGB
 * - 输出：512 维 L2 归一化 embedding
 *
 * 模型路径: {filesDir}/llm_models/picme-face-embedding-mnn/w600k_mbf.mnn
 */
class MnnFaceEmbedder private constructor(
    private var nativeHandle: Long,
    private val inputSize: Int,
    private val embeddingDim: Int
) {
    companion object {
        private const val TAG = "MnnFaceEmbedder"

        // [性能优化] 复用像素缓冲区和 RGB 缓冲区
        private var reusablePixels: IntArray? = null
        private var reusableRgbBuffer: ByteBuffer? = null
        private var reusableResult: FloatArray? = null

        init {
            try {
                System.loadLibrary("beauty_native")
                Logger.i(TAG, "Native library loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Logger.e(TAG, "Failed to load native library", e)
            }
        }

        private fun getPixelsBuffer(size: Int): IntArray {
            var buffer = reusablePixels
            if (buffer == null || buffer.size < size) {
                buffer = IntArray(size)
                reusablePixels = buffer
            }
            return buffer
        }

        private fun getRgbBuffer(size: Int): ByteBuffer {
            var buffer = reusableRgbBuffer
            if (buffer == null || buffer.capacity() < size) {
                buffer = ByteBuffer.allocateDirect(size)
                reusableRgbBuffer = buffer
            }
            buffer.clear()
            return buffer
        }

        private fun getResultBuffer(size: Int): FloatArray {
            var buffer = reusableResult
            if (buffer == null || buffer.size < size) {
                buffer = FloatArray(size)
                reusableResult = buffer
            }
            return buffer
        }

        /**
         * 创建 embedder 实例
         *
         * @param modelPath MNN 模型文件路径
         * @param inputSize 模型输入尺寸（正方形，默认 112）
         * @param embeddingDim 输出维度（默认 512）
         * @param inputName 输入层名称（默认 "input.1"）
         * @param outputName 优先使用的输出层名称（空则自动查找）
         */
        fun create(
            modelPath: String,
            inputSize: Int = 112,
            embeddingDim: Int = 512,
            inputName: String = "input.1",
            outputName: String = ""
        ): MnnFaceEmbedder? {
            val handle = MnnGlobalReleaseLock.withOperation {
                nativeCreate(modelPath, inputSize, embeddingDim, inputName, outputName)
            }
            return if (handle != 0L) {
                MnnFaceEmbedder(handle, inputSize, embeddingDim)
            } else {
                Logger.e(TAG, "Failed to create native MNN face embedder")
                null
            }
        }

        @JvmStatic
        private external fun nativeCreate(
            modelPath: String,
            inputSize: Int,
            embeddingDim: Int,
            inputName: String,
            outputName: String
        ): Long

        @JvmStatic
        private external fun nativeDestroy(handle: Long)

        @JvmStatic
        private external fun nativeExtract(
            handle: Long,
            imageData: ByteBuffer,
            width: Int,
            height: Int,
            channels: Int,
            outResult: FloatArray
        ): Int
    }

    /**
     * 从 Bitmap 提取人脸 embedding
     *
     * @param bitmap 输入 Bitmap（ARGB_8888），期望 112×112
     * @return 512 维 L2 归一化 embedding，失败返回 null
     */
    fun extract(bitmap: Bitmap): FloatArray? {
        if (nativeHandle == 0L) {
            Logger.w(TAG, "Embedder not initialized")
            return null
        }

        // 确保输入尺寸严格为模型输入尺寸
        val resized = if (bitmap.width != inputSize || bitmap.height != inputSize) {
            Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        } else {
            bitmap
        }

        val width = resized.width
        val height = resized.height
        val pixelCount = width * height
        val pixels = getPixelsBuffer(pixelCount)
        resized.getPixels(pixels, 0, width, 0, 0, width, height)

        val rgbBuffer = getRgbBuffer(pixelCount * 3)
        for (i in 0 until pixelCount) {
            val pixel = pixels[i]
            rgbBuffer.put(i * 3, (pixel shr 16 and 0xFF).toByte())     // R
            rgbBuffer.put(i * 3 + 1, (pixel shr 8 and 0xFF).toByte())  // G
            rgbBuffer.put(i * 3 + 2, (pixel and 0xFF).toByte())        // B
        }

        val outResult = getResultBuffer(embeddingDim)
        val written = MnnGlobalReleaseLock.withOperation {
            nativeExtract(nativeHandle, rgbBuffer, width, height, 3, outResult)
        }
        val result = if (written == embeddingDim) outResult.copyOf(embeddingDim) else null
        if (resized !== bitmap) {
            resized.recycle()
        }
        return result
    }

    fun release() {
        if (nativeHandle != 0L) {
            MnnGlobalReleaseLock.withLock {
                nativeDestroy(nativeHandle)
            }
            nativeHandle = 0L
        }
    }
}
