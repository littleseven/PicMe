# PicMe 基于 Agent 命令的自动化测试方案

> **文档类型**: 技术方案 (Technical Spec)
> **关联文档**: [AGENTS.md](/AGENTS.md), [FEATURES.md](/docs/01-PRODUCT/FEATURES.md)
> **最后更新**: 2026-06-06

---

## 1. 现状分析：广播测试方案的痛点

### 1.1 当前方案架构

当前自动化测试通过 `adb shell am broadcast` 发送命令到设备端广播接收器，由 `CameraTestCommandReceiver` 接收后分发给 `CameraTestCommandDispatcher`，最终触发 CameraScreen 的回调执行。

```
测试脚本 (PC) → adb broadcast → CameraTestCommandReceiver → CameraTestCommandDispatcher → CameraScreen
```

### 1.2 核心痛点

| 痛点 | 具体表现 | 影响 |
|------|----------|------|
| **语义丢失** | 广播仅传递字符串 action，参数类型不安全 | 拼写错误、类型不匹配导致命令静默失败 |
| **无执行反馈** | 广播是"发射后不管"，无法确认命令是否被消费 | 测试脚本需轮询 logcat 猜测执行状态 |
| **场景感知缺失** | 广播不了解当前页面状态，命令可能发送到错误页面 | "拍照"命令在相册页面被丢弃，测试脚本不知情 |
| **状态断言困难** | 无法直接查询应用内部状态，依赖截图+图像识别 | 脆弱、慢、维护成本高 |
| **跨页面编排弱** | 多步骤测试需手动管理页面切换和等待 | 脚本复杂、flaky 率高 |
| **类型安全缺失** | 参数通过 Bundle 传递，无编译期检查 | `smooth=80.5` 传入 Int 字段被截断 |

### 1.3 与 Agent 命令体系的对比

| 维度 | 广播测试方案 | Agent 命令体系 |
|------|-------------|---------------|
| **命令定义** | 字符串 + Bundle | `sealed class AgentCommand`，编译期类型安全 |
| **分发机制** | 广播接收器 + 手动解析 | `CapabilityRegistry` 自动路由到对应 Capability |
| **场景感知** | 无 | `SceneManager` 自动过滤，跨页面命令自动排队 |
| **执行反馈** | 无 | `Result<AgentAction>` 明确返回 Success/Error/TextReply |
| **状态查询** | 需单独实现 get_state 广播 | `Capability.isAvailable()` + `PageContext` 实时状态 |
| **批量执行** | 需脚本层手动编排 | `BatchExecute` + `ExecutePlan` 原生支持 |
| **LLM 兼容** | 完全不兼容 | 与 Agent 运行时共用同一套命令，LLM 可直接生成 |

---

## 2. 设计方案：Agent-First 自动化测试架构

### 2.1 核心设计原则

1. **复用而非重建**: 测试直接复用 `AgentCommand` + `CapabilityRegistry` + `ExecutionEngine`，不引入新的命令体系
2. **确定性优先**: 每个测试步骤都有明确的执行结果和状态断言，拒绝"猜测式"验证
3. **自描述测试**: 测试用例本身是可被 LLM 理解的结构化数据，支持 AI 自动生成和诊断
4. **分层隔离**: 测试编排层、命令执行层、状态验证层、报告输出层完全解耦

### 2.2 架构分层

