# iOS 文档整合索引（前门）

> **定位**：周五(08-07)→周一(08-10) iOS 冷启动期产生的全部文档的**单一入口**。把 35+ 份文档按「活 / 历史 / 已归档」三类索引，消除"找不到权威来源"与"分不清哪份还有效"。
> **现状**：iOS 冷启动期，**尚无功能完善的稳定版本**。进度状态以 **origin/main** 为准；未合并分支工作标注「in-flight」。
> **权威进展来源**：[`IOS_TASK_STATUS.md`](IOS_TASK_STATUS.md)（任务看板）· 本次整合留痕见 [`../reviews/2026-08-10-ios-doc-consolidation-audit.md`](../reviews/2026-08-10-ios-doc-consolidation-audit.md)。
> **维护**：新增 iOS 文档时归类到下表对应栏；功能落地后把对应设计/计划从「活」迁「历史」。

---

## §1 活文档（Living — 现行事实来源，须保持最新）

| 文档 | 职责 | 维护触发 |
|---|---|---|
| [`IOS_TASK_STATUS.md`](IOS_TASK_STATUS.md) | **任务看板**：done/next/won't-do + 进展指标 SSOT | 每完成 Phase 6 子项 / 缺口落地 / 新漂移 |
| [`IOS_PRODUCT_REFERENCE.md`](IOS_PRODUCT_REFERENCE.md) | **产品行为契约**：逐功能 8 子节模板，以 Android main 代码为准 | 功能行为变更 |
| [`../superpowers/plans/2026-08-07-polang-kmp-ios-transformation.md`](../superpowers/plans/2026-08-07-polang-kmp-ios-transformation.md) | **Phase 路线图**：Phase 1-7 + 决策 + 风险登记 + 变更记录 | Phase 推进 / 风险状态变 |
| [`../03-TECHNICAL-SPECS/IOS_ANDROID_UI_PARITY.md`](../03-TECHNICAL-SPECS/IOS_ANDROID_UI_PARITY.md) | **Parity 方法论**：度量/坐标/系统栏/Back/a11y/深色/RTL | 方法论演进 |
| [`../../specs/PARITY_MASTER_PLAN.md`](../../specs/PARITY_MASTER_PLAN.md) | **Parity 顶层架构**：五层防线 + 子文档索引 | 体系结构变 |
| [`../../specs/README.md`](../../specs/README.md) | **Vibe Coding 流程**：Android→spec→iOS 四步 | 流程变 |
| [`../../specs/screens/*.yaml`](../../specs/screens/) | **逐屏契约**（camera/gallery-grid/chat/settings/model-download-center） | 屏交互定稿 |
| [`../superpowers/plans/2026-08-10-ios-implementation-tasks.md`](../superpowers/plans/2026-08-10-ios-implementation-tasks.md) | **缺口实施主排序**：G1-G7 → T0-T11 Wave | 缺口状态变 |
| [`../superpowers/specs/2026-08-10-ios-follow-command-design.md`](../superpowers/specs/2026-08-10-ios-follow-command-design.md) | **`/ios-follow` 命令设计**（待审批，未入 specs/README 索引） | 审批/实现后入索引 |
| [`../reviews/2026-08-10-ios-android-consistency-gap.md`](../reviews/2026-08-10-ios-android-consistency-gap.md) | **5 屏 code 级差异清单**（最新审计；相机项已完成，相册/设置/聊天大功能建属 Phase 6） | 下轮审计 supersede |

---

## §2 历史文档（Historical — 已结算/已被取代，原地加 📜 横幅，仅供回溯）

> 结论已沉淀进活文档或代码；**勿用于当前规划**。

### 2.1 排雷 Spikes（Phase 2，结论在路线图）
- `../superpowers/specs/2026-08-07-ios-mnn-spike-design.md` — MNN iOS 编译+推理（含补验 A/C ✅、补验 B 暂缓；注：默认 Pass3 已改走 Florence-2，补验 B 仅备选 Qwen3-VL 路径需）
- `../superpowers/specs/2026-08-07-ios-spm-quickjs-spike-design.md` — sentencepiece+QuickJS（GO）
- `../superpowers/specs/2026-08-07-kmp-koog-spike-design.md` — KMP+Koog 端到端（GO）
- `../superpowers/specs/2026-08-08-ios-beauty-metal-spike-design.md` — 美颜 Metal 渲染（GO，量化 shader~1w+宿主~2w）

