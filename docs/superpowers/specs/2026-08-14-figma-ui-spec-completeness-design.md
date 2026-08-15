# 设计：Figma 作为 UI & 样式 规格中枢 + 完整性闸门（Android → Figma → iOS）

> **日期**：2026-08-14
> **状态**：✅ **先导已执行完毕（2026-08-15，见下方执行结果）**；铺开其余屏待定
> **先导屏**：相机页（压力测试，状态最丰富）
> **基准**：Android `main` 为 ground truth；契约 SSOT 复用 `specs/screens/*.yaml` 的状态机
> **动机**：iOS 对齐 Android 时**完整性（漏东西）**最卡——AI 读 YAML+源码脑补，漏元素/漏状态；验收靠人眼，漏了不知。
>
> ---
> ## ✅ 执行结果摘要（2026-08-15，分支 feat/figma-ui-spec-camera-pilot）
>
> **管线全通**：Android 真机自动采集(ui_driver)→ Figma 10 帧设计稿(156 token Variables)→ 完整性闸门(match.py, 真机 XCUITest dump)→ iOS 面板还原 → **闸门 6 面板状态全绿(missing=0)**。
>
> **iOS 还原交付**（对标 Android,经闸门+用户真机验收）：
> - 美颜 tab 文字标签（面部精修/妆容调节,三语）——修复 VoiceOver 读不出
> - selector 三面板重构为 Android 真身**底部矮 chip 行**（原为错误的高面板壳）+ 真实文案（全屏/关闭/九宫格/黄金比例/拍月亮——修复**中机英文裸奔**）
> - 滑杆换自绘 AppSlider（系统 Slider 观感完全不同）、filter 面板高度 50%→280pt、chip 选中 primaryContainer、Pro 满宽
> - bug 修：Pro+比例双面板（抑制条件漏 .ratio）、面板彻底互斥、点空白收起
>
> **成功标准核验**：缺口数>0 ✓（首轮闸门 missing=6~12/面板,全部真实缺口）；6 面板闸门全绿 ✓；用户真机验收 ✓。
>
> **关键教训**：①文案/结构必须锚 a11y 实测,勿从 spec yaml 猜（曾致闸门"两错相符"假绿）②token/组件常"已定义未使用"（AppSlider/filterSelectorHeight）,还原先查现有资产 ③iOS dump 用 -openPanel 启动参数确定性驱动,面板间点击切换 flaky。
>
> **未竟**：beauty_makeup tab 内容 iOS 仍占位、idle 的 AI FAB(机-22 功能缺口)、token 导出回路(Figma→JSON 再生)、其余屏铺开。

---

## 1. 问题定义

### 1.1 真实痛点：完整性（漏东西）

"漏东西"发生在**两个时机**，杠杆不同：

| 时机 | 现象 | 根因 | 杠杆 |
|---|---|---|---|
| **实现时漏** | AI 做 iOS 时没做某元素/状态 | 读 YAML+源码**脑补**，文本规格记不全分支 | **视觉靶子**（让 AI 看到全部） |
| **验收时漏** | 做完了没人发现少了 | 验收靠人眼/截图，**无机器逐元素核对** | **结构化 diff 闸门** |

**范围限定**：本设计只解决**视觉/结构完整**（每个元素、每个状态都画了/做了）。它**不**负责建功能（搜索/录像等 Phase 6 大功能——Figma 画了不等于 iOS 能跑）。

### 1.2 为什么是 Figma + 闸门 的混合

- **Figma 的独特价值**：同一份规格，**AI 可程序读（MCP）+ 人可可视化改（Figma app）**。YAML 能读不能看；截图能看不能改；Figma 两者都行。
- **Figma 治"实现时漏"**：一帧把所有元素画出来 → AI 看图复刻 → 不漏。这是项目 `IOS_ANDROID_UI_PARITY.md §6` 证伪反模式（读源码脑补→翻译）的**反面**——从脑补切到视觉。
- **但 Figma 治不了"验收时漏"**：一帧不会自己说"iOS 少了 3 个元素"。那得靠**机器逐元素 diff**。
- 所以：**Figma 管"描述完整 + AI 读 + 人改"；diff 闸门管"证明 iOS 全实现了"**。两者都做，两个阶段的漏都堵。

---

