# Google Play Store Listing（ASO 优化文案）

破浪相册 / PoLang 的 Google Play 商店上架文案，按 Play Console 本地化语言分组。直接把各 `*.txt` 内容粘贴到 Play Console 对应字段即可。

## 目录结构

```
google-play-listing/
├── en-US/   ← 默认语言（全球英文），Play Console 主语言
│   ├── title.txt               (≤30 字符)
│   ├── short-description.txt   (≤80 字符)
│   └── full-description.txt    (≤4000 字符)
├── zh-CN/   ← 简体中文（海外华人 / 简中用户）
│   ├── title.txt
│   ├── short-description.txt
│   └── full-description.txt
└── zh-TW/   ← 繁体中文（台湾 / 香港）
    ├── title.txt
    ├── short-description.txt
    └── full-description.txt
```

## 字段权重（ASO 关键）

Google Play **没有独立关键词字段**，它索引标题 + 短描述 + 完整描述的全文。权重排序：

| 字段 | 字符上限 | 权重 |
|------|----------|------|
| 标题 title | 30 | 🔴 最高（最强排名信号） |
| 短描述 short-description | 80 | 🟠 高（索引 + 转化） |
| 完整描述 full-description | 4000 | 🟡 中（密度重复 3–5 次） |

## 在 Play Console 怎么用

1. Play Console → **Grow → Store presence → Main store listing**
2. 默认语言选 `en-US`，粘贴 `en-US/` 下三个文件
3. **Manage translations** → 添加 `zh-CN` 和 `zh-TW`，分别粘贴对应文件
4. 文案外的配置另做：
   - **Tags**：勾选最相关的 5 个（优先 Photo Gallery / Photo Editor / Beauty / Filter / Camera）
   - **Category**：保持 `Photography`
   - **App name** 字段即标题，改后会触发重新审核

> 本目录是**商店上架素材**，独立于 App 内 `strings.xml`（应用内显示名）。改动不影响 App 代码。

> 策略与关键词地图见 `docs/superpowers/specs/2026-08-08-google-play-aso-design.md`。
