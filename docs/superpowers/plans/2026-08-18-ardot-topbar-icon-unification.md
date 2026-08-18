# Ardot 顶栏与 Icon 体系统一化 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 Ardot 画布建立统一 icon 组件库并重建/改造五页顶栏（细描边线性 1.6 / 22 网格），同步升级 topbar.yaml v2 与 design-tokens，重导 refs 快照。

**Architecture:** 先落 token 与画布变量 → 新建 IconSet 页承载 35 个 icon 组件（SVG path 定义，颜色绑 `scheme/onSurface` 变量）→ 冒烟验证实例行为 → 按 Settings→Chat→Gallery→Editor 顺序改造顶栏（旧 icon 删除、新实例插入、命名/高度/字重修正）→ 从 camera.yaml + git HEAD 快照重建相机 7 帧 → 重写 topbar.yaml v2 → 重导 refs 并与 HEAD 基线比对。

**Tech Stack:** Ardot MCP（batch_edit/batch_read/capture_screenshot/capture_layout/apply_variables）、design-tokens.json + `scripts/gen-design-tokens.py`、`scripts/export-ardot-snapshot.py`、`scripts/screenshot-diff.py`。

**关键上下文（执行者必读）：**
- 设计文档（已签核）：`docs/superpowers/specs/2026-08-18-ardot-topbar-icon-unification-design.md`
- 画布现状有损伤（相机 6 帧空壳、部分 icon 空壳）——本计划 Task 11 重建、Task 7-10 改造时顺带修复
- **画布 node id 会漂移**（曾有并发会话动刀）：每个任务动手前必须用 `batch_read`/`batch_read patterns` 重新确认目标节点 id 与 children 数；对不上即停下报告，不要盲写
- Ardot 已知坑：SVG path 必须**绝对坐标**（小写 `m` 开头不渲染）；auto-layout 子节点 x/y 是派生值；`batch_edit` 每次 ≤25 ops；复制含实例的帧会挂适配器（**本计划只插入实例，禁止复制含实例帧**）
- 变量集：`PoLang Tokens`（id 2:2，modes Dark/Light）；icon 色 = `$:PoLang Tokens:scheme/onSurface`（次要 `$:PoLang Tokens:scheme/onSurfaceVariant`）
- 提交规范：直接提交 main（项目惯例），Conventional Commits，中文描述

---

### Task 0: 开工基线复扫

**Files:** 无文件改动，纯读。

- [ ] **Step 0.1: 全帧 children 基线**

用 Ardot MCP `batch_read` 读取五页全部顶层帧（Camera 6:2 / Gallery 103:1 / Chat 111:319 / Settings 108:1 / Editor 118:104，readDepth 1，只取 name+children 计数），与设计文档 §0.2 盘点表比对。

预期（2026-08-18 19:26 盘点）：
- Camera：idle/panel_beauty_face/panel_ratio/panel_grid/panel_filter/panel_pro children=[]（空壳），focusing=2 个子节点
- Gallery：9 帧结构完好；grid 顶栏 icon_model_center/icon_search 空壳
- Chat：6 帧结构完好；empty 顶栏 ic/bug_report 空壳
- Settings：5 帧 + 3 弹窗结构完好
- Editor：6 帧（current×2 + concept_a×4）结构完好

若与上述不符（说明又有会话动过刀）：**停止，报告用户**，不要开始写操作。

- [ ] **Step 0.2: 记录 refs 基线**

```bash
mkdir -p tmp/ardot-unification/baseline
for f in camera-idle camera-panel_beauty_face camera-panel_ratio camera-panel_grid camera-panel_filter camera-panel_pro camera-focusing; do
  cp specs/screens/refs/ardot/$f.png tmp/ardot-unification/baseline/$f.png
done
ls tmp/ardot-unification/baseline/
```

预期：7 个 PNG（相机重建的视觉基准；这些文件 git 未修改过 = HEAD 态）。

---

### Task 1: Token 与画布变量（icon/strokeWidth）

**Files:**
- Modify: `shared/src/commonMain/resources/design-tokens.json`（icon 节）
- 生成物（自动）: `androidApp/.../designsystem/*.kt`、`iosApp/PoLang/DesignSystem/DesignTokens.swift`、`build/design-tokens/ardot-variables.json`

- [ ] **Step 1.1: design-tokens.json 加 token**

在 `"icon"` 节（现有 `sm/md/lg/xl` 之后）加：

```json
"strokeWidth": 1.6,
```

并把 icon 节的 `_comment` 追加一句：`strokeWidth=统一线性icon描边(2026-08-18 topbar/icon统一化,spec=docs/superpowers/specs/2026-08-18-ardot-topbar-icon-unification-design.md)`。`_updated` 改为当天日期。

- [ ] **Step 1.2: 跑 codegen 并验证**

```bash
python3 scripts/gen-design-tokens.py
python3 scripts/gen-design-tokens.py --check && echo CODEGEN-OK
```

预期：第二次输出 CODEGEN-OK（exit 0）；`git diff` 中 DesignTokens.kt / DesignTokens.swift 出现 icon 新常量（如 `iconStrokeWidth` / `Icon.strokeWidth` 命名按既有扁平规则）。

- [ ] **Step 1.3: 画布变量同步（只加这一个，merge 模式）**

Ardot MCP `apply_variables`：

