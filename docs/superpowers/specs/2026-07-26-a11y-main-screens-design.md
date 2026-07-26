# 三大主页面无障碍（ui-driver / TalkBack）适配设计

> 日期：2026-07-26
> 范围：`Gallery` + `Camera` + `Chat` + `PhotoEditor` 四大主页面
> 目标：在不改架构的前提下，补齐无障碍语义，使 ui-driver 能稳定定位、TalkBack 能正确朗读。

## 1. 背景

PoLang 的 UI 自动化走 `ui-driver`（`scripts/ui_driver.py` + `PoLangAccessibilityService`），通过 AccessibilityNode 树的 `contentDescription` / `text` / `bounds` 定位元素，刻意避免截图+图像识别（分辨率/主题/动画敏感、维护成本高、token 昂贵）。

实测发现四大主页面部分元素对该机制不友好：相机工具按钮整列失明、相册缩略图把文件名塞进 `contentDescription`（TalkBack 会朗读 `20260610-203541.jpg`）、聊天输入框无 placeholder、底部 Tab 靠子节点 text 间接暴露语义导致 `ui_driver.py click --text` 直接失败。

## 2. 现状审计（实测 + 静态）

通过 `PoLangAccessibilityService` dump 真实无障碍树 + 源码静态统计，四页问题计数：

| 页面 | A 完全盲区 | B 文件名当 desc | C 容器靠子 text | D EditText 无 hint | 严重度 |
|---|---|---|---|---|---|
| Camera | **14**（左栏5 Button / 右栏6 Button / 底部3 View） | 0 | 8 | 0 | 🔴 最差 |
| Chat（Gallery 内嵌对话态） | **7**（裁切区 喜欢/不喜欢/更多类似） | **6**（缩略图） | 19 | 1 | 🔴 |
| Gallery | 0 | 0 | 12（底部 Tab / 顶部栏） | 0 | 🟡 |
| Editor | 静态较规范（IconButton 多、`.clickable` 少） | — | — | — | 🟢 待实测 |

根因：
- Camera 大量复用 `ControlButton` / `BeautyEntryButton`（`features/camera/components/CameraControlButtons.kt`），其内部 `Icon(contentDescription = null)` **硬编码为 null** 且组件不接受 desc 参数 → 所有相机工具按钮整列失明。
- Chat 缩略图组件直接把媒体文件名写进 `contentDescription`。
- 多处 `Box + Modifier.clickable` 容器自身无 desc，仅靠子节点 `Text` 暴露语义。

## 3. 问题分类

| 类 | 描述 | 处理 |
|---|---|---|
| **A** | clickable 元素自身及整棵子树均无 text/desc —— ui-driver 完全无法定位、TalkBack 不朗读 | **必修** |
| **B** | 用媒体文件名当 `contentDescription` —— TalkBack 朗读文件名 | **必修** |
| **C** | clickable 容器自身无 desc，仅靠子节点 text 间接暴露 —— ui-driver `click --text` 失败、TalkBack 行为不一致 | **关键容器必修**（Tab/工具栏/顶部栏） |
| **D** | EditText 无 placeholder hint —— ui-driver 难判断输入框用途 | **必修** |
| **E** | 缺 `testTag` —— Compose UI Test / `find_nodes(testTag=)` 不可用 | **本次不做** |

## 4. 方案选型

**选定：方案① 就地补 desc + 关键组件加参数**

- 对反复复用的按钮组件（Camera `ControlButton` / `BeautyEntryButton` 等）增加 `contentDescription: String` 形参，内部 `Icon(contentDescription = contentDescription)` —— 一处改、所有调用处受益。
- 单点的 `Box + Modifier.clickable`，用 `Modifier.semantics { contentDescription = ... }`（必要时 `mergeDescendants = true`）就地补。
- B 类：缩略图 `contentDescription` 从文件名改为语义化文案。
- D 类：EditText 补 `placeholder` / `label`。

备选（未采用）：
- 方案② 抽统一 `A11yIconButton` / `A11yTab` 强制 desc —— 重构面大、超出"补 desc"目标、违反 YAGNI。
- 方案③ 纯容器层 `mergeDescendants` 聚合 —— 需逐个理清语义树、复杂；其手法作为方案①处理 C 类容器的局部手段保留。

## 5. 逐页设计

### 5.1 Camera（14 盲区，最严重）

**核心修复**：`CameraControlButtons.kt`
- `ControlButton(icon, onClick, ...)` → 增加 `contentDescription: String` 形参；`Icon(contentDescription = contentDescription)`。
- `BeautyEntryButton(...)` → 增加 `contentDescription`；内部 `Icon(contentDescription = ...)`。

**ControlButton 调用处补文案**（`CameraLeftControls` / `CameraRightControls`）：

| 按钮 | 图标 | desc（中） | desc（英） |
|---|---|---|---|
| 返回 | ArrowBack | 返回 | Back |
| 重置相机状态 | Refresh | 重置相机状态 | Reset camera state |
| 日志浮层（debug） | Terminal | 日志浮层 | Log overlay |
| 释放 LLM（debug） | Psychology | 释放 LLM | Release LLM |
| 释放人脸检测（debug） | Face | 释放人脸检测 | Release face detection |
| 美颜 | AutoFixHigh | 美颜 | Beauty |
| 画幅比例 | AspectRatio/Crop169/CropSquare/CropFree | 画幅比例 | Aspect ratio |
| 参考线 | GridOn | 参考线 | Grid |
| 场景 | Landscape | 场景 | Scene |
| 滤镜 | FilterBAndW | 滤镜 | Filter |
| 专业模式 | Tune | 专业模式 | Pro mode |

