package com.mamba.picme.features.camera.facedetect

import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

/**
 * 图像处理工具类
 *
 * [GPU 检测优化] 消除 JPEG 压缩/解压中间步骤，
 * YUV_420_888 直接转 ARGB_8888 Bitmap，降低 CPU→GPU 传输延迟。
 */
object ImageUtils {
    private const val TAG = "PoLang:ImageUtils"

    // [常量定义] 旋转角度
    internal const val ROTATION_90 = 90
    internal const val ROTATION_180 = 180
    internal const val ROTATION_270 = 270

    // [常量定义] UV 通道移位（除以 2）
    internal const val UV_CHANNEL_SHIFT = 1

    // [常量定义] ITU-R BT.601 颜色转换系数
    internal const val Y_TO_R_COEFF = 1.402f
    internal const val Y_TO_G_U_COEFF = 0.344136f
    internal const val Y_TO_G_V_COEFF = 0.714136f
    internal const val Y_TO_B_U_COEFF = 1.772f

    // [常量定义] 位操作和颜色范围
    internal const val BYTE_MASK = 0xFF
    internal const val UV_OFFSET = 128
    internal const val COLOR_MIN = 0
    internal const val COLOR_MAX = 255
    internal const val ALPHA_BYTE = 0xFF
    internal const val RED_SHIFT = 16
    internal const val GREEN_SHIFT = 8

    // [性能优化] 复用 ARGB 像素缓冲区，避免每帧 new IntArray
    private var reusableArgbBuffer: IntArray? = null
    private var reusableArgbBufferSize: Int = 0

    // [GC 优化] 复用 Y/U/V 平面 ByteArray 缓冲区，消除每像素 ByteBuffer.get() JNI 调用
    private var reusableYPlane: ByteArray? = null
    private var reusableUPlane: ByteArray? = null
    private var reusableVPlane: ByteArray? = null

    // [性能优化] 复用 Bitmap 池，避免每帧 Bitmap.createBitmap
    private var reusableBitmap: Bitmap? = null
    private var reusableBitmapWidth: Int = 0
    private var reusableBitmapHeight: Int = 0

    private fun getYuvPlaneBuffer(existing: ByteArray?, size: Int): ByteArray {
        if (existing != null && existing.size >= size) return existing
        return ByteArray(size)
    }

    private fun getArgbBuffer(size: Int): IntArray {
        var buffer = reusableArgbBuffer
        if (buffer == null || buffer.size < size) {
            buffer = IntArray(size)
            reusableArgbBuffer = buffer
            reusableArgbBufferSize = size
        }
        return buffer
    }

    private fun getReusableBitmap(width: Int, height: Int): Bitmap {
        var bmp = reusableBitmap
        if (bmp == null || bmp.isRecycled || bmp.width != width || bmp.height != height) {
            bmp?.recycle()
            bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            reusableBitmap = bmp
            reusableBitmapWidth = width
            reusableBitmapHeight = height
        }
        return bmp
    }

    /**
     * 目标检测最大输入尺寸。
     * Det10G 模型输入为 640×640，2D106 为 192×192。
     * YUV→Bitmap 时直接缩放到此尺寸，避免全分辨率（720×1280）转换的巨额开销。
     */
    internal const val TARGET_MAX_SIZE = 320

