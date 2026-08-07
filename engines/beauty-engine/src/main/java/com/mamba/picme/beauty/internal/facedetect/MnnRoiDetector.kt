package com.mamba.picme.beauty.internal.facedetect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.os.SystemClock
import com.mamba.picme.mnn.MnnGlobalReleaseLock
import com.mamba.picme.mnn.MnnResourceManager
import com.mamba.picme.beauty.api.Logger
import com.mamba.picme.beauty.api.facedetect.FaceDetection
import com.mamba.picme.beauty.internal.facedetect.mnn.MnnFaceDetector
import com.mamba.picme.beauty.internal.model.ModelManager
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 基于 MNN + OpenCL GPU 的 ROI 检测器
 * 替代 InsightFace Det10G (ONNX Runtime)，提供更快的 GPU 推理
 *
 * 兼容骁龙 765G + Adreno 620（OpenCL）
 *
 * [Agent First] 支持动态加载/卸载：
 * - 通过 MnnResourceManager 注册引用，参与全局内存协调
 * - 场景切换时自动卸载（如进入聊天页），返回相机页时自动恢复
 * - 内存压力时优先被卸载（模型小，恢复快）
 */
class MnnRoiDetector(
    context: Context,
    private val requireGpu: Boolean = true
) : RoiDetector {

    companion object {
        private const val TAG = "MnnRoi"
        private const val MODEL_KEY = "det_500m_mnn"
        private const val INPUT_SIZE = 320  // [RetinaFace-MobileNet0.25] 320×320 输入，75% 像素减少
        private const val CONFIDENCE_THRESHOLD = 0.5f
        private const val ROI_EXPAND_RATIO = 1.2f  // [对齐 ONNX] ROI 扩展比例，与 InsightFaceDet10G 一致
        // [det_500m] RetinaFace-MobileNet0.25 9 个输出层名称（与 MNNConvert 输出一致）
        // 尺度分组: stride 8/16/32，每个 3 输出 (score/bbox/landmark)
        private val OUTPUT_NAMES = arrayOf(
            "443", "468", "493",   // score: stride 8/16/32
            "446", "471", "496",   // bbox: stride 8/16/32
            "449", "474", "499"    // landmark: stride 8/16/32
        )
    }

    private val appContext = context.applicationContext
    private val resourceManager = MnnResourceManager.getInstance(appContext)
    private var detector: MnnFaceDetector? = null
    private val initialized = AtomicBoolean(false)
    private val initializing = AtomicBoolean(false)
    private var isGpuEnabled: Boolean = false

    // [性能优化] Bitmap 缩放复用池
    private var reusableScaledBitmap: Bitmap? = null

    init {
        // [优化] 不立即初始化，改为懒加载
        Logger.d(TAG, "MnnRoiDetector created (lazy initialization, requireGpu=$requireGpu)")

        // 注册资源管理监听器，响应全局卸载/加载事件
        resourceManager.registerFaceDetectionUnloadListener(::onResourceManagerUnload)
        resourceManager.registerFaceDetectionLoadListener(::onResourceManagerLoad)

        // [P0-4] 注册分级释放回调，供 releaseAtLevel("face", level) 使用
        resourceManager.registerFaceReleaseCallback(MnnResourceManager.ReleaseLevel.SESSION) {
            MnnGlobalReleaseLock.withLock {
                detector?.releaseSession()
            }
        }
        resourceManager.registerFaceReleaseCallback(MnnResourceManager.ReleaseLevel.FULL) {
            performUnload()
        }
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

            Logger.i(TAG, "Initializing MNN RetinaFace detector with OpenCL GPU (requireGpu=$requireGpu)...")
            val initStart = SystemClock.elapsedRealtime()
            detector = MnnFaceDetector.create(
                modelPath = modelFile.absolutePath,
                inputSize = INPUT_SIZE,
                useGpu = requireGpu,
                inputName = "input.1",
                outputNames = OUTPUT_NAMES
            )
            val initElapsed = SystemClock.elapsedRealtime() - initStart

            if (detector != null) {
                isGpuEnabled = true
                Logger.i(TAG, "MnnRoiDetector initialized in ${initElapsed}ms with OpenCL GPU")
                // 向 ResourceManager 注册引用，参与全局协调
                resourceManager.acquireFaceDetection("MnnRoiDetector")
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
            Logger.e(TAG, "Failed to initialize MnnRoiDetector (requireGpu=$requireGpu)", e)
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
     * landmarks 不随 ROI 扩展而扩展，保留真实五官位置用于人脸 embedding 对齐。
     */
    fun detectFacesWithLandmarks(bitmap: Bitmap): List<FaceDetection> {
        // [优化] 懒加载初始化（CAS 无锁，初始化中的线程直接返回）
        if (!ensureInitialized()) {
            Logger.d(TAG, "[Perf] Skipping ROI detection: detector not ready")
            return emptyList()
        }

        val totalStart = SystemClock.elapsedRealtime()
        val det = detector

        if (det == null) {
            Logger.w(TAG, "[Perf] MnnRoiDetector not initialized after lazy init, skipping")
            return emptyList()
        }

        val engineLabel = if (isGpuEnabled) "MNN-OpenCL" else "MNN-CPU"
        Logger.d(TAG, "[Perf] MnnRoiFacesWithLandmarks START: engine=$engineLabel, original=${bitmap.width}x${bitmap.height}, scaled=$INPUT_SIZE")

        return try {
            val scaleStart = SystemClock.elapsedRealtime()
            val scaledBitmap = getScaledBitmap(bitmap, INPUT_SIZE)
            val scaleElapsed = SystemClock.elapsedRealtime() - scaleStart

            val inferStart = SystemClock.elapsedRealtime()
            val faces = det.detectRetinaFaces(scaledBitmap, CONFIDENCE_THRESHOLD, 0.4f)
            val inferElapsed = SystemClock.elapsedRealtime() - inferStart
            val totalElapsed = SystemClock.elapsedRealtime() - totalStart

            if (faces.isEmpty()) {
                Logger.d(TAG, "[Perf] MnnRoiFacesWithLandmarks DONE (no face): total=${totalElapsed}ms (scale=${scaleElapsed}ms, infer=${inferElapsed}ms)")
                return emptyList()
            }

            Logger.i(TAG, "[Perf] MnnRoiFacesWithLandmarks DONE: engine=$engineLabel, total=${totalElapsed}ms (scale=${scaleElapsed}ms, infer=${inferElapsed}ms), rawFaces=${faces.size}")

            // 逆向 letterbox 变换映射回原图尺寸
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

            Logger.d(TAG, "MnnRoiFacesWithLandmarks: ${result.size} valid faces after filtering (min 1.5% area)")
            result
        } catch (e: Exception) {
            Logger.e(TAG, "MnnRoiFacesWithLandmarks detection failed", e)
            emptyList()
        }
    }

    /**
     * [Zero-Copy] ROI 检测——直接从 YUV NV21 输入
     *
     * 绕过 Bitmap 转换和 getScaledBitmap CPU 缩放，
     * NV21 DirectByteBuffer 直传 MNN，由 C++ 层 ImageProcess::convert 处理。
     *
     * @param nv21Data 紧凑 NV21 数据
     * @param width 原始图像宽度（可能已旋转）
     * @param height 原始图像高度（可能已旋转）
     * @return 原图坐标的 ROI 矩形，未检测到返回 null
     */
    fun detectRoiFromYuv(nv21Data: java.nio.ByteBuffer, width: Int, height: Int, rotationDegrees: Int = 0): RectF? {
        // [优化] 懒加载初始化（CAS 无锁，初始化中的线程直接返回）
        if (!ensureInitialized()) {
            Logger.d(TAG, "[Perf] Skipping NV21 ROI detection: detector not ready")
            return null
        }

        val det = detector ?: run {
            Logger.w(TAG, "[Perf] MnnRoiDetector not initialized after lazy init, skipping")
            return null
        }

        val yuvStart = SystemClock.elapsedRealtime()
        val yuvEngineLabel = if (isGpuEnabled) "MNN-OpenCL" else "MNN-CPU"
        Logger.d(TAG, "[Perf] MnnRoi NV21 START: engine=$yuvEngineLabel, input=${width}x${height}, rot=$rotationDegrees")

        return try {
            val result = det.detectRetinaFaceFromYuv(nv21Data, width, height,
                rotationDegrees, CONFIDENCE_THRESHOLD, 0.4f)
            val yuvElapsed = SystemClock.elapsedRealtime() - yuvStart

            if (result == null || result.size < 5) {
                Logger.d(TAG, "[Perf] MnnRoi NV21 DONE (no face): total=${yuvElapsed}ms")
                return null
            }

            // MNN ImageProcess 内部做了 rotation + letterbox + resize → inputSize_ × inputSize_
            // 输出坐标在 inputSize_ 空间，需要先逆向 letterbox，再映射到“旋转后坐标系”。
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

            Logger.i(TAG, "[Perf] MnnRoi NV21 DONE: engine=$yuvEngineLabel, total=${yuvElapsed}ms, rect=[$mappedX1,$mappedY1,$mappedX2,$mappedY2]")
            RectF(mappedX1, mappedY1, mappedX2, mappedY2)
        } catch (e: Exception) {
            Logger.e(TAG, "MnnRoi YUV detection failed", e)
            null
        }
    }

    /**
     * 获取复用的缩放 Bitmap，避免每帧创建
     */
    private fun getScaledBitmap(source: Bitmap, targetSize: Int): Bitmap {
        // 防御：source 可能在 CameraX ImageProxy 关闭后被回收
        if (source.isRecycled) {
            throw IllegalStateException("Source bitmap recycled before scaling")
        }
        if (source.width == targetSize && source.height == targetSize) {
            return source
        }
        var bmp = reusableScaledBitmap
        if (bmp == null || bmp.isRecycled || bmp.width != targetSize || bmp.height != targetSize) {
            bmp?.recycle()
            try {
                bmp = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
            } catch (e: OutOfMemoryError) {
                Logger.e(TAG, "OOM creating ${targetSize}x$targetSize bitmap, fallback to RGB_565")
                bmp = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.RGB_565)
            }
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
        // 注销监听器，避免已释放对象收到回调
        resourceManager.unregisterFaceDetectionUnloadListener(::onResourceManagerUnload)
        resourceManager.unregisterFaceDetectionLoadListener(::onResourceManagerLoad)

        // 释放引用（触发 ResourceManager 协调）
        resourceManager.releaseFaceDetection(
            owner = "MnnRoiDetector",
            onSafeUnload = ::performUnload,
            onSoftRelease = ::performUnload
        )

        reusableScaledBitmap?.recycle()
        reusableScaledBitmap = null
        Logger.i(TAG, "MnnRoiDetector released")
    }

}
