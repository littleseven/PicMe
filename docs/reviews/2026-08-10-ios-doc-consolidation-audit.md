# iOS 文档整合审计（2026-08-10）

> **范围**：周五(08-07)→周一(08-10) 期间产生的全部 iOS 文档/spec（35+ 份，跨 `docs/`、`specs/`、`docs/superpowers/{specs,plans}/`、`docs/reviews/`）。
> **目的**：整合、更新进展、消除冗余与歧义。**iOS 当前处于冷启动期，尚无功能完善的稳定版本。**
> **方法**：4 个并行只读子代理分簇审计（① 路线图+parity 基建 / ② TAG+相机 vs git / ③ spike+skeleton+reviews 归档候选 / ④ feature specs+plans+screen yaml）+ 主会话读码核验（`git log`、`iosApp/` 源文件）。
> **前置**：继承 [`2026-08-10-ios-kmp-doc-drift-audit.md`](2026-08-10-ios-kmp-doc-drift-audit.md)（25 处漂移已修）；本次聚焦其后的**进展漂移**与**结构性整合**。
> **两项策略决策（用户拍板）**：
> 1. **跟踪策略**：进度状态以 **origin/main** 为准；未合并分支工作在对应子项标注「in-flight: `<分支>` 待合并」。
> 2. **归档策略**：**删减**——冗余/历史文档直接删除（git 历史保留可恢复），只保留活文档 SSOT + 前门索引 [`IOS_DOC_INDEX.md`](../01-PRODUCT/IOS_DOC_INDEX.md)；in-flight 的 TAG design+plan 暂留至分支合并。共删 19 份。

---

## §1 真实当前状态（iOS 冷启动期，无稳定版）

| 维度 | 真实状态 | 证据 |
|---|---|---|
| **分支拓扑** | `main` = Phase 1-5 ✅ + 相机对齐（已合并）；`feat/ios-tag-scan-core` = **18 commits 未合并**，含 TAG Pass1(接线)/Pass2/Pass3/控制页 | `git log main..feat/ios-tag-scan-core --oneline` |
| **Phase 进度** | 1-5 ✅（agent-core→Koog / iOS spikes / 改名 / shared KMP 抽取 / iOS 骨架+相机管线）；**Phase 6 = 当前主战场**（功能对齐与发布准备） | 路线图 §3、看板 §1 |
| **TAG Pass1（人脸+嵌入+语义）** | ✅ 基建于 main（`25414e12`：`Pass1Pipeline`/`FaceAlignment`/`MobileClipEncoder`/`TagDatabase`）+ 人脸检测可用（MNN 2d106 预归一化修复 + MediaPipe 468→106） | 看板 §6.1 |
| **TAG Pass2（聚类）** | 🔄 **in-flight**：分支 `feat/ios-tag-scan-core` 已实现+单测+真机跑过（`FaceClusterer.swift` k-NN 连通分量，2 clusters/34 embeddings，`7b674428`），**待合并 main** | 分支 commits |
| **TAG Pass3（VLM 打标）** | 🔄 **in-flight**：分支已代码完成+编译过（`Florence2Tagger.swift` ORT 4-session，`869721c3`），**待真机验证**（需下 266MB `florence2_base`）；默认走 Florence-2，**不阻塞于「补验 B/Qwen3-VL」**（那是备选路径） | 分支 commits |
| **TAG 控制页+编排** | 🔄 **in-flight**：分支已建并全开放（`TagScanScreen`/`ViewModel`/`Orchestrator`，`8184b85a`/`9c7bfaba`/`2b06089f`）；**main 上 `Pass1Pipeline` 仍为孤立编排器** | 分支 commits |
| **MetalGuardian / 后台扫描** | ❌ 均未实现（准确，分别推迟 SP-A / iOS ~30s 限制改增量） | `iosApp/` 无源 |
| **相机对齐** | ✅ **已合并 main**（B1 快门 token+黑闪+反馈 `f050d6ea`；B2a-d 右列面板 `262bf406`/`0267b62f`/`19ae5942`/`04b912fa`/`e965445e`） | `git branch --contains` |
| **代码规模** | ~12800 行 Swift（Camera/Gallery/Settings/Person/Chat/Platform） | 看板 §1（准确） |
| **i18n** | **main = 239 key × 三语**（en/zh-Hans/zh-Hant，08-10 D4 校准）；分支含 TAG 50+ 键，合并后将达 **~323** | `Localizable.xcstrings` |