### 2.2 Phase 5 骨架（已交付）
- `../superpowers/specs/2026-08-08-ios-app-skeleton-design.md` — 骨架设计（S1-S10 决策）
- `../superpowers/plans/2026-08-08-ios-app-skeleton.md` — 骨架实现计划（Task 0-21，已完成）
- `../reviews/2026-08-09-phase5-task20-21-verification.md` — Phase 5 出口验收（PERF+打包）
- `../reviews/2026-08-08-ios-camera-s5-consistency.md` — S5 美颜参数双端一致性自查（已修）

### 2.3 已实现功能的设计+计划（现行事实在看板/产品参考）
- `../superpowers/specs/2026-08-09-ios-gallery-face-landmark-design.md` / `plans/2026-08-09-ios-gallery-face-landmark.md` — 相册人脸关键点（已实现，debug 门控）
- `../superpowers/plans/2026-08-09-ios-chat-phase6.2-plan.md` — Chat 6.2（已完成，缺口在看板 §6.2）
- `../superpowers/specs/2026-08-10-ios-tag-scan-core-design.md` / `plans/2026-08-10-ios-tag-scan-core.md` — TAG SP-B（已实现并扩展到 SP-C/SP-D，in-flight 分支待合并）
- `../superpowers/plans/2026-08-09-ios-camera-gallery-gap.md` — Phase 6.6 切片（相机 C1+zh-Hant 已完成；相册 G1-G4 仍缺口，见看板 §6.6）
- `../superpowers/specs/2026-08-09-ios-spec-test-gaps.md` — spec→UITest gap 清单（部分活：相册项仍准；相机项已完成）
- `../superpowers/specs/2026-08-09-ios-ui-parity-spec.md` — 相机/相册对齐定量合同（相机部分已完成；相册余项并入 yaml）

### 2.4 已被取代 / 已实现的设计
- `../superpowers/specs/2026-08-09-ios-product-reference-design.md` — **已被** `IOS_PRODUCT_REFERENCE.md`（其设计产物）取代
- `../superpowers/specs/2026-08-08-ios-adhoc-distribution-page-design.md` — 已实现（`server/` DownloadRoute）
- `../superpowers/specs/2026-08-08-server-ios-adaptation-audit.md` — Phase 6.4 已完成（5 适配点零阻塞）

### 2.5 过期快照审计（已被新审计 supersede，已加 banner）
- `../reviews/2026-08-08-ios-camera-ui-gap-analysis.md` — 相机 UI gap（08-08 快照，多数 gap 已关；以 `2026-08-10-ios-android-consistency-gap.md` 为准）
- `../reviews/2026-08-08-ios-gallery-ui-gap-analysis.md` — 相册 UI gap（同上）

---

## §3 已归档（`docs/archived/2026-08-ios-coldstart/`）

无人引用的过程产物：
- `ios-camera-glm-kickoff.md` — Phase 5 GLM 实例派发包
- `ios-gallery-k3-kickoff.md` — Phase 5 K3 实例派发包

---

## §4 真实状态快照（详见看板 §1-§2）

- **Phase 1-5 ✅**；**Phase 6 = 主战场**（功能对齐与发布准备）。
- **TAG**：main 上 Pass1 基建 ✅；Pass2/Pass3/控制页 🔄 in-flight（`feat/ios-tag-scan-core` 18 commits 待合并，Pass3 待真机验证）；MetalGuardian/后台 ❌。
- **相机对齐 ✅**（已合并 main）；**Chat 6.2 ✅**；**设置 6.3 🔄**（剩合规/隐私政策/账号）；**server 6.4 ✅**。
- **代码** ~12800 行 Swift；**i18n** main 239 key × 三语（分支 ~323 待合并）。
