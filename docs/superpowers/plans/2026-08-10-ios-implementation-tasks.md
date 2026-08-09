# iOS 功能对齐 — 缺口实施计划（主排序）

> **For agentic workers:** 本计划是**主排序计划**（priority roadmap + 验收点），每个 Epic 是可派发到 worktree / virtual-team 的独立单元。Epic 开工时用 superpowers:writing-plans 产出该 Epic 的细粒度 TDD 子计划（命名 `plans/YYYY-MM-DD-ios-<epic>.md`，对标 `2026-08-09-ios-chat-phase6.2-plan.md` 的 T0–T7 粒度），再用 superpowers:subagent-driven-development 或 executing-plans 执行。
>
> **Goal:** 把 [`IOS_TASK_STATUS.md`](../../01-PRODUCT/IOS_TASK_STATUS.md) §3 的 7 个缺口（G1–G7）按依赖与解锁价值排序，给出可派发、可验收的实施路线。
>
> **Architecture:** 多子系统并行——按「Wave」分波次：Wave 0 解除阻塞项 → Wave 1 关键路径（TAG 组装，解锁最多下游）→ Wave 2 依赖 Wave 1 的功能 → Wave 3 独立可并行。每个 Epic 自包含、有独立验收门。
>
> **Tech Stack:** Swift / SwiftUI / Metal MSL / AVFoundation / MNN.framework（arm64 Metal 后端）/ ONNX Runtime（onnxruntime-objc）/ Koog（:shared commonMain，K/N 消费）/ GRDB.swift（SQLite）/ CoreML。
>
> **构建/验收基线命令**（开发机为 Intel，见记忆 `ios-intel-dev-device-only-build`——模拟器构建会因 MNN.framework 仅 arm64-device 失败；绿构建用 `generic/platform=iOS`）：
> ```bash
> # 绿构建（不连设备）
> xcodebuild -workspace iosApp/PoLang.xcworkspace -scheme PoLang \
>   -destination 'generic/platform=iOS' -configuration Debug build
> # 真机闭环（编译→安装→启动→日志，见 /ios-build-debug、/ios-dev-loop）
> ```
> **全局红线**：`[PRIVACY]` 媒体 100% 端侧（人脸/OCR/分类/打标/VLM 不上云）；`[I18N]` 三语同步（en/zh-Hans/zh-Hant）；`[PERF]` 预览 <100ms / 快门 <50ms。每个 Epic 验收门含这三项自检。

---

## §1 依赖图与波次

```
Wave 0 (gating)        Wave 1 (critical path)        Wave 2 (unlocked)         Wave 3 (parallel)
─────────────          ────────────────────          ───────────────           ─────────────────
T0 补验 B ─────┐       T1 TAG 控制页+编排(组装Pass1)  T5 人物后端(需 T3)        T8 编辑器(G3)
(硬件验证)     │       T2 MetalGuardian              T6 搜索(需 T1/T4)         T9 相机补全(G5)
               │       T3 Pass2 聚类                 T7 Gallery 叠层(需 T1)    T10 设置账号/备份(G6)
               └──────►T4 Pass3 VLM 🔴阻塞本项       T7a 跟手 Pager(独立)      T11 Chat 补全(G7)
                                                       T7b 相机规格 gap
```

**关键解锁链**：T1（组装现有 Pass1 基建到可运行 TAG 控制页）是最高 ROI——它让已建好的 `Pass1Pipeline` 真正可用，且为 T3/T4/T6/T7 铺路。T4（Pass3 VLM）被 T0 硬阻塞。

**派发建议**：Wave 1 内 T1/T2/T3 文件零冲突可真并行（T1=UI/编排、T2=守护进程、T3=聚类算法）；Wave 3 的 T8/T9/T10/T11 互相独立，可四实例并行（对标项目既有并行执行模型，见 plan doc §3.1）。

---

## Wave 0 — 解除阻塞（前置）

### T0: 补验 B — Qwen3-VL-2B 真机验证（gating）

