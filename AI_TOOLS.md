# PoLang AI 工具配置索引

> **用途**：速查本项目所有 AI 辅助工具的配置位置与规范来源。
> **维护**：新增 AI 工具或调整配置路径时，必须同步更新本文件。

---

## 工具配置速查表

| 工具 | 配置位置 | 读取范围 | 用途 / 状态 |
|------|----------|----------|-------------|
| **Claude Code** | `.claude/commands/*.md` + `.claude/CLAUDE.md` | 项目级 | AI 开发环境之一·日常备选（命令目录与索引） |
| **kimi-cli** | `.kimi/AGENTS.md` + `.kimi/skills/` | 项目级 | 终端交互式 AI 开发·主力；`.kimi/skills` → `../skills` |
| **AndroidStudio Qwen 插件** | `AGENTS.md`（根目录） | 项目级 | IDE 内置助手，读取根 AGENTS.md 治理 |
| **通用治理** | `AGENTS.md`（根目录） | 项目级 | 顶层治理、架构原则、全局红线 |

---

## 规范优先级（同目录内）

```
模块级 AGENTS.md   ← 最近层级优先
    ↓
根目录 AGENTS.md   ← 全局治理
    ↓
.kimi/AGENTS.md    ← kimi-cli 专用补充
```

> Claude Code 命令以 `.claude/commands/*.md` 为准；当命令与根 `AGENTS.md` 冲突时，以 `AGENTS.md` 顶层治理为准。

---

## Skills / Commands 来源

```text
skills/                  ← ★ 唯一事实来源（SSOT）：kimi 通过软链共享
.kimi/skills/            ← 符号链接 → ../skills
.claude/commands/        ← Claude Code 专用镜像（见下方"漂移治理"）
```

> **Skills SSOT = `skills/`**。修改 Skill 时，改这里即可，kimi 自动生效（CC 需手动同步 `.claude/commands/`）。

**`.claude/commands/` 漂移治理**：Claude Code 命令格式（纯 Markdown，无 frontmatter）与 Skills（带 YAML frontmatter 的目录结构）不同，无法软链统一，已存在内容分叉。治理策略：
- 修改 Skill 后**必须手动同步**对应的 `.claude/commands/<name>.md`
- 运行 `./scripts/check-skill-sync.sh` 检查两侧命名是否齐全、内容是否一致
- 该脚本只**报告**漂移，不自动覆盖（避免丢失任一侧独有内容）

**新增 Skill 的标准流程**：

```bash
# 1. 在唯一事实来源创建 Skill
mkdir -p skills/my-skill
cat > skills/my-skill/SKILL.md << 'EOF'
---
name: my-skill
description: <触发场景描述>
---
EOF

# 2. 同步符号链接（.opencode/skills 和 .kimi/skills 通常已整体软链，无需单条操作）

# 3. Claude Code 镜像：从 SKILL.md 提取正文（去 frontmatter）写入
#    .claude/commands/my-skill.md

# 4. 运行校验
./scripts/check-skill-sync.sh

# 5. 更新索引文档（本文件 + skills/README.md 如存在）
```

---

## Plans / Specs 公共位置（★ SSOT）

```text
docs/superpowers/
├── README.md            ← SSOT 声明与命名规范（权威文档）
├── plans/               ← 所有工具的执行计划（work plans）
│   └── YYYY-MM-DD-<slug>.md
├── specs/               ← 所有工具的设计规格（design specs）
│   └── YYYY-MM-DD-<slug>-design.md
└── *.md                 ← 阶段汇总（summary / nightly）
```

> **Plan / Spec SSOT = `docs/superpowers/{plans,specs}/`**。四工具一律写这里，**禁止**写到工具私有目录。

**各工具默认位置的重定向**：

