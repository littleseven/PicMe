# PoLang iOS 任务看板

> **定位**：iOS 端工作的**单一任务看板**（What's done / What's next / Won't do）。按代码实况维护，状态以 `iosApp/` 实际 Swift 文件 + git 为准。
>
> **关系**：本文是 *执行视图*（任务 + 状态 + 优先级）；*产品规格* 见 [`IOS_PRODUCT_REFERENCE.md`](IOS_PRODUCT_REFERENCE.md)（逐功能行为契约）；*Phase 路线图* 见 [`../superpowers/plans/2026-08-07-polang-kmp-ios-transformation.md`](../superpowers/plans/2026-08-07-polang-kmp-ios-transformation.md)（Phase 划分 SSOT + 变更记录）。三者冲突时：**代码 > 产品参考 > 本文**，但本文反映最新执行进度。
>
> **基线**：Android main v1.0.34 · iOS 截至 Phase 6.x · 最近校准 **2026-08-10**
>
> **图例**：✅ 已落地 · 🔄 部分/进行中 · 📋 规划 · ❌ 缺口 · 🚫 不对齐（平台无等价，不做）

---

## §1 Phase 总览

| Phase | 内容 | 状态 | 出口证据 |
|---|---|---|---|
| 1 | agent-core → Koog 迁移（Android 侧） | ✅ | `:agent-core` 删除（`1cbe9353`），全链路真机回归 |
| 2 | iOS 技术排雷 Spikes（MNN/QuickJS/Metal 宿主） | ✅ | 三 spike 全绿；⏸️ 补验 B「Qwen3-VL-2B 真机」暂缓（触发点：Phase 6.1 Pass3 开工前） |
| 3 | 改名 + 目录重组 | ✅ | `323c3e1a` merge，`assembleDebug` + 安装回归全绿 |
| 4 | shared KMP 模块抽取 | ✅ | `805870e5`→`c1dc78e4`，`:shared` 五 target，107 JVM 用例全绿 |
| 5 | iOS App 骨架 + 相机管线（TestFlight 出口） | ✅ | 相册浏览 + 相机预览/拍照 + 设置骨架 |
| **6** | **iOS 功能对齐与发布准备** | **🔄** | 见 §2 |
| 7 | 演进（持续） | — | — |

**代码规模**：~12800 行 Swift（Camera 3411 / Gallery 1996 / Settings 1813 / Person 1311 / Chat 738 / Platform 基建 2562 / Main+Common 171）+ 5 个 metal shader（beauty/lut/smoothing/warp/yuv）。

---

## §2 Phase 6 详细（当前主战场）

### 6.1 TAG 3-Pass 流水线 — 🔄 基建层已建，端到端未组装

| 子项 | 状态 | 证据 / 缺口 |
|---|---|---|
| 模型中心（16 模型下载/进度/删除） | ✅ | `ModelCenterView` 真机 6/6 绿 |
| Pass1 基建（编排/对齐/embedding/MobileCLIP/GRDB） | ✅ | `Pass1Pipeline.swift` / `FaceAlignment` / `MobileClipEncoder` / `TagDatabase`（`25414e12` Step1-6） |
| 人脸检测可用（MNN 106pt + MediaPipe 468→106） | ✅ | self-test faceFound=true/106pt；2d106det 预归一化修复 |
| Pass2 聚类（DBSCAN / 自适应 k-NN 连通分量） | ❌ | 未实现 |
| Pass3 VLM 打标（Florence-2 默认 / Qwen3-VL 备选） | ❌ | 🔴 阻塞于补验 B（Qwen3-VL-2B 真机未验证） |
| iOS MetalGuardian（替代 OpenClGuardian） | ❌ | 新设计：warmup 超时 + Metal→CPU 降级（含模型卸载重载）+ MTLDevice 丢失 + 黑名单持久化 |
| TAG 控制页 + 扫描编排 | ❌ | `Pass1Pipeline` 当前**未接线任何 UI/VM** |
| 后台扫描（ForegroundService → BGTaskScheduler） | ❌ | iOS ~30s 限制 → 改「充电+锁屏增量」或「手动触发」（双端功能差异） |

### 6.2 Chat 与 AI 指令 — ✅ 完成（基础链路）

