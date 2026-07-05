package com.mamba.picme.beauty.internal.facedetect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import com.mamba.picme.beauty.api.Logger
import com.mamba.picme.beauty.internal.facedetect.ncnn.NcnnFaceDetector
import com.mamba.picme.beauty.internal.model.ModelManager

/**
 * 基于 NCNN 的 106 点关键点检测器
 * 替代 InsightFace 2D106 (ONNX Runtime)，提供轻量级 GPU 推理
 */
class NcnnLandmarkDetector(
    context: Context,
    private val requireGpu: Boolean = true
) : LandmarkDetector {

    companion object {
        private const val TAG = "NcnnLandmark"
        private const val MODEL_KEY = "2d106_ncnn"
        private const val INPUT_SIZE = 192
        private const val POINT_COUNT = 106
        private const val ENGINE_NAME = "NCNN-Vulkan"

        // [关键修复] 进程级全局锁：OpenMP 运行时初始化不是线程安全的，
        // 所有 NCNN 调用（包括 ROI 和 Landmark）必须串行化到同一线程。
        // 注意：必须与 NcnnRoiDetector 使用同一个锁对象。
        private val NCNN_GLOBAL_LOCK = NcnnRoiDetector::class.java
    }

    private val appContext = context.applicationContext
    private var detector: NcnnFaceDetector? = null
    private var isInitialized = false
    private var isGpuEnabled: Boolean = false

    // [诊断] 初始化失败原因（供运行时查询与日志诊断）
    @Volatile
    var initFailureReason: String? = null
        private set

    // [性能优化] 复用 Bitmap 池
    private var reusableScaledBitmap: Bitmap? = null

    /**
     * 懒加载初始化 - 仅在首次 detect 时调用
     * [修复] 初始化失败时不标记 isInitialized=true，允许后续调用重试
     */
    private fun ensureInitialized() {
        if (isInitialized && detector != null) return

        synchronized(this) {
            if (isInitialized && detector != null) return

            initialize()
            if (detector != null) {
                isInitialized = true
                initFailureReason = null
            }
        }
    }

    init {
        Logger.d(TAG, "NcnnLandmarkDetector created (lazy initialization, requireGpu=$requireGpu)")
    }

    private fun initialize() {
        // [修复崩溃] OpenMP 运行时初始化不是线程安全的，必须全局同步
        synchronized(NCNN_GLOBAL_LOCK) {
            try {
                val (paramFile, binFile) = ModelManager.prepareNcnnModel(MODEL_KEY, appContext)

                Logger.i(TAG, "Initializing NCNN landmark detector (requireGpu=$requireGpu)...")
                Logger.d(TAG, "Model files: param=${paramFile.absolutePath} (${paramFile.length()} bytes), bin=${binFile.absolutePath} (${binFile.length()} bytes)")

                val initStart = SystemClock.elapsedRealtime()
                detector = NcnnFaceDetector.create(
                    paramPath = paramFile.absolutePath,
                    binPath = binFile.absolutePath,
                    inputSize = INPUT_SIZE,
                    useGpu = requireGpu,
                    inputName = "data",
                    outputNames = arrayOf("fc1")
                )
                val initElapsed = SystemClock.elapsedRealtime() - initStart

                if (detector != null) {
                    isGpuEnabled = true
                    Logger.i(TAG, "NcnnLandmarkDetector initialized in ${initElapsed}ms")
                    return
                }

                // [诊断] nativeCreate 返回 null：libncnn.so 缺失或模型文件不兼容
                isGpuEnabled = false
                initFailureReason = "nativeCreate returned null in ${initElapsed}ms " +
                    "— likely libncnn.so is missing from jniLibs/ or model is incompatible"
                Logger.e(TAG, "NCNN init FAILED: ${initFailureReason}. " +
                    "Ensure libncnn.so in jniLibs/arm64-v8a/ AND model [$MODEL_KEY] is valid.")
            } catch (e: IllegalStateException) {
                initFailureReason = "Model not found: $MODEL_KEY. " +
                    "Download 'face-landmark-2d106-ncnn' from ModelScope via Settings."
                Logger.e(TAG, initFailureReason!!, e)
                detector = null
            } catch (e: Exception) {
                initFailureReason = "${e.javaClass.simpleName}: ${e.message}"
                Logger.e(TAG, "NcnnLandmarkDetector init failed: ${initFailureReason}", e)
                detector = null
            }
        }
    }

    override fun detectLandmarks(bitmap: Bitmap, lensFacing: Int, roi: RectF?): FloatArray? {
        ensureInitialized()

        val totalStart = SystemClock.elapsedRealtime()
        val det = detector

        if (det == null) {
            Logger.w(TAG, "[Perf] NcnnLandmarkDetector not initialized. Reason: ${initFailureReason ?: "unknown"}. Will retry on next call.")
            return null
        }

        // [关键修复] OpenMP 线程亲和性初始化不是线程安全的，
        // 所有 NCNN 调用（包括 ROI 和 Landmark）必须串行化到同一线程
        return synchronized(NCNN_GLOBAL_LOCK) {
            try {
                detectLandmarksLocked(bitmap, lensFacing, roi, det, totalStart)
            } catch (e: Exception) {
                Logger.e(TAG, "NcnnLandmark detection failed", e)
                null
            }
        }
    }

    private fun detectLandmarksLocked(bitmap: Bitmap, lensFacing: Int, roi: RectF?, det: NcnnFaceDetector, totalStart: Long): FloatArray? {
        val prepStart = SystemClock.elapsedRealtime()
        val cropResult = prepareInputBitmap(bitmap, roi)
        val prepElapsed = SystemClock.elapsedRealtime() - prepStart

        val inferStart = SystemClock.elapsedRealtime()
        val result = det.detect(cropResult.bitmap)

        if (result == null || result.isEmpty()) {
            return null
        }

        val landmarks = parseLandmarks(result, bitmap.width, bitmap.height, cropResult.inverseTransform)
        return landmarks
    }

    /**
     * 裁剪信息，包含裁剪后的 Bitmap 和逆变换矩阵
     */
    private data class CropResult(
        val bitmap: Bitmap,
        val inverseTransform: Matrix
    )

    /**
     * 准备输入 Bitmap：裁剪 ROI 并缩放到 INPUT_SIZE
     */
    private fun prepareInputBitmap(source: Bitmap, roi: RectF?): CropResult {
        val faceBounds = if (roi != null) {
            Rect(
                roi.left.toInt().coerceIn(0, source.width),
                roi.top.toInt().coerceIn(0, source.height),
                roi.right.toInt().coerceIn(0, source.width),
                roi.bottom.toInt().coerceIn(0, source.height)
            )
        } else {
            Rect(0, 0, source.width, source.height)
        }

        val faceWidth = faceBounds.width().toFloat().coerceAtLeast(1f)
        val faceHeight = faceBounds.height().toFloat().coerceAtLeast(1f)
        val looseSize = maxOf(faceWidth, faceHeight)
        if (looseSize <= 0f) {
            return buildFallbackCrop(source)
        }

        val centerX = faceBounds.exactCenterX()
        val centerY = faceBounds.exactCenterY()
        val inputScale = INPUT_SIZE / looseSize

        val transformMatrix = Matrix()
        transformMatrix.setValues(
            floatArrayOf(
                inputScale, 0f, INPUT_SIZE / 2f - centerX * inputScale,
                0f, inputScale, INPUT_SIZE / 2f - centerY * inputScale,
                0f, 0f, 1f
            )
        )

        val inverseMatrix = Matrix()
        transformMatrix.invert(inverseMatrix)

        var scaled = reusableScaledBitmap
        if (scaled == null || scaled.isRecycled || scaled.width != INPUT_SIZE || scaled.height != INPUT_SIZE) {
            scaled?.recycle()
            scaled = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
            reusableScaledBitmap = scaled
        }

        val canvas = Canvas(scaled)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(source, transformMatrix, null)

        return CropResult(scaled, inverseMatrix)
    }

    /**
     * 回退裁剪：当 ROI 无效时，使用全图居中缩放
     */
    private fun buildFallbackCrop(source: Bitmap): CropResult {
        val sourceWidth = source.width.toFloat()
        val sourceHeight = source.height.toFloat()
        val scale = INPUT_SIZE / maxOf(sourceWidth, sourceHeight)
        val scaledW = sourceWidth * scale
        val scaledH = sourceHeight * scale
        val left = (INPUT_SIZE - scaledW) / 2f
        val top = (INPUT_SIZE - scaledH) / 2f

        val transformMatrix = Matrix()
        transformMatrix.setValues(
            floatArrayOf(
                scale, 0f, left,
                0f, scale, top,
                0f, 0f, 1f
            )
        )

        val inverseMatrix = Matrix()
        transformMatrix.invert(inverseMatrix)

        var scaled = reusableScaledBitmap
        if (scaled == null || scaled.isRecycled || scaled.width != INPUT_SIZE || scaled.height != INPUT_SIZE) {
            scaled?.recycle()
            scaled = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
            reusableScaledBitmap = scaled
        }

        val canvas = Canvas(scaled)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(source, transformMatrix, null)

        return CropResult(scaled, inverseMatrix)
    }

    /**
     * 解析 NCNN 输出的 106 点关键点
     * 使用逆变换矩阵将模型坐标系 [-1, 1] 映射回原始图像坐标系
     */
    private fun parseLandmarks(
        output: FloatArray,
        bitmapWidth: Int,
        bitmapHeight: Int,
        inverseTransform: Matrix
    ): FloatArray {
        val points = FloatArray(POINT_COUNT * 2)
        val outputPoints = minOf(output.size / 2, POINT_COUNT)
        val halfInputSize = INPUT_SIZE / 2f
        val mappedPoint = floatArrayOf(0f, 0f)

        for (i in 0 until outputPoints) {
            val x = output[i * 2]
            val y = output[i * 2 + 1]

            // 模型输出 [-1, 1] → INPUT_SIZE 像素坐标
            mappedPoint[0] = (x + 1f) * halfInputSize
            mappedPoint[1] = (y + 1f) * halfInputSize

            // 使用逆变换矩阵映射回原始图像坐标
            inverseTransform.mapPoints(mappedPoint)

            // 归一化到 [0, 1]
            points[i * 2] = (mappedPoint[0] / bitmapWidth).coerceIn(0f, 1f)
            points[i * 2 + 1] = (mappedPoint[1] / bitmapHeight).coerceIn(0f, 1f)
        }

        return points
    }

    override fun release() {
        detector?.release()
        detector = null
        reusableScaledBitmap?.recycle()
        reusableScaledBitmap = null
        Logger.i(TAG, "NcnnLandmarkDetector released")
    }

}