| 工具 | 默认位置 | 本项目处理 |
|------|----------|-----------|
| Claude Code（superpowers） | `~/.claude/plans/` | ❌ 禁用；生成后立即移到 `docs/superpowers/plans/` |
| OpenCode（ulw-plan / Momus） | `.omo/plans/` | ✅ 软链 `.omo/plans → ../docs/superpowers/plans`，自动落地 |
| Kimi / Qwen 插件 | 无固定 | 直接写 `docs/superpowers/{plans,specs}/` |

完整约定（命名规范、公共 vs 私有边界、判断准则）：见 [`docs/superpowers/README.md`](docs/superpowers/README.md)。

---

## 当前可用 Claude Code Commands

完整列表与说明见 `.claude/CLAUDE.md`。常用命令：

| Command | 描述 |
|---------|------|
| `/android-build-debug` | Android 编译、安装、日志调试标准化流程 |
| `/error-healer` | Kotlin/Gradle 编译错误自动分类与修复策略 |
| `/dev-loop` | 一键编译→安装→设备验证→质量检查闭环 |
| `/i18n-validator` | 多语言同步验证（中/英/繁），禁止硬编码字符串 |
| `/doc-sync-guardian` | 三层文档体系一致性维护 |
| `/intent-router` | 意图路由：自然语言需求→技术任务 |
| `/mnn-llm-android` | MNN-LLM 端侧大模型推理（Qwen/下载/调试） |
| `/av-gl-expert` | OpenGL/CameraX 诊断（黑屏/Shader/EGL） |

---

## 当前可用 Qoder / kimi-cli Skills

| Skill | 描述 |
|-------|------|
| `ui-driver` | PoLang UI 自动化（Accessibility 结构化文本驱动） |
| `adb-bot` | ADB 基础控制与调试 |
| `android-build-debug` | Android 编译、安装、日志调试 |
| `dev-loop` | 编译→安装→验证→报告一键闭环 |
| `av-gl-expert` | 音视频与 OpenGL 渲染专家 |
| `compose-ui-expert` | Jetpack Compose UI 开发与性能优化 |
| `coordinate-system-standard` | 人脸关键点坐标系规范化 |
| `doc-sync-guardian` | 三层文档体系一致性检查 |
| `egl-state-machine` | EGL 上下文与离屏渲染状态机规范 |
| `error-healer` | Kotlin/Gradle 错误分类与自愈 |
| `i18n-validator` | 国际化资源检查与三语同步验证 |
| `image-quality-checker` | 截屏图片质量分析 |
| `intent-router` | 自然语言需求解析与上下文加载 |
| `layout-inspector-expert` | Android 布局检查与 UI 结构分析专家 |
| `mediapipe-landmark-mapping` | MediaPipe 关键点映射规范 |
| `mnn-integration` | MNN 推理引擎集成规范 |
| `mnn-landmark-diagnosis` | MNN 关键点诊断与调试 |
| `mnn-llm-android` | MNN-LLM 端侧大模型部署指南 |
| `onnx-model-integration` | ONNX 模型接入 Checklist |
| `perf-optimizer` | 性能分析与优化策略 |
| `rd-reflection` | RD 复盘模板 |

---

## 核心项目文档

| 文档 | 路径 | 用途 |
|------|------|------|
| 产品需求 | `PRODUCT.md` | 目标与约束 |
| 交互规范 | `docs/01-PRODUCT/FEATURES.md` | 交互与体验规则 |
| 开发工作流 | `docs/05-DEVELOPMENT/DEVELOPMENT.md` | Spec-Code 双螺旋演进、CR 规范 |
| 相册搜索 SSOT | `docs/03-TECHNICAL-SPECS/GALLERY_SEARCH.md` | 自然语言搜索完整链路 |
| TAG 生成 | `docs/03-TECHNICAL-SPECS/TAG_GENERATION.md` | 3-Pass 标签生成管道 |
| 美颜引擎 | `docs/03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md` | Shader 架构、多 Pass 渲染 |
| 人脸关键点 | `docs/03-TECHNICAL-SPECS/FACE_LANDMARKS.md` | MediaPipe 468 / 火山 106 点参考与映射 |
| 人脸检测架构 | `docs/03-TECHNICAL-SPECS/FACE_DETECTION_ENGINE_ARCHITECTURE.md` | 多引擎 ROI + Landmark 设计 |
| 帧同步妆容 | `docs/03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md` | 时序对齐、甩飞问题根治（帧同步已并入美颜引擎 spec） |
| 远程推理 | `docs/02-ARCHITECTURE/AGENT_ARCHITECTURE.md` | 本地/远程协议分离、IntentCache |