```
┌─────────────────────────────────────────────────────────────────┐
│  Layer 4: 测试编排层 (Test Orchestration)                        │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  AgentTestOrchestrator                                   │   │
│  │  - 解析测试计划 (JSON/YAML/DSL)                           │   │
│  │  - 管理测试生命周期 (setup → run → teardown)              │   │
│  │  - 协调多设备/多轮次测试                                  │   │
│  └─────────────────────────────────────────────────────────┘   │
├─────────────────────────────────────────────────────────────────┤
│  Layer 3: 测试用例层 (Test Case Layer)                           │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  AgentTestCase<T> (已有)                                 │   │
│  │  - 用例 ID、名称、优先级、分类                             │   │
│  │  - 步骤列表 (List<TestStep>)                              │   │
│  │  - 输出提供者 (outputProvider)                            │   │
│  └─────────────────────────────────────────────────────────┘   │
├─────────────────────────────────────────────────────────────────┤
│  Layer 2: 命令执行层 (Command Execution)                         │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  CapabilityRegistry (已有) + ExecutionEngine (已有)      │   │
│  │  - AgentCommand 分发到对应 Capability                     │   │
│  │  - 场景匹配检查 + 跨页面排队                               │   │
│  │  - 批量执行 (BatchExecute) / 计划执行 (ExecutePlan)       │   │
│  └─────────────────────────────────────────────────────────┘   │
├─────────────────────────────────────────────────────────────────┤
│  Layer 1: 状态验证层 (State Verification)                        │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  AgentStateProbe + TestAssertion                         │   │
│  │  - 应用内部状态查询 (非 UI 截图)                           │   │
│  │  - 结构化断言 (state/log/performance)                     │   │
│  │  - 截屏作为辅助证据 (非主要验证手段)                       │   │
│  └─────────────────────────────────────────────────────────┘   │
├─────────────────────────────────────────────────────────────────┤
│  Layer 0: 设备交互层 (Device Interaction)                        │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  DeviceTestController (已有，需重构)                      │   │
│  │  - 应用启动/状态检查                                      │   │
│  │  - 截屏 (screencap)                                      │   │
│  │  - 日志收集 (logcat)                                     │   │
│  │  - 性能数据 (gfxinfo, meminfo)                           │   │
│  └─────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

### 2.3 关键组件设计

#### 2.3.1 双模式输入设计（核心）

测试驱动支持两种输入形式，最终都 converges 到同一执行路径：

```
自然语言指令 ──→ [可选] LLM 解析 ──→ AgentCommand ──→ CapabilityRegistry
                    ↓
格式化指令 ───────→ 直接构造 ───────→ AgentCommand ──→ CapabilityRegistry
```

| 输入模式 | 形式 | 适用场景 | 确定性 |
|---------|------|----------|--------|
| **模式 A: 自然语言** | `"拍张照片并检查是否保存成功"` | 探索性测试、LLM 生成用例、模糊需求 | 中（依赖 LLM 解析） |
| **模式 B: 格式化指令** | `{"method":"capture","params":{},"assert":"photo_saved"}` | P0 回归、性能基线、CI 门禁 | 高（直接构造命令） |

两种模式共享同一套 `AgentCommand` + `CapabilityRegistry` 执行路径，确保测试和生产代码路径一致。

#### 2.3.2 AgentTestOrchestrator（新增）

测试编排器，负责将高层测试意图转换为 Agent 命令序列并执行验证。

```kotlin
/**
 * Agent 测试编排器
 *
 * 支持双模式输入：
 * - 自然语言模式：通过 LLM 解析为 AgentCommand 序列
 * - 格式化模式：直接解析 JSON/YAML 为 AgentCommand
 *
 * 两种模式最终都通过 CapabilityRegistry 执行，并收集结果生成报告。
 */
class AgentTestOrchestrator(
    private val registry: CapabilityRegistry,
    private val reporter: ExecutionReporter,
    private val llmParser: AgentCommandParser? = null  // 自然语言模式需要
) {
    /**
     * 模式 B: 执行格式化的测试用例（P0 回归首选）
     */
    suspend fun <T> execute(case: AgentTestCase<T>): AgentTestResult<T>

    /**
     * 模式 A: 执行自然语言测试意图（探索性测试）
     */
    suspend fun executeIntent(
        naturalLanguage: String,
        context: AgentContext
    ): AgentTestResult<String>

    /**
     * 执行测试套件
     */
    suspend fun executeSuite(suite: TestSuite): SuiteReport

    /**
     * 从 JSON 测试描述生成并执行（支持混合模式）
     */
    suspend fun executeFromJson(json: String): SuiteReport
}
```

#### 2.3.3 AgentStateProbe（新增）

状态探针，提供应用内部状态的类型安全查询接口。

```kotlin
/**
 * Agent 状态探针
 *
 * 通过 CapabilityRegistry 查询应用内部状态，
 * 替代脆弱的截图+图像识别验证方式。
 */