## 2. 背景：业内成熟度与项目既有体系

### 2.1 业内成熟度（2026-08 核实）

| 链路 | 成熟度 | 结论 |
|---|---|---|
| 原生 App → Figma（capture/reverse） | ❌ 最弱最新 | 无工具能干净抓原生 Compose；Figma "Claude Code to Figma" 是浏览器优先；`generate_figma_design` 只抓 web。**必须锚真机截图，不能源码自动导出** |
| Figma → SwiftUI（codegen） | ⚠️ 有但不稳 | Dev Mode/Builder.io/Locofy 只产初稿，复杂自定义 UI 要大量手改。**只当骨架/靶子，不当自动流水线** |
| 设计 token 双向同步 | ✅ 成熟 | `Figma Variables ↔ Tokens Studio/Style Dictionary ↔ 代码`。项目 `design-tokens.json` 天然是入口 |
| Code Connect（组件映射） | ✅ 成熟 | Figma 组件 ↔ 代码组件映射，适合组件级对接 |

### 2.2 项目既有对齐体系（本设计在其上叠加）

- **结构 SSOT**：7 份 `specs/screens/*.yaml`（含状态机）
- **视觉地面真值**：真机截图 + 视图树 dump，归一化"占屏比 + dp"
- **token SSOT**：`shared/src/commonMain/resources/design-tokens.json`（双端共享）
- ⚠️ **证伪反模式**：`IOS_ANDROID_UI_PARITY.md §6`——"读源码脑补布局再翻译"两轮返工，真机验收"完全对不上"。**地面真值 = 真机截图，不是源码、不是设计画布。**

---

## 3. 架构：Figma 作为 UI & 样式 规格中枢

```
            Figma 设计文件 = UI & 样式 规格中枢（SSOT）
            ├─ 一屏 = 一个 page；一状态 = 一个 frame（帧名 = 状态 id）
            ├─ 样式 = Figma Variables（由 design-tokens.json 一次性播种）
            ├─ AI 直读(MCP)：get_design_context / get_variable_defs / get_metadata
            └─ 人手调：Figma app 改值/挪位/换色 → AI 重读
                          │
        ┌─────────────────┼─────────────────┐
        ▼                 ▼                 ▼
   ① 真机截图锚定     ② 行为/状态机        ③ iOS 实现 + 完整性闸门
   (地面真值护栏,      (Figma 表达不了,      读 Figma 帧 + 精简 YAML → SwiftUI;
    保 Figma 不飘)      留在精简 YAML)       iOS a11y 树 vs Figma 帧 = 不漏闸门
```

### 3.1 职责切分（谁放哪）

| 内容 | 归属 | 理由 |
|---|---|---|
| 结构（元素树/布局/anchor/尺寸） | **Figma 帧** | 可视化、可改、AI 可读 |
| 样式（配色/间距/字阶/圆角） | **Figma Variables** | token 机制，人可改，`get_variable_defs` 可读 |
| 行为/状态机（按钮干嘛、状态怎么转） | **精简 YAML** | Figma 表达不了逻辑 |
| iOS 范围 / 允许的平台差异 | **精简 YAML** | 非 UI 描述 |

**状态 id 是连接键**：Figma 帧名 = 精简 YAML `states[].id`（如 `panel_beauty_face`）。Figma 和 YAML 是同一状态的两个视图，靠 id 对齐。现有 7 份 YAML 瘦身（结构/样式移走，留行为/状态机/差异）。

### 3.2 完整性不变量（整个方案的契约）

对每屏每个状态，三者必须一一对应：

| 状态 | ① Figma 帧（锚真机截图） | ② diff 核对（iOS a11y vs Figma 帧） | ③ iOS 该状态实现 |

**任何一格空缺 = 一个被追踪的 gap**。完整性从"人眼尽量别漏"变成"清单逐项勾掉"。

---

## 4. 组件 A：Figma 文件结构

### 4.1 文件骨架

