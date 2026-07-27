# TAG 生成控制页 · 分阶段进度展示重构

> 状态：设计稿（待实现）
> 日期：2026-07-27
> 涉及文件：`app/src/main/java/com/mamba/picme/features/gallery/components/TagGenerationControlScreen.kt`
> 数据源：`domain/tag/scan/TagScanOrchestrator.kt` → `TagScanDbStats`

## 背景

`TagGenerationControlScreen.kt` 是「TAG 生成精细控制子页面」，其中三处用 `X / Y 张已完成` 的分数式描述各阶段进度。当前写法引起歧义，需要重构。

三处分别是：
- **A. 分阶段独立控制**（约 361–421 行）：三个 `PassControlCard`，其中人脸检测 / 内容理解两条 subtitle 含分数式。
- **B. 处理阶段概览**（约 254–324 行）：只读三步说明，第一/三步副标题含分数式。
- **C. 阶段进度表格**（约 851–856 行）：`StatsPassTableRow`，「完成」列是 `X / Y`。

## 问题：两层歧义

1. **格式读法歧义**：`$withFace / $totalMedia 张已完成` 形似数学分数/除法，「张」「已完成」各修饰谁不清晰，扫读时易误读为「X 分之 Y」或「X 除以 Y」。
2. **语义口径不准**：分子 `withFace`（有人脸的照片数）、`withLabels`（有标签的照片数）被当成「本阶段已完成数」，但二者并不等于真实处理进度。
   - Pass1 真实已处理 = `totalMedia − remainingForPass1`（做过人脸检测的）；`withFace` 只是其子集（检测到人脸的）。
   - 反例：100 张照片、Pass1 处理完 80 张、其中 50 张有人脸 → 当前显示「50 / 100 已完成」，误报为 50%，而真实已处理是 80%。
   - Pass3 的 `withLabels` 恰好等于 `totalMedia − remainingForPass3`，语义正确，但仍含格式歧义。

## 目标与非目标

**目标**
- 消除所有 `X / Y` 斜杠分数写法；比例交给进度条，绝对数用「已处理 · 待处理」表达，口径自洽（已处理 + 待处理 = 总数）可心算校验。
- 修正口径：Pass1/Pass3「已处理」= `总数 − 待处理`；`withFace` 降级为 Pass1 的补充信息。
- 三处同源分数式统一重构，整页口径一致。
- 新文案走 `stringResource`，同步三语言。

**非目标**
- 不改扫描逻辑 / Service / Orchestrator。
- 不改 DAO 查询与 `TagScanDbStats` 结构（真实已处理数在 UI 层用 `总数 − 待处理` 计算，数据已齐）。
- 不对文件中其它既有硬编码中文做全量 i18n 治理（仅限本次新增/改写文案）。

## 数据口径（确认）

`TagScanDbStats` 已有字段（无需扩展）：

| 字段 | 含义 |
|---|---|
| `totalMedia` | 全部照片数 |
| `withFace` | 有人脸的照片数（Pass1 子集） |
| `withLabels` | 有标签的照片数 == `totalMedia − remainingForPass3` |
| `withSemantic` | 有语义嵌入的照片数 |
| `personCount` / `faceEmbeddingCount` | 人物数 / 特征条数 |
| `remainingForPass1` | 未做人脸检测的照片数 |
| `remainingForPass3` | 未生成标签的照片数 |

UI 层派生（页面 state 已持有 `totalMedia`、`remainingPass1`、`remainingPass3`、`withFace`、`withLabels`、`withSemantic`、`personCount`、`embeddingCount`）：

```
processedPass1 = totalMedia − remainingPass1   // Pass1 真实已处理
processedPass3 = totalMedia − remainingPass3   // == withLabels
```

## 设计

### A. 分阶段独立控制（核心，引入进度条）

改造 `PassControlCard`：在 title + 功能说明下方，新增进度条与进度文字行；保留右侧「增量 / 全量」操作。

```
人脸检测与语义编码
为未处理照片识别面孔并提取语义特征
████████████████░░░░░░  80%
已处理 80 · 待处理 20 · 有人脸 50            [+ 增量][↻ 全量]
```

- 进度条 `LinearProgressIndicator`：`progress = processed / total`（`total=0` 时 `progress=0f` 并显示「暂无照片」）。
- 进度条右侧：进行中显示百分比；完成显示绿色 `CheckCircle`。
- 进度条下方文字行：
  - **人脸检测与语义编码**：`已处理 {processedPass1} · 待处理 {remainingPass1} · 有人脸 {withFace}`
  - **图片内容理解**：`已处理 {processedPass3} · 待处理 {remainingPass3}`
- **人物聚类**：**不画进度条**。理由：聚类是对全部 embedding 的一次性全量操作，没有「部分完成」概念，空进度条会被误读为「0% 完成」。改用「状态点 + 计数行」：
  - `personCount > 0` → 已完成样式点 + `已识别 {personCount} 人 · {embeddingCount} 条特征`
  - `personCount == 0` → 待补充样式点 + `尚未聚类 · {embeddingCount} 条特征待分组`

进度条三态：
- `remaining == 0 && total > 0` → 满条 + 绿色 `CheckCircle`（已完成）
- `total > 0 && remaining > 0` → primary 色进度条 + 右侧百分比
- `total == 0` → 灰条 + 「暂无照片」

### B. 处理阶段概览（只改文案口径，保留 icon + 文字形态）

