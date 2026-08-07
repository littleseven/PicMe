package com.mamba.picme.beauty.internal.facedetect.adapter

import com.mamba.picme.beauty.api.facedetect.FaceDetectionSource

/**
 * 人脸关键点适配器接口
 *
 * 将不同检测器（MediaPipe 468点、InsightFace 106点等）的原生输出
 * 转换为统一的 106 点标准格式，供美颜引擎消费。
 *
 * 设计原则：
 * 1. 检测器输出原生格式，不做隐式转换
 * 2. 适配层显式声明映射关系，可配置可扩展
 * 3. 美颜资源只依赖标准索引，不感知检测器来源
 */
interface FaceLandmarkAdapter {

    /**
     * 将检测器原生输出转换为统一 106 点标准格式
     *
     * @param nativeLandmarks 检测器原生输出的关键点数组
     * @param lensFacing 镜头方向（用于前置摄像头镜像处理）
     * @return 转换结果，成功时包含统一 106 点 FloatArray(212)，失败时包含异常信息
     */
    fun adapt(nativeLandmarks: FloatArray, lensFacing: Int): Result<FloatArray>

    /**
     * 适配器支持的检测源标识
     */
    val detectionSource: FaceDetectionSource
}
