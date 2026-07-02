---
name: agent-test-framework
description: |
  PicMe Agent 化测试框架。支持声明式 DSL 定义测试用例、AI Agent 远程触发执行、自动截屏/日志收集、失败诊断与报告生成。
version: 1.0.0
created: 2026-05-26
updated: 2026-05-26
maintainer: [RD] 全栈工程师, [QA] 质量专家
tags:
  - android
  - agent
  - testing
  - automation
  - dsl
  - device
---


# Agent 测试框架 ⚠️ V1 (已由 agent-test-expert V2 替代)

> **⚠️ 已废弃**：V1 测试体系已由 `agent-test-expert`（V2 JSON 驱动 PC 端方案）替代。
> 本文档保留作为架构设计参考。DSL 设计理念仍适用于理解测试架构。
> 当前运行时 UI 验证优先使用 `/accessibility-ui-driver`（结构化 accessibility dump），截图仅保留用于最终视觉验收。


## 定位

面向 AI Agent 的**下一代自动化测试体系**，替代传统的 bash 脚本回归测试。核心特性：

- **声明式 DSL** — 自然语言描述测试步骤，AI 可直接理解和扩展
- **设备端执行** — 测试逻辑在 Android 设备上运行，避免 adb 命令碎片化
- **状态驱动断言** — 基于应用状态快照而非日志文本匹配
- **自动上下文收集** — 失败时自动保留截屏、日志、状态快照
- **双向通信** — AI Agent 通过 `AgentTestBroadcastReceiver` 发送 JSON 命令触发测试，设备端返回结构化 JSON 结果

## 架构概览

```
AI Agent (PC/Server)
    │  adb broadcast
    ▼
┌─────────────────────────────────────┐
│  AgentTestBroadcastReceiver         │
│  (接收命令、分发到 Runner)           │
├─────────────────────────────────────┤
│  AgentTestRunner                    │
│  (管理用例生命周期、收集结果)         │
├─────────────────────────────────────┤
│  AgentTestFramework                 │
│  (核心框架：步骤执行、断言、超时)      │
├─────────────────────────────────────┤
│  DeviceTestController               │
│  (封装设备操作：相机/相册/截屏/日志)   │
│  通过 CapabilityRegistry 直接分发命令 │
└─────────────────────────────────────┘
    │  AgentCommand → Capability
    ▼
CameraScreen / GalleryScreen
```

## 快速开始

### 1. 运行 P0 回归测试（推荐）

```bash
# 一键完整回归（编译→安装→执行→报告）
.qoder/skills/image-quality-checker/scripts/agent-test.sh interactive

# 或仅执行（假设应用已安装）
.qoder/skills/image-quality-checker/scripts/agent-test.sh suite p0
```

### 2. 运行指定模块

```bash
# 相机模块
.qoder/skills/image-quality-checker/scripts/agent-test.sh suite camera

# 美颜模块
.qoder/skills/image-quality-checker/scripts/agent-test.sh suite beauty
```

### 3. 运行单个用例

```bash
.qoder/skills/image-quality-checker/scripts/agent-test.sh case TC-CAMERA-01
```

### 4. 获取报告

```bash
.qoder/skills/image-quality-checker/scripts/agent-test.sh report
```

## 测试用例 DSL

### 定义新用例

```kotlin
// 在 app/src/main/java/com/mamba/picme/testing/agent/cases/ 下创建

fun tcCameraNew(controller: DeviceTestController): AgentTestCase<OutputType> =
    agentTestCase("TC-CAMERA-NEW", "新功能验证") {
        category(TestCategory.CAMERA)
        priority(TestPriority.P0)

        step("步骤描述（AI 可理解的语义）") {
            action { ctx ->
                // 执行设备操作
                controller.capture()
                // 记录状态
                ctx.addStateSnapshot(mapOf("key" to "value"))
            }
            assertState("预期状态") { state ->
                state["key"] == "value"
            }
            assertLogContains("Tag", "pattern", timeout = 5.seconds)
        }

        step("截屏验证") {
            action { ctx ->
                controller.takeScreenshot("step_name", ctx)
            }
            assertScreenshot("画面正常") { screenshot ->
                // 图像验证逻辑
                true
            }
        }

        output { ctx ->
            // 返回测试结果
            ctx.getMetadata("resultKey") ?: ""
        }
    }
```

### 断言类型

| 断言 | 用途 | 示例 |
|------|------|------|
| `assertState` | 验证应用状态 | `state["isPreviewActive"] == true` |
| `assertLogContains` | 验证日志输出 | `assertLogContains("PhotoProcessor", "DONE")` |
| `assertScreenshot` | 验证截屏内容 | 检查亮度、人脸检测 |
| `customAssertion` | 自定义逻辑 | 任意 Kotlin 表达式 |

### 便捷断言工厂