**范围**：iOS 端跑 Qwen3-VL-2B（MNN Metal 后端）真机推理，验证内存峰值 / Metal 算子覆盖 / 首 token 延迟 / precision 档位（必须 `Precision_High`/`Low`，默认 `Normal`=fp16 数值全错，见 plan doc Phase 2.1 补验 A）。失败则 TAG Pass3 VLM 需重开选型（MLX 不支持 iOS、CoreML LLM 支持有限，替代路径极少）。

**验收命令/判据**：
- [ ] 真机单图 VLM 推理产出非空 caption（端侧，不联网）
- [ ] 内存峰值 < 设定阈值（用 Instruments 抓取，写入报告）
- [ ] 首 token < 可接受延迟
- [ ] precision 档位锁定 `Precision_High`，cos≥0.9999 vs CPU 基线
- [ ] 报告落 `docs/superpowers/specs/2026-08-XX-ios-qwen3vl-realspike.md`

**依赖**：无（前置）· **派发**：单人真机 spike（不适合并行）· **风险**：🔴 方案级，失败影响 G1 Pass3 与 G2 搜索标签来源。

---

## Wave 1 — 关键路径（TAG 组装，解锁最多下游）

> 三者文件零冲突，可并行派发。

### T1: TAG 控制页 + 扫描编排（把 Pass1 基建接进可运行 UI）

**范围**：新建 TAG 控制页（对标 Android `TagGenerationControlScreen`），把已建但未接线的 `Pass1Pipeline.swift` 编排进一个可触发/查看进度/中断的扫描流程。**首版只跑 Pass1（人脸检测+embedding+语义）**，Pass2/Pass3 接口预留（T3/T4 填充）。

**文件**：
- Create: `iosApp/PoLang/Features/TagScan/TagScanControlView.swift`（状态机 UI：空闲/扫描中/暂停/完成；进度+ETA+最近消息）
- Create: `iosApp/PoLang/Features/TagScan/TagScanViewModel.swift`（驱动 `Pass1Pipeline`，per-media 任务队列 + 暂停/恢复/取消）
- Create: `iosApp/PoLang/Features/TagScan/TagScanOrchestrator.swift`（会话调度，对标 Android `TagScanOrchestrator`）
- Modify: `iosApp/PoLang/Platform/Pass1Pipeline.swift`（暴露 suspend `runForAsset(_:)` / 进度回调 hook；当前为孤立编排器）
- Modify: `iosApp/PoLang/Features/Settings/SettingsScreen.swift`（设置页「相册功能」加 TAG 控制入口）+ 相册顶栏播放/暂停入口（`GalleryGridView`）
- Test: `iosApp/PoLangTests/TagScanOrchestratorTests.swift`（状态机迁移 IDLE→RUNNING→PAUSED→COMPLETED；纯逻辑 jvmTest 风格，无设备依赖）

**验收命令**：
```bash
xcodebuild -workspace iosApp/PoLang.xcworkspace -scheme PoLang \
  -destination 'generic/platform=iOS' -configuration Debug build   # 绿构建
# 真机：设置→相册功能→TAG 控制→开始扫描，单图 Pass1 完成，persons/face_embeddings 写入 GRDB（TagDatabase 查验非空）
```
- [ ] `Pass1Pipeline` 不再孤立（grep 调用方 ≥1 个 ViewModel）
- [ ] TAG 控制页四态状态机单测通过
- [ ] [PRIVACY] VLM 段在首版为 stub（T4 才接），不联网

**依赖**：无（Pass1 基建已就绪）· **派发**：GLM（Swift/iOS 轨）

### T2: iOS MetalGuardian（替代 OpenClGuardian）

**范围**：iOS 无 OpenCL，需**新设计**（非策略对齐）Metal 推理守护：Metal kernel warmup 超时检测 → Metal→CPU 降级（含模型卸载重载）→ MTLDevice 丢失处理 → 黑名单持久化。对标 Android `OpenClGuardian`（warmup 20s + 单次 30s 超时 + 连续 3 失败降级 + 24h 冷却 + 设备黑名单）。