---

## §2 陈旧/歧义清单（本次修正）

> 根因模式：TAG 三处文档互相一致但**联合陈旧**于分支实况；相机/zh-Hant/i18n 计数为「快照后又有进展」。全部已修正。

| # | 文档:原述 | 真实 | 级别 | 修正 |
|---|---|---|---|---|
| D1 | `IOS_TASK_STATUS.md` §6.1：Pass2 ❌「未实现」/ Pass3 ❌「阻塞于补验 B」/ 控制页 ❌「未接线」 | 分支已实现 Pass2+Pass3(代码)+控制页 | 🔴 | 改为 main-based + in-flight 标注（Pass2/3/控制页 🔄 in-flight 分支待合并；Pass3 不阻塞补验 B） |
| D2 | `IOS_PRODUCT_REFERENCE.md` §2.7：iOS 落点「Pass2/Pass3/MetalGuardian/控制页未组装」 | Pass2/3/控制页 分支已组装；仅 MetalGuardian ❌ | 🔴 | 落点改述 + header 日期 08-09→08-10 |
| D3 | 路线图 §6.1 / §6 状态块（修订十六）：「6.1 Pass1 基建已建，端到端未组装」 | 分支已组装 Pass1 接线 + Pass2/3/控制页 | 🔴 | §6.1/§6 状态块刷新 + 新增修订十七登记 |
| D4 | `2026-08-10-ios-implementation-tasks.md` T1/T3/T4：「Create TagScanControlView/FaceClusterer/Florence2Tagger」 | 三者均已在分支存在 | 🟡 | 标 in-flight；T4 注明默认 Florence-2 路径**不依赖 T0**（补验 B 仅备选 Qwen3-VL 路径需） |
| D5 | `2026-08-10-ios-android-consistency-gap.md`：「下一步：相机页」/ §3「DesignTokens 死代码」 | 相机已对齐合并 main，token 已启用 | 🔴 | 「下一步:相机」→「相机已对齐(merged)」；§3 加完成注 |
| D6 | `2026-08-09-ios-spec-test-gaps.md` §一 + `2026-08-09-ios-camera-gallery-gap.md` §1：相机面板(ratio/scene/grid/pro/互斥/composition) ❌ | 已合并 main | 🟡 | 标 ✅ 已实现；相册项保持 |
| D7 | i18n 计数「239」散见看板/路线图/产品参考 | main 239 准确，但分支 ~323 未体现 | 🟡 | 各处补注「分支 ~323 待合并」 |
| D8 | `PARITY_MASTER_PLAN.md` §0/§8 A1：「`[PARITY]` 不在 AGENTS.md，P0 待办」 | **已在 `AGENTS.md` §5:236**；A3（README 反链）也已完成 | 🟡 | §0 行标 ✅ 已落实；§8 A1/A3 标完成；§4.1 chat/settings yaml 标 ✅ 已建 |
| D9 | `PARITY_MASTER_PLAN.md` §3：4 步流程整段抄 `specs/README.md` | 重复 | 🟢 | §3 收敛为交叉引用 README |
| D10 | `camera-gallery-gap.md` §5：zh-Hant「全局缺失」 | 08-10 已补齐 | 🟢 | 标已解决 |

---

## §3 冗余聚类与处置

