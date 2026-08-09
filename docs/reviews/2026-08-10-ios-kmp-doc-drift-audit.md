# iOS + KMP 文档漂移审计（2026-08-10）

> **范围**：整套 iOS + KMP 文档对照 `iosApp/` / `shared/` / `androidApp/` 代码与 git 实况的全量漂移扫描。
> **方法**：3 个并行只读 subagent 逐文档比对（产品参考 / KMP 路线图 / UI parity + specs + reviews），高严重度项由主会话读码二次核验。
> **前置**：本审计在 `7832df62`（D1–D4 漂移回写）之后进行；D1–D4 不重复收录，但收录其**传播遗漏**（同一事实在别的章节未同步）。
> **结论**：共发现 **25 处可修正漂移** + 2 处需运行时复查 + 若干历史文档（留）。已全部修正（见各 doc 提交）。

---

## §1 漂移总览（按文档）

| 文档 | 高 | 中 | 低 | 处置 |
|---|---|---|---|---|
| `IOS_PRODUCT_REFERENCE.md` | 4 | 5 | 2 | ✅ 全部修正（A1–A11） |
| `2026-08-07-...-kmp-ios-transformation.md`（路线图 §4 风险登记） | 0 | 4 | 3 | ✅ 风险降级/关闭（B1–B7） |
| `IOS_ANDROID_UI_PARITY.md` | 0 | 1 | 2 | ✅ 修正（C1–C3） |
| `specs/2026-08-09-ios-spec-test-gaps.md` | 0 | 1 | 1 | ✅ 修正 D1；D2 留待运行时复查 |
| `specs/2026-08-09-ios-gallery-face-landmark-design.md` | 0 | 1 | 0 | ✅ 修正（D3） |
| `reviews/2026-08-08-ios-camera-ui-gap-analysis.md` | 1(整篇) | — | — | ✅ 加快照 banner（E1） |
| `reviews/2026-08-08-ios-gallery-ui-gap-analysis.md` | 1(整篇) | — | — | ✅ 加快照 banner（E2） |
| `specs/2026-08-08-ios-app-skeleton-design.md` | — | — | — | ⏭️ 留（历史设计提案，无前向漂移） |

---

## §2 漂移明细

### A. `IOS_PRODUCT_REFERENCE.md`（11 处）

| # | 节:行 | 文档声称 | 代码实况（证据） | 级别 | 修正 |
|---|---|---|---|---|---|
| A1 | §4.2:1299 | 场景同步「未调 `onMainPageChanged`，路由恒 UNKNOWN」 | **已接入**：`MainTabView.swift:60-62`(onAppear) + `:63-66`(onChange) 调 `IosAgentComposition.shared.onMainPageChanged` | 高 | 移至 §4.1 已落地 |
| A2 | §2.5:761 | 「场景同步关键 gap：iOS **必须调** `onMainPageChanged`」 | 同 A1，已接入 | 高 | 改述为「已接入，不再为 gap」 |
| A3 | §4.2:1300 | i18n「191 key + 缺 zh-Hant」 | xcstrings **239 key × 三语**（en/zh-Hans/zh-Hant 全就绪）；D4 修了 §3.5/N5 但**漏了 §4.2** | 高 | →「239 key（vs 981，覆盖仍不足）；三语就绪」 |
| A4 | §1.3:166 | TAG「iOS 缺口」 | Pass1 基建已移植（`Pass1Pipeline.swift` 等，`25414e12`） | 高 | →「iOS 部分（Pass1 已移植）」 |
| A5 | §2.7:852,935 | TAG「iOS ❌ 全缺口，Phase 6.1」 | 同 A4；D2 修了路线图 §6.1 但**漏了产品参考 §2.7** | 高 | →「🔄 Pass1 已移植，Pass2/3/MetalGuardian/控制页待组装」 |
| A6 | §1.3:126 | 设置入口「iOS 待对齐」 | 已实现：`GalleryGridView.swift` 顶栏设置按钮 → `SettingsRoot`（`.fullScreenCover`） | 中 | →「iOS 已有」 |
| A7 | §3.1:1152 | 「人物为占位页」 | 人物页 1311 行真实 UI（`02806687`）；D1 修了 §1.3/§2.6/§4.3 但**漏了 §3.1** | 中 | →「人物页 UI 骨架已落地（1311 行）」 |
| A8 | §1.2:116 | 「非真正跟手 Pager——**无横滑手势**」 | 已有 swipe 切页手势：`MainTabView.swift:78-88` `simultaneousGesture(DragGesture)` | 中 | →「已有 swipe 切页（非跟手 drag-tracking）；仍无页面常驻/物理吸附」 |
| A9 | §4.2:1297 | 「iOS 为 ZStack 条件渲染，**无手势**/常驻」 | 同 A8，swipe 手势已有 | 中 | →「有 swipe 手势（非跟手）；仍 ZStack、无常驻」 |
| A10 | §2.1:271 | Phase 5 已落地清单缺 Gallery 人脸关键点 overlay | 已落地：`GalleryFaceDebug.swift` + `MediaPagerView.swift:123-318`（debug 门控，`c6622f42`/`8d4c40ec`） | 低 | 补入已落地清单 |
| A11 | §2.8:1036 | 相机仅提「MediaPipe 468→106 warp」 | 已双引擎：`FaceEngineRouter` + `MnnFaceLandmarkService`（MNN 2d106，`9cb910e1`） | 低 | 补「+ MNN 2d106 双引擎运行时切换」；§1.3:127 二级页状态精确化 |

