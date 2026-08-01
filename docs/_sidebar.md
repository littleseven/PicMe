- [🏠 返回官网](/)
- [📑 文档总索引](00-INDEX.md)

- **产品**
  - [功能交互](01-PRODUCT/FEATURES.md)
  - [使用前提与设置](01-PRODUCT/SETUP_GUIDE.md)
  - [非功能性需求](01-PRODUCT/NFR_SPEC.md)

- **架构**
  - [Agent 架构](02-ARCHITECTURE/AGENT_ARCHITECTURE.md)
  - [模块架构](02-ARCHITECTURE/MODULE_ARCHITECTURE.md)
  - **架构决策 (ADR)**
    - [ADR-001 美颜引擎架构](02-ARCHITECTURE/ADR/ADR-001-beauty-engine-architecture.md)
    - [ADR-002 OpenGL 离屏统一管线](02-ARCHITECTURE/ADR/ADR-002-opengl-offscreen-unified-pipeline.md)
    - [ADR-003 坐标系管理](02-ARCHITECTURE/ADR/ADR-003-coordinate-system-management.md)
    - [ADR-004 GPU 争用解决](02-ARCHITECTURE/ADR/ADR-004-gpu-contention-resolution.md)
    - [ADR-005 本地/远程推理分离](02-ARCHITECTURE/ADR/ADR-005-local-remote-inference-split.md)
    - [ADR-006 命令系统分离](02-ARCHITECTURE/ADR/ADR-006-command-system-separation.md)
    - [ADR-007 自然语言照片搜索](02-ARCHITECTURE/ADR/ADR-007-natural-language-photo-search.md)
    - [ADR-008 隐私红线（禁媒体上传）](02-ARCHITECTURE/ADR/ADR-008-privacy-redline-media-only.md)
    - [ADR-009 本地 LLM 收缩至相机](02-ARCHITECTURE/ADR/ADR-009-local-llm-camera-only.md)
    - [ADR-010 远程/本地链路隔离](02-ARCHITECTURE/ADR/ADR-010-remote-local-chain-isolation.md)
    - [ADR-011 退役非 ui-driver 测试](02-ARCHITECTURE/ADR/ADR-011-retire-non-ui-driver-tests.md)
    - [ADR-012 统一会话记忆](02-ARCHITECTURE/ADR/ADR-012-unify-conversation-memory.md)

- **技术规格**
  - [大美丽引擎](03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md)
  - [人脸检测引擎](03-TECHNICAL-SPECS/FACE_DETECTION_ENGINE_ARCHITECTURE.md)
  - [人脸关键点](03-TECHNICAL-SPECS/FACE_LANDMARKS.md)
  - [AI 一键优化](03-TECHNICAL-SPECS/AI_OPTIMIZATION.md)
  - [相册搜索](03-TECHNICAL-SPECS/GALLERY_SEARCH.md)
  - [TAG 生成](03-TECHNICAL-SPECS/TAG_GENERATION.md)
  - [语音栈](03-TECHNICAL-SPECS/VOICE_STACK.md)
  - [MNN LLM 运维](03-TECHNICAL-SPECS/MNN_LLM_OPERATIONS.md)
  - [MNN 关键点诊断](03-TECHNICAL-SPECS/MNN_LANDMARK_DIAGNOSIS.md)
  - [端侧推理清单](03-TECHNICAL-SPECS/ON_DEVICE_INFERENCE_INVENTORY_TECH_SPEC.md)
  - [端侧图像理解模型](03-TECHNICAL-SPECS/ONDEVICE_IMAGE_UNDERSTANDING_MODELS.md)
  - [聊天 UI 统一](03-TECHNICAL-SPECS/CHAT_UI_UNIFICATION.md)
  - [JS 沙盒引擎](03-TECHNICAL-SPECS/JS_ENGINE_TECH_SPEC.md)
  - [IM 远程控制](03-TECHNICAL-SPECS/IM_REMOTE_CONTROL_TECH_SPEC.md)

- **Agent 能力**
  - [能力注册表](04-AGENT-CAPABILITIES/CAPABILITY_REGISTRY.md)
  - [命令参考](04-AGENT-CAPABILITIES/COMMAND_REFERENCE.md)

- **开发**
  - [开发流程](05-DEVELOPMENT/DEVELOPMENT.md)
  - [本地环境](05-DEVELOPMENT/LOCAL_ENVIRONMENT.md)

- **QA**
  - [性能基线报告](06-QA/PERFORMANCE_BASELINE_REPORT.md)
  - [OPUS 翻译验证](06-QA/research/OPUS_MT_TRANSLATION_VALIDATION.md)

- **标准**
  - [术语表](07-STANDARDS/GLOSSARY.md)
  - [坐标系规范](07-STANDARDS/COORDINATE_SYSTEM.md)