| 聚类 | 文档（→ canonical） | 处置 |
|---|---|---|
| **A. Parity 框架** | `PARITY_MASTER_PLAN`(架构) / `IOS_ANDROID_UI_PARITY`(方法论) / `specs/README`(流程) / `*.yaml`(契约) | canonical 各司其职；删 MASTER_PLAN §3 与 README 的重复（→ 交叉引用） |
| **B. Phase 6 缺口清单** | 看板 §3 G1-G7（SSOT）/ `implementation-tasks` T0-T11（执行排序，已做 G→T 映射）/ 路线图 §6.6（叙述） | 缺口清单只在看板 §3 一份；implementation-tasks 引用它；路线图 §6.6 改为指针 |
| **C. TAG 6.1 状态** | 看板 §6.1（dashboard SSOT）/ 产品参考 §2.7（产品契约）/ 路线图 §6.1（Phase 叙述） | 一次协调刷新（D1-D3）消除联合陈旧 |
| **D. 进展指标** | LOC / i18n key | 单一来源 = 看板 §1，他处引用 |
| **E. 已结算功能的设计+计划** | gallery 人脸关键点 / Chat 6.2 / TAG SP-B / app-skeleton 等 | 历史 → 横幅（见 §4）；现行事实在看板/产品参考 |

---

## §4 冗余/历史文档删减（git 留史，可恢复）

**已删除 19 份**（事实已沉淀进活文档/代码）：
- 4 spike（mnn / spm-quickjs / kmp-koog / beauty-metal）→ 结论在路线图 Phase 2
- app-skeleton design + plan、camera-s5-consistency、phase5-task20-21-verification → Phase 5 已交付
- adhoc-distribution-page-design、server-ios-adaptation-audit、product-reference-design、gallery-face-landmark design + plan、chat-phase6.2 plan、ios-ui-parity-spec → 已实现/已完成/已被取代
- 08-08 camera / gallery UI gap-analysis → 被 `2026-08-10-ios-android-consistency-gap.md` 取代
- camera-gallery-gap plan（相册 G1-G4 → 看板 §6.6）、spec-test-gaps（gap 项 → 看板 §6.5）→ 已吸收
- 2 份 Phase 5 kickoff 派发 → 过程产物

**暂留**：TAG scan-core design + plan（🔄 in-flight 分支 `feat/ios-tag-scan-core` 待合并；合并后删）。

**无断链**：存活文档对被删文档的引用全部为 backtick 文本提及（非可点击 markdown 链接，已核验），删除不产生断链；提及自然成为 git 历史引用。完整清单见 [`IOS_DOC_INDEX.md`](../01-PRODUCT/IOS_DOC_INDEX.md) §2。

---

## §5 跟踪策略与防漂移

- **状态基准 = origin/main**（稳定/已交付）；未合并分支工作在子项标注「in-flight: `<分支>` 待合并」。此决策写入 `IOS_TASK_STATUS.md` §7。
- **单一进展来源**：看板 §1（指标）+ §2（Phase 6 子项）；路线图承载 Phase 叙述与变更记录；产品参考承载产品行为契约。三者冲突时代码为准。
- **防漂移三招**（延续 drift audit §4）：① 结构层 `check_doc_sync.py` 补 `tmp/`、`iosApp/Pods/` 排除与 leading-slash 解析；② 语义层周期性重扫 iOS 状态标记列对照 `iosApp/`；③ 流程层每次代码落地后 `grep` 全文同义状态表述（防传播遗漏）。
- **分支合并后必做**：`feat/ios-tag-scan-core` 合入 main 时，将看板/产品参考/路线图的 TAG in-flight 标注翻为 ✅，并刷新 i18n 239→323。

---

## §6 核验方法

- 4 子代理逐文档只读比对 + 主会话读码二次核验（`git log`/`git branch --contains`/`iosApp/PoLang/Platform/Tag/*.swift` 存在性/`Localizable.xcstrings` 计数）；
- 每项陈旧附 commit hash 或 `文件:行` 证据；
- `[PARITY]` 红线落位以 `AGENTS.md:236` 实测为准。
