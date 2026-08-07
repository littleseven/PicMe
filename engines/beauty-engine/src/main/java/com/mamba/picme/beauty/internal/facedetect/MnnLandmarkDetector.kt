package com.mamba.picme.beauty.internal.facedetect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import com.mamba.picme.mnn.MnnGlobalReleaseLock
import com.mamba.picme.mnn.MnnResourceManager
import com.mamba.picme.beauty.api.Logger
import com.mamba.picme.beauty.internal.facedetect.mnn.MnnFaceDetector
import com.mamba.picme.beauty.internal.model.ModelManager
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 基于 MNN + OpenCL GPU 的 106 点关键点检测器
 * 替代 InsightFace 2D106 (ONNX Runtime)，提供更快的 GPU 推理
 *
 * 兼容骁龙 765G + Adreno 620（OpenCL）
 *
 * [Agent First] 支持动态加载/卸载：
 * - 通过 MnnResourceManager 注册引用，参与全局内存协调
 * - 场景切换时自动卸载（如进入聊天页），返回相机页时自动恢复
 * - 内存压力时优先被卸载（模型小，恢复快）
 */
class MnnLandmarkDetector(
    context: Context,
    private val requireGpu: Boolean = true
) : LandmarkDetector {

    companion object {
        private const val TAG = "MnnLandmark"
        private const val MODEL_KEY = "2d106_mnn"
        private const val INPUT_SIZE = 192  // [对齐 ONNX] 与 InsightFace2D106Detector 保持一致
        private const val POINT_COUNT = 106
    }

    private val appContext = context.applicationContext
    private val resourceManager = MnnResourceManager.getInstance(appContext)
    private var detector: MnnFaceDetector? = null
    private val initialized = AtomicBoolean(false)
    private val initializing = AtomicBoolean(false)
    private var isGpuEnabled: Boolean = false

    // [性能优化] 复用 Bitmap 池
    private var reusableScaledBitmap: Bitmap? = null

    init {
        // [优化] 不立即初始化，改为懒加载
        Logger.d(TAG, "MnnLandmarkDetector created (lazy initialization, requireGpu=$requireGpu)")

        // 注册资源管理监听器，响应全局卸载/加载事件
        resourceManager.registerFaceDetectionUnloadListener(::onResourceManagerUnload)
        resourceManager.registerFaceDetectionLoadListener(::onResourceManagerLoad)
    }

    /**
     * 懒加载初始化 - 仅在首次 detect 时调用
     *
     * 使用 AtomicBoolean CAS 确保只有一个线程执行初始化，
     * 其他线程直接返回 false（跳过本帧检测），避免锁竞争阻塞渲染管线。
     *
     * @return true 如果检测器已就绪，false 如果正在初始化或初始化失败
     */
    private fun ensureInitialized(): Boolean {
        if (initialized.get()) return detector != null

        // CAS: 只有一个线程执行初始化
        if (!initializing.compareAndSet(false, true)) {
            Logger.d(TAG, "[Perf] Initialization already in progress, skipping frame")
            return false
        }

        try {
            if (initialized.get()) return detector != null  // Double-check
            initialize()
            if (detector != null) {
                initialized.set(true)
            }
            return detector != null
        } finally {
            initializing.set(false)
        }
    }

    private fun initialize() {
        try {
            val modelFile = ModelManager.prepareModel(MODEL_KEY, appContext)

            Logger.i(TAG, "Initializing MNN landmark detector (requireGpu=$requireGpu)...")
            val initStart = SystemClock.elapsedRealtime()
            detector = MnnFaceDetector.create(
                modelPath = modelFile.absolutePath,
                inputSize = INPUT_SIZE,
                useGpu = requireGpu,
                inputName = "data",
                outputNames = arrayOf("fc1")
            )
            val initElapsed = SystemClock.elapsedRealtime() - initStart

            if (detector != null) {
                isGpuEnabled = requireGpu
                val backendLabel = if (requireGpu) "OpenCL GPU" else "CPU"
                Logger.i(TAG, "MnnLandmarkDetector initialized in ${initElapsed}ms with $backendLabel")
                // 向 ResourceManager 注册引用，参与全局协调
                resourceManager.acquireFaceDetection("MnnLandmarkDetector")
            } else {
                // [关键策略] 要求 GPU 时初始化失败，直接放弃，不降级到 CPU
                isGpuEnabled = false
                if (requireGpu) {
                    Logger.e(TAG, "MNN GPU initialization failed and requireGpu=true, detector will remain null (no CPU fallback)")
                } else {
                    Logger.w(TAG, "MNN GPU initialization failed, attempting CPU fallback...")
                    // TODO: 实现 CPU 降级逻辑（如果需要）
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to initialize MnnLandmarkDetector (requireGpu=$requireGpu)", e)
            detector = null
        }
    }

    /**
     * ResourceManager 触发的卸载回调
     * 释放 native 模型资源，但保留 Kotlin 对象以便快速恢复
     */
    private fun onResourceManagerUnload() {
        synchronized(this) {
            if (!initialized.get()) return
            Logger.i(TAG, "Unloading due to ResourceManager request")
            performUnload()
            initialized.set(false)
            isGpuEnabled = false
        }
    }

    /**
     * ResourceManager 触发的加载回调
     *
     * 通过 ensureInitialized() 的 CAS 机制在后台线程异步初始化，
     * 不阻塞 detection 协程。初始化期间帧会被跳过，完成后自动恢复。
     */
    private fun onResourceManagerLoad() {
        if (initialized.get()) return
        Logger.i(TAG, "Load requested by ResourceManager, starting async init")
        ensureInitialized()  // CAS: 无锁、不阻塞，失败代表已在初始化中
    }

    /**
     * 执行实际卸载（线程安全）
     *
     * 使用 MnnGlobalReleaseLock 串行化 MNN native 释放，
     * 防止与 ASR/LLM 的 MNN 释放并发导致崩溃。
     */
    private fun performUnload() {
        MnnGlobalReleaseLock.withLock {
            detector?.release()
        }
        detector = null
        initialized.set(false)
        isGpuEnabled = false
        Logger.d(TAG, "Native resources unloaded")
    }

    override fun detectLandmarks(bitmap: Bitmap, lensFacing: Int, roi: RectF?): FloatArray? {
        // [优化] 懒加载初始化（CAS 无锁，初始化中的线程直接返回）
        if (!ensureInitialized()) {
            Logger.d(TAG, "[Perf] Skipping detection: detector not ready")
            return null
        }

        val totalStart = SystemClock.elapsedRealtime()
        val det = detector

        if (det == null) {
            Logger.w(TAG, "[Perf] MnnLandmarkDetector not initialized after lazy init, skipping")
            return null
        }

        val engineLabel = if (isGpuEnabled) "MNN-OpenCL" else "MNN-CPU"
        Logger.d(TAG, "[Perf] MnnLandmark START: engine=$engineLabel, bitmap=${bitmap.width}x${bitmap.height}, roi=$roi")

        return try {
            val prepStart = SystemClock.elapsedRealtime()
            val cropResult = prepareInputBitmap(bitmap, roi)
            val prepElapsed = SystemClock.elapsedRealtime() - prepStart

            val inferStart = SystemClock.elapsedRealtime()
            val result = det.detect(cropResult.bitmap)

            if (result == null || result.isEmpty()) {
                val inferElapsed = SystemClock.elapsedRealtime() - inferStart
                val totalElapsed = SystemClock.elapsedRealtime() - totalStart
                Logger.d(TAG, "[Perf] MnnLandmark DONE (no face): total=${totalElapsed}ms (prep=${prepElapsed}ms, infer=${inferElapsed}ms)")
                return null
            }

            // 使用逆变换矩阵将模型输出坐标映射回原始图像
            val parseStart = SystemClock.elapsedRealtime()
            val landmarks = parseLandmarks(result, bitmap.width, bitmap.height, cropResult.inverseTransform)
            val parseElapsed = SystemClock.elapsedRealtime() - parseStart
            val inferElapsed = SystemClock.elapsedRealtime() - inferStart - parseElapsed
            val totalElapsed = SystemClock.elapsedRealtime() - totalStart

            Logger.i(TAG, "[Perf] MnnLandmark DONE: total=${totalElapsed}ms (prep=${prepElapsed}ms, infer=${inferElapsed}ms, parse=${parseElapsed}ms), pts=${landmarks.size/2}")
            landmarks
        } catch (e: Exception) {
            Logger.e(TAG, "MnnLandmark detection failed", e)
            null
        }
    }

    /**
     * [Zero-Copy] 关键点检测——YUV NV21 + ROI 裁剪
     *
     * 跳过 Bitmap 创建和 CPU pixel loop，直接通过 MNN ImageProcess 在 GPU 上完成
     * NV21→RGB + ROI 裁剪 + 缩放到模型输入尺寸的一体化预处理。
     *
     * @param nv21Data 紧凑 NV21 DirectByteBuffer
     * @param nv21Width NV21 图像宽度
     * @param nv21Height NV21 图像高度
     * @param roiLeft ROI 左边界（NV21 像素坐标）
     * @param roiTop ROI 上边界（NV21 像素坐标）
     * @param roiRight ROI 右边界（NV21 像素坐标）
     * @param roiBottom ROI 下边界（NV21 像素坐标）
     * @return 归一化 [0,1] 的 106 点关键点，或 null
     */
    fun detectLandmarksFromNv21(
        nv21Data: ByteBuffer,
        nv21Width: Int,
        nv21Height: Int,
        rotationDegrees: Int = 0,
        roiLeft: Int,
        roiTop: Int,
        roiRight: Int,
        roiBottom: Int
    ): FloatArray? {
        if (!ensureInitialized()) {
            Logger.d(TAG, "[Perf] Skipping NV21 detection: detector not ready")
            return null
        }

        val det = detector ?: run {
            Logger.w(TAG, "[Perf] MnnLandmarkDetector not initialized, skipping NV21")
            return null
        }

        val totalStart = SystemClock.elapsedRealtime()

        return try {
            val inferStart = SystemClock.elapsedRealtime()
            val result = det.detectLandmarksFromYuv(
                nv21Data, nv21Width, nv21Height,
                rotationDegrees,
                roiLeft, roiTop, roiRight, roiBottom
            )
            val inferElapsed = SystemClock.elapsedRealtime() - inferStart

            if (result == null || result.isEmpty()) {
                val totalElapsed = SystemClock.elapsedRealtime() - totalStart
                Logger.d(TAG, "[Perf] MnnLandmark[NV21] DONE (no face): total=${totalElapsed}ms (infer=${inferElapsed}ms)")
                return null
            }

            // 构建逆变换矩阵：192×192 模型坐标 → 旋转后 ROI 像素坐标
            // 与 C++ buildNv21TransformMatrix(ROI) 保持一致：
            //   rotatedX = (dstX - padLeft) / scale + roiLeft
            //   rotatedY = (dstY - padTop) / scale + roiTop
            val roiW = (roiRight - roiLeft).toFloat()
            val roiH = (roiBottom - roiTop).toFloat()
            val scale = minOf(INPUT_SIZE.toFloat() / roiW, INPUT_SIZE.toFloat() / roiH)
            // 与 C++ buildNv21TransformMatrix 完全对齐：scaledW/scaledH 先截断为 Int 再计算 padding。
            // 若直接使用 Float，会在 scale<1（近脸大 ROI）场景放大反向映射误差，导致唇色贴合偏移。
            val scaledW = (roiW * scale).toInt()
            val scaledH = (roiH * scale).toInt()
            val padLeft = (INPUT_SIZE - scaledW) / 2f
            val padTop = (INPUT_SIZE - scaledH) / 2f
            val invScale = 1f / scale
            val tx = -padLeft * invScale + roiLeft
            val ty = -padTop * invScale + roiTop

            val inverseMatrix = Matrix()
            inverseMatrix.setValues(
                floatArrayOf(
                    invScale, 0f, tx,
                    0f, invScale, ty,
                    0f, 0f, 1f
                )
            )

            val needSwap = rotationDegrees == 90 || rotationDegrees == 270
            val normalizedWidth = if (needSwap) nv21Height else nv21Width
            val normalizedHeight = if (needSwap) nv21Width else nv21Height

            val parseStart = SystemClock.elapsedRealtime()
            val landmarks = parseLandmarks(result, normalizedWidth, normalizedHeight, inverseMatrix)
            val parseElapsed = SystemClock.elapsedRealtime() - parseStart
            val totalElapsed = SystemClock.elapsedRealtime() - totalStart

            Logger.i(TAG, "[Perf] MnnLandmark[NV21] DONE: total=${totalElapsed}ms (infer=${inferElapsed}ms, parse=${parseElapsed}ms), pts=${landmarks.size/2}")
            landmarks
        } catch (e: Exception) {
            Logger.e(TAG, "MnnLandmark NV21 detection failed", e)
            null
        }
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
     * 使用与 InsightFace2D106Detector 相同的变换逻辑：
     * - 以人脸中心为锚点缩放
     * - 将人脸中心对齐到 INPUT_SIZE/2
     * - 计算逆变换矩阵用于坐标映射回原始图像
     */
    private fun prepareInputBitmap(source: Bitmap, roi: RectF?): CropResult {
        // 计算有效的人脸边界框（与 ONNX 版本的 rectBounds 对应）
        val faceBounds = if (roi != null) {
            Rect(
                roi.left.toInt().coerceIn(0, source.width),
                roi.top.toInt().coerceIn(0, source.height),
                roi.right.toInt().coerceIn(0, source.width),
                roi.bottom.toInt().coerceIn(0, source.height)
            )
        } else {
            // 无 ROI 时使用全图
            Rect(0, 0, source.width, source.height)
        }

        val faceWidth = faceBounds.width().toFloat().coerceAtLeast(1f)
        val faceHeight = faceBounds.height().toFloat().coerceAtLeast(1f)
        val looseSize = maxOf(faceWidth, faceHeight)
        if (looseSize <= 0f) {
            // 回退：直接返回全图缩放
            return buildFallbackCrop(source)
        }

        val centerX = faceBounds.exactCenterX()
        val centerY = faceBounds.exactCenterY()
        val inputScale = INPUT_SIZE / looseSize

        // [关键修复] 使用与 ONNX 版本完全相同的变换矩阵构造方式
        // 变换公式：dst = inputScale * src + (INPUT_SIZE/2 - center * inputScale)
        // 即将人脸中心 (centerX, centerY) 映射到画布中心 (INPUT_SIZE/2, INPUT_SIZE/2)
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
     * 解析 MNN 输出的 106 点关键点
     * 使用逆变换矩阵将模型坐标系 [-1, 1] 映射回原始图像坐标系
     *
     * 与 InsightFace2D106Detector 保持一致：
     * 1. 模型输出 [-1, 1] → INPUT_SIZE 像素坐标
     * 2. 逆变换矩阵映射回原始图像空间
     * 3. 归一化到 [0, 1]
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
            // 与 ONNX 版本完全一致：(value + 1) * halfInputSize
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
        // 注销监听器，避免已释放对象收到回调
        resourceManager.unregisterFaceDetectionUnloadListener(::onResourceManagerUnload)
        resourceManager.unregisterFaceDetectionLoadListener(::onResourceManagerLoad)

        // 释放引用（触发 ResourceManager 协调）
        resourceManager.releaseFaceDetection(
            owner = "MnnLandmarkDetector",
            onSafeUnload = ::performUnload,
            onSoftRelease = ::performUnload
        )

        reusableScaledBitmap?.recycle()
        reusableScaledBitmap = null
        Logger.i(TAG, "MnnLandmarkDetector released")
    }

}