**文件**：
- Create: `iosApp/PoLang/Platform/MetalGuardian.swift`（`warmup()` / `guardRun(_:)` / `recordFailure()` / 黑名单 UserDefaults 持久化）
- Modify: `iosApp/PoLang/Features/Camera/Beauty/MNN/MnnSelfTest.swift` 及 `MnnFaceLandmarkService.swift`（推理经 Guardian 守护）
- Test: `iosApp/PoLangTests/MetalGuardianTests.swift`（连续失败计数→降级；冷却时间窗；黑名单读写）

**验收命令**：
```bash
xcodebuild ... build   # 绿构建
# 单测：模拟连续 3 次 warmup 超时 → 断言 isMetalDisabled=true；模拟冷却过期 → 断言重新启用
```
- [ ] Guardian 单测覆盖 降级/冷却/黑名单 三路径
- [ ] precision 档位锁定（不使用默认 fp16）

**依赖**：无 · **派发**：GLM

### T3: Pass2 人脸聚类（自适应 k-NN 连通分量）

**范围**：对标 Android `TagGenerationPipeline` Pass2（默认自适应 k-NN 连通分量 Plan B，`preserveNamedPersons` cos≥0.65 保留已命名簇）。输入 `face_embeddings`（R100 512d），输出 `persons` 表 + 回写 `media_assets.faceId`。

**文件**：
- Create: `iosApp/PoLang/Platform/FaceClusterer.swift`（cosine 相似度 + 连通分量；纯数组算法，可 JVM 单测）
- Create: `iosApp/PoLangTests/FaceClustererTests.swift`（已知 embedding 集→期望簇划分）
- Modify: `iosApp/PoLang/Platform/TagDatabase.swift`（`persons` 表 + upsert；GRDB schema migration）
- Modify: `iosApp/PoLang/Features/TagScan/TagScanOrchestrator.swift`（T1 产出，插入 Pass2 阶段）

**验收命令**：
```bash
xcodebuild ... build
# 单测：固定 5 人脸 embedding（同人 cos>0.6）→ 断言聚成预期簇数；命名保留不丢
```
- [ ] 聚类单测通过；`persons` 表写入可查

**依赖**：T1（Orchestrator 容器）· **派发**：K3（算法纯 Kotlin 可先 JVM 验证再迁 Swift，或直接 Swift 单测）

### T4: Pass3 VLM 打标 🔴 阻塞于 T0

**范围**：Florence-2（默认）/ Qwen3-VL-2B（备选）端侧内容打标 + `TagNormalizer` + `ControlledVocab` 规范化 + `LabelSinicizer` 汉化（词表→MT 兜底）→ 写 `media_assets.labelsEn/labelsZh/labels`。**T0 通过方可开工**。

**验收命令**（T0 绿后细化）：
- [ ] 单图 VLM 产出英文 caption → 汉化 → 写三列 labels
- [ ] 全程端侧（[PRIVACY] 自检）

**依赖**：T0 · **派发**：T0 报告结论后定轨（Metal/ORT）

---

## Wave 2 — 依赖 Wave 1 的功能

### T5: 人物后端（关系图谱 / 封面美学 / 事实记忆）

**范围**：UI 骨架已落地（1311 行），**接 shared 后端**——`IosAgentComposition` 注册 `PersonRelationCapability`/`MemoryCapability`；`PersonStore` 消费真实 `persons`/`person_relations`/`memory_facts`；封面美学（NIMA+eDifFIQA 加权，`W_FACE=0.6/W_AESTHETIC=0.4`）；`KinshipLexicon`/`PersonQueryResolver` 下沉 shared commonMain 双端复用。

**文件**：
- Modify: `iosApp/PoLang/Features/Person/PersonStore.swift`（接 GRDB 真实表，替换当前实现）
- Modify: `shared/src/iosMain/.../IosAgentComposition.kt`（注册 2 Capability）
- Create: `shared/src/commonMain/.../person/KinshipLexicon.kt` + `PersonQueryResolver.kt`（从 androidApp 下沉；`java.util.Calendar` 无关）
- Create: `iosApp/PoLang/Platform/CoverSelector.swift`（纯算法，NIMA/eDifFIQA 经 ONNX Runtime/CoreML）

