package com.mamba.picme.beauty.api.facedetect

import android.graphics.Bitmap
import android.graphics.RectF

/**
 * 人脸检测器公开接口
 *
 * 由 beauty-engine 内部实现，app 模块通过 Factory 获取实例。
 * 所有检测输入均为 Bitmap，ImageProxy → Bitmap 转换由调用方负责。
 */
interface FaceDetector {
    /**
     * 实时预览帧检测（Bitmap 输入）
     *
     * @param bitmap RGBA Bitmap（已由调用方从 CameraX ImageProxy 转换）
     * @param rotationDegrees 图像旋转角度（0/90/180/270）
     * @param lensFacing 镜头方向（CameraSelector.LENS_FACING_FRONT / BACK）
     * @return 检测结果，无人脸返回 null
     */
    fun detect(bitmap: Bitmap, rotationDegrees: Int, lensFacing: Int): FaceDetectionResult?

    /**
     * 拍照后静态图检测
     *
     * @param bitmap 静态图片 Bitmap
     * @param lensFacing 镜头方向
     * @return 检测结果，无人脸返回 null
     */
    fun detectPhoto(bitmap: Bitmap, lensFacing: Int): FaceDetectionResult?

    /**
     * 轻量人脸检测（仅 ROI，无关键点）
     *
     * 专为 TAG 生成等不需要关键点对齐的场景设计。
     * 跳过关键点检测，直接返回人脸 ROI 矩形列表。
     *
     * @param bitmap 静态图片 Bitmap
     * @return 人脸 ROI 列表（像素坐标），无人脸返回空列表
     */
    fun detectFacesOnly(bitmap: Bitmap): List<RectF>

    /**
     * 多人脸检测（ROI + 5 点 landmarks）
     *
     * 为人脸识别/聚类任务提供对齐所需关键点。
     * MNN 路径复用 RetinaFace 已输出的 5 点 landmarks；
     * MediaPipe 路径当前仅返回 ROI，不返回 landmarks。
     *
     * @param bitmap 静态图片 Bitmap
     * @return 人脸检测结果列表（像素坐标 ROI + 5 点 landmarks），无人脸返回空列表
     */
    fun detectFacesWithLandmarks(bitmap: Bitmap): List<FaceDetection>

    /**
     * 对单个人脸 ROI 执行 2D106 关键点检测
     *
     * 供 Tag 生成等场景使用：先通过 [detectFacesWithLandmarks] 或 [detectFacesOnly]
     * 获取 ROI，再调用此方法获取 106 点关键点，用于更精确的人脸对齐。
     *
     * @param bitmap 静态图片 Bitmap
     * @param lensFacing 镜头方向（CameraSelector.LENS_FACING_FRONT / BACK）
     * @param roi 人脸 ROI 区域（Bitmap 像素坐标）
     * @return 包含 106 关键点的检测结果，失败返回 null
     */
    fun detectLandmarksForRoi(bitmap: Bitmap, lensFacing: Int, roi: RectF): FaceDetectionResult?

    /**
     * 切换检测引擎模式
     */
    fun setEngineMode(mode: EngineType)

    /**
     * 获取最近一次检测耗时（ms）
     */
    fun getLastProcessTimeMs(): Long

    /**
     * 获取最近一次检测来源
     */
    fun getLastDetectionSource(): FaceDetectionSource

    /**
     * 更新检测流水线配置（ROI + Landmark 检测器组合）
     */
    fun updatePipelineConfig(config: DetectionPipelineConfig)

    /**
     * 释放所有检测资源
     */
    fun release()
}