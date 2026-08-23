# Google Play 关键词优化（ASO）方案

> **日期**：2026-08-08（第一轮）／ 2026-08-24（第二轮，见 §7）
> **应用**：破浪相册 / PoLang（`com.mamba.picme`，v1.0.34）
> **目标**：提升搜索曝光 / 下载量
> **覆盖语言**：en-US + zh-CN + zh-TW + zh-HK（zh-HK 与 zh-TW 同文镜像）
> **配套文案**：`androidApp/src/main/play/listings/{en-US,zh-CN,zh-TW,zh-HK}/`（2026-08-20 自 `google-play-listing/` 迁移至 GPP 约定目录，自动化同步）
> **词表台账**：`google-play-listing/keyword-ledger.md`（2026-08-24 起，双周随搜索词报告更新）

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

---

## 7. 第二轮迭代（2026-08-24）：数据回路 + 长尾补强

> 第一轮（§1–§5）已上线：标题锚词换位、三语文案、8 槽位素材。本轮从「一次性写对文案」转向「数据回路 + 词表台账」；词表 SSOT 移至 `google-play-listing/keyword-ledger.md`，本节只记录决策与变更依据。

### 7.1 审计结论（改动依据）

对已上线文案的关键词覆盖实测（Python **字符**计数）发现缺口：

| 语言 | 缺口词 | 原状 |
|------|--------|------|
| en-US | photo album | 长描述 0 次出现（头部大词） |
| en-US | photo organizer | 只有动词 organize，缺名词检索形 |
| en-US | offline | 0 次（on-device 的搜索同义形，隐私人群高频） |
| en-US | AI photo editor（精确短语） | 只有 photo editor |
| zh 全部 | 照片管理 | 只有动词式「管理媒体文件/管理媒體檔案」 |
| zh 全部 | 图片搜索 / 圖片搜尋 | 只有「以图搜图」（反搜功能，非泛搜索词） |

另修正第一轮的测量口径错误：记录的 zh 长描述「2339 字符」实为 `wc -c` **字节**数（CJK 3 字节/字）；按字符计 zh 仅 ~920/4000，**加词余量约 3000 字符**。

### 7.2 落地变更（13 处；标题与 zh 短描述不动）

- **en-US 短描述**（76/80）：`Private AI photo gallery: search by image, organize & edit photos on-device.`——恢复第一轮落地时丢失的 organize 词簇，保留 search by image；弃 semantic（搜索量近零）。
- **en-US 长描述 3 处**：① intro 补 `a full AI photo editor` 与新句 `a smart photo album and an automatic photo organizer in one app`；② ORGANIZE 段尾补 `your photo organizer at work, keeping your photo album tidy automatically`；③ PRIVACY 段改 `run 100% on your device and work fully offline`（属实：打标/人脸/语义搜索均端侧，远程仅 chat 文本且 GOOD TO KNOW 已声明）。
- **zh-CN / zh-TW / zh-HK 各 3 处**：intro 补「既是流畅的相册/相簿，也是省心的 AI 照片管理工具」；搜索段尾补「文字搜索/搜尋、图片搜索/圖片搜尋、人脸搜索/人臉搜尋，想怎么找就怎么找/想怎麼找就怎麼找」；整理段尾补「照片管理的琐事，交给端侧 AI 就好」。

### 7.3 覆盖实测（落地后）

| 词 | en-US（短/长） | zh-CN（长） | zh-TW/HK（长） |
|----|----------------|-------------|----------------|
| photo gallery / 相册 / 相簿 | 1 / 3 | 12 | 12 |
| photo album | 0 / 2 | — | — |
| photo organizer | 0 / 2 | — | — |
| offline | 0 / 1 | — | — |
| AI photo editor | 0 / 1 | — | — |
| 照片管理 | — | 2 | 2 |
| 图片搜索 / 圖片搜尋 | — | 1 | 1 |

字符预算：en 25/76/2589，zh 14/51/924~940（各上限 30/80/4000），全部达标。

### 7.4 Console 侧待办（不依赖文案，见台账 §3）

V1 导出搜索词报告 CSV 作基线；V2 线上「类似应用」是否已回摄影类（第一轮误归类修复信号——**若未修复，修聚类优先于一切加词**）；V3 play.google.com 搜 "PoLang" 品牌词可命中；V4 Tags 勾满 5 个。

### 7.5 节奏与 KPI

- **双周**：搜索词 CSV → 台账 §4；连续两周期零曝光的补强词降级/替换。
- **4 周**：目标长尾词（private gallery / AI 相簿 / photo organizer）出现在搜索词报告 = 已索引且有曝光。
- **8 周**：搜索曝光周均值环比上升；zh-TW「AI 相簿」抽查进前 20。
- Store listing experiments 暂缓（当前流量撑不起显著性）；**标题自本轮起冻结**（重审 + 排名重置风险）。
