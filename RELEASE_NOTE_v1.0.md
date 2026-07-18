# 破浪相册 PoLang v1.0 正式发布

> **发布日期**：2026-06-05
> **版本**：v1.0
> **包体积**：84MB（含端侧模型）
> **GitHub**：https://github.com/littleseven/PoLang

---

## 概述

PoLang（破浪相册）是一款探索「端侧 AI Agent 驱动应用」的智能相册。用户可以通过**自然语言**与照片交互——说「调高美颜」「帮我把天空调蓝」「找出去年夏天的照片」即可控制相册浏览、图片编辑和相机功能，无需手动调节复杂参数。

项目同时是一个**Agent First 工程试验场**，验证 Agent 作为研发流程第一公民的可行性，探索面向 Agent 的架构设计、协作机制与研发范式。

> 美颜相机是试验载体，真正的研究对象是**端侧 Agent 运行时架构**与**Agent First 工程范式**。

---

## 核心特性

### 自然语言交互

| 你说 | PoLang 做 |
|------|----------|
| 「拍张照」 | 立即拍摄并保存 |
| 「调高美颜」 | 平滑提升磨皮/美白强度 |
| 「换个冷调滤镜」 | 切换冷色调风格滤镜 |
| 「打开前置」 | 翻转至前置摄像头 |

- **端侧运行 Qwen3.5-2B 大模型**，100% 本地推理，隐私敏感数据零上传
- **Agent Runtime 模块化**：核心编排逻辑已迁移至 `:agent-core` 纯 Kotlin 模块，`app` 层通过 `AiAgentUseCase` Facade 桥接访问
- **:beauty-api 契约层**：纯 Kotlin 接口模块（`BeautyProcessor`、`PhotoProcessor`、`FaceDetector` 等），与 GPU 实现解耦
- **远程 LLM 编排**（可选）：配置个人 OpenAI/Claude 兼容 Token 解锁云端推理
- **Capability 系统**支持热插拔扩展，新增能力零侵入 Agent 核心
- **多轮对话上下文记忆**（MemoryManager），交互体验连贯自然
- **隐私分级守卫**（PrivacyGuard）：PUBLIC / SENSITIVE / RESTRICTED 三级自动分级，敏感指令强制本地执行
- **统一聊天界面**，Camera 与 Gallery 页面视觉一致，支持折叠/展开、语音输入
- **语音控制**（试验性）：唤醒词触发、语音指令识别，快速迭代验证中

### 自研实时美颜引擎

- **全自研 OpenGL ES + EGL 渲染管线**（无第三方美颜 SDK）
- 完整美颜链路：磨皮、美白、瘦脸、大眼、唇色、腮红、眉毛
- **GPU 离屏拍照**：预览与输出使用同一套 Shader，效果一致性 99%+
- **双引擎人脸检测**：MediaPipe 468→106（主）+ MNN/NCNN（备），InsightFace ONNX 路径已于 2026-05 完全移除
- **帧同步妆容系统**：解决检测帧率与渲染帧率不匹配导致的"妆容甩飞"问题

### 隐私优先

| 功能 | 运行位置 | 网络依赖 |
|------|----------|----------|
| 本地 LLM 推理 | 本地 MNN-LLM | 可离线 |
| 人脸检测 | 本地 MediaPipe / NCNN / MNN | 零云端推理 |
| OCR 文字识别 | 本地 ML Kit | 零云端推理 |
| 美颜渲染 | 本地 GPU | 零云端推理 |
| 远程复杂编排 | 云端 LLM API（可选） | 仅非敏感指令 |

PoLang 对隐私敏感数据采用**强制本地处理**策略；在远程模式下，仅非敏感指令允许走网络编排。

---

## 技术架构

```
┌──────────────────────────────────────────────────────────────┐
│  User Interface (Jetpack Compose)                             │
│  ├─ 相机预览 + Agent 对话面板                                  │
│  ├─ 实时美颜调节面板                                          │
│  └─ 相册浏览与静态图编辑                                       │
├──────────────────────────────────────────────────────────────┤
│  Agent Runtime (:agent-core) —— 端侧 Agent 中枢神经系统        │
│  ├─ AgentOrchestrator      意图解析与任务编排                  │
│  ├─ InferenceRouter        本地/远程推理路由（自适应策略）      │
│  ├─ LocalLlmEngine         Qwen3.5-2B / MNN-LLM 端侧推理      │
│  ├─ CapabilityRegistry     设备能力路由（自描述元数据）        │
│  ├─ MemoryManager          多轮对话上下文管理                  │
│  ├─ PrivacyGuard           隐私分级守卫（PUBLIC/SENSITIVE/     │
│  │                          RESTRICTED）                       │
│  └─ SceneManager           页面场景状态机（Camera/Gallery/     │
│                             Settings）                         │
│  App 桥接层: AiAgentUseCase (Facade) → AgentOrchestrator       │
├──────────────────────────────────────────────────────────────┤
│  Capability Layer（可插拔、自描述、热注册）                     │
│  ├─ CameraCapability       相机控制（拍摄/切换/参数）          │
│  ├─ BeautyCapability       美颜参数调节                        │
│  ├─ GalleryCapability      相册管理                            │
│  ├─ SettingsCapability     设置管理                            │
│  ├─ NavigationCapability   页面导航（支持跨页面指令队列）       │
│  └─ （新增 Capability 零侵入 Agent 核心）                      │
├──────────────────────────────────────────────────────────────┤
│  :beauty-api (纯 Kotlin 接口契约层)                           │
│  └─ BeautySettings / BeautyProcessor / PhotoProcessor / etc.  │
├──────────────────────────────────────────────────────────────┤
│  :beauty-engine (独立模块 · OpenGL ES + EGL)                   │
│  ├─ CameraPreviewRenderer  实时预览渲染                        │
│  ├─ BeautyRenderer         美颜 Shader 多 Pass 管线            │
│  ├─ PhotoProcessorImpl     GPU 离屏拍照处理                    │
│  ├─ FrameSyncManager       帧同步妆容系统                      │
│  └─ FaceDetectionEngine    MediaPipe + MNN/NCNN 双引擎         │
└──────────────────────────────────────────────────────────────┘
```