**验收命令**：
- [ ] 人物页显真实聚类封面（非占位）；命名/标关系/标「我」落库可回读
- [ ] 「这是我女儿」chat 命令经 `remember_person_relation` 落 `person_relations`（幂等覆盖）
- [ ] shared commonMain 单测覆盖 KinshipLexicon（无 Android 依赖）

**依赖**：T3（人脸聚类产 persons）· **派发**：K3（shared 下沉）+ GLM（iOS 接线）

### T6: 自然语言搜索（整链路）

**范围**：首版「规则解析 + SQL 召回」（对标 Android `ExplicitFirstSearchPipeline` 退化建议），语义召回（MobileCLIP）与人物关系作后续。`QueryParser`/`QuerySegmenter`/`SearchVocabulary` 为纯 Kotlin 宜下沉 shared commonMain（注意 `java.util.Calendar`→`kotlinx-datetime`，DAO→`expect/actual`）。

**验收命令**：
- [ ] 相册搜索框输入「去年的照片」→ 时间维度召回命中（纯本地，无 LLM）
- [ ] 下沉后的 `QueryParser` commonMain 单测通过（时间解析无 `java.util.Calendar`）

**依赖**：T1（标签索引 labels）/ T4（VLM 标签来源）· **派发**：K3

### T7: Gallery 叠层 + 跟手 Pager + 相机规格 gap（多切片）

**切片**（各自独立可拆子计划）：
- **T7a 跟手横滑 Pager + 4 页常驻**：`MainTabView.swift` 由 ZStack 条件渲染重构为 SwiftUI 等价跟手容器（`TabView`/自定义 offset 手势 + `beyondViewportPageCount` 等价）。验收：4 页横滑跟手 + 切页调 `onMainPageChanged(page)` 同步 SceneManager（否则 Capability 路由恒 UNKNOWN）。**独立，无 Wave 1 依赖，可提前。**
- **T7b 相机规格 gap**：美颜默认值统一 0（勿照搬 FEATURES 的 35/25/…）、滤镜色调/风格**互斥**、快门反馈（触感+音效+80ms 黑场）、十字星时序。**独立。**
- **T7c Gallery 叠层**（依赖 T1/T4）：搜索顶栏激活 / TAG 扫描进度 / 大图页图像理解·OCR·标签叠层 / 拖拽多选 / 视频播放 / 分组菜单。

**验收命令**（T7a 示例）：
- [ ] 横滑切页有跟手动画；非相册页按返回回相册；4 页状态滑走不丢
- [ ] `onMainPageChanged` 在切页时被调用（日志/单测验）

**依赖**：T7a/T7b 无；T7c 依赖 T1/T4 · **派发**：GLM（Swift）

---

## Wave 3 — 独立可并行（不阻塞、不被阻塞）

### T8: 图片编辑器（G3）

**范围**：静态美颜编辑器（复用相机 Metal 管线 off-screen）+ 智能抠图（U2Netp/MODNet/FUSION，ONNX Runtime iOS）+ 证件照（`IDPhotoComposer`/`MaskPostProcessor`/`BackgroundComposer` 纯数组可移植）+ AI 一键优化抽卡（NIMA ONNX + 技术护栏 + 4 候选采样）。`BeautyParams`/`FilterType`/`StyleFilter` 已 commonMain。FBO→Metal MSL。

**验收命令**：
- [ ] 相册详情底栏「编辑」→ 编辑器 5 tab（CROP/ADJUST/BEAUTY/FILTER/MARKUP）可调可存（新文件，非破坏）
- [ ] 抠图 FUSION 逐像素 max 单测通过（纯数组）
- [ ] 全链路 100% 端侧 GPU（[PRIVACY] 自检）

**依赖**：无 · **派发**：GLM（最大单 Epic，建议拆 4 子计划）

### T9: 相机补全（G5）

**范围**：美颜录像（Metal 美颜录制 + 原生降级）/ 十字星完整时序 / 5 风格特效 shader / 语音入口（Sherpa-ONNX iOS 单独实现，默认隐藏）。

**验收命令**：
- [ ] 录像产出带美颜视频存 PHPhotoLibrary；降级路径无美颜可跑
- [ ] [PERF] 预览参数生效 <100ms

