# PicMe Agent Capability 注册表

> **边界声明（Boundary Statement）**
> - 本文档定义所有 Agent Capability 的注册表、命令映射与执行逻辑。
> - 架构设计以 [`../02-ARCHITECTURE/AGENT_ARCHITECTURE.md`](../02-ARCHITECTURE/AGENT_ARCHITECTURE.md) 为准。
> - 交互规范以 [`../01-PRODUCT/FEATURES.md`](../01-PRODUCT/FEATURES.md) 为准。

**模块定位**: Agent 能力注册表与命令映射  
**主要维护者**: [RD] 全栈工程师  
**阅读对象**: RD、AI Agent  
**版本**: 1.0  
**最后更新**: 2026-07-01  

---

## 📋 目录

1. [Capability 概览](#capability-概览)
2. [CameraCapability](#2-cameraCapability)
3. [GalleryCapability](#3-gallerycapability)
4. [SettingsCapability](#4-settingscapability)
5. [NavigationCapability](#5-navigationcapability)
6. [EditCapability](#6-editcapability)

---

## 1. Capability 概览

| Capability | 活跃场景 | 命令数 | 状态 |
|------------|----------|--------|------|
| **CameraCapability** | CAMERA | 11 | ✅ 已落地 |
| **GalleryCapability** | GALLERY | 7 | ✅ 已落地 |
| **SettingsCapability** | SETTINGS | 5 | ✅ 已落地 |
| **NavigationCapability** | ALL | 2 | ✅ 已落地 |
| **SystemCapability** | ALL | 2 | ✅ 已落地 |
| **AccessibilityCapability** | ALL | 1 | ✅ 已落地（需用户手动开启无障碍服务） |
| **EditCapability** | EDITOR | 0 | 🔄 待实现（`AgentCommands.kt` 中无对应命令类型） |

### 1.1 场景 - 能力映射

| 场景 | 可用 Capability |
|------|-----------------|
| `CAMERA` | CameraCapability, NavigationCapability, SystemCapability |
| `GALLERY` | GalleryCapability, NavigationCapability, SystemCapability |
| `SETTINGS` | SettingsCapability, NavigationCapability, SystemCapability |
| `CHAT` | NavigationCapability, SystemCapability |
| `EDITOR` | NavigationCapability, SystemCapability（编辑独立路由预留） |
| `DEBUG` | NavigationCapability, SystemCapability |

---

## 2. CameraCapability

**职责**: 相机控制、美颜调节、滤镜切换、拍摄模式管理  
**活跃场景**: `CAMERA`  
**状态**: ✅ 已落地

### 2.1 支持命令

| 命令 | 参数 | 描述 | 示例 |
|------|------|------|------|
| `capture` | - | 拍照 | "拍照" |
| `toggle_recording` | - | 开始/停止录像 | "开始录像"/"停止录像" |
| `flip_camera` | - | 翻转摄像头 | "翻转镜头" |
| `adjust_zoom` | `factor: Float` | 变焦调节 | "放大两倍" → 2.0x |
| `adjust_exposure` | `offset: Int` | 曝光调节 (+/-) | "调亮一点" → +2 |
| `switch_mode` | `mode: String` | 切换拍摄模式 | "夜景模式" |
| `adjust_beauty` | `type: String, value: Int` | 调节美颜参数 | "磨皮 50" |
| `switch_filter` | `filterType: String` | 切换滤镜 | "冷调滤镜" |
| `switch_style` | `styleType: String` | 切换风格特效 | "卡通风格" |
| `switch_scene` | `scene: String` | 切换场景模式 | "人像场景" |
| `switch_ratio` | `ratio: String` | 切换画幅比例 | "16:9" |
| `text_reply` | `message: String` | 文本回复 | "你会什么" |

### 2.2 美颜参数范围

| 参数 | 范围 | 默认值 |
|------|------|--------|
| 磨皮 | 0-100 | 35 |
| 美白 | 0-100 | 25 |
| 瘦脸 | -50~+50 | 0 |
| 大眼 | 0-100 | 20 |
| 唇色 | 0-100 | 40 |
| 腮红 | 0-100 | 20 |
| 眉毛 | 0-100 | 15 |

### 2.3 实现要点

```kotlin
class CameraCapability(
    private val onCapturePhoto: () -> Unit,
    private val onToggleRecording: () -> Unit,
    private val onFlipCamera: () -> Unit,
    private val onAdjustZoom: (Float) -> Unit,
    private val onAdjustExposure: (Int) -> Unit,
    private val onSwitchMode: (String) -> Unit,
    private val onAdjustBeauty: (BeautyType, Int) -> Unit,
    private val onSwitchFilter: (FilterType) -> Unit,
    private val onSwitchStyle: (StyleType) -> Unit,
    private val onSwitchScene: (SceneMode) -> Unit,
    private val onSwitchRatio: (AspectRatio) -> Unit
) : Capability {
    override val name = "camera"
    override val description = "相机控制：拍照、录像、美颜、滤镜"
    
    override fun activeScenes() = listOf(SceneManager.Scene.CAMERA)
    
    override fun supportedCommands() = listOf(
        "capture", "toggle_recording", "flip_camera",
        "adjust_zoom", "adjust_exposure", "switch_mode",
        "adjust_beauty", "switch_filter", "switch_style",
        "switch_scene", "switch_ratio"
    )
}
```

---

## 3. GalleryCapability

**职责**: 相册查看、删除、分享、搜索、批量选择  
**活跃场景**: `GALLERY`  
**状态**: ✅ 已落地（2026-06 更新：此前标注"部分实现"，现已完整实现）

### 3.1 支持命令

| 命令 | 参数 | 描述 | 示例 |
|------|------|------|------|
| `view_media` | `mediaId: String?` | 查看照片/视频 | "看这张照片" |
| `delete_media` | `mediaIds: List<String>` | 删除照片/视频 | "删除这张" |
| `share_media` | `mediaIds: List<String>` | 分享照片/视频 | "分享这张" |
| `favorite_media` | `mediaId: String` | 收藏照片 | "收藏这张" |
| `search_media` | `query: String` | 搜索照片 | "找昨天的照片" |
| `select_media` | `mediaId: String, selected: Boolean` | 批量选择 | "多选这张" |
| `switch_view_mode` | `mode: ViewMode` | 切换视图模式 | "网格视图" |
| `text_reply` | `message: String` | 文本回复 | "有哪些照片" |

### 3.2 页面上下文

```kotlin
data class GalleryContext(
    val currentMedia: MediaAsset?,
    val selectedItems: List<MediaAsset>,
    val isSelectionMode: Boolean
) : PageContext()
```

### 3.3 实现要点

```kotlin
class GalleryCapability(
    private val onViewMedia: ((MediaAsset) -> Unit)? = null,
    private val onDeleteMedia: ((List<MediaAsset>) -> Unit)? = null,
    private val onShareMedia: ((List<MediaAsset>) -> Unit)? = null,
    private val onSelectMedia: ((MediaAsset, Boolean) -> Unit)? = null,
    private val onSearch: ((String) -> Unit)? = null,
    private val onSwitchViewMode: ((ViewMode) -> Unit)? = null
) : Capability {
    
    override val name = "gallery"
    override val description = "查看、删除、分享、搜索照片和视频"
    
    override fun activeScenes() = listOf(SceneManager.Scene.GALLERY)
    
    override suspend fun execute(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext?
    ): Result<AgentAction> {
        val galleryContext = pageContext as? PageContext.GalleryContext
        // ... 命令处理逻辑
    }
}
```

---

## 4. SettingsCapability

**职责**: 主题切换、语言设置、模型管理、人脸引擎切换  
**活跃场景**: `SETTINGS`  
**状态**: ✅ 已落地（2026-06 更新：此前标注"规划中"，现 SettingsViewModel 中已实现对应的状态管理）

### 4.1 支持命令

| 命令 | 参数 | 描述 | 示例 |
|------|------|------|------|
| `change_theme` | `theme: ThemeMode` | 切换主题 | "深色模式" |
| `change_language` | `language: AppLanguage` | 切换语言 | "英文界面" |
| `download_model` | `modelId: String` | 下载 AI 模型 | "下载美颜模型" |
| `switch_face_engine` | `engine: FaceDetectionEngineMode` | 切换人脸引擎 | "用 MediaPipe" |
| `toggle_setting` | `key: String, enabled: Boolean` | 开关设置项 | "开启调试模式" |
| `text_reply` | `message: String` | 文本回复 | "有什么设置" |

### 4.2 页面上下文

```kotlin
data class SettingsContext(
    val currentCategory: String?
) : PageContext()
```

---

## 5. NavigationCapability

**职责**: 页面切换、返回上一页  
**活跃场景**: `ALL` (所有场景)  
**状态**: ✅ 已落地

### 5.1 支持命令

| 命令 | 参数 | 描述 | 示例 |
|------|------|------|------|
| `navigate_to` | `destination: String` | 切换到指定页面 | "去相册"/"打开设置" |
| `go_back` | - | 返回上一页 | "返回"/"回去" |
| `text_reply` | `message: String` | 文本回复 | "能去哪里" |

### 5.2 页面映射

| 意图关键词 | 目标页面 |
|-----------|---------|
| "相机", "拍照" | `Screen.Camera` |
| "相册", "照片", " gallery" | `Screen.Gallery` |
| "设置", "设定" | `Screen.Settings` |
| "编辑", "修图" | `Screen.Editor` |

### 5.3 实现要点

```kotlin
class NavigationCapability(
    private val onNavigate: (Screen) -> Unit,
    private val onBack: () -> Unit
) : Capability {
    
    override val name = "navigation"
    override val description = "页面导航：切换页面、返回上一页"
    
    override fun activeScenes() = SceneManager.Scene.entries.toList()
    
    override fun supportedCommands() = listOf(
        "navigate_to", "go_back", "text_reply"
    )
}
```

---

## 6. SystemCapability

**职责**: 启动其他应用、打开系统设置
**活跃场景**: `ALL` (所有场景)
**状态**: ✅ 已落地

### 6.1 支持命令

| 命令 | 参数 | 描述 | 示例 |
|------|------|------|------|
| `launch_app` | `packageName: String?`, `appName: String?` | 启动应用 | "打开微信" |
| `open_system_settings` | `setting: String` | 打开系统设置 | "打开WiFi设置" |
| `text_reply` | `message: String` | 文本回复 | "能打开哪些应用" |

### 6.2 应用名解析

- 优先使用 `packageName`。
- 若只提供 `appName`，则在已安装应用列表中按应用标签模糊匹配。
- 匹配失败时返回错误，不会随意启动未知应用。

### 6.3 实现要点

```kotlin
class SystemCapability(
    private val context: Context
) : BaseCapability() {
    override val name = "system"
    override val description = "系统控制：打开其他应用、系统设置"

    override fun supportedCommands() = listOf(
        "launch_app",
        "open_system_settings"
    )
}
```

---

## 7. AccessibilityCapability

**职责**: 在其他应用中执行无障碍自动操作（点击、输入、滚动、返回、主页、最近任务）
**活跃场景**: `ALL` (所有场景)
**状态**: ✅ 已落地（需用户在系统设置中手动开启 PicMe 无障碍服务）

### 7.1 支持命令

| 命令 | 参数 | 描述 | 示例 |
|------|------|------|------|
| `perform_accessibility_action` | `action: String`, `target: AccessibilityTarget?`, `params: Map<String,String>` | 执行无障碍动作 | "点击通讯录"、"输入 1234" |

### 7.2 可用性

- `isAvailable()` 仅在 `PicMeAccessibilityService` 已连接时返回 `true`。
- 服务未开启时执行命令会返回 `CAPABILITY_UNAVAILABLE` 错误，并提示用户前往设置开启。

### 7.3 实现要点

```kotlin
class AccessibilityCapability : BaseCapability() {
    override val name = "accessibility"
    override val description = "无障碍自动化：在其他应用中执行点击、输入、滚动、返回等操作"

    override fun supportedCommands() = listOf("perform_accessibility_action")

    override fun isAvailable() = AccessibilityController.isServiceConnected()
}
```

---

## 8. EditCapability（预留）

**职责**: 图片编辑、保存、撤销/重做  
**活跃场景**: `EDITOR`  
**状态**: ⏳ 规划中

### 8.1 支持命令

| 命令 | 参数 | 描述 | 示例 |
|------|------|------|------|
| `apply_edit` | `editType: String, params: Map` | 应用编辑操作 | "磨皮 30" |
| `save_edit` | - | 保存编辑结果 | "保存" |
| `undo_edit` | - | 撤销上一步 | "撤销" |
| `redo_edit` | - | 重做上一步 | "重做" |
| `text_reply` | `message: String` | 文本回复 | "能怎么编辑" |

### 8.2 页面上下文

```kotlin
data class EditorContext(
    val editingMedia: MediaAsset,
    val hasUnsavedChanges: Boolean
) : PageContext()
```

---

## 附录：新增 Capability 指南

### 步骤 1: 定义 Capability 接口实现

```kotlin
class NewCapability : Capability {
    override val name = "new_feature"
    override val description = "新功能描述"
    
    override fun activeScenes() = listOf(SceneManager.Scene.YOUR_SCENE)
    
    override fun supportedCommands() = listOf("command_1", "command_2")
    
    override suspend fun execute(command: AgentCommand, context: AgentContext, pageContext: PageContext?): Result<AgentAction> {
        // 实现命令处理逻辑
    }
}
```

### 步骤 2: 注册到 CapabilityRegistry

```kotlin
val registry = CapabilityRegistry.getInstance().apply {
    register(NewCapability())
}
```

### 步骤 3: 扩展 AgentCommand

```kotlin
sealed class AgentCommand {
    data class NewCommand(val param: String) : AgentCommand()
    // ... 其他命令
}
```

### 步骤 4: 更新 PromptBuilder

在 `PromptBuilder.buildSystemPrompt()` 中添加新 Capability 的自描述。

---

> **参考文档**:
> - [AGENT_ARCHITECTURE.md](../02-ARCHITECTURE/AGENT_ARCHITECTURE.md) — Agent 架构设计
> - [COMMAND_REFERENCE.md](./COMMAND_REFERENCE.md) — 命令语法参考
> - [CAPABILITY_IMPLEMENTATION_GUIDE.md](./CAPABILITY_IMPLEMENTATION_GUIDE.md) — 实现指南