class AgentStateProbe(private val registry: CapabilityRegistry) {

    /**
     * 查询当前场景
     */
    fun currentScene(): SceneManager.Scene

    /**
     * 查询指定 Capability 是否可用
     */
    fun isCapabilityAvailable(capabilityName: String): Boolean

    /**
     * 查询命令在当前场景是否可执行
     */
    fun isCommandAvailable(command: AgentCommand): Boolean

    /**
     * 获取当前页面状态快照
     */
    suspend fun captureStateSnapshot(): StateSnapshot

    /**
     * 等待条件满足（带超时）
     */
    suspend fun waitFor(
        condition: () -> Boolean,
        timeout: Duration = 10.seconds,
        pollInterval: Duration = 500.milliseconds
    ): Boolean
}
```

---

## 3. 测试用例 DSL 与 Agent 命令映射

### 3.1 测试用例的双模式编写

#### 模式 B: 格式化指令（P0 回归首选）—— 纯数据驱动

测试用例以 JSON/YAML 文件定义，测试引擎动态加载并执行，无需编写 Kotlin 代码。

```json
{
  "caseId": "TC-CAMERA-03",
  "name": "拍照与 GPU 后处理验证",
  "category": "CAMERA",
  "priority": "P0",
  "steps": [
    {
      "description": "确保在相机页面",
      "if": "scene != 'CAMERA'",
      "then": [{"method": "navigate_to", "params": {"destination": "camera"}}],
      "assert": {"scene": "CAMERA"}
    },
    {
      "description": "确保后置摄像头",
      "if": "lensFacing == 'front'",
      "then": [{"method": "flip_camera", "params": {}}],
      "assert": {"lensFacing": "back"}
    },
    {
      "description": "设置美颜参数",
      "action": {"method": "adjust_beauty", "params": {"smoothing": 80, "whitening": 60}},
      "assert": {"beautySmooth": ">= 80"}
    },
    {
      "description": "触发拍照",
      "action": {"method": "capture", "params": {}},
      "assert": {"commandResult": "success"}
    },
    {
      "description": "验证 GPU 处理耗时",
      "wait": {"condition": "processing == false", "timeout": 5000},
      "assert": {"gpuProcessTimeMs": "< 1000"}
    }
  ]
}
```

执行方式：
```bash
# 动态加载 JSON 测试用例执行
./scripts/agent-tester case scripts/tests/camera/tc-camera-03.json
```

#### 模式 A: 自然语言指令（探索性测试）

测试脚本写自然语言，由 LLM 解析为 AgentCommand 序列。

```kotlin
// 自然语言测试意图
val intent = "拍张照片，然后切换到相册确认照片已保存"

// 由 LLM 解析为命令序列
val commands = llmParser.parseIntent(intent, context)
// 返回: [AgentCommand.CapturePhoto, AgentCommand.NavigateTo("gallery")]

// 顺序执行并验证
commands.forEach { command ->
    val result = registry.dispatch(command, context)
    // 每步都有明确反馈
}
```

### 3.2 完整测试用例示例（模式 B: 格式化指令）

```kotlin
/**
 * TC-CAMERA-03: 拍照与 GPU 后处理验证（Agent 命令版本）
 */
