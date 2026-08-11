<p align="center">
  <img src="https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white" alt="Platform">
  <img src="https://img.shields.io/badge/minSdk-24-3DDC84" alt="Min SDK">
  <img src="https://img.shields.io/badge/Kotlin-2.3.10-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin">
  <img src="https://img.shields.io/badge/Java-17-ED8B00?logo=openjdk&logoColor=white" alt="Java">
  <img src="https://img.shields.io/badge/License-MIT-blue.svg" alt="License">
</p>

<h1 align="center">PoLang（破浪相册）</h1>

<p align="center">
  <b>AI Agent 驱动的智能相册应用</b><br>
  <i>自然语言对话调度搜索 / 编辑 / 抠图 / 证件照 / 打标 · 端侧 VLM + 远程 OpenAI 兼容推理 · 自研 OpenGL ES 美颜引擎</i>
</p>

<p align="center">
  <a href="#polang-产品特性">特性</a> ·
  <a href="#polang-架构一览">架构</a> ·
  <a href="#运行-polang">运行</a> ·
  <a href="#agent-first-研发范式">Agent 范式</a>
</p>

---

## 概览

**PoLang（破浪相册）** —— 一个接近生产级复杂度的 **AI Agent 驱动的智能相册应用**，以相册首页为默认入口，通过自然语言对话调度搜索 / 编辑 / 抠图 / 证件照 / 标签等能力：文本推理全远程（DeepSeek / 通义等 OpenAI 兼容），端侧保留 VLM 打标 / 人脸 / 美颜等媒体处理，自研 OpenGL ES 美颜引擎，自建 Ktor 后端。

