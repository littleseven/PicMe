package com.mamba.picme.beauty.internal.facedetect.adapter

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.mamba.picme.beauty.api.facedetect.FaceDetectionSource
import com.mamba.picme.beauty.internal.facedetect.adapter.MediaPipe468Adapter.Companion.NON_CONTOUR_MAPPING

// CameraSelector.LENS_FACING_FRONT = 0, LENS_FACING_BACK = 1
private const val LENS_FACING_FRONT = 0

/**
 * MediaPipe 468 点 → 统一 106 点适配器
 *
 * 将 MediaPipe Face Landmarker 输出的 468 个 3D 关键点
 * 映射为与字节火山引擎兼容的 106 点标准格式。
 *
 * 映射规则（严格遵循现有实现，零修改）：
 * - 轮廓 33 点（0-32）：基于 MediaPipe FACE_OVAL 路径插值生成
 * - 非轮廓 73 点（33-105）：通过固定映射表 [NON_CONTOUR_MAPPING] 直接转换
 * - 前置摄像头时水平镜像 x 坐标
 *
 * 参考文档：
 * - docs/03-TECHNICAL-SPECS/VOLCANO_106_POINTS.md
 * - docs/03-TECHNICAL-SPECS/MEDIAPIPE_468_REFERENCE.md
 * - docs/03-TECHNICAL-SPECS/MEDIAPIPE_468_TO_106_MAPPING_STRATEGY.md
 */
class MediaPipe468Adapter : FaceLandmarkAdapter {

    override val detectionSource: FaceDetectionSource = FaceDetectionSource.MEDIAPIPE

    companion object {
        private const val TAG = "MediaPipeAdapter"

        const val POINT_COUNT = 106
        const val CONTOUR_POINT_COUNT = 33
        const val NON_CONTOUR_POINT_COUNT = 73

        // MediaPipe 468 → 字节火山引擎 106 点映射表
        // 映射依据：docs/03-TECHNICAL-SPECS/VOLCANO_106_POINTS.md + docs/03-TECHNICAL-SPECS/MEDIAPIPE_468_REFERENCE.md
        // 106点标准拓扑（被摄者真实面部，前置摄像头镜像后）——严格遵循火山引擎标准：
        //   0-16: 右轮廓17点（画面左侧，从右上到下巴右）
        //   17-32: 左轮廓16点（画面右侧，从下巴左到左上）
        //   33-37: 右眉上部5点（画面左侧，从眉头到眉尾）
        //   38-42: 左眉上部5点（画面右侧，从眉头到眉尾）
        //   43: 眉心
        //   44-46: 鼻梁3点（从上到下：44=鼻根/山根, 45=鼻梁中, 46=鼻梁下）
        //   47-51: 鼻尖5点（从左到右，47=右鼻翼上/画面左侧, 48=鼻尖右, 49=鼻尖中心, 50=鼻尖左, 51=左鼻翼上/画面右侧）
        //   52-57: 右眼外轮廓6点（画面左侧，从外角到内角）
        //   58-63: 左眼外轮廓6点（画面右侧，从外角到内角）
        //   64-67: 右眉下部4点（画面左侧，从眉头到眉尾）
        //   68-71: 左眉下部4点（画面右侧，从眉尾到眉头）
        //   72-74: 右眼补充3点（72=右眼下眼睑中, 73=右眼下眼睑外, 74=右瞳孔）
        //   75-77: 左眼补充3点（75=左眼下眼睑中, 76=左眼下眼睑外, 77=左瞳孔）
        //   78-79: 山根2点（78=山根右/画面左侧, 79=山根左/画面右侧）
        //   80-83: 鼻孔4点（80=右鼻孔左/画面左侧, 81=右鼻孔右, 82=左鼻孔左, 83=左鼻孔右/画面右侧）
        //   84-95: 嘴巴外轮廓12点（84=右嘴角/画面左侧, 94=左嘴角/画面右侧）
        //   96-103: 嘴巴内轮廓8点（96=右内角, 100=左内角）
        //   104-105: 瞳孔2点（104=右瞳孔/画面左侧, 105=左瞳孔/画面右侧）
        // 非轮廓区域映射表（33-105，共73点）
        private val NON_CONTOUR_MAPPING = intArrayOf(
            // === 右眉上部 33-37 (5点) - 画面左侧=实际右脸，从眉头到眉尾 ===
            70, 63, 105, 66, 107,

            // === 左眉上部 38-42 (5点) - 画面右侧=实际左脸，从眉头到眉尾 ===
            336, 296, 334, 293, 300,

            // === 眉心 43 ===
            168,

            // === 鼻梁 44-46 (3点) - 从上到下 ===
            197, 5, 4,

            // === 鼻尖 47-51 (5点) - 从左到右，严格对称 ===
            98, 241, 2, 461, 327,

            // === 右眼 52-57 (6点) - 画面左侧=实际右脸 ===
            226, 30, 56, 133, 26, 110,

            // === 左眼 58-63 (6点) - 画面右侧=实际左脸 ===
            362, 286, 260, 446, 339, 256,

            // === 右眉下部 64-67 (4点) - 画面左侧=实际右脸，从眉头到眉尾 ===
            53, 52, 65, 55,

            // === 左眉下部 68-71 (4点) - 画面右侧=实际左脸，从眉尾到眉头 ===
            285, 295, 282, 283,

            // === 右眼补充 72-74 (3点) - 画面左侧 ===
            374, 375, 473,

            // === 左眼补充 75-77 (3点) - 画面右侧 ===
            44, 45, 468,

            // === 山根 78-79 (2点) ===
            193, 417,

            // === 鼻孔 80-83 (4点) ===
            198, 420, 49, 279,

            // === 嘴巴外轮廓 84-95 (12点) - 顺时针闭合曲线 ===
            61, 40, 37, 0, 267, 270, 291, 321, 314, 17, 84, 91,

            // === 嘴巴内轮廓 96-103 (8点) - 顺时针闭合曲线 ===
            78, 81, 13, 311, 308, 178, 14, 402,

            // === 瞳孔 104-105 (2点) ===
            473, 468
        )
    }

