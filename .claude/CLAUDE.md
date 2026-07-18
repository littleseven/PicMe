# langchain4android Claude Code Commands 索引

> Claude Code 命令索引。所有命令定义在 `.claude/commands/*.md`，对话中通过 `/command-name` 调用。历史命令曾从 `.qoder/skills/` 迁移而来，当前以 `.claude/commands/` 为唯一事实来源。

## 可用 Commands（共 25 个）

### 🔧 开发与构建
| Command | 说明 | 行数 |
|---------|------|------|
| `/android-build-debug` | Android 编译、安装、日志调试标准化流程 | 114 |
| `/error-healer` | Kotlin/Gradle 编译错误自动分类与修复策略 | 299 |
| `/dev-loop` | 一键编译→安装→设备验证→质量检查闭环 | 213 |
| `/i18n-validator` | 多语言同步验证（中/英/繁），禁止硬编码字符串 | 113 |

### 📱 设备控制与调试
| Command | 说明 | 行数 |
|---------|------|------|
| `/adb-bot` | adb 自动化控制相机应用与设备调试 | 374 |
| `/image-quality-checker` | 截屏质量分析（黑屏/亮度），注意：自动化脚本尚未实现 | 167 |

### 🧪 测试与质量
| Command | 说明 | 行数 |
|---------|------|------|
| `/agent-test` | Agent 自动化测试 V2（JSON 驱动，PC 端）✅ 当前推荐 | 158 |
| `/qa-acceptance` | QA 质量验收（端到端/边界/性能基线/红线） | 267 |
| `/ui-driver` | PoLang UI 自动化（Accessibility 结构化文本驱动） | 129 |

### 🎨 渲染与图形
| Command | 说明 | 行数 |
|---------|------|------|
| `/av-gl-expert` | OpenGL/CameraX 诊断（黑屏/Shader/EGL） | 415 |
| `/egl-state-machine` | EGL 上下文状态机管理 | 173 |
| `/coordinate-system-standard` | 人脸关键点坐标/渲染管线/UI 标注规范 | 436 |

### 🤖 AI/推理引擎
| Command | 说明 | 行数 |
|---------|------|------|
| `/mnn-integration` | MNN 推理引擎接入（模型加载/JNI/LLM） | 347 |
| `/mnn-llm-android` | MNN-LLM 端侧大模型推理（Qwen/下载/调试） | 274 |
| `/mnn-landmark-diagnosis` | MNN/ONNX 人脸关键点检测对齐诊断 | 274 |
| `/ncnn-integration` | NCNN 推理引擎接入（Vulkan GPU/param修复） | 213 |
| `/onnx-model-integration` | ONNX 模型接入专家 | 104 |

### 🎯 UI/交互
| Command | 说明 | 行数 |
|---------|------|------|
| `/compose-ui-expert` | Jetpack Compose UI（布局/状态/重组/HyperOS） | 89 |
| `/layout-inspector-expert` | Layout Inspector 调试 Compose UI 问题 | 119 |
| `/mediapipe-landmark-mapping` | MediaPipe 468/106 点人脸关键点映射 | 118 |

### 📋 流程与治理
| Command | 说明 | 行数 |
|---------|------|------|
| `/doc-sync-guardian` | 三层文档体系一致性维护 | 493 |
| `/intent-router` | 意图路由：自然语言需求→技术任务 | 292 |
| `/perf-optimizer` | 性能优化（内存泄漏/卡顿/帧率） | 106 |
| `/rd-reflection` | RD 自我进化系统（复盘/经验/检查清单） | 176 |

---

## 使用方式

在 Claude Code 对话中输入 `/command-name` 即可加载对应 skill 的完整上下文。

例如：
- `/adb-bot` — 获取 adb 自动化控制能力
- `/error-healer` — 获取编译错误自动修复策略
- `/av-gl-expert` — 获取 OpenGL 诊断能力

---

> 命令源文件：`.claude/commands/*.md`
> 历史源文件：`.qoder/skills/*/SKILL.md`（已迁移，不再维护）
> 同步脚本：`.claude/migrate.py`（`.qoder/skills/` → `.claude/commands/`）
> 修复脚本：`.claude/fix_skills.py`（修复过期引用、路径、模板）
> 最近整理：2026-06-30（刷新命令来源说明，确认 25 个命令与 `.claude/commands/` 一一对应）