不引入进度条（避免整页进度条过载），仅去斜杠 + 改口径，措辞与 A 的「已处理 · 待处理」一致：

- 第一步（人脸检测与语义编码）副标题：
  `识别照片中的人脸并提取语义特征，用于人物归类与智能搜索 · 已处理 {processedPass1} 张 · 待处理 {remainingPass1} 张 · {withFace} 张有人脸 · {withSemantic} 张有语义`
- 第二步（人物聚类）副标题：`已识别 {personCount} 个人物`（无分数，仅随口径措辞统一，内容不变）
- 第三步（图片内容理解）副标题：
  `分析画面内容，生成场景、活动、物体等标签与摘要 · 已处理 {processedPass3} 张 · 待处理 {remainingPass3} 张`

第一步副标题较长，实现时若超出两行可省略「{withSemantic} 张有语义」分句（语义编码已内联到人脸检测，属次要信息）。

### C. 阶段进度表格（去斜杠，改列语义）

`StatsPassTableRow(pass, done, remaining)` 结构不变，改表头与传值：

- 表头：`Pass / 完成 / 剩余` → `阶段 / 已处理 / 待处理`
- 行：
  - `人脸检测` / `{processedPass1}` / `{remainingPass1}`
  - `内容标签` / `{processedPass3}` / `{remainingPass3}`
- 值为纯整数字符串，无斜杠。

## i18n string 清单

新增（前缀 `tag_pass_`，与既有 `tag_control_*` 风格一致），同步 `values/`（EN）、`values-zh-rCN/`、`values-zh-rTW/`；若 `values-zh/` 存在则一并：

| key | values (EN) | values-zh-rCN | values-zh-rTW |
|---|---|---|---|
| `tag_pass_processed` | Processed | 已处理 | 已處理 |
| `tag_pass_pending` | Pending | 待处理 | 待處理 |
| `tag_pass_done` | Done | 已完成 | 已完成 |
| `tag_pass_no_media` | No photos | 暂无照片 | 暫無照片 |
| `tag_pass_pending_cluster` | Pending | 尚未聚类 | 尚未聚類 |
| `tag_pass_progress_p1` | Processed %1$d · Pending %2$d · %3$d with face | 已处理 %1$d · 待处理 %2$d · 有人脸 %3$d | 已處理 %1$d · 待處理 %2$d · 有人臉 %3$d |
| `tag_pass_progress_p3` | Processed %1$d · Pending %2$d | 已处理 %1$d · 待处理 %2$d | 已處理 %1$d · 待處理 %2$d |
| `tag_pass_cluster_done` | %1$d people · %2$d embeddings | 已识别 %1$d 个人物 · %2$d 条特征 | 已識別 %1$d 個人物 · %2$d 條特徵 |
| `tag_pass_cluster_pending` | %1$d embeddings to group | %1$d 条特征待分组 | %1$d 條特徵待分組 |
| `tag_pass_col_stage` | Stage | 阶段 | 階段 |
| `tag_pass_col_processed` | Processed | 已处理 | 已處理 |
| `tag_pass_col_pending` | Pending | 待处理 | 待處理 |

阶段标题与功能说明（A 区 `PassControlCard` 的 title、B 区「第N步」标题及说明）同样提取为 string；实现时按现有 `tag_control_*` 命名延续（如 `tag_pass_title_face` / `tag_pass_title_cluster` / `tag_pass_title_content` 及各自 desc）。百分比文案用 `stringResource` + 数字格式化，避免硬编码。

> 既有 `tag_control_title` / `tag_control_subtitle` 三语言已存在，本次复用，不改。

## 验收标准

1. 三处（A/B/C）均无 `X / Y` 斜杠分数式；`grep -n "/ \$totalMedia\|/ \$totalMedia\|/ .*totalMedia" TagGenerationControlScreen.kt` 无命中。
2. Pass1「已处理」显示 `totalMedia − remainingPass1`：在「100 张 / Pass1 处理 80 / 50 有人脸」场景下，显示「已处理 80」而非「50」。
3. Pass2 不出现进度条；`personCount==0` 显示「尚未聚类」、`>0` 显示「已识别 N 人」。
4. 进度条三态正确：满条+对勾（已完成）/ 主色+百分比（进行中）/ 灰条+「暂无照片」（空库）。
5. 新增 string 在 `values/`、`values-zh-rCN/`、`values-zh-rTW/`（及存在的 `values-zh/`）齐全；本次改写范围内无硬编码用户可见文字。
6. `./gradlew :app:assembleDebug` 与 `./gradlew test` 通过。

## 风险与边界

- **垂直空间**：A 区每个 `PassControlCard` 增加进度条 + 文字行，三卡堆叠高度上升。页面已有 `verticalScroll`，溢出可滚动；但仍需在小屏机型目视确认 Pass2 不画条、Pass1/3 各只加一行进度条 + 一行文字后的整体长度可接受。
- **百分比/进度计算**：`total=0` 必须前置判空，避免除零；`progress` 用 `Float`、`coerceIn(0f,1f)`。
- **i18n 占位符**：带 `%1$d` 的模板用 `stringResource(R.string.tag_pass_progress_p1, processedPass1, remainingPass1, withFace)`；中文繁体「张/張」「识/識」勿混。
- **既有债**：文件中大量既有硬编码中文不在本次范围；仅本次新增/改写文案走 i18n，避免范围蔓延。
