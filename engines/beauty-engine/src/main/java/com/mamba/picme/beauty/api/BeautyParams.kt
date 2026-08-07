package com.mamba.picme.beauty.api

import com.mamba.picme.beauty.render.StyleEffect

/**
 * R Plan 美颜参数
 *
 * 新模块专用参数容器，解耦对 app 层 BeautySettings 的依赖。
 * app 层负责将 BeautySettings 转换为 BeautyParams 后传入。
 *
 * 所有强度值说明：
 * - smoothing   : 磨皮 0.0~1.0（已归一化）
 * - whitening   : 美白 0.0~1.0（已归一化）
 * - bigEyes     : 大眼 0.0~1.0（已归一化）
 * - slimFace    : 瘦脸 -1.0~1.0（已归一化，负值为丰满）
 * - lipColor    : 唇色强度 0.0~1.0
 * - lipColorIndex : 唇色色号 0~11
 * - blush       : 腮红强度 0.0~1.0
 * - blushColorFamily : 腮红色系 0=粉/1=橙/2=梅
 *
 * 专业调色参数（大美丽引擎路径专用，GPUPixel 路径忽略此字段）：
 * - exposure      : 曝光 -10.0~10.0，0 为原始
 * - contrast      : 对比度 0.0~4.0，1.0 为原始
 * - saturation    : 饱和度 0.0~2.0，1.0 为原始
 * - temperature   : 色温 -1.0~+1.0，0 为原始（蓝-黄偏移）
 * - tint          : 色调 -1.0~+1.0，0 为原始（绿-品红偏移）
 * - brightness    : 亮度 -1.0~+1.0，0 为原始
 * - redAdjustment : 红色通道 0.0~2.0，1.0 为原始
 * - greenAdjustment : 绿色通道 0.0~2.0，1.0 为原始
 * - blueAdjustment : 蓝色通道 0.0~2.0，1.0 为原始
 *
 * 专业调色参数（GPUPixel 路径专用，大美丽引擎忽略此字段）：
 * - gpuExposure      : 曝光 -10.0~10.0，0 为原始（对应 GPUPixel ExposureFilter exposure）
 * - gpuContrast      : 对比度 0.0~4.0，1.0 为原始（对应 GPUPixel ContrastFilter contrast）
 * - gpuSaturation    : 饱和度 0.0~2.0，1.0 为原始（对应 GPUPixel SaturationFilter saturation）
 * - gpuWhiteBalance  : 色温 2000~10000K，5000 为原始（对应 GPUPixel WhiteBalanceFilter temperature）
 *
 * 风格特效参数（大美丽引擎路径专用，GPUPixel 路径忽略此字段）：
 * - styleEffect : 风格特效类型，NONE 表示无特效
 * - styleIntensity : 风格特效强度 0.0~1.0
 * - toonThreshold : Toon 边缘阈值 0.0~1.0
 * - toonQuantizationLevels : Toon 颜色量化级数 1.0~256.0
 * - sketchEdgeStrength : Sketch 边缘强度 0.0~4.0
 * - posterizeColorLevels : Posterize 颜色级数 1.0~256.0
 * - embossIntensity : Emboss 强度 0.0~4.0
 * - crosshatchSpacing : Crosshatch 线条间距 0.001~0.5
 * - crosshatchLineWidth : Crosshatch 线条宽度 0.0001~0.1
 *
 * 风格特效参数（GPUPixel 路径专用，大美丽引擎忽略此字段）：
 * - styleFilterClassName : GPUPixel 风格滤镜类名，null 表示无特效（对应 StyleFilter 枚举）
 *                         合法值："ToonFilter" | "SketchFilter" |
 *                                "PosterizeFilter" | "EmbossFilter" | "CrosshatchFilter" | null
 *
 * 色调滤镜参数（大美丽引擎路径专用，GPUPixel 路径忽略此字段）：
 * - colorMatrix : 4x5 颜色矩阵（20个 float），用于预览时实时色调变换。
 *                 layout 与 Android ColorMatrix 一致（行主序，4行×5列，第5列为平移量，已归一化到 0~1）。
 *                 null 表示无色调滤镜（直通）。
 */
