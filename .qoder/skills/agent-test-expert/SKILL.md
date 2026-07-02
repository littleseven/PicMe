---
name: picme-test-json
description: Use when running PicMe automated tests via JSON commands sent through AgentTestBroadcastReceiver, including test suites, single test cases, camera/agent feature verification, or performance/log collection
version: 2.0.0
created: 2026-06-06
updated: 2026-07-02
maintainer: [RD] 全栈工程师, [QA] 质量专家
tags:
  - android
  - testing
  - agent
  - json
  - adb
  - automation
---


# PicMe JSON 测试

> **定位**：PC 端主导的 JSON 数据驱动自动化测试执行与诊断。
> **触发时机**：用户提及运行测试、验证功能、发送测试命令、Accessibility UI 状态检查、性能采集时启用。

---

## 触发条件

- 运行测试套件：`.qoder/skills/image-quality-checker/scripts/agent-tester suite camera`
- 发送单个 JSON 命令：`.qoder/skills/image-quality-checker/scripts/agent-tester cmd '{"method":"flip_camera"}'`
- 运行单个 JSON 用例：`.qoder/skills/image-quality-checker/scripts/agent-tester case scripts/tests/camera/xxx.json`
- 截屏验证、性能采集、报告生成
- 调试测试命令不生效的问题

---

## 核心原则

1. **PC 端主导**：测试编排、截屏、报告全部由 PC 端完成，应用端只接收命令
2. **JSON 透传**：PC 端不解析参数，直接把 `{"method":"...","params":{...}}` 传给应用端
3. **显式广播**：Android 12+ 必须用 `-n` 指定组件：`com.mamba.picme/.testing.agent.bridge.AgentTestBroadcastReceiver`
4. **单引号包裹 JSON**：防止 adb shell 把 `{}` 解释为 brace expansion
5. **复用 AgentCommandParser**：应用端用与 LLM 命令相同的解析路径处理测试命令

---

## 快速参考

### 发送 JSON 命令（最常用）

```bash
# 导航到相机页
.qoder/skills/image-quality-checker/scripts/agent-tester cmd '{"method":"navigate_to","params":{"destination":"camera"}}'

# 切换画幅比例
.qoder/skills/image-quality-checker/scripts/agent-tester cmd '{"method":"switch_ratio","params":{"ratio":"16_9"}}'

# 切换滤镜
.qoder/skills/image-quality-checker/scripts/agent-tester cmd '{"method":"switch_filter","params":{"filter":"leica_classic"}}'

# 拍照
.qoder/skills/image-quality-checker/scripts/agent-tester cmd '{"method":"capture","params":{}}'

# 翻转摄像头
.qoder/skills/image-quality-checker/scripts/agent-tester cmd '{"method":"flip_camera","params":{}}'
```

### 运行测试套件

```bash
# 相机套件
.qoder/skills/image-quality-checker/scripts/agent-tester suite camera

# 设置页套件
.qoder/skills/image-quality-checker/scripts/agent-tester suite settings
```

### 运行单个用例

```bash
.qoder/skills/image-quality-checker/scripts/agent-tester case scripts/tests/camera/tc-camera-02-flip.json
```

---

## 诊断流程

### Step 1: 确认设备连接

```bash
adb devices | grep "device$"
```

### Step 2: 确认应用状态

```bash
# 应用是否运行
adb shell pidof com.mamba.picme

# 启动应用
adb shell am start -n com.mamba.picme/.MainActivity
```

### Step 3: 发送命令并观察

```bash
# 清除日志
adb logcat -c

# 发送命令（示例：导航到相机页）
.qoder/skills/image-quality-checker/scripts/agent-tester cmd '{"method":"navigate_to","params":{"destination":"camera"}}'

# 查看 PicMe 相关日志
adb logcat -d | grep -iE "AgentTestReceiver|NavigationCapability|CapabilityRegistry|CameraCapability|scene"
```

### Step 4: UI 状态验证

**首选：Accessibility UI dump（结构化文本，更准更省 token）**

```bash
python3 scripts/ui_driver.py dump > /tmp/ui_dump.json
python3 scripts/ui_driver.py find --content-description "快门"
```

**次选：截屏（仅用于最终视觉验证）**

```bash
adb shell screencap -p /sdcard/test.png
adb pull /sdcard/test.png /tmp/test.png
```

---

## 常见陷阱

| 陷阱 | 症状 | 修复 |
|------|------|------|
| 隐式广播被拦截 | 广播发送成功但无响应 | 使用 `-n` 显式组件 |
| JSON 被 brace expansion 截断 | `json=method:switch_ratio` | 用单引号包裹 JSON：`--es json '{...}'` |
| 场景不匹配命令排队 | 命令执行但 UI 无变化 | 先发送 `navigate_to` 切换到目标页面 |
| Capability delegate 未绑定 | `CAPABILITY_UNAVAILABLE` 错误 | 确保目标页面已显示并绑定 delegate |
| 日志过滤不到 | `grep` 无输出 | 检查 PID 是否正确，或直接用 `adb logcat -d` 全量查看 |

---

## 命令格式规范

### JSON 命令结构

```json
{"method":"<命令名>","params":{"<参数名>":"<值>"}}
```

### 支持的命令

| 命令 | 参数示例 | 说明 |
|------|----------|------|
| `navigate_to` | `{"destination":"camera"}` | 导航到指定页面 |
| `capture` | - | 拍照 |
| `flip_camera` | - | 切换前后摄像头 |
| `toggle_recording` | - | 开始/停止录像 |
| `switch_ratio` | `{"ratio":"16_9"}` | 切换画幅比例 |
| `switch_filter` | `{"filter":"leica_classic"}` | 切换滤镜 |
| `switch_style` | `{"style":"toon"}` | 切换风格 |
| `adjust_beauty` | `{"smoothing":80,"whitening":60}` | 调整美颜 |
| `adjust_exposure` | `{"exposure":2}` | 调整曝光 |
| `adjust_zoom` | `{"zoom":2.0}` | 调整变焦 |
| `switch_mode` | `{"mode":"video"}` | 切换拍摄模式 |

---

## 相关文件

- [架构文档](docs/03-TECHNICAL-SPECS/AGENT_TEST_ARCHITECTURE.md) — 完整架构说明
- [PC 端测试脚本](scripts/agent-tester) — 测试执行入口
- [accessibility-ui-driver](/accessibility-ui-driver) — PicMe UI 自动化
- [adb-bot](/adb-bot) — PicMe ADB 参考
- [应用端接收器](app/src/main/java/com/mamba/picme/testing/agent/bridge/AgentTestBroadcastReceiver.kt) — 广播接收与命令分发
- [CapabilityRegistry](app/src/main/java/com/mamba/picme/domain/agent/CapabilityRegistry.kt) — 命令分发中心
- [AgentCommandParser](app/src/main/java/com/mamba/picme/domain/agent/AgentCommandParser.kt) — JSON 命令解析

## 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| 2.0.0 | 2026-07-02 | 统一命名为 PicMe JSON 测试；UI 验证优先使用 accessibility dump |
| 1.0.0 | 2026-06-06 | 初始版本，基于 Agent Test V2 JSON 驱动架构 |