    override fun adapt(nativeLandmarks: FloatArray, lensFacing: Int): Result<FloatArray> {
        // MediaPipe 468 点适配器需要 NormalizedLandmark 列表输入
        // 但接口统一使用 FloatArray，因此需要调用方先将 MediaPipe 结果转为 FloatArray
        // 这里我们提供重载方法接受 NormalizedLandmark 列表
        return Result.failure(
            UnsupportedOperationException(
                "MediaPipe468Adapter requires NormalizedLandmark list input. " +
                    "Use adapt(landmarks: List<NormalizedLandmark>, lensFacing: Int) instead."
            )
        )
    }

    /**
     * MediaPipe 专用适配方法（兼容旧接口，rotationDegrees 默认 0）
     */
    fun adapt(landmarks: List<NormalizedLandmark>, lensFacing: Int): Result<FloatArray> {
        return adapt(landmarks, lensFacing, 0)
    }

    /**
     * MediaPipe 专用适配方法
     *
     * @param landmarks MediaPipe 468 个 NormalizedLandmark
     * @param lensFacing 镜头方向
     * @param rotationDegrees 图像旋转角度（0/90/180/270）。
     *                        MediaPipe 直接检测原始 YUV Image 时，
     *                        结果坐标需旋转后才能与预览/渲染坐标系对齐。
     * @return 统一 106 点 FloatArray(212)
     */
    fun adapt(landmarks: List<NormalizedLandmark>, lensFacing: Int, rotationDegrees: Int): Result<FloatArray> {
        if (landmarks.size < 468) {
            return Result.failure(
                IllegalArgumentException(
                    "MediaPipe landmarks size ${landmarks.size} < required 468"
                )
            )
        }

        val result = FloatArray(POINT_COUNT * 2)
        val isFrontCamera = lensFacing == LENS_FACING_FRONT

        // 辅助函数：旋转归一化坐标
        // MediaPipe 在原始（未旋转）图像上检测，输出坐标基于原始图像方向。
        // 需根据 rotationDegrees 旋转到与预览/渲染对齐的方向。
        fun rotateNormalized(x: Float, y: Float, degrees: Int): Pair<Float, Float> {
            return when (degrees) {
                90 -> Pair(1f - y, x)
                180 -> Pair(1f - x, 1f - y)
                270 -> Pair(y, 1f - x)
                else -> Pair(x, y)
            }
        }

        // 辅助函数：获取 MediaPipe 点坐标
        // [坐标系对齐] 后置 textureMatrix(det=-1) 已包含 X 轴翻转，FBO 人脸朝右；
        // 前置 textureMatrix(det=1) 无翻转，FBO 人脸朝左。
        // MediaPipe 原始输出人脸朝右，因此前置需要做 x=1-x 镜像才能与 FBO 对齐。
        fun getMpPoint(index: Int): Pair<Float, Float>? {
            if (index >= landmarks.size) return null
            val landmark = landmarks[index]
            var x = landmark.x()
            val y = landmark.y()

            // 先旋转坐标（针对直接 YUV Image 检测路径）
            val (rx, ry) = rotateNormalized(x, y, rotationDegrees)
            x = rx
            val finalY = ry

            if (isFrontCamera) {
                x = 1f - x
            }
            return Pair(x, finalY)
        }

        // 辅助函数：计算两点中点
        fun midPoint(p1: Pair<Float, Float>?, p2: Pair<Float, Float>?): Pair<Float, Float>? {
            if (p1 == null || p2 == null) return null
            return Pair((p1.first + p2.first) / 2f, (p1.second + p2.second) / 2f)
        }

        // 辅助函数：设置106点坐标
        fun setPoint(idx: Int, point: Pair<Float, Float>?) {
            if (point == null) return
            result[idx * 2] = point.first.coerceIn(0f, 1f)
            result[idx * 2 + 1] = point.second.coerceIn(0f, 1f)
        }

        // === 生成33个轮廓点（0-32）===
        // 规则：M0=127, M16=152, M32=356，沿 FACE_OVAL 路径均匀插值
        // MediaPipe FACE_OVAL 点序：[10,338,297,332,284,251,389,356,454,323,361,288,397,365,379,378,400,377,152,148,176,149,150,136,172,58,132,93,234,127,162,21,54,103,67,109]
        // 注意：106点为开放曲线（不含额头），M0和M32与上眼皮位置齐平

        // M0-M16：从 127 沿 FACE_OVAL 逆时针到 152
        // FACE_OVAL 从 127 到 152 的路径（逆时针）：127→234→93→132→58→172→136→150→149→176→148→152
        val leftContourBasePoints = listOf(127, 234, 93, 132, 58, 172, 136, 150, 149, 176, 148, 152)
            .mapNotNull { idx -> getMpPoint(idx) }

        // M16-M32：从 152 沿 FACE_OVAL 逆时针到 356
        // FACE_OVAL 从 152 继续：152→377→400→378→379→365→397→288→361→323→454→356
        val rightContourBasePoints = listOf(152, 377, 400, 378, 379, 365, 397, 288, 361, 323, 454, 356)
            .mapNotNull { idx -> getMpPoint(idx) }

        // 沿路径均匀插值生成 33 点
        // M0-M16 (17点)
        for (i in 0..16) {
            val t = i.toFloat() / 16f
            val pos = t * (leftContourBasePoints.size - 1)
            val idx = pos.toInt().coerceIn(0, leftContourBasePoints.size - 2)
            val frac = pos - idx

            val p1 = leftContourBasePoints[idx]
            val p2 = leftContourBasePoints[idx + 1]
            val x = p1.first + (p2.first - p1.first) * frac
            val y = p1.second + (p2.second - p1.second) * frac
            setPoint(i, Pair(x, y))
        }

        // M16-M32 (17点，M16已设置，所以从M17开始)
        for (i in 1..16) {
            val t = i.toFloat() / 16f
            val pos = t * (rightContourBasePoints.size - 1)
            val idx = pos.toInt().coerceIn(0, rightContourBasePoints.size - 2)
            val frac = pos - idx

            val p1 = rightContourBasePoints[idx]
            val p2 = rightContourBasePoints[idx + 1]
            val x = p1.first + (p2.first - p1.first) * frac
            val y = p1.second + (p2.second - p1.second) * frac
            setPoint(16 + i, Pair(x, y))
        }

        // [GC 优化] 调试日志已注释：每帧 66 次 String.format() + StringBuilder 高额分配
        // val sb = StringBuilder("Contour33: ")
        // for (i in 0 until 33) {
        //     sb.append("M$i=(${String.format("%.3f", result[i * 2])},${String.format("%.3f", result[i * 2 + 1])}) ")
        // }
        // Logger.d(TAG, sb.toString())

        // === 生成非轮廓区域点（33-105）===
        for (i in 0 until NON_CONTOUR_POINT_COUNT) {
            val mpIndex = NON_CONTOUR_MAPPING[i]
            if (mpIndex < landmarks.size) {
                val landmark = landmarks[mpIndex]
                var x = landmark.x()
                var y = landmark.y()

                // 先旋转坐标（与轮廓点 getMpPoint() 一致）
                val (rx, ry) = rotateNormalized(x, y, rotationDegrees)
                x = rx
                y = ry

                // 再前置摄像头镜像
                if (isFrontCamera) {
                    x = 1f - x
                }
                result[(33 + i) * 2] = x.coerceIn(0f, 1f)
                result[(33 + i) * 2 + 1] = y.coerceIn(0f, 1f)
            }
        }

        return Result.success(result)
    }
}