```kotlin
// 常用断言直接复用
AgentAsserts.previewIsActive()
AgentAsserts.photoSaved()
AgentAsserts.gpuProcessCompleted(maxTimeMs = 1000)
AgentAsserts.beautySettingsApplied(smooth = 80, whiten = 60)
```

## AI Agent 集成

### 从 AI Agent 触发测试

```bash
# 1. 发送测试命令（显式组件，Android 12+ 必需）
adb shell "am broadcast -n com.mamba.picme/.testing.agent.bridge.AgentTestBroadcastReceiver -a com.mamba.picme.AGENT_TEST --es json '{\"method\":\"navigate_to\",\"params\":{\"destination\":\"camera\"}}'"

# 2. 等待响应（通过日志）
adb logcat -d | grep "AgentTestReceiver: Response sent"

# 3. 解析 JSON 结果
```

### 响应格式

```json
{
  "suiteName": "Camera",
  "timestamp": 1716748800000,
  "totalCases": 5,
  "passedCount": 5,
  "failedCount": 0,
  "skippedCount": 0,
  "caseResults": [
    {
      "caseId": "TC-CAMERA-01",
      "caseName": "应用启动与预览验证",
      "status": "PASSED",
      "screenshots": ["/sdcard/PicMe_Agent_Test/startup_xxx.png"]
    }
  ]
}
```

### 测试入口

测试命令通过 [AgentTestBroadcastReceiver](app/src/main/java/com/mamba/picme/testing/agent/bridge/AgentTestBroadcastReceiver.kt) 统一接收，无需在每个 Screen 中单独注册接收器。Capability 生命周期重构后，命令通过 `CapabilityRegistry` 分发到当前页面级 Capability 执行。

## 现有用例清单

### 相机模块 (Camera)

| 用例 ID | 名称 | 优先级 |
|---------|------|--------|
| TC-CAMERA-01 | 应用启动与预览验证 | P0 |
| TC-CAMERA-02 | 前后摄像头切换验证 | P0 |
| TC-CAMERA-03 | 拍照与 GPU 后处理验证 | P0 |
| TC-CAMERA-04 | 画幅比例切换验证 | P1 |
| TC-CAMERA-05 | 滤镜切换验证 | P1 |

### 美颜模块 (Beauty)

| 用例 ID | 名称 | 优先级 |
|---------|------|--------|
| TC-BEAUTY-01 | 美颜参数设置与效果验证 | P0 |
| TC-BEauty-02 | 连续滤镜切换验证 | P1 |
| TC-BEAUTY-03 | 相册编辑美颜验证 | P0 |

## 与旧体系对比

| 维度 | 旧体系 (TEST_COMMAND 广播) | 新体系 (Agent Framework JSON) |
|------|---------------------------|-------------------------------|
| 命令格式 | `adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action "capture"` | `adb shell "am broadcast -n ... --es json '{"method":"capture"}'"` |
| 用例定义 | bash 函数 | Kotlin DSL + JSON 用例 |
| 断言方式 | grep 日志文本 | 状态快照 + 类型安全断言 |
| 失败诊断 | 手动查看日志 | 自动收集上下文快照 |
| 扩展性 | 需修改脚本 + Screen 注册接收器 | 新增 JSON/Kotlin 文件即可 |
| AI 理解 | 需解析 bash | 直接理解 DSL/JSON 语义 |
| 截屏关联 | 手动命名 | 自动关联到步骤 |
| 报告格式 | Markdown | JSON + Markdown |
| 生命周期 | 广播接收器随 Screen 注册/注销 | 统一入口，Capability 页面级生命周期 |

## 故障排除

### 命令无响应

1. 确认应用在前台运行
2. 确认使用显式组件 `-n com.mamba.picme/.testing.agent.bridge.AgentTestBroadcastReceiver`
3. 确认 JSON 用单引号包裹：`--es json '{"method":"..."}'`
4. 查看日志：`adb logcat -s PicMe:AgentTest:*`

### 截屏失败

- 检查存储权限：`adb shell ls /sdcard/PicMe_Agent_Test/`
- 部分设备需要 `screencap` 通过 adb 执行而非 Runtime

### 状态断言失败

- 确认 Capability 已正确注册到 CapabilityHost 且 `isAvailable()` 返回 true
- 检查状态字段名是否与断言一致

## 相关文件

- `app/src/main/java/com/mamba/picme/testing/agent/core/` — 核心框架
- `app/src/main/java/com/mamba/picme/testing/agent/cases/` — 测试用例
- `app/src/main/java/com/mamba/picme/testing/agent/device/` — 设备控制器
- `app/src/main/java/com/mamba/picme/testing/agent/runner/` — 运行器
- `app/src/main/java/com/mamba/picme/testing/agent/bridge/` — 广播桥接
- `scripts/agent-test.sh` — AI Agent 侧入口脚本

## 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| 1.0.0 | 2026-05-26 | 初始版本 |
