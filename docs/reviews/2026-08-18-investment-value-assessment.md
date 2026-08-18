# PoLang 投入价值评估：产品与技术值得继续投入的方向

> **文档编号**: REVIEW-INVEST-001
> **创建日期**: 2026-08-18
> **性质**: 中立价值评估（非规划文档），基于 2026-08-18 主会话四轮讨论的结论固化
> **结论状态**: 推演性判断，市场侧结论待真实用户验证

---

## 1. 评估背景

基于仓库硬数据画像：

- 约 19 万行 Kotlin/Swift（不含构建产物）：Android 628 源文件、shared KMP 186、iOS 205 Swift、server 79、engines 78
- 2694 个 commit / 135 个有效开发日；近 30 天 1358 个 commit（AI 高密度生成节奏）
- 31 个 skills、9 个双端 UI parity spec、完整 docs 分层体系
- 173 个测试文件（对 800+ 源文件，密度偏低）；AGENTS.md §6.2 度量指标均为"待收集"

总体判断：**工程与方法论价值真实，产品价值悬空**——最大风险不是代码质量，而是在未被市场验证的产品上持续做高标准工程。

---

## 2. 技术侧值得重点投入的价值点（按优先级）

### 2.1 能力平面（Capability Plane）+ JS 沙箱 —— 最高杠杆

- **是什么**：`CapabilityRegistry` 统一注册分发（约 20 个 capability 覆盖相机/相册/搜索/写操作）+ QuickJS 沙箱（LLM 现场生成脚本做组合式取数与受控写操作，CommandRisk 分级 + 用户确认）。
- **为什么值得投**："LLM 通过标准化能力接口操作 App 数据"是行业明确方向（Google AppFunctions / Apple App Intents / MCP），而**端侧、隐私优先、跨端一致**这条路无成熟参照物；本项目已跑通 tool_calls → capability dispatch → JS 沙盒写通路 → 用户确认的完整闭环，属先发位置。该层位于 `shared/commonMain`（引擎无关，约 10.3k 行），一份投入双端受益。
- **投入落点（排序）**：
  1. Capability 契约严格化：schema（输入输出类型、风险分级、权限、幂等性）从约定升级为机器可校验的声明式定义（规模到 50 个 capability 时会失控）
  2. 评估向 MCP 对齐/桥接：行业标准若收敛到 MCP，做接入方而非孤岛，判断越早越便宜
  3. JS 沙箱写通路成熟化：风险分级、dry-run、可撤销性做成一等公民（做好是护城河，做砸是事故源）
  4. 补度量分析层：`llm_call_log`/`tool_call_log`/`js_run_log` 三件套已有数据，只差分析——这是证明"Agent First 架构有效"的唯一证据链

### 2.2 KMP 封装的 Koog 编排层 —— 对 iOS 杠杆最大

- **是什么**：`shared/commonMain` 的 `KoogChatAgent` / `KoogReActAgent` + `AgentOrchestrator` 等引擎无关层；iOS 经 `ChatAgentBridge` 零 Swift 推理代码获得完整 chat + tool_calls（Phase 6.2 已验证）。
- **关键区分**：该层补的是**远程 Agent 编排**缺口（iOS 无 Swift 等价物），**不补端侧模型推理**缺口（iOS VLM 仍是 stub，需 MNN.framework / CoreML 解决，与 Koog 无关）。
- **投入原则**：
  - 真正的长期资产是**引擎无关接缝**（`AgentOrchestrator`/`CapabilityRegistry`/`MemoryStore`），Koog 只是当前实现——Koog 1.1.1 年轻且已被迫打补丁（`poLangSingleRunStrategy`），保持"可替换"姿态，不写不可迁移的代码
  - 编排层适合放 shared（低频调用、复杂逻辑，互操作税占比小）；高频端侧推理路径不适合，两层不混
  - 补齐 iOS 互操作工程化欠账：SKIE spike、CrashKiOS（见 `docs/reviews/2026-08-10-kmp-best-practices-architecture-review.md`）

### 2.3 端侧 VLM 打标流水线 —— 最深的技术点（功能级资产）

- **是什么**：`domain/tag`（约 8.6k 行）：Qwen3-VL-2B + Florence-2 三 Pass 流水线、人脸聚类（AdaptiveFaceClusterer）、MobileCLIP 分类、OpenCL 降级守卫。
- **定位**：技术含量最深、踩坑经验最值钱，但复用边界止于"相册打标"场景——是**功能**不是平台。价值在于它是人物关系图谱（§3.1）的底层供给，随产品楔子一起投，不独立扩张。

### 2.4 不做新投但保留残值：相机/美颜引擎

- 2026-08-16 产品决策（PRODUCT.md）：相机线**冻结不删**——保留实时渲染引擎试验场 + 编辑流采集入口两项职责。评估确认该决策正确：
  - 编辑器静态图渲染复用相机美颜 GPU 管线（`PhotoProcessorImpl`、精准局部美颜、批量处理），删相机会连坐编辑器
  - 冻结态维护成本≈0，删除只省心理成本