```json
{"variables": {"PoLang Tokens": {"variables": {"icon/strokeWidth": {"type": "FLOAT", "value": 1.6, "scopes": ["STROKE_FLOAT"]}}}}}
```

注意：**不带 `replace: true`**（防删既有变量/mode）。

- [ ] **Step 1.4: Commit**

```bash
git add shared/src/commonMain/resources/design-tokens.json androidApp/src/main/java/com/mamba/picme/core/designsystem/ iosApp/PoLang/DesignSystem/DesignTokens.swift build/design-tokens/ardot-variables.json 2>/dev/null
git commit -m "feat(design): icon/strokeWidth token 入库——topbar/icon 统一化第一步

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

（build/ 若被 gitignore 则 add 时自然跳过，无妨。）

---

### Task 2: IconSet 页 + spec-sheet 骨架

**Files:** 无 git 文件（画布操作）。

- [ ] **Step 2.1: 建页与骨架**

`create_new_page`（name: `IconSet`）。对返回的 pageId 调 `locate_available_space`（width 900, height 1400）。然后 `batch_edit`：

```javascript
sheet=I("<pageId>", {type:"frame", name:"icon/spec_sheet", layout:"vertical", width:860, height:"hug_contents",
  padding:{left:40,right:40,top:32,bottom:40}, gap:24, fills:[], x:0, y:0})
title=I(sheet, {type:"text", name:"sheet_title", content:"PoLang Icon Set — 统一线性图标 · 22网格 · 描边1.6 · 圆头",
  fontSize:20, fontName:{family:"Sarasa Gothic SC", style:"Semi Bold"}, fill:"$:PoLang Tokens:scheme/onSurface", width:"fill_container"})
sub=I(sheet, {type:"text", name:"sheet_subtitle", content:"Android = Material Symbols Rounded Outlined w400 · iOS = SF Symbols regular · 字形允许平台差异，笔画重量必须对齐",
  fontSize:11, fontName:{family:"Sarasa Gothic SC", style:"Regular"}, fill:"$:PoLang Tokens:scheme/onSurfaceVariant", width:"fill_container"})
```

- [ ] **Step 2.2: 五个分组区骨架**

```javascript
g1=I(sheet, {type:"frame", name:"group_nav", layout:"horizontal", width:"fill_container", height:"hug_contents", gap:20, fills:[]})
g2=I(sheet, {type:"frame", name:"group_gallery_chat", layout:"horizontal", width:"fill_container", height:"hug_contents", gap:20, fills:[]})
g3=I(sheet, {type:"frame", name:"group_tab_editor", layout:"horizontal", width:"fill_container", height:"hug_contents", gap:20, fills:[]})
g4=I(sheet, {type:"frame", name:"group_editor_actions", layout:"horizontal", width:"fill_container", height:"hug_contents", gap:20, fills:[]})
g5=I(sheet, {type:"frame", name:"group_camera_info", layout:"horizontal", width:"fill_container", height:"hug_contents", gap:20, fills:[]})
```

- [ ] **Step 2.3: 验证**

`capture_screenshot(nodeIds:[sheet])` → 空分组+标题可见即过。

---

### Task 3: Icon 组件落库 · 批 1（导航 7 + 相册 3 + chat 4 = 14 个）

**SVG 统一模板**：`<svg width="22" height="22" viewBox="0 0 22 22" fill="none" xmlns="http://www.w3.org/2000/svg"><path d="<PATH>" stroke="#EDE9E3" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/></svg>`
（`#EDE9E3` = onSurface Dark 模式占位色，落库后 Step 3.2 统一绑变量。）

**PATH 数据（批 1，14 个）：**

| name | d |
|---|---|
| ic/back | `M13.5 5 L8 11 L13.5 17` |
| ic/menu | `M4 7.3 H18 M4 11 H18 M4 14.7 H18` |
| ic/search | `M4.8 10.2 A5.4 5.4 0 1 1 15.6 10.2 A5.4 5.4 0 1 1 4.8 10.2 M13.9 13.9 L17.8 17.8` |
| ic/close | `M5.8 5.8 L16.2 16.2 M16.2 5.8 L5.8 16.2` |
| ic/more_vert | `M11 5.5 v.01 M11 11 v.01 M11 16.5 v.01` |
| ic/check | `M4.5 11.5 L9 16 L17.5 6.8` |
| ic/clear | `M4.5 11 A6.5 6.5 0 1 1 17.5 11 A6.5 6.5 0 1 1 4.5 11 M8.7 8.7 L13.3 13.3 M13.3 8.7 L8.7 13.3` |
| ic/scan | `M4 8 V6.5 Q4 4 6.5 4 H8 M14 4 H15.5 Q18 4 18 6.5 V8 M18 14 V15.5 Q18 18 15.5 18 H14 M8 18 H6.5 Q4 18 4 15.5 V14 M7.8 11 H14.2` |
| ic/sort | `M7 4.8 V16.6 M4.4 14.2 L7 16.8 L9.6 14.2 M15 17.2 V5.4 M12.4 7.8 L15 5.2 L17.6 7.8` |
| ic/settings | `M7 11 A4 4 0 1 1 15 11 A4 4 0 1 1 7 11 M16.2 11 H18.4 M14.7 14.7 L16.2 16.2 M11 16.2 V18.4 M7.3 14.7 L5.8 16.2 M5.8 11 H3.6 M7.3 7.3 L5.8 5.8 M11 5.8 V3.6 M14.7 7.3 L16.2 5.8` |
| ic/model_center | `M11 3.6 L18 7.4 V14.6 L11 18.4 L4 14.6 V7.4 Z M4 7.4 L11 11.2 L18 7.4 M11 11.2 V18.4` |
| ic/bug_report | `M7.4 12 A3.6 4.6 0 1 0 14.6 12 A3.6 4.6 0 1 0 7.4 12 M8.6 7 Q9.6 5.2 11 5.2 Q12.4 5.2 13.4 7 M11 7.4 V16.6 M7.4 9.6 L4.6 8.4 M7.4 12.4 L4.2 12.4 M7.6 14.8 L5 16.6 M14.6 9.6 L17.4 8.4 M14.6 12.4 L17.8 12.4 M14.4 14.8 L17 16.6` |
| ic/add_comment | `M7.2 4 H14.8 Q18 4 18 7.2 V11.8 Q18 15 14.8 15 H11 L7.2 17.8 V14.6 Q4 14 4 11.8 V7.2 Q4 4 7.2 4 Z M11 7.2 V11.8 M8.7 9.5 H13.3` |
| ic/delete_sweep | `M5.2 6.6 H15.2 M9 4.6 H11.4 M6.6 6.6 V15.6 Q6.6 17.4 8.4 17.4 H12.2 M15.2 6.6 V10 M9.4 9.6 V14.4 M11.8 9.6 V14.4 M17.4 10.6 H20.6 M17.4 13.6 H20.6` |