PoLang 的 Agent 编排层（`AgentOrchestrator`、`CapabilityRegistry`、`PrivacyGuard` 等）位于 `:shared` KMP 模块（commonMain 引擎无关层 + androidMain 平台实现），远程推理经 **Koog**（JetBrains KMP Agent 框架）编排——2026-08 由自维护的 langchain4j fork 迁移而来，`:agent-core` 模块已随之移除；原 `:runtime-core` 已于 Phase 4 整体迁入 `:shared` 后删除。先看 [PoLang 产品特性](#polang-产品特性)。

---

## PoLang 产品特性

PoLang 以「对话即操作」为核心：用户用自然语言与相册交互，Agent 把意图路由到端侧或云端能力执行。以下能力大多已落地（标 🔄 者开发中）。

### AI Agent 对话中枢
🤖 自然语言 → 能力调度：`AgentOrchestrator` + `CapabilityRegistry` + `PrivacyGuard` 完整架构，Capability 可热插拔。
- **文本推理全远程**：远程 DeepSeek / 通义等 OpenAI 兼容模型（ReAct + tool_calls），输入框下拉切换；端侧不再跑文本 LLM
- **多轮对话记忆**：Room 持久化，重启自动恢复
- **隐私分级**：`PrivacyGuard` 把媒体处理（人脸/OCR/打标/图片）100% 钉在端侧，仅文本/元数据走远程推理

### 智能相册搜索
🔍 「找出去年夏天的照片」「我和小明的合照」「最近一个月的视频」——规则解析 + MobileCLIP 语义召回 + 多维度 SQL 召回，**端侧执行**，结果可交互媒体网格。

### 对话式图片编辑
🎨 聊天里发图 + 指令（「磨皮再强一点」「换成冷色调」）→ 远程 ReAct（`edit_image`）解析为编辑 recipe 并执行 → 结果回渲染至对话，支持媒体结果轮播查看。

### 智能抠图 / 证件照
✂️ 三后端 + 路由器自动选择：**U2Netp**（通用抠图）/ **ModNet**（人像）/ **MediaPipe Selfie Segmentation**（自拍分割），由 `MattingRouter` 按场景路由；支持纯背景、换背景。
📇 **证件照制作**：`IDPhotoComposer` + `IDPhotoSpecs`，一寸/二寸/签证等多规格 + 背景色。

### 自动标签
🏷️ **端侧多模型打标**：Florence-2 INT8 + Qwen3-VL-2B（TAG Pass3），3-Pass 链路 + 中英双字段 + opus-mt 汉化；可由对话触发批量扫描。

### JS 沙盒脚本
📜 QuickJS 沙箱 + JSBridge，对话内运行相册分析 / 健康报告脚本（`run_gallery_script`），结果以图表 SVG / 结构化文本回显。

### 人物记忆与关系图谱 🔄
👥 事实记忆（「帮我记住…」）+ 人物命名 /「我」标记 + 关系图谱（配偶/子女/父母/…），支撑「我女儿的照片」「老婆的合照」式自然语言人物检索。（开发中，未合并 main）

### 自研美颜引擎
📷 全自研 **OpenGL ES + EGL** 渲染管线（磨皮/美白/瘦脸/大眼/唇色/腮红 + 风格滤镜），无第三方美颜 SDK；帧同步美妆解决快速移动妆容甩飞；GPU 离屏渲染保证预览/拍照一致。

### 语音交互
🎙️ 唤醒词（可选）+ 流式 ASR（Sherpa-ONNX 端侧）+ 远程 LLM 指令解析；语音入口位于相机页。

### 自建 Ktor 后端
🌐 独立 `server/` 工程（部署 `api.polang.net`）：AI 网关（按模型路由 Cloudflare AI Gateway / 腾讯 TokenHub）+ 邮箱注册账号 + 免费额度 + 管理后台 + 遥测。**不做 Agent 编排**（ReAct 循环在客户端）。

### 用户问题上报
📝 Chat 顶部「上报问题」入口 → `POST /v1/report-issue`，服务端脱敏后自动在 GitHub 仓库创建 issue，用户无需离开 App 即可反馈问题。

> **核心特点**：媒体处理端侧 · 隐私安全 ｜ Agent First 架构（Capability 可插拔）｜ 文本推理全远程 ｜ monorepo（androidApp / iosApp / shared(KMP) / engines(beauty-engine · beauty-api · mnn-core · agent-native · sentencepiece) + server）

---

## PoLang 架构一览

```mermaid
---
config:
  theme: base
  themeVariables:
    fontSize: 14px
---
flowchart TB
    subgraph CLIENTS["客户端层（双端）"]
        direction LR
        AA["<b>:androidApp</b> · Kotlin / Compose<br/>features/ UI + ViewModel<br/>AndroidAgentComposition（组合根）<br/>ChatToolService · CameraToolService<br/>RemoteControlToolService（飞书/TG）"]
        IOS["<b>iosApp/</b> · SwiftUI<br/>相机 · 相册 · Chat · 设置<br/>IosAgentComposition（组合根）<br/>ChatAgentBridge ── SKIE 消费 shared<br/>Metal 4-pass 美颜 · MNN/MediaPipe"]
        AA ~~~ IOS
    end

    subgraph SHARED[":shared · Agent 编排层（KMP：commonMain + androidMain + iosMain）"]
        ORCH["<b>commonMain（引擎无关）</b><br/>AgentOrchestrator → CapabilityRegistry → dispatch<br/>PrivacyGuard：媒体处理 100% 钉在端侧，仅文本 / 元数据上云"]
        CHAIN["Chat 链 streamChat → KoogReActAgent + ChatToolService<br/>Camera 链 processCameraInput → KoogReActAgent + CameraToolService<br/>MemoryManager ── 多轮对话记忆（持久化，重启恢复）"]
        PLAT["androidMain：LocalLlmEngine（端侧 VLM 打标）· 语音 · DataStore<br/>iosMain：ChatAgentBridge · IosChatGalleryCapability（端侧 VLM 仍 stub）"]
        ORCH --> CHAIN --> PLAT
    end

    KOOG["<b>Koog</b> · JetBrains KMP Agent 框架（外部依赖 · 双端共用）<br/>AIAgent · ToolRegistry · ChatMemory · poLangSingleRunStrategy<br/>OpenAILLMClient · PromptExecutor · EventHandler 流式 SSE"]

    SRV["<b>server/</b> · Ktor 后端（独立 Gradle 工程 · api.polang.net）<br/>AI 网关（Cloudflare AI Gateway / 腾讯 TokenHub）· 账号 · 免费额度<br/>管理后台 · 遥测 · /download（APK + iOS Ad-Hoc 分发）"]

    LLM["<b>远程 LLM</b> · DeepSeek / 通义 …（OpenAI 兼容 · tool_calls · 多轮 · 流式）<br/>⚠ 只收文本 / 元数据 —— 用户图片 / 视频文件绝不上传"]

    subgraph MEDIA["端侧媒体处理 · 100% 本地 · 不上云（由 Capability 在设备端调用）"]
        direction LR
        ME["<b>Android（Gradle 模块）</b><br/>:engines:beauty-engine OpenGL 美颜<br/>:engines:mnn-core MNN 推理 JNI<br/>:engines:agent-native VLM 打标 JNI<br/>:engines:sentencepiece tokenizer<br/>人脸 · 美颜 · OCR · 抠图/证件照 · 语义搜索 · 打标 · 聚类"]
        MI["<b>iOS（Framework / 原生管线）</b><br/>Metal 4-pass 美颜（相机实时）<br/>MNN.framework ── 人脸检测 · Florence-2<br/>MediaPipe ── 468 关键点（双源之一）<br/>TagDatabase（GRDB）── 打标 / 聚类<br/>端侧 VLM（Qwen3-VL）── 待接 stub"]
        ME ~~~ MI
    end

    AA --> ORCH
    IOS -->|SharedKit XCFramework| ORCH
    ORCH -->|"文本 / 指令（自然语言 → 意图编排）"| KOOG
    KOOG -->|HTTPS| SRV
    SRV --> LLM
    PLAT -.->|"媒体数据不出端"| MEDIA

    classDef client fill:#E8F0FE,stroke:#1A73E8,stroke-width:1.5px,color:#111
    classDef shared fill:#E6F4EA,stroke:#188038,stroke-width:1.5px,color:#111
    classDef infra fill:#FEF7E0,stroke:#F9AB00,stroke-width:1.5px,color:#111
    classDef remote fill:#FCE8E6,stroke:#D93025,stroke-width:1.5px,color:#111
    classDef media fill:#F3E8FD,stroke:#9334E6,stroke-width:1.5px,color:#111

    class AA,IOS client
    class ORCH,CHAIN,PLAT shared
    class KOOG,SRV infra
    class LLM remote
    class ME,MI media
```

> 注：`:shared` 五 target（android / jvm / iosX64 / iosArm64 / iosSimulatorArm64），iOS 经 SharedKit XCFramework 消费；iosApp 相机 / 相册 / Chat / 设置已落地，人物页聚类已对齐，端侧 VLM 打标与部分能力仍为 stub，按 [`specs/PARITY_MASTER_PLAN.md`](specs/PARITY_MASTER_PLAN.md) 逐屏追齐。图源文件：[`docs/assets/architecture.mmd`](docs/assets/architecture.mmd)（Mermaid，GitHub 原生渲染）。

### Chat 双模式架构

Chat 页输入栏的 **AI 工程师** toggle 在两条独立 LLM 链路间切换：

- **普通 Chat（相册助手）**：用户输入 → `ChatViewModel.sendMessage()` → `AgentOrchestrator` → 远程 DeepSeek / 通义等（OpenAI 兼容 ReAct）→ `@Tool` → `CapabilityRegistry` → 端侧 Capability 执行 → 结果渲染到对话。
- **AI 工程师（远程 coding agent）**：用户输入 → `ChatViewModel.sendClaudeMessage()` → `POST /v1/claude-chat` → chisel 反向隧道 → KimiClaw `Claude Code` → 读改代码 / MCP app tools 感知 App 状态 → 用户选择 `push/pr/auto` 交付 → `POST /v1/claude-deliver` → git 分支/PR/自动合并。

两条链路的目标、LLM、上下文、工具、隐私边界与交付物完全不同；详细架构图与对比见 [`docs/02-ARCHITECTURE/AGENT_ARCHITECTURE.md`](docs/02-ARCHITECTURE/AGENT_ARCHITECTURE.md) §2.5。

### 项目模块

| 模块 | 语言 | 说明 |
|------|------|------|
| `:androidApp` | Kotlin | **PoLang 应用** — Agent 编排层 + 智能相册 UI（Jetpack Compose） |
| `iosApp/` | Swift (SwiftUI) | **PoLang iOS 应用** — 相机（AVFoundation + Metal 4-pass 美颜）、相册、Chat（SharedKit `ChatAgentBridge` 流式推理 + tool_calls）、设置（含模型下载中心）；经 SharedKit XCFramework 消费 `:shared` |
| `:shared` | Kotlin (KMP) | **Agent 编排层** — AgentOrchestrator、CapabilityRegistry、PrivacyGuard、SceneManager、JS 沙盒引擎无关层（commonMain）+ VLM/语音/DataStore（androidMain）+ ChatAgentBridge/IosChatGalleryCapability（iosMain，端侧 VLM 仍 stub） |
| `:engines:agent-native` | C++ | VLM 打标 JNI 桥构建模块（`libagent_native.so`） |
| `:engines:beauty-api` | Kotlin | 美颜接口契约层 |
| `:engines:beauty-engine` | C++/Kotlin | 自研 GPU 美颜渲染引擎 |
| `:engines:mnn-core` | C++ | MNN 推理运行时共享库（`:shared`（androidMain）和 `:engines:beauty-engine` 共用） |
| `:engines:sentencepiece` | C++/JNI | SentencePiece tokenizer JNI 封装 |
| `server/` | Kotlin | **Ktor 后端**（独立 Gradle 工程）— AI 网关、账号体系、管理后台 |

---

## 运行 PoLang

```bash
git clone https://github.com/littleseven/polang.git
cd polang

# 构建 Demo APK
./gradlew :androidApp:assembleDebug

# 安装到设备
adb install -r androidApp/build/outputs/apk/debug/polang-debug.apk

# 一键开发闭环
./scripts/auto-dev-loop.sh
```

---

## 服务端：PoLang Server

`server/` 是独立的 Ktor 后端工程（`rootProject.name = "picme-server"`），**不纳入 Android `settings.gradle.kts`**，通过 `./gradlew -p server` 独立构建。

| 能力 | 说明 |
|------|------|
| **AI 网关** | `LlmProxy` + `ChannelRegistry` — 按模型自动路由到 Cloudflare AI Gateway 或腾讯 TokenHub |
| **账号体系** | 邮箱注册、动态 Token、SHA-256 校验、免费额度管控 |
| **管理后台** | kotlinx.html SSR 运营后台（概览 / 用户 / 流量 / 渠道配置） |
| **推荐引擎** | 纯规则型场景推荐（规避算法备案） |
| **遥测收集** | 批量匿名事件写入 SQLite |
| **COS 存储** | 腾讯 COS 预签名 URL 生成 |

```bash
# 本地开发
./gradlew -p server run

# 构建分发包
./gradlew -p server installDist
```

---

## 文档

| 层级 | 文档 | 内容 |
|------|------|------|
| **导航** | [`docs/00-INDEX.md`](docs/00-INDEX.md) | 完整文档导航索引 |
| **产品** | [`PRODUCT.md`](PRODUCT.md) | 产品定义、核心命题 |
| **架构** | [`docs/02-ARCHITECTURE/AGENT_ARCHITECTURE.md`](docs/02-ARCHITECTURE/AGENT_ARCHITECTURE.md) | Agent 架构设计 |
| **决策** | [`docs/02-ARCHITECTURE/ADR/`](docs/02-ARCHITECTURE/ADR/) | 架构决策记录（ADR-001 ~ ADR-012） |
| **技术规范** | [`docs/03-TECHNICAL-SPECS/`](docs/03-TECHNICAL-SPECS/) | 相册搜索、TAG 生成、美颜引擎（含帧同步）、人脸检测、语音栈、服务端部署 |
| **Agent 能力** | [`docs/04-AGENT-CAPABILITIES/`](docs/04-AGENT-CAPABILITIES/) | Capability 实现指南、命令参考 |
| **开发规范** | [`docs/05-DEVELOPMENT/`](docs/05-DEVELOPMENT/) | 工作流、CR 检查清单、Release 包备份恢复 |

---

## Agent First 研发范式

本项目同时验证「Agent 能否主导软件研发全流程」——让 Agent 通过编排原子化 Tools，从辅助工具进化为研发主导力量。

### 四项架构原则

| 原则 | 效果 |
|------|------|
| **显式优于隐式** | 构造函数即文档，Agent 无需跨文件搜索即可理解组件协作 |
| **枚举优于条件** | Sealed Class 枚举合法状态，Agent 可枚举全部边界情况 |
| **自描述优于注释** | 类型系统即契约，Agent 靠类型推导而非易腐烂的注释 |
| **结构化可观测性** | 结构化事件日志，Agent 可消费、可诊断 |

### 实践关键发现

- **文本推理全远程** — 端侧文本 LLM（Qwen3.5-2B）已移除以简化架构、降低功耗；文本对话 / 指令统一走远程 OpenAI 兼容 ReAct（tool_calls），相机指令亦改远程 tool_calls（`CameraToolService` + `AgentOrchestrator.processCameraInput`）
- **媒体处理 100% 端侧** — 打标（Florence-2 / Qwen3-VL-2B）、人脸检测、美颜、相册搜索、抠图均在端侧，仅文本 / 元数据上云（隐私红线，ADR-008）
- **多引擎资源隔离** — MNN/MediaPipe 共存时 OpenCL/EGL 资源竞争是隐形崩溃源（NCNN 路径已移除）
- **远程推理优先** — 文本对话与指令解析明确上云，端侧算力聚焦媒体处理（VLM 打标 / 人脸 / 美颜）

### 度量指标

> 以下度量为实验性目标，当前基线待重新采集，不以未经验证的数字作为项目承诺。

| 指标 | 说明 |
|------|------|
| Agent 生成代码占比 | 目标 > 80% |
| Self-Heal 成功率 | 目标 > 85% |
| 文档-代码一致性 | 目标 > 98% |
| 人工介入频次 | 目标 < 10% |

---

## 自动化工具链

| 脚本 | 功能 |
|------|------|
| [`auto-dev-loop.sh`](scripts/auto-dev-loop.sh) | 编译 -> 安装 -> 截屏 -> 日志 -> 报告 |
| [`ai-gate.sh`](scripts/ai-gate.sh) | 代码质量门禁 |
| [`screenshot-diff.py`](scripts/screenshot-diff.py) | UI 回归像素级对比 |

## 许可

MIT License — 研究、学习、二次开发均可自由使用。

---

<p align="center">
  <b>PoLang（破浪相册）</b> · AI Agent 驱动的智能相册
</p>
