package com.mamba.picme.beauty.api.facedetect

import android.graphics.PointF
import android.graphics.RectF

/**
 * 美颜 Shader 人脸变形参数
 *
 * 所有坐标均为归一化坐标（0.0 ~ 1.0）
 */
data class FaceWarpParams(
    val faceCenterX: Float = 0.5f,
    val faceCenterY: Float = 0.5f,
    val leftEyeX: Float = 0.4f,
    val leftEyeY: Float = 0.45f,
    val rightEyeX: Float = 0.6f,
    val rightEyeY: Float = 0.45f,
    val mouthCenterX: Float = 0.5f,
    val mouthCenterY: Float = 0.62f,
    val mouthLeftX: Float = 0.42f,
    val mouthLeftY: Float = 0.62f,
    val mouthRightX: Float = 0.58f,
    val mouthRightY: Float = 0.62f,
    val upperLipCenterX: Float = 0.5f,
    val upperLipCenterY: Float = 0.60f,
    val lowerLipCenterX: Float = 0.5f,
    val lowerLipCenterY: Float = 0.66f,
    val faceRadius: Float = 0.18f,
    val hasFace: Boolean = false,
    val contourPoints: List<PointF> = emptyList(),
    val leftEyeContourPoints: List<PointF> = emptyList(),
    val rightEyeContourPoints: List<PointF> = emptyList(),
    val lipOuterContourPoints: List<PointF> = emptyList(),
    val lipInnerContourPoints: List<PointF> = emptyList(),
    val leftCheekContourPoints: List<PointF> = emptyList(),
    val rightCheekContourPoints: List<PointF> = emptyList(),
    val allContours: FaceContourData = FaceContourData(),
    val bigBeautyLandmarks: GpuPixelLandmarks = GpuPixelLandmarks(),
    val detectionSource: FaceDetectionSource = FaceDetectionSource.NONE,
    val requestedDetectionEngineMode: EngineType = EngineType.MEDIAPIPE,
    val roiRect: RectF? = null,
    val roiDetectorName: String = "Unknown",
    val useGpuForRoi: Boolean = false,
    val landmarkDetectorName: String = "Unknown",
    val useGpuForLandmark: Boolean = false
)