data class BeautyParams(
    val enabled: Boolean = false,
    val smoothing: Float = 0f,
    val whitening: Float = 0f,
    val bigEyes: Float = 0f,
    val slimFace: Float = 0f,
    val lipColor: Float = 0f,
    val lipColorIndex: Int = 0,
    val blush: Float = 0f,
    val blushColorFamily: Int = 0,
    // 专业调色参数（大美丽引擎路径专用）
    val exposure: Float = 0f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val brightness: Float = 0f,
    val redAdjustment: Float = 1f,
    val greenAdjustment: Float = 1f,
    val blueAdjustment: Float = 1f,
    // 风格特效参数
    val styleEffect: StyleEffect = StyleEffect.NONE,
    val styleIntensity: Float = 1f,
    val toonThreshold: Float = 0.2f,
    val toonQuantizationLevels: Float = 10f,
    val sketchEdgeStrength: Float = 1f,
    val posterizeColorLevels: Float = 10f,
    val embossIntensity: Float = 1f,
    val crosshatchSpacing: Float = 0.03f,
    val crosshatchLineWidth: Float = 0.003f,
    // 色调滤镜矩阵：4x5 ColorMatrix，null 表示无滤镜
    val colorMatrix: FloatArray? = null
) {
    companion object {
        val EMPTY = BeautyParams()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BeautyParams) return false
        return enabled == other.enabled &&
            smoothing == other.smoothing &&
            whitening == other.whitening &&
            bigEyes == other.bigEyes &&
            slimFace == other.slimFace &&
            lipColor == other.lipColor &&
            lipColorIndex == other.lipColorIndex &&
            blush == other.blush &&
            blushColorFamily == other.blushColorFamily &&
            exposure == other.exposure &&
            contrast == other.contrast &&
            saturation == other.saturation &&
            temperature == other.temperature &&
            tint == other.tint &&
            brightness == other.brightness &&
            redAdjustment == other.redAdjustment &&
            greenAdjustment == other.greenAdjustment &&
            blueAdjustment == other.blueAdjustment &&
            styleEffect == other.styleEffect &&
            styleIntensity == other.styleIntensity &&
            toonThreshold == other.toonThreshold &&
            toonQuantizationLevels == other.toonQuantizationLevels &&
            sketchEdgeStrength == other.sketchEdgeStrength &&
            posterizeColorLevels == other.posterizeColorLevels &&
            embossIntensity == other.embossIntensity &&
            crosshatchSpacing == other.crosshatchSpacing &&
            crosshatchLineWidth == other.crosshatchLineWidth &&
            colorMatrix.contentEquals(other.colorMatrix)
    }

    override fun hashCode(): Int {
        var result = enabled.hashCode()
        result = 31 * result + smoothing.hashCode()
        result = 31 * result + whitening.hashCode()
        result = 31 * result + bigEyes.hashCode()
        result = 31 * result + slimFace.hashCode()
        result = 31 * result + lipColor.hashCode()
        result = 31 * result + lipColorIndex
        result = 31 * result + blush.hashCode()
        result = 31 * result + blushColorFamily
        result = 31 * result + exposure.hashCode()
        result = 31 * result + contrast.hashCode()
        result = 31 * result + saturation.hashCode()
        result = 31 * result + temperature.hashCode()
        result = 31 * result + tint.hashCode()
        result = 31 * result + brightness.hashCode()
        result = 31 * result + redAdjustment.hashCode()
        result = 31 * result + greenAdjustment.hashCode()
        result = 31 * result + blueAdjustment.hashCode()
        result = 31 * result + styleEffect.hashCode()
        result = 31 * result + styleIntensity.hashCode()
        result = 31 * result + toonThreshold.hashCode()
        result = 31 * result + toonQuantizationLevels.hashCode()
        result = 31 * result + sketchEdgeStrength.hashCode()
        result = 31 * result + posterizeColorLevels.hashCode()
        result = 31 * result + embossIntensity.hashCode()
        result = 31 * result + crosshatchSpacing.hashCode()
        result = 31 * result + crosshatchLineWidth.hashCode()
        result = 31 * result + colorMatrix.contentHashCode()
        return result
    }
}
