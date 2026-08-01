# 管理后台设置页 UI 翻新 · 设计

> **日期**：2026-08-01
> **状态**：已确认
> **范围**：`server/` 模块管理后台（kotlinx.html SSR），重点为 `/admin/settings` 设置页

## 1. 背景与问题

管理后台所有样式集中在 `AdminViews.kt` 的 `adminHead()` 内联 CSS（约 160 行）。设置页（`AdminViews.kt` `settingsPage()` + `whitelistSection()`）存在以下问题：

1. **样式类跨界复用**：借用渠道表单的 `chan-form`、APK 页的 `apk-info-card` / `apk-empty`、用户页的 `limit-card`，语义混乱、牵一发动全身。
2. **信息架构扁平**：「额度默认值」与「AI 工程师白名单」两个 `h1` 裸堆叠，说明文字（`p.meta` / `div.card.apk-info-card`）与所属表单分离，页面无分区感。
3. **内联样式**：输入框宽度写 `style = "width:160px"` / `style = "width:280px"`。
4. **全局 CSS 无令牌**：颜色（`#006eff`、`#0abf5b`、`#e54545`、`#ff9c00` 等）、圆角、阴影硬编码散落各处，维护成本高。

## 2. 目标

- 设置页重组为「分区卡片」信息架构：每个功能组一张 section card，内含标题 + 说明 + 表单/列表。
- 全局 CSS 令牌化（CSS 自定义属性），所有页面自动获得视觉精化，但**不动其他页面的 HTML 结构**。
- 零前端框架、零新增 JS、不改路由与后端逻辑。

## 3. 非目标（YAGNI）

- 不引入任何 CSS/JS 框架或构建步骤（保持 kotlinx.html SSR 单文件）。
- 不做深色模式 / 主题切换。
- 不迁移其他页面（overview/users/devices/traffic/channels/apk/diagnosis）的 HTML 结构到 `.page` 容器——后续按需单独做。
- 不改任何路由、表单字段、POST 端点、后端逻辑。

## 4. 设计

### 4.1 设计令牌（`:root` CSS 变量）

在 `adminHead()` 的 `<style>` 顶部定义令牌，全 CSS 改用变量：

| 类别 | 令牌 | 值 |
|------|------|-----|
| 主色 | `--c-primary` / `--c-primary-hover` | `#006eff` / `#005ce6` |
| 语义色 | `--c-success` `--c-warning` `--c-danger` | `#0abf5b` `#ff9c00` `#e54545`（各带 `--c-*-bg`、`--c-*-border` 浅色底） |
| 中性色 | `--c-text` `--c-text-2` `--c-text-3` `--c-border` `--c-bg` `--c-surface` | `#1f2d3d` `#666` `#999` `#e5e5e5` `#f0f2f5` `#fff` |
| 字号 | `--fs-xs/sm/md/lg/xl/xxl` | 12 / 13 / 14 / 16 / 20 / 24 px |
| 圆角 | `--r-sm/md/lg` | 4 / 8 / 12 px |
| 阴影 | `--shadow-sm/md` | 比现状更轻更柔的两级 |
| 间距 | 基数 4px（直接用数值，不为间距设变量） |

同时统一：
- **focus ring**：所有可聚焦控件 `:focus` 用 `box-shadow: 0 0 0 3px rgba(0,110,255,.15)` + 主色边框。
- **按钮高度规范**：主按钮 `.btn` 40px（padding 10px 20px 维持）、小按钮 `.btn-sm` 28px（维持）。
- **数字对齐**：卡片数值、统计列加 `font-variant-numeric: tabular-nums`。

### 4.2 `.page` 容器

- 新增 `.page{max-width:1200px;margin:0 auto;padding:0 24px}`。
- 设置页 body 内容（navBar 之后）整体包一层 `div("page")`，页内 `h1/h2/表格/卡片` 不再依赖 `body>h1` 等直接子元素选择器获得布局。
- 其他页面 HTML 不动，继续走现有 `body>h1,...` 选择器；两套并存不冲突（`body>*` 选择器只作用于直接子元素，`.page` 内元素由 `.page` 及其子选择器接管）。

### 4.3 设置页结构重组

新语义化类（全部新加，不复用他页类）：

