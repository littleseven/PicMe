package com.mamba.picme.beauty.api

import android.graphics.Bitmap

/**
 * 拍照后处理器接口
 *
 * 核心职责：将拍照后的 Bitmap 通过 GPU 离屏渲染处理，复用预览同一套 Shader 管线，
 * 确保预览与拍照效果 100% 一致。
 *
 * 使用场景：
 * 1. 大美丽模式下拍照后处理（替代 CPU Canvas 路径）
 * 2. 批量图片离线处理
 *
 * 实现要求：
 * - 创建独立 EGL 上下文，不与预览上下文冲突
 * - 支持 2D 纹理输入（Bitmap → GL_TEXTURE_2D）
 * - 复用 BeautyRenderer 多 Pass 管线
 * - GPU 路径失败时抛出异常，由调用方回退 CPU 路径
 *
 * @see BeautyParams 美颜参数
 * @see FaceData 人脸数据（用于瘦脸/大眼/妆容）
 */
interface PhotoProcessor {

    /**
     * 处理拍照后的 Bitmap
     *
     * 完整流程：
     * 1. 创建独立 EGL 上下文
     * 2. Bitmap → OpenGL 2D Texture
     * 3. 执行多 Pass 美颜渲染（复用预览 Shader）
     * 4. FBO → Bitmap（glReadPixels / PBO）
     * 5. 释放临时资源
     *
     * @param bitmap 原始照片 Bitmap（已旋转/裁剪/镜像）
     * @param params 美颜参数
     * @param faceData 人脸数据（可选，用于美型和妆容）
     * @return 处理后的 Bitmap
     * @throws PhotoProcessException GPU 处理失败，调用方应回退到 CPU 路径
     */
    fun process(bitmap: Bitmap, params: BeautyParams, faceData: FaceData?): Bitmap

    /**
     * 释放所有资源
     *
     * 必须释放：
     * - EGL 上下文和 Surface
     * - FBO 和纹理
     * - PBO 缓冲
     * - Shader Program
     */
    fun release()
}

/**
 * 拍照处理异常
 *
 * GPU 离屏渲染失败时抛出，包含具体失败原因。
 * 调用方应捕获此异常并回退到 CPU Canvas 处理路径。
 *
 * @param reason 失败原因描述
 * @param cause 原始异常（如果有）
 */
class PhotoProcessException(
    val reason: String,
    cause: Throwable? = null
) : Exception("Photo GPU processing failed: $reason", cause)

/**
 * 人脸数据（用于拍照后处理）
 *
 * 包含人脸关键点、轮廓等信息，用于驱动美型和妆容效果。
 * 数据格式与预览时使用的 FaceWarpParams 保持一致。
 *
 * @param faceCenterX 人脸中心 X（0.0~1.0，标准化坐标）
 * @param faceCenterY 人脸中心 Y（0.0~1.0）
 * @param leftEyeX 左眼中心 X
 * @param leftEyeY 左眼中心 Y
 * @param rightEyeX 右眼中心 X
 * @param rightEyeY 右眼中心 Y
 * @param mouthCenterX 嘴部中心 X
 * @param mouthCenterY 嘴部中心 Y
 * @param mouthLeftX 嘴部左侧 X
 * @param mouthLeftY 嘴部左侧 Y
 * @param mouthRightX 嘴部右侧 X
 * @param mouthRightY 嘴部右侧 Y
 * @param upperLipCenterX 上唇中心 X
 * @param upperLipCenterY 上唇中心 Y
 * @param lowerLipCenterX 下唇中心 X
 * @param lowerLipCenterY 下唇中心 Y
 * @param faceRadius 人脸半径（0.08~0.45）
 * @param hasFace 是否检测到人脸
 * @param lipOuterPoints 嘴唇外轮廓点列表（标准化坐标）
 * @param lipInnerPoints 嘴唇内轮廓点列表（标准化坐标）
 * @param leftCheekPoints 左脸颊轮廓点列表（标准化坐标）
 * @param rightCheekPoints 右脸颊轮廓点列表（标准化坐标）
 * @param landmarks106 106 点人脸关键点（FloatArray，212 个元素）
 */
data class FaceData(
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
    val lipOuterPoints: List<Pair<Float, Float>> = emptyList(),
    val lipInnerPoints: List<Pair<Float, Float>> = emptyList(),
    val leftCheekPoints: List<Pair<Float, Float>> = emptyList(),
    val rightCheekPoints: List<Pair<Float, Float>> = emptyList(),
    val landmarks106: FloatArray? = null
)
