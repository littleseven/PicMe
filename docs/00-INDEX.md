# langchain4android 文档导航索引

> **维护者**: CO Agent
> **最后更新**: 2026-07-08
> **版本**: 2.1（第二轮精简版）

---

## 文档地图

| 层级 | 路径 | 说明 |
|------|------|------|
| **顶层治理** | [`../AGENTS.md`](../AGENTS.md) | Agent First 研发流程、角色协作、全局红线 |
| **产品定义** | [`../PRODUCT.md`](../PRODUCT.md) | 产品目标、路线图、验收标准 |
| **交互规范** | [`01-PRODUCT/FEATURES.md`](./01-PRODUCT/FEATURES.md) | 用户交互流程与体验规则 |
| **性能红线** | [`01-PRODUCT/NFR_SPEC.md`](./01-PRODUCT/NFR_SPEC.md) | 性能/稳定性/隐私量化指标 |
| **Agent 架构** | [`02-ARCHITECTURE/AGENT_ARCHITECTURE.md`](./02-ARCHITECTURE/AGENT_ARCHITECTURE.md) | Agent 运行时架构、本地/远程推理 |
| **模块架构** | [`02-ARCHITECTURE/MODULE_ARCHITECTURE.md`](./02-ARCHITECTURE/MODULE_ARCHITECTURE.md) | Gradle 模块划分与依赖关系 |
| **架构决策** | [`02-ARCHITECTURE/ADR/`](./02-ARCHITECTURE/ADR/) | ADR-001 ~ ADR-007 |
| **能力注册** | [`04-AGENT-CAPABILITIES/CAPABILITY_REGISTRY.md`](./04-AGENT-CAPABILITIES/CAPABILITY_REGISTRY.md) | Capability 列表、命令映射、实现指南与生命周期规范 |
| **命令参考** | [`04-AGENT-CAPABILITIES/COMMAND_REFERENCE.md`](./04-AGENT-CAPABILITIES/COMMAND_REFERENCE.md) | 命令语法与示例 |
| **开发规范** | [`05-DEVELOPMENT/DEVELOPMENT.md`](./05-DEVELOPMENT/DEVELOPMENT.md) | 双螺旋工作流、代码审查、任务标记规范、CI 规则 |
| **本地环境** | [`05-DEVELOPMENT/LOCAL_ENVIRONMENT.md`](./05-DEVELOPMENT/LOCAL_ENVIRONMENT.md) | 本机开发路径与工具上下文 |
| **QA 验收** | [`06-QA/QA_EXECUTION_CHECKLIST.md`](./06-QA/QA_EXECUTION_CHECKLIST.md) | 端到端验收、自动化测试、Accessibility UI Driver、核心功能测试 |
| **性能基线** | [`06-QA/PERFORMANCE_BASELINE_REPORT.md`](./06-QA/PERFORMANCE_BASELINE_REPORT.md) | 历史性能 trace 报告合集 |
| **坐标系标准** | [`07-STANDARDS/COORDINATE_SYSTEM.md`](./07-STANDARDS/COORDINATE_SYSTEM.md) | 图像/人脸坐标系与命名规范 |
| **术语词典** | [`07-STANDARDS/GLOSSARY.md`](./07-STANDARDS/GLOSSARY.md) | 统一术语定义 |
| **重构计划** | [`07-STANDARDS/CODE_REFACTORING_PLAN.md`](./07-STANDARDS/CODE_REFACTORING_PLAN.md) | 代码债务与重构路线图 |

---

## 技术规范速查

