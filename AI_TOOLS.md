# PicMe AI 工具配置索引

> **用途**：速查本项目所有 AI 辅助工具的配置位置与规范来源。
> **维护**：新增 AI 工具或调整配置路径时，必须同步更新本文件。

---

## 工具配置速查表

| 工具 | 配置位置 | 读取范围 | 用途 / 状态 |
|------|----------|----------|-------------|
| **Claude Code** | `.claude/commands/*.md` + `.claude/CLAUDE.md` | 项目级 | 当前主力 AI 开发环境（命令目录与索引） |
| **Qoder** | `.qoder/skills/` + `.qoder/agents/` | 项目级 | 原主力 AI 开发环境（Skills / Agents），现与 `.claude/commands/` 并存 |
| **kimi-cli** | `.kimi/AGENTS.md` + `.kimi/skills/` | 项目级 | 终端交互式 AI 开发；`.kimi/skills` 为 `.qoder/skills` 符号链接 |
| **Cursor** | `.cursorrules` | 项目级 | Cursor IDE 规范与上下文 |
| **通用治理** | `AGENTS.md`（根目录） | 项目级 | 顶层治理、角色职责、全局红线 |

---

## 规范优先级（同目录内）

```
模块级 AGENTS.md   ← 最近层级优先
    ↓
根目录 AGENTS.md   ← 全局治理
    ↓
.kimi/AGENTS.md    ← kimi-cli 专用补充
    ↓
.cursorrules       ← 通用代码规范
```

> Claude Code 命令以 `.claude/commands/*.md` 为准；当命令与根 `AGENTS.md` 冲突时，以 `AGENTS.md` 顶层治理为准。

---

## Skills / Commands 来源

```text
.claude/commands/        ← Claude Code 唯一事实来源（当前主力）
.qoder/skills/           ← Qoder / kimi-cli Skills 事实来源
.openclaw/skills/        ← OpenClaw 读取（如配置）
.kimi/skills/            ← 符号链接 → ../.qoder/skills
```

**新增 Claude Code Command 的标准流程**：

```bash
# 1. 在 .claude/commands/ 创建命令文件
#    例如 .claude/commands/my-command.md

# 2. 更新索引
#    - .claude/CLAUDE.md
#    - 本文件（AI_TOOLS.md）如工具表需要
```

**新增 Skill（Qoder / kimi-cli 共用）的标准流程**：

```bash
# 1. 在唯一事实来源创建 Skill
mkdir -p .qoder/skills/my-skill
cat > .qoder/skills/my-skill/SKILL.md << 'EOF'
---
name: my-skill
---
EOF

# 2. 同步符号链接（如 .kimi/skills 尚未自动同步）
ln -sf ../../.qoder/skills/my-skill .kimi/skills/my-skill

# 3. 更新索引文档
# - .qoder/skills/README.md（如存在）
# - 本文件（AI_TOOLS.md）
```

---

## 当前可用 Claude Code Commands

完整列表与说明见 `.claude/CLAUDE.md`。常用命令：

| Command | 描述 |
|---------|------|
| `/android-build-debug` | Android 编译、安装、日志调试标准化流程 |
| `/error-healer` | Kotlin/Gradle 编译错误自动分类与修复策略 |
| `/auto-dev-loop` | 一键编译→安装→设备验证→质量检查闭环 |
| `/i18n-validator` | 多语言同步验证（中/英/繁），禁止硬编码字符串 |
| `/qa-acceptance` | QA 质量验收（端到端/边界/性能基线/红线） |
| `/doc-sync-guardian` | 三层文档体系一致性维护 |
| `/intent-router` | 意图路由：自然语言需求→技术任务 |
| `/mnn-llm-android` | MNN-LLM 端侧大模型推理（Qwen/下载/调试） |
| `/av-gl-expert` | OpenGL/CameraX 诊断（黑屏/Shader/EGL） |

---

