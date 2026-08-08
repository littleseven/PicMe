package com.mamba.picme.agent.core.model.context

/**
 * 页面上下文（页面特定状态）
 *
 * 每个页面可以向 Agent 提供自己的特定状态，
 * 让 Agent 能够基于当前页面状态执行更精准的命令。
 */
sealed class PageContext {

    /**
     * 无特定上下文
     */
    object None : PageContext()

    /**
     * 相册页面上下文
     */
    data class GalleryContext(
        /** 当前正在查看的媒体 */
        val currentMedia: MediaAsset? = null,
        /** 已选中的项目列表 */
        val selectedItems: List<MediaAsset> = emptyList(),
        /** 是否处于多选模式 */
        val isSelectionMode: Boolean = false,
        /** 当前视图模式 */
        val viewMode: GalleryViewMode = GalleryViewMode.GRID,
        /** 搜索结果 */
        val searchQuery: String? = null
    ) : PageContext()

    /**
     * 设置页面上下文
     */
    data class SettingsContext(
        /** 当前选中的设置分类 */
        val currentCategory: String? = null,
        /** 当前主题模式 */
        val currentTheme: String = "system",
        /** 当前语言 */
        val currentLanguage: String = "zh"
    ) : PageContext()

    /**
     * 照片编辑页面上下文
     */
    data class EditorContext(
        /** 正在编辑的媒体 */
        val editingMedia: MediaAsset,
        /** 是否有未保存的更改 */
        val hasUnsavedChanges: Boolean = false,
        /** 编辑历史栈深度 */
        val undoStackDepth: Int = 0,
        /** 重做栈深度 */
        val redoStackDepth: Int = 0
    ) : PageContext()

    /**
     * 相机页面上下文
     */
    data class CameraContext(
        /** 是否正在录制 */
        val isRecording: Boolean = false,
        /** 当前媒体类型 */
        val captureMode: String = "photo",
        /** 已拍摄的照片数量（本次会话） */
        val photosTaken: Int = 0
    ) : PageContext()

    enum class GalleryViewMode {
        GRID,       // 网格视图
        LIST,       // 列表视图
        TIMELINE    // 时间线视图
    }
}