> **根因模式**：A3/A7/A1/A2 是 `7832df62`（D1–D4）的**传播遗漏**——同一事实只修了一个章节；A5 是 D2 只修路线图未修产品参考。这正是「持续迭代」要解决的：一次漂移修正必须 grep 全文同义表述。

### B. `2026-08-07-...-kmp-ios-transformation.md` 路线图（7 处，集中在 §4 风险登记）

| # | 节:行 | 风险（原级别） | 实况 | 修正 |
|---|---|---|---|---|
| B1 | §4:279 | MNN Metal「未验证/输出全 0」🔴 方案级 | 补验 A 已 PASS（cos≥0.9999），残留 fp16 precision 坑已纳入 6.1 MetalGuardian | 降级 🟡→标「已缓解」 |
| B2 | §4:286 | 「GLSL→MSL 迁移量未知」🟡 | spike 2.4 已量化（shader ~1w + 宿主重写 ~2w）并 GO | 标 ✅ 关闭 |
| B3 | §4:288 | 「server iOS 适配延迟发现」🟡 | Phase 6.4 审计零阻塞，已完成 | 标 ✅ 关闭 |
| B4 | §4:284 | 「Koog 1.x 不匹配」🟡 | iOS 初始化 2.3 验证 + `poLangSingleRunStrategy` 绕过已落 main | 降级 🔵 已缓解 |
| B5 | §4:282 | 「K/N 构建慢」🟡 偏高 | 2.3 实测增量 ~5-6s 可控 | 降级 🔵 |
| B6 | §4:289 | 「repo 改名打断」🔵 | Phase 3.5 已完成（自动重定向） | 标 ✅ 关闭 |
| B7 | line 145 | 「:agent-core 已删除（Phase 6，`1cbe9353`）」易误读为 roadmap Phase 6 | 实为 Koog 迁移内部子 Phase 6 | 括注消歧 |

> 路线图的结构性/事实性声称（37 个 commit hash、删除模块、5 target、Phase 1–5 完成态）**全部核验准确**；漂移仅集中在风险登记的「已解风险未关闭」。

### C. `IOS_ANDROID_UI_PARITY.md`（3 处）

| # | 节:行 | 漂移 | 修正 |
|---|---|---|---|
| C1 | §2.1:93-96 | spec 清单仅列 camera/gallery-grid 两个 yaml | 补 chat/settings/model-download-center，或改「见 `specs/screens/` 目录」 |
| C2 | §4.4:186 | RTL「iOS 待 Phase 5 验证」 | →「iOS 待 Phase 6.x 验证」（Phase 5 已发） |
| C3 | §6/参考来源 | 把 2026-08-08 两份 gap-analysis 当「当前实证」引用 | 加日期标注：快照，多数 gap 已关，勿用于当前规划 |