## 当前可用 Qoder / kimi-cli Skills

| Skill | 描述 |
|-------|------|
| `accessibility-ui-driver` | PicMe UI 自动化（Accessibility 结构化文本驱动） |
| `adb-bot` | ADB 基础控制与调试 |
| `android-build-debug` | Android 编译、安装、日志调试 |
| `auto-dev-loop` | 编译→安装→验证→报告一键闭环 |
| `av-gl-expert` | 音视频与 OpenGL 渲染专家 |
| `compose-ui-expert` | Jetpack Compose UI 开发与性能优化 |
| `coordinate-system-standard` | 人脸关键点坐标系规范化 |
| `doc-sync-guardian` | 三层文档体系一致性检查 |
| `egl-state-machine` | EGL 上下文与离屏渲染状态机规范 |
| `error-healer` | Kotlin/Gradle 错误分类与自愈 |
| `i18n-validator` | 国际化资源检查与三语同步验证 |
| `image-quality-checker` | 截屏图片质量分析 |
| `intent-router` | 自然语言需求解析与上下文加载 |
| `mediapipe-landmark-mapping` | MediaPipe 关键点映射规范 |
| `mnn-integration` | MNN 推理引擎集成规范 |
| `mnn-landmark-diagnosis` | MNN 关键点诊断与调试 |
| `mnn-llm-android` | MNN-LLM 端侧大模型部署指南 |
| `ncnn-integration` | NCNN 推理引擎集成规范 |
| `onnx-model-integration` | ONNX 模型接入 Checklist |
| `perf-optimizer` | 性能分析与优化策略 |
| `qa-acceptance` | QA 验收测试流程与清单 |
| `rd-reflection` | RD 复盘模板 |

---

## 角色定义文件

| 角色 | 文件 | 说明 |
|------|------|------|
| [CO] 协调者 | `agents/co_agent.md` | 任务路由、状态管理、冲突仲裁 |
| [PM] 产品经理 | `agents/pm_agent.md` | 需求定义、UX 愿景、I18N 治理 |
| [RD] 全栈工程师 | `agents/rd_agent.md` | 技术实现、自愈修复、性能优化 |
| [CR] 规范审计 | `agents/review_agent.md` | 代码质量、架构合规、安全巡检 |
| [QA] 质量专家 | `agents/qa_agent.md` | 测试策略、边界测试、性能验收 |

> **快速参考**：`agents/README.md`

---

## 核心项目文档

| 文档 | 路径 | 用途 |
|------|------|------|
| 产品需求 | `PRODUCT.md` | 目标与约束 |
| 交互规范 | `docs/01-PRODUCT/FEATURES.md` | 交互与体验规则 |
| 开发工作流 | `docs/05-DEVELOPMENT/DEVELOPMENT.md` | Spec-Code 双螺旋演进、CR 规范 |
| 相册搜索 SSOT | `docs/03-TECHNICAL-SPECS/GALLERY_SEARCH.md` | 自然语言搜索完整链路 |
| TAG 生成 | `docs/03-TECHNICAL-SPECS/AUTO_TAG_GENERATION_SPEC.md` | 5-Pass 标签生成管道 |
| 美颜引擎 | `docs/03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md` | Shader 架构、多 Pass 渲染 |
| 人脸检测架构 | `docs/03-TECHNICAL-SPECS/FACE_DETECTION_ENGINE_ARCHITECTURE.md` | 多引擎 ROI + Landmark 设计 |
| 帧同步妆容 | `docs/03-TECHNICAL-SPECS/FRAME_SYNC_TECH_SPEC.md` | 时序对齐、甩飞问题根治 |
| 远程推理 | `docs/03-TECHNICAL-SPECS/REMOTE_INFERENCE_ARCHITECTURE.md` | 本地/远程协议分离、IntentCache |

---

## 快捷命令

