package com.mamba.picme.beauty.internal

/**
 * 美颜 Shader 链接口
 *
 * 定义统一的渲染接口，供 OffscreenRenderer 调用。
 * 实现类需要封装完整的预览渲染管线（磨皮、美白、瘦脸、大眼、妆容等）。
 *
 * 设计目标：
 * 1. 预览和拍照使用同一套 Shader 链，确保效果一致
 * 2. 支持多 Pass 渲染（美颜 → 风格特效）
 * 3. 抽象底层 OpenGL 细节，便于测试和维护
 *
 * 使用示例：
 * ```kotlin
 * val shaderChain = BeautyRendererAsShaderChain(beautyRenderer)
 * val outputBitmap = offscreenRenderer.processBitmap(inputBitmap, shaderChain)
 * ```
 */
interface BeautyShaderChain {

    /**
     * 执行美颜渲染链
     *
     * @param inputTextureId 输入纹理 ID（相机帧或 Bitmap 转换的纹理）
     * @param outputTextureId 输出纹理 ID（FBO 绑定的纹理）
     * @param width 渲染宽度
     * @param height 渲染高度
     *
     * @return 是否渲染成功
     */
    fun render(
        inputTextureId: Int,
        outputTextureId: Int,
        width: Int,
        height: Int
    ): Boolean

    /**
     * 设置美颜参数
     *
     * @param smoothingStrength 磨皮强度 (0.0 - 1.0)
     * @param whiteningStrength 美白强度 (0.0 - 1.0)
     * @param slimFaceStrength 瘦脸强度 (0.0 - 1.0)
     * @param bigEyeStrength 大眼强度 (0.0 - 1.0)
     * @param lipColorStrength 唇色强度 (0.0 - 1.0)
     * @param blushStrength 腮红强度 (0.0 - 1.0)
     * @param eyebrowStrength 眉毛强度 (0.0 - 1.0)
     */
    fun setBeautyParams(
        smoothingStrength: Float,
        whiteningStrength: Float,
        slimFaceStrength: Float,
        bigEyeStrength: Float,
        lipColorStrength: Float,
        blushStrength: Float,
        eyebrowStrength: Float
    )

    /**
     * 设置人脸关键点（用于瘦脸、大眼、妆容等）
     *
     * @param landmarks106 106 点人脸关键点（归一化坐标 0.0-1.0）
     * @param hasFace 是否检测到人脸
     */
    fun setFaceLandmarks(landmarks106: FloatArray?, hasFace: Boolean)

    /**
     * 设置滤镜类型
     *
     * @param filterType 滤镜类型枚举
     */
    fun setFilterType(filterType: String)

    /**
     * 释放资源
     */
    fun release()
}