- [ ] **Step 3.1: 插入 14 个组件 + 名称标签**

对每个 icon（14 次操作，g1 放导航 7 个、g2 放相册 3 + chat 4）：

```javascript
c1=I("<groupId>", {type:"component", name:"ic/back", width:22, height:22, layout:"none", fills:[],
  svg:"<svg width=\"22\" height=\"22\" viewBox=\"0 0 22 22\" fill=\"none\" xmlns=\"http://www.w3.org/2000/svg\"><path d=\"M13.5 5 L8 11 L13.5 17\" stroke=\"#EDE9E3\" stroke-width=\"1.6\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/></svg>", x:0, y:0})
l1=I("<groupId>", {type:"text", name:"lbl_ic/back", content:"back\nchevron.left\nKeyboardArrowLeft", fontSize:9,
  fontName:{family:"Inter", style:"Regular"}, fill:"$:PoLang Tokens:scheme/onSurfaceVariant", width:"hug_contents", height:"hug_contents"})
```

（每个 icon 后跟一个三行标签：语义名 / iOS 名 / Android 名，竖排 `fontSize:9`。全部 14 组 = 28 ops，分两个 `batch_edit` 调用：7 组 + 7 组。）

- [ ] **Step 3.2: 颜色绑变量**

`batch_read` 取每个组件的 vector 子节点 id，然后对每个 vector 子节点 `U(<vectorId>, {strokes: [{type:"SOLID", color: "$:PoLang Tokens:scheme/onSurface"}]})`（14 个组件 ≈ 14-20 个 U，两个 `batch_edit` 调用）。

- [ ] **Step 3.3: 截图验证 + 修形**

`capture_screenshot(nodeIds:[g1,g2])`，逐个目检：字形居中、无越界（22 网格内）、描边均匀、可读性。发现畸形的 icon 当场改 path 再验证（这是设计资产，**不允许带病过批**）。

---

### Task 4: Icon 组件落库 · 批 2（tab 4 + 编辑轨 7 + 编辑动作 3 = 14 个）

同 Task 3 模板。g3 放 tab 4 + 编辑轨 7，g4 放编辑动作 3。

**PATH 数据（批 2）：**

