package com.mamba.picme.core.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PointF
import com.mamba.picme.beauty.api.BeautyProcessor
import com.mamba.picme.beauty.api.Face
import com.mamba.picme.beauty.api.FaceContour
import com.mamba.picme.beauty.api.FaceLandmark
import com.mamba.picme.core.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import android.graphics.BlurMaskFilter
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF

/**
 * 拍照后 CPU 路径的美颜效果实现（静态位图处理）
 *
 * ⚠️ 此类用于拍照后的静态 Bitmap 后处理，与实时预览无关。
 * 实时预览美颜由 beauty-engine 模块的 BeautyPreviewEngine 负责（GPU 路径）。
 *
 * 磨皮：原 RenderScript 高斯模糊已因 API 废弃改为 Android Canvas + ColorMatrix 近似实现。
 * 美白/唇色/腮红/瘦脸/大眼：均在 CPU 上通过 ColorMatrix / Canvas 变换完成。
 */
// context 保留以维持构造函数签名兼容性；未来如需 Canvas 硬件加速或其他平台 API 仍可使用
@Suppress("UnusedPrivateProperty")
class GpuBeautyProcessor(private val context: Context) : BeautyProcessor {

    companion object {
        private const val TAG = "ImageProc"
    }

    override suspend fun applySmoothing(bitmap: Bitmap, strength: Float, faces: List<Face>): Bitmap {
        return withContext(Dispatchers.Default) {
            try {
                if (strength <= 0f || faces.isEmpty()) {
                    return@withContext bitmap
                }

                // [修复] 使用高斯模糊 + 人脸 Mask 实现磨皮，更接近预览的双边滤波效果
                // 实时预览磨皮由 beauty-engine 的双边滤波 Shader 实现
                val ratio = strength.coerceIn(0f, 100f) / 100f

                // 1. 创建模糊层（高斯模糊近似双边滤波）
                val blurRadius = (5f + ratio * 15f).toInt() // 5-20px 模糊半径
                val downsampleDivisor = (4f - ratio * 2f).coerceIn(2f, 4f)
                val downscaledWidth = (bitmap.width / downsampleDivisor).toInt().coerceAtLeast(1)
                val downscaledHeight = (bitmap.height / downsampleDivisor).toInt().coerceAtLeast(1)

                // 缩小 -> 模糊 -> 放大，模拟高斯模糊
                val downscaled = Bitmap.createScaledBitmap(bitmap, downscaledWidth, downscaledHeight, true)
                val blurLayer = Bitmap.createScaledBitmap(downscaled, bitmap.width, bitmap.height, true)
                downscaled.recycle()

                // 2. 创建人脸蒙版（只对人脸区域应用磨皮）
                val maskBitmap = createFaceMaskBitmap(
                    width = bitmap.width,
                    height = bitmap.height,
                    faces = faces,
                    featherRadius = 14f + ratio * 26f,
                    logPrefix = "Smoothing"
                ) ?: run {
                    blurLayer.recycle()
                    return@withContext bitmap
                }

                // 3. 合并：原图 + 模糊层 * 蒙版 * 强度
                val output = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                val canvas = Canvas(output)

                // 先画原图
                canvas.drawBitmap(bitmap, 0f, 0f, null)

                // 再画磨皮效果（只在人脸区域）
                val alpha = (120 + ratio * 135).toInt().coerceIn(0, 255) // 120-255
                val blendPaint = Paint().apply {
                    this.alpha = alpha
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_OVER)
                }

                // 使用蒙版只覆盖人脸区域
                val maskedCanvas = Canvas(blurLayer)
                val maskPaint = Paint().apply {
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                }
                maskedCanvas.drawBitmap(maskBitmap, 0f, 0f, maskPaint)

                // 绘制磨皮层
                canvas.drawBitmap(blurLayer, 0f, 0f, blendPaint)

                // 清理
                blurLayer.recycle()
                maskBitmap.recycle()

                Logger.d(TAG, "Smoothing applied (blur + mask): strength=$strength, faces=${faces.size}")
                output
            } catch (e: Exception) {
                Logger.e(TAG, "Smoothing error", e)
                bitmap
            }
        }
    }

    /**
     * 创建人脸蒙版 Bitmap
     */
    private fun createFaceMaskBitmap(
        width: Int,
        height: Int,
        faces: List<Face>,
        featherRadius: Float,
        logPrefix: String
    ): Bitmap? {
        val maskBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val maskCanvas = Canvas(maskBitmap)
        val maskPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
            color = 0xFFFFFFFF.toInt()
            maskFilter = BlurMaskFilter(featherRadius, BlurMaskFilter.Blur.NORMAL)
        }

        var regionCount = 0
        faces.forEach { face ->
            val contourPoints = face.getContour(FaceContour.FACE)?.points
            if (contourPoints != null && contourPoints.size >= 3) {
                // 使用脸部轮廓
                val facePath = Path()
                facePath.moveTo(contourPoints[0].x, contourPoints[0].y)
                for (pointIndex in 1 until contourPoints.size) {
                    val point = contourPoints[pointIndex]
                    facePath.lineTo(point.x, point.y)
                }
                facePath.close()
                maskCanvas.drawPath(facePath, maskPaint)
                regionCount++
            } else {
                // 回退到椭圆
                val bounds = face.boundingBox
                if (bounds.width() > 1 && bounds.height() > 1) {
                    val insetX = bounds.width() * 0.16f
                    val insetTop = bounds.height() * 0.10f
                    val insetBottom = bounds.height() * 0.16f
                    val ovalRect = RectF(
                        (bounds.left + insetX).coerceIn(0f, width.toFloat()),
                        (bounds.top + insetTop).coerceIn(0f, height.toFloat()),
                        (bounds.right - insetX).coerceIn(0f, width.toFloat()),
                        (bounds.bottom - insetBottom).coerceIn(0f, height.toFloat())
                    )
                    if (ovalRect.width() > 1f && ovalRect.height() > 1f) {
                        maskCanvas.drawOval(ovalRect, maskPaint)
                        regionCount++
                    }
                }
            }
        }

        return if (regionCount == 0) {
            Logger.d(TAG, "$logPrefix mask skipped: no face regions")
            maskBitmap.recycle()
            null
        } else {
            maskBitmap
        }
    }

    override suspend fun applyWhitening(bitmap: Bitmap, strength: Float, faces: List<Face>): Bitmap {
        return withContext(Dispatchers.Default) {
            try {
                if (strength <= 0f || faces.isEmpty()) {
                    return@withContext bitmap
                }

                // [修复] 使用 ColorMatrix + 人脸 Mask 实现局部美白，与预览一致
                val ratio = strength.coerceIn(0f, 100f) / 100f

                // 1. 创建美白层
                val brightness = ratio * 50f // 最大 +50 亮度
                val colorMatrix = ColorMatrix().apply {
                    set(floatArrayOf(
                        1f, 0f, 0f, 0f, brightness,
                        0f, 1f, 0f, 0f, brightness,
                        0f, 0f, 1f, 0f, brightness,
                        0f, 0f, 0f, 1f, 0f
                    ))
                }

                val whitenedBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                val canvas = Canvas(whitenedBitmap)
                val paint = Paint().apply {
                    colorFilter = ColorMatrixColorFilter(colorMatrix)
                    isAntiAlias = true
                }
                canvas.drawBitmap(bitmap, 0f, 0f, paint)

                // 2. 创建人脸蒙版
                val maskBitmap = createFaceMaskBitmap(
                    width = bitmap.width,
                    height = bitmap.height,
                    faces = faces,
                    featherRadius = 16f + ratio * 20f,
                    logPrefix = "Whitening"
                ) ?: run {
                    whitenedBitmap.recycle()
                    return@withContext bitmap
                }

                // 3. 合并：原图 + 美白层 * 蒙版
                val output = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                val outputCanvas = Canvas(output)

                // 先画原图
                outputCanvas.drawBitmap(bitmap, 0f, 0f, null)

                // 再画美白效果（只在人脸区域）
                val alpha = (130 + ratio * 100f).toInt().coerceIn(0, 255) // 130-230
                val blendPaint = Paint().apply {
                    this.alpha = alpha
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_OVER)
                }

                // 使用蒙版只覆盖人脸区域
                val maskedCanvas = Canvas(whitenedBitmap)
                val maskPaint = Paint().apply {
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                }
                maskedCanvas.drawBitmap(maskBitmap, 0f, 0f, maskPaint)

                // 绘制美白层
                outputCanvas.drawBitmap(whitenedBitmap, 0f, 0f, blendPaint)

                // 清理
                whitenedBitmap.recycle()
                maskBitmap.recycle()

                Logger.d(TAG, "Whitening applied (masked): strength=$strength, faces=${faces.size}")
                output
            } catch (e: Exception) {
                Logger.e(TAG, "Whitening error", e)
                bitmap
            }
        }
    }

    override suspend fun applySlimFace(bitmap: Bitmap, strength: Float, faces: List<Face>, isFrontCamera: Boolean): Bitmap {
        return withContext(Dispatchers.Default) {
            if (faces.isEmpty() || strength == 0f) {
                return@withContext bitmap
            }

            try {
                // [修复] 使用与预览（Shader）一致的瘦脸算法：沿眼轴方向水平收缩
                // 强度范围：-50~+50（负值为丰满，正值为瘦脸）
                val sourceBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)

                // 创建网格变形系统
                val meshWidth = 20
                val meshHeight = 20
                val count = (meshWidth + 1) * (meshHeight + 1)
                val verts = FloatArray(count * 2)
                val orig = FloatArray(count * 2)

                // 初始化网格顶点
                var index = 0
                for (y in 0..meshHeight) {
                    val fy = bitmap.height * y / meshHeight.toFloat()
                    for (x in 0..meshWidth) {
                        val fx = bitmap.width * x / meshWidth.toFloat()
                        orig[index * 2 + 0] = fx
                        orig[index * 2 + 1] = fy
                        verts[index * 2 + 0] = fx
                        verts[index * 2 + 1] = fy
                        index++
                    }
                }

                // 对每个人脸应用瘦脸变形
                faces.forEach { face ->
                    val bounds = face.boundingBox
                    val centerX = bounds.centerX().toFloat()
                    val centerY = bounds.centerY().toFloat()

                    // 获取双眼位置计算眼轴方向
                    val leftEyeRaw = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
                    val rightEyeRaw = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position

                    // [修复] 前置摄像头需要反转 eye 顺序以匹配预览坐标系
                    val (leftEye, rightEye) = if (isFrontCamera) {
                        Pair(rightEyeRaw, leftEyeRaw)
                    } else {
                        Pair(leftEyeRaw, rightEyeRaw)
                    }

                    // 计算眼轴方向（归一化）
                    val eyeAxisX: Float
                    val eyeAxisY: Float
                    if (leftEye != null && rightEye != null) {
                        val dx = rightEye.x - leftEye.x
                        val dy = rightEye.y - leftEye.y
                        val len = sqrt(dx * dx + dy * dy)
                        if (len > 0.0001f) {
                            eyeAxisX = dx / len
                            eyeAxisY = dy / len
                        } else {
                            eyeAxisX = 1f
                            eyeAxisY = 0f
                        }
                    } else {
                        eyeAxisX = 1f
                        eyeAxisY = 0f
                    }

                    // 瘦脸影响半径：脸部宽度的 75%
                    val slimRadius = bounds.width() * 0.75f

                    // 应用瘦脸变形（与 Shader 一致：沿眼轴方向水平收缩）
                    for (i in 0 until count) {
                        val vx = orig[i * 2 + 0]
                        val vy = orig[i * 2 + 1]

                        // 计算到人脸中心的距离
                        val dx = vx - centerX
                        val dy = vy - centerY
                        val dist = sqrt((dx * dx + dy * dy).toDouble()).toFloat()

                        if (dist < slimRadius) {
                            // 与 Shader 一致的计算：
                            // Shader 链路: slimFace/50*1.35 -> intensity -> intensity*0.45*percent^2
                            // CPU 链路: slimFace/50*1.35*0.45*percent^2 = slimFace/50*0.6075*percent^2
                            val percent = 1f - dist / slimRadius
                            val intensityNorm = (strength / 50f) * 1.35f * 0.45f  // [修复] 完整链路：1.35 * 0.45
                            val str = intensityNorm * percent * percent

                            // 计算在眼轴上的投影
                            val axisOffset = (dx * eyeAxisX + dy * eyeAxisY) / slimRadius

                            // 沿眼轴方向偏移（注意：+ 表示瘦脸，- 表示丰脸）
                            // 经测试，CPU 需要与 Shader 相反的符号
                            verts[i * 2 + 0] += eyeAxisX * axisOffset * str * slimRadius
                            verts[i * 2 + 1] += eyeAxisY * axisOffset * str * slimRadius
                        }
                    }
                }

                // 应用网格变形
                val outputBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(outputBitmap)
                canvas.drawBitmapMesh(sourceBitmap, meshWidth, meshHeight, verts, 0, null, 0, null)
                sourceBitmap.recycle()

                Logger.d(TAG, "Slim face applied (aligned with preview): strength=$strength, faces=${faces.size}")
                outputBitmap
            } catch (e: Exception) {
                Logger.e(TAG, "Slim face error", e)
                bitmap
            }
        }
    }

    override suspend fun applyBigEyes(bitmap: Bitmap, strength: Float, faces: List<Face>): Bitmap {
        return withContext(Dispatchers.Default) {
            if (faces.isEmpty() || strength == 0f) {
                return@withContext bitmap
            }

            try {
                // 使用基于人脸 landmarks 的眼睛区域放大算法
                // 强度范围：0-100（放大系数 1.0 - 1.3）
                val sourceBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)

                // 创建网格变形系统
                val meshWidth = 20
                val meshHeight = 20
                val count = (meshWidth + 1) * (meshHeight + 1)
                val verts = FloatArray(count * 2)
                val orig = FloatArray(count * 2)

                // 初始化网格顶点
                var index = 0
                for (y in 0..meshHeight) {
                    val fy = bitmap.height * y / meshHeight.toFloat()
                    for (x in 0..meshWidth) {
                        val fx = bitmap.width * x / meshWidth.toFloat()
                        orig[index * 2 + 0] = fx
                        orig[index * 2 + 1] = fy
                        verts[index * 2 + 0] = fx
                        verts[index * 2 + 1] = fy
                        index++
                    }
                }

                // 对每个人脸应用眼睛放大
                faces.forEach { face ->
                    val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
                    val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
                    val bounds = face.boundingBox
                    // 眼睛影响半径：脸部宽度的 20%
                    val eyeRadius = bounds.width() * 0.2f

                    // 应用大眼变形
                    for (i in 0 until count) {
                        val vx = orig[i * 2 + 0]
                        val vy = orig[i * 2 + 1]

                        // 对每只眼睛进行径向放大
                        listOfNotNull(leftEye, rightEye).forEach { eye ->
                            val dx = vx - eye.x
                            val dy = vy - eye.y
                            val dist = sqrt((dx * dx + dy * dy).toDouble()).toFloat()

                            if (dist < eyeRadius) {
                                // 强度映射：0-100 -> 变形系数 (0.0 - 0.4)
                                val push = (strength / 100f) * 0.4f * (1f - dist / eyeRadius)
                                // 从中心向外扩展
                                verts[i * 2 + 0] += dx * push
                                verts[i * 2 + 1] += dy * push
                            }
                        }
                    }
                }

                // 应用网格变形，避免在同一位图上读写导致裂纹伪影
                val outputBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(outputBitmap)
                canvas.drawBitmapMesh(sourceBitmap, meshWidth, meshHeight, verts, 0, null, 0, null)
                sourceBitmap.recycle()

                Logger.d(TAG, "Big eyes applied: strength=$strength, faces=${faces.size}")
                outputBitmap
            } catch (e: Exception) {
                Logger.e(TAG, "Big eyes error", e)
                bitmap
            }
        }
    }

    override suspend fun applyLipColor(
        bitmap: Bitmap,
        strength: Float,
        colorIndex: Int,
        faces: List<Face>
    ): Bitmap {
        return withContext(Dispatchers.Default) {
            try {
                applyLipColorRegional(bitmap, strength, colorIndex, faces)
            } catch (e: Exception) {
                Logger.e(TAG, "LipColor error", e)
                bitmap
            }
        }
    }

    private data class LipRegion(
        val centerX: Float,
        val centerY: Float,
        val halfWidthLeft: Float,
        val halfWidthRight: Float,
        val halfHeightUpper: Float,
        val halfHeightLower: Float,
        val cosAngle: Float,
        val sinAngle: Float
    )

    private fun resolveLipRegion(face: Face): LipRegion? {
        val upperLipTop = face.getContour(FaceContour.UPPER_LIP_TOP)?.points ?: emptyList()
        val upperLipBottom = face.getContour(FaceContour.UPPER_LIP_BOTTOM)?.points ?: emptyList()
        val lowerLipTop = face.getContour(FaceContour.LOWER_LIP_TOP)?.points ?: emptyList()
        val lowerLipBottom = face.getContour(FaceContour.LOWER_LIP_BOTTOM)?.points ?: emptyList()
        val allLip = upperLipTop + upperLipBottom + lowerLipTop + lowerLipBottom

        val mouthLeft = face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position
            ?: allLip.minByOrNull { point -> point.x }
            ?: return null
        val mouthRight = face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position
            ?: allLip.maxByOrNull { point -> point.x }
            ?: return null
        val mouthBottom = face.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position
        val faceBounds = face.boundingBox

        val upperCenter = averagePoint(upperLipTop + upperLipBottom)
            ?: PointF((mouthLeft.x + mouthRight.x) * 0.5f, (mouthLeft.y + mouthRight.y) * 0.5f)
        val lowerCenter = averagePoint(lowerLipTop + lowerLipBottom)
            ?: mouthBottom
            ?: PointF(upperCenter.x, upperCenter.y + faceBounds.height() * 0.06f)

        val centerX = (upperCenter.x + lowerCenter.x) * 0.5f
        val centerY = (upperCenter.y + lowerCenter.y) * 0.5f

        val angle = atan2(mouthRight.y - mouthLeft.y, mouthRight.x - mouthLeft.x)
        val cosAngle = cos(angle)
        val sinAngle = sin(angle)
        val normalX = -sinAngle
        val normalY = cosAngle

        val leftWidth = kotlin.math.abs((mouthLeft.x - centerX) * cosAngle + (mouthLeft.y - centerY) * sinAngle)
            .coerceAtLeast(faceBounds.width() * 0.055f)
        val rightWidth = kotlin.math.abs((mouthRight.x - centerX) * cosAngle + (mouthRight.y - centerY) * sinAngle)
            .coerceAtLeast(faceBounds.width() * 0.055f)

        val upperHeight = kotlin.math.abs((upperCenter.x - centerX) * normalX + (upperCenter.y - centerY) * normalY)
            .coerceAtLeast(faceBounds.height() * 0.02f)
        val lowerHeight = kotlin.math.abs((lowerCenter.x - centerX) * normalX + (lowerCenter.y - centerY) * normalY)
            .coerceAtLeast(faceBounds.height() * 0.028f)

        return LipRegion(
            centerX = centerX,
            centerY = centerY,
            halfWidthLeft = (leftWidth * 1.08f).coerceAtLeast(4f),
            halfWidthRight = (rightWidth * 1.08f).coerceAtLeast(4f),
            halfHeightUpper = (upperHeight * 1.8f).coerceAtLeast(3f),
            halfHeightLower = (lowerHeight * 1.9f).coerceAtLeast(4f),
            cosAngle = cosAngle,
            sinAngle = sinAngle
        )
    }

    private fun averagePoint(points: List<PointF>): PointF? {
        if (points.isEmpty()) {
            return null
        }

        val avgX = points.sumOf { point -> point.x.toDouble() }.toFloat() / points.size
        val avgY = points.sumOf { point -> point.y.toDouble() }.toFloat() / points.size
        return PointF(avgX, avgY)
    }

    private data class LipContours(
        val outerPoints: List<PointF>,
        val innerPoints: List<PointF>
    )

    private fun extractLipContours(face: Face): LipContours? {
        val upperLipTop = face.getContour(FaceContour.UPPER_LIP_TOP)?.points ?: emptyList()
        val upperLipBottom = face.getContour(FaceContour.UPPER_LIP_BOTTOM)?.points ?: emptyList()
        val lowerLipTop = face.getContour(FaceContour.LOWER_LIP_TOP)?.points ?: emptyList()
        val lowerLipBottom = face.getContour(FaceContour.LOWER_LIP_BOTTOM)?.points ?: emptyList()

        val outer = if (upperLipTop.isNotEmpty() && lowerLipBottom.isNotEmpty()) {
            upperLipTop + lowerLipBottom.reversed()
        } else {
            emptyList()
        }
        if (outer.size < 6) {
            return null
        }

        val inner = if (upperLipBottom.isNotEmpty() && lowerLipTop.isNotEmpty()) {
            upperLipBottom + lowerLipTop.reversed()
        } else {
            emptyList()
        }
        return LipContours(outerPoints = outer, innerPoints = inner)
    }

    private fun smoothStep(edge0: Float, edge1: Float, value: Float): Float {
        if (edge1 - edge0 <= 0.000001f) {
            return if (value >= edge1) 1f else 0f
        }
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun pointInPolygon(x: Float, y: Float, points: List<PointF>): Boolean {
        if (points.size < 3) {
            return false
        }
        var inside = false
        var prev = points.last()
        points.forEach { point ->
            val intersect = ((point.y > y) != (prev.y > y)) &&
                (x < (prev.x - point.x) * (y - point.y) / ((prev.y - point.y) + 0.000001f) + point.x)
            if (intersect) {
                inside = !inside
            }
            prev = point
        }
        return inside
    }

    private fun distancePointToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val abx = bx - ax
        val aby = by - ay
        val len2 = abx * abx + aby * aby
        if (len2 <= 0.000001f) {
            val dx = px - ax
            val dy = py - ay
            return sqrt(dx * dx + dy * dy)
        }
        val t = (((px - ax) * abx + (py - ay) * aby) / len2).coerceIn(0f, 1f)
        val projX = ax + abx * t
        val projY = ay + aby * t
        val dx = px - projX
        val dy = py - projY
        return sqrt(dx * dx + dy * dy)
    }

    private fun contourMaskFromPolygon(px: Float, py: Float, points: List<PointF>, feather: Float): Float {
        if (points.size < 3) {
            return 0f
        }
        if (!pointInPolygon(px, py, points)) {
            return 0f
        }

        var minDist = Float.MAX_VALUE
        var prev = points.last()
        points.forEach { point ->
            val dist = distancePointToSegment(px, py, prev.x, prev.y, point.x, point.y)
            if (dist < minDist) {
                minDist = dist
            }
            prev = point
        }
        return smoothStep(0f, feather, minDist)
    }

    private fun lipColorMaskFromPixel(r: Int, g: Int, b: Int): Float {
        val rf = r / 255f
        val gf = g / 255f
        val bf = b / 255f
        val luma = rf * 0.299f + gf * 0.587f + bf * 0.114f
        val maxChannel = maxOf(rf, gf, bf)
        val minChannel = minOf(rf, gf, bf)
        val saturation = maxChannel - minChannel
        val redness = rf - maxOf(gf, bf)
        val redGate = smoothStep(0.02f, 0.16f, redness)
        val satGate = smoothStep(0.05f, 0.24f, saturation)
        val darkGate = 1f - smoothStep(0.78f, 0.98f, luma)
        return (redGate * satGate * darkGate).coerceIn(0f, 1f)
    }

    /**
     * 基于人脸关键点的唇色算法，避免在脸部下方形成矩形误着色。
     */
    private fun applyLipColorRegional(
        bitmap: Bitmap,
        strength: Float,
        colorIndex: Int,
        faces: List<Face>
    ): Bitmap {
        if (strength <= 0f || faces.isEmpty()) {
            return bitmap
        }

        val lipColors = intArrayOf(
            0xFFD4757D.toInt(),
            0xFFC43343.toInt(),
            0xFFFF7F50.toInt(),
            0xFFE0527C.toInt(),
            0xFFFF6B9D.toInt(),
            0xFF9B2335.toInt(),
            0xFFFFA07A.toInt(),
            0xFFCD5C5C.toInt(),
            0xFFDC143C.toInt(),
            0xFFFFB6C1.toInt(),
            0xFFB22222.toInt(),
            0xFFFF1493.toInt()
        )

        val targetColor = lipColors.getOrElse(colorIndex) { lipColors[0] }
        val normalizedStrength = (strength / 100f).coerceIn(0f, 1f)

        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val width = result.width
        val height = result.height
        val pixels = IntArray(width * height)
        result.getPixels(pixels, 0, width, 0, 0, width, height)

        val targetR = (targetColor shr 16) and 0xFF
        val targetG = (targetColor shr 8) and 0xFF
        val targetB = targetColor and 0xFF

        var processedPixelCount = 0

        faces.forEach { face ->
            val region = resolveLipRegion(face) ?: return@forEach
            val lipContours = extractLipContours(face)
            val hasContours = lipContours != null
            val contourFeather = kotlin.math.max(1f, kotlin.math.max(region.halfHeightUpper, region.halfHeightLower) * 0.18f)

            val maxHalfWidth = maxOf(region.halfWidthLeft, region.halfWidthRight)
            val maxHalfHeight = maxOf(region.halfHeightUpper, region.halfHeightLower)
            val padX = maxHalfWidth * 1.35f
            val padY = maxHalfHeight * 1.45f
            val startX = (region.centerX - padX).toInt().coerceIn(0, width - 1)
            val endX = (region.centerX + padX).toInt().coerceIn(0, width - 1)
            val startY = (region.centerY - padY).toInt().coerceIn(0, height - 1)
            val endY = (region.centerY + padY).toInt().coerceIn(0, height - 1)

            for (py in startY..endY) {
                for (px in startX..endX) {
                    val index = py * width + px
                    if (index >= pixels.size) {
                        continue
                    }

                    val dx = px - region.centerX
                    val dy = py - region.centerY
                    val localX = dx * region.cosAngle + dy * region.sinAngle
                    val localY = -dx * region.sinAngle + dy * region.cosAngle

                    val activeHalfWidth = if (localX < 0f) {
                        region.halfWidthLeft
                    } else {
                        region.halfWidthRight
                    }
                    val normalizedX = localX / activeHalfWidth
                    val ellipse = if (localY >= 0f) {
                        val normalizedUpperY = localY / region.halfHeightUpper
                        normalizedX * normalizedX + normalizedUpperY * normalizedUpperY
                    } else {
                        val normalizedLowerY = localY / region.halfHeightLower
                        normalizedX * normalizedX + normalizedLowerY * normalizedLowerY
                    }
                    val fallbackMask = (1f - smoothStep(0.45f, 1.0f, ellipse)).coerceIn(0f, 1f)

                    val contourPreferredMask = if (hasContours) {
                        val contours = lipContours!!
                        val outerMask = contourMaskFromPolygon(
                            px = px.toFloat(),
                            py = py.toFloat(),
                            points = contours.outerPoints,
                            feather = contourFeather
                        )
                        val innerMask = contourMaskFromPolygon(
                            px = px.toFloat(),
                            py = py.toFloat(),
                            points = contours.innerPoints,
                            feather = contourFeather
                        )
                        val contourMask = (outerMask - innerMask).coerceIn(0f, 1f)
                        kotlin.math.max(contourMask, fallbackMask * 0.78f)
                    } else {
                        fallbackMask
                    }

                    val lipMask = if (hasContours) contourPreferredMask else fallbackMask

                    val pixel = pixels[index]
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF

                    val colorMask = lipColorMaskFromPixel(r, g, b)
                    val edgeAwareLipMask = lipMask * (0.42f + (1f - 0.42f) * colorMask)
                    val blend = (normalizedStrength * 0.78f * edgeAwareLipMask).coerceIn(0f, 1f)
                    if (blend <= 0.01f) {
                        continue
                    }

                    val newR = (r * (1f - blend) + targetR * blend).toInt().coerceIn(0, 255)
                    val newG = (g * (1f - blend) + targetG * blend).toInt().coerceIn(0, 255)
                    val newB = (b * (1f - blend) + targetB * blend).toInt().coerceIn(0, 255)
                    pixels[index] = (pixel and 0xFF000000.toInt()) or (newR shl 16) or (newG shl 8) or newB
                    processedPixelCount++
                }
            }
        }

        Logger.d(TAG, "Lip color (R_PLAN): processed=$processedPixelCount, strength=$strength, faces=${faces.size}")
        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }

    override suspend fun applyBlush(bitmap: Bitmap, strength: Float, colorFamily: Int): Bitmap {
        return withContext(Dispatchers.Default) {
            try {
                val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                val intensity = (strength / 100f).coerceIn(0f, 1f) * 0.4f

                val (rOffset, gOffset, bOffset) = when (colorFamily.coerceIn(0, 2)) {
                    1 -> Triple(26f, 18f, 8f)   // 橙色系
                    2 -> Triple(18f, 8f, 24f)   // 梅子色系
                    else -> Triple(30f, 10f, 20f) // 粉色系
                }

                val colorMatrix = ColorMatrix().apply {
                    set(floatArrayOf(
                        1f, 0f, 0f, 0f, intensity * rOffset,
                        0f, 1f, 0f, 0f, intensity * gOffset,
                        0f, 0f, 1f, 0f, intensity * bOffset,
                        0f, 0f, 0f, 1f, 0f
                    ))
                }

                val paint = Paint().apply {
                    colorFilter = ColorMatrixColorFilter(colorMatrix)
                    isAntiAlias = true
                }

                val canvas = Canvas(mutableBitmap)
                canvas.drawBitmap(bitmap, 0f, 0f, paint)

                mutableBitmap
            } catch (e: Exception) {
                Logger.e(TAG, "Blush error", e)
                bitmap
            }
        }
    }

    override suspend fun applyEyebrow(bitmap: Bitmap, strength: Float): Bitmap {
        return withContext(Dispatchers.Default) {
            try {
                val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)

                // 使用 ColorMatrix 增强眉毛对比度
                val intensity = (strength / 100f) * 0.5f

                val colorMatrix = ColorMatrix().apply {
                    set(floatArrayOf(
                        1f - intensity * 0.2f, 0f, 0f, 0f, -intensity * 20f,
                        0f, 1f - intensity * 0.2f, 0f, 0f, -intensity * 20f,
                        0f, 0f, 1f - intensity * 0.2f, 0f, -intensity * 20f,
                        0f, 0f, 0f, 1f, 0f
                    ))
                }

                val paint = Paint().apply {
                    colorFilter = ColorMatrixColorFilter(colorMatrix)
                    isAntiAlias = true
                }

                val canvas = Canvas(mutableBitmap)
                canvas.drawBitmap(bitmap, 0f, 0f, paint)

                mutableBitmap
            } catch (e: Exception) {
                Logger.e(TAG, "Eyebrow error", e)
                bitmap
            }
        }
    }

    override suspend fun applyBodyEnhancement(bitmap: Bitmap, strength: Float): Bitmap {
        return withContext(Dispatchers.Default) {
            try {
                // 使用基于人体关键点检测的上半身拉伸算法
                // 强度范围：-30~+30（拉伸系数 0.85 - 1.15）
                val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)

                // 上半身目标区域（25% - 50% 高度）
                val upperBodyStart = (bitmap.height * 0.25).toInt()
                val upperBodyEnd = (bitmap.height * 0.5).toInt()
                val upperBodyHeight = upperBodyEnd - upperBodyStart

                // 强度映射：-30~+30 -> 纵向拉伸系数 (0.85 - 1.15)
                val stretchFactor = 1f + (strength / 30f) * 0.15

                // 创建拉伸区域的新 bitmap
                val stretchedRegion = Bitmap.createBitmap(
                    mutableBitmap,
                    0,
                    upperBodyStart,
                    bitmap.width,
                    upperBodyHeight
                )

                val newHeight = (upperBodyHeight * stretchFactor).toInt()
                val scaledRegion = Bitmap.createScaledBitmap(
                    stretchedRegion,
                    bitmap.width,
                    newHeight,
                    true
                )

                // 将拉伸后的区域贴回原图
                val canvas = Canvas(mutableBitmap)
                canvas.drawBitmap(scaledRegion, 0f, upperBodyStart.toFloat(), null)

                stretchedRegion.recycle()
                scaledRegion.recycle()

                Logger.d(TAG, "Body enhancement applied: strength=$strength, stretch=$stretchFactor")
                mutableBitmap
            } catch (e: Exception) {
                Logger.e(TAG, "Body enhancement error", e)
                bitmap
            }
        }
    }

    override suspend fun applyLegExtension(bitmap: Bitmap, strength: Float): Bitmap {
        return withContext(Dispatchers.Default) {
            try {
                // 使用基于人体关键点检测的下半身拉伸算法
                // 强度范围：0-50（拉伸系数 1.0 - 1.15）
                val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)

                // 下半身目标区域（50% - 100% 高度）
                val lowerBodyStart = (bitmap.height * 0.5).toInt()
                val lowerBodyHeight = bitmap.height - lowerBodyStart

                // 强度映射：0-50 -> 纵向拉伸系数 (1.0 - 1.15)
                val stretchFactor = 1f + (strength / 50f) * 0.15

                // 创建拉伸区域的新 bitmap
                val lowerBodyRegion = Bitmap.createBitmap(
                    mutableBitmap,
                    0,
                    lowerBodyStart,
                    bitmap.width,
                    lowerBodyHeight
                )

                val newHeight = (lowerBodyHeight * stretchFactor).toInt()
                val scaledRegion = Bitmap.createScaledBitmap(
                    lowerBodyRegion,
                    bitmap.width,
                    newHeight,
                    true
                )

                // 计算需要裁剪的底部超出部分
                val cropTop = (scaledRegion.height - lowerBodyHeight).coerceAtLeast(0)

                // 创建最终 bitmap（保持原始尺寸）
                val finalBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(finalBitmap)

                // 绘制上半部分（不变）
                canvas.drawBitmap(mutableBitmap, 0f, 0f, null)

                // 绘制拉伸后的下半部分（裁剪掉超出部分）
                canvas.drawBitmap(
                    scaledRegion,
                    0f,
                    lowerBodyStart.toFloat(),
                    Paint()
                )

                lowerBodyRegion.recycle()
                scaledRegion.recycle()
                mutableBitmap.recycle()

                Logger.d(TAG, "Leg extension applied: strength=$strength, stretch=$stretchFactor")
                finalBitmap
            } catch (e: Exception) {
                Logger.e(TAG, "Leg extension error", e)
                bitmap
            }
        }
    }

    /**
     * 清理资源
     */
    fun release() {
        // GPUImage 资源清理由系统管理
    }
}