```
PoLang UI Spec（新建 Figma 设计文件）
├─ Variables 集合 "PoLang Tokens"（Light / Dark 双 mode）   ← 由 design-tokens.json 播种
│   ├─ color / spacing / typography / radius
│   └─ 编辑它 = 改一处，所有帧 + 导出 JSON 同步变（治"样式调整麻烦"）
├─ Page: Camera（先导）
│   ├─ Frame "camera/idle"
│   ├─ Frame "camera/panel_beauty_face"
│   ├─ Frame "camera/panel_beauty_makeup"
│   ├─ Frame "camera/panel_filter"
│   ├─ Frame "camera/panel_ratio"
│   ├─ Frame "camera/panel_scene"
│   ├─ Frame "camera/panel_grid"
│   ├─ Frame "camera/panel_pro"
│   ├─ Frame "camera/capturing"
│   ├─ Frame "camera/focusing"
│   └─ Frame "camera/permission_denied"
├─ Page: Settings / Chat / Gallery / Person / Editor / ModelDownloadCenter（后续）
```

- **帧规格**：viewport 基准 iOS 375×812pt / Android 360×800dp（parity 文档 §1.2）；只用 dp/pt 数值，禁止 px（§1.1）。
- **每帧旁锁一张真机截图**做地面真值锚——护栏的可视版（帧 ≠ 截图即规格失效）。截图放 `specs/screens/refs/android/<screen>-<state>.png`。
- **帧内样式全部引用 Variables**（颜色/间距/字阶），不写裸值——这样改 Variable 即全局生效。

### 4.2 样式层：token 播种 + 导出回路

- **一次性播种**：`design-tokens.json` → Figma Variables（经 `use_figma` 的 `figma.variables.createVariable` 程序化建集合 + Light/Dark mode）。
- **SSOT 方向（目标态）**：Figma Variables 成为样式唯一源；`design-tokens.json` 改为**从 Figma 导出再生**，保证代码侧仍读 JSON、且不再有两份源头。
- **导出工具**：Tokens Studio（Figma 插件，导出 JSON/Git）或直接 Figma API 脚本（读 Variables → 写 JSON）。**具体选型留 writing-plans 定**。
- **iOS 侧**：`DesignTokens.swift` 仍由 token 派生（不直接改手维护的值）。

### 4.3 ⚠️ 反模式护栏（升级为 SSOT 后最重要）

Figma 成了规格，**最大新风险** = 直接读 Compose 源码生成 Figma 然后宣称为 SSOT——这是 §6 证伪反模式被正式化为规格源头，危害更大。

**护栏写死**：
1. 每个 Figma 帧**必须锚在真机截图**（截图=地面真值，源码只做交叉校验）。
2. 建帧流程：**先放截图 → 照着画 → 画完比对截图**。源码只用来查"这个具体值是多少"（dp/颜色），不用来"猜布局"。
3. Figma 是"可改的规格"，但"改完仍须对得上真机截图"，否则规格无效。

---

## 5. 组件 B：完整性闸门（验收时漏的机器核对）

### 5.1 机制

对每个状态：
```
Figma 帧 node 树(get_metadata)   ←→   iOS 该状态 a11y 树(XCUITest app.descendants())
   按 label + role + 结构位置 匹配（归一化到 dp/pt + 语义 label/role）
   Figma 有、iOS 无 = 🔴 完整性缺口(fail)
   iOS 多出来的    = 🟡 警告
```

- **主闸门 = iOS vs Figma 帧**（规格符合性：规格里的东西都做了吗）。
- **副闸门 = iOS vs Android uiautomator dump**（设备一致性：顺带验 Figma 帧没飘离真机——若 iOS 漏的元素 Android 也有而 Figma 帧 没有，说明 Figma 帧本身错了）。
- **脚本**：`scripts/completeness-check.sh <screen> [--state <id>]`，接入 `scripts/ai-gate.sh` / dev-loop，非零退出 = 有缺口。

### 5.2 工程难点（留计划细化）

Figma 节点 ↔ iOS a11y 元素**不是 1:1**：Figma 有装饰性分组/容器，iOS 是语义元素。需**归一化匹配规则**：按 `accessibility_label`/`role` 优先匹配，忽略装饰层；位置/尺寸仅作辅助验证（尺寸偏差属"还原度"另一个痛点，本闸门只判"在不在"）。

---

## 6. 组件 C：精简 YAML（行为规格）

现有 `specs/screens/camera.yaml`（1376 行）混了结构/样式/行为。瘦身后：