```
.page
└── h1.page-title          「设置」
    ├── toast（如有 message）
    ├── section.settings-section        区块 1：额度默认值
    │   ├── .section-head
    │   │   ├── h2.section-title        「额度默认值（全局）」
    │   │   └── p.section-desc          影响说明（原 apk-info-card 内文案，移入此处）
    │   └── form.form-grid (action=/admin/settings)
    │       ├── .field                  label「新注册账号上限（free，>0）」+ number input
    │       ├── .field                  label「访客设备上限（guest，>0）」+ number input
    │       └── .form-actions           保存按钮右对齐（沿用现有类）
    └── section.settings-section#whitelist   区块 2：AI 工程师白名单
        ├── .section-head
        │   ├── h2.section-title        「AI 工程师白名单」
        │   └── p.section-desc          原 p.meta 文案（空表=所有用户可诊断…）
        ├── .whitelist-add              邮箱 input + 添加按钮（同行）
        └── table 或 .empty-state       白名单列表 / 空态
```

对应新 CSS（精选）：

```css
.page-title{font-size:var(--fs-xxl);font-weight:600;margin:24px 0 16px}
.settings-section{background:var(--c-surface);border:1px solid var(--c-border);
  border-radius:var(--r-lg);box-shadow:var(--shadow-sm);padding:20px 24px;margin:16px 0}
.section-head{margin-bottom:16px}
.section-title{font-size:var(--fs-lg);font-weight:600;margin:0 0 6px}
.section-desc{font-size:var(--fs-sm);color:var(--c-text-3);margin:0;line-height:1.6}
.form-grid{display:flex;gap:20px;flex-wrap:wrap;align-items:flex-end}
.field label{display:block;font-size:var(--fs-sm);color:var(--c-text-2);font-weight:500;margin-bottom:6px}
.field input{width:160px;padding:10px 12px;border:1px solid var(--c-border);border-radius:var(--r-sm);font-size:var(--fs-md)}
.whitelist-add{display:flex;gap:10px;align-items:center;margin-bottom:16px;flex-wrap:wrap}
.whitelist-add input{width:280px;...同 .field input}
.empty-state{text-align:center;padding:32px 24px;color:var(--c-text-3);font-size:var(--fs-md);
  border:1px dashed var(--c-border);border-radius:var(--r-md)}
```

要点：
- 去掉设置页对 `chan-form`、`apk-info-card`、`limit-card`、`apk-empty` 的引用；这些类保留给其他页面使用，不删。
- 去掉设置页所有内联 `style`。
- 页面主标题改为单个 `h1`「设置」，两个功能组降为 `h2.section-title`（语义层级修正）。
- `#whitelist` 锚点保留在区块 2 的 `<section>` 上（`/admin/settings#whitelist` 深链不破坏；原 `ai-engineer-whitelist` 301 重定向目标不变）。
- 表单字段名、action、method 全部不变。

### 4.4 移动端

`@media (max-width:640px)` 内补充：`.form-grid` 纵向堆叠、`.field input`/`.whitelist-add input` 宽度 100%、`.settings-section` padding 收敛为 16px。

## 5. 影响面

| 文件 | 变更 |
|------|------|
| `server/.../admin/AdminViews.kt` | `adminHead()` CSS 重写（令牌化 + 新类）；`settingsPage()`、`whitelistSection()` 结构重组 |
| `server/src/test/.../AdminViewsTest.kt` | 若断言涉及设置页旧 class 名 / h1 文案，同步更新 |
| 其他页面 | 仅享受令牌化后的视觉变化，HTML 零改动 |

风险：全局 CSS 变量化后颜色/阴影值微调会波及所有页面——属于本次「视觉精化」的预期效果，通过人工走查概览/渠道页兜底。

## 6. 验证

1. `./gradlew -p server build`（编译 + 全部单测，含 `AdminViewsTest`、`AdminRoutesTest`）。
2. `./server/run-local.sh start` 本地起服务，人工走查：
   - `/admin/settings`：两区块卡片化、表单可用、保存/添加/移除白名单功能正常、`#whitelist` 锚点跳转正常。
   - 抽查 `/admin`（概览）、`/admin/channels`：全局 CSS 变更无布局回归。
   - 手机窄屏（<640px）设置页表单堆叠正常。
