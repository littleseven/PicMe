# PoLang QA 验收测试清单 (QA Execution Checklist)

> **边界声明（Boundary Statement）**
> - 本文档定义端到端验收测试清单、性能基线验证方法以及 QA/测试相关规范。
> - 本文档同时涵盖：基于 Agent 命令的自动化测试、Accessibility UI Driver 使用说明、核心功能测试指引。
> - 性能指标以 [`../01-PRODUCT/NFR_SPEC.md`](../01-PRODUCT/NFR_SPEC.md) 为准。
> - 技术规范以各模块 `*_TECH_SPEC.md` 为准。
> - 历史性能基线报告见 [`PERFORMANCE_BASELINE_REPORT.md`](./PERFORMANCE_BASELINE_REPORT.md)。

**模块定位**: QA 验收测试标准  
**主要维护者**: [QA] 质量专家  
**阅读对象**: QA、RD  
**版本**: 1.1
**最后更新**: 2026-06-08

---

## 📋 目录

1. [功能验收测试](#1-功能验收测试)
2. [语音交互专项验收](#2-语音交互专项验收)
3. [远程编排专项验收](#3-远程编排专项验收)
4. [帧同步专项验收](#4-帧同步专项验收)
5. [性能基线验证](#5-性能基线验证)
6. [稳定性测试](#6-稳定性测试)
7. [兼容性测试](#7-兼容性测试)
8. [隐私与安全验证](#8-隐私与安全验证)
9. [基于 Agent 命令的自动化测试](#9-基于-agent-命令的自动化测试)
10. [Accessibility UI Driver 使用](#10-accessibility-ui-driver-使用)
11. [核心功能测试指引](#11-核心功能测试指引)

---

## 1. 功能验收测试

### 1.1 Agent 交互

#### 1.1.1 对话模式

| 测试项 | 步骤 | 预期结果 | 状态 |
|--------|------|----------|------|
| 拍照指令 | 输入"拍照" | ✅ 立即拍照，50ms 内反馈 | ☐ |
| 美颜调节 | 输入"磨皮 50" | ✅ 磨皮滑块调至 50，实时预览 | ☐ |
| 滤镜切换 | 输入"冷调滤镜" | ✅ 切换到冷调滤镜，视觉切换 < 200ms | ☐ |
| 参数查询 | 输入"当前美颜多少？" | ✅ 回复当前参数值 | ☐ |
| 多轮对话 | "调高美颜" → "再高一点" | ✅ 连续理解上下文，逐步调整 | ☐ |
| 澄清请求 | 输入模糊指令"调高一点" | ✅ 返回澄清问题 | ☐ |
| 闲聊 | 输入"今天天气怎么样" | ✅ 友好回复 + 能力引导 | ☐ |

**性能要求**:
- Agent 回复首字 < 500ms
- 每个用户输入必须有可见反馈

#### 1.1.2 快捷模式

| 测试项 | 步骤 | 预期结果 | 状态 |
|--------|------|----------|------|
| 美颜面板开关 | 点击美颜面板开关 | ✅ 开启/关闭美颜总开关 | ☐ |
| 参数调节 | 拖动滑块调节参数 | ✅ 实时预览，延迟 < 100ms | ☐ |
| 对比功能 | 长按预览区 | ✅ 显示原图，松手恢复 | ☐ |
| 滤镜滚动 | 横向滚动滤镜列表 | ✅ 流畅滚动，切换动画 < 200ms | ☐ |

### 1.2 相机控制

| 测试项 | 步骤 | 预期结果 | 状态 |
|--------|------|----------|------|
| 拍照 | 点击快门按钮 | ✅ 50ms 触感 + 音效 + 黑场闪烁同步 | ☐ |
| 录像 | 点击录像按钮 | ✅ 开始/停止录制，指示灯变化 | ☐ |
| 翻转镜头 | 点击翻转按钮 | ✅ 前后摄像头切换，无黑屏 > 300ms | ☐ |
| 变焦 | 双指缩放 / 点击 +/- | ✅ 平滑变焦，1x~10x 范围 | ☐ |
| 曝光调节 | 点击太阳图标拖动 | ✅ 曝光补偿 -5~+5，实时预览 | ☐ |
| 拍摄模式切换 | 点击模式选项 | ✅ 正常/夜景/人像/专业模式切换 | ☐ |

### 1.3 美颜系统

| 测试项 | 步骤 | 预期结果 | 状态 |
|--------|------|----------|------|
| 磨皮调节 | 拖动磨皮滑块 0~100 | ✅ 自然磨皮效果，不过度模糊 | ☐ |
| 美白调节 | 拖动美白滑块 0~100 | ✅ 肤色自然提亮，不失真 | ☐ |
| 瘦脸调节 | 拖动瘦脸滑块 -50~+50 | ✅ 推脸/拉脸效果，边缘过渡自然 | ☐ |
| 大眼调节 | 拖动大眼滑块 0~100 | ✅ 眼睛放大，比例协调 | ☐ |
| 唇色调节 | 拖动唇色滑块 0~100 | ✅ 唇色自然变化 | ☐ |
| 腮红调节 | 拖动腮红滑块 0~100 | ✅ 腮红位置准确，不突兀 | ☐ |
| 美颜总开关 | 关闭开关 | ✅ 跳过人脸检测，仅支持滤镜/调色 | ☐ |
| 美颜总开关 | 所有参数归零 | ✅ 自动关闭美颜 | ☐ |

### 1.4 相册管理

| 测试项 | 步骤 | 预期结果 | 状态 |
|--------|------|----------|------|
| 查看照片 | 点击照片缩略图 | ✅ 展开全屏查看，双指缩放可用 | ☐ |
| 删除照片 | 选择照片后点击删除 | ✅ 确认对话框，删除后从列表消失 | ☐ |
| 分享照片 | 选择照片后点击分享 | ✅ 唤起系统分享面板 | ☐ |
| OCR 提取 | 点击"提取文字" | ✅ 原位浮层展示识别文字 | ☐ |
| 图片编辑 | 点击图片"编辑" | ✅ 进入编辑模式，可调节美颜参数 | ☐ |
| 保存编辑 | 编辑后点击保存 | ✅ 保存为新文件，不覆盖原图 | ☐ |

---

## 2. 语音交互专项验收

> **状态（2026-06）**：语音控制为试验性功能，快速迭代验证中。

### 2.1 唤醒词检测

| 测试项 | 步骤 | 预期结果 | 状态 |
|--------|------|----------|------|
| 唤醒词触发 | 在相机预览页说出唤醒词 | ✅ 检测到唤醒词，进入语音输入模式 | ☐ |
| 误触发抑制 | 环境噪音/正常对话中不触发 | ✅ 误触发率 < 5%（非目标场景） | ☐ |
| 页面退出停止 | 切换到其他页面 | ✅ WakeWordEngine 自动停止，VAD 检测关闭 | ☐ |
| 耳机模式适配 | 蓝牙/有线耳机接入 | ✅ 自动切换音源（MIC/VOICE_COMMUNICATION），AEC/NS 正确配置 | ☐ |

### 2.2 语音指令识别

| 测试项 | 步骤 | 预期结果 | 状态 |
|--------|------|----------|------|
| 拍照指令 | 说出"拍照" | ✅ 识别为拍摄命令，50ms 内执行快门 | ☐ |
| 美颜调节 | 说出"磨皮调高一点" | ✅ 识别为美颜调节，参数实时变化 | ☐ |
| 滤镜切换 | 说出"换个冷调滤镜" | ✅ 识别为滤镜切换，视觉变化 < 200ms | ☐ |
| 推流模式 | 长按语音按钮说话 | ✅ 流式 ASR，识别结果实时显示 | ☐ |
| 不支持命令 | 说无关内容 | ✅ 友好提示"暂不支持"或能力引导 | ☐ |

**性能要求**:
- 语音录入 → LLM 解析 → 命令执行 < 500ms
- ASR 首字延迟 < 200ms

---

## 3. 远程编排专项验收

> **状态（2026-06）**：远程 LLM 编排为可选功能，需用户配置 Token 后方可使用。

### 3.1 推理路由

| 测试项 | 步骤 | 预期结果 | 状态 |
|--------|------|----------|------|
| IntentCache 缓存命中 | 输入预定义高频指令（如"拍照"） | ✅ L1 缓存直接命中，不触发 LLM 调用 | ☐ |
| 本地 LLM 推理 | 输入非敏感简单指令 | ✅ 路由到本地 Qwen3.5-2B 推理 | ☐ |
| 远程 LLM 推理 | 配置 Token 后输入复杂指令 | ✅ 路由到云端 LLM API（OpenAI 兼容） | ☐ |
| 隐私守卫拦截 | 输入敏感指令（如人脸数据相关） | ✅ PrivacyGuard 强制本地执行，不发送远程 | ☐ |
| 远程不可用时降级 | 远程 API 超时/不可用 | ✅ 自动降级到本地推理，不阻塞 | ☐ |

**性能要求**:
- 远程推理响应 < 2000ms
- 降级时间 < 500ms

### 3.2 策略选择

| 测试项 | 步骤 | 预期结果 | 状态 |
|--------|------|----------|------|
| 上下文记忆 | 多轮对话中连续指令 | ✅ MemoryManager 正确维护上下文 | ☐ |

> 详细架构设计见 `docs/02-ARCHITECTURE/AGENT_ARCHITECTURE.md`

---

## 4. 帧同步专项验收

### 2.1 预览场景 - 快转头测试

**测试步骤**:
1. 开启美颜系统
2. 快速转头（速度 > 90°/s）
3. 录屏逐帧分析妆容偏差

**验收标准**:
- 红线：妆容偏差 ≤ 16px @1080p
- 目标：妆容偏差 ≤ 8px @1080p

**执行记录**:
| 测试次数 | 偏差像素 | 是否通过 |
|---------|---------|---------|
| 1 | ___ px | ☐ |
| 2 | ___ px | ☐ |
| 3 | ___ px | ☐ |

### 2.2 预览场景 - 人脸出画测试

**测试步骤**:
1. 开启美颜系统
2. 人脸缓慢移出画面
3. 记录妆容消失时间

**验收标准**:
- 红线：≤ 3 帧（~50ms@60fps）
- 目标：≤ 2 帧

**执行记录**:
| 测试次数 | 消失帧数 | 是否通过 |
|---------|---------|---------|
| 1 | ___ 帧 | ☐ |
| 2 | ___ 帧 | ☐ |
| 3 | ___ 帧 | ☐ |

### 2.3 录制场景 - 快转头测试

**测试步骤**:
1. 开启美颜系统 + 录制
2. 快速转头（速度 > 90°/s）
3. 回放视频逐帧分析妆容偏差

**验收标准**:
- 红线：妆容偏差 ≤ 16px @1080p
- 目标：妆容偏差 ≤ 8px @1080p

**执行记录**:
| 测试次数 | 偏差像素 | 是否通过 |
|---------|---------|---------|
| 1 | ___ px | ☐ |
| 2 | ___ px | ☐ |
| 3 | ___ px | ☐ |

### 2.4 录制场景 - 匀速移动测试

**测试步骤**:
1. 开启美颜系统 + 录制
2. 匀速平移设备
3. 相邻帧妆容位移差分析

**验收标准**:
- 红线：相邻帧位移差 ≤ 8px @1080p
- 目标：相邻帧位移差 ≤ 5px @1080p

**执行记录**:
| 测试次数 | 最大位移差 | 是否通过 |
|---------|-----------|---------|
| 1 | ___ px | ☐ |
| 2 | ___ px | ☐ |
| 3 | ___ px | ☐ |

### 2.5 预测补偿精度

**测试步骤**:
1. 模拟人脸缺失 N 帧
2. 使用预测补偿算法计算位置
3. 验证预测位移约束

**验收标准**:
- 预测计算耗时 ≤ 1ms / 帧
- 预测位移约束 ≤ 150% 上一帧位移

**执行记录**:
| 测试项 | 实测值 | 是否通过 |
|--------|--------|---------|
| 预测耗时 | ___ ms | ☐ |
| 位移约束 | ___ % | ☐ |

---

## 5. 性能基线验证

### 3.1 启动性能

| 指标 | 红线 | 目标 | 测量方法 | 实测值 | 状态 |
|------|------|------|----------|--------|------|
| 冷启动 → 首帧预览 | ≤ 500ms | ≤ 400ms | `adb shell am start -W` | ___ ms | ☐ |
| 启动阶段禁止项 | — | 零启动页 / 零数据库迁移阻塞 | 人工检查 + Logcat | ___ | ☐ |

**执行命令**:
```bash
adb shell am start -W com.mamba.picme/com.mamba.picme.ui.MainActivity
```

### 3.2 预览性能

| 指标 | 红线 | 目标 | 测量方法 | 实测值 | 状态 |
|------|------|------|----------|--------|------|
| 预览帧率（高端机） | ≥ 30fps | ≥ 55fps | `BeautyPerfStats.fps` | ___ fps | ☐ |
| 预览帧率（低端机） | ≥ 30fps | ≥ 30fps | `BeautyPerfStats.fps` | ___ fps | ☐ |
| 单帧处理耗时 | ≤ 16ms | ≤ 12ms | Systrace / 自定义计时 | ___ ms | ☐ |

**调试工具**:
- 开启调试浮层：`adb shell setprop picme.debug.perf true`
- 查看 Systrace: `python android/tools/systrace/systrace.py trace.html -t 10 camera SurfaceFlinger`

### 3.3 参数响应

| 指标 | 红线 | 目标 | 测量方法 | 实测值 | 状态 |
|------|------|------|----------|--------|------|
| 参数调节 → 画面变化 | ≤ 100ms | ≤ 50ms | 人工体感 + 高速摄像 | ___ ms | ☐ |

**测试步骤**:
1. 打开美颜面板
2. 拖动任意滑块
3. 使用高速摄像记录从拖动到画面变化的时间

### 3.4 拍照后处理

| 指标 | 红线 | 目标 | 测量方法 | 实测值 | 状态 |
|------|------|------|----------|--------|------|
| 1080p GPU 处理 | ≤ 300ms | ≤ 200ms | `PhotoProcessorImpl` 耗时 | ___ ms | ☐ |
| 4K GPU 处理 | ≤ 800ms | ≤ 600ms | `PhotoProcessorImpl` 耗时 | ___ ms | ☐ |

**日志关键字**:
```
PhotoProcessorImpl: processPhoto completed in ___ ms
```

### 3.5 相册滚动

| 指标 | 红线 | 目标 | 测量方法 | 实测值 | 状态 |
|------|------|------|----------|--------|------|
| 1000+ 照片滑动帧率 | ≥ 120fps | ≥ 120fps | GPU Profile | ___ fps | ☐ |
| 快速滑动停止后首图清晰 | ≤ 100ms | ≤ 50ms | 人工体感 | ___ ms | ☐ |

### 3.6 美颜引擎资源占用

| 指标 | 红线 | 目标 | 测量方法 | 实测值 | 状态 |
|------|------|------|----------|--------|------|
| 内存占用 | ≤ 30MB | ≤ 25MB | Android Profiler | ___ MB | ☐ |
| CPU 占用（美颜模块） | ≤ 15% | ≤ 10% | Android Profiler | ___ % | ☐ |

---

## 6. 稳定性测试

### 4.1 崩溃率监控

| 指标 | 红线 | 目标 | 监控方式 | 近 7 天数据 | 状态 |
|------|------|------|----------|-----------|------|
| 整体崩溃率 | ≤ 0.1% | ≤ 0.05% | Crashlytics / 友盟 | ___ % | ☐ |
| 美颜引擎崩溃率 | ≤ 0.05% | ≤ 0.01% | Crashlytics | ___ % | ☐ |
| ANR 率 | ≤ 0.1% | ≤ 0.05% | Google Play Console | ___ % | ☐ |

### 4.2 降级恢复测试

| 测试项 | 步骤 | 预期结果 | 状态 |
|--------|------|----------|------|
| EGL 初始化失败降级 | 模拟 EGL 初始化失败 | ✅ 降级到基础预览，不崩溃 | ☐ |
| 冷却恢复成功率 | 触发降级后等待 30s | ✅ ≥ 95% 成功率恢复 | ☐ |
| 降级耗时 | 记录降级触发到完成时间 | ✅ ≤ 2s | ☐ |
| 拍照 fallback | GPU 路径失败时 | ✅ 100% 回退 CPU，不丢照片 | ☐ |

**模拟方法**:
```kotlin
// 在测试代码中注入异常
object TestInject {
    var forceEglFailure = false
}
```

### 4.3 长时间运行测试

| 测试项 | 步骤 | 预期结果 | 状态 |
|--------|------|----------|------|
| 连续拍照 100 张 | 连续拍照，不退出应用 | ✅ 无崩溃，内存稳定 | ☐ |
| 连续录像 30 分钟 | 持续录像 30 分钟 | ✅ 无崩溃，发热可控 | ☐ |
| 美颜开关 50 次 | 频繁开关美颜 | ✅ 无泄漏，响应正常 | ☐ |

**监控指标**:
- 内存曲线（Android Profiler）
- CPU 温度（`adb shell dumpsys battery`）
- 帧率稳定性（调试浮层）

---

## 7. 兼容性测试

### 5.1 设备覆盖率

| 设备类型 | Android 版本 | 测试项 | 状态 |
|---------|-------------|--------|------|
| 高端机（Flagship） | Android 13+ | 全部功能 | ☐ |
| 中端机（Mid-range） | Android 11+ | 全部功能 | ☐ |
| 低端机（Budget） | Android 10+ | 基础功能 | ☐ |
| 折叠屏 | Android 12+ | 页面适配 | ☐ |

### 5.2 分辨率适配

| 分辨率 | 设备示例 | 测试项 | 状态 |
|--------|---------|--------|------|
| 720p | 入门机型 | 预览清晰度 | ☐ |
| 1080p | 主流机型 | 美颜精度 | ☐ |
| 2K+ | 旗舰机型 | UI 渲染质量 | ☐ |

---

## 8. 隐私与安全验证

### 6.1 权限检查

| 检查项 | 验证方法 | 预期结果 | 状态 |
|--------|----------|----------|------|
| 网络权限 | `AndroidManifest.xml` 扫描 | INTERNET 仅用于远程编排（非敏感指令） | ☐ |
| 人脸数据上传 | 网络抓包 | 敏感数据未经用户授权不上云；授权后仅传输必要元数据 | ☐ |
| 本地存储 | 文件浏览器 | 照片仅存本地 | ☐ |

**执行命令**:
```bash
aapt dump badging app-release.apk | grep uses-permission
tcpdump -i any port 443 -w capture.pcap  # 抓包验证
```

### 6.2 离线可用性

| 测试项 | 步骤 | 预期结果 | 状态 |
|--------|------|----------|------|
| 无网络拍照 | 关闭 WiFi/移动数据，拍照 | ✅ 核心功能可用 | ☐ |
| 无网络美颜 | 关闭网络，开启美颜 | ✅ 端侧 AI 正常工作 | ☐ |
| 模型未下载引导 | 首次安装无模型，调用 AI | ✅ 明确引导用户下载 | ☐ |

---

## 附录：自动化测试脚本

### ADB 命令速查

```bash
# 启动性能测试
adb shell am start -W com.mamba.picme/com.mamba.picme.ui.MainActivity

# 开启调试浮层
adb shell setprop picme.debug.perf true

# 清除应用数据
adb shell pm clear com.mamba.picme

# 查看日志
adb logcat -s PicMe:Agent PicMe:BeautyEngine PicMe:*

# 截图
adb shell screencap -p /sdcard/screenshot.png
adb pull /sdcard/screenshot.png
```

### 性能测试脚本

```bash
#!/bin/bash
# perf_test.sh

# 冷启动测试
START_TIME=$(adb shell am start -W com.mamba.picme/com.mamba.picme.ui.MainActivity | grep mTotalTime | awk -F= '{print $2}' | awk '{print $1}')
echo "Cold startup: ${START_TIME}ms"

# 帧率测试
adb shell dumpsys SurfaceFlinger --list | grep CameraPreview | while read surface; do
    adb shell dumpsys SurfaceFlinger --stats $surface
done

# 内存测试
adb shell dumpsys meminfo com.mamba.picme | grep Total PSS
```

## 9. 基于 Agent 命令的自动化测试

### 1. 现状分析：广播测试方案的痛点

#### 1.1 当前方案架构

当前自动化测试通过 `adb shell am broadcast` 发送命令到设备端广播接收器，由 `CameraTestCommandReceiver` 接收后分发给 `CameraTestCommandDispatcher`，最终触发 CameraScreen 的回调执行。

```
测试脚本 (PC) → adb broadcast → CameraTestCommandReceiver → CameraTestCommandDispatcher → CameraScreen
```

#### 1.2 核心痛点

| 痛点 | 具体表现 | 影响 |
|------|----------|------|
| **语义丢失** | 广播仅传递字符串 action，参数类型不安全 | 拼写错误、类型不匹配导致命令静默失败 |
| **无执行反馈** | 广播是"发射后不管"，无法确认命令是否被消费 | 测试脚本需轮询 logcat 猜测执行状态 |
| **场景感知缺失** | 广播不了解当前页面状态，命令可能发送到错误页面 | "拍照"命令在相册页面被丢弃，测试脚本不知情 |
| **状态断言困难** | 无法直接查询应用内部状态，依赖截图+图像识别 | 脆弱、慢、维护成本高 |
| **跨页面编排弱** | 多步骤测试需手动管理页面切换和等待 | 脚本复杂、flaky 率高 |
| **类型安全缺失** | 参数通过 Bundle 传递，无编译期检查 | `smooth=80.5` 传入 Int 字段被截断 |

#### 1.3 与 Agent 命令体系的对比

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

### 2. 设计方案：Agent-First 自动化测试架构

#### 2.1 核心设计原则

1. **复用而非重建**: 测试直接复用 `AgentCommand` + `CapabilityRegistry` + `ExecutionEngine`，不引入新的命令体系
2. **确定性优先**: 每个测试步骤都有明确的执行结果和状态断言，拒绝"猜测式"验证
3. **自描述测试**: 测试用例本身是可被 LLM 理解的结构化数据，支持 AI 自动生成和诊断
4. **分层隔离**: 测试编排层、命令执行层、状态验证层、报告输出层完全解耦

#### 2.2 架构分层

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

#### 2.3 关键组件设计

##### 2.3.1 双模式输入设计（核心）

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

##### 2.3.2 AgentTestOrchestrator（新增）

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

##### 2.3.3 AgentStateProbe（新增）

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

### 3. 测试用例 DSL 与 Agent 命令映射

#### 3.1 测试用例的双模式编写

##### 模式 B: 格式化指令（P0 回归首选）—— 纯数据驱动

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

##### 模式 A: 自然语言指令（探索性测试）

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

#### 3.2 完整测试用例示例（模式 B: 格式化指令）

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

#### 3.3 新增断言类型

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

### 4. 状态断言与验证机制

#### 4.1 三层验证模型

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

#### 4.2 状态探针实现策略

状态探针不直接访问 UI 组件，而是通过以下渠道获取状态：

| 状态来源 | 获取方式 | 用途 |
|----------|----------|------|
| `SceneManager.currentScene` | 直接读取 StateFlow | 验证当前页面 |
| `Capability.isAvailable()` | 调用接口 | 验证页面是否就绪 |
| `CameraTestCommandDispatcher.currentState` | 读取快照 | 验证相机参数 |
| `ExecutionEngine.stateFlow` | 收集 StateFlow | 验证计划执行状态 |
| `AgentTestFramework.eventFlow` | 收集事件流 | 验证测试事件序列 |
| 结构化日志 | logcat 过滤 | 验证业务事件 |

#### 4.3 截屏的定位转变

截屏从"主要验证手段"降级为"辅助诊断证据"：

- **不再用于**: 判断按钮是否存在、判断是否黑屏、识别文字内容
- **仅用于**: 失败时保留现场、人工复核时的视觉参考、UI 回归对比基线

---

### 5. 测试报告与失败诊断

#### 5.1 报告结构

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

#### 5.2 自动诊断规则

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

### 6. 迁移路径

#### 6.1 阶段一：桥接共存（1-2 周）

- 新增 `AgentTestCommandBridge`，将 `DeviceTestController` 的操作桥接到 `AgentCommand`
- 保留现有广播测试，新增 Agent 命令测试用例并行运行
- 验证 Agent 命令的可靠性和反馈完整性

#### 6.2 阶段二：逐步替换（2-3 周）

- 将核心 P0 用例从广播命令迁移到 Agent 命令
- 新增 `AgentStateProbe` 和诊断能力
- 截屏从主要验证降级为辅助诊断

#### 6.3 阶段三：全面切换（1 周）

- 移除 `CameraTestCommandReceiver` 和相关广播代码
- `DeviceTestController` 仅保留设备级操作（截屏、日志、性能）
- 所有测试用例通过 `AgentCommand` 执行

#### 6.4 废弃代码清单

| 文件 | 操作 | 替代方案 |
|------|------|----------|
| `CameraTestCommandReceiver.kt` | 删除 | `CapabilityRegistry.dispatch(AgentCommand, context)` |
| `CameraTestCommandDispatcher` 中的广播解析 | 删除 | `CapabilityRegistry.dispatch()` |
| `AgentTestBroadcastReceiver.kt` | 删除 | 直接调用 `AgentTestOrchestrator` |
| `DeviceTestController.dispatchCommand()` | 重构 | 直接构造 `AgentCommand` 并 dispatch |

---

### 7. 与现有 Agent 运行时的协同

#### 7.1 测试与生产的命令一致性

```
用户语音输入 ──→ AgentOrchestrator ──→ CapabilityRegistry ──→ Capability.execute()
                                                        ↑
测试脚本输入 ──→ AgentTestOrchestrator ───────────────────┘
```

测试和生产使用完全相同的命令分发路径，确保：
- 测试覆盖的就是用户实际触发的代码路径
- 新 Capability 注册后自动可被测试使用
- LLM 生成的命令可直接用于测试用例

#### 7.2 测试专用 Capability（可选扩展）

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

### 8. 收益总结

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

### 9. 交付审计清单

- [ ] `AgentTestOrchestrator` 双模式输入实现完成（自然语言 + 格式化指令）
- [ ] `AgentStateProbe` 实现完成，支持场景/Capability/状态查询
- [ ] 新增断言类型 (`assertCommandSuccess`, `assertScene`, `assertPerformance`)
- [ ] 至少 3 个 P0 用例完成 Agent 命令迁移并验证通过（模式 B）
- [ ] 至少 1 个探索性测试用例验证自然语言模式（模式 A）
- [ ] 失败自动诊断引擎实现，覆盖常见失败模式
- [ ] 测试报告 JSON 格式更新，包含 `agentActions` 和 `diagnosis` 字段
- [ ] 废弃广播相关代码：`CameraTestCommandReceiver`, `AgentTestBroadcastReceiver`
- [ ] 文档同步：本文档「9. 基于 Agent 命令的自动化测试」章节

---

> **维护者**: RD Agent
> **评审者**: CR Agent, QA Agent
> **实验状态**: 设计中 · 待评审

## 10. Accessibility UI Driver 使用

### 1. 前置条件

| 项目 | 要求 |
|------|------|
| 代码分支 | `main`（包含 `app/src/debug/java/com/mamba/picme/testing/accessibility/`） |
| 构建工具 | Android Studio 或 `./gradlew` |
| Python | 3.8+（macOS/Linux 通常自带） |
| adb | 已配置并在 PATH 中 |
| 设备 | Android 真机或模拟器，已开启开发者选项和 USB 调试 |

---

### 2. 编译并安装 Debug APK

在工程根目录执行：

```bash
./gradlew :app:installDebug --no-daemon
```

成功后会看到 `BUILD SUCCESSFUL`，APK 会安装到当前连接的设备。

验证安装：

```bash
adb shell pm list packages | grep com.mamba.picme
```

应输出：

```
package:com.mamba.picme
```

---

### 3. 启用 PicMeAccessibilityService

AccessibilityService 不会自动启用，需要手动在系统设置中打开，或通过 adb 命令开启。

#### 方式 A：通过系统设置（推荐首次使用）

1. 打开设备的 **设置 → 无障碍 → 已安装的服务**（路径可能因厂商而异）
2. 找到 **PoLang Accessibility Service**
3. 打开开关
4. 在弹出的权限确认对话框中点击 **允许**

#### 方式 B：通过 adb（适合自动化脚本）

```bash
adb shell settings put secure enabled_accessibility_services com.mamba.picme/.accessibility.PicMeAccessibilityService
```

验证是否启用：

```bash
adb shell settings get secure enabled_accessibility_services
```

应包含：

```
com.mamba.picme/.accessibility.PicMeAccessibilityService
```

> 注意：部分国产 ROM 可能限制通过 adb 开启无障碍服务，若命令执行后未生效，请使用方式 A。

---

### 4. 启动 PoLang 应用

```bash
adb shell am start -n com.mamba.picme/.MainActivity
```

等待应用进入主界面（相册网格）。

---

### 5. 建立 PC ↔ 设备的通信通道

AccessibilityService 在设备本地监听 `127.0.0.1:27183`。通过 adb forward 将该端口映射到开发机：

```bash
adb forward tcp:27183 tcp:27183
```

验证端口是否已转发：

```bash
adb forward --list
```

应输出类似：

```
<device-serial> tcp:27183 tcp:27183
```

> 每次重新插拔设备或重启 adb server 后，都需要重新执行 `adb forward`。

---

### 6. 使用 ui_driver.py 手动操作

`scripts/ui_driver.py` 是 PC 端的 Python 客户端，通过 JSON-RPC 与设备上的 AccessibilityService 通信。

#### 6.1 查看帮助

```bash
python3 scripts/ui_driver.py --help
```

#### 6.2 获取当前界面结构（dump）

```bash
python3 scripts/ui_driver.py dump
```

输出示例（节选）：

```text
[android.widget.FrameLayout] android.widget.FrameLayout bounds=(0,0,1200,2670)
  [android.view.View] android.view.View bounds=(0,0,1200,2670)
    ...
    [android.view.View] android.view.View clickable, bounds=(719,166,875,322)
      [android.view.View] 搜索照片 bounds=(758,205,836,283)
      [android.widget.Button] android.widget.Button bounds=(732,179,862,309)
```

每一行格式：

```text
[ClassName] text_or_contentDescription bounds=(left,top,right,bottom) [flags]
```

#### 6.3 点击元素

支持通过 `text`、`content_description` 或 `bounds` 定位元素。

```bash
# 通过 contentDescription 点击搜索按钮
python3 scripts/ui_driver.py click --content-description "搜索照片"

# 通过文本点击（会匹配包含该文本的节点）
python3 scripts/ui_driver.py click --text "2026-07-02"

# 通过 bounds 点击（需要传入 JSON）
python3 scripts/ui_driver.py click --bounds '{"left":758,"top":205,"right":836,"bottom":283}'
```

若目标节点本身不可点击，客户端会自动向上查找到可点击的父节点再执行点击。

#### 6.4 长按

```bash
python3 scripts/ui_driver.py long-click --content-description "搜索照片"
```

#### 6.5 输入文本

PoLang 的搜索框提示文字在 `EditText` 的子 `TextView` 上，因此直接通过 `--text` 定位不到 `EditText`。推荐先用 `dump` 获取搜索框的 bounds，再用 `--bounds` 输入：

```bash
# 先点击搜索进入搜索模式
python3 scripts/ui_driver.py click --content-description "搜索照片"

# 通过 bounds 在搜索框输入文本（bounds 从 dump 输出中复制）
python3 scripts/ui_driver.py input \
  --bounds '{"left":325,"top":166,"right":1135,"bottom":322}' \
  --value "猫"
```

> `input` 命令会聚焦目标节点并通过 `ACTION_SET_TEXT` 设置文本。它**不会**向上查找可点击父节点，因此请精确指向 `EditText` 本身。

#### 6.6 返回键

```bash
python3 scripts/ui_driver.py back
```

#### 6.7 滑动

```bash
python3 scripts/ui_driver.py swipe \
  --start-x 600 --start-y 2000 \
  --end-x 600 --end-y 500 \
  --duration 300
```

#### 6.8 查找节点

```bash
python3 scripts/ui_driver.py find --content-description "关闭搜索"
```

输出为 JSON 数组，包含匹配节点的完整信息。

---

### 7. 运行集成验证脚本

`scripts/verify_ui_driver.py` 是一个端到端验证脚本，会自动：

1. 如果当前在搜索模式，先按返回键回到主界面
2. dump 主界面
3. 点击「搜索照片」
4. dump 搜索界面
5. 验证是否出现「关闭搜索」，确认进入搜索模式

执行：

```bash
python3 scripts/verify_ui_driver.py
```

成功输出：

```text
✅ Integration test passed: search mode entered
```

失败会输出：

```text
❌ Integration test failed: search mode not detected after click
```

并返回非零退出码。

---

### 8. 完整手动测试流程示例

```bash
# 1. 安装 debug APK
./gradlew :app:installDebug --no-daemon

# 2. 启用 AccessibilityService
adb shell settings put secure enabled_accessibility_services com.mamba.picme/.accessibility.PicMeAccessibilityService

# 3. 启动应用
adb shell am start -n com.mamba.picme/.MainActivity

# 4. 等待应用启动
sleep 3

# 5. 建立端口转发
adb forward tcp:27183 tcp:27183

# 6. 查看界面
python3 scripts/ui_driver.py dump

# 7. 执行集成验证
python3 scripts/verify_ui_driver.py
```

---

### 9. 常见问题

#### 9.1 `ConnectionRefusedError` 或 `RPC error`

可能原因：

- AccessibilityService 未启用 → 按第 3 步重新启用
- adb forward 未建立 → 重新执行 `adb forward tcp:27183 tcp:27183`
- 应用被杀死 → 重新启动 PoLang
- 服务尚未初始化完成 → 等待 2-3 秒后重试

#### 9.2 点击成功但界面没有反应

- 检查目标节点是否真实可交互（有些 View 只是装饰性容器）
- 尝试使用更上层的 `contentDescription` 定位，或直接使用 `bounds`
- 某些自定义 View 可能不响应 Accessibility 点击，可改用 `swipe` 或 `input`

#### 9.3 dump 输出为空或只有 FrameLayout

- 应用可能不在前台 → 重新 `adb shell am start -n com.mamba.picme/.MainActivity`
- 当前窗口可能是系统弹窗/悬浮窗 → 关闭弹窗后再 dump

#### 9.4 通过 adb 启用 AccessibilityService 后未生效

部分厂商 ROM 会拦截该设置，请改用系统设置界面手动开启。

#### 9.5 脚本执行时提示找不到 `ui_driver` 模块

确保在工程根目录执行脚本，或设置正确的 `PYTHONPATH`：

```bash
export PYTHONPATH="$(pwd)/scripts:$PYTHONPATH"
python3 scripts/verify_ui_driver.py
```

---

### 10. 安全与隐私提示

- AccessibilityService 拥有较高的系统权限，仅用于 Debug 构建的自动化测试
- Release 构建不会包含该服务
- 不要在生产环境或他人设备上随意开启此服务

---

### 11. 下一步

验证通过后，你可以：

- 基于 `scripts/ui_driver.py` 编写更复杂的自动化测试用例
- 将 `dump` 结果直接喂给 LLM，让 AI 基于结构化文本描述进行 UI 理解和操作决策
- 探索 Phase 2：在 App 进程内直接消费 UI 结构化数据并本地调用大模型（无需经过 PC）

## 11. 核心功能测试指引

### 一、环境准备

| 项目 | 要求 |
|------|------|
| Android 设备 | 真机（arm64-v8a），API 24+ |
| 内存 | 8GB+ 推荐（Qwen3.5-2B 端侧推理需要） |
| 存储 | 5GB+ 可用空间（模型约 3GB + 照片库） |
| 安装方式 | `adb install -r app/build/outputs/apk/debug/picme-debug.apk` |
| API Key | 需配置 OpenAI 兼容 API（DeepSeek / 通义千问等） |

---

### 二、需要授予的权限

首次启动时需依次授权，建议在系统设置中手动确认：

| 权限 | 用途 | 必须 |
|------|------|:---:|
| `CAMERA` | 拍照、实时人脸检测 | ✅ |
| `POST_NOTIFICATIONS` | 模型下载进度、TAG 扫描通知 | ✅ |
| `READ_MEDIA_IMAGES` | 读取相册已有照片 | ✅ |
| `RECORD_AUDIO` | 语音输入 | ⭕ |
| `SYSTEM_ALERT_WINDOW` | 悬浮聊天气泡 | ⭕ |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 后台扫描不中断 | ⭕ |

> **TAG 扫描需后台持续运行**：建议在系统设置中将 PoLang 加入电池优化白名单。

---

### 三、必须下载的模型

进入 **`设置 → 模型中心`**，确保以下标记为 **"必须"** 的模型已下载：

| 模型 ID | 名称 | 用途 | 大小（约） |
|------|------|------|:---:|
| `qwen3_5_2b` | Qwen3.5-2B | 端侧图像理解与标签生成 | ~2.5GB |
| `sherpa-onnx-zipformer-zh-en` | Sherpa-ONNX Zipformer | 流式语音识别（语音输入） | ~280MB |
| `sherpa-onnx-kws-zipformer-wenetspeech` | Sherpa-ONNX KWS | 唤醒词检测 | ~14MB |
| `face-det-retina500m-mnn` | MNN ROI (Det500M) | 默认高精度人脸检测 | ~5MB |
| `face-landmark-2d106-mnn` | MNN 2D106 | 106 点人脸关键点定位 | ~1MB |
| `face-embedding-glint360k-r100-mnn` | Glint360K R100 | 人脸特征向量提取（聚类/识别） | ~248MB |
| `mobileclip-onnx` | MobileCLIP-S2 | 图像/文本语义编码（语义搜索/相册打标） | ~397MB |
| `opus-mt-zh-en` | OPUS-MT | 中文→英文翻译（语义搜索查询翻译） | ~70MB |

> 模型中心顶部 Tag 按"**人脸检测**"和"**对话**"排序，便于快速找到必须模型。

---

### 四、核心页面与功能

#### 4.1 导航结构

```
┌──────────────────────────────────────┐
│  Chat（对话）  ←→  Camera（拍照）    │
│  Gallery（相册） ←→ Settings（设置）  │
└──────────────────────────────────────┘
```

| 页面 | 路由 | 入口 |
|------|------|------|
| Camera（拍照） | `camera` | 底部 Tab 第二个 |
| Gallery（相册） | `gallery` | 底部 Tab 第三个 |
| Chat（对话） | `chat` | 底部 Tab 第一个 |
| Settings（设置） | `settings` | Chat / Gallery 右上角齿轮图标 |
| Model Center | `model_center/{tag}` | 设置页内部 |
| TAG 控制页 | `tag_control` | 相册页内部入口 |

---

#### 4.2 拍照页（Camera）

| 操作 | 验证点 |
|------|--------|
| 点击快门按钮 | 照片保存成功，有触觉反馈 |
| 切换前后摄像头 | 预览画面正常切换，无黑屏 |
| 美颜参数调节 | 滑块实时生效：平滑、美白、瘦脸等 |
| 连续拍照 | 5-10 张无卡顿、无 OOM |

---

#### 4.3 相册页（Gallery）

| 操作 | 验证点 |
|------|--------|
| 照片网格加载 | 缩略图正常显示，滚动流畅 |
| 点击照片 | 进入大图预览，含人脸关键点标注 |
| 按人物分组 | PERSON 模式下正确显示各人物簇 |
| 搜索照片 | 输入标签关键词可过滤照片 |
| TAG 控制入口 | 点击入口进入标签生成控制页 |

---

#### 4.4 标签生成（TAG 控制页）

##### 控制页布局

```
┌──────────────────────────────────┐
│     扫描进度卡片                  │
│     数据库统计卡片                │
│     ├ 总照片 / 含人脸 / 有标签 / 人物簇 │
│     └ MFNet 模型状态              │
│                                 │
│     3-Pass 管道概览              │
│                                 │
│ ┌──────────────────────────┐     │
│ │ 全量 3-Pass 扫描         │     │
│ ├──────────────────────────┤     │
│ │ Pass 1：人脸检测          │     │
│ │ Pass 2：DBSCAN 聚类      │     │
│ │ Pass 3：Qwen 标签        │     │
│ │ Pass 3：重新生成标签      │     │
│ │ ─────────────────────── │     │
│ │ 增量扫描                 │     │
│ │ 取消扫描                 │     │
│ └──────────────────────────┘     │
└──────────────────────────────────┘
```

##### 按钮功能说明

| 按钮 | 功能 | 前置条件 |
|------|------|----------|
| **全量 3-Pass 扫描** | 顺序执行 P1→P2→P3 | 5 个必须模型已下载 |
| **Pass 1：人脸检测** | 仅执行人脸检测 + Embedding 提取 | MNN ROI + 2D106 + Glint360K R100 |
| **Pass 2：DBSCAN 聚类** | 基于已有 embedding 重新聚类 | Pass 1 已执行过 |
| **Pass 3：Qwen 标签** | 对无标签照片生成标签 | Pass 1 已执行过，Qwen 模型已下载 |
| **Pass 3：重新生成标签** | 清空全部标签后全量重标 | Qwen 模型已下载 |
| **增量扫描** | 仅处理新增/未标记照片 | 已有扫描基础 |
| **取消扫描** | 中断当前扫描任务 | 扫描进行中 |

##### 预期结果（以 10 张含人脸照片为例）

| Pass | 耗时（骁龙8 Gen3） | 预期输出 |
|------|:---:|------|
| P1 | ~30s | `含人脸: 10`，`Embedding: ≥10` |
| P2 | ~2s | `人物簇: 2-5`（取决于面孔数量） |
| P3 | ~2min | `有标签: 10`，每张照片含场景/活动/标签 |

##### 重点验证

- [ ] P1 执行后"含人脸"数正确增长
- [ ] P2 执行后人物簇数与实际面孔数匹配（不同人不会误合并）
- [ ] P3 执行后每张照片可在相册中按标签搜索
- [ ] 锁屏/切换 App 后扫描持续进行（前台服务通知常驻）
- [ ] 取消扫描后状态正确停止
- [ ] Pass 3 重新生成后旧标签被替换

---

#### 4.5 Chat 对话页

| 操作 | 验证点 |
|------|--------|
| 发送文字消息 | AI 回复正常，有输入中动画 |
| 发送图片 | 拍照或从相册选择，AI 理解图片内容 |
| Markdown 渲染 | 代码块、表格、列表格式正确 |
| 模型切换 | 本地 Qwen / 云端模型正常切换 |
| 侧边栏 | 打开/关闭正常，历史对话记录 |
| 清空对话 | 对话列表正确清空 |

---

#### 4.6 模型中心

| 操作 | 验证点 |
|------|--------|
| Tag 排序 | "人脸检测"和"对话"排在前两位 |
| "必须"标签 | MNN 系列 + Qwen 显示红色"必须"徽章 |
| 下载模型 | 有下载进度通知，完成后状态更新 |
| 轻量版标签 | < 50MB 模型显示"轻量版"标签 |
| 切换 Tab | 不同分类模型列表正常切换 |

---

### 五、快速验证流程（约 10 分钟）

```
1. 安装 APK → 打开应用 → 授予全部权限

2. 设置 → 模型中心 → 优先下载 5 个"必须"模型
   等待下载完成（Qwen3.5-2B 约 2.5GB，需 Wi-Fi）

3. 拍照页 → 拍摄 3-5 张含人脸的样片（不同人物）

4. 相册页 → TAG 控制 → "全量 3-Pass 扫描"
   等待完成（约 2-5 分钟，首 Qwen 加载有额外开销）

5. 相册页 → 验证：
   ✓ 照片可正常浏览
   ✓ 搜索标签可过滤到对应照片
   ✓ PERSON 分组显示正确的人物簇

6. Chat 页 → 发送含图片的消息
   → 验证 AI 能正确理解图片内容并回复
```

---

### 六、常见问题

| 问题 | 排查步骤 |
|------|----------|
| Pass 1 结果为 0 | ① 检查 MNN ROI + 2D106 模型是否已下载<br>② 确认相机权限已授予 |
| Pass 2 人物簇不准确 | ① 检查 Glint360K R100 模型是否已下载<br>② 聚类阈值在 `ClusteringConfig.kt`（当前余弦相似度阈值 0.72） |
| Pass 3 标签为空 | ① 检查 Qwen3.5-2B 模型是否已下载<br>② 确认 API Key 配置正确<br>③ 确认设备内存足够（需 4GB+ 空闲） |
| 模型下载失败 | ① 检查网络连接<br>② 确认存储空间 > 5GB<br>③ 检查下载通知栏是否有错误提示 |
| 扫描中途停止 | ① 确认前台服务通知可见<br>② 检查电池优化白名单<br>③ 查看 logcat `PicMe:TagScheduler` 标签日志 |
| Qwen 推理太慢 | ① 当前为 CPU 推理，已禁用 OpenCL 回退<br>② 骁龙 8 Gen3 约为每张图 15-30s |

---

> **参考文档**:
> - [NFR_SPEC.md](../01-PRODUCT/NFR_SPEC.md) — 非功能性需求规格
> - [BEAUTY_ENGINE_TECH_SPEC.md](../03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md) — 大美丽引擎技术规范（含帧同步美妆系统）
> - [DEVELOPMENT.md](../05-DEVELOPMENT/DEVELOPMENT.md) — 开发规范（含代码审查检查清单）
