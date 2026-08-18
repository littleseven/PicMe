# Ardot 顶栏与 Icon 体系统一化重设计 — 设计文档

- 日期：2026-08-18
- 状态：设计定稿（用户已逐节签核 §1–§4），待实施
- 范围：Ardot 画布五页（Camera / Gallery / Chat / Settings / Editor）+ `specs/screens/topbar.yaml` + `design-tokens.json`
- 不含：双端手写代码改动（后续走三同步分轮落地，可经 `/ios-follow`）

## 0. 背景与事件记录

### 0.1 动机

Ardot 画布各页 topbar 与 icon 设计未统一：命名三套（`top_bar`/`TopBar`/`title_bar`、`icon_back`/`ic/back`/`icon_model_center`）、图标风格两派（chat/settings 填充式 vs gallery 细描边式）、高度不齐（44/48 混用）、editor 手画假状态栏、标题字重漂移（canvas SemiBold vs spec Medium）。`topbar.yaml`（2026-08-18 设立）仅覆盖标题顶栏族，明确排除相机与 chat，统一化缺少全页面结构。

### 0.2 画布损伤事件（2026-08-18 19:07–19:26 之间）

开工前基线扫描发现画布内容分批丢失，疑似并发会话（编辑器重设计·方案 A，最后一次写 `editor.yaml` 于 19:22）的批量操作所致：

| 受损内容 | 现状 |
|---|---|
| Camera 页 6 帧（idle / panel_beauty_face / panel_ratio / panel_grid / panel_filter / panel_pro） | 全部空壳（仅剩背景填充） |
| camera/focusing | 仅剩对焦十字 |
| gallery/grid 顶栏 icon_model_center、icon_search | 空壳 |
| chat/empty 顶栏 ic/bug_report | 空壳 |
| 19:07 导出的 settings-remote_models/sandbox PNG | 1748 字节空图（帧结构现读完好，疑导出时序损伤） |

时间线：19:07 `export-ardot-snapshot.py` 导出时相机内容完整（git 快照可证）→ 19:26 读画布已空。**用户决策：丢失内容由本次工作重建**（相机页按 camera.yaml + git HEAD 快照反向重画）。

## 1. 已签核决策

| # | 决策 | 选择 |
|---|---|---|
| 1 | 画布损伤处理 | 重建丢失内容（并入本次工作） |
| 2 | Icon 风格 | **细描边线性**：22 网格、描边 1.6、圆头端点+圆角连接、单色 |
| 3 | 覆盖范围 | **五页全覆盖**（含相机重建直接用新体系；Editor 方案 A 帧 icon 对齐） |
| 4 | 交付边界 | 画布 + spec + token（跑 codegen 生成物），双端手写代码后续三同步 |
| 5 | 结构方案 | **A：icon 组件库 + spec 驱动顶栏**（顶栏不组件化；实例冒烟验证失败则降级纯规则） |

## 2. Icon 系统

### 2.1 规格

- 容器 = 字形网格 **22×22**（与现画布全量一致，零迁移成本）；视觉边距由字形自留
- 描边 **1.6**（token `icon.strokeWidth`），端点 `round`、连接 `round`；纯线性，无填充层、无渐变
- 颜色一律绑语义变量：默认 `onSurface`、次要 `onSurfaceVariant@0.8`；深浅色随变量 mode 翻转（chat light 帧零额外工作）
- 命名 `ic/<语义名>`，组件名 = 实例名 = 结构导出名，三处一致
- 全部为 IconSet 页内 COMPONENT 本体 + 各帧 INSTANCE 引用

### 2.2 全集（32 定 + 相机/相册信息页待定 ~10）

| 域 | icon | iOS (SF Symbols) | Android (Material Symbols Outlined) |
|---|---|---|---|
| 导航 | ic/back | chevron.left | KeyboardArrowLeft |
| 导航 | ic/menu | line.3.horizontal | Menu |
| 导航 | ic/search | magnifyingglass | Search |
| 导航 | ic/close | xmark | Close |
| 导航 | ic/more_vert | ellipsis | MoreVert |
| 导航 | ic/check | checkmark | Check |
| 导航 | ic/clear | xmark.circle | Clear |
| 相册 | ic/model_center | cube | ViewInAr |
| 相册 | ic/scan | viewfinder | QrCodeScanner |
| 相册 | ic/sort | arrow.up.arrow.down | SwapVert |
| 通用 | ic/settings | gearshape | Settings |
| chat | ic/bug_report | ladybug | BugReport |
| chat | ic/add_comment | plus.bubble | AddComment |
| chat | ic/delete_sweep | trash | DeleteSweep |
| tab | ic/camera | camera | PhotoCamera |
| tab | ic/chat | ellipsis.bubble | ChatBubbleOutline |
| tab | ic/tag | tag | LabelOutline |
| tab | ic/people | person.2 | PeopleOutline |
| 编辑轨 | ic/crop | crop | Crop |
| 编辑轨 | ic/tune | slider.horizontal.3 | Tune |
| 编辑轨 | ic/face | face.smile | Face |
| 编辑轨 | ic/filter | camera.filters | AutoAwesome |
| 编辑轨 | ic/brush | paintbrush | Brush |
| 编辑轨 | ic/erase | eraser | InkEraser |
| 编辑轨 | ic/auto_fix | wand.and.stars | AutoFixHigh |
| 编辑动作 | ic/undo | arrow.uturn.backward | Undo |
| 编辑动作 | ic/redo | arrow.uturn.forward | Redo |
| 编辑动作 | ic/cutout | person.crop.rectangle | ContentCut |
| 相机（重建时从 camera.yaml 定） | ic/flash / ic/flip / ic/grid / ic/ratio / ic/pro… | bolt / arrow.triangle.2.circlepath.camera / grid / aspectratio / 曝仪表待定 | FlashOn / Cameraswitch / GridOn / AspectRatio / 待定 |
| 相册信息页（重建时按需） | ic/share / ic/favorite… 待定 | square.and.arrow.up / heart | Share / FavoriteOutline |