```bash
# 构建调试版本
./gradlew :app:assembleDebug

# 运行单元测试
./gradlew :app:testDebugUnitTest

# 查看 PicMe 日志
adb logcat -s "PicMe:*"

# 启动 kimi-cli
cd ~/AndroidStudioProjects/langchain4android && kimi-cli chat

# 启动 Claude Code（如已安装）
cd ~/AndroidStudioProjects/langchain4android && claude
```

> 完整开发指南（环境配置、构建命令、调试技巧、发布流程）：`DEVELOPMENT.md`

---

## IDE 内置 AI 助手

| 助手 | 配置位置 | 状态 | 备注 |
|------|----------|------|------|
| **Android Studio Studio Bot** | `.idea/studiobot.xml` | ⚠️ 已启用上下文共享 | 当前配置为 `shareContext="OptedIn"`。本项目代码涉及人脸识别、图像处理等敏感算法，如需严格符合 `[PRIVACY]` 红线（100% 本地），建议改为 `OptedOut`。 |
| **Claude Code** | `.claude/` | ✅ 已配置 | 当前主力 AI 开发环境 |
| **Qoder** | `.qoder/` | ✅ 已配置 | 原主力环境，Skills 仍与 kimi-cli 共用 |
| **kimi-cli** | `.kimi/` | ✅ 已配置 | 终端交互式 AI 开发 |
| **Lingma（通义灵码）** | `.lingma/skills/` | ⚠️ 已停用 | 原 Skills 已迁移至 `.qoder/skills/`，不再维护 |

---

## 兼容性变更记录

| 日期 | 变更 | 影响 |
|------|------|------|
| 2026-05-03 | 创建 `.kimi/AGENTS.md` 与 `.kimi/skills/` | kimi-cli 获得独立项目配置入口 |
| 2026-05-03 | `.openclaw/skills/` 同步新增 skills 符号链接 | OpenClaw 可见完整 skills 列表 |
| 2026-05-03 | 删除断裂的 `shader-debug` 符号链接 | 消除 OpenClaw 加载错误 |
| 2026-05-03 | 精简 `agents/README.md` | 避免与根 `AGENTS.md` 重复 |
| 2026-05-03 | 修正 `scripts/kimi-cli.sh` APK 路径 | `picme-debug` → `app-debug`；项目路径 `PicMe` → `langchain4android` |
| 2026-05-03 | 新增 `DEVELOPMENT.md` | 通用开发命令从 OpenClaw 独占迁移为全平台共用 |
| 2026-05-03 | 新增 `AI_TOOLS.md` | 统一索引所有 AI 工具配置位置 |
| 2026-05-20 | **Skills 唯一事实来源迁移** | `.lingma/skills/` → `.qoder/skills/`，Qoder 成为主力开发环境 |
| 2026-05-20 | 新增 `mnn-landmark-diagnosis` | MNN 关键点 C++ 层性能诊断 Skill |
| 2026-05-25 | 新增 `compose-ui-expert` / `i18n-validator` / `perf-optimizer` | Jetpack Compose 专家、国际化验证、性能优化 |
| 2026-05-25 | 新增 `mnn-integration` / `ncnn-integration` / `mnn-llm-android` | 推理引擎集成规范完善 |
| 2026-05-28 | 新增 `qa-acceptance` | QA 验收流程 |
| 2026-07-02 | 测试 Skill 整合 | 删除 `agent-test-framework` 与 `ui-automation-expert`，合并到 `accessibility-ui-driver`；`adb-bot` 精简为通用 ADB 参考 |
| 2026-05-31 | 文档全面审计与更新 | 根目录文档与 wiki 一致性清理 |
| 2026-06-25 | Claude Code 命令整理 | `.qoder/skills/` → `.claude/commands/`，修复 19 个过期引用 |
| 2026-06-30 | AI 工具索引刷新 | 明确 Claude Code 命令为主力来源，更新 DEVELOPMENT.md / CLAUDE.md / 本文件 |
