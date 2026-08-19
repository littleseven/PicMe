# ios-follow Stage 5 审查报告 — 图标 Outlined 化 / 分隔线统一（feat/ios-icon-outlined @ bbcdfaa20）

> 审查人：gap 审查员（独立代理交叉审查，非实现者）· 2026-08-19
> 契约：`tmp/ios-follow/icon-system-outlined/follow-plan.md`（Stage 1 输入分析）
> 实现：commit `bbcdfaa20`（29 mat_o_* imageset + 15 Swift 文件 + topbar.yaml）
> 方法：契约 A3/C/D 逐项对照 + 全量消费点↔资产交叉 grep + 资产 SVG 字形抽查 + main 分支冲突预判

## 结论

**🔴 0 项 → 可合并**（附 1 条合并冲突处置建议 + 6 条 🟡 挂账/口径记录）

---

## ✅ 通过项

### 1. 契约符合（A3 表 28 行全覆盖，零漏项零断链）

- **28+1 资产对照**：29 枚 imageset 与 A3 表逐行对上（27 必建 + `mat_o_filter_list_off` 可选已建 + `mat_o_pause`）；交叉 grep 消费点 28 个 `mat_o_*` 字面名 vs 资产目录 **comm 零差集**（无消费但缺资产=会渲染空白的情况为零）。
- **12 处 SF→MatIcon 转换**（实为 11 个转换点/15 枚字形，见 🟡-4）：PersonView×5（back/filter 双态/autorenew/info→auto_awesome/check）、PersonInfoView×3（back/undo/check）、DiagnosticLogView×1（refresh）、groupingMenu×1（sort）、EditorAction 组件级×1（覆盖 layers_clear/auto_fix_high/undo/redo/check 5 枚）。符号名与 Android Outlined.* 语义全部正确；`mat_o_auto_awesome` 落 PersonView 域菜单（契约 #18 语义对应处）核对无误。
- **字形切换 2 处**：浮动tab `camera_alt→photo_camera`（对齐 2278d6f7a）、gallery 扫描钮 `play_circle→play_arrow` ✓。
- **断链修复**：`square.dashed`→`mat_o_select_all`（GalleryGridView:145）✓；其余 body 域断链（`radio_button_checked`/`tune`/`photo`/`broken_image`）grep 证实**原样保留**，未扩面 ✓。
- **管线正确性**：`AppTopBarAction(systemName:)` 内部是 `MatIcon(name:)`（非 `Image(systemName:)`），`Image(matIcon:)` 未命中 Map 按字面名加载 → `AppTopBarAction(systemName: "mat_o_arrow_back")` 直通资产，不渲染空白 ✓。a11y fallback `topbar_\(systemName)` 仅在未传 accessibilityID 时生效，全部顶栏调用均显式传 ID，锚点无漂移 ✓。

### 2. 资产正确性抽查（4/4 通过）

`mat_o_settings` / `mat_o_account_circle` / `mat_o_photo_camera` / `mat_o_select_all`：
- 均为 **materialiconsoutlined 描边镂空字形**（settings 齿轮带中心圆孔与齿间镂空、account_circle 双环+头肩内孔、photo_camera 机身轮廓+镜头圆环、select_all 四角标+双层方框），与本地 filled `mat_*` 字形明显不同，非 filled 复制。
- 均 `M` 绝对坐标开头（避开 iOS SVG 小写 `m` 起点不渲染坑）；双 path 结构（`fill="none"` 背景 + 主 path）。
- `Contents.json` 与既有 `mat_settings` 模板同构：`preserves-vector-representation: true` + `template-rendering-intent: template`，单 SVG universal ✓。

### 3. 风险点核验

- **FloatingBottomTab 锚点**：`tabId(for:)` 4 case 全覆盖新资产名，a11y 锚点 `tab_camera/tab_chat/tab_tag/tab_person` 输出不变；`onPlaceholderTap` 改传语义 key——原传 SF 名 `"tag"` 恰与语义 key 同值，MainTabView `showPlaceholder = icon` 路由行为**零变化**，且解耦了资产名（后续再换字形不破路由）✓。
- **SettingsM3Divider 渲染语义**：`Rectangle().fill(outlineVariant.opacity(alpha)).frame(height: 0.5)` 显式绘制，落实契约 C 节「勿用 background 叠加」注意点；outlineVariant 双 mode 色源（浅 #CDC7BC / 深 #46413A）直接生效。全部 20 处使用点在 SettingsScreen/SettingsSubPages 的 **VStack 结构**（非 List），无系统 Divider 的 List inset 语义损失 ✓。`SettingsTokens.rowChevronAlpha = 0.6`（DesignTokens.swift:518）引用成立。
- **raw Divider 收口**：契约 C 节清单 19 处（SettingsScreen 6 + SettingsSubPages 13）**全部替换**，`Color(.separator)` 显式绑定（SettingsScreen:595）清除 ✓；`grep 'Divider()'` 残留 6 处均在契约清单之外（见 🟡-3）。