- **保留**：`panel_state_machine`、`back_stack`、`states`（状态枚举）、`allowed_differences`、各控件 `click`/`action`/`show_when`（行为与触发）、`*_defaults`（默认值）。
- **移除**：元素树结构、anchor/size/token 数值引用、`active_state`/`inactive_state` 的视觉描述（这些进 Figma 帧）。
- **状态 id 对齐**：YAML `states.interaction_state`（idle/panel_open/capturing/recording/focusing）+ panel 可见性 → 拆成 Figma 帧名（见 §7）。YAML 保留状态枚举与转移规则，Figma 帧承载每个状态的视觉。

> 瘦身是渐进的：先导屏（相机）做完整瘦身做模板，其余屏随对齐进度推进。

---

## 7. 先导屏：相机页（压力测试）

### 7.1 为什么选相机

状态最丰富（5 Primary 互斥面板 + ProMode 独立轨道 + 拍照/录像/文档模式 + 对焦/快门态 + 权限态），是完整性痛点最典型的屏。若这套机制能在相机页跑通，铺开其余屏风险低。

> **🔴 用户明确诉求（2026-08-14）**：相机页**各入口点击后的面板弹框及样式**（美颜/滤镜/比例/场景/网格/ProMode 6 类面板）iOS 与 Android **差距非常大**，要求**完全对齐**。因此 **§7.2 的 7 个 panel_* 帧是优先级 1**，是本先导的核心交付；`idle/capturing/focusing/permission_denied` 是优先级 2。
>
> **"完全对齐"如何达成**：不只是"元素在不在"（完整性），还包括面板的尺寸/间距/配色/比例（还原度）。本设计对面板的还原度由两条覆盖——
> - **实现时**：Figma 帧承载**精确 token 值**（面板高度比、间距、圆角、配色全引 Variables），AI 读帧 1:1 实现到 SwiftUI，不靠目测；
> - **验收时**：副闸门（iOS vs Android uiautomator dump）逐元素核对**归一化 bounds**（dp/pt，非 px），面板高度比/控件尺寸/锚定位置偏差即 fail。
>
> 即面板的"完全对齐"= 精确值帧实现 + bounds 闸门验收，**不需要**独立的像素级 diff 流水线（那是过度工程）。

### 7.2 状态 → Figma 帧矩阵（来自 camera.yaml §18）

| 帧名（=state id） | 来源状态 | 视觉特征（建帧锚点） |
|---|---|---|
| `camera/idle` | interaction_state=idle, PHOTO | 预览 + 顶栏按钮 + 底部控件，无面板 |
| `camera/panel_beauty_face` | showBeautySelector, beauty_tab=FACE | 美颜面板，FACE 4 项 |
| `camera/panel_beauty_makeup` | showBeautySelector, beauty_tab=MAKEUP | 美颜面板，MAKEUP tab |
| `camera/panel_filter` | showFilterSelector | 滤镜面板（9 色调 + 5 风格） |
| `camera/panel_ratio` | showRatioSelector | 比例选择器（4:3/16:9/FULL） |
| `camera/panel_scene` | showSceneSelector | 场景选择（NONE/NIGHT/MOON） |
| `camera/panel_grid` | showGridSelector | 网格选择（NONE/THIRDS/GOLDEN） |
| `camera/panel_pro` | showProPanel | ProMode 面板（EV/WB/对比度/饱和度/色温） |
| `camera/capturing` | interaction_state=capturing | 快门动画 + 闪屏（黑），交互禁用 |
| `camera/focusing` | interaction_state=focusing | focus_ring 显示 |
| `camera/permission_denied` | 相机权限拒绝态 | 权限引导 UI |

> **注**：控制值枚举（如具体选中哪个滤镜、哪个比例）是**帧内变体**（Figma component variant），不单独建帧——建帧时选定默认态，变体用 component set 承载。

### 7.3 相机页特有难点（留计划细化）

- **实时预览区**：帧里用占位图（一张真实预览截图）代表，不重建相机 feed。
- **叠层（十字星/网格线/对焦框）**：这些是状态相关的 overlay，按状态在帧内画出。
- **多状态截图采集成本**：需把 Android 设备驱动到 11 个状态分别截图（部分状态如 capturing 是瞬时的，需录屏抽帧或触发后快速截）。

---

## 8. 工作流（单屏闭环，先导 = 相机）