| name | d |
|---|---|
| ic/camera | `M7.6 6.4 L8.6 4.8 H13.4 L14.4 6.4 M5.8 6.4 H16.2 Q18.4 6.4 18.4 8.6 V15 Q18.4 17.2 16.2 17.2 H5.8 Q3.6 17.2 3.6 15 V8.6 Q3.6 6.4 5.8 6.4 Z M7.9 11.8 A3.1 3.1 0 1 0 14.1 11.8 A3.1 3.1 0 1 0 7.9 11.8` |
| ic/chat | `M3.6 10.6 A7.4 6.4 0 1 0 18.4 10.6 A7.4 6.4 0 1 0 3.6 10.6 M8.2 16.4 L7.2 19.4 L11 17.6` |
| ic/tag | `M3.8 6.4 H13.2 L18.2 11 L13.2 15.6 H3.8 Z M7.2 11 v.01` |
| ic/people | `M5.2 9 A2.7 2.7 0 1 0 10.6 9 A2.7 2.7 0 1 0 5.2 9 M3.4 17.4 Q3.4 12.8 7.9 12.8 Q12.4 12.8 12.4 17.4 M13.6 9.4 A2.3 2.3 0 1 0 18.2 9.4 A2.3 2.3 0 1 0 13.6 9.4 M12.6 17.4 Q12.6 13.6 15.9 13.6 Q18.6 13.6 19.2 16.2` |
| ic/crop | `M4.6 4.6 V14.6 Q4.6 16.6 6.6 16.6 H16.6 M5.4 7.2 H15 Q17 7.2 17 9.2 V19.4` |
| ic/tune | `M3.8 6.2 H18.2 M3.8 11 H18.2 M3.8 15.8 H18.2 M6.5 6.2 A1.7 1.7 0 1 0 9.9 6.2 A1.7 1.7 0 1 0 6.5 6.2 M12.1 11 A1.7 1.7 0 1 0 15.5 11 A1.7 1.7 0 1 0 12.1 11 M4.7 15.8 A1.7 1.7 0 1 0 8.1 15.8 A1.7 1.7 0 1 0 4.7 15.8` |
| ic/face | `M3.6 11 A7.4 7.4 0 1 0 18.4 11 A7.4 7.4 0 1 0 3.6 11 M8.4 9.4 v.01 M13.6 9.4 v.01 M7.6 13 Q11 16 14.4 13` |
| ic/filter | `M11 3.8 L12.5 9.5 L18.2 11 L12.5 12.5 L11 18.2 L9.5 12.5 L3.8 11 L9.5 9.5 Z M17.2 3.6 V6.4 M15.8 5 H18.6` |
| ic/brush | `M18.6 3.4 L14.2 7.8 M14.2 7.8 L9.6 9 L6.4 13.4 Q5 15.6 3.8 18.2 Q6.4 17 8.6 15.6 L13 12.4 Z` |
| ic/erase | `M5.6 14.2 L11.4 5.6 L16.4 8.9 L10.6 17.5 Z M10.6 17.5 H18` |
| ic/auto_fix | `M4.4 17.6 L13.2 8.8 M16.8 3.4 V6.8 M15.1 5.1 H18.5 M18.8 9.4 V11.4 M17.8 10.4 H19.8` |
| ic/undo | `M14.8 16.4 V11.2 Q14.8 7.8 11.4 7.8 H6.2 M9 4.8 L6 7.8 L9 10.8` |
| ic/redo | `M7.2 16.4 V11.2 Q7.2 7.8 10.6 7.8 H15.8 M13 4.8 L16 7.8 L13 10.8` |
| ic/cutout | `M3.6 16.4 A1.7 1.7 0 1 0 7 16.4 A1.7 1.7 0 1 0 3.6 16.4 M3.6 5.6 A1.7 1.7 0 1 0 7 5.6 A1.7 1.7 0 1 0 3.6 5.6 M6.5 14.6 L17.8 4.2 M6.5 7.4 L17.8 17.8` |

- [ ] **Step 4.1-4.3:** 同 Task 3（插入+标签 → 绑变量 → 截图验证修形）。

---

### Task 5: Icon 组件落库 · 批 3（相机 5 + 信息页 2 = 7 个）+ spec-sheet 收尾

g5 放相机 5 + 信息页 2。

**PATH 数据（批 3）：**

| name | d |
|---|---|
| ic/flash | `M12.6 3.4 L6.2 12.6 H10.4 L9.4 18.6 L15.8 9.4 H11.6 Z` |
| ic/flip | `M6.2 8.6 A6.3 6.3 0 0 1 16.9 9.8 M17.4 6.2 L17 9.9 L13.5 9.3 M15.8 13.4 A6.3 6.3 0 0 1 5.1 12.2 M4.6 15.8 L5 12.1 L8.5 12.7` |
| ic/grid | `M3.8 5.6 Q3.8 3.8 5.6 3.8 H16.4 Q18.2 3.8 18.2 5.6 V16.4 Q18.2 18.2 16.4 18.2 H5.6 Q3.8 18.2 3.8 16.4 Z M3.8 8.7 H18.2 M3.8 13.3 H18.2 M8.7 3.8 V18.2 M13.3 3.8 V18.2` |
| ic/ratio | `M3.8 8.2 V5.4 Q3.8 3.8 5.4 3.8 H8.2 M13.8 3.8 H16.6 Q18.2 3.8 18.2 5.4 V8.2 M18.2 13.8 V16.6 Q18.2 18.2 16.6 18.2 H13.8 M8.2 18.2 H5.4 Q3.8 18.2 3.8 16.6 V13.8` |
| ic/pro | `M4 15.2 A7 7 0 1 1 18 15.2 M11 15.2 L15 9.4 M11 15.2 v.01` |
| ic/share | `M7.4 10.8 V16.4 Q7.4 17.6 8.6 17.6 H13.4 Q14.6 17.6 14.6 16.4 V10.8 M11 3.6 V13.4 M8 6.4 L11 3.4 L14 6.4` |
| ic/favorite | `M11 17.8 Q4.6 13.6 3.6 9.4 Q3 6.6 5 5.2 Q6.8 4 8.6 5 Q10 5.8 11 7.2 Q12 5.8 13.4 5 Q15.2 4 17 5.2 Q19 6.6 18.4 9.4 Q17.4 13.6 11 17.8 Z` |

- [ ] **Step 5.1-5.3:** 同 Task 3 模板（插入+标签 → 绑变量 → 截图验证修形）。
- [ ] **Step 5.4: spec-sheet 全景截图**

`capture_screenshot(nodeIds:[sheet])` → 35 个组件 + 分组 + 标签完整可见。这份截图就是双端实现对照表。

---

### Task 6: 实例冒烟验证（settings/main_list）

**Files:** 无 git 文件。

