package com.mamba.picme.agent.core.model.context

/**
 * 时间段枚举
 */
enum class TimeOfDay {
    MORNING,
    AFTERNOON,
    EVENING,
    NIGHT
}

/**
 * 光照等级枚举
 */
enum class LightingLevel {
    DARK,
    DIM,
    NORMAL,
    BRIGHT
}

/**
 * 摄像头朝向枚举
 */
enum class CameraFacing {
    FRONT,
    BACK
}

/**
 * 场景上下文
 *
 * 描述当前拍摄场景的物理环境信息，用于场景推荐和计划生成。
 *
 * @property timeOfDay 当前时间段
 * @property lightingLevel 光照等级
 * @property cameraFacing 摄像头朝向
 * @property locationHint 位置提示（可选，如 "室内", "户外", "海边" 等）
 */
data class SceneContext(
    val timeOfDay: TimeOfDay = TimeOfDay.AFTERNOON,
    val lightingLevel: LightingLevel = LightingLevel.NORMAL,
    val cameraFacing: CameraFacing = CameraFacing.BACK,
    val locationHint: String? = null
)

/**
 * 用户偏好画像
 *
 * 描述用户的个性化偏好，用于推荐和计划生成。
 *
 * @property preferredFilters 偏好的滤镜列表
 * @property preferredStyles 偏好的风格列表
 * @property autoApplyBeauty 是否自动应用美颜
 * @property preferredRatio 偏好的画幅比例
 */
data class PreferenceProfile(
    val preferredFilters: List<String> = emptyList(),
    val preferredStyles: List<String> = emptyList(),
    val autoApplyBeauty: Boolean = true,
    val preferredRatio: String = "4:3"
)