fun tcCamera03CaptureAgent(registry: CapabilityRegistry, probe: AgentStateProbe): 
    AgentTestCase<Map<String, Any>> = agentTestCase("TC-CAMERA-03-A", "拍照与 GPU 后处理验证(Agent)") {
    
    category(TestCategory.CAMERA)
    priority(TestPriority.P0)

    step("确保在相机页面") {
        action { ctx, _ ->
            if (probe.currentScene() != SceneManager.Scene.CAMERA) {
                registry.dispatch(AgentCommand.NavigateTo("camera"), context)
            }
        }
        assertState("当前场景为相机") { _ ->
            probe.currentScene() == SceneManager.Scene.CAMERA
        }
    }

    step("确保后置摄像头") {
        action { ctx, _ ->
            val state = probe.captureStateSnapshot()
            if (state.cameraState?.lensFacing == "front") {
                registry.dispatch(AgentCommand.FlipCamera, context)
            }
        }
        assertState("使用后置摄像头") { state ->
            state["lensFacing"] == "back"
        }
    }

    step("设置美颜参数") {
        action { ctx, _ ->
            val settings = BeautySettings(smoothing = 80f, whitening = 60f)
            val result = registry.dispatch(AgentCommand.AdjustBeauty(settings), context)
            ctx.setMetadata("beautyResult", result.isSuccess.toString())
        }
        assertCommandSuccess("美颜命令执行成功")
        assertState("美颜参数已应用") { state ->
            (state["beautySmooth"] as? Float)?.let { it >= 80f } == true
        }
    }

    step("触发拍照") {
        action { ctx, _ ->
            val result = registry.dispatch(AgentCommand.CapturePhoto, context)
            ctx.setMetadata("captureResult", result.isSuccess.toString())
            
            // 直接解析 AgentAction 获取反馈
            result.getOrNull()?.let { action ->
                when (action) {
                    is AgentAction.Success -> ctx.addLog("Test", "拍照成功")
                    is AgentAction.Error -> ctx.addLog("Test", "拍照错误: ${action.message}")
                    is AgentAction.TextReply -> ctx.addLog("Test", "拍照回复: ${action.message}")
                }
            }
        }
        assertCommandSuccess("拍照命令执行成功")
    }

    step("验证 GPU 处理耗时") {
        action { ctx, _ ->
            // 等待处理完成，通过状态探针轮询
            val completed = probe.waitFor(
                condition = { probe.captureStateSnapshot().isProcessing == false },
                timeout = 5.seconds
            )
            ctx.setMetadata("processingCompleted", completed.toString())
        }
        assertState("GPU 处理已完成") { state ->
            state["isProcessing"] == false
        }
        assertPerformance("GPU 处理耗时 < 1000ms") { perf ->
            (perf["gpuProcessTimeMs"] as? Long)?.let { it < 1000 } == true
        }
    }

    step("截屏记录最终状态") {
        action { ctx, controller ->
            controller.takeScreenshot("after_capture_agent", ctx)
        }
    }

    output { ctx ->
        mapOf(
            "captureSuccess" to (ctx.getMetadata("captureResult") == "true"),
            "beautyApplied" to (ctx.getMetadata("beautyResult") == "true"),
            "screenshotCount" to ctx.screenshots.size
        )
    }
}
```

### 3.3 新增断言类型

```kotlin
/**
 * Agent 命令执行成功断言
 */
fun TestStepBuilder.assertCommandSuccess(description: String) {
    assertions.add(TestAssertion { context ->
        val result = context.getMetadata("lastCommandResult")
        if (result == "success") {
            AssertionResult.Success
        } else {
            AssertionResult.Failure("命令执行未成功: $description")
        }
    })
}

/**
 * Agent 命令返回特定 Action 类型断言
 */
fun TestStepBuilder.assertActionType(
    description: String,
    expectedType: KClass<out AgentAction>
) {
    assertions.add(TestAssertion { context ->
        val typeName = context.getMetadata("lastActionType")
        if (typeName == expectedType.simpleName) {
            AssertionResult.Success
        } else {
            AssertionResult.Failure("期望 Action 类型 ${expectedType.simpleName}，实际为 $typeName")
        }
    })
}

/**
 * 性能指标断言
 */