- [ ] **Step 6.1: 定位并替换**

`batch_read(["108:95"])` 确认 top_bar 与 icon_back（108:96）仍在。然后 `batch_edit`：

```javascript
newic=I("108:95", {type:"ref", ref:"<ic/back组件id>", name:"ic/back"})
// 移到首位（在 title 之前）——用 move 操作把 newic 移到 index 0；若无 move 语法则删除 title 后重插（顺序：back, title）
D("108:96")   // 删旧 icon_back
U("108:98", {fontName: {family:"Sarasa Gothic SC", style:"Medium"}})  // 标题 Semi Bold → Medium
```

- [ ] **Step 6.2: 验证**

`capture_layout(parentId:"108:95", maxDepth:1)` → back 实例 x≈8 y≈13 22×22；`capture_screenshot(["108:94"])` → chevron 线性字形正确显示。

**判定门**：实例渲染正常（可见、位置对、颜色随变量）→ 继续后续任务。实例不渲染/挂适配器 → **触发降级方案**：后续任务不再用 `I(ref)`，改为把组件的 SVG path 直接散画进各帧（path 数据沿用 Task 3-5），并在最终报告记录降级原因。

- [ ] **Step 6.3: 顺带完成 settings/main_list 之外暂不提交**（画布无 git，无需 commit）。

---

### Task 7: Settings 族改造（4 帧剩余）

**目标帧**: settings/local_models、settings/remote_models、settings/sandbox、settings/developer 的 top_bar。

- [ ] **Step 7.1: 逐帧定位**

`batch_read patterns [{name:"icon_back"},{name:"top_bar"}] parentId:"108:1" searchDepth:4` 取全部 icon_back id 与 title id。

- [ ] **Step 7.2: 逐帧替换（每帧一个 batch_edit，≤25 ops）**

对每帧：`I(topBarId, {type:"ref", ref:"<ic/back组件id>", name:"ic/back"})` + move 到首位 + `D(旧icon_back)` + `U(title, {fontName:{family:"Sarasa Gothic SC", style:"Medium"}})`。

- [ ] **Step 7.3: 验证**

`capture_screenshot` 四帧 → 顶栏 back 均为线性 chevron、标题 Medium。命名检查：`batch_read` 确认帧内已无 `icon_back` 散画节点。

---

### Task 8: Chat 6 帧改造

**目标帧**: chat/empty(111:321)、conversation(111:383)、sidebar(111:492)、empty_light(116:72)、conversation_light(116:150)、sidebar_light(116:26)。

- [ ] **Step 8.1: 定位**

`batch_read patterns [{name:"TopBar"},{name:"ic/"}] parentId:"111:319" searchDepth:4`（light 三帧在 116:* 下，一并取）。

- [ ] **Step 8.2: 逐帧改造（每帧一个 batch_edit）**

1. `U(topBarId, {name:"top_bar"})`（TopBar→top_bar）
2. 删旧 `ic/back`、`ic/menu`、`ic/bug_report`、`ic/add_comment`、`ic/delete_sweep` 散画节点（sidebar 帧无顶栏则跳过——sidebar 只有 SidebarPanel，先读确认）
3. 按**原 children 顺序**插入 5 个实例（back、menu 在 spacer 前；bug_report、add_comment、delete_sweep 在 spacer 后）——顺序错误会导致 flex 排布错乱，插完必须 `capture_layout` 复核
4. conversation 帧若有标题文字则 `U` Medium

- [ ] **Step 8.3: 验证**

`capture_screenshot` 6 帧（light 帧验证图标颜色随 Light mode 变量翻转——若 light 帧实例仍显示深色，检查该帧 variableModes 继承）。

---

### Task 9: Gallery 9 帧改造

**目标帧**: grid、empty、search、search_no_result、scanning、sort_menu、selection、settings、info。

- [ ] **Step 9.1: 定位**

`batch_read patterns [{name:"icon_"},{name:"top_bar"},{name:"TopBar"},{name:"title_bar"},{name:"floating_tab"},{name:"tab_"}] parentId:"103:1" searchDepth:5`。

- [ ] **Step 9.2: grid 帧（105:45）**

`I("105:46")` 按序插 5 实例（model_center、scan、search、sort、settings）→ 删 5 个旧 icon frame（含 2 个空壳）→ `U("105:47", {fontName:{family:"Sarasa Gothic SC", style:"Medium"}})` → floating_tab(105:68) 内 tab_camera/tab_chat/tab_tag/tab_people 的图标换 ic/camera、ic/chat、ic/tag、ic/people 实例（先 `batch_read` 取 tab 内图标节点）。

- [ ] **Step 9.3: empty/search/search_no_result/scanning/sort_menu/selection 六帧**

同模式：顶栏（top_bar/search_top_bar）内 icon 全换实例 + `search_top_bar` 改名 `top_bar` + floating_tab 图标换实例。search 帧搜索框内 16dp 小图标（search/clear）不动尺寸语义，用 `I` 后 `U(w:16,h:16)` 缩放实例。

- [ ] **Step 9.4: settings 帧（118:1）**

`U("118:2", {name:"top_bar"})` + 内部 icon 换实例（先读确认有哪些）。

- [ ] **Step 9.5: info 帧（105:1）修正**