    /**
     * 统一的 ImageProxy → Bitmap 转换（零 JPEG 路径 + 直接缩放）
     *
     * 相比旧方案（YUV→NV21→JPEG→Bitmap），本方案直接做 YUV→ARGB 色彩空间转换：
     * - 省去 JPEG 压缩（~5ms）和解压（~3ms）
     * - 省去 Bitmap 旋转的额外内存分配
     * - 直接生成 MediaPipe BitmapImageBuilder 所需的 ARGB_8888 格式
     *
     * [发热优化] YUV→Bitmap 时直接缩放到此尺寸，像素处理量从 40 万降至约 10 万（320×320），
     * 预期耗时从 ~20ms 降至 ~5ms。
     */
    @ExperimentalGetImage
    fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        return imageProxy.image?.let { img ->
            val width = imageProxy.width
            val height = imageProxy.height
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees

            val yBuffer = imageProxy.planes[0].buffer
            val uBuffer = imageProxy.planes[1].buffer
            val vBuffer = imageProxy.planes[2].buffer

            val yRowStride = imageProxy.planes[0].rowStride
            val uvRowStride = imageProxy.planes[1].rowStride
            val uvPixelStride = imageProxy.planes[1].pixelStride

            // 计算旋转后的输出尺寸
            val needSwap = rotationDegrees == ROTATION_90 || rotationDegrees == ROTATION_270
            val rotatedWidth = if (needSwap) height else width
            val rotatedHeight = if (needSwap) width else height

            // [性能优化] 计算缩放比例，YUV 转换直接输出缩放后尺寸
            val scale = TARGET_MAX_SIZE.toFloat() / maxOf(rotatedWidth, rotatedHeight)
            val invScale = 1f / scale
            val outWidth = (rotatedWidth * scale).toInt().coerceAtLeast(1)
            val outHeight = (rotatedHeight * scale).toInt().coerceAtLeast(1)
            val pixelCount = outWidth * outHeight

            val argb = getArgbBuffer(pixelCount)

            // [GC 优化] 批量读取 Y/U/V 平面到 ByteArray，消除每像素 3 次 ByteBuffer.get() JNI 调用
            // 使用 buffer.remaining() 而非 rowStride*height，因为 CameraX 实际 buffer 大小可能小于 stride*height
            val yPlaneSize = yBuffer.remaining()
            reusableYPlane = getYuvPlaneBuffer(reusableYPlane, yPlaneSize)
            yBuffer.position(0)
            yBuffer.get(reusableYPlane!!, 0, yPlaneSize)

            val uPlaneSize = uBuffer.remaining()
            reusableUPlane = getYuvPlaneBuffer(reusableUPlane, uPlaneSize)
            uBuffer.position(0)
            uBuffer.get(reusableUPlane!!, 0, uPlaneSize)

            val vPlaneSize = vBuffer.remaining()
            reusableVPlane = getYuvPlaneBuffer(reusableVPlane, vPlaneSize)
            vBuffer.position(0)
            vBuffer.get(reusableVPlane!!, 0, vPlaneSize)

            val yBytes = reusableYPlane!!
            val uBytes = reusableUPlane!!
            val vBytes = reusableVPlane!!

            // YUV → ARGB 转换（ITU-R BT.601 标准），直接采样到目标尺寸
            // [性能优化] 使用乘法替代每像素除法
            for (outY in 0 until outHeight) {
                val srcY = (outY * invScale).toInt().coerceIn(0, rotatedHeight - 1)
                for (outX in 0 until outWidth) {
                    // 反向映射到原始图像坐标
                    val srcX = (outX * invScale).toInt().coerceIn(0, rotatedWidth - 1)

                    // 根据旋转角度映射回 ImageProxy 原始坐标
                    val (origCol, origRow) = when (rotationDegrees) {
                        ROTATION_90 -> Pair(srcY, height - 1 - srcX)
                        ROTATION_180 -> Pair(width - 1 - srcX, height - 1 - srcY)
                        ROTATION_270 -> Pair(width - 1 - srcY, srcX)
                        else -> Pair(srcX, srcY)
                    }

                    val y = yBytes[origRow * yRowStride + origCol].toInt() and BYTE_MASK

                    val uvRow = origRow shr UV_CHANNEL_SHIFT
                    val uvCol = origCol shr UV_CHANNEL_SHIFT
                    val uvIndex = uvRow * uvRowStride + uvCol * uvPixelStride

                    val v = (vBytes[uvIndex].toInt() and BYTE_MASK) - UV_OFFSET
                    val u = (uBytes[uvIndex].toInt() and BYTE_MASK) - UV_OFFSET

                    val r = ((y + Y_TO_R_COEFF * v).toInt().coerceIn(COLOR_MIN, COLOR_MAX))
                    val g = ((y - Y_TO_G_U_COEFF * u - Y_TO_G_V_COEFF * v).toInt().coerceIn(COLOR_MIN, COLOR_MAX))
                    val b = ((y + Y_TO_B_U_COEFF * u).toInt().coerceIn(COLOR_MIN, COLOR_MAX))

                    val argbPixel = ((ALPHA_BYTE shl 24) or (r shl RED_SHIFT) or (g shl GREEN_SHIFT) or b)
                    argb[outY * outWidth + outX] = argbPixel
                }
            }

            val bitmap = getReusableBitmap(outWidth, outHeight)
            bitmap.setPixels(argb, 0, outWidth, 0, 0, outWidth, outHeight)
            bitmap
        }
    }

    /**
     * 释放复用的 Bitmap 资源（在相机销毁时调用）
     */
    fun release() {
        reusableBitmap?.recycle()
        reusableBitmap = null
        reusableArgbBuffer = null
        reusableArgbBufferSize = 0
        reusableBitmapWidth = 0
        reusableBitmapHeight = 0
        reusableYPlane = null
        reusableUPlane = null
        reusableVPlane = null
        reusableNv21Buffer = null
        hasLoggedUvFallbackWarning = false
    }

    // [Zero-Copy] 复用 NV21 DirectByteBuffer 池
    private var reusableNv21Buffer: ByteBuffer? = null
    private var hasLoggedUvFallbackWarning = false

    /**
     * ImageProxy → Bitmap（RGBA_8888 输出格式专用，零色彩转换）
     *
     * CameraX OUTPUT_IMAGE_FORMAT_RGBA_8888 输出时，planes[0] 已是 RGBA_8888 数据。
     * 只需将 buffer 拷贝到 Bitmap，无需 YUV→ARGB 色彩空间转换（省去 ~5ms）。
     *
     * @param imageProxy CameraX ImageProxy，planes[0] 为 RGBA_8888 数据
     * @return ARGB_8888 Bitmap（经 rotation 和 scale 处理），或 null
     */
    @ExperimentalGetImage
    fun imageProxyToBitmapRgba(imageProxy: ImageProxy): Bitmap? {
        val width = imageProxy.width
        val height = imageProxy.height
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val buffer = imageProxy.planes[0].buffer
        val pixelStride = imageProxy.planes[0].pixelStride
        val rowStride = imageProxy.planes[0].rowStride

        val needSwap = rotationDegrees == ROTATION_90 || rotationDegrees == ROTATION_270
        val rotatedWidth = if (needSwap) height else width
        val rotatedHeight = if (needSwap) width else height

        val scale = TARGET_MAX_SIZE.toFloat() / maxOf(rotatedWidth, rotatedHeight)
        val outWidth = (rotatedWidth * scale).toInt().coerceAtLeast(1)
        val outHeight = (rotatedHeight * scale).toInt().coerceAtLeast(1)
        val pixelCount = outWidth * outHeight

        val argb = getArgbBuffer(pixelCount)

        // 逐像素采样，处理旋转 + 缩放
        val invScale = 1f / scale

        for (outY in 0 until outHeight) {
            val srcY = (outY * invScale).toInt().coerceIn(0, rotatedHeight - 1)
            for (outX in 0 until outWidth) {
                val srcX = (outX * invScale).toInt().coerceIn(0, rotatedWidth - 1)

                val (origCol, origRow) = when (rotationDegrees) {
                    ROTATION_90 -> Pair(srcY, height - 1 - srcX)
                    ROTATION_180 -> Pair(width - 1 - srcX, height - 1 - srcY)
                    ROTATION_270 -> Pair(width - 1 - srcY, srcX)
                    else -> Pair(srcX, srcY)
                }

                val pixelOffset = origRow * rowStride + origCol * pixelStride
                buffer.position(pixelOffset)
                val r = (buffer.get().toInt() and BYTE_MASK)
                val g = (buffer.get().toInt() and BYTE_MASK)
                val b = (buffer.get().toInt() and BYTE_MASK)
                val a = (buffer.get().toInt() and BYTE_MASK)

                val argbPixel = ((a shl 24) or (r shl RED_SHIFT) or (g shl GREEN_SHIFT) or b)
                argb[outY * outWidth + outX] = argbPixel
            }
        }

        val bitmap = getReusableBitmap(outWidth, outHeight)
        bitmap.setPixels(argb, 0, outWidth, 0, 0, outWidth, outHeight)
        return bitmap
    }

    private fun logUvFallbackWarningOnce(message: String) {
        if (hasLoggedUvFallbackWarning) return
        Log.w(TAG, message)
        hasLoggedUvFallbackWarning = true
    }

    /**
     * [Zero-Copy] ImageProxy → NV21 DirectByteBuffer
     *
     * 将 CameraX YUV_420_888 三平面紧凑打包为 NV21 格式的 DirectByteBuffer。
     * NV21 布局: Y 平面 (width*height) + 交错 VU 平面 (width*height/2)
     *
     * 此方法做一次紧凑的 memcpy（处理 rowStride/pixelStride），
     * 替代之前的 YUV→ARGB Bitmap（~5ms）+ Bitmap→RGB ByteBuffer（~2ms）双重重 CPU 拷贝。
     *
     * @return NV21 DirectByteBuffer，直接可传入 MNN ImageProcess
     */
    @ExperimentalGetImage
    fun imageProxyToNv21(imageProxy: ImageProxy): ByteBuffer? {
        val width = imageProxy.width
        val height = imageProxy.height
        val yPlane = imageProxy.planes[0]
        val uPlane = imageProxy.planes[1]
        val vPlane = imageProxy.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        val nv21Size = width * height * 3 / 2
        var nv21 = reusableNv21Buffer
        if (nv21 == null || nv21.capacity() < nv21Size) {
            nv21 = ByteBuffer.allocateDirect(nv21Size)
            reusableNv21Buffer = nv21
        }
        nv21.clear()

        // --- Y 平面拷贝（处理 rowStride） ---
        if (yRowStride == width) {
            // 最优路径：无 padding，直接 bulk copy
            yBuffer.position(0)
            yBuffer.limit(width * height)
            nv21.put(yBuffer)
        } else {
            // 逐行拷贝（处理 rowStride > width 的情况）
            val yBytes = ByteArray(width)
            for (row in 0 until height) {
                yBuffer.position(row * yRowStride)
                yBuffer.get(yBytes, 0, width)
                nv21.put(yBytes)
            }
        }

        // --- UV 交错平面拷贝 (VU VU VU...) ---
        val uvHeight = height / 2
        val uvWidth = width / 2
        if (uvPixelStride == 2 && uvRowStride == width) {
            // 常见设备上 U/V plane 可能共享底层内存且有 1 byte 偏移，
            // 不能用 uvSize(宽*高/2) 作为单 plane 的容量判定，否则会误报降级。
            val uvLastIndex = (uvHeight - 1) * uvRowStride + (uvWidth - 1) * uvPixelStride
            val uLimit = uBuffer.limit()
            val vLimit = vBuffer.limit()

            for (row in 0 until uvHeight) {
                val rowBase = row * uvRowStride
                for (col in 0 until uvWidth) {
                    val uvIdx = rowBase + col * uvPixelStride
                    val v = if (uvIdx < vLimit) vBuffer.get(uvIdx) else UV_OFFSET.toByte()
                    val u = if (uvIdx < uLimit) uBuffer.get(uvIdx) else UV_OFFSET.toByte()
                    nv21.put(v)
                    nv21.put(u)
                }
            }

            if (uLimit <= uvLastIndex || vLimit <= uvLastIndex) {
                logUvFallbackWarningOnce(
                    "UV plane readable range insufficient, filled missing bytes with neutral chroma; " +
                        "uLimit=$uLimit, vLimit=$vLimit, requiredIndex=$uvLastIndex"
                )
            }
        } else if (uvPixelStride == 1) {
            // [Planar] U 和 V 分离，需要交错
            val uRowStride = uPlane.rowStride
            val vRowStride = vPlane.rowStride
            for (row in 0 until uvHeight) {
                uBuffer.position(row * uRowStride)
                vBuffer.position(row * vRowStride)
                for (col in 0 until uvWidth) {
                    nv21.put(vBuffer.get())  // V first (NV21)
                    nv21.put(uBuffer.get())  // U second
                }
            }
        } else {
            // [通用路径] pixelStride=2 且存在 row padding，使用绝对索引读取避免 position 抖动
            val uLimit = uBuffer.limit()
            val vLimit = vBuffer.limit()
            for (row in 0 until uvHeight) {
                val rowBase = row * uvRowStride
                for (col in 0 until uvWidth) {
                    val uvIdx = rowBase + col * uvPixelStride
                    val v = if (uvIdx < vLimit) vBuffer.get(uvIdx) else UV_OFFSET.toByte()
                    val u = if (uvIdx < uLimit) uBuffer.get(uvIdx) else UV_OFFSET.toByte()
                    nv21.put(v)
                    nv21.put(u)
                }
            }
        }

        // [修复] 恢复 plane buffer 的 position=0，供后续 imageProxyToBitmap 使用
        // nv21.put() 会推进 yBuffer/uBuffer/vBuffer.position，导致 remaining()=0
        yBuffer.position(0)
        uBuffer.position(0)
        vBuffer.position(0)

        nv21.flip()
        return nv21
    }
}
