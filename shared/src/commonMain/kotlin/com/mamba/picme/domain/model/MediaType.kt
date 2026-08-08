package com.mamba.picme.domain.model

/**
 * 相机镜头方向（纯 Kotlin 领域模型，与 CameraX 无关）
 * Features 层在使用时自行映射到 CameraSelector.LENS_FACING_* 常量。
 */
enum class LensFacing {
    FRONT,
    BACK
}

/**
 * 画面比例
 */
enum class AspectRatio(val value: Int) {
    RATIO_4_3(0),
    RATIO_16_9(1),
    RATIO_1_1(2),
    RATIO_FULL(3)
}

/**
 * 场景模式
 */
enum class SceneMode {
    NONE,
    NIGHT,
    MOON
}

/**
 * 网格类型
 */
enum class GridType {
    NONE,
    THIRDS,
    GOLDEN
}