---

## 安装要求

- **Android 7.0+** (API 24+)
- **ARM64** 设备
- **包体积**：约 84MB（含端侧模型）

---

## 快速开始

```bash
# 克隆仓库
git clone https://github.com/littleseven/PoLang.git
cd PoLang

# 构建 Debug APK
./gradlew :app:assembleDebug

# 安装到设备
adb install -r app/build/outputs/apk/debug/polang-debug.apk
```

### 常用命令

| 命令 | 说明 |
|------|------|
| `./gradlew test` | 运行 JVM 单元测试 |
| `./gradlew connectedAndroidTest` | 运行仪器测试（需设备） |
| `./gradlew lint` | 静态代码检查 |
| `./scripts/ai-gate.sh` | 代码质量门禁 |
| `adb logcat -s "PoLang:*"` | 查看 PoLang 日志 |

---

## 文档索引

| 层级 | 文档 | 读者 | 内容 |
|------|------|------|------|
| **导航** | [`docs/00-INDEX.md`](docs/00-INDEX.md) | 全部 | 完整文档导航索引 |
| **产品层** | [`PRODUCT.md`](PRODUCT.md) | PM/RD/QA | 产品定义、核心命题、验收标准 |
| | [`docs/01-PRODUCT/FEATURES.md`](docs/01-PRODUCT/FEATURES.md) | PM/RD/QA | 交互流程与体验规则 |
| | [`docs/01-PRODUCT/NFR_SPEC.md`](docs/01-PRODUCT/NFR_SPEC.md) | QA/RD | 性能/稳定性/隐私量化指标 |
| **架构层** | [`docs/02-ARCHITECTURE/AGENT_ARCHITECTURE.md`](docs/02-ARCHITECTURE/AGENT_ARCHITECTURE.md) | RD/CO | Agent 运行时架构、Capability 模型 |
| | [`docs/02-ARCHITECTURE/ADR/`](docs/02-ARCHITECTURE/ADR/) | RD/CR | 架构决策记录 |
| **技术规范** | [`docs/03-TECHNICAL-SPECS/`](docs/03-TECHNICAL-SPECS/) | RD | 美颜引擎、帧同步、人脸检测、相机预览 |
| **Agent 能力** | [`docs/04-AGENT-CAPABILITIES/`](docs/04-AGENT-CAPABILITIES/) | RD/PM | Capability 注册表、命令参考、实现指南 |
| **开发规范** | [`docs/05-DEVELOPMENT/`](docs/05-DEVELOPMENT/) | RD/CO | 工作流、CR 检查清单、任务标记规范 |
| **质量标准** | [`docs/06-QA/`](docs/06-QA/) | QA | 验收测试清单 |
| **标准词典** | [`docs/07-STANDARDS/`](docs/07-STANDARDS/) | 全部 | 坐标系标准、统一术语词典 |
| **容灾** | [`docs/08-FALLBACK/`](docs/08-FALLBACK/) | RD/QA | 引擎降级策略与恢复机制 |

---

## Agent First 研发范式

PoLang 不仅是一款应用，更是对「Agent 能否主导软件研发全流程」的系统性验证。

### 三重实验维度

| 维度 | 目标 | 关键产出 |
|------|------|----------|
| 端侧 Agent 架构 | 验证 LLM 能否成为应用的中枢神经系统 | Agent Runtime、Capability 系统、对话式交互、语音控制 |
| Agent First 客户端框架 | 让 Agent 高效理解、修改、扩展代码 | 显式边界、声明式状态、自描述能力 |
| Agent First 研发流程 | Agent 主导协作，基础设施原子化为 Tools | 角色化协作、Self-Heal、即时验证 |

### 已验证的假设

| 假设 | 结论 |
|------|------|
| 显式架构可被 Agent 高效理解 | 成立 — RD Agent 成功实现跨模块功能 |
| 文档驱动开发减少沟通损耗 | 成立 — PRODUCT→FEATURES→AGENTS 链条有效 |
| Tools 化支持 Self-Heal 闭环 | 成立 — 编译/安装/验证全自动化 |
| Capability 系统支持热插拔 | 成立 — 新增能力零侵入 Agent 核心 |
| 端侧 LLM 可满足相册/编辑控制推理需求 | 成立 — Qwen3.5-2B 本地推理响应 < 500ms |
| 隐私分级守卫可有效拦截敏感数据 | 成立 — RESTRICTED/SENSITIVE 指令 100% 本地执行 |

---

## 许可

**MIT License** — 研究、学习、二次开发均可自由使用。

> 项目性质：技术研究项目，暂无商业化与收费计划。所有功能完全免费开放，无广告、无订阅、无内购。

---

<p align="center"><i>PoLang — 让相机听懂你说的话</i></p>