✅ 远程 tool_calls 流式对话（`ChatAgentBridge`→`RemoteChatEngine`→`KoogChatAgent`）/ 思考态→首 token→流式光标 / 媒体结果卡（`media_results`）/ 空态示例 / 清空·新建 / iOS 专属 prompt（8 工具裁剪）/ 隐私契约（DTO 无文件路径/GPS/base64）

**非阻塞缺口**（归 §3 后续）：`text_reply`/`success`/`error` 三 kind 被 `default:break` 丢弃 · 停止生成 UI（`cancelCurrent` 未调用）· 多会话侧边栏 · JS 画图（`CHART`）· 反馈 UI（`media_feedback`）· 图片附件 · 语音输入 · AI 优化抽卡 · Claude 工程师模式

### 6.3 设置与账号 — 🔄 主体完成，剩 3 项

✅ 设置主页 + 全部二级页（逐像素还原）+ Model Center（BYOK）+ 端侧模型下载中心 + 主题/语言即时生效 + Telegram 通道 + Privacy Manifest

**剩余**：① 🔴 App Store 2.5.2 合规分析（LLM 生成代码端侧执行；iOS code-interpreter 上线前出三级结论）· ② 隐私政策页（相册用途描述 / 数据收集声明待完善）· ③ server 账号体系（Apple Sign In，P3 按需；邮箱方案合规非必需）

### 6.4 server 端 iOS 适配 — ✅ 完成

✅ server 纯平台无关（5 适配点零阻塞）· `X-Platform: ios` header（`0b89ee3a`/`553fefba`）· 设备平台字段（`a5ecf2f5`）。Apple Sign In / APNs 按需推进。

### 6.5 验收对齐 — 🔄 部分完成

✅ iOS UI Driver + 截图自动验收（`79350b84`）· spec→UITest gap 清查

**剩余**：① 核心验收 UITests 补全（`media_pager` 标识符 + 相机/gallery 控件 identifier）· ② 结构化日志（llm/tool/js 三层）iOS 落地（未启动）

### 6.6 功能深化对齐 — 🔄 部分启动

| 切片 | 状态 | 备注 |
|---|---|---|
| 人物页 UI | ✅ | 1311 行（`PersonView`/`PersonInfoView`/`PersonViewModel`/`PersonStore`，`02806687`）；**后端未接** shared |
| 相册人脸关键点交互 | 🔄 | 近期 commits（debug 门控 + 缩放跟随） |
| 相机规格 gap（美颜默认值统一 0 / 滤镜互斥 / 快门 80ms 反馈） | 🔄 | MVP 在，细节待对齐 |
| 跟手横滑 Pager + 4 页常驻 | ❌ | 仍 ZStack 条件渲染，无手势 / 无常驻 |
| 自然语言搜索（整链路） | ❌ | 依赖 6.1 TAG 索引 |

---

## §3 缺口（Phase 6+，需新建）

> 按依赖深度与解锁价值排序。前置依赖标注于「关键替换 / 依赖」列。**可执行任务拆分见 [`../superpowers/plans/2026-08-10-ios-implementation-tasks.md`](../superpowers/plans/2026-08-10-ios-implementation-tasks.md)。**