1. **枚举状态** → 相机页 11 个状态（§7.2）成 Figma 帧名 + YAML state id。
2. **采地面真值** → Android 驱动到每状态 → 截图 + `uiautomator dump` → 存 `specs/screens/refs/android/camera-<state>.{png,xml}`。
3. **（一次性）token 播种** → `design-tokens.json` → Figma Variables（Light/Dark）。
4. **建 Figma 帧** → 每状态：按截图建帧（全用 Variables）+ 锁截图锚点 + 记 node id。
5. **精简 YAML** → 相机页 YAML 瘦身（留行为/状态机，删结构/样式）。
6. **iOS 实现** → 每状态：读 Figma 帧（`get_design_context`）+ 精简 YAML 行为 → SwiftUI。
7. **跑闸门** → `scripts/completeness-check.sh camera` → iOS a11y vs Figma 帧，修到净。
8. **人验收** → 真机 iOS vs Android 截图并排（11 状态），过则相机页闭环成立。

---

## 9. 非目标（YAGNI）

- ❌ **Figma → SwiftUI 自动 codegen 流水线**（业内不成熟，只把帧当靶子，人/AI 写码）
- ❌ **全量 7 屏铺开**（先导 1 屏验证，相机页闭环成立再决定铺开节奏）
- ❌ **建缺失功能**（搜索/录像等 Phase 6，Figma 管外观不管功能）
- ❌ **像素级还原度自动化 diff**（不建独立的 pixel-diff 流水线——过度工程）。还原度由"精确值 Figma 帧实现 + bounds 副闸门"覆盖（§7.1），对先导面板达到"完全对齐"即可，不追求逐像素机器比对。

---

## 10. 风险与对策

| 风险 | 对策 |
|---|---|
| Figma 帧飘离真机（规格失效） | 护栏：每帧锁截图锚点；副闸门 iOS vs Android dump 兜底 |
| 11 状态截图采集成本高（capturing 瞬时态） | 录屏抽帧 / adb 高频截图；无法采集的状态降级用源码值 + 标注"未截图锚定" |
| Figma 节点 ↔ iOS a11y 匹配噪声 | 归一化规则只判"在不在"（label/role），尺寸偏差归还原度另处理 |
| token 双向同步工具选型 | 播种用程序化 `use_figma`；导出在计划里比选 Tokens Studio vs Figma API 脚本 |
| 瘦身 YAML 引入回归（删错行为描述） | 瘦身是"移结构/样式"非"删行为"；行为条目全保留；先导屏做完人工核对 |

---

## 11. 待决项（留 writing-plans）

1. **Figma 文件归属**：新建文件（需用户的 planKey，`whoami` → `create_new_file`）还是已有文件。
2. **token 导出工具**：Tokens Studio（插件依赖）vs Figma API 脚本（无外部依赖，自维护）。
3. **完整性闸门实现**：iOS a11y 树采集用 XCUITest 还是 Accessibility 调试 dump；Figma node 树用 `get_metadata` 还是 `get_design_context`。
4. **分支策略**：当前分支 `feat/ios-chat-rich-features` 有用户 Chat WIP；相机先导实现应**从 main 切新分支**，不污染 Chat WIP。
5. **铺开触发条件**：相机页闭环后，用什么标准决定铺开其余屏（缺口率？ROI？）。

---

## 12. 成功标准（先导屏相机页）

- [ ] Figma 相机页存在 11 个状态帧，每帧锁有真机截图锚点，且帧内样式全引 Variables。
- [ ] **优先级 1 的 7 个 `panel_*` 帧与 Android 真机"完全对齐"**：副闸门 bounds 核对（面板高度比/间距/圆角/配色）全 pass，这是本先导的核心交付。
- [ ] `design-tokens.json` 已播种为 Figma Variables（Light/Dark），且导出回路跑通（Figma 改 Variable → JSON 再生）。
- [ ] 相机页 YAML 瘦身完成（结构/样式移除，行为/状态机保留）。
- [ ] `scripts/completeness-check.sh camera` 对 11 状态全部 pass（iOS 实现了 Figma 帧的每个元素）。
- [ ] 真机验收：iOS 11 状态截图 vs Android 并排，结构零容差一致。
- [ ] **关键验证**：闭环过程中**发现并填补的完整性缺口数量** > 0（证明机制有效，不是空跑）。