- 可优化项：6 个相机/渲染 skills 可合并归档；冻结收尾待办（`SceneSelector`/`ScenePreset`/`scene_*` strings 移除）应清掉

---

## 3. 产品侧值得投入的价值点

### 3.1 人物关系图谱 —— 进攻性差异（无人区）

- 主流相册（Google/Apple/一刻/腾讯相册管家）均有人脸聚类+命名，**无人建模"关系"**（妈/岳父/同事）并将关系用于检索与分享。
- 中国家庭场景真实高频痛点：孩子照片定期发长辈、婚礼照片按亲友分发——现有工具全手动。
- 三重优势：关系图谱是用户标注积累的资产（迁移成本高、留存逻辑硬）；恰好是端侧推理投入的出口；"家人照片"最敏感场景里端侧隐私叙事（[PRIVACY] 红线）从成本变成卖点。
- **定位建议**：不做"更好的相册"（对 Google/手机厂商必输），做"懂人物关系的家庭照片管家"。

### 3.2 Chat 对话式相册操作 —— 防守性差异（窗口以年计）

- 竞品现状（2026-08 查证）：Google Ask Photos 因延迟/质量翻车暂停后于 2025-11 恢复扩大（验证了需求也证明了体验门槛）；国内相册 App 停留在关键词搜索层级，无"对话+工具调用+操作相册"；Apple Intelligence 大陆基本不可用——**国内 Android 存在真空**。
- 真正威胁是手机厂商系统级助手（超级小爱/小艺）做跨 App 智能体，相册是其主场数据——窗口是大厂体验未打磨好的时间差。
- **定位**：Chat 是交互层不是卖点。用户不为"能聊天"付费，为"一句话搞定原来翻半小时的事"付费——人物关系恰是这类任务中最高频的宾语。

### 3.3 语音栈 —— 分层处置（2/3 是鸡肋）

| 层 | 结论 | 处置 |
|----|------|------|
| 唤醒词（"小觅" KWS） | 对系统助手无胜算，使用率趋零 | 维持"设置项可选"躺平，不投 iOS，bug 不求完美 |
| 相机语音命令 | 演示炫、实装无人用 | 随相机冻结自然死亡 |
| Chat Push-to-Talk | **非鸡肋**：家庭/长辈场景的自然入口，与 §3.1 楔子咬合 | 保留；评估默认切系统 `SpeechRecognizer`，砍 Sherpa-ONNX 282MB 按需加载（Sherpa 留作离线选项，仅当"全离线模式"成为产品档位时保留战略意义） |

---

## 4. 最大空洞与风险（投入前必须正视）

1. **市场价值未经任何外部信号验证**：所有产品结论均为推演。最便宜的验证：把"人物关系 + 一句话找/发照片"做成完整小闭环，发测试版给 50-100 个真实家庭用户，看留存与付费意愿。
2. **度量断链**：AGENTS.md §6.2 指标（自动修复成功率/文档一致性/AI 代码占比/人工介入率）全部"待收集"——元实验的价值取决于数据，度量自动化应优先于新功能。
3. **AI 高速生成代码的质量未知**：近 30 天 1358 commit，测试密度偏低，隐性 bug/过度设计/死代码未经时间与真实使用检验。
4. **商业化路径空洞**：相册类付费模型≈云存储订阅，端侧路线收不到存储的钱；"AI 整理服务"订阅付费意愿无数据。
5. **iOS parity 债**：每加一个 Android 功能欠一份 iOS 债；iOS 1.0 完成前遵循"功能 > UI > 性能"。

---

## 5. 一句话总结

> **技术上投"面"不投"点"**：capability plane + KMP 编排层（引擎无关接缝）是平台级资产，VLM 打标与美颜引擎是功能级资产随产品走；**产品上 All-in 人物关系楔子**，Chat 做交互层、端侧隐私做叙事、PTT 做长辈入口；**一切推演用一次 50-100 人的真实测试版发版来定价**。

---

## 6. 附录：代码级通用性审查（2026-08-18，五模块源码实证）

> 对 §2 各价值模块逐一做了源码级耦合审查（5 路并行 explore），核心发现：**估值与通用性存在错位**——§2.1 的 Capability Plane 是"设计通用、实现被业务锁死"（需 1-2 周重构才可复用），反而是其下层的 Koog 适配箱与 JS 沙箱"今天就能抽走"。若考虑开源/对外输出，真实优先级应按下表重排。

### 6.1 第一梯队：实现层面就通用，可直接/近乎直接抽取

