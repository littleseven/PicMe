package com.mamba.picme.beauty.internal.facedetect

import android.graphics.Bitmap
import android.graphics.RectF

/**
 * 人脸关键点检测器（内部接口）
 * 负责在给定 ROI 内精确定位人脸关键点
 */
internal interface LandmarkDetector {
    /**
     * 检测人脸关键点
     * @param bitmap 输入图像
     * @param lensFacing 镜头方向（用于坐标镜像）
     * @param roi 可选的人脸区域，如果为 null 则自动检测
     * @return 归一化关键点数组（FloatArray），未检测到返回 null
     */
    fun detectLandmarks(bitmap: Bitmap, lensFacing: Int, roi: RectF? = null): FloatArray?

    /**
     * 释放资源
     */
    fun release()
}