fun TestStepBuilder.assertPerformance(
    description: String,
    check: (Map<String, Any>) -> Boolean
) {
    assertions.add(TestAssertion { context ->
        val perf = context.toSnapshot().metadata
            .filter { it.key.startsWith("perf_") }
            .mapKeys { it.key.removePrefix("perf_") }
        if (check(perf)) {
            AssertionResult.Success
        } else {
            AssertionResult.Failure("性能断言失败: $description")
        }
    })
}

/**
 * 场景断言
 */
fun TestStepBuilder.assertScene(expectedScene: SceneManager.Scene) {
    assertions.add(TestAssertion { context ->
        val current = context.getMetadata("currentScene")
        if (current == expectedScene.name) {
            AssertionResult.Success
        } else {
            AssertionResult.Failure("期望场景 ${expectedScene.name}，实际为 $current")
        }
    })
}
```

---

## 4. 状态断言与验证机制

### 4.1 三层验证模型

```
┌─────────────────────────────────────────────┐
│  Layer 3: 业务语义验证                        │
│  - "照片已保存到相册"                         │
│  - "美颜参数已生效"                           │
│  - "GPU 处理在 50ms 内完成"                   │
├─────────────────────────────────────────────┤
│  Layer 2: AgentAction 验证                    │
│  - 命令返回 Success / Error / TextReply       │
│  - 错误消息包含预期关键字                     │
│  - 跨场景命令是否正确排队                     │
├─────────────────────────────────────────────┤
│  Layer 1: 命令执行验证                        │
│  - Result.isSuccess == true                   │
│  - 无异常抛出                                 │
│  - 命令在超时内完成                           │
└─────────────────────────────────────────────┘
```

### 4.2 状态探针实现策略

状态探针不直接访问 UI 组件，而是通过以下渠道获取状态：

| 状态来源 | 获取方式 | 用途 |
|----------|----------|------|
| `SceneManager.currentScene` | 直接读取 StateFlow | 验证当前页面 |
| `Capability.isAvailable()` | 调用接口 | 验证页面是否就绪 |
| `CameraTestCommandDispatcher.currentState` | 读取快照 | 验证相机参数 |
| `ExecutionEngine.stateFlow` | 收集 StateFlow | 验证计划执行状态 |
| `AgentTestFramework.eventFlow` | 收集事件流 | 验证测试事件序列 |
| 结构化日志 | logcat 过滤 | 验证业务事件 |

### 4.3 截屏的定位转变

截屏从"主要验证手段"降级为"辅助诊断证据"：

- **不再用于**: 判断按钮是否存在、判断是否黑屏、识别文字内容
- **仅用于**: 失败时保留现场、人工复核时的视觉参考、UI 回归对比基线

---

## 5. 测试报告与失败诊断

### 5.1 报告结构

```json
{
  "suiteName": "Camera-P0-Agent",
  "timestamp": 1717603200000,
  "totalCases": 5,
  "passedCount": 4,
  "failedCount": 1,
  "cases": [
    {
      "caseId": "TC-CAMERA-03-A",
      "caseName": "拍照与 GPU 后处理验证(Agent)",
      "status": "FAILED",
      "failedStep": 3,
      "steps": [
        {"index": 0, "description": "确保在相机页面", "status": "PASSED", "durationMs": 1200},
        {"index": 1, "description": "确保后置摄像头", "status": "PASSED", "durationMs": 800},
        {"index": 2, "description": "设置美颜参数", "status": "PASSED", "durationMs": 600},
        {"index": 3, "description": "触发拍照", "status": "FAILED", "durationMs": 5200, "reason": "命令返回 Error: 相机页面未激活"},
        {"index": 4, "description": "验证 GPU 处理耗时", "status": "SKIPPED", "reason": "前置步骤失败"}
      ],
      "agentActions": [
        {"step": 3, "command": "capture", "actionType": "Error", "message": "相机页面未激活，请先切换到相机页面"}
      ],
      "stateSnapshots": [
        {"step": 0, "scene": "GALLERY", "capabilities": ["GalleryCapability", "NavigationCapability"]},
        {"step": 3, "scene": "GALLERY", "capabilities": ["GalleryCapability", "NavigationCapability"]}
      ],
      "screenshots": [
        {"name": "after_capture_agent", "path": "/sdcard/.../after_capture_agent_123456.png"}
      ],
      "logs": [
        {"tag": "CapabilityRegistry", "message": "[CapturePhoto] Capability CameraCapability unavailable (delegate not bound)", "level": "INFO"}
      ],
      "diagnosis": {
        "rootCause": "相机页面未激活时执行拍照命令",
        "suggestion": "在步骤 0 中 navigateTo(camera) 后添加等待，确保 CameraCapability delegate 已绑定",
        "confidence": "HIGH"
      }
    }
  ]
}
```

### 5.2 自动诊断规则

```kotlin
/**
 * 失败自动诊断引擎
 */