| 模块 | 内容 | 规模 | 抽取成本 |
|------|------|------|---------|
| **JS 沙箱层**（commonMain `agent/core/js/`） | JsEngine/JsValue/JsBridge/JsRuntime/运行观测；全仓库解耦最干净模块，QuickJS（Android）+ JavaScriptCore（iOS）双引擎已验证同一契约，业务 handler 全在模块外经 `NativeHandler` 注入 | ~460 行 + 190 行通用测试 | ~1 人日（删 `GallerySummaryJs` 相册耦合文件、内联 `LlmCallRecord.cap` 错层依赖、参数化 `device.info` 硬编码） |
| **Koog on KMP 生产级适配箱** | `KoogMessageMemory`（三不变式纯函数，解决 OpenAI 兼容 API 历史裁剪 400）、`poLangSingleRunStrategy`（修 Koog 1.1.1 丢"文本+tool_calls 同帧"缺陷）、`polangSystemPrompt`（绕过 systemPrompt 丢 LLMParams 坑）、Android ServiceLoader 发布缺陷绕过、`ChatMemoryStore` 抽象、LLM 调用观测三件套——**全是真机实证、Koog 官方文档未覆盖的生态级痛点** | ~1500-2000 行 | 复制 + 去包名级 |
| **人脸聚类纯算法族** | `AdaptiveFaceClusterer`（k-NN 图 + 连通分量）+ shared commonMain 5 个纯逻辑文件（小簇合并决策/DBSCAN 精修/攒批策略/聚类配置）；KMP 纯 Kotlin、零平台依赖、带单测 | ~700 行 | 直接可开源 |
| **帧同步机制**（beauty-engine `internal/framesync/`） | `FrameSyncBridge`（时间戳最近邻帧关联，O(logn)）+ `MotionTracker`（速度外推预测 + 双缓冲防 GC）——解决"低频检测对齐高频渲染"通用问题，有独创工程价值 | ~470 行 | 1-2 天（payload 从 106 点人脸泛化为通用关键点集、去进程级单例、offset 索引参数化） |

### 6.2 第二梯队：设计通用、实现被业务锁死（需重构接口层）

- **Capability Plane 机制内核**（`CrossPageCommandQueue` 223 行 + `CommandExecutor` 144 行 + `ExecutionEngine` 403 行 + Registry 场景路由主体，~1000 行）：审查确认 **Koog 原生 ToolRegistry 没有场景路由和端侧命令排队能力**，`CrossPageCommandQueue`（TTL 5min/去重/重试 3 次/页面激活后自动执行）是真实差异化单件。但锁死点明确：`AgentCommand` sealed 词汇表 46 个命令中 34 个是业务命令且 import `beauty.api`（编译期耦合）；`Scene` 枚举写死本 App 五页面；机制层 4 处中文用户文案硬编码（违反自身 [I18N] 红线）；`ExecutionEngine` 的 WaitCondition 塞了人脸检测条件。抽取需命令协议开放化（泛型 payload/接口扩展点）+ Scene 泛化 + AgentContext 拆基础/扩展两层，**1-2 周重构量级**。
- **OpenClGuardian**：降级状态机/黑名单/24h 冷却是纯机制（端侧推理社区缺这类生产级容灾件），但 4 处硬绑 `LocalLlmEngine` 具体类 + `AgentOrchestrator` 单例 + `__ERROR_OPENCL_TIMEOUT__` 字符串哨兵私有协议。抽 `TimedInference`/`KeyValueStore`/backend 切换回调三个接口即可，1-2 天。

### 6.3 第三梯队：业务本体，无通用性

`AgentOrchestrator`（chat/相机/飞书三链路专用组合体）、`ChatToolService`/`CameraToolService`（40+ 业务 @Tool）、`RemoteModelConfig`（产品服务器/供应商硬编码）、三 Pass 打标编排（每条路径 touch 相册专属表结构，抽库成本高于重写精简版）、`FaceClusterEngine` 持久化层（~10 个 DAO 操作 + 人物关系 schema 深织）、`BeautyRenderer`/`FaceMakeupPass` 美颜算法本体（领域库非平台件）。

### 6.4 审查暴露的三个结构性事实

1. **GL/EGL 基础层通用但同质化**：`EGLCore`/`ShaderProgram`/`FramebufferPool` 等 ~1.5k 行质量不错，但 Grafika/GPUImage 早已覆盖该领域，护城河浅，不值得为抽取投入。
2. **beauty-engine "独立模块"名不副实**：`beauty-api` 经 `api(project(":shared"))` 依赖整个 KMP shared（`BeautySettings`/`FilterType` 已迁走）；App 侧 10+ 处穿透 `beauty.internal.*`；api 包反向 import render 包——宣称的契约边界三处均被打破，不可能作为独立 SDK 抽出。不影响冻结决策。
3. **"设计通用 vs 实现通用"的落差是普遍模式**：`PrivacyGuard`（概念通用、关键词表是相册中文）、`CapabilityRegistry`（机制干净、文案面向最终用户）、`ExecutionEngine`（执行器通用、写死 CAMERA 场景）——后续若认真走库化路线，第一步是建立"机制层禁止 import 业务类型"的架构门禁。

### 6.5 修正后的对外输出/开源优先级

> **Koog 适配箱**（生态痛点、最易获外部认可）→ **JS 沙箱**（最干净）→ **聚类算法族**（学术/工程价值）→ **OpenClGuardian**（1-2 天解耦后）→ **Capability Plane 机制内核**（1-2 周重构后，差异化最强但成本最高）。
