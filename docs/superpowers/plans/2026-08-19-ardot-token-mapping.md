# JSON↔Ardot Token 双向映射与全量绑定 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** design-tokens.json（SSOT）⇄ Ardot「PoLang Tokens」双向同步工具化 + 五页/IconSet 全量绑定清扫（像素零回归）。

**Architecture:** 扩展 sync-ardot-variables.py（--pull/--check）实现 gen_ardot_payload 的逆变换；gen 脚本补 scopes；审计归并 editor 域 token；按页清扫字面量→变量绑定。

**Tech Stack:** python3（直连 127.0.0.1:50501 MCP）、gen-design-tokens.py、Ardot MCP 工具、screenshot-diff 思路（前后帧像素对比）。

**关键上下文（执行者必读）：**
- 设计文档：`docs/superpowers/specs/2026-08-19-ardot-token-mapping-design.md`（五原则四阶段已签核）
- gen_ardot_payload 正向变换在 `scripts/gen-design-tokens.py:544`（scheme/* 双mode↔colorScheme.dark/light；color/statusColor 单mode；其余域 `域/键` 扁平 FLOAT/COLOR/STRING；typography 三段式）——pull 写它的精确逆
- fetch_variables 返回 valuesByMode 以 **mode ID** 为键（Dark=2:0、Light=79:1），需经集合 modes 列表映射回名
- 画布浮点为 float32（1.600000023841858）→ pull 时归一（round 4 位去尾零）
- 画布类子代理**全新 dispatch 不 resume**（死会话坑）；NO_ADAPTER 30/60/90s+5min 双轮重试；capture_screenshot 不传 fileUrl
- 相机 7 帧为保护态空壳（内容待用户侧恢复），一切操作不碰 Camera 页
- 唯一既有画布库页：IconSet（132:2，spec_sheet=132:4）

---

### Task 1: 同步工具双向化 + gen scopes 修复（纯文件 + 本地脚本）

**Files:**
- Modify: `scripts/sync-ardot-variables.py`（加 --pull/--check）
- Modify: `scripts/gen-design-tokens.py`（gen_ardot_payload scopes + 两脚本头部教义注释）

- [ ] 1.1 gen_ardot_payload：无歧义键发 scopes——`*strokeWidth*→[STROKE_FLOAT]`、`radius/* 与 *radius* 键→[CORNER_RADIUS]`；其余键不设 scope（保持全域可绑）。`--check` 回归（生成物 diff 仅 scopes 字段）
- [ ] 1.2 sync 脚本加 `--pull`：fetch_variables（集合 PoLang Tokens）→ 逆变换回写 design-tokens.json——scheme/* 双 mode → colorScheme.dark/light；color/*、statusColor/* → 同名组；`域/键` → 嵌套组；typography/<role>/<field> → typography 节；保留 `_comment/_version/_updated`（_updated 刷新当日）；画布新增键归入对应域并输出报告；JSON 有而画布无 → 仅报告（--prune 才删）；值变更逐项报告
- [ ] 1.3 加 `--check`：双端对比（JSON→payload vs 画布 fetch 归一后），漂移清单 + exit 1；对当前态首跑产出基线报告（预期漂移=editor/* 画布侧新键 + icon/strokeWidth scope）
- [ ] 1.4 两脚本头部注释更新教义：JSON=SSOT（代码唯一源）/ Ardot=辅助精修面 / --push/--pull/--check 三向用法
- [ ] 1.5 Commit：`feat(tooling): token 双向同步——sync --pull/--check + gen scopes`

### Task 2: 审计归并与 editor token 入库

- [ ] 2.1 跑 `--check` 基线，人工核对漂移清单（editor/* 新键逐个确认语义；icon/strokeWidth scope 确认）
- [ ] 2.2 editor 域定稿：画布既有 editor/* 值优先，§18 提案补缺（canvasDark/gachaBarBg/overlay 三档 alpha/toolRailItemSize/paramTileSize——与 radius/card 等既有键去重），JSON 定稿 + `_comment` 溯源
- [ ] 2.3 `gen-design-tokens.py` → `--check` 绿 → `sync --push` → `--check` 零漂移
- [ ] 2.4 Commit：`feat(design): editor 域 token 入库 + 双向零漂移收口`

### Task 3-7: 绑定清扫（每页一个任务，画布串行）

顺序：Chat(6帧) → Gallery(9帧) → Settings(5+3) → Editor(6帧) → IconSet(sheet)。每任务模板：

- [ ] N.1 前置截图存档（每帧 capture_screenshot，改前基线）
- [ ] N.2 字面量→变量绑定（batch_edit，逐处先比对「字面量 == 变量当前 mode 解析值」再绑）：
  - fill/stroke 字面色 → scheme/*、color/*、statusColor/*、editor/*（含 white@0.06/0.08/0.12 玻璃系 → editor overlay alpha 配白——若为 fills 带 opacity，绑 color 后 opacity 保字面或换 alpha token，以像素零回归为准）
  - 顶栏高 48 字面量 → $topBar/height；title fontSize 17 → $topBar/titleFontSize
  - 字阶匹配 fontSize/lineHeight/letterSpacing → typography/*；fontWeight 字符串 → typography/*/weight（仅当帧文字角色明确对应阶梯）
  - 尺寸/间距/圆角语义匹配 → 对应域 token（icon 22/18、gap 8/12/16、radius 12/24…）
- [ ] N.3 「≥2 处重复且无 token」字面量 → 记入全局清单（不现场散加）
- [ ] N.4 验证：每帧改后截图与 N.1 基线**像素 diff=0**（等值绑定不许有视觉变化）；batch_read 抽查 boundVariables；豁免清单留档（坐标/内容色/派生值/一次性值）
- [ ] N.5 报告：绑定清单（节点→token）、豁免清单、新 token 候选、像素验证结果

### Task 8: 新 token 候选统一入库（汇总 Task 3-7 的 N.3 清单）

- [ ] 8.1 评审候选（去重/命名/归域）→ JSON → codegen → push → 绑定回填
- [ ] 8.2 Commit

### Task 9: 收口

- [ ] 9.1 `gen --check` + `sync --check` 双绿
- [ ] 9.2 refs 重导（相机 7 帧若被覆盖为空图 → 按 HEAD 保护态回填，manifest note 记录）
- [ ] 9.3 `DESIGN_TOKENS_SPEC.md` 补「双向同步」节（三向用法+冲突语义+CI 门禁）；CLAUDE.md Token 工作流行同步
- [ ] 9.4 最终 Commit + 记忆更新（sync 工具用法、绑定覆盖率、豁免清单位置）

## 自审记录
1. 覆盖度：设计文档 A→Task 1-2（check 基线=机器化审计）、B→Task 1/2/8、C→Task 3-7、D→Task 9。✓
2. 无占位符；每步有明确命令/判据。✓
3. 命名一致：--push/--pull/--check、域/键、mode 名 Dark/Light。✓