object TestFailureDiagnosis {

    fun diagnose(failure: AgentTestResult.Failure<*>): Diagnosis {
        val step = failure.failedStep
        val reason = failure.reason
        val snapshots = failure.context.stateSnapshots
        val currentScene = snapshots.lastOrNull()?.state?.get("scene")

        return when {
            // 诊断 1: 场景不匹配
            reason.contains("未激活") || reason.contains("页面未") -> {
                Diagnosis(
                    rootCause = "目标页面未激活时执行命令",
                    suggestion = "在导航命令后添加 waitForCapabilityAvailable 等待",
                    confidence = DiagnosisConfidence.HIGH,
                    category = DiagnosisCategory.SCENE_MISMATCH
                )
            }

            // 诊断 2: Capability 未注册
            reason.contains("暂不支持") -> {
                Diagnosis(
                    rootCause = "Capability 未注册或命令映射错误",
                    suggestion = "检查 Application.onCreate 中是否注册了对应 Capability",
                    confidence = DiagnosisConfidence.HIGH,
                    category = DiagnosisCategory.CAPABILITY_MISSING
                )
            }

            // 诊断 3: 命令超时
            reason.contains("timed out") -> {
                Diagnosis(
                    rootCause = "命令执行超时",
                    suggestion = "检查是否有阻塞操作，或增加 timeout 配置",
                    confidence = DiagnosisConfidence.MEDIUM,
                    category = DiagnosisCategory.TIMEOUT
                )
            }

            // 诊断 4: GPU 处理超时
            reason.contains("GPU") && reason.contains("超过") -> {
                Diagnosis(
                    rootCause = "GPU 后处理性能不达标",
                    suggestion = "检查美颜参数是否过高，或检测模型是否降级到 CPU",
                    confidence = DiagnosisConfidence.MEDIUM,
                    category = DiagnosisCategory.PERF_REGRESSION
                )
            }

            else -> Diagnosis(
                rootCause = "未知原因: $reason",
                suggestion = "请查看完整日志和截图进行人工分析",
                confidence = DiagnosisConfidence.LOW,
                category = DiagnosisCategory.UNKNOWN
            )
        }
    }
}
```

---

## 6. 迁移路径

### 6.1 阶段一：桥接共存（1-2 周）

- 新增 `AgentTestCommandBridge`，将 `DeviceTestController` 的操作桥接到 `AgentCommand`
- 保留现有广播测试，新增 Agent 命令测试用例并行运行
- 验证 Agent 命令的可靠性和反馈完整性

### 6.2 阶段二：逐步替换（2-3 周）

- 将核心 P0 用例从广播命令迁移到 Agent 命令
- 新增 `AgentStateProbe` 和诊断能力
- 截屏从主要验证降级为辅助诊断

### 6.3 阶段三：全面切换（1 周）

- 移除 `CameraTestCommandReceiver` 和相关广播代码
- `DeviceTestController` 仅保留设备级操作（截屏、日志、性能）
- 所有测试用例通过 `AgentCommand` 执行

### 6.4 废弃代码清单

| 文件 | 操作 | 替代方案 |
|------|------|----------|
| `CameraTestCommandReceiver.kt` | 删除 | `CapabilityRegistry.dispatch(AgentCommand, context)` |
| `CameraTestCommandDispatcher` 中的广播解析 | 删除 | `CapabilityRegistry.dispatch()` |
| `AgentTestBroadcastReceiver.kt` | 删除 | 直接调用 `AgentTestOrchestrator` |
| `DeviceTestController.dispatchCommand()` | 重构 | 直接构造 `AgentCommand` 并 dispatch |

---

## 7. 与现有 Agent 运行时的协同

### 7.1 测试与生产的命令一致性

```
用户语音输入 ──→ AgentOrchestrator ──→ CapabilityRegistry ──→ Capability.execute()
                                                        ↑
