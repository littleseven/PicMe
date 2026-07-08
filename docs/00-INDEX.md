# langchain4android 文档导航索引

> **维护者**: CO Agent
> **最后更新**: 2026-07-08
> **版本**: 2.0（精简版）

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
| **能力注册** | [`04-AGENT-CAPABILITIES/CAPABILITY_REGISTRY.md`](./04-AGENT-CAPABILITIES/CAPABILITY_REGISTRY.md) | Capability 列表与命令映射 |
| **命令参考** | [`04-AGENT-CAPABILITIES/COMMAND_REFERENCE.md`](./04-AGENT-CAPABILITIES/COMMAND_REFERENCE.md) | 命令语法与示例 |
| **开发规范** | [`05-DEVELOPMENT/DEVELOPMENT.md`](./05-DEVELOPMENT/DEVELOPMENT.md) | 双螺旋工作流、反向链接、CI 规则 |
| **QA 验收** | [`06-QA/QA_EXECUTION_CHECKLIST.md`](./06-QA/QA_EXECUTION_CHECKLIST.md) | 端到端验收清单 |
| **坐标系标准** | [`07-STANDARDS/COORDINATE_SYSTEM.md`](./07-STANDARDS/COORDINATE_SYSTEM.md) | 图像/人脸坐标系与命名规范 |
| **术语词典** | [`07-STANDARDS/GLOSSARY.md`](./07-STANDARDS/GLOSSARY.md) | 统一术语定义 |

---

## 技术规范速查

| 文档 | 主题 |
|------|------|
| [`AI_OPTIMIZATION.md`](./03-TECHNICAL-SPECS/AI_OPTIMIZATION.md) | AI 一键图片优化方案与参数标准 |
| [`TAG_GENERATION.md`](./03-TECHNICAL-SPECS/TAG_GENERATION.md) | 相册自动 TAG 生成（5-Pass Pipeline） |
| [`MNN_LLM_OPERATIONS.md`](./03-TECHNICAL-SPECS/MNN_LLM_OPERATIONS.md) | MNN-LLM 运维与资源管理 |
| [`VOICE_STACK.md`](./03-TECHNICAL-SPECS/VOICE_STACK.md) | 语音栈：唤醒词优化与 KWS 迁移 |
| [`FACE_LANDMARKS.md`](./03-TECHNICAL-SPECS/FACE_LANDMARKS.md) | MediaPipe 468 / 火山 106 点参考与映射 |
| [`GALLERY_SEARCH.md`](./03-TECHNICAL-SPECS/GALLERY_SEARCH.md) | 相册自然语言搜索完整链路 |
| [`BEAUTY_ENGINE_TECH_SPEC.md`](./03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md) | 大美丽引擎技术规格 |
| [`FRAME_SYNC_TECH_SPEC.md`](./03-TECHNICAL-SPECS/FRAME_SYNC_TECH_SPEC.md) | 帧同步美妆系统 |
| [`CAMERA_PREVIEW_TECH_SPEC.md`](./03-TECHNICAL-SPECS/CAMERA_PREVIEW_TECH_SPEC.md) | 相机预览管线 |
| [`IM_REMOTE_CONTROL_TECH_SPEC.md`](./03-TECHNICAL-SPECS/IM_REMOTE_CONTROL_TECH_SPEC.md) | IM（飞书）远程控制 |
| [`CHAT_UI_UNIFICATION.md`](./03-TECHNICAL-SPECS/CHAT_UI_UNIFICATION.md) | Chat UI 统一化改造 |

---

## 快速导航

- **新人入门** → `FEATURES.md` → `AGENT_ARCHITECTURE.md` → `COMMAND_REFERENCE.md`
- **实现新功能** → `FEATURES.md` → `CAPABILITY_IMPLEMENTATION_GUIDE.md` → 模块 `AGENTS.md`
- **排查性能问题** → `NFR_SPEC.md` → `BEAUTY_ENGINE_TECH_SPEC.md`
- **执行 QA 验收** → `QA_EXECUTION_CHECKLIST.md` → `FRAME_SYNC_TECH_SPEC.md`

---

> 完整治理规则与文档维护顺序见 [`../AGENTS.md`](../AGENTS.md)。
