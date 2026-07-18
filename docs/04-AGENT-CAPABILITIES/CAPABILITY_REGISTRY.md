# PoLang Agent Capability 注册表

> **边界声明（Boundary Statement）**
> - 本文档定义所有 Agent Capability 的注册表、命令映射、执行逻辑、新增 Capability 实现指南以及生命周期规范。
> - 架构设计以 [`../02-ARCHITECTURE/AGENT_ARCHITECTURE.md`](../02-ARCHITECTURE/AGENT_ARCHITECTURE.md) 为准。
> - 交互规范以 [`../01-PRODUCT/FEATURES.md`](../01-PRODUCT/FEATURES.md) 为准。

**模块定位**: Agent 能力注册表、命令映射、实现指南与生命周期规范  
**主要维护者**: [RD] 全栈工程师  
**阅读对象**: RD、AI Agent  
**版本**: 1.2  
**最后更新**: 2026-07-08  

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
11. [附录 A：新增 Capability 指南](#附录-a新增-capability-指南)
12. [附录 B：Capability 生命周期规范](#附录-bcapability-生命周期规范)

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

## 附录 A：新增 Capability 指南

### 1. 新增 Capability 流程

#### 步骤 1: 定义 Capability 接口实现

```kotlin
class YourNewCapability : Capability {

    // 1. 定义能力标识
    override val name = "your_feature"
    override val description = "功能的简短描述，用于 System Prompt"

    // 2. 声明活跃场景
    override fun activeScenes() = listOf(
        SceneManager.Scene.YOUR_SCENE
    )

    // 3. 列出支持的命令
    override fun supportedCommands() = listOf(
        "command_1",
        "command_2",
        "text_reply"
    )

    // 4. 实现命令执行逻辑
    override suspend fun execute(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext?
    ): Result<AgentAction> {
        return when (command) {
            is AgentCommand.YourCommand -> {
                // 执行具体逻辑
                performYourAction(command)
                Result.success(AgentAction.Success(command))
            }

            is AgentCommand.TextReply -> {
                Result.success(AgentAction.Text(command.message))
            }

            else -> {
                Result.success(AgentAction.Error("不支持的命令：${command::class.simpleName}"))
            }
        }
    }

    private fun performYourAction(cmd: AgentCommand.YourCommand): AgentAction {
        // TODO: 实现你的业务逻辑
        // 注意：不要直接依赖 UI 层，使用回调注入
    }
}
```

#### 步骤 2: 注册到 CapabilityRegistry

```kotlin
// 在 Application 或 ViewModel 初始化时
val capabilityRegistry = CapabilityRegistry().apply {
    register(YourNewCapability())
    // 确保 NavigationCapability 始终注册
    register(NavigationCapability(onNavigate = {...}, onBack = {...}))
}
```

#### 步骤 3: 扩展 AgentCommand

```kotlin
sealed class AgentCommand {
    // ... 现有命令

    // 新增命令
    data class YourCommand(val param1: String, val param2: Int) : AgentCommand()
}
```

#### 步骤 4: 更新 PromptBuilder

```kotlin
class PromptBuilder(private val sceneManager: SceneManager) {

    fun buildSystemPrompt(
        capabilities: List<Capability>,
        context: AgentContext
    ): String {
        return buildString {
            appendLine(basePrompt)
            appendLine()
            appendLine("当前页面：${sceneManager.currentScene.value.name}")
            appendLine()
            appendLine("可用功能:")

            capabilities.forEach { cap ->
                appendLine("- ${cap.name}: ${cap.description}")
                cap.supportedCommands().forEach { cmd ->
                    appendLine("  • $cmd")
                }
            }
        }
    }
}
```

#### 步骤 5: 添加自然语言映射（可选）

```kotlin
object NaturalLanguageMapper {
    fun parseToCommand(input: String): AgentCommand? {
        return when {
            input.contains("你的功能") -> AgentCommand.YourCommand("default", 0)
            input.contains("参数 1") -> AgentCommand.YourCommand("value1", 42)
            else -> null
        }
    }
}
```

### 2. Capability 接口详解

#### `name: String`

**用途**: 能力的唯一标识符  
**要求**: 小写字母 + 下划线，无空格  
**示例**: `"camera"`, `"gallery"`, `"your_feature"`

#### `description: String`

**用途**: 用于 System Prompt 的自描述  
**要求**: 简洁明了，不超过 50 字  
**示例**: `"相机控制：拍照、录像、美颜、滤镜"`

#### `activeScenes(): List<SceneManager.Scene>`

**用途**: 声明该能力在哪些场景可用  
**要求**: 必须返回非空列表  
**示例**:
```kotlin
override fun activeScenes() = listOf(
    SceneManager.Scene.CAMERA,
    SceneManager.Scene.DEBUG
)
```

#### `supportedCommands(): List<String>`

**用途**: 列出所有支持的命令名称  
**要求**: 必须包含 `"text_reply"`  
**示例**:
```kotlin
override fun supportedCommands() = listOf(
    "perform_action",
    "cancel_action",
    "text_reply"
)
```

#### `execute(...): Result<AgentAction>`

**用途**: 执行解析后的命令  
**参数**:
- `command`: 解析后的结构化命令
- `context`: 全局上下文（对话历史、用户信息等）
- `pageContext`: 页面特定上下文（如当前选中的照片）

**返回值**:
- `Result.success(AgentAction.Success)` - 执行成功
- `Result.success(AgentAction.Error(reason))` - 执行失败
- `Result.success(AgentAction.Text(message))` - 文本回复

### 3. 命令解析器扩展

#### 扩展 Sealed Class

```kotlin
sealed class AgentCommand {
    data class YourCommand(
        val param1: String,
        val param2: Int,
        val optionalParam: String? = null
    ) : AgentCommand()
}
```

#### JSON 解析规则

**必须使用 `kotlinx.serialization`，禁止正则**:

```kotlin
import kotlinx.serialization.json.*

fun parseJsonResponse(jsonString: String): List<AgentCommand> {
    return Json.decodeFromString<List<YourCommand>>(jsonString)
        .map { AgentCommand.YourCommand(it.param1, it.param2) }
}
```

#### 错误处理

```kotlin
override suspend fun execute(
    command: AgentCommand,
    context: AgentContext,
    pageContext: PageContext?
): Result<AgentAction> {
    return try {
        when (command) {
            is AgentCommand.YourCommand -> {
                // 参数验证
                if (command.param2 < 0 || command.param2 > 100) {
                    return Result.success(AgentAction.Error("param2 必须在 0-100 范围"))
                }

                // 执行逻辑
                performAction(command)
                Result.success(AgentAction.Success(command))
            }

            else -> Result.success(AgentAction.Error("不支持的命令"))
        }
    } catch (e: Exception) {
        Log.e("YourCapability", "执行失败", e)
        Result.success(AgentAction.Error("执行异常：${e.message}"))
    }
}
```

### 4. 页面上下文集成

#### 定义 PageContext

```kotlin
sealed class PageContext {
    data class YourContext(
        val currentData: YourData?,
        val selectedItems: List<YourItem>,
        val extraInfo: Map<String, Any>?
    ) : PageContext()

    object None : PageContext()
}
```

#### 获取 PageContext

```kotlin
override suspend fun execute(
    command: AgentCommand,
    context: AgentContext,
    pageContext: PageContext?
): Result<AgentAction> {
    val yourContext = pageContext as? PageContext.YourContext

    return when (command) {
        is AgentCommand.YourCommand -> {
            val data = command.param1?.let { findDataById(it) }
                ?: yourContext?.currentData

            data?.let { performAction(it, command) }
            Result.success(AgentAction.Success(command))
        }

        else -> Result.success(AgentAction.Error("不支持的命令"))
    }
}
```

#### UI 层提供 Context

```kotlin
@Composable
fun YourScreen(
    viewModel: YourViewModel
) {
    val pageContext by viewModel.pageContext.collectAsState()

    GlobalAgentPanel(
        orchestrator = agentOrchestrator,
        pageContextProvider = { pageContext }
    )
}
```

### 5. 测试与验证

#### 单元测试

```kotlin
class YourCapabilityTest {

    private lateinit var capability: YourCapability

    @Before
    fun setup() {
        capability = YourCapability(
            onPerformAction = { param1, param2 ->
                // Mock 逻辑
            }
        )
    }

    @Test
    fun `test valid command executes successfully`() {
        val command = AgentCommand.YourCommand("valid", 50)
        val context = AgentContext()
        val pageContext = PageContext.None

        val result = capability.execute(command, context, pageContext)

        assert(result.getOrNull() is AgentAction.Success)
    }

    @Test
    fun `test invalid parameter returns error`() {
        val command = AgentCommand.YourCommand("valid", 150) // Out of range
        val context = AgentContext()
        val pageContext = PageContext.None

        val result = capability.execute(command, context, pageContext)

        assert(result.getOrNull() is AgentAction.Error)
        assert(result.getOrNull()?.message?.contains("范围") == true)
    }

    @Test
    fun `test text_reply returns message`() {
        val command = AgentCommand.TextReply("Hello")
        val context = AgentContext()
        val pageContext = PageContext.None

        val result = capability.execute(command, context, pageContext)

        assert(result.getOrNull() is AgentAction.Text)
        assert((result.getOrNull() as AgentAction.Text).message == "Hello")
    }
}
```

#### 集成测试

```kotlin
class YourCapabilityIntegrationTest {

    @Test
    fun `test end-to-end command flow`() = runTest {
        // 1. 模拟用户输入
        val userInput = "执行你的功能，参数 1 为 test，参数 2 为 75"

        // 2. 构建 Orchestrator
        val orchestrator = AgentOrchestrator(
            llmEngine = mockLlmEngine,
            capabilityRegistry = registry
        )

        // 3. 执行解析与执行
        val result = orchestrator.processUserInput(userInput, createContext())

        // 4. 验证结果
        assertTrue(result.isSuccess)
        verify(mockService).performAction("test", 75)
    }
}
```

#### QA 验收清单

- [ ] 命令能被 LLM 正确解析
- [ ] 参数验证生效
- [ ] 错误信息友好
- [ ] 文本回复符合预期
- [ ] 页面上下文正确传递
- [ ] 多场景切换不崩溃
- [ ] 性能达标（执行耗时 < 100ms）

### 6. 常见陷阱

#### ❌ 陷阱 1: 硬编码 System Prompt

**错误**:
```kotlin
class YourCapability : Capability {
    private val systemPrompt = """
        你是 PoLang 助手...
        可用功能：your_feature
        • command_1
    """.trimIndent()
}
```

**正确**:
```kotlin
class PromptBuilder(private val sceneManager: SceneManager) {
    fun buildSystemPrompt(capabilities: List<Capability>): String {
        // 动态构建，支持插件化
    }
}
```

#### ❌ 陷阱 2: 直接依赖 UI 层

**错误**:
```kotlin
class YourCapability : Capability {
    override suspend fun execute(..., pageContext: PageContext?) {
        // ❌ 直接调用 UI 方法
        uiController.updateView(data)
    }
}
```

**正确**:
```kotlin
class YourCapability(
    private val onUpdateView: ((Data) -> Unit)? = null
) : Capability {
    override suspend fun execute(...) {
        // ✅ 通过回调注入
        onUpdateView?.invoke(data)
    }
}
```

#### ❌ 陷阱 3: 使用正则解析 JSON

**错误**:
```kotlin
fun parseJson(json: String): YourCommand {
    val param1 = json Regex "\"param1\": \"([^\"]+)\"" groupValues[1]
    // ❌ 无法处理嵌套/转义
}
```

**正确**:
```kotlin
fun parseJson(json: String): YourCommand {
    return Json.decodeFromString(json)
    // ✅ 类型安全，支持复杂结构
}
```

#### ❌ 陷阱 4: 忘记注册 Command 映射

**错误**:
```kotlin
// 新增 YourCommand 后未更新 CapabilityRegistry
class CapabilityRegistry {
    fun mapCommand(name: String): AgentCommand? {
        return when (name) {
            "command_1" -> ExistingCommand()
            // ❌ 遗漏 YourCommand
        }
    }
}
```

**正确**:
```kotlin
class CapabilityRegistry {
    fun mapCommand(name: String): AgentCommand? {
        return when (name) {
            "command_1" -> ExistingCommand()
            "your_command" -> YourCommand() // ✅ 同步更新
        }
    }
}
```

#### ❌ 陷阱 5: 忽略线程安全

**错误**:
```kotlin
class YourCapability : Capability {
    private var counter = 0 // ❌ 非线程安全

    override suspend fun execute(...) {
        counter++ // 并发修改
    }
}
```

**正确**:
```kotlin
class YourCapability : Capability {
    private val counter = AtomicInteger(0) // ✅ 原子操作

    override suspend fun execute(...) {
        counter.incrementAndGet()
    }
}
```

### 7. Checklist

#### 代码审查清单

- [ ] Capability 接口实现完整
- [ ] `name` / `description` 清晰准确
- [ ] `activeScenes()` 正确声明
- [ ] `supportedCommands()` 包含所有命令
- [ ] `execute()` 处理所有命令分支
- [ ] 参数验证与错误处理完善
- [ ] 不使用正则解析 JSON
- [ ] 不直接依赖 UI 层
- [ ] 已注册到 CapabilityRegistry
- [ ] 已更新 PromptBuilder
- [ ] 单元测试覆盖核心路径
- [ ] 日志规范（`PoLang:YourCapability`）

#### 文档同步清单

- [ ] 更新 `CAPABILITY_REGISTRY.md` 添加新能力
- [ ] 更新 `COMMAND_REFERENCE.md` 添加命令示例
- [ ] 更新 `FEATURES.md`（如有交互变更）
- [ ] 添加反向链接注释（`// Spec: ...`）

---

## 附录 B：Capability 生命周期规范

> **状态**: 草案  
> **创建**: 2026-06-06  
> **更新**: 2026-06-06  
> **作者**: [RD] 全栈工程师  
> **评审**: [CR] 规范守护者

### 1. 设计目标

| 目标 | 优先级 | 说明 |
|------|--------|------|
| **零内存泄漏** | P0 | Capability 不得持有 Activity/Fragment/Screen 的强引用 |
| **生命周期对齐** | P0 | Capability 的生命周期必须与页面生命周期严格对齐 |
| **组合优于单例** | P0 | 优先使用依赖注入和组合，避免全局单例 |
| **跨页面命令** | P1 | 支持从任意页面发送命令到目标页面 |
| **低功耗** | P1 | 避免后台轮询和无效状态检查 |

### 2. 当前架构问题

#### 2.1 问题清单

```
┌──────────────────────────────────────────────────────────────┐
│  问题 1: 单例持有页面引用（内存泄漏风险）                       │
├──────────────────────────────────────────────────────────────┤
│  CameraCapability.getInstance() ──► WeakReference<Delegate>  │
│  ▲ 问题: WeakReference 只能缓解，不能根治                      │
│  ▲ 问题: 匿名 Delegate 实现隐式持有 CameraScreen 的闭包变量     │
│  ▲ 问题: 单例生命周期 > Activity 生命周期                      │
└──────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│  问题 2: DisposableEffect 时序竞争（delegate 绑定后立即解绑）   │
├──────────────────────────────────────────────────────────────┤
│  CameraScreen 重组 ──► DisposableEffect.onDispose()          │
│  ▲ 问题: Compose 重组频繁，onDispose 被过早调用                │
│  ▲ 问题: 导航动画期间，旧页面 DisposableEffect 先 dispose      │
│  ▲ 问题: 新页面 DisposableEffect 后 enter，存在时间窗口        │
└──────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│  问题 3: Application 级注册僵化（无法动态扩展）                 │
├──────────────────────────────────────────────────────────────┤
│  Application.onCreate() ──► registry.register(capability)    │
│  ▲ 问题: 注册后无法注销，无法热插拔 Capability                 │
│  ▲ 问题: 所有 Capability 常驻内存，增加基础内存占用              │
│  ▲ 问题: 单元测试需要清理全局状态，增加测试复杂度                │
└──────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│  问题 4: SceneManager 与 Compose 生命周期脱节                   │
├──────────────────────────────────────────────────────────────┤
│  MainActivity 设置 scene ──► SceneManager.transitionTo()     │
│  CameraScreen DisposableEffect ──► bindDelegate()            │
│  ▲ 问题: 两个系统独立运行，存在状态不一致窗口                    │
│  ▲ 问题: SceneManager 引用计数复杂，容易出错                   │
└──────────────────────────────────────────────────────────────┘
```

#### 2.2 内存泄漏路径分析

```kotlin
// 当前代码（泄漏路径）
class CameraCapability : BaseCapability() {
    companion object {
        private var instance: CameraCapability? = null  // 静态引用，永不释放
        fun getInstance() = instance!!
    }

    private var delegateRef: WeakReference<Delegate>? = null
}

// CameraScreen.kt
DisposableEffect(Unit) {
    val cameraCapability = CameraCapability.getInstance()  // 获取单例
    cameraCapability.bindDelegate(object : CameraCapability.Delegate {
        override fun onSwitchRatio(ratio: String) {
            aspectRatio = ratio  // 匿名类隐式持有 CameraScreen 的 aspectRatio
        }
        // ... 其他方法同样持有 CameraScreen 的状态引用
    })
    // 即使 WeakReference 被清理，单例仍然存活，且匿名类的类加载器引用链复杂
}
```

### 3. 新架构设计

#### 3.1 核心原则

##### 原则 1: 页面级 Capability（Page-Scoped Capability）

```kotlin
// ✅ 新设计: Capability 随页面创建和销毁
@Composable
fun CameraScreen(
    viewModel: MediaViewModel,
    // Capability 通过参数注入，而非全局单例
    cameraCapability: CameraCapability = remember { CameraCapability() }
) {
    // CameraCapability 直接持有状态，无需 delegate 模式
    DisposableEffect(Unit) {
        // 注册到当前页面的 Capability 集合
        LocalCapabilityHost.current.register(cameraCapability)
        onDispose {
            LocalCapabilityHost.current.unregister(cameraCapability)
        }
    }
}
```

##### 原则 2: 组合优于单例（Composition over Singleton）

```kotlin
// ❌ 旧设计: 单例访问
val registry = CapabilityRegistry.getInstance()
registry.register(CameraCapability.getInstance())

// ✅ 新设计: 依赖注入
class MainActivity : ComponentActivity() {
    private val navigationCapability = NavigationCapability()  // Activity 级

    override fun onCreate(savedInstanceState: Bundle?) {
        setContent {
            val capabilityHost = rememberCapabilityHost(navigationCapability)
            CompositionLocalProvider(LocalCapabilityHost provides capabilityHost) {
                NavHost(...) { ... }
            }
        }
    }
}
```

##### 原则 3: 状态内聚（State Cohesion）

```kotlin
// ❌ 旧设计: 状态分散在 Screen 和 Capability 之间
class CameraScreen {
    var aspectRatio by remember { mutableIntStateOf(AspectRatio.RATIO_FULL) }
    // Capability 通过 delegate 回调修改 Screen 状态
}

// ✅ 新设计: Capability 持有自己的状态
class CameraCapability {
    var aspectRatio by mutableIntStateOf(AspectRatio.RATIO_FULL)
        private set

    fun switchRatio(ratio: String) {
        aspectRatio = parseRatio(ratio)
    }
}
```

#### 3.2 架构分层

```
┌─────────────────────────────────────────────────────────────────┐
│  Layer 1: Application（全局配置，无状态）                          │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  - LogModuleConfig 默认值                                │   │
│  │  - BeautyEngine 全局初始化（仅一次）                      │   │
│  │  - 不持有任何 Capability 实例                             │   │
│  └─────────────────────────────────────────────────────────┘   │
├─────────────────────────────────────────────────────────────────┤
│  Layer 2: Activity（导航级 Capability）                           │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  NavigationCapability ── 绑定 NavController              │   │
│  │  - 生命周期: Activity.onCreate() ~ Activity.onDestroy()  │   │
│  │  - 作用域: 所有页面共享同一个 NavigationCapability        │   │
│  └─────────────────────────────────────────────────────────┘   │
├─────────────────────────────────────────────────────────────────┤
│  Layer 3: Screen（页面级 Capability）                             │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  CameraCapability ── 绑定 Camera 状态                    │   │
│  │  - 生命周期: Screen Enter ~ Screen Exit                  │   │
│  │  - 作用域: 仅当前 CameraScreen                           │   │
│  │  - 状态: aspectRatio, lensFacing, beautySettings...      │   │
│  └─────────────────────────────────────────────────────────┘   │
├─────────────────────────────────────────────────────────────────┤
│  Layer 4: ViewModel（业务逻辑，跨配置变更存活）                     │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  MediaViewModel ── 媒体数据管理                           │   │
│  │  - 生命周期: Activity 配置变更存活                         │   │
│  │  - 不持有 Capability 引用（通过回调通信）                   │   │
│  └─────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

#### 3.3 Capability 生命周期分类

| 类型 | 生命周期 | 典型示例 | 创建位置 | 销毁位置 |
|------|----------|----------|----------|----------|
| **应用级** | Application | `BeautyEngine` | `Application.onCreate()` | 永不销毁 |
| **活动级** | Activity | `NavigationCapability` | `Activity.onCreate()` | `Activity.onDestroy()` |
| **页面级** | Screen | `CameraCapability` | `Screen 首次重组` | `Screen 从组合树移除` |
| **用例级** | UseCase | `AiAgentUseCase` | `需要时创建` | `不再使用时释放` |

### 4. 详细设计

#### 4.1 CapabilityHost（Capability 容器）

```kotlin
/**
 * Capability 宿主
 *
 * 管理当前作用域内所有 Capability 的注册和查询。
 * 支持层级查找：如果当前宿主找不到，会委托给父宿主。
 */
class CapabilityHost(
    private val parent: CapabilityHost? = null
) {
    private val capabilities = mutableMapOf<String, Capability>()

    fun register(capability: Capability) {
        capabilities[capability.name] = capability
    }

    fun unregister(capability: Capability) {
        capabilities.remove(capability.name)
    }

    fun find(name: String): Capability? {
        return capabilities[name] ?: parent?.find(name)
    }

    fun findForScene(scene: SceneManager.Scene): List<Capability> {
        return capabilities.values.filter {
            it.activeScenes().contains(scene) || it.activeScenes().isEmpty()
        }
    }
}

// Compose 集成
val LocalCapabilityHost = compositionLocalOf<CapabilityHost> {
    error("CapabilityHost not provided")
}

@Composable
fun rememberCapabilityHost(vararg capabilities: Capability): CapabilityHost {
    val parent = LocalCapabilityHost.current
    return remember(capabilities) {
        CapabilityHost(parent).apply {
            capabilities.forEach { register(it) }
        }
    }
}
```

#### 4.2 页面级 CameraCapability

```kotlin
/**
 * 相机控制 Capability（页面级）
 *
 * 由 CameraScreen 创建和持有，Screen 销毁时自动释放。
 * 不再使用 delegate 模式，状态直接内聚在 Capability 中。
 */
class CameraCapability : BaseCapability() {
    override val name: String = "camera"
    override val description: String = "控制相机拍摄、美颜参数、滤镜..."

    // 状态直接内聚在 Capability 中
    var aspectRatio by mutableIntStateOf(AspectRatio.RATIO_FULL)
        private set
    var lensFacing by mutableIntStateOf(CameraSelector.LENS_FACING_BACK)
        private set
    var beautySettings by mutableStateOf(BeautySettings(enabled = false))
        private set

    // 命令执行直接修改内部状态
    override suspend fun execute(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext?
    ): Result<AgentAction> {
        return when (command) {
            is AgentCommand.SwitchRatio -> {
                aspectRatio = parseRatio(command.ratio)
                Result.success(AgentAction.Success(...))
            }
            // ... 其他命令
        }
    }

    override fun isAvailable(): Boolean = true  // 页面级 Capability 只要存在就可用
}
```

#### 4.3 Screen 与 Capability 的绑定

```kotlin
@Composable
fun CameraScreen(
    viewModel: MediaViewModel,
    onNavigateToGallery: () -> Unit,
    onNavigateToSettings: () -> Unit,
) {
    // 创建页面级 Capability
    val cameraCapability = remember { CameraCapability() }

    // 注册到当前 CapabilityHost
    val host = LocalCapabilityHost.current
    DisposableEffect(cameraCapability) {
        host.register(cameraCapability)
        onDispose { host.unregister(cameraCapability) }
    }

    // 将 Capability 的状态绑定到 UI
    val aspectRatio = cameraCapability.aspectRatio
    val lensFacing = cameraCapability.lensFacing

    // UI 使用 Capability 状态
    CameraPreviewContent(
        aspectRatio = aspectRatio,
        lensFacing = lensFacing,
        // ...
    )
}
```

#### 4.4 跨页面命令处理

```kotlin
/**
 * 跨页面命令由 NavigationCapability 统一处理
 *
 * 导航到目标页面后，目标页面的 Capability 自然可用。
 * 无需复杂的排队和轮询机制。
 */
class NavigationCapability(
    private val navController: NavController
) : BaseCapability() {
    override val name: String = "navigation"

    override suspend fun execute(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext?
    ): Result<AgentAction> {
        return when (command) {
            is AgentCommand.NavigateTo -> {
                // 导航到目标页面
                navController.navigate(command.destination)
                // 导航完成后，目标页面的 Capability 会自动接管后续命令
                Result.success(AgentAction.Success(...))
            }
            // ...
        }
    }
}
```

### 5. 迁移路径

#### 5.1 阶段 1: 引入 CapabilityHost（向后兼容）

```kotlin
// 1. 添加 CapabilityHost 和 CompositionLocal
// 2. 修改 CapabilityRegistry 支持从 CapabilityHost 查询
// 3. CameraScreen 同时注册到单例和 CapabilityHost
class CapabilityRegistry {
    fun dispatch(command: AgentCommand, context: AgentContext): Result<AgentAction> {
        // 优先从 CapabilityHost 查找
        val host = LocalCapabilityHost.currentOrNull
        val capability = host?.findForCommand(command)
            ?: findCapabilityForCommand(command)
        // ...
    }
}
```

#### 5.2 阶段 2: 移除单例（破坏性变更）

```kotlin
// 1. 移除 CameraCapability.getInstance()
// 2. 移除 Application 中的 initializeCapabilities()
// 3. MainActivity 创建 NavigationCapability 并注入
// 4. 各 Screen 创建自己的 Capability
```

#### 5.3 阶段 3: 清理废弃代码

```kotlin
// 1. 移除 SceneManager 的引用计数机制
// 2. 移除 CapabilityRegistry 的命令队列
// 3. 移除所有 WeakReference delegate 模式
```

### 6. 内存影响评估

| 指标 | 旧架构 | 新架构 | 变化 |
|------|--------|--------|------|
| 常驻 Capability 数 | 4（永不释放） | 1（Navigation） | -75% |
| CameraCapability 内存占用 | 常驻 | 仅在相机页 | 按需分配 |
| 匿名 Delegate 实例 | 1/页面（泄漏风险） | 0 | 完全消除 |
| 命令队列轮询 | 500ms 间隔 | 无 | 节省 CPU |
| SceneManager 引用计数 | 复杂 | 简单 | 降低复杂度 |

### 7. 红线合规检查

| 红线 | 合规状态 | 说明 |
|------|----------|------|
| [PRIVACY] | ✅ | 无变更 |
| [PERF] | ✅ | 减少常驻内存和后台轮询 |
| [I18N] | ✅ | 无变更 |
| [DOC-SYNC] | ✅ | 本文档同步架构变更 |
| [AGENT-FIRST] | ✅ | 显式生命周期、枚举状态、自描述类型 |

---

> **参考文档**:
> - [AGENTS.md](../../AGENTS.md) — Agent First 架构原则
> - [AGENT_ARCHITECTURE.md](../02-ARCHITECTURE/AGENT_ARCHITECTURE.md) — Agent 架构设计
> - [COMMAND_REFERENCE.md](./COMMAND_REFERENCE.md) — 命令语法参考