`U("105:2", {name:"top_bar", height:48})`（title_bar 44→48 + 改名）；内部若有动作 icon 一并换实例。**注意**：info 帧下游元素 y 坐标若依赖 44 需顺移 4（`capture_layout problemsOnly:true` 检查重叠）。

- [ ] **Step 9.6: 验证**

`capture_screenshot` 9 帧 + `capture_layout problemsOnly:true` 全页（无重叠/溢出）。

---

### Task 10: Editor 6 帧改造

**目标帧**: current_crop(118:105)、current_adjust(118:165)、concept_a_hypic(118:243)、concept_a_adjust(118:372)、concept_a_crop(118:480)、concept_a_beauty(118:583)。

- [ ] **Step 10.1: 现状两帧**

每帧：`D(status_time)` + `D(status_icons)`（去假状态栏）；top_back/act_cutout_disabled/act_ai_optimize/act_undo_disabled/act_redo_disabled/act_done 换 `ic/back`/`ic/cutout`/`ic/auto_fix`/`ic/undo`/`ic/redo`/`ic/check` 实例；`top_title` 字重统一 Medium。

- [ ] **Step 10.2: 方案 A 四帧**

每帧：`D(status_time)` + `D(status_icons)`；top_close 内 18×18 的 `ic` 子帧换 `ic/close` 实例并 `U(w:18,h:18)`；top_undo/top_redo 换实例；rail_crop/rail_adjust/rail_beauty/rail_filter_sel/rail_markup/rail_erase/rail_ai 内 `ic` 换 `ic/crop`/`ic/tune`/`ic/face`/`ic/filter`/`ic/brush`/`ic/erase`/`ic/auto_fix` 实例。保存胶囊（top_save 文字钮）不动。

- [ ] **Step 10.3: 验证**

`capture_screenshot` 6 帧 + 帧顶部无 9:41/信号图标；图标全部线性风格。

---

### Task 11: Camera 7 帧重建（最重任务）

**输入**: `specs/screens/camera.yaml`（1148 行，几何 SSOT——头部 ①-⑪ 定稿注释 + elements 树）+ `tmp/ardot-unification/baseline/camera-*.png`（视觉基准）。

- [ ] **Step 11.1: 通读 camera.yaml**

完整读一遍 camera.yaml（elements 树 + ①-⑪ 注释）。重建原则：**布局结构零容差、尺寸 ±2、icon 换新体系（预期差异）、取景区用渐变占位**（不用 imageHash 图片，避免跨节点不渲染坑）。

- [ ] **Step 11.2: camera/idle 骨架**

`locate_available_space`（在 Camera 页空帧原位直接填充——帧本体 118:1146 已存在，直接往里 I）。按 camera.yaml `root`(zstack 全屏黑底) + `top_left_controls` + `top_tool_bar`(胶囊行：美颜/比例/辅助线/滤镜/专业，chip 34 高/13sp/间距10/paddingH14) + 取景区渐变 + 底部控制区三行（模式行→变焦胶囊行→快门行，快门底距 60dp，行距 20/28）+ back 融入工具栏行（icon 中心 x=28、距美颜胶囊 8dp）。每完成一节 `capture_screenshot` 对照 baseline PNG。

- [ ] **Step 11.3: panel_beauty_face**

= idle 全量 + 美颜抽屉（圆角 24/高 0.4/黑渐变 scrim/底部 icon-only Tab/滑杆结构按 camera.yaml ⑪；accent = camera/cameraAccent）。从 idle 复制内容是被禁止的（含实例）——**整帧重新构建**，逐节 I。

- [ ] **Step 11.4: panel_ratio / panel_grid**

= idle + 内联面板（圆角 16/#1C1A1F@72%/宽=屏宽−56）。

- [ ] **Step 11.5: panel_filter**

= idle + 滤镜面板（5 列圆形缩略图 + accent 选中态，camera.yaml ⑨）。

- [ ] **Step 11.6: panel_pro**

= idle + Pro 面板（六修三段式：WB 胶囊行 → 0.5 分隔线 → 滑杆组，camera.yaml ⑩）。

- [ ] **Step 11.7: focusing**

= idle + 对焦环（focusRing 100dp/3dp 描边/圆角 20/十字 16）。

- [ ] **Step 11.8: 全帧验收**

对 7 帧逐个 `capture_screenshot`，与 `tmp/ardot-unification/baseline/` PNG 并排目检（布局结构、间距节奏、胶囊/快门/面板位置）；`capture_layout problemsOnly:true` 全帧无重叠。

- [ ] **Step 11.9: 中期快照保护（防再丢）**