| # | 功能域 | 缺口 | 关键替换 / 依赖 | 阻塞 |
|---|---|---|---|---|
| G1 | TAG | Pass2 聚类 + Pass3 VLM + MetalGuardian + 控制页 + 后台扫描 | MNN Metal（**precision 档位锁定坑**）；MetalGuardian 新设计；FGS→BGTaskScheduler | 🔴 Pass3 阻塞于补验 B |
| G2 | 搜索 | 整条 NL 搜索链路（Parser/Segmenter/Vocabulary/Pipeline/Engine） | 前置：G1 TAG 标签 + 人脸聚类 + MobileCLIP + OCR + SQLite；`QueryParser` 用 `java.util.Calendar` 需换 `kotlinx-datetime` | G1（软阻塞，可先做规则+SQL 召回） |
| G3 | 编辑 | 静态美颜编辑器 / 智能抠图 / 证件照 / AI 一键优化抽卡 | FBO→Metal MSL；`BeautyParams`/`FilterType`/`StyleFilter` 已 commonMain；ONNX Runtime iOS 可用；FUSION 纯数组可移植 | 无 |
| G4 | 人物/记忆 | 关系图谱后端 / 封面美学（NIMA+eDifFIQA）/ 事实记忆 | Room→SQLDelight；NNAPI→CoreML/Metal；`KinshipLexicon`/`PersonQueryResolver` 纯 Kotlin 宜下沉 shared；UI 骨架已有 | G1 Pass2（人脸聚类） |
| G5 | 相机 | 录像（美颜录制）/ 十字星时序 / 风格特效 5 项 / 语音入口 | Metal 美颜录制；Sherpa-ONNX iOS 单独实现；风格 GLSL→MSL | 无 |
| G6 | 设置 | 账号登录 / quota / WiFi 静默预下载 / 备份恢复 | `PoLangAuthClient` 等价 + `X-Platform: ios` + `UIDocumentPicker` + App Store 2.5.2（JS 下发声明） | 6.3①合规结论 |
| G7 | Chat 补全 | 多会话 / JS 画图 / 反馈 / 图片附件 / 语音 / 抽卡 | shared 契约就绪，主要是 UI 消费完备性 + 新消息类型 + 持久化升级多会话 | 6.3①（JS 画图）/ G1（语义搜索） |

---

## §4 平台不对齐（🚫 不计划复刻）

| 功能 | 原因 |
|---|---|
| 飞书/Telegram 远程控制（跨应用 a11y RPA） | iOS 无 AccessibilityService 等价，`RemoteControlToolService` click/scroll/input 不可移植 |
| 悬浮聊天气泡 | iOS 无系统悬浮窗（`TYPE_APPLICATION_OVERLAY`）等价 |
| `launch_app` / `open_system_settings` 强能力 | iOS 沙盒限制，`SystemCapability` 能力远弱于 Android |
| HyperOS 后台冻结检测 | iOS 无厂商冻结问题，`BackgroundScanGuard` 无意义 |

---

## §5 已移除（iOS 勿复刻）

端侧文本 LLM（`qwen3_5_2b`，2026-08 移除）· GPUPixel（自研引擎替代）· InsightFace ONNX / NCNN（MediaPipe+MNN 替代）· langchain4j fork（Koog 替代）· `PrivacyGuard.isRemoteAllowed()` 死代码 · `AiAgentMode.LOCAL`（仅留作离线兜底枚举）。

---

## §6 漂移记录（文档 vs 代码）

> 本看板与两份源文档保持同步；历史漂移修正登记于此，最新一次见下。

| 日期 | 漂移 | 修正 | 提交 |
|---|---|---|---|
| 2026-08-10 | D1 人物页「0 文件」 | → 1311 行 UI 骨架已落地（后端未接） | `7832df62` |
| 2026-08-10 | D2 6.1 TAG「未启动」 | → Pass1 基建已建（端到端未组装） | `7832df62` |
| 2026-08-10 | D3「44 commits 未 push」 | → 已全推，main 与 origin 同步 | `7832df62` |
| 2026-08-10 | D4 i18n「无 zh-Hant」 | → 三语就绪，key 191→239 | `7832df62` |
| 2026-08-10 | 全面漂移扫描（A/B/C/D/E 共 25 处） | 6 文档修正：产品参考 11 处（场景同步已接入 / TAG Pass1 已移植 / 人物页非占位 / swipe 手势 / i18n §4.2 传播遗漏等）、路线图风险登记 6 项已解风险关闭、parity spec 清单+RTL+gap 引用、2 份 gap-analysis 加快照 banner；详见 [`2026-08-10-ios-kmp-doc-drift-audit.md`](../reviews/2026-08-10-ios-kmp-doc-drift-audit.md) | 见审计报告 §1 |

---

## §7 维护说明

- **更新时机**：每完成一个 Phase 6 子项 / 缺口功能落地 / 发现新漂移时更新本文 §2 / §3 / §6。
- **状态判定**：以 `iosApp/PoLang/Features/` 实际 Swift 文件 + git commit 为准（非文档自述）。
- **新建功能**：落地后从 §3 移至 §2 对应子项（或新增 ✅ 行），并在 §6 登记漂移修正。
- **不收录**：纯 Android 侧变更（除非影响 shared 契约面）。
