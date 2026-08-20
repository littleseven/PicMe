# Google Play 关键词优化（ASO）方案

> **日期**：2026-08-08
> **应用**：破浪相册 / PoLang（`com.mamba.picme`，v1.0.33）
> **目标**：提升搜索曝光 / 下载量
> **覆盖语言**：en-US + zh-CN + zh-TW
> **配套文案**：`androidApp/src/main/play/listings/{en-US,zh-CN,zh-TW}/`（2026-08-20 自 `google-play-listing/` 迁移至 GPP 约定目录，自动化同步）

---

## 1. 背景与诊断

应用已在 Google Play 上架（"PoLang Gallery"，开发者 budao，类目 Photography，免费无广告），但存在两个叠加的 ASO 问题：

### 问题 1：标题浪费最高权重字段

Google Play 标题（≤30 字符）是最强排名信号（[AppFollow](https://appfollow.io/blog/app-store-optimization-title)、[PlayAudit](https://playaudit.app/blog/google-play-aso-guide)）。原标题 `PoLang Gallery` 把 "PoLang"（零搜索量品牌词）放在最前，约 7 个字符的高价值空间被浪费。

### 问题 2：Google 误归类（致命）

线上 listing 的 "Similar apps" 是 *Mobile Security / Smappee / Automotive Keyboard*——与摄影完全无关。Google 未把本应用聚类到摄影/相册主题。由于 Google Play **无独立关键词字段**，全文索引 title + short + full description（[AppLaunchFlow](https://www.applaunchflow.com/blog/app-store-keyword-research-2026)），需通过标题+描述中 `photo gallery / album / editor` 等核心词的自然高密度重复来重建主题相关性。

### 当前状态

- 安装量：`1+`（基本零曝光，等于白纸——无历史包袱）
- 已有英文描述但缺中文标题/本地化文案
- 现状短描述重复品牌词，未充分利用关键词

---

## 2. 策略

### 2.1 主锚词方向（已选 A）

| 方案 | 主锚词 | 取舍 |
|------|--------|------|
| **A：AI 相册锚定（选定）** | photo gallery / AI gallery / 相册 | 意向池最大、竞争适中，与应用「相册」本色一致 |
| B：AI 修图锚定 | ai photo editor / 修图 | 搜索量高但直面美图/醒图/Picsart 巨头 |
| C：隐私/离线差异化锚定 | private / offline gallery | 几乎无竞争但搜索量小、起量慢 |

A 为主锚放标题；B/C 及搜索、整理、抠图、美颜等作为**支撑词**铺进短/长描述。品牌 "破浪 / PoLang" 保留但后置，让出标题前段给关键词。

### 2.2 关键词地图（按字段权重分配）

| 集群 | EN | zh-CN | zh-TW | 落点 |
|------|----|-------|-------|------|
| 主锚：AI 相册 | ai photo gallery, photo gallery | AI 智能相册, 相册 | AI 智慧相簿, 相簿 | 标题+短+长 |
| 修图/编辑 | ai photo editor | AI 修图 | AI 修圖 | 短+长 |
| 搜索 | photo search | 自然语言搜索 | 自然語言搜尋 | 短+长 |
| 整理/管理 | photo organizer | 智能整理 | 智慧整理 | 短+长 |
| 隐私/离线 | private, on-device | 隐私, 本地 | 隱私, 本機 | 短+长 |
| 抠图/证件照 | background eraser, id photo | 抠图, 证件照 | 去背, 證件照 | 长 |
| 美颜/滤镜 | beauty, filter | 美颜, 滤镜 | 美顏, 濾鏡 | 长 |

差异化定位：**隐私优先的端侧 AI 相册 + 自然语言搜索 + 对话式修图**——Google Photos 是云端（隐私痛点），纯相册应用非 AI 对话，这是本应用独特切入点。

---

## 3. 文案（三语）

### 标题（≤30 字符）

| 语言 | 标题 | 字符 |
|------|------|------|
| en-US | `AI Photo Gallery - PoLang` | 25 |
| zh-CN | `AI 智能相册 · 破浪相册` | 14 |
| zh-TW | `AI 智慧相簿 · 破浪相簿` | 14 |

### 短描述（≤80 字符）

- en-US（75）：`Private AI photo gallery: search, edit & organize photos with on-device AI.`
- zh-CN（48）：`隐私优先的 AI 智能相册：自然语言搜索、AI 修图美颜、智能整理，全程本地处理，照片绝不上传。`
- zh-TW（48）：`隱私優先的 AI 智慧相簿：自然語言搜尋、AI 修圖美顏、智慧整理，全程本機處理，照片絕不上傳。`

### 完整描述（≤4000 字符）

完整文本见 `androidApp/src/main/play/listings/<locale>/full-description.txt`。三语结构对齐，核心词（photo gallery / album / editor / search / on-device）自然重复 3–5 次，重建摄影主题聚类。

> 设计取舍：完整描述**未纳入 IM 远程控制**（飞书/Telegram），因该功能实验性且会冲淡主线关键词密度。

---

## 4. 文案之外的配置（同样影响曝光）

| 项 | 建议 | 优先级 |
|----|------|--------|
| Play Console Tags | 勾满 5 个最相关：Photo Gallery / Photo Editor / Beauty / Filter / Camera（具体可选集由 Google 定义） | 高 |
| Category | 保持 `Photography`（正确，勿动） | — |
| 截图文案 | 首图强化差异化：自然语言搜索 / 对话式修图 / 本地不上传（影响转化） | 中 |
| 开发者名 `budao` | 可选改为 `PoLang` 提升品牌识别 | 低 |

---

## 5. 迭代与衡量

ASO 是迭代过程，非一次性：

1. **上线新文案**后，标题改动触发 Play 重新审核（通常 1–3 天）。
2. **2 周后**观察：「Similar apps」是否回到摄影类（误归类修复信号）；目标关键词（photo gallery / ai gallery / 相册）是否进入索引。
3. **4 周后**看搜索曝光、商店获取转化率，对低效词做第二轮调整。
4. 持续监测：Play Console → **Statistics → Store performance**（关键词/搜索来源）。

---

## 6. 来源

- [AppFollow — ASO Title Playbook 2026](https://appfollow.io/blog/app-store-optimization-title)
- [PlayAudit — Google Play ASO Guide](https://playaudit.app/blog/google-play-aso-guide)
- [AppLaunchFlow — App Store Keyword Research 2026](https://www.applaunchflow.com/blog/app-store-keyword-research-2026)
- [42matters — Google's new ASO guidelines (title crackdown)](https://42matters.com/blog/?p=googles-new-aso-guidelines-may-impact-385k-android-apps)
- 竞品参照：[Google Photos](https://play.google.com/store/apps/details?id=com.google.android.apps.photos)、[AI Gallery](https://play.google.com/store/apps/details?id=gallery.ai.photoeditor)
- 本应用：[polang.net](https://polang.net/)、[Play 线上 listing](https://play.google.com/store/apps/details?id=com.mamba.picme)