测试脚本输入 ──→ AgentTestOrchestrator ───────────────────┘
```

测试和生产使用完全相同的命令分发路径，确保：
- 测试覆盖的就是用户实际触发的代码路径
- 新 Capability 注册后自动可被测试使用
- LLM 生成的命令可直接用于测试用例

### 7.2 测试专用 Capability（可选扩展）

```kotlin
/**
 * 测试专用 Capability
 *
 * 仅在测试模式下注册，提供生产环境不需要的诊断能力。
 */
class TestDiagnosticsCapability : Capability {
    override fun name(): String = "TestDiagnostics"
    override fun activeScenes(): List<SceneManager.Scene> = emptyList() // 全场景
    override fun supportedCommands(): List<String> = listOf(
        "get_internal_state",
        "trigger_gc",
        "dump_memory",
        "simulate_low_memory"
    )
    override fun execute(command: AgentCommand, context: AgentContext, pageContext: PageContext?): Result<AgentAction> {
        // 实现诊断命令
    }
}
```

---

## 8. 收益总结

| 维度 | 广播方案 | Agent 命令方案 | 提升 |
|------|----------|---------------|------|
| **类型安全** | Bundle 字符串，运行时解析 | `sealed class`，编译期检查 | 消除参数错误 |
| **执行反馈** | 无反馈，轮询 logcat | `Result<AgentAction>` 明确返回 | 100% 可观测 |
| **场景感知** | 无 | `SceneManager` + 自动排队 | 跨页面测试可靠 |
| **状态验证** | 截图+图像识别 | 结构化状态探针 | 速度快 10x+ |
| **失败诊断** | 人工分析日志 | 自动诊断 + 建议 | 诊断时间从小时到秒 |
| **LLM 兼容** | 完全不兼容 | 共用命令体系 | AI 可自动生成测试 |
| **维护成本** | 高（字符串易腐） | 低（类型重构安全） | 长期维护成本降低 |

---

## 9. 交付审计清单

- [ ] `AgentTestOrchestrator` 双模式输入实现完成（自然语言 + 格式化指令）
- [ ] `AgentStateProbe` 实现完成，支持场景/Capability/状态查询
- [ ] 新增断言类型 (`assertCommandSuccess`, `assertScene`, `assertPerformance`)
- [ ] 至少 3 个 P0 用例完成 Agent 命令迁移并验证通过（模式 B）
- [ ] 至少 1 个探索性测试用例验证自然语言模式（模式 A）
- [ ] 失败自动诊断引擎实现，覆盖常见失败模式
- [ ] 测试报告 JSON 格式更新，包含 `agentActions` 和 `diagnosis` 字段
- [ ] 废弃广播相关代码：`CameraTestCommandReceiver`, `AgentTestBroadcastReceiver`
- [ ] 文档同步：`docs/03-TECHNICAL-SPECS/AGENT_BASED_AUTOMATION_TEST.md`

---

> **维护者**: RD Agent
> **评审者**: CR Agent, QA Agent
> **实验状态**: 设计中 · 待评审
