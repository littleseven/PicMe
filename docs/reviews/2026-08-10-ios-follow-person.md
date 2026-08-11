# ios-follow · person · 验收报告（Stage 5）

- **日期**：2026-08-10
- **feature**：人物页（列表 + 详情）
- **分支**：`feat/ios-tag-scan-core`（保留主 checkout：依赖本分支 TagDatabase schema + 可构建二进制产物）
- **设计 spec**：`docs/superpowers/specs/2026-08-10-ios-person-page-cluster-parity-design.md`
- **契约**：`specs/screens/person.yaml` · `tmp/ios-follow/person/{follow-plan,contracts}.md`

## 改动摘要

iOS 人物页从**手动建人模型（PersonStore）**迁移到**聚类数据模型（TagDatabase）**，与 Android `PersonScreen`/`PersonInfoScreen` 全量对齐。

| 类别 | 文件 | 动作 |
|------|------|------|
| 数据层 | `Platform/TagDatabase.swift` | 新增 `person_relations` 表 + 索引 |
| 数据层 | `Platform/TagDatabase+Person.swift` | **新建**：人物/关系/封面/计数查询（allPersonRows、reconcilePersons、photoCountForPerson UNION DISTINCT、coverInfos、relationToSelf、upsertRelationToSelf、coverCandidates、rename/cover/setSelf…） |
| 编排 | `Features/Person/PersonRepository.swift` | **新建**：对标 Android PersonRepository+PersonCoverResolver（reconcileAndLoad、coverable 过滤、单人组过滤、亲密度排序、详情编辑） |
| VM | `Features/Person/PersonViewModel.swift` | 重写：list/detail VM 改读 PersonRepository；保留 RelationOptions 桥接；reconcile/toggleShowAll/inline rename/recluster/refresh |
| UI | `Features/Person/PersonView.swift` | 重写：动态计数标题 `People (可见/总数)`、2 列人脸感知卡片、行内改名、关系 chip、筛选+重聚类工具栏、toast |
| UI | `Features/Person/PersonInfoView.swift` | 重写：`Cluster #N` 标题、55%×180 人脸感知封面+3列选择器、名称行内编辑、「这是我」、家庭/社会关系 chip 组+自定义；移除照片网格/指派/删除/两级关系选择器 |
| 组件 | `Features/Gallery/ThumbnailView.swift` | 新增 `cornerRadius` 参数（默认 2，人物卡用 16/0） |
| 删除 | `Features/Person/PersonStore.swift` | 整体删除（手动模型退役） |
| token | `design-tokens.json` / `DesignTokens.swift` | 新增 `person` 节（cardRadius16/coverHeight180/coverWidthRatio0.55/gridColumns2…） |
| i18n | `Localizable.xcstrings` | 新增 17 key（en/zh-Hans/zh-Hant，值取自 Android values-zh-rCN/zh-rTW） |

## ✅ 自动通过（命令可判）

- **xcodebuild device 构建绿**：`xcodebuild -workspace PoLang.xcworkspace -scheme PoLang -destination 'generic/platform=iOS' -configuration Debug CODE_SIGNING_ALLOWED=NO` → **BUILD SUCCEEDED**，0 error。
- **xcodegen generate** 成功（新文件入 target，PersonStore 移除）。
- **pod install** 成功（Pods 集成）。
- **xcstrings JSON 校验** 通过（17 key 三语齐全）。
- **无 stale 引用**：PersonStore/PersonRow/RelationRow/AddPersonSheet/MediaPickerSheet 全仓零残留。
- **构建重试记录**：① pod install 缺失→no such module MediaPipeTasksVision（非代码）② `SQLITE_TRANSIENT` 文件级 private 未跨文件可见→本文件补定义 ③ `upsertRelationToSelf` 缺 `return queue.sync`→补 return。均为单行精确修复。

## ⚠️ 待真机终验（留用户）

> 命令绿 ≠ 做完。以下需真机（OTA 自测分发页）终验，按「功能 > UI > 性能」优先级。

- **聚类数据渲染**：需先跑一次 TAG 扫描产出 `persons`（Pass2 聚类）后，列表才有内容；空态对齐 Android（不渲染占位）。
- **列表观感**：2 列 16pt 圆角卡、1:1 人脸感知封面（faceFocusY 裁切）、动态计数标题、关系 chip（isSelf 高亮）、亲密度排序。
- **详情观感**：`Cluster #N` 标题、55%×180 封面+3列选择器、名称 headline、家庭/社会 chip 组、自定义称呼覆盖逻辑。
- **交互手感**：行内改名（✓/✗）、筛选切换+隐藏计数提示、重聚类触发（fire-and-forget + toast）。
- **稳定性**：黑屏体检、崩溃信号检查（需真机安装）。
- **双端像素 diff**：Android↔iOS 截图 SSIM（需双端截图，阈值 0.80）。

## 📋 技术债清单

- **Stage 2 暂缓项**：① 重打分（iOS 无美学打分器，NIMA 未接）；② 封面点击→相册按聚类 `personId` 筛选（相册现按 `faceId` 分组，需 faceId↔personId 桥接；Stage1 封面点击→进详情兜底）。
- **封面候选排序**：`getMediaByPersonOrderedForCover` 的「单人脸优先」精修未做（Stage1 按拍摄时间倒序）。
- **关系谓词 zh-Hant**：取自 `:shared` 单 `labelZh`（简繁未分）；组标题/hint/cluster 等已三语，23 谓词标签待 shared 分简繁。
- **Android token 归一**：`PersonListItem.kt` 用字面量 `16.dp`/`180.dp` 未引用 token（铁律1：不改 Android 业务代码，已登记）。
- **isSelf 关系 chip 配色**：iOS 用白色（黑底 shell 语境近似 Android primaryContainer 高亮），未逐像素用 primaryContainer。
- **重聚类保名**：iOS Pass2 为全量重扫（清 persons/名字/关系），与 Android 增量 split/merge 不同；重命名保名未实现（研究项目可接受）。

## 结论

**Stage 1（核心全量对齐）代码完成且编译绿**。功能/UI 结构与 Android 对齐；聚类数据源迁移到位；三语 i18n 齐全。Stage 2（重打分 + 封面点击进相册）已登记技术债。**真机终验 + 双端 SSIM 留用户**。
