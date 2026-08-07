package com.mamba.picme.beauty.internal.facedetect.mnn

import android.graphics.Bitmap
import com.mamba.picme.mnn.MnnGlobalReleaseLock
import com.mamba.picme.beauty.api.Logger
import java.nio.ByteBuffer

/**
 * MNN 人脸检测器 JNI 桥接类
 *
 * 通过 JNI 调用 C++ 层的 MNN 推理引擎，支持 OpenCL GPU 加速。
 * 兼容骁龙 765G + Adreno 620（OpenCL）
 */
class MnnFaceDetector private constructor(
    private var nativeHandle: Long,
    private val inputSize: Int
) {
    companion object {
        private const val TAG = "MnnFaceDetector"

        // [性能优化] 复用像素缓冲区
        private var reusablePixels: IntArray? = null
        // [性能优化] DirectByteBuffer 零拷贝输入
        private var reusableRgbBuffer: ByteBuffer? = null
        // [性能优化] 复用结果缓冲区，避免 JNI NewFloatArray 每帧分配
        private var reusableDetectResult: FloatArray? = null
        private var reusableRetinaResult: FloatArray? = null
        private const val RETINA_RESULT_SIZE = 15
        /** 2D106 关键点结果上限：106 点 × 2 坐标。native 侧按 GetArrayLength 截断拷贝，按上限分配即可 */
        private const val DETECT_RESULT_SIZE = 106 * 2

        init {
            try {
                System.loadLibrary("beauty_native")
                // 同步 native 层日志开关状态
                val logEnabled = Logger.isLogEnabled(TAG)
                nativeSetLogEnabled(logEnabled)
                Logger.i(TAG, "Native library loaded successfully, nativeLogEnabled=$logEnabled")
            } catch (e: UnsatisfiedLinkError) {
                Logger.e(TAG, "Failed to load native library", e)
            }
        }

        /**
         * 释放 companion 级复用缓冲区（含 DirectByteBuffer 离堆内存，GC 无法回收）。
         *
         * 由 FaceDetectorManager.release() 在检测链路整体退出时调用；
         * 缓冲区为按需重建，后续检测不受影响。
         */
        @Synchronized
        fun releaseSharedBuffers() {
            reusablePixels = null
            reusableRgbBuffer = null
            reusableDetectResult = null
            reusableRetinaResult = null
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

        private fun getDetectResult(size: Int): FloatArray {
            var buffer = reusableDetectResult
            if (buffer == null || buffer.size < size) {
                buffer = FloatArray(size)
                reusableDetectResult = buffer
            }
            return buffer
        }

        private fun getRetinaResult(): FloatArray {
            var buffer = reusableRetinaResult
            if (buffer == null) {
                buffer = FloatArray(RETINA_RESULT_SIZE)
                reusableRetinaResult = buffer
            }
            return buffer
        }

        /**
         * 设置 native 层日志开关。
         * 根据 Logger 的模块开关状态同步控制 native 层日志输出。
         */
        fun setNativeLogEnabled(enabled: Boolean) {
            nativeSetLogEnabled(enabled)
        }

        @JvmStatic
        private external fun nativeSetLogEnabled(enabled: Boolean)

        /**
         * 创建检测器实例
         *
         * @param modelPath MNN 模型文件路径 (.mnn)
         * @param inputSize 模型输入尺寸（正方形）
         * @param useGpu 是否尝试使用 OpenCL GPU
         * @param inputName 输入层名称
         * @param outputNames 输出层名称列表（RetinaFace 多输出）
         * @return 检测器实例，失败返回 null
         */
        fun create(
            modelPath: String,
            inputSize: Int,
            useGpu: Boolean = true,
            inputName: String = "input.1",
            outputNames: Array<String> = emptyArray()
        ): MnnFaceDetector? {
            val handle = MnnGlobalReleaseLock.withOperation {
                nativeCreate(modelPath, inputSize, useGpu, inputName, outputNames)
            }
            return if (handle != 0L) {
                MnnFaceDetector(handle, inputSize)
            } else {
                Logger.e(TAG, "Failed to create native MNN detector")
                null
            }
        }

        @JvmStatic
        private external fun nativeCreate(
            modelPath: String,
            inputSize: Int,
            useGpu: Boolean,
            inputName: String,
            outputNames: Array<String>
        ): Long

        @JvmStatic
        private external fun nativeDestroy(handle: Long)

        @JvmStatic
        private external fun nativeReleaseSession(handle: Long)

        @JvmStatic
        private external fun nativeRebuildSession(handle: Long): Boolean

        @JvmStatic
        private external fun nativeReleaseModelBuffer(handle: Long)

        @JvmStatic
        private external fun nativeDetect(
            handle: Long,
            imageData: ByteBuffer,
            width: Int,
            height: Int,
            channels: Int,
            outResult: FloatArray
        ): Int

        @JvmStatic
        private external fun nativeDetectRetinaFace(
            handle: Long,
            imageData: ByteBuffer,
            width: Int,
            height: Int,
            channels: Int,
            confidenceThreshold: Float,
            nmsThreshold: Float,
            outResult: FloatArray
        ): Boolean

        @JvmStatic
        private external fun nativeDetectRetinaFaces(
            handle: Long,
            imageData: ByteBuffer,
            width: Int,
            height: Int,
            channels: Int,
            confidenceThreshold: Float,
            nmsThreshold: Float
        ): Array<FaceBox>

        @JvmStatic
        private external fun nativeDetectRetinaFaceFromNv21(
            handle: Long,
            nv21Data: ByteBuffer,
            width: Int,
            height: Int,
            rotationDegrees: Int,
            confidenceThreshold: Float,
            nmsThreshold: Float,
            outResult: FloatArray
        ): Boolean

        @JvmStatic
        private external fun nativeDetectFromNv21(
            handle: Long,
            nv21Data: ByteBuffer,
            width: Int,
            height: Int,
            rotationDegrees: Int,
            outResult: FloatArray
        ): Int

        @JvmStatic
        private external fun nativeDetectLandmarksFromNv21(
            handle: Long,
            nv21Data: ByteBuffer,
            nv21Width: Int,
            nv21Height: Int,
            rotationDegrees: Int,
            roiLeft: Int,
            roiTop: Int,
            roiRight: Int,
            roiBottom: Int,
            outResult: FloatArray
        ): Int
    }

    /**
     * 执行单输出检测（2D106 关键点）
     *
     * @param bitmap 输入 Bitmap（ARGB_8888）
     * @return 检测结果数组，未检测到返回 null
     */
    fun detect(bitmap: Bitmap): FloatArray? {
        if (nativeHandle == 0L) {
            Logger.w(TAG, "Detector not initialized")
            return null
        }

        val width = bitmap.width
        val height = bitmap.height
        val pixelCount = width * height
        val pixels = getPixelsBuffer(pixelCount)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // 将 ARGB IntArray 写入 DirectByteBuffer（零拷贝 JNI 传输）
        val rgbBuffer = getRgbBuffer(pixelCount * 3)
        for (i in 0 until pixelCount) {
            val pixel = pixels[i]
            rgbBuffer.put(i * 3, (pixel shr 16 and 0xFF).toByte())     // R
            rgbBuffer.put(i * 3 + 1, (pixel shr 8 and 0xFF).toByte())  // G
            rgbBuffer.put(i * 3 + 2, (pixel and 0xFF).toByte())        // B
        }

        val outResult = getDetectResult(DETECT_RESULT_SIZE)
        val written = MnnGlobalReleaseLock.withOperation {
            nativeDetect(nativeHandle, rgbBuffer, width, height, 3, outResult)
        }
        return if (written > 0) outResult.copyOf(written) else null
    }

    /**
     * RetinaFace 检测（多输出：bbox + score + 5 landmarks）
     * @return [x1, y1, x2, y2, score, lx1, ly1, lx2, ly2, lx3, ly3, lx4, ly4, lx5, ly5]
     */
    fun detectRetinaFace(bitmap: Bitmap, confidenceThreshold: Float = 0.5f, nmsThreshold: Float = 0.4f): FloatArray? {
        val faces = detectRetinaFaces(bitmap, confidenceThreshold, nmsThreshold)
        return faces.firstOrNull()?.toFloatArray()
    }

    /**
     * RetinaFace 多脸检测
     * @return 所有人脸 FaceBox 列表，按置信度从高到低排序
     */
    fun detectRetinaFaces(bitmap: Bitmap, confidenceThreshold: Float = 0.5f, nmsThreshold: Float = 0.4f): List<FaceBox> {
        if (nativeHandle == 0L) {
            Logger.w(TAG, "Detector not initialized")
            return emptyList()
        }

        val width = bitmap.width
        val height = bitmap.height
        val pixelCount = width * height
        val pixels = getPixelsBuffer(pixelCount)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val rgbBuffer = getRgbBuffer(pixelCount * 3)
        for (i in 0 until pixelCount) {
            val pixel = pixels[i]
            rgbBuffer.put(i * 3, (pixel shr 16 and 0xFF).toByte())
            rgbBuffer.put(i * 3 + 1, (pixel shr 8 and 0xFF).toByte())
            rgbBuffer.put(i * 3 + 2, (pixel and 0xFF).toByte())
        }

        return MnnGlobalReleaseLock.withOperation {
            nativeDetectRetinaFaces(nativeHandle, rgbBuffer, width, height, 3, confidenceThreshold, nmsThreshold).toList()
        }
    }

    fun releaseSession() {
        if (nativeHandle != 0L) {
            MnnGlobalReleaseLock.withLock {
                nativeReleaseSession(nativeHandle)
            }
        }
    }

    fun rebuildSession(): Boolean {
        if (nativeHandle == 0L) {
            return false
        }
        return MnnGlobalReleaseLock.withOperation {
            nativeRebuildSession(nativeHandle)
        }
    }

    fun releaseModelBuffer() {
        if (nativeHandle != 0L) {
            MnnGlobalReleaseLock.withLock {
                nativeReleaseModelBuffer(nativeHandle)
            }
        }
    }

    /**
     * [Zero-Copy] RetinaFace 检测——直接从 YUV NV21 输入
     *
     * 绕过 YUV→ARGB Bitmap→RGB ByteBuffer 的多重 CPU 拷贝，
     * 将 NV21 DirectByteBuffer 直接传入 C++ 层，由 MNN ImageProcess::convert
     * 在 native 端完成 NV21→RGB + resize + 归一化的一体化处理。
     *
     * @param nv21Data 紧凑 NV21 数据 (Y 平面 + 交错 VU 平面)
     * @param width 原始图像宽度
     * @param height 原始图像高度
     * @return [x1, y1, x2, y2, score, landmarks(10)] 或 null
     */
    fun detectRetinaFaceFromYuv(
        nv21Data: ByteBuffer,
        width: Int,
        height: Int,
        rotationDegrees: Int = 0,
        confidenceThreshold: Float = 0.5f,
        nmsThreshold: Float = 0.4f
    ): FloatArray? {
        if (nativeHandle == 0L) {
            Logger.w(TAG, "Detector not initialized")
            return null
        }
        if (!nv21Data.isDirect) {
            Logger.e(TAG, "NV21 buffer must be direct")
            return null
        }
        val outResult = getRetinaResult()
        val detected = MnnGlobalReleaseLock.withOperation {
            nativeDetectRetinaFaceFromNv21(nativeHandle, nv21Data, width, height,
                rotationDegrees, confidenceThreshold, nmsThreshold, outResult)
        }
        return if (detected) outResult.copyOf() else null
    }

    /**
     * [Zero-Copy] 2D106 关键点检测——直接从 YUV NV21 输入
     */
    fun detectFromYuv(
        nv21Data: ByteBuffer,
        width: Int,
        height: Int,
        rotationDegrees: Int = 0
    ): FloatArray? {
        if (nativeHandle == 0L) {
            Logger.w(TAG, "Detector not initialized")
            return null
        }
        if (!nv21Data.isDirect) {
            Logger.e(TAG, "NV21 buffer must be direct")
            return null
        }
        val outResult = getDetectResult(DETECT_RESULT_SIZE)
        val written = MnnGlobalReleaseLock.withOperation {
            nativeDetectFromNv21(nativeHandle, nv21Data, width, height, rotationDegrees, outResult)
        }
        return if (written > 0) outResult.copyOf(written) else null
    }

    /**
     * [Zero-Copy] 关键点检测——YUV NV21 + ROI 裁剪
     *
     * 跳过 Bitmap 创建和 pixel loop，直接通过 MNN ImageProcess 在 GPU 上完成
     * NV21→RGB + ROI 裁剪 + 缩放到模型输入尺寸的一体化预处理。
     *
     * @param nv21Data 紧凑 NV21 DirectByteBuffer
     * @param nv21Width NV21 图像宽度
     * @param nv21Height NV21 图像高度
     * @param roiLeft ROI 左边界（NV21 像素坐标）
     * @param roiTop ROI 上边界（NV21 像素坐标）
     * @param roiRight ROI 右边界（NV21 像素坐标）
     * @param roiBottom ROI 下边界（NV21 像素坐标）
     * @return 模型原始输出（未做坐标逆映射），失败返回 null
     */
    fun detectLandmarksFromYuv(
        nv21Data: ByteBuffer,
        nv21Width: Int,
        nv21Height: Int,
        rotationDegrees: Int = 0,
        roiLeft: Int,
        roiTop: Int,
        roiRight: Int,
        roiBottom: Int
    ): FloatArray? {
        if (nativeHandle == 0L) {
            Logger.w(TAG, "Detector not initialized")
            return null
        }
        if (!nv21Data.isDirect) {
            Logger.e(TAG, "NV21 buffer must be direct")
            return null
        }
        val outResult = getDetectResult(DETECT_RESULT_SIZE)
        val written = MnnGlobalReleaseLock.withOperation {
            nativeDetectLandmarksFromNv21(
                nativeHandle, nv21Data,
                nv21Width, nv21Height,
                rotationDegrees,
                roiLeft, roiTop, roiRight, roiBottom,
                outResult
            )
        }
        return if (written > 0) outResult.copyOf(written) else null
    }

    fun release() {
        if (nativeHandle != 0L) {
            MnnGlobalReleaseLock.withLock {
                nativeDestroy(nativeHandle)
            }
            nativeHandle = 0L
        }
    }

    // [P0-3] 已移除 finalize()，业务层必须显式调用 release() 释放 native 资源。
}
