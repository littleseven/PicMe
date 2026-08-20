# Google Play Store Listing（ASO 优化文案）

破浪相册 / PoLang 的 Google Play 商店上架文案。

> **⚠️ 路径迁移（2026-08-20）**：三语**文案**文件已迁移至 GPP（gradle-play-publisher）约定目录
> **`androidApp/src/main/play/listings/{en-US,zh-CN,zh-TW}/`**，文件名不变（`title.txt` /
> `short-description.txt` / `full-description.txt`），由 `./scripts/play-publish.sh --listing-only`
> 或 CI 自动同步到 Play Console，**不再手动粘贴**。**图形素材仍保留在本目录**（见下文「图形素材」）。
> 运维手册：`docs/05-DEVELOPMENT/GOOGLE_PLAY_RELEASE_AUTOMATION.md`。

## 目录结构（文案，新）

```
androidApp/src/main/play/
├── default-language.txt        ← zh-CN（以 Console 线上为准，2026-08-20 bootstrap 实测；GPP 翻译合并基准）
├── listings/
│   ├── en-US/   ← 全球英文
│   │   ├── title.txt               (≤30 字符)
│   │   ├── short-description.txt   (≤80 字符)
│   │   └── full-description.txt    (≤4000 字符)
│   ├── zh-CN/   ← 简体中文（海外华人 / 简中用户）
│   └── zh-TW/   ← 繁体中文（台湾 / 香港）
└── release-notes/<lang>/<track>.txt  ← 发布说明（≤500 字符，由 play-publish.sh --notes 写入）
```

## 字段权重（ASO 关键）

Google Play **没有独立关键词字段**，它索引标题 + 短描述 + 完整描述的全文。权重排序：

| 字段 | 字符上限 | 权重 |
|------|----------|------|
| 标题 title | 30 | 🔴 最高（最强排名信号） |
| 短描述 short-description | 80 | 🟠 高（索引 + 转化） |
| 完整描述 full-description | 4000 | 🟡 中（密度重复 3–5 次） |

## 文案外的 Console 配置（仍需手动）

- **Tags**：勾选最相关的 5 个（优先 Photo Gallery / Photo Editor / Beauty / Filter / Camera）
- **Category**：保持 `Photography`
- **App name** 字段即标题，改后会触发重新审核

> 本目录是**商店上架素材**，独立于 App 内 `strings.xml`（应用内显示名）。改动不影响 App 代码。

## 图形素材（2026-08-20 起）

每个语言目录：

```
google-play-listing/<locale>/
├── feature-graphic.png        1024×500 置顶横幅（Play Console → Main store listing → Feature graphic）
├── screenshots/               1080×1920 ×6，按顺序上传
│   ├── 01-gallery.png         AI 智能整理（相册网格）
│   ├── 02-search.png          自然语言搜索
│   ├── 03-chat.png            对话式 AI 助手
│   ├── 04-people.png          人物分组
│   ├── 05-privacy.png         端侧隐私（模型中心）
│   └── 06-insight.png         相册洞察
└── screenshot-captions.json   文案 SSOT：标题/副标语 ↔ frame ↔ 源截图的映射
```

- 设计与产出在 Ardot 文件页面 **Play Store Assets**：改文案改图在 Ardot 里改 frame（命名 `<序号>-<scene>/<locale>`），`export_nodes` 重新导出即可；文案以 `screenshot-captions.json` 为准同步
- 源截图为 `docs-site/assets/screenshot-*.jpg`（2026-07 真机截图，UI 如更新需重截重灌）
- ⚠️ 03/04/06 场景源图含真实人物照片与人名（内部测试数据），上架前确认可公开使用，否则重新截取替换
- 📤 **上传方式**：当前图形素材手动传 Console；如需纳入自动化，迁入 GPP 约定目录 `androidApp/src/main/play/listings/<lang>/graphics/{feature-graphic,phone-screenshots}/` 后即可随 `publishListing` 上传（维度约束见运维手册）

> 策略与关键词地图见 `docs/superpowers/specs/2026-08-08-google-play-aso-design.md`。