### D. specs（2 处修正 + 1 处留待复查）

| # | 文档:节 | 漂移 | 修正 |
|---|---|---|---|
| D1 | `spec-test-gaps.md` §三:65 | 人脸关键点叠层「❌ 未实现」 | →「✅ 已实现（debug 门控，`MediaPagerView.swift:123-318`）」；图像理解/OCR 仍灰置 |
| D2 | `spec-test-gaps.md` §四:81 | `media_pager` identifier 覆盖子元素 | identifier 已移至 TabView（`MediaPagerView.swift:57`），子按钮各自有 id；但缺 `children:.contain`，**需 XCUITest 运行时复查**，不盲改 |
| D3 | `gallery-face-landmark-design.md` header | 「状态：待写实现计划」 | →「✅ 已实现（`9cb910e1`/`8d4c40ec`），本文存档」 |

### E. reviews（2 处，整篇快照过期）

| # | 文档 | 实况 | 修正 |
|---|---|---|---|
| E1 | `2026-08-08-ios-camera-ui-gap-analysis.md` | 2026-08-08 快照（`83d70270`）；相机视图层 08-09 大幅重构（B1），~18/25 P0-P2 gap 已关 | 加 header banner：快照日期 + 多数 gap 已关 + 当前以 `2026-08-09-ios-ui-parity-spec.md` 为合同 |
| E2 | `2026-08-08-ios-gallery-ui-gap-analysis.md` | 同上，~16/26 🔴 gap 已关（含选择模式/人脸感知裁切/顶底栏/信息弹窗/空态/缩放翻页） | 同 E1 banner 处理 |

---

## §3 不修正项（附理由）

| 文档/项 | 理由 |
|---|---|
| `specs/2026-08-08-ios-app-skeleton-design.md`（S1–S10 设计决策） | Phase 5 历史设计提案；前向声称是设计边界非「缺失清单」，无误导性漂移 |
| 路线图 §6 变更记录（修订一~十六） | 历史记录（含修订十五旧状态），修订十六已 supersede；改写历史记录会破坏审计链 |
| D2 `media_pager` identifier | 已部分修复（移至 TabView）但缺 `children:.contain`，需 XCUITest a11y dump 运行时确认子 id 是否浮现，不盲改文档 |
| 各 spike 报告（mnn/quickjs/beauty-metal） | 点时验证产物，结论已沉淀进路线图，本身不漂移 |

---

## §4 持续防漂移建议（→ 阶段 B）

1. **结构层**：`scripts/check_doc_sync.py` 的 `EXCLUDED_DIRS` 补 `tmp/`、`iosApp/Pods/`，并修 leading-slash 链接解析，使其作为 PR/CI 廉价门（当前 22 报警全噪声）。
2. **语义层**：周期性（如每周）重跑本审计的轻量版——只扫 iOS 状态标记列（✅/🔄/❌/iOS 缺口/待对齐/未实现/不存在/0 文件）对照 `iosApp/` 代码。语义漂移无法靠脚本，须 LLM。
3. **流程层**：每次 iOS 代码落地后，`grep` 全文同义状态表述（避免 A3/A7/A1 这类传播遗漏）。

---

## §5 核验方法

- 3 subagent 逐文档只读比对（产品参考 20 tool-use / 路线图 10 / parity+specs 28）；
- 高严重度项主会话读码二次核验：`MainTabView.swift`（A1/A2/A8/A9 场景同步 + swipe 手势）、`Pass1Pipeline.swift`（A4/A5 TAG）、xcstrings JSON 解析（A3）、`iosApp/PoLang/Features/Person/` 行数（A7）；
- commit hash `git cat-file -e` 批量验证（路线图 37 个全有效）；
- 漂移只记不臆测，每项附 `文件:行` 证据。
