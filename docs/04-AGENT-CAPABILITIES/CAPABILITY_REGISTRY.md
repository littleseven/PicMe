# PicMe Agent Capability 注册表

> **边界声明（Boundary Statement）**
> - 本文档定义所有 Agent Capability 的注册表、命令映射与执行逻辑。
> - 架构设计以 [`../02-ARCHITECTURE/AGENT_ARCHITECTURE.md`](../02-ARCHITECTURE/AGENT_ARCHITECTURE.md) 为准。
> - 交互规范以 [`../01-PRODUCT/FEATURES.md`](../01-PRODUCT/FEATURES.md) 为准。

**模块定位**: Agent 能力注册表与命令映射  
**主要维护者**: [RD] 全栈工程师  
**阅读对象**: RD、AI Agent  
**版本**: 1.1  
**最后更新**: 2026-07-06  

---

## 📋 目录

1. [Capability 概览](#capability-概览)
2. [CameraCapability](#2-cameracapability)
3. [GalleryCapability](#3-gallerycapability)
4. [SettingsCapability](#4-settingscapability)
5. [NavigationCapability](#5-navigationcapability)
6. [SystemCapability](#6-systemcapability)
7. [AutoTagCapability](#7-autotagcapability)
8. [AiOptimizeCapability](#8-aioptimizecapability)
9. [RemoteControlCapability](#9-remotecontrolcapability)
10. [BeautyCapability（非 Agent 编排）](#10-beautycapability非-agent-编排)

---

## 1. Capability 概览

| Capability | name | 活跃场景 | 命令数 | 状态 | 生命周期 |
|------------|------|----------|--------|------|----------|
| **CameraCapability** | `camera` | CAMERA | 12 | ✅ 已落地 | 页面级（CameraScreen） |
| **GalleryCapability** | `gallery` | GALLERY | 7 | ✅ 已落地 | 应用级单例 + 页面 delegate |
| **SettingsCapability** | `settings` | SETTINGS | 5 | ✅ 已落地 | 应用级单例 + 页面 delegate |
| **NavigationCapability** | `navigation` | ALL | 2 | ✅ 已落地 | Activity 级（MainActivity） |
| **SystemCapability** | `system` | ALL | 2 | ✅ 已落地 | Activity/Service 级 |
| **AutoTagCapability** | `auto_tag` | GALLERY | 4 | ✅ 已落地 | 应用级 |
| **AiOptimizeCapability** | `ai_optimize` | GALLERY, CHAT | 1 | ✅ 已落地 | 应用级 |
| **RemoteControlCapability** | `remote_control` | ALL | 0 | ✅ 已落地 | 应用级单例，不走 AgentCommand 路由 |
| **BeautyCapability** | — | — | — | ✅ 已落地 | 测试/程序化 API，不注册到 Agent 编排 |

> **变更说明（2026-07-06）**：
> - 新增 `AutoTagCapability`、`AiOptimizeCapability`、`RemoteControlCapability`
> - 移除 `AccessibilityCapability`（当前代码库中不存在对应实现）
> - 移除 `EditCapability`（编辑页独立路由尚未落地）
> - `CameraCapability` 命令从 11 个增加到 12 个（新增 `delay`）

### 1.1 场景 - 能力映射

| 场景 | 可用 Capability |
|------|-----------------|
| `CAMERA` | CameraCapability, NavigationCapability, SystemCapability |
| `GALLERY` | GalleryCapability, AutoTagCapability, AiOptimizeCapability, NavigationCapability, SystemCapability |
| `SETTINGS` | SettingsCapability, NavigationCapability, SystemCapability |
| `CHAT` | AiOptimizeCapability, NavigationCapability, SystemCapability |
| `DEBUG` | NavigationCapability, SystemCapability |
| `UNKNOWN` | NavigationCapability, SystemCapability, RemoteControlCapability |

---

## 2. CameraCapability

**职责**: 相机控制、美颜调节、滤镜切换、拍摄模式管理、延迟拍照  
**活跃场景**: `CAMERA`  
**文件**: `app/src/main/java/com/mamba/picme/features/camera/capability/CameraCapability.kt`  
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
| `delay` | `delay_ms: Int` | 延迟执行（可组合其他命令） | "3秒后拍照" |

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

### 2.3 生命周期

- **页面级**：由 `CameraScreen` 创建和持有
- `CameraScreen Enter → CameraCapability() 创建 → 注册到 CapabilityHost`
- `CameraScreen Exit → CapabilityHost 注销 → CameraCapability 被 GC 回收`

---

## 3. GalleryCapability

**职责**: 相册查看、删除、分享、搜索、批量选择、收藏  
**活跃场景**: `GALLERY`  
**文件**: `app/src/main/java/com/mamba/picme/features/gallery/capability/GalleryCapability.kt`  
**状态**: ✅ 已落地

### 3.1 支持命令

| 命令 | 参数 | 描述 | 示例 |
|------|------|------|------|
| `view_media` | `media_id: String?` | 查看照片/视频 | "看这张照片" |
| `delete_media` | `media_ids: List<String>` | 删除照片/视频 | "删除这张" |
| `share_media` | `media_ids: List<String>` | 分享照片/视频 | "分享这张" |
| `favorite_media` | `media_id: String, favorite: Boolean` | 收藏/取消收藏 | "收藏这张" |
| `search_media` | `query: String` | 搜索照片 | "找昨天的照片" |
| `select_media` | `media_id: String, selected: Boolean` | 批量选择 | "多选这张" |
| `switch_view_mode` | `mode: String` | 切换视图模式 | "网格视图" |

### 3.2 生命周期

- **应用级单例 + 页面 delegate**：在 `Application.onCreate()` 中注册一次
- 相册页面激活时绑定 delegate，离开时解绑
- 支持跨页面指令排队：页面再次激活时执行待处理命令

---

## 4. SettingsCapability

**职责**: 主题切换、语言设置、模型管理、人脸引擎切换、调试选项  
**活跃场景**: `SETTINGS`  
**文件**: `app/src/main/java/com/mamba/picme/features/settings/capability/SettingsCapability.kt`  
**状态**: ✅ 已落地

### 4.1 支持命令

| 命令 | 参数 | 描述 | 示例 |
|------|------|------|------|
| `change_theme` | `theme: String` | 切换主题（light/dark/system） | "深色模式" |
| `change_language` | `language: String` | 切换语言（zh/en） | "英文界面" |
| `download_model` | `model_id: String` | 下载 AI 模型 | "下载美颜模型" |
| `switch_face_engine` | `engine: String` | 切换人脸引擎（mediapipe/mnn/custom） | "用 MediaPipe" |
| `toggle_setting` | `key: String, enabled: Boolean` | 开关设置项 | "开启调试模式" |

### 4.2 生命周期

- **应用级单例 + 页面 delegate**
- 设置页面激活时绑定 delegate，离开时解绑

---

## 5. NavigationCapability

**职责**: 页面切换、返回上一页  
**活跃场景**: `ALL` (所有场景)  
**文件**: `app/src/main/java/com/mamba/picme/domain/agent/capability/NavigationCapability.kt`  
**状态**: ✅ 已落地

### 5.1 支持命令

| 命令 | 参数 | 描述 | 示例 |
|------|------|------|------|
| `navigate_to` | `destination: String` | 切换到指定页面 | "去相册"/"打开设置" |
| `go_back` | - | 返回上一页 | "返回"/"回去" |

### 5.2 页面映射

| 意图关键词 | 目标页面 |
|-----------|---------|
| "相机", "拍照" | `camera` |
| "相册", "照片", "gallery" | `gallery` |
| "设置", "设定" | `settings` |
| "聊天", "对话" | `chat` |
| "调试" | `debug` |
| "模型中心" | `model_center` |

### 5.3 生命周期

- **Activity 级**：由 `MainActivity` 创建和持有
- 同时在 `MainActivity` 中通过 `AgentOrchestrator.registerCapability()` 注册到全局 `CapabilityRegistry`

---

## 6. SystemCapability

**职责**: 启动其他应用、打开系统设置  
**活跃场景**: `ALL` (所有场景)  
**文件**: `app/src/main/java/com/mamba/picme/domain/agent/capability/SystemCapability.kt`  
**状态**: ✅ 已落地

### 6.1 支持命令

| 命令 | 参数 | 描述 | 示例 |
|------|------|------|------|
| `launch_app` | `package_name: String?`, `app_name: String?` | 启动应用 | "打开微信" |
| `open_system_settings` | `setting: String` | 打开系统设置 | "打开WiFi设置" |

### 6.2 生命周期

- 在 `MainActivity` 和 `FloatingChatBubbleService` 中创建并注册
- 构造函数注入 `Context`

---

## 7. AutoTagCapability

**职责**: 将标签系统作为 Agent 可编排的 Capability 暴露，支持触发全量标签扫描、查询照片标签、获取进度、取消扫描  
**活跃场景**: `GALLERY`  
**文件**: `app/src/main/java/com/mamba/picme/domain/agent/capability/AutoTagCapability.kt`  
**状态**: ✅ 已落地

### 7.1 支持命令

| 命令 | 参数 | 描述 | 示例 |
|------|------|------|------|
| `scan_all_tags` | - | 触发全量标签扫描 | "扫描所有照片标签" |
| `get_photo_tags` | `photo_id: Long` | 查询指定照片的标签 | "查看这张照片的标签" |
| `get_tag_progress` | - | 获取当前扫描进度 | "标签扫描进度" |
| `cancel_tag_scan` | - | 取消当前扫描 | "取消标签扫描" |

### 7.2 生命周期

- **应用级**：委托给 `TagScanOrchestrator` 执行
- 所有扫描统一走 orchestrator，确保与 UI 控制页进度同源

---

## 8. AiOptimizeCapability

**职责**: AI 一键优化图片，分析照片场景并自动推荐美颜、滤镜、调节参数  
**活跃场景**: `GALLERY`, `CHAT`  
**文件**: `app/src/main/java/com/mamba/picme/domain/agent/capability/optimize/AiOptimizeCapability.kt`  
**状态**: ✅ 已落地

### 8.1 支持命令

| 命令 | 参数 | 描述 | 示例 |
|------|------|------|------|
| `ai_optimize` | `image_uri: String`, `mode: String?` | AI 一键优化图片 | "优化这张照片" |

参数说明：
- `image_uri`: 待优化图片的本地文件 URI（必填）
- `mode`: `fast`（本地场景分析 + 本地预设，默认）或 `smart`（云端视觉模型推荐，需用户授权）

### 8.2 生命周期

- **应用级**：在 `Application.onCreate()` 中注册
- 实际优化逻辑委托给 `AiOptimizeUseCase`

---

## 9. RemoteControlCapability

**职责**: IM 远程控制：管理设备绑定与远程命令执行状态  
**活跃场景**: `ALL`  
**文件**: `app/src/main/java/com/mamba/picme/domain/agent/capability/RemoteControlCapability.kt`  
**状态**: ✅ 已落地

### 9.1 支持命令

**无 AgentCommand 路由命令**。`RemoteControlCapability` 不通过 `AgentCommand` 密封类分发命令；所有管理操作通过公开 API 由 `RemoteCommandDispatcher` 直接调用。

`execute()` 始终返回 `METHOD_NOT_FOUND`。

### 9.2 公开管理 API

| API | 说明 |
|-----|------|
| `updateBinding(token, relayUrl, userId, deviceName)` | 更新设备绑定状态 |
| `clearBinding()` | 清除设备绑定 |
| `setAutoConfirm(enabled)` | 设置自动确认模式 |
| `buildStatusString()` | 构建设备状态描述 |

### 9.3 生命周期

- **应用级单例**：在 `Application.onCreate()` 中创建，永不注销
- 进程结束时 `onDestroy()` 清理状态

---

## 10. BeautyCapability（非 Agent 编排）

**职责**: 提供美颜调节的标准化程序化能力，支持生产代码和测试直接调用  
**文件**: `app/src/main/java/com/mamba/picme/capability/BeautyCapability.kt`  
**状态**: ✅ 已落地

### 10.1 支持操作

| 操作 | 参数 | 描述 |
|------|------|------|
| `adjustSmoothing` | `smoothness: Float` | 调整磨皮 |
| `adjustWhitening` | `whitening: Float` | 调整美白 |
| `adjustSlimFace` | `slimFace: Float` | 调整瘦脸 |
| `adjustBigEyes` | `bigEyes: Float` | 调整大眼 |
| `applyAllEffects` | `settings: BeautySettings` | 应用所有美颜效果 |
| `batchTest` | `paramSets: List<BeautySettings>` | 批量测试多组参数 |

### 10.2 说明

- `BeautyCapability` **不注册到 `CapabilityRegistry`**，不作为 Agent 可编排能力
- 供测试引擎、自动化测试或业务代码直接调用
- 若未来需要 Agent 控制美颜参数，应通过 `CameraCapability.adjust_beauty` 命令

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

### 步骤 2: 注册到 AgentOrchestrator

```kotlin
AgentOrchestrator.getInstance(context).registerCapability(NewCapability())
```

### 步骤 3: 在 `AgentCommand` 密封类中添加命令类型

位置：`agent-core/src/main/java/com/mamba/.../AgentCommand.kt`（或项目实际路径）

### 步骤 4: 更新本文档与 `COMMAND_REFERENCE.md`

新增 Capability 必须在 `CAPABILITY_REGISTRY.md` 和 `COMMAND_REFERENCE.md` 中同步登记。

---

> **参考文档**:
> - [AGENT_ARCHITECTURE.md](../02-ARCHITECTURE/AGENT_ARCHITECTURE.md) — Agent 架构设计
> - [COMMAND_REFERENCE.md](./COMMAND_REFERENCE.md) — 命令语法参考
> - [CAPABILITY_IMPLEMENTATION_GUIDE.md](./CAPABILITY_IMPLEMENTATION_GUIDE.md) — 实现指南
