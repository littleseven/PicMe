# Google Play Store Listing（ASO 优化文案）

破浪相册 / PoLang 的 Google Play 商店上架文案。

> **⚠️ 路径迁移（2026-08-20）**：三语文案文件已迁移至 GPP（gradle-play-publisher）约定目录
> **`androidApp/src/main/play/listings/{en-US,zh-CN,zh-TW}/`**，文件名不变（`title.txt` /
> `short-description.txt` / `full-description.txt`），由 `./scripts/play-publish.sh --listing-only`
> 或 CI 自动同步到 Play Console，**不再手动粘贴**。本目录仅保留本说明与 ASO 知识。
> 运维手册：`docs/05-DEVELOPMENT/GOOGLE_PLAY_RELEASE_AUTOMATION.md`。

## 目录结构（新）

```
androidApp/src/main/play/
├── default-language.txt        ← en-US（默认语言，GPP 翻译合并基准）
├── listings/
│   ├── en-US/   ← 默认语言（全球英文），Play Console 主语言
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
- 图形资产（icon / feature-graphic / screenshots）：可放入 `listings/<lang>/graphics/` 对应子目录后随 publishListing 上传，维度约束见运维手册