**依赖**：无 · **派发**：GLM

### T10: 设置账号 / quota / WiFi 预下载 / 备份恢复（G6）

**范围**：`PoLangAuthClient` 等价层（邮箱验证码登录 + quota 展示 + 删除账号 + 清除访客）+ `X-Platform: ios`（已做）+ WiFi 静默预下载 + 备份恢复（`UIDocumentPicker`，v5 JSON 同 schema）。

**验收命令**：
- [ ] 邮箱发码→验码→token 持久化；quota 卡 `used/limit≥0.9` 转 error 色
- [ ] 备份导出 JSON 可被 Android 恢复（跨端 schema 一致）

**依赖**：6.3① App Store 2.5.2 合规结论（仅 JS 相关项受影响）· **派发**：GLM

### T11: Chat 补全（G7）

**范围**：多会话管理（侧边栏，`chat_sessions`/`chat_messages` 双表）+ 停止生成 UI（调 `cancelCurrent`）+ 补全 `text_reply`/`success`/`error` kind + 反馈 UI（`media_feedback`）+ 图片附件 + JS 画图（`CHART` 渲染）+ AI 优化抽卡。shared 契约就绪，主要是 UI 消费完备性。

**验收命令**：
- [ ] 多会话：新建/切换/重命名/删除（级联清消息/缓存/记忆）
- [ ] 停止生成按钮调用 `cancelCurrent` 且流式终止

**依赖**：6.3①（JS 画图）/ T6（语义搜索）· **派发**：GLM

---

## §2 全局验收门（每个 Epic 合并前）

```bash
# 1. 绿构建（Intel 开发机用 generic/platform=iOS）
xcodebuild -workspace iosApp/PoLang.xcworkspace -scheme PoLang \
  -destination 'generic/platform=iOS' -configuration Debug build
# 2. shared JVM 单测（若 Epic 触及 commonMain 下沉）
./gradlew :shared:jvmTest
# 3. 三红线自检
#   [PRIVACY] grep 新增代码无媒体上传符号（URLSession 上传图片/视频 body）
#   [I18N]    新增 UI 文案已在 Localizable.xcstrings 三语同步
#   [PERF]    相机/美颜相关 Epic：预览<100ms、快门<50ms 真机实测
# 4. 文档同步（三层体系）：IOS_TASK_STATUS.md §3→§2 迁移 + §6 漂移登记
```

---

## §3 优先级建议（若要立即开工）

1. **T7a（跟手 Pager）+ T7b（相机规格 gap）**：零依赖、零阻塞、纯 iOS UI，立刻可派 GLM 并行两实例。
2. **T1（TAG 控制页组装）**：最高 ROI——让已建的 Pass1 基建可用，解锁 T3/T6/T7c。紧接 T2/T3 并行。
3. **T0（补验 B）**：尽早安排真机 spike，解除 T4 阻塞（影响 G1 完整性与 G2 标签来源）。

**建议首批派发**：T7a + T7b + T1（三 worktree 并行，文件零冲突），同时排队 T0 真机验证。

---

## §4 自检（writing-plans self-review）

- **Spec 覆盖**：`IOS_TASK_STATUS.md` §3 的 G1–G7 全部映射到 T1–T11（G1→T1-T4，G2→T6，G3→T8，G4→T5，G5→T9，G6→T10，G7→T11）。
- **占位符扫描**：T4（Pass3）的验收命令标注「T0 绿后细化」——这是**有意的受阻塞项**（依赖硬件验证结论），非占位；其余 Epic 验收点均具体可运行。
- **类型/命名一致**：`Pass1Pipeline`/`TagScanOrchestrator`/`MetalGuardian`/`FaceClusterer`/`CoverSelector`/`PersonStore` 跨 Epic 引用一致；shared 下沉类型 `KinshipLexicon`/`PersonQueryResolver` 与 Android 源同名。
- **粒度声明**：本文件是**主排序计划**，每个 Epic 开工时产出独立 TDD 子计划（见头部派发说明）——符合 writing-plans「多子系统拆分」要求。