```bash
python3 scripts/export-ardot-snapshot.py
git add specs/screens/refs/ardot/ && git commit -m "chore(ardot): 相机页重建 + icon 实例化中期快照

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

（把重建成果立刻固化入库，避免重蹈今天的内容丢失。）

---

### Task 12: topbar.yaml v2 重写 + 页面 yaml 去重

**Files:**
- Rewrite: `specs/screens/topbar.yaml`
- Modify: `specs/screens/chat.yaml`（top_bar 节）、`specs/screens/gallery-grid.yaml`（top_bar / search_top_bar 节）
- Modify: `specs/screens/camera.yaml`（头部加 1 行附录 E 指针）

- [ ] **Step 12.1: topbar.yaml 全量替换为以下内容**

```yaml
# ============================================================================
# 全局顶栏统一规范 v2（Unified TopBar Spec）— 2026-08-18 设立 / 同日 v2 五页全覆盖
# ============================================================================
# v2 变更：纳入 chat 图标行（变体 D）；新增相机/编辑器附录；标题字重统一 Medium；
#         禁手画状态栏；icon 一律 ic/* 组件实例（IconSet 页，22网格/描边1.6）。
# 设计依据：docs/superpowers/specs/2026-08-18-ardot-topbar-icon-unification-design.md
#
# 双端 SSOT：
#   Android 实现 = AppTopBar.kt（features/common/topbar/，自建 Row）
#   iOS 实现     = SwiftUI 顶栏（读本 spec + DesignTokens.swift）
#   设计稿几何   = Ardot canvas 各帧 top_bar（400×890 帧内坐标）
# 本文件为规格源；实现与设计稿向此对齐，发现漂移先改这里。

topbar:
  height: 48                       # dp（帧内 48px）；token topBar.height
  layout:
    type: horizontal_row
    vertical_alignment: center
    padding: { start: 8, end: 8 }
    spacing: 8
  naming: { bar: "top_bar", icon: "ic/<语义名>" }   # 帧内命名统一（结构导出校验）
  status_bar: 不手画               # 帧从 y0 起，状态栏区域由宿主让出（五页一致）

  title:
    font_size: 17                  # sp，Medium（token topBar.titleFontWeight）
    color: "onSurface"
    max_lines: 1
    single_line: true

  icons:                           # 统一线性 icon 体系（IconSet 页组件库）
    grid: 22                       # token icon/md
    stroke_width: 1.6              # token icon.strokeWidth
    style: 细描边线性，圆头端点/圆角连接，单色
    color: { default: "onSurface", secondary: "onSurfaceVariant@0.8" }
    touch: 36                      # 热区；glyph 居中
    platforms:
      android: "Material Symbols Rounded Outlined w400（material-icons-extended Icons.Outlined.*）"
      ios: "SF Symbols regular"
    rule: 字形允许平台差异（平台材质项免检），笔画视觉重量必须对齐

  # ── 变体 A：子页顶栏（back + title）──────────────────────────
  variant_subpage:
    leading: { back: "ic/back 36/22" }
    title: { align: start }        # 返回钮之后左对齐（Android x≈44）
    applies: [settings族, gallery/settings, gallery/info]

  # ── 变体 B：首页顶栏（title + actions）───────────────────────
  variant_home:
    title: { align: start, padding_start: 16 }
    actions:
      pack: end                    # 右对齐成组，30px 节奏 = 22 icon + 8 gap
      count_max: 5
      # 设计稿几何（相册首页 5 钮）：x = 242/272/302/332/362，最右图标右边 384
    applies: [gallery/grid, gallery/empty]

  # ── 变体 C：搜索顶栏（back + field + count）──────────────────
  variant_search:
    leading: { back: "ic/back 36/22" }
    field:
      height: 36
      corner_radius: 18
      background: "surfaceVariant@0.5"
      grow: true
      leading_icon: { icon: "ic/search", size: 16 }
      placeholder: { size: 14, color: "onSurface@0.35" }
      clear_button: { icon: "ic/clear", size: 16 }
    trailing: { result_count: { size: 12, color: "onSurfaceVariant" } }
    applies: [gallery/search, gallery/search_no_result]

  # ── 变体 D：图标行顶栏（无标题）— 2026-08-18 v2 新增 ─────────
  variant_iconrow:
    leading: ["ic/menu"]           # 聊天主页；带返回入口的场合前置 ic/back
    trailing_max: 3                # chat: ic/bug_report, ic/add_comment, ic/delete_sweep
    spacer: fill_container         # 中部弹性
    applies: [chat/empty, chat/conversation (+light)]

  # ── 附录 E：相机 overlay 顶区（引用，不重复定义）──────────────
  appendix_camera:
    form: overlay（无标题栏，控件浮于取景器之上）
    icon_rule: 顶部图标钮遵循本文件 icons 节 + 44 热区
    geometry_ssot: specs/screens/camera.yaml（top_tool_bar / top_left_controls）

  # ── 附录 F：编辑器浮层顶栏（引用，不重复定义）────────────────
  appendix_editor:
    form: "✕ 32圆钮 | undo·redo | 保存胶囊（方案 A glass-noir）"
    icon_rule: 遵循本文件 icons 节；close 字形在 32 钮内为 18
    geometry_ssot: specs/screens/editor.yaml §18 redesign_a

  # ── 禁止事项 ─────────────────────────────────────────────────
  anti_patterns:
    - "标题盒锁窄宽（<文本宽）→ 竖排折行（gallery/grid 等四帧曾 title 30×42）"
    - "动作图标大间距散布（60px 节奏）——必须 30px 节奏右对齐成组"
    - "标题与返回钮重叠（icon 8..30 内起字）——标题起 x≥38"
    - "顶栏高度 44/56 混用——统一 48"
    - "帧内散画 icon 矢量——必须 ic/* 组件实例（IconSet 页）"
    - "手画状态栏（9:41/信号图标）——帧从 y0 起"

