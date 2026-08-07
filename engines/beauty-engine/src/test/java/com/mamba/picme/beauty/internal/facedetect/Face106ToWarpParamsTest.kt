package com.mamba.picme.beauty.internal.facedetect

import com.mamba.picme.beauty.api.facedetect.FaceDetectionSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Face106ToWarpParams 核心转换逻辑测试
 *
 * 验证 106 点 FloatArray → FaceWarpParams 的数学计算正确性。
 * 这些测试使用 synthetic 数据（非真实人脸），确保索引映射和均值计算无误。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Face106ToWarpParamsTest {

    /**
     * 构造一个 perfectly centered 的 synthetic 106 点人脸。
     *
     * 轮廓 0-32：均匀分布在以 (0.5, 0.5) 为圆心、半径 0.2 的圆上
     * 其他点：也围绕中心分布，便于验证均值计算
     */
    private fun createCenteredLandmarks(): FloatArray {
        val landmarks = FloatArray(106 * 2)

        // 轮廓点 0-32：圆上均匀分布
        for (i in 0..32) {
            val angle = 2 * Math.PI * i / 33
            landmarks[i * 2] = 0.5f + 0.2f * kotlin.math.cos(angle).toFloat()
            landmarks[i * 2 + 1] = 0.5f + 0.2f * kotlin.math.sin(angle).toFloat()
        }

        // 其余点也放在中心附近，便于验证
        for (i in 33..105) {
            landmarks[i * 2] = 0.5f
            landmarks[i * 2 + 1] = 0.5f
        }

        return landmarks
    }

    /**
     * 构造一个已知具体值的 synthetic 人脸，用于精确验证计算。
     *
     * 左眼外轮廓 (52-57) 和左眼内角 (75-76) 的坐标被固定为可预测值
     */
    private fun createKnownLandmarks(): FloatArray {
        val landmarks = FloatArray(106 * 2)

        // 填充默认值避免未初始化
        for (i in 0..105) {
            landmarks[i * 2] = 0f
            landmarks[i * 2 + 1] = 0f
        }

        // 轮廓点 0-32（用于 faceCenter 和 faceRadius）
        // 点 0 在 (0.5, 0.3)，点 16 在 (0.5, 0.7)，其余在圆上
        for (i in 0..32) {
            val angle = 2 * Math.PI * i / 33
            landmarks[i * 2] = 0.5f + 0.2f * kotlin.math.cos(angle).toFloat()
            landmarks[i * 2 + 1] = 0.5f + 0.2f * kotlin.math.sin(angle).toFloat()
        }

        // 左眼外轮廓 52-57
        val leftEyeOuter = listOf(
            0.4f to 0.4f, 0.42f to 0.38f, 0.44f to 0.4f,
            0.44f to 0.42f, 0.42f to 0.44f, 0.4f to 0.42f
        )
        leftEyeOuter.forEachIndexed { idx, (x, y) ->
            landmarks[(52 + idx) * 2] = x
            landmarks[(52 + idx) * 2 + 1] = y
        }

        // 代码中 calculateEyeCenter(isLeft=true) 使用 innerStart=72（右眼内角 72-74 的前2个）
        // 所以左眼中心 = 左眼外轮廓 52-57 (6点) + 右眼内角 72-73 (2点)
        landmarks[72 * 2] = 0.55f
        landmarks[72 * 2 + 1] = 0.41f
        landmarks[73 * 2] = 0.55f
        landmarks[73 * 2 + 1] = 0.43f

        // 右眼外轮廓 58-63
        val rightEyeOuter = listOf(
            0.6f to 0.4f, 0.58f to 0.38f, 0.56f to 0.4f,
            0.56f to 0.42f, 0.58f to 0.44f, 0.6f to 0.42f
        )
        rightEyeOuter.forEachIndexed { idx, (x, y) ->
            landmarks[(58 + idx) * 2] = x
            landmarks[(58 + idx) * 2 + 1] = y
        }

        // 代码中 calculateEyeCenter(isLeft=false) 使用 innerStart=75（左眼内角 75-77 的前2个）
        // 所以右眼中心 = 右眼外轮廓 58-63 (6点) + 左眼内角 75-76 (2点)
        landmarks[75 * 2] = 0.45f
        landmarks[75 * 2 + 1] = 0.41f
        landmarks[76 * 2] = 0.45f
        landmarks[76 * 2 + 1] = 0.43f

        // 嘴巴外轮廓 84-95: 全部 (0.5, 0.6)
        for (i in 84..95) {
            landmarks[i * 2] = 0.5f
            landmarks[i * 2 + 1] = 0.6f
        }

        // 嘴巴内轮廓 96-103: 全部 (0.5, 0.62)
        for (i in 96..103) {
            landmarks[i * 2] = 0.5f
            landmarks[i * 2 + 1] = 0.62f
        }

        // upperLipCenter = 点 87
        landmarks[87 * 2] = 0.5f
        landmarks[87 * 2 + 1] = 0.58f

        // lowerLipCenter = 点 93
        landmarks[93 * 2] = 0.5f
        landmarks[93 * 2 + 1] = 0.64f

        return landmarks
    }

    @Test
    fun convert_perfectlyCenteredFace_producesExpectedParams() {
        val landmarks = createCenteredLandmarks()
        val result = Face106ToWarpParams.convert(landmarks, FaceDetectionSource.MEDIAPIPE)

        assertTrue("hasFace should be true", result.hasFace)
        assertEquals(FaceDetectionSource.MEDIAPIPE, result.detectionSource)

        // 轮廓点均匀分布在圆上，中心应在 (0.5, 0.5)
        assertEquals("faceCenterX", 0.5f, result.faceCenterX, 0.001f)
        assertEquals("faceCenterY", 0.5f, result.faceCenterY, 0.001f)
    }

    @Test
    fun convert_calculatesFaceCenterFromContourAverage() {
        val landmarks = createKnownLandmarks()
        val result = Face106ToWarpParams.convert(landmarks)

        // 轮廓点 0-32 均匀分布在圆上，中心为 (0.5, 0.5)
        assertEquals("faceCenterX should be average of contour points", 0.5f, result.faceCenterX, 0.001f)
        assertEquals("faceCenterY should be average of contour points", 0.5f, result.faceCenterY, 0.001f)
    }

    @Test
    fun convert_calculatesLeftEyeCenter_fromOuter6AndInner2() {
        val landmarks = createKnownLandmarks()
        val result = Face106ToWarpParams.convert(landmarks)

        // 左眼中心 = 左眼外轮廓 52-57 (6点) + 右眼内角 72-73 (2点)
        // 外轮廓 sumX = 0.4+0.42+0.44+0.44+0.42+0.4 = 2.52
        // 内角 sumX = 0.55+0.55 = 1.10
        // 8 点均值 x = (2.52 + 1.10)/8 = 3.62/8 = 0.4525
        // 外轮廓 sumY = 0.4+0.38+0.4+0.42+0.44+0.42 = 2.46
        // 内角 sumY = 0.41+0.43 = 0.84
        // 8 点均值 y = (2.46 + 0.84)/8 = 3.30/8 = 0.4125
        assertEquals("leftEyeX", 0.4525f, result.leftEyeX, 0.001f)
        assertEquals("leftEyeY", 0.4125f, result.leftEyeY, 0.001f)
    }

    @Test
    fun convert_calculatesRightEyeCenter_fromOuter6AndInner2() {
        val landmarks = createKnownLandmarks()
        val result = Face106ToWarpParams.convert(landmarks)

        // 右眼中心 = 右眼外轮廓 58-63 (6点) + 左眼内角 75-76 (2点)
        // 外轮廓 sumX = 0.6+0.58+0.56+0.56+0.58+0.6 = 3.48
        // 内角 sumX = 0.45+0.45 = 0.90
        // 8 点均值 x = (3.48 + 0.90)/8 = 4.38/8 = 0.5475
        // 外轮廓 sumY = 0.4+0.38+0.4+0.42+0.44+0.42 = 2.46
        // 内角 sumY = 0.41+0.43 = 0.84
        // 8 点均值 y = (2.46 + 0.84)/8 = 3.30/8 = 0.4125
        assertEquals("rightEyeX", 0.5475f, result.rightEyeX, 0.001f)
        assertEquals("rightEyeY", 0.4125f, result.rightEyeY, 0.001f)
    }

    @Test
    fun convert_calculatesMouthCenter_fromOuter12AndInner8() {
        val landmarks = createKnownLandmarks()
        val result = Face106ToWarpParams.convert(landmarks)

        // 嘴巴外轮廓 12 点全部 (0.5, 0.6)，内轮廓 8 点全部 (0.5, 0.62)
        // 20 点均值: x = 0.5, y = (0.6*12 + 0.62*8)/20 = (7.2 + 4.96)/20 = 12.16/20 = 0.608
        assertEquals("mouthCenterX", 0.5f, result.mouthCenterX, 0.001f)
        assertEquals("mouthCenterY", 0.608f, result.mouthCenterY, 0.001f)
    }

    @Test
    fun convert_calculatesFaceRadius_asMaxDistanceFromCenter() {
        val landmarks = createKnownLandmarks()
        val result = Face106ToWarpParams.convert(landmarks)

        // 轮廓点分布在半径 0.2 的圆上，所以 faceRadius 应为 0.2
        assertEquals("faceRadius should match contour circle radius", 0.2f, result.faceRadius, 0.001f)
    }

    @Test
    fun convert_faceRadiusIsClamped() {
        // 构造一个半径极小的人脸
        val landmarks = FloatArray(106 * 2) { 0.5f } // 所有点都在 (0.5, 0.5)
        val result = Face106ToWarpParams.convert(landmarks)

        // 所有点到中心的距离为 0，但会被 clamp 到最小值 0.12
        assertEquals("faceRadius should be clamped to minimum 0.12", 0.12f, result.faceRadius, 0.001f)
    }

    @Test
    fun convert_extracts33ContourPoints() {
        val landmarks = createKnownLandmarks()
        val result = Face106ToWarpParams.convert(landmarks)

        assertEquals("contourPoints should have 33 points", 33, result.contourPoints.size)
    }

    @Test
    fun convert_extracts9PointsPerEyeContour() {
        val landmarks = createKnownLandmarks()
        val result = Face106ToWarpParams.convert(landmarks)

        // 外轮廓 6 点 + 内角 3 点 = 9 点
        assertEquals("leftEyeContourPoints should have 9 points", 9, result.leftEyeContourPoints.size)
        assertEquals("rightEyeContourPoints should have 9 points", 9, result.rightEyeContourPoints.size)
    }

    @Test
    fun convert_extracts20MouthContourPoints() {
        val landmarks = createKnownLandmarks()
        val result = Face106ToWarpParams.convert(landmarks)

        // 外轮廓 12 点 + 内轮廓 8 点 = 20 点
        assertEquals("lipOuterContourPoints should have 20 points", 20, result.lipOuterContourPoints.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun convert_emptyLandmarks_throwsIllegalArgumentException() {
        Face106ToWarpParams.convert(FloatArray(0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun convert_tooSmallLandmarks_throwsIllegalArgumentException() {
        Face106ToWarpParams.convert(FloatArray(100)) // 远小于 212
    }

    @Test
    fun convert_preservesDetectionSource() {
        val landmarks = createKnownLandmarks()

        val mpResult = Face106ToWarpParams.convert(landmarks, FaceDetectionSource.MEDIAPIPE)
        assertEquals(FaceDetectionSource.MEDIAPIPE, mpResult.detectionSource)

        val mnnResult = Face106ToWarpParams.convert(landmarks, FaceDetectionSource.MNN)
        assertEquals(FaceDetectionSource.MNN, mnnResult.detectionSource)
    }

    @Test
    fun convert_populatesBigBeautyLandmarks() {
        val landmarks = createKnownLandmarks()
        val result = Face106ToWarpParams.convert(landmarks)

        assertTrue("bigBeautyLandmarks should have face", result.bigBeautyLandmarks.hasFace)
        assertEquals("bigBeautyLandmarks should have 106 points", 106, result.bigBeautyLandmarks.rawPoints.size / 2)
    }
}
