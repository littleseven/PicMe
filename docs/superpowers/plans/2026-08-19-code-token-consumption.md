# 三同步代码落地轮 — 实施计划（Android 为主 + iOS 小项）

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Steps use checkbox syntax.

**Goal:** 双端 UI 代码消费面对齐统一后的 token/icon 体系：顶栏图标 filled→Outlined、dynamicColor 全局关钉青玉、编辑器选中态青玉化、chat 残余蓝清除。

**规范锚点:** `docs/08-UI-SPECS/screens/topbar.yaml` icons.platforms · design-tokens.json（440→524 token）· 普查报告（2026-08-19 消费面清单，file:line 级）。

**已签核决策:** dynamicColor 全局关闭钉青玉 · 相机 features 冻结排除 · 模型来源语义色保留非品牌 · iOS mat_* 资产替换另开波次。

**符号坑（AAR 实证）:** LabelOutline→`Icons.Outlined.Label`；Undo/Redo/Sort/ArrowBack/ContentCut→`AutoMirrored.Outlined.*` 前缀；Cameraswitch 小写 s；InkEraser 1.7.8 无（本轮无需）。生成物（Color.kt/DesignTokens.kt/Swift）禁手改。

---

### C2: 顶栏图标换装（含 C1 通配展开前置）

**Files:** AppTopBar.kt / ChatScreen.kt / GalleryTopBar.kt / EditorTopBar.kt / ImageEditScreen.kt / IDPhotoScreen.kt / PersonScreen.kt / LlmCallLogScreen.kt / SearchField.kt / 4 通配导入文件 / AppTopBarTest.kt

- [ ] 0. 展开 4 处 `import ...icons.rounded.*` 通配（CameraCommandMessages/TagGenerationControlScreen/AgentChatComponents/AiChatScreen）——只展开不换装（相机文件只展开 Rounded 导入）
- [ ] 1. AppTopBar.kt:182 `Icons.AutoMirrored.Rounded.ArrowBack` → `Icons.AutoMirrored.Outlined.ArrowBack`
- [ ] 2. 按普查表逐调用点 Rounded/Default→Outlined：Chat 变体D 四钮、GalleryTopBar 变体B 五钮+Sort+多选态+DuplicateManager、EditorTopBar 五钮、ImageEditScreen 三钮、IDPhoto Check、PersonScreen 三钮、LlmCallLog 两钮、SearchField Filled.Search/Clear→Outlined
- [ ] 3. AppTopBarTest fixture `Icons.Rounded.Settings`→Outlined
- [ ] 4. ktlintCheck + 编译 + 该文件单测绿
- [ ] 5. Commit `feat(ui): 顶栏/搜索/浮动tab图标统一为 Material Outlined 细描边——topbar.yaml icons.platforms 落地`

### C3: 浮动 tab 与邻接面

- [ ] FloatingBottomTab 传入四枚（GalleryScreen.kt:843-862）CameraAlt/ChatBubble/Sell/AccountCircle → Outlined.*（CameraAlt→Outlined.CameraAlt 或 PhotoCamera 对齐画布 ic/camera=PhotoCamera——用 PhotoCamera 对齐 spec 映射）
- [ ] 编译+ktlint；Commit 合并入 C2 或独立小 commit

### C4: 主题钉青玉 + 编辑器选中态

- [ ] MainActivity.kt:148 + BackupRestoreActivity.kt:72 `PoLangTheme(themeMode)` → 传 `dynamicColor = false`（Theme.kt 默认值同步改 false 防新调用点漏传）
- [ ] editor 5 处裸 FilterChip（EditorBottomBar:32/CropPanel:56/AdjustPanel:123/MarkupPanel:128/ImageEditScreen:222）抽 `EditorChip` 组件钉 `selectedContainerColor = MaterialTheme.colorScheme.primaryContainer, selectedLabelColor = onPrimaryContainer`（对齐画布 token 映射 primaryContainer/onPrimaryContainer）
- [ ] 编译；Commit `feat(ui): dynamicColor 全局关闭钉青玉 + 编辑器选中态 primaryContainer 化`

### C5: chat 残余清除

- [ ] FloatingChatBubbleService.kt:670-671 蓝 → ChatBubbleTokens.brandGradientStart 系（背景青玉淡/图标青玉）
- [ ] values/colors.xml purple_500/700 死资源删除（grep 确认零引用）
- [ ] LlmModelManagerScreen getTagColor 手写色改引用 ModelCenterTokens（消抄写漂移）
- [ ] 编译；Commit `chore(ui): chat 残余蓝清除——悬浮气泡青玉化+死资源+token引用收敛`

### C6: Android 收口

- [ ] `./gradlew ktlintCheck detekt :androidApp:compileDebugKotlin` 全绿
- [ ] `python3 scripts/gen-design-tokens.py --check` 绿（防误碰生成物）
- [ ] AppTopBarTest（JVM 部分可跑则跑）+ 相关单测
- [ ] 真机截图基线重采（顶栏图标全局视觉变更，按 ui-parity 流程重采 refs/android 顶栏截图——需设备，无设备则登记待采）
- [ ] 更新 docs/08-UI-SPECS/screens/topbar.yaml 头注「双端代码已落地」+ Commit

### C7: iOS 小项（本波）

- [ ] ChatView.swift:357 发送钮 `.foregroundColor(.accentColor)` → 青玉品牌（brandGradientStart 实色或对齐 Android 渐变圆钮+白 icon，取轻量：图标+底色用 ChatBubbleTokens）
- [ ] xcodegen 无涉（无新文件则直接 build）+ iOS 编译绿（Intel 机注意 device 构建）+ Commit
- [ ] **登记后续波**：mat_* 60 枚 SVG outlined 资产替换（资产生产任务，走 /ios-follow 或独立轮）

## 验收
1. 顶栏/搜索框/浮动 tab 图标全部 Outlined 系（grep Icons.Rounded 在 topbar/search 域=0；camera 域豁免）
2. Android 12+ 真机视觉 = 青玉 scheme（dynamicColor 关）
3. 编辑器选中态 chip = primaryContainer 青玉
4. ktlint/detekt/编译/gen --check 全绿；通配导入清零
5. iOS 发送钮非系统蓝

## 自审
覆盖普查 A1/A2/A3/B1/B2/C 残余/D 门禁；B3 iOS 编辑器已合规不动；相机冻结排除显式化；语义色保留显式化。✓
