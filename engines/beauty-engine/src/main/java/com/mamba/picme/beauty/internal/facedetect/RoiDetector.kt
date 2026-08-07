package com.mamba.picme.beauty.internal.facedetect

import android.graphics.Bitmap
import android.graphics.RectF

/**
 * 人脸区域检测器（内部接口）
 * 负责快速定位人脸边界框，为关键点检测提供 ROI
 */
internal interface RoiDetector {
    /**
     * 从 Bitmap 检测人脸边界框
     * @param bitmap 输入图像
     * @return 人脸边界框（像素坐标），未检测到返回 null
     */
    fun detectRoi(bitmap: Bitmap): RectF?

    /**
     * 释放资源
     */
    fun release()
}