| 文档 | 主题 |
|------|------|
| [`AI_OPTIMIZATION.md`](./03-TECHNICAL-SPECS/AI_OPTIMIZATION.md) | AI 一键图片优化方案与参数标准 |
| [`TAG_GENERATION.md`](./03-TECHNICAL-SPECS/TAG_GENERATION.md) | 相册自动 TAG 生成（5-Pass Pipeline） |
| [`GALLERY_SEARCH.md`](./03-TECHNICAL-SPECS/GALLERY_SEARCH.md) | 相册自然语言搜索完整链路 |
| [`ON_DEVICE_INFERENCE_INVENTORY_TECH_SPEC.md`](./03-TECHNICAL-SPECS/ON_DEVICE_INFERENCE_INVENTORY_TECH_SPEC.md) | 端侧推理引擎与模型全景梳理（含优化评估与多模型生命周期改造清单） |
| [`MNN_LLM_OPERATIONS.md`](./03-TECHNICAL-SPECS/MNN_LLM_OPERATIONS.md) | MNN-LLM 运维与资源管理 |
| [`MNN_LANDMARK_DIAGNOSIS.md`](./03-TECHNICAL-SPECS/MNN_LANDMARK_DIAGNOSIS.md) | MNN Landmark 检测路径诊断与修复方法论 |
| [`ONDEVICE_IMAGE_UNDERSTANDING_MODELS.md`](./03-TECHNICAL-SPECS/ONDEVICE_IMAGE_UNDERSTANDING_MODELS.md) | 端侧图片理解模型调研 |
| [`VOICE_STACK.md`](./03-TECHNICAL-SPECS/VOICE_STACK.md) | 语音栈：唤醒词优化、KWS 迁移与 ASR Language Model 说明 |
| [`BEAUTY_ENGINE_TECH_SPEC.md`](./03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md) | 大美丽引擎技术规格（含相机预览比例、帧同步美妆、容灾降级） |
| [`FACE_DETECTION_ENGINE_ARCHITECTURE.md`](./03-TECHNICAL-SPECS/FACE_DETECTION_ENGINE_ARCHITECTURE.md) | 人脸检测引擎架构 |
| [`OVERSEAS_SERVER_DEPLOYMENT.md`](./03-TECHNICAL-SPECS/OVERSEAS_SERVER_DEPLOYMENT.md) | 海外服务端部署（香港 + Cloudflare）：架构、选型、Cloudflare 详解、安全、成本与运维 |
| [`SERVER_IMPLEMENTATION_PLAN.md`](./03-TECHNICAL-SPECS/SERVER_IMPLEMENTATION_PLAN.md) | 服务端实现方案（Ktor）Review 版：技术栈、仓库策略、API/DB、部署、决策点 |
| [`FACE_LANDMARKS.md`](./03-TECHNICAL-SPECS/FACE_LANDMARKS.md) | MediaPipe 468 / 火山 106 点参考与映射 |
| [`IM_REMOTE_CONTROL_TECH_SPEC.md`](./03-TECHNICAL-SPECS/IM_REMOTE_CONTROL_TECH_SPEC.md) | IM（飞书）远程控制 |
| [`AGENT_UI_DESIGN.md`](./03-TECHNICAL-SPECS/AGENT_UI_DESIGN.md) | 远程 LLM 编排 UI 层设计 |
| [`CHAT_UI_UNIFICATION.md`](./03-TECHNICAL-SPECS/CHAT_UI_UNIFICATION.md) | Chat UI 统一化改造 |

---

## 快速导航

- **新人入门** → `FEATURES.md` → `AGENT_ARCHITECTURE.md` → `COMMAND_REFERENCE.md`
- **实现新功能** → `FEATURES.md` → `CAPABILITY_REGISTRY.md` → 模块 `AGENTS.md`
- **排查性能问题** → `NFR_SPEC.md` → `ON_DEVICE_INFERENCE_INVENTORY_TECH_SPEC.md` → `BEAUTY_ENGINE_TECH_SPEC.md`
- **执行 QA 验收** → `QA_EXECUTION_CHECKLIST.md` → `BEAUTY_ENGINE_TECH_SPEC.md`

---

> 完整治理规则与文档维护顺序见 [`../AGENTS.md`](../AGENTS.md)。
