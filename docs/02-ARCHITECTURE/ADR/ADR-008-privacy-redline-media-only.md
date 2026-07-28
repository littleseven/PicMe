# ADR-008: 隐私红线放宽为「禁止媒体文件上传到远程大模型」

**状态**: 已实施（文案与防回归守卫落地；治理进行中）
**日期**: 2026-07-28
**决策**: 用户（基于 chat/LLM 链路架构 review）
**依赖**: ADR-005（本地/远程推理协议分离）；`docs/reviews/2026-07-27-chat-llm-architecture-review.md` §0

---

## 1. 背景

项目原 `[PRIVACY]` 红线为「All AI processing (face, OCR, classification) must be 100% on-device. Cloud inference is strictly prohibited.」该红线在产品重心迁移到相册/chat（远程 LLM 为主，见 ADR-005）后已名存实亡：chat 默认走远程 DeepSeek/PoLang server，用户自由文本 + 相册摘要 + 搜索词均上行。review 指出此为 P0 红线失守。

用户重新审视后认为：**真正的隐私关切是用户的图片/视频文件内容外泄给第三方模型**，而非「任何远程推理」。文本/元数据上行可接受。

## 2. 决策

将 `[PRIVACY]` 红线重定义为：

> **禁止向远程大模型/推理服务器上传用户图片、视频文件。** 人脸检测/OCR/分类/打标等媒体处理必须 100% 端侧。文本、元数据、相册聚合摘要等非媒体数据可走远程推理（chat 默认远程）。

**豁免**：飞书、Telegram 等**用户自配置 IM 通道**回传图片/视频给用户本人不属红线——这是用户自有通道的内容投递，非模型推理上传。

## 3. 实现要点

- **红线文案**：`CLAUDE.md`（Privacy-first 头 + Global Red Line `[PRIVACY]`）、`AGENTS.md`、`app/AGENTS.md` 已同步更新。
- **防回归守卫**：新增 `RemoteInferenceNoMediaUploadGuardTest`（runtime-core/src/test），静态扫描 `inference/remote/**` 源码，断言不出现 `ImageContent`/`generateWithImage`/`imageInference`/`MultipartBody`/`multipart/` 等媒体上传符号。任一出现即测试变红。
- **现状合规**：已核实远程 ReAct 链路（`RemoteReActAgent.executeTask`）入参仅为 text prompt；`ai_optimize`/`edit_image`/`adjust_image`/打标/人脸 全走端侧 renderer/本地模型，仅回文本 observation 给 LLM。
- **`PrivacyGuard` 重定位**：用途从「拦截远程推理」转为「拦截媒体文件进入远程 LLM」；已死的 `assertLocalOnly`/`isRemoteAllowed` 待清理。

## 4. 后果

- ✅ chat 可放手使用远程 LLM 的强能力（多轮、tool_calls），不再受「禁止云推理」束缚。
- ✅ 真正的隐私边界（媒体文件）有测试守卫，不会被悄悄突破。
- ⚠️ 文本/元数据上行需用户认知（注册/访客模式已有 X-Device-Id 等机制）。
- ⚠️ 未来若要做「多模态看图对话」，必须改走端侧 VLM（`LocalLlmEngine.imageInference`），不可把图喂给远程模型。

## 5. 状态

| 项 | 状态 |
|---|---|
| 红线文案同步 | ✅ 2026-07-28 |
| 防回归守卫 | ✅ 2026-07-28 |
| `PrivacyGuard` 重定位 | ⏳ 待（随决策3 隔离） |

## 6. 相关

- ADR-005（协议分离）、ADR-009（本地模型收缩）、ADR-010（链路隔离）
- `docs/reviews/2026-07-27-chat-llm-architecture-review.md` §0.3-D1