# ── 状态栏避让 ────────────────────────────────────────────────
# Android：AppTopBar 内置 statusBarsPadding + displayCutoutPadding。
# 设计稿：帧从 y0 画起（状态栏区域由宿主让出），帧内不含状态栏占位。
# iOS：safeAreaInset.top 顶栏上留白，同一 48pt 高度内容区。
```

- [ ] **Step 12.2: chat.yaml 去重**

`grep -n "top_bar" specs/screens/chat.yaml` 定位第 22 行起的 `top_bar:` 节。保留行为/交互键（trigger/动作语义），删除与 topbar.yaml 重复的几何键（height/padding/icon 尺寸/间距类），节首加注释：

```yaml
top_bar:
  # 几何 SSOT → specs/screens/topbar.yaml 变体 D（variant_iconrow）；本节仅保留 chat 专属行为
```

- [ ] **Step 12.3: gallery-grid.yaml 去重**

同法处理两处：`top_bar:`（≈L73，→ 变体 B）与 `search_top_bar:`（≈L153，→ 变体 C），各留行为键 + 引用注释。

- [ ] **Step 12.4: camera.yaml 加附录指针**

头部注释块末尾追加一行：

```yaml
# ── 顶栏归属 ── 相机 overlay 顶区遵循 specs/screens/topbar.yaml 附录 E（icon 体系见其 icons 节）
```

- [ ] **Step 12.5: Commit**

```bash
git add specs/screens/topbar.yaml specs/screens/chat.yaml specs/screens/gallery-grid.yaml specs/screens/camera.yaml
git commit -m "docs(spec): topbar.yaml v2 五页全覆盖（变体D+附录E/F）+ chat/gallery 顶栏节去重

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 13: refs 重导 + 终验

- [ ] **Step 13.1: 结构校验**

用 Ardot MCP `batch_read patterns [{name:"ic/"}]`（每页 searchDepth 5）确认五页所有顶栏/工具轨 icon 均为 INSTANCE（type 字段）且命名 `ic/*`；无散画 icon frame 残留（editor rail 内 `ic` 旧名子帧应已删换）。

- [ ] **Step 13.2: 顶栏规格抽检**

五页各抽 1 帧 `capture_layout maxDepth:2`：top_bar 高 48、命名统一、标题字重 Medium、无 status_time/status_icons 节点。

- [ ] **Step 13.3: 重导快照**

```bash
python3 scripts/export-ardot-snapshot.py
```

预期：manifest 含全部帧（含 IconSet 页新帧）；settings-remote_models/sandbox PNG 不再是 1748 字节空图。

- [ ] **Step 13.4: 相机帧视觉比对**

```bash
for f in camera-idle camera-panel_beauty_face camera-panel_ratio camera-panel_grid camera-panel_filter camera-panel_pro camera-focusing; do
  python3 scripts/screenshot-diff.py --baseline tmp/ardot-unification/baseline/$f.png --current specs/screens/refs/ardot/$f.png --threshold 0.85 --output tmp/ardot-unification/diff-$f.png --report || echo "DIFF-FAIL: $f"
done
```

预期：全部 ≥0.85 通过（icon 风格差异已用低阈值容忍）；未过的帧人工看 diff 图判断是「预期 icon 变更」还是「布局偏差」，后者回 Task 11 修正重验。

- [ ] **Step 13.5: 最终 commit**

```bash
git status --short   # 审查：refs PNG、structure.json、manifest 应有变更；不应有无关文件
git add specs/screens/refs/ specs/screens/
git commit -m "feat(design): Ardot 五页顶栏/icon 统一化定稿——refs 快照重导

IconSet 组件库 35 icon + 相机 7 帧重建 + 四页改造 + topbar.yaml v2

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 14: 收尾

- [ ] **Step 14.1: 记忆更新**

写 `~/.claude/projects/-Users-guoshuai-AndroidStudioProjects-polang/memory/ardot-icon-system-unified.md`：icon 体系落成（IconSet 页、35 组件、实例行为结论——冒烟结果成败都记）、相机重建完成、topbar.yaml v2 位置；更新 MEMORY.md 索引。同时更新 `ardot-storage-and-snapshot-pipeline.md` 里「并发会话损伤」教训（若有新发现）。

- [ ] **Step 14.2: 汇报**

向用户汇报：改动清单、冒烟验证结论、相机重建比对结果、遗留项（双端代码三同步待后续轮、editor 标记/抽卡态帧由编辑器会话补）。

---

## 自审记录

1. **Spec coverage**：设计文档 §2.2 全集 32 定 + 待定——计划覆盖 35 个组件（32 定 + 相机 5 中 pro 为新增 + 信息页 2），待定项在 Task 11/9 执行中按 camera.yaml/info 帧实需增补（增补即入 IconSet，流程同 Task 3 模板）。§3 修正项分布在 Task 6-10。§4 工程顺序=Task 0-13。§5 token=Task 1、yaml=Task 12。§6 验收=Task 13。✓
2. **Placeholder scan**：无 TBD/TODO；相机重建的"逐节 I"依赖 camera.yaml 全文（1148 行 SSOT），计划明确 Step 11.1 先通读、重建原则与验收线量化，不复制 SSOT 进计划（DRY）。✓
3. **Type consistency**：组件命名 `ic/<name>` 全文一致；变量引用 `$:PoLang Tokens:scheme/onSurface` 一致；变体名 variant_subpage/home/search/iconrow 与 topbar.yaml v2 一致。✓
