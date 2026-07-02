package com.mamba.picme.features.editor

import com.mamba.picme.beauty.api.FaceData
import com.mamba.picme.beauty.api.facedetect.FaceDetectionSource
import com.mamba.picme.beauty.internal.facedetect.Face106ToWarpParams

object FaceDataConverter {

    fun fromLandmarks106(landmarks: FloatArray, imageWidth: Int, imageHeight: Int): FaceData? {
        if (landmarks.size < 212) return null
        val warpParams = Face106ToWarpParams.convert(landmarks, FaceDetectionSource.MEDIAPIPE)
        return FaceData(
            faceCenterX = warpParams.faceCenterX,
            faceCenterY = warpParams.faceCenterY,
            leftEyeX = warpParams.leftEyeX,
            leftEyeY = warpParams.leftEyeY,
            rightEyeX = warpParams.rightEyeX,
            rightEyeY = warpParams.rightEyeY,
            mouthCenterX = warpParams.mouthCenterX,
            mouthCenterY = warpParams.mouthCenterY,
            mouthLeftX = warpParams.mouthLeftX,
            mouthLeftY = warpParams.mouthLeftY,
            mouthRightX = warpParams.mouthRightX,
            mouthRightY = warpParams.mouthRightY,
            upperLipCenterX = warpParams.upperLipCenterX,
            upperLipCenterY = warpParams.upperLipCenterY,
            lowerLipCenterX = warpParams.lowerLipCenterX,
            lowerLipCenterY = warpParams.lowerLipCenterY,
            faceRadius = warpParams.faceRadius,
            hasFace = true,
            landmarks106 = landmarks
        )
    }
}
