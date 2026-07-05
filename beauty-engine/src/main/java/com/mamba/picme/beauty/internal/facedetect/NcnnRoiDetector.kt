package com.mamba.picme.beauty.internal.facedetect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.os.SystemClock
import com.mamba.picme.beauty.api.Logger
import com.mamba.picme.beauty.api.facedetect.FaceDetection
import com.mamba.picme.beauty.internal.facedetect.ncnn.NcnnFaceDetector
import com.mamba.picme.beauty.internal.model.ModelManager
import java.nio.ByteBuffer

/**
 * 基于 NCNN 的 ROI 检测器
 * 替代 InsightFace Det10G (ONNX Runtime)，提供轻量级 GPU 推理
 */
class NcnnRoiDetector(
    context: Context,
    private val requireGpu: Boolean = true
) : RoiDetector {

    companion object {
        private const val TAG = "NcnnRoi"
        private const val MODEL_KEY = "det_500m_ncnn"
        private const val INPUT_SIZE = 320  // [RetinaFace-MobileNet0.25] 320×320 输入
        private const val CONFIDENCE_THRESHOLD = 0.5f
        private const val ROI_EXPAND_RATIO = 1.2f
        private const val ENGINE_NAME = "NCNN-Vulkan"

        // [关键修复] 进程级全局锁：OpenMP 运行时初始化不是线程安全的，
        // 所有 NCNN 调用（包括 ROI 和 Landmark）必须串行化到同一线程。
        // 注意：必须与 NcnnLandmarkDetector 使用同一个锁对象。
        private val NCNN_GLOBAL_LOCK = NcnnRoiDetector::class.java

        // [det_500m] RetinaFace-MobileNet0.25 NCNN 9 个输出 blob 名称（PNNX→NCNN 转换后）
        // 尺度分组: stride 8/16/32，每个 3 输出 (score/bbox/landmark)
        // NCNN 输出: out0=score8, out1=score16, out2=score32,
        //            out3=bbox8, out4=bbox16, out5=bbox32,
        //            out6=landmark8, out7=landmark16, out8=landmark32
        private val OUTPUT_NAMES = arrayOf(
            "out0", "out1", "out2",  // score: stride 8/16/32
            "out3", "out4", "out5",  // bbox: stride 8/16/32
            "out6", "out7", "out8"   // landmark: stride 8/16/32
        )
    }

    private val appContext = context.applicationContext
    private var detector: NcnnFaceDetector? = null
    private var isInitialized = false
    private var isGpuEnabled: Boolean = false

    // [诊断] 初始化失败原因（供运行时查询与日志诊断）
    @Volatile
    var initFailureReason: String? = null
        private set

    // [性能优化] Bitmap 缩放复用池
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
        Logger.d(TAG, "NcnnRoiDetector created (lazy initialization, requireGpu=$requireGpu)")
    }

    private fun initialize() {
        // [修复崩溃] OpenMP 运行时初始化不是线程安全的，必须全局同步
        synchronized(NCNN_GLOBAL_LOCK) {
            try {
                val (paramFile, binFile) = ModelManager.prepareNcnnModel(MODEL_KEY, appContext)

                Logger.i(TAG, "Initializing NCNN RetinaFace detector (requireGpu=$requireGpu)...")
                Logger.d(TAG, "Model files: param=${paramFile.absolutePath} (${paramFile.length()} bytes), bin=${binFile.absolutePath} (${binFile.length()} bytes)")

                // [诊断] 验证文件可读性
                if (!paramFile.canRead()) {
                    Logger.e(TAG, "Param file not readable: ${paramFile.absolutePath}")
                }
                if (!binFile.canRead()) {
                    Logger.e(TAG, "Bin file not readable: ${binFile.absolutePath}")
                }

                val initStart = SystemClock.elapsedRealtime()
                detector = NcnnFaceDetector.create(
                    paramPath = paramFile.absolutePath,
                    binPath = binFile.absolutePath,
                    inputSize = INPUT_SIZE,
                    useGpu = requireGpu,
                    inputName = "in0",
                    outputNames = OUTPUT_NAMES
                )
                val initElapsed = SystemClock.elapsedRealtime() - initStart

                if (detector != null) {
                    isGpuEnabled = true
                    Logger.i(TAG, "NcnnRoiDetector initialized in ${initElapsed}ms")
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
                    "Download 'picme-face-det-500m-ncnn' from ModelScope via Settings."
                Logger.e(TAG, initFailureReason!!, e)
                detector = null
            } catch (e: Exception) {
                initFailureReason = "${e.javaClass.simpleName}: ${e.message}"
                Logger.e(TAG, "NcnnRoiDetector init failed: ${initFailureReason}", e)
                detector = null
            }
        }
    }

    override fun detectRoi(bitmap: Bitmap): RectF? {
        val faces = detectFaces(bitmap)
        return faces.firstOrNull()
    }

    /**
     * 多脸检测：返回所有有效人脸 ROI 列表
     *
     * 过滤策略：
     * - 过滤掉面积 < 3% 图片总面积的脸（过小/误检）
     * - 按面积从大到小排序
     * - 返回所有有效脸，供上层判断合影/自拍
     */
    fun detectFaces(bitmap: Bitmap): List<RectF> {
        return detectFacesWithLandmarks(bitmap).map { it.roi }
    }

    /**
     * 多脸检测：返回 ROI + RetinaFace 5 点 landmarks
     *
     * 5 点顺序：[左眼，右眼，鼻尖，左嘴角，右嘴角]，坐标为原图像素坐标。
     */
    fun detectFacesWithLandmarks(bitmap: Bitmap): List<FaceDetection> {
        ensureInitialized()

        val totalStart = SystemClock.elapsedRealtime()
        val det = detector

        if (det == null) {
            Logger.w(TAG, "[Perf] NcnnRoiDetector not initialized. Reason: ${initFailureReason ?: "unknown"}. Will retry on next call.")
            return emptyList()
        }

        // [关键修复] OpenMP 线程亲和性初始化不是线程安全的，
        // 所有 NCNN 调用（包括 ROI 和 Landmark）必须串行化到同一线程
        return synchronized(NCNN_GLOBAL_LOCK) {
            try {
                detectFacesWithLandmarksLocked(bitmap, det, totalStart)
            } catch (e: Exception) {
                Logger.e(TAG, "NcnnRoi detection failed", e)
                emptyList()
            }
        }
    }

    private fun detectFacesWithLandmarksLocked(bitmap: Bitmap, det: NcnnFaceDetector, totalStart: Long): List<FaceDetection> {
        val scaleStart = SystemClock.elapsedRealtime()
        val scaledBitmap = getScaledBitmap(bitmap, INPUT_SIZE)
        val scaleElapsed = SystemClock.elapsedRealtime() - scaleStart

        Logger.d(TAG, "[Perf] NcnnRoiFacesWithLandmarks START: engine=$ENGINE_NAME, gpu=$isGpuEnabled, original=${bitmap.width}x${bitmap.height}, scaled=${scaledBitmap.width}x${scaledBitmap.height}")

        val inferStart = SystemClock.elapsedRealtime()
        val faces = det.detectRetinaFaces(scaledBitmap, CONFIDENCE_THRESHOLD, 0.3f)
        val inferElapsed = SystemClock.elapsedRealtime() - inferStart

        val totalElapsed = SystemClock.elapsedRealtime() - totalStart

        if (faces.isEmpty()) {
            Logger.d(TAG, "[Perf] NcnnRoiFacesWithLandmarks DONE (no face): engine=$ENGINE_NAME, gpu=$isGpuEnabled, total=${totalElapsed}ms (scale=${scaleElapsed}ms, infer=${inferElapsed}ms)")
            return emptyList()
        }

        Logger.i(TAG, "[Perf] NcnnRoiFacesWithLandmarks DONE: engine=$ENGINE_NAME, gpu=$isGpuEnabled, total=${totalElapsed}ms (scale=${scaleElapsed}ms, infer=${inferElapsed}ms), rawFaces=${faces.size}")

        val origW = bitmap.width.toFloat()
        val origH = bitmap.height.toFloat()
        val scale = INPUT_SIZE.toFloat() / maxOf(origW, origH)
        val scaledW = (origW * scale).toInt()
        val scaledH = (origH * scale).toInt()
        val padLeft = (INPUT_SIZE - scaledW) / 2f
        val padTop = (INPUT_SIZE - scaledH) / 2f
        val imageArea = origW * origH

        val result = mutableListOf<FaceDetection>()
        for (face in faces) {
            // 减去 letterbox padding，再除以缩放比例，映射回原图
            var mappedX1 = ((face.x1 - padLeft) / scale)
            var mappedY1 = ((face.y1 - padTop) / scale)
            var mappedX2 = ((face.x2 - padLeft) / scale)
            var mappedY2 = ((face.y2 - padTop) / scale)

            // 放大 ROI 区域，以包含更多面部上下文
            val centerX = (mappedX1 + mappedX2) / 2f
            val centerY = (mappedY1 + mappedY2) / 2f
            val faceW = mappedX2 - mappedX1
            val faceH = mappedY2 - mappedY1
            val newWidth = faceW * ROI_EXPAND_RATIO
            val newHeight = faceH * ROI_EXPAND_RATIO

            mappedX1 = (centerX - newWidth / 2f).coerceIn(0f, origW)
            mappedY1 = (centerY - newHeight / 2f).coerceIn(0f, origH)
            mappedX2 = (centerX + newWidth / 2f).coerceIn(0f, origW)
            mappedY2 = (centerY + newHeight / 2f).coerceIn(0f, origH)

            val faceArea = (mappedX2 - mappedX1) * (mappedY2 - mappedY1)
            val areaRatio = faceArea / imageArea

            // 过滤面积 < 1.5% 图片总面积的脸（过小/误检）
            if (areaRatio < 0.015f) {
                Logger.d(TAG, "Filtered small face: areaRatio=${String.format("%.2f%%", areaRatio * 100)}")
                continue
            }

            // 映射 5 点 landmarks 到原图坐标（不随 ROI 扩展）
            val landmarks5 = if (face.landmarks.size >= 10) {
                FloatArray(10) { i ->
                    val src = face.landmarks[i]
                    if (i % 2 == 0) {
                        ((src - padLeft) / scale).coerceIn(0f, origW)
                    } else {
                        ((src - padTop) / scale).coerceIn(0f, origH)
                    }
                }
            } else {
                null
            }

            result.add(FaceDetection(RectF(mappedX1, mappedY1, mappedX2, mappedY2), landmarks5))
        }

        // 按面积从大到小排序
        result.sortByDescending { it.roi.width() * it.roi.height() }

        Logger.d(TAG, "NcnnRoiFacesWithLandmarks: ${result.size} valid faces after filtering (min 1.5% area)")
        return result
    }

    /**
     * [Zero-Copy] ROI 检测——直接从 YUV NV21 输入
     *
     * 绕过 Bitmap 转换和 getScaledBitmap CPU 缩放，
     * NV21 DirectByteBuffer 直传 NCNN C++ 层，由 C++ 层完成
     * NV21→RGB + resize + letterbox + normalize 的一体化预处理。
     *
     * @param nv21Data 紧凑 NV21 数据
     * @param width 原始图像宽度
     * @param height 原始图像高度
     * @return 原图坐标的 ROI 矩形，未检测到返回 null
     */
    fun detectRoiFromYuv(nv21Data: ByteBuffer, width: Int, height: Int, rotationDegrees: Int = 0): RectF? {
        ensureInitialized()

        val det = detector ?: run {
            Logger.w(TAG, "[Perf] NcnnRoiDetector not initialized (YUV path). Reason: ${initFailureReason ?: "unknown"}. Will retry on next call.")
            return null
        }

        return synchronized(NCNN_GLOBAL_LOCK) {
            try {
                val result = det.detectRetinaFaceFromNv21(
                    nv21Data,
                    width,
                    height,
                    rotationDegrees,
                    CONFIDENCE_THRESHOLD,
                    0.3f
                )

                if (result == null || result.size < 5) {
                    return@synchronized null
                }

                // C++ 层 preprocessFromNv21 内部做了 rotation + letterbox + resize → INPUT_SIZE × INPUT_SIZE
                // 输出坐标在 INPUT_SIZE 空间，需要映射回旋转后坐标系。
                val needSwap = rotationDegrees == 90 || rotationDegrees == 270
                val rotatedW = if (needSwap) height.toFloat() else width.toFloat()
                val rotatedH = if (needSwap) width.toFloat() else height.toFloat()
                val scale = INPUT_SIZE.toFloat() / maxOf(rotatedW, rotatedH)
                val scaledW = (rotatedW * scale).toInt()
                val scaledH = (rotatedH * scale).toInt()
                val padLeft = (INPUT_SIZE - scaledW) / 2f
                val padTop = (INPUT_SIZE - scaledH) / 2f

                var mappedX1 = ((result[0] - padLeft) / scale)
                var mappedY1 = ((result[1] - padTop) / scale)
                var mappedX2 = ((result[2] - padLeft) / scale)
                var mappedY2 = ((result[3] - padTop) / scale)

                val centerX = (mappedX1 + mappedX2) / 2f
                val centerY = (mappedY1 + mappedY2) / 2f
                val w = mappedX2 - mappedX1
                val h = mappedY2 - mappedY1
                val newWidth = w * ROI_EXPAND_RATIO
                val newHeight = h * ROI_EXPAND_RATIO

                mappedX1 = (centerX - newWidth / 2f).coerceIn(0f, rotatedW)
                mappedY1 = (centerY - newHeight / 2f).coerceIn(0f, rotatedH)
                mappedX2 = (centerX + newWidth / 2f).coerceIn(0f, rotatedW)
                mappedY2 = (centerY + newHeight / 2f).coerceIn(0f, rotatedH)

                Logger.d(TAG, "[Perf] NcnnRoi YUV detection: result=$result, roi=(${mappedX1.toInt()},${mappedY1.toInt()},${mappedX2.toInt()},${mappedY2.toInt()})")
                RectF(mappedX1, mappedY1, mappedX2, mappedY2)
            } catch (e: Exception) {
                Logger.e(TAG, "NcnnRoi YUV detection failed", e)
                null
            }
        }
    }

    /**
     * 获取复用的缩放 Bitmap，避免每帧创建
     */
    private fun getScaledBitmap(source: Bitmap, targetSize: Int): Bitmap {
        if (source.width == targetSize && source.height == targetSize) {
            return source
        }
        var bmp = reusableScaledBitmap
        if (bmp == null || bmp.isRecycled || bmp.width != targetSize || bmp.height != targetSize) {
            bmp?.recycle()
            bmp = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
            reusableScaledBitmap = bmp
        }
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.BLACK)
        val matrix = Matrix()
        val scale = targetSize.toFloat() / maxOf(source.width, source.height)
        val scaledW = (source.width * scale).toInt()
        val scaledH = (source.height * scale).toInt()
        val left = (targetSize - scaledW) / 2f
        val top = (targetSize - scaledH) / 2f
        matrix.setScale(scale, scale)
        matrix.postTranslate(left, top)
        canvas.drawBitmap(source, matrix, null)
        return bmp
    }

    override fun release() {
        detector?.release()
        detector = null
        reusableScaledBitmap?.recycle()
        reusableScaledBitmap = null
        Logger.i(TAG, "NcnnRoiDetector released")
    }

}