**底部 3 控件**（`CameraBaseComponents.kt`）：快门「拍摄 / Shutter」、相册缩略图「相册 / Gallery」、切换镜头「切换镜头 / Switch camera」。

**C 类容器补 desc**：变焦按钮（`0.6x`/`1x`/`2x`/`3.2x` →「0.6 倍」…）、模式 Tab（视频/照片/文档）、语音控制、AI 智能助手。

### 5.2 Chat / 对话态

| 元素 | 当前 | 改后（中 / 英） |
|---|---|---|
| 图片缩略图（B 类） | desc=`20260610-203541.jpg` | 「照片 / Photo」（视频→「视频 / Video」） |
| 喜欢（A 类，裁切区失明） | 无 | 「喜欢 / Like」 |
| 不喜欢 | 无 | 「不喜欢 / Dislike」 |
| 更多类似 | 无 | 「更多类似 / More like this」 |
| 顶部返回 | 子 text | 容器 desc「返回 / Back」 |
| 打开侧边栏 | 子 text | 「打开侧边栏 / Open sidebar」 |
| 清空聊天 | 子 text | 「清空聊天 / Clear chat」 |
| 新建聊天 | 子 text | 「新建聊天 / New chat」 |
| 设置 | 子 text | 「设置 / Settings」 |
| 底部相册 | 子 text | 「相册 / Gallery」 |
| 切换到语音 | 子 text | 「切换到语音 / Switch to voice」 |
| 聊天输入框（D 类） | 无 hint | placeholder「输入消息… / Type a message…」 |

### 5.3 Gallery

底部 Tab + 顶部栏 clickable 容器补 desc（当前靠子 text，`ui_driver.py click --text` 实测失败）：

| 元素 | desc（中 / 英） |
|---|---|
| 相机 Tab | 相机 / Camera |
| 聊天 Tab | 聊天 / Chat |
| 模型中心 Tab | 模型中心 / Model center |
| TAG 扫描控制 | TAG 扫描控制 / Tag scan control |
| 开始扫描 | 开始扫描 / Start scan |
| 搜索照片 | 搜索照片 / Search photos |
| 分组 | 分组 / Group |
| 设置 | 设置 / Settings |

日期分组头（`2026-07-25 (4)`）与视频卡片：已有语义子 text，**评估后按需补**，不强制。

### 5.4 PhotoEditor

静态看较规范（`IconButton` 12、`contentDescription` 23、`.clickable` 0）。实施时先用 ui-driver dump 实测，按 A/B/C/D 同标准补漏（预期工作量小）。重点核验 `EditorTopBar` / `EditorBottomBar` / 各 `*Panel` 的工具按钮。

## 6. i18n（CLAUDE.md 红线，强制）

- 所有新增 `contentDescription` / `placeholder` 一律走 `stringResource(R.string.xxx)`，禁止字面量。
- 三语同步：`values/strings.xml`（EN 默认）、`values-zh-rCN/strings.xml`（简中）、`values-zh-rTW/strings.xml`（繁中）。
- 繁中在简中基础上转写（如「相册」→「相冊」、「视频」→「影片」、「参考线」→「參考線」、「切换镜头」→「切換鏡頭」）。
- 新增 string 命名建议前缀 `a11y_`（如 `a11y_shutter`、`a11y_beauty`、`a11y_tab_camera`）便于检索与回归。

## 7. 验证标准

实施后用 ui-driver dump 复测四页，全部满足：
1. **盲区 = 0**（clickable 且子树全无 text/desc 的节点数为 0）
2. **文件名当 desc = 0**（无 `\d{8}-\d{6}.jpg` 之类出现在 text/desc）
3. **EditText 无 hint = 0**
4. **关键 C 类容器均有自身 contentDescription**（底部 Tab / 顶部栏 / 相机工具栏 / 编辑工具栏 / 聊天操作栏）
5. 回归：四页可被 ui-driver 通过 `contentDescription` 稳定点击主操作（如相机快门、Gallery Tab 切换、Chat 发送）。

复测脚本：复用本设计阶段编写的前台 dump 分析逻辑（盲区 / 文件名 / EditText hint 三项自动扫描），纳入验证步骤。

## 8. 范围外（本次不做）

- 不加 `testTag`（E 类）。
- 不重构为统一 `A11yIconButton` / `A11yTab` 组件（方案②）。
- 不改二级页（Settings / Debug / TagViewer / ModelCenter 等），仅四主页面。
- 不改业务逻辑与交互行为，仅补无障碍语义。
- 不处理键盘焦点/触觉反馈等更广义 a11y 议题。

## 9. 风险与对策

| 风险 | 对策 |
|---|---|
| `ControlButton` 改签名波及所有调用处 | 全量更新调用处；编译期保证（缺参数即编译失败） |
| 三语翻译工作量 | 文案均为短词，集中在一张表，一次性翻译 |
| ui-driver 复测依赖设备/无障碍服务状态 | 实施前确认 `adb forward` + 无障碍服务已启用 |
| 改动触及正在变动的页面（如 Gallery 首页仍在开发） | 改动限定在无障碍语义层，不动布局/状态，降低冲突 |