### 4. 越界零触碰

- 相机域文件零 diff；`MaterialIconMap` 零改动；`design-tokens.json` / `DesignTokens.swift` 零 diff，token 门禁 `--check` 复核**绿**（7 文件一致，自述属实）。
- body 域 filled 消费点原样（MediaPager 底栏 `mat_autofix`/`mat_delete`/`mat_more_horiz`、ChatThreadSidebar `mat_close`、设置分类卡、ModelCenter）；filled `mat_settings/mat_camera_alt/mat_sell/mat_account_circle/mat_chat_bubble/mat_pause` 资产目录完整未删 ✓。
- 无新 Swift 文件 → 免 xcodegen（与契约 A4 判断一致）✓。worktree 无未提交源码漂移（仅 untracked Pods/build 构建产物）✓。

---

## 🟡 建议清单（不阻塞合并）

1. **mat_o_pause 资产零消费**：契约 #12「isScanning 条件切 pause」被拒。拒因核实成立——扫描钮点击即 `fullScreenCover` 推 TagScanScreen，Gallery 顶栏无可见扫描态（"无可见时机"属实）。建议：入**平台差异台账**（Android 有 `if (isScanning) Pause else PlayArrow` 双态，iOS 静态 play_arrow），资产保留作后续恢复态用。
2. **alpha 分档参数预留未用**：契约 D.4 建议 0.3/0.5/0.6 分档，实现加参数但 20 处全走默认 0.6。iOS 消费点均为 row 间分隔线（对应 Android 0.6 档），当前等价；0.3/0.5 档 Android 落在不同组件行位（SettingsBaseComponents.kt:158/:467），iOS 暂无对应物。参数保留可接受（供 🟡-3 收口时用）。
3. **范围外残留 6 处 raw Divider**：`TagScanComponents.swift:142/154/192/194/354` + `DebugScreenView.swift:61`——契约 C 节清单未列（Stage 1 只盘点 Settings 域），实现忠实于契约。挂账：TagScan 域分隔线漂移下轮收口（届时可顺手用 alpha 分档）。
4. **commit message 计数口径**：「21 处 raw Divider」实为 **19 处**替换（契约 C 节列出 19 处但行文写 21——契约自身计数瑕疵传导）；「12 处 SF→MatIcon」按字形枚数 15、按转换点 11。无害，记录备查。
5. **DiagnosticLogView toolbar 未指定 size**：`MatIcon(name: "mat_o_refresh")` 走默认 18，原 SF symbol 随 toolbar 环境字号（≈17）。1pt 级差异，Stage 4 目检项。
6. **EditorAction 字号→帧尺寸语义**：原 `Image(systemName:).font(size: IconSize.md, weight: .medium)`（字号 22）改 `MatIcon(size: IconSize.md)`（帧 22 fit），描边字形与 SF 符号视觉重量本有差异（平台字形允许差异条款内），框 36 不变。Stage 4 双端截图目检项。

---

## 合并冲突处置建议（topbar.yaml:42）

**冲突确定发生**：merge-base `0e4216851` 在 main tip `89de72dc4` 之前（`merge-base --is-ancestor` = NO），两分支改同一行 `platforms.ios`（同源 base `"SF Symbols regular"`），措辞不同：

- main 89de72dc4：`"mat_o_* classic outlined SVG（24px @22pt，MaterialIconMap/字面名回退加载）；SF Symbols 仅为语义参考名"`
- 本分支 bbcdfaa20：`"Material Icons Outlined（mat_o_* SVG 资产，template 渲染）"`

**建议保留本分支版为主干，融合 main 版尺寸细节**，合入时手工解决为：

```yaml
ios: "Material Icons Outlined（mat_o_* SVG 资产，24px @22pt，template 渲染，字面名回退加载）"
```

理由：
1. main 版 `MaterialIconMap/字面名回退加载` **半失真**——`mat_o_*` 不在 MaterialIconMap（本 commit Map 零改动是关键设计），纯字面名回退，"MaterialIconMap/" 前缀易误导读者以为 Map 有条目；
2. `Material Icons Outlined` 是官方类别名（materialiconsoutlined），与 android 行 `Material Symbols Rounded Outlined w400` 形成家族命名对仗；
3. main 版的 `24px @22pt` 尺寸细节有价值，融合保留；`SF Symbols 仅为语义参考名` 可舍（代码内已无 SF 消费点残留于此域）。

---

## Stage 4 提醒（非本审查范围）

真机/模拟器双 mode 截图验收（分隔线显色 #CDC7BC@0.6 / #46413A@0.6、图标描边视觉重量、全选钮不再空白回归、a11y 锚点断言）在契约 Stage 4 要点中，建议合并前或紧随完成；XCUITest 套路按 `ios-xcuitest-device-verification`（硬件 UDID destination）。
