# iOS 文档索引（前门）

> **定位**：iOS 端文档的单一入口。2026-08-10 整合后，冗余/历史文档已**删除**（git 历史可恢复），只保留活文档 SSOT。
> **现状**：iOS 冷启动期，**尚无功能完善的稳定版本**。进度以 **origin/main** 为准；未合并分支标「in-flight」。
> **权威进展**：[`IOS_TASK_STATUS.md`](IOS_TASK_STATUS.md) · **整合留痕**：[`../reviews/2026-08-10-ios-doc-consolidation-audit.md`](../reviews/2026-08-10-ios-doc-consolidation-audit.md)。

---

## §1 活文档（现行事实来源，须保持最新）

| 文档 | 职责 |
|---|---|
| [`IOS_TASK_STATUS.md`](IOS_TASK_STATUS.md) | **任务看板**：done/next/won't-do + 进展指标 SSOT |
| [`IOS_PRODUCT_REFERENCE.md`](IOS_PRODUCT_REFERENCE.md) | **产品行为契约**：逐功能 8 子节，以 Android main 代码为准 |
| [`../superpowers/plans/2026-08-07-polang-kmp-ios-transformation.md`](../superpowers/plans/2026-08-07-polang-kmp-ios-transformation.md) | **Phase 路线图**：Phase 1-7 + 决策 + 风险登记 + 变更记录 |
| [`../superpowers/plans/2026-08-10-ios-implementation-tasks.md`](../superpowers/plans/2026-08-10-ios-implementation-tasks.md) | **缺口主排序**：G1-G7 → T0-T11 Wave |
| [`../reviews/2026-08-10-ios-android-consistency-gap.md`](../reviews/2026-08-10-ios-android-consistency-gap.md) | **5 屏 code 级差异审计**（最新；相机项已完成） |
| [`../../specs/PARITY_MASTER_PLAN.md`](../../specs/PARITY_MASTER_PLAN.md) | **Parity 顶层架构**（五层防线） |
| [`../03-TECHNICAL-SPECS/IOS_ANDROID_UI_PARITY.md`](../03-TECHNICAL-SPECS/IOS_ANDROID_UI_PARITY.md) | **Parity 方法论** |
| [`../../specs/README.md`](../../specs/README.md) | **Vibe Coding 流程** |
| [`../../specs/screens/*.yaml`](../../specs/screens/) | **逐屏契约**：camera / gallery-grid / chat / settings / model-download-center |
| [`../superpowers/specs/2026-08-10-ios-follow-command-design.md`](../superpowers/specs/2026-08-10-ios-follow-command-design.md) | **`/ios-follow` 命令设计**（待审批） |
| [`../superpowers/specs/2026-08-10-ios-tag-scan-core-design.md`](../superpowers/specs/2026-08-10-ios-tag-scan-core-design.md) + [`plans/2026-08-10-ios-tag-scan-core.md`](../superpowers/plans/2026-08-10-ios-tag-scan-core.md) | **TAG SP-B 设计+计划**（🔄 in-flight 分支 `feat/ios-tag-scan-core` 待合并，合并后归档） |

---

## §2 已删减（2026-08-10，git 历史可恢复）

> 下列文档的事实已沉淀进上方活文档或代码，原文档删除以消除冗余。需要细节时查 git 历史（`git log -- <path>` 或 `git show <rev>:<path>`）。

- **4 排雷 Spike**（mnn / spm-quickjs / kmp-koog / beauty-metal）→ 结论在路线图 Phase 2
- **Phase 5 骨架**：app-skeleton design + plan、camera-s5-consistency、phase5-task20-21-verification → Phase 5 已交付，代码为现行事实
- **已实现/已完成**：adhoc-distribution-page-design、server-ios-adaptation-audit、product-reference-design、gallery-face-landmark design + plan、chat-phase6.2 plan、ios-ui-parity-spec → 事实在产品参考/看板/代码
- **已过期审计**：08-08 camera / gallery UI gap-analysis → 被 `2026-08-10-ios-android-consistency-gap.md` 取代
- **已吸收**：camera-gallery-gap plan（相册 G1-G4 → 看板 §6.6）、spec-test-gaps（gap 项 → 看板 §6.5）
- **过程产物**：2 份 Phase 5 kickoff 派发（camera-glm / gallery-k3）

---

## §3 真实状态快照（详见看板 §1-§2）

- **Phase 1-5 ✅**；**Phase 6 = 主战场**（功能对齐与发布准备）。
- **TAG**：main 上 Pass1 基建 ✅；Pass2/Pass3/控制页 🔄 in-flight（`feat/ios-tag-scan-core` 18 commits 待合并，Pass3 待真机验证）；MetalGuardian/后台 ❌。
- **相机对齐 ✅**（已合并 main）；**Chat 6.2 ✅**；**设置 6.3 🔄**（剩合规/隐私政策/账号）；**server 6.4 ✅**。
- **代码** ~12800 行 Swift；**i18n** main 239 key × 三语（分支 ~323 待合并）。
