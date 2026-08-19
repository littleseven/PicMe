# design-tokens.json ↔ Ardot 完美映射 — 设计文档

- 日期：2026-08-19
- 状态：设计定稿（四原则 + 四阶段 + 三项范围决策已由用户签核）
- 前置：`2026-08-18-ardot-topbar-icon-unification-design.md`（icon 组件库与实例体系已完成）

## 0. 目标

建立 JSON token 与 Ardot 变量的**一比一镜像**，并按「有 token 必绑定」纪律清扫画布全部页面 UI 定义，使画布成为 JSON 的忠实投影。

## 1. 四原则（映射形式）

1. **形状保持两级 `域/键`，不升三级**。codegen 的 `parentKey+Capitalized(childKey)` 扁平规则已冻结双端 API 名，三级化 = 双端代码全炸。结构化 = 域的分层语义：基础域（spacing/radius/icon/alpha/elevation）→ 组件域（topBar/chip/bottomTab…）→ 页面域（camera/editor/settings…）→ 颜色角色域（colorScheme→scheme/*）。
2. **一比一镜像**：JSON token ↔ 同名画布变量；`colorScheme.dark.X ↔ scheme/X@Dark`、`light.X ↔ @Light`（画布只有 scheme 域带双 mode，数值域全 mode 同值）。
3. **绑定纪律**：语义 token 存在处必须绑变量（色 / alpha / 关键尺寸 / 字阶 / 圆角 / 间距）；一次性坐标、内容色（滤镜缩略图等）、派生布局值不绑。同值重复 ≥2 处且无 token 的字面量 → 补 token 再绑（单一语义除外）。
4. **JSON 是唯一源**：改值先 JSON → `gen-design-tokens.py` → 增量 merge `apply_variables`；禁整包重灌（scope 会丢）。

## 2. 已签核的三项范围决策

| # | 决策 |
|---|---|
| 1 | 清扫范围 = **全量尺寸也绑**（色/alpha/顶栏高/字阶/尺寸/圆角/间距，凡有语义 token 处） |
| 2 | **顺手修 gen 脚本**：ardot 导出为无歧义键补 scopes（strokeWidth→STROKE_FLOAT、radius/*→CORNER_RADIUS），其余键不设 scope 保持全域可绑 |
| 3 | **editor 域 token 现在入库**（editor.yaml §18 提案 + 画布既有 editor/* 变量吸收，去重后补缺） |

## 3. 四阶段

### A 审计（只读）
`fetch_variables` 全量 vs `design-tokens.json` 四维 diff：名称（双向缺失）/ 值 / mode / scope。已知输入态：画布有另一会话加的 `editor/*` 变量（JSON editor 域部分存在）；`icon/strokeWidth` 手工带 scope。产出漂移清单，其中「画布有 JSON 无」的项按语义吸收进 JSON（画布值优先入 JSON，因彼时它是唯一记录）。

### B 补缺（JSON + 脚本 + 画布）
1. gen 脚本 scope 修复（决策 2）+ `--check` 回归
2. editor 域补缺入库（决策 3）：候选键 canvasDark #0F0E0E、gachaBarBg #1A1919、overlayAlphaPrimary 0.06、overlayAlphaHover 0.08、overlayAlphaTrack 0.12、toolRailItemSize 44、paramTileSize 54（先与画布既有 editor/* 及 radius/card 12 等去重，重复语义复用既有键）
3. codegen → 增量 apply → `fetch_variables` 复核双向零漂移

### C 清扫（画布，按页串行）
页面顺序 Chat → Gallery → Settings → Editor → IconSet（相机帧当前为空壳仅存 icon 实例，实例已绑变量，无清扫面）。每页任务：
- 字面色 fill/stroke → `scheme/*` / `color/*` / `alpha/*`（含 editor 玻璃白系 white@0.06/0.08/0.12、各页 48/0.5 系）
- 顶栏高 48 字面量 → `$topBar/height`；字阶匹配的 fontSize/fontWeight → `typography/*` / `topBar/titleFontSize`
- 尺寸/间距/圆角凡语义匹配 → 对应域 token；发现「≥2 处重复且无 token」→ 记录清单统一补（不现场散加）
- 验证：每页改后 capture_screenshot 与改前对比（视觉零回归——绑定等值替换不应有任何像素变化）+ batch_read 抽查 boundVariables

### D 收口
codegen `--check` + refs 重导（相机 7 帧维持 HEAD 保护态）+ topbar/DESIGN_TOKENS_SPEC 注记 + commit。

## 4. 验收标准

1. 审计复核：JSON ↔ 画布变量名称/值/mode 双向零漂移（scope 按新规则）
2. 像素级：清扫前后各帧截图 diff = 0（等值替换，视觉必须完全不变）
3. 绑定覆盖：五页 + IconSet 内，与 token 语义匹配的字面量属性绑定率 100%（豁免：坐标/内容色/派生值/一次性值——逐帧留清单）
4. `gen-design-tokens.py --check` 通过；ardot-variables.json 含 scopes
5. refs/manifest 更新入库，相机帧不受损

## 5. 风险与缓解

| 风险 | 缓解 |
|---|---|
| 绑定值与字面量不完全等值（mode 解析差异） | C 阶段每处替换前先 fetch 该变量当前 mode 解析值比对；帧级像素 diff 兜底 |
| scope 错配破坏既有绑定 | gen 只给无歧义键发 scope，其余不设；`--check` + 抽查兜底 |
| 大批量 U 期间适配器闪断 | 沿用 30/60/90s+5min 双轮重试；新派代理（不 resume） |
| editor.yaml §18 值与画布既有 editor/* 变量冲突 | 以画布既有变量值为准（彼时唯一记录），§18 提案仅补缺 |