---

## 快捷命令

```bash
# 构建调试版本
./gradlew :androidApp:assembleDebug

# 运行单元测试
./gradlew :androidApp:testDebugUnitTest

# 查看 PoLang 日志
adb logcat -s "PoLang:*"

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
| **Claude Code** | `.claude/` | ✅ 已配置 | AI 开发环境（日常备选） |
| **kimi-cli** | `.kimi/` | ✅ 已配置 | 终端交互式 AI 开发（主力） |
| **Lingma（通义灵码）** | `.lingma/skills/` | ⚠️ 已停用 | 原 Skills 已迁出，不再维护 |

---

## 兼容性变更记录

| 日期 | 变更 | 影响 |
|------|------|------|
| 2026-05-03 | 创建 `.kimi/AGENTS.md` 与 `.kimi/skills/` | kimi-cli 获得独立项目配置入口 |
| 2026-05-03 | `.openclaw/skills/` 同步新增 skills 符号链接 | OpenClaw 可见完整 skills 列表 |
| 2026-05-03 | 删除断裂的 `shader-debug` 符号链接 | 消除 OpenClaw 加载错误 |
| 2026-05-03 | 精简 `AGENTS.md`（已合并入根 `AGENTS.md`） | 避免与根 `AGENTS.md` 重复 |
| 2026-05-03 | 修正 `scripts/kimi-cli.sh` APK 路径 | `picme-debug` → `app-debug`；项目路径 `PoLang` → `langchain4android` |
| 2026-05-03 | 新增 `DEVELOPMENT.md` | 通用开发命令从 OpenClaw 独占迁移为全平台共用 |
| 2026-05-03 | 新增 `AI_TOOLS.md` | 统一索引所有 AI 工具配置位置 |
| 2026-05-20 | **Skills 唯一事实来源迁移** | `.lingma/skills/` → `.qoder/skills/`，Qoder 成为主力开发环境 |
| 2026-05-20 | 新增 `mnn-landmark-diagnosis` | MNN 关键点 C++ 层性能诊断 Skill |
| 2026-05-25 | 新增 `compose-ui-expert` / `i18n-validator` / `perf-optimizer` | Jetpack Compose 专家、国际化验证、性能优化 |
| 2026-05-25 | 新增 `mnn-integration` / `ncnn-integration` / `mnn-llm-android` | 推理引擎集成规范完善 |
| 2026-05-28 | 新增 `qa-acceptance` | QA 验收流程 |
| 2026-07-02 | 测试 Skill 整合与重命名 | 删除 `agent-test-framework` / `ui-automation-expert`；`accessibility-ui-driver` → `ui-driver`；`agent-test-expert` → `agent-test`；`auto-dev-loop` → `dev-loop` |
| 2026-05-31 | 文档全面审计与更新 | 根目录文档与 wiki 一致性清理 |
| 2026-06-25 | Claude Code 命令整理 | `.qoder/skills/` → `.claude/commands/`，修复 19 个过期引用 |
| 2026-06-30 | AI 工具索引刷新 | 明确 Claude Code 命令为主力来源，更新 DEVELOPMENT.md / CLAUDE.md / 本文件 |
| 2026-08-01 | **Plans / Specs SSOT 统一** | 新建 `docs/superpowers/README.md`；`.omo/plans` 软链到公共目录；新增 `scripts/check-skill-sync.sh` 治理 `.claude/commands` 漂移；OpenCode 纳入工具表 |