映射规则：字形允许平台差异（属既定「平台材质项免检」），**笔画视觉重量必须对齐**。表内 Android 名为 material-icons-extended 的 `Icons.Outlined.*` 语义对应，个别名在实现轮核准。spec-sheet 帧上直接标注本表（即双端实现对照表）。

## 3. 顶栏规格 v2（topbar.yaml 升级）

### 3.1 四变体 + 两附录

- **A 子页**（back + title）：设置族 / 相册子页（gallery/settings、gallery/info）
- **B 首页**（title + actions 右组 30px 节奏）：相册首页
- **C 搜索**（back + field + count）：gallery/search、search_no_result
- **D 图标行（新增）**：chat——leading `ic/menu`（+ `ic/back` 视入口来源）+ trailing ≤3 动作；同 48 高 / 水平边距 8 / 图标间 30px 节奏；无标题
- **附录 E 相机**：overlay 形态（无标题栏）；顶部图标钮遵循 icon 体系 + 44 热区；几何以 camera.yaml 为准，本规范只引用
- **附录 F 编辑器**：方案 A 浮层形态（✕ 32 圆钮 / undo·redo / 保存胶囊，y42）；icon 换统一集；几何以 editor.yaml §18 为准

通用：高 48；标题 17 **Medium** 单行省略；动作钮 touch 36 / glyph 22。

### 3.2 修正项（本次落地）

1. gallery/info `title_bar` 44 → 48，命名并入 `top_bar`
2. 标题字重画布 SemiBold → **Medium**（向 spec 与双端代码对齐）
3. **五页一律不画假状态栏**：editor 帧手画的 9:41/status_icons 移除，帧从 y0 起（与其他页及 topbar.yaml 既有约定一致）
4. 帧内顶栏命名一律 `top_bar`；icon 一律 `ic/*` 实例

### 3.3 anti_patterns 追加

- icon 必须为 `ic/*` 组件实例（结构导出可校验，禁止帧内散画矢量）
- 禁止手画状态栏
- 沿用既有四条（窄宽折行 / 60px 散布 / 标题与返回钮重叠 / 44、56 混用）

## 4. 画布工程（执行顺序）

1. **开工前置**：重读全部顶层帧 children 基线（对照 §0.2 盘点表）；有变即停、报告——防并发会话再动刀
2. **新页「IconSet」**：spec-sheet 陈列帧（组件本体 + 名称 + 双端映射标注）
3. **实例冒烟验证**：settings/main_list 的 `icon_back` 换 1 个实例 → 截图验证；通过才铺开；失败降级纯规则方案（icon 库与 spec 仍有效，仅放弃实例引用）
4. **相机页 7 帧重建**：camera.yaml + git HEAD refs PNG 逐帧对照重画，直接用新 icon 体系（不加新功能，不违冻结）；photo 填充遇 imageHash 不渲染 → G() 原 prompt 重生
5. **其余四页改造**：Gallery 9 帧（补 2 空壳 icon、info 高度、search/selection/scanning 顶栏核对）、Chat 6 帧含 light（补 ic/bug_report、命名统一）、Settings 5 帧（弹窗不动）、Editor 6 帧（现状 2 + 方案 A 4：icon 对齐 + 去状态栏；「标记态/抽卡态」待补帧由编辑器会话后续新建，不在本轮）
6. **refs 重导**：export-ardot-snapshot.py 全量重跑 + manifest 更新；相机帧与 HEAD 快照视觉比对验收

## 5. Spec & Token 变更

- `design-tokens.json` 新增：`icon.size=22`、`icon.strokeWidth=1.6`、（若缺）`topBar.height=48` → 跑 `gen-design-tokens.py` 生成双端镜像（生成物，不手改）
- `topbar.yaml` v2 按 §3 重写（含变体 D 与附录 E/F）
- camera/chat/gallery/settings yaml：顶栏节改为引用 topbar.yaml 变体（去重复）；camera.yaml 几何不动；editor.yaml §18 不动
- 画布变量：`apply_variables` 补画布侧 icon 相关变量，与 JSON 保持同步

## 6. 验收标准

1. 结构导出：五页所有顶栏 icon 均为 `ic/*` 实例，无散画矢量（editor 概念帧工具轨含内）
2. 顶栏：全部 48 高、`top_bar` 命名、标题 17 Medium、无手画状态栏
3. IconSet spec-sheet：全集陈列 + 双端映射标注，组件本体可被实例引用
4. 相机 7 帧：与 git HEAD refs 快照逐帧视觉比对，布局结构零容差、尺寸 ±2dp（icon 风格差异属预期变更）
5. refs 重导后 manifest 覆盖五页全帧
6. `gen-design-tokens.py` 跑通且双端镜像含新 token

## 7. 风险与缓解

| 风险 | 缓解 |
|---|---|
| 并发会话再动刀 | 开工前 + 每大步前重读基线；有变即停 |
| 组件实例行为异常（历史上复制含实例帧挂适配器 ×4） | 冒烟验证先行；仅插入场景使用，禁复制含实例帧；失败降级纯规则 |
| 相机重建保真度 | camera.yaml 几何 SSOT + HEAD 快照比对，布局零容差 |
| imageHash 跨节点不渲染 | G() 原 prompt 重生（既有经验） |
| Material Symbol 个别名缺位 | 映射表标注「实现轮核准」，不阻塞设计层 |
