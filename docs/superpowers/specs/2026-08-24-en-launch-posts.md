# 英文冷启动渠道帖成稿（Show HN + Reddit）· 2026-08-24

> **用途**：破局外部种子流量的逐字可贴成稿 + 发布手册。对应策略讨论：738 展示 ≈ 0 安装的破局 = 外部流量 → 安装+评分 → 质量信号 → 搜索起飞。
> **前置已落定**：MIT LICENSE 已入库并推送 GitHub（commit `76215a848`，GitHub API 已识别 MIT）——所有 "open source" 口径自此成立。
> **归因**：全部 Play 链接带 UTM；发完 7 天后 Play Console → 商店投放效果 → 第三方引荐，看各渠道转化再决定火力分配。

---

## 0. UTM 链接组（发帖用这些，别用裸链）

| 渠道 | 链接 |
|------|------|
| Show HN | `https://play.google.com/store/apps/details?id=com.mamba.picme&referrer=utm_source%3Dhackernews%26utm_medium%3Dshow_hn%26utm_campaign%3Dlaunch_2026_08` |
| r/AndroidApps | `https://play.google.com/store/apps/details?id=com.mamba.picme&referrer=utm_source%3Dreddit%26utm_medium%3Dpost%26utm_campaign%3Dr_androidapps` |
| r/opensource | `https://play.google.com/store/apps/details?id=com.mamba.picme&referrer=utm_source%3Dreddit%26utm_medium%3Dpost%26utm_campaign%3Dr_opensource` |
| r/privacy | `https://play.google.com/store/apps/details?id=com.mamba.picme&referrer=utm_source%3Dreddit%26utm_medium%3Dpost%26utm_campaign%3Dr_privacy` |
| GitHub（通用，不追踪） | `https://github.com/littleseven/polang` |
| 官网 | `https://polang.net` |

---

## 1. Show HN（首发）

**提交**：Title 填下面标题，URL 填 GitHub 仓库（HN 对源码链接友好度远高于商店链接）。

**标题**（75 字符）：

```
Show HN: PoLang – Open-source AI photo gallery that runs entirely on-device
```

**首评**（发布后立刻以作者身份发，HN 惯例；这是正文）：

```
Hi, I'm the developer.

PoLang is a photo gallery for Android where all the AI runs on your phone: semantic search over your library, automatic tagging, face grouping, person relationships — none of your photos ever leave the device.

Why I built it: every "AI gallery" I tried either uploads your library to a cloud or ships a thin client around an API. I wanted to see how far a phone can go running the whole pipeline locally. It's a research project — free, no ads, no subscriptions.

How it works, briefly:

- Semantic search: photos are embedded on-device (CLIP-class model via MNN); queries like "sunset at the beach" or "my daughter last summer" are matched against those embeddings, combined with auto tags, face clusters and time/place filters.
- Tagging: Florence-2 for scene/object/activity tags, plus a small on-device VLM (Qwen3-VL-2B through MNN's runtime) for richer content understanding.
- Face grouping and person relationships are computed locally; label a face once, then search "mom's photos".
- A conversational layer on top: an agent orchestrator (Kotlin Multiplatform, built on JetBrains' Koog) that maps natural language to device capabilities — search, organize, edit ("make it warmer", "cut out the background and make an ID photo").
- The edit/beauty pipeline is a self-written OpenGL ES + EGL renderer — no third-party beauty SDK.

Honest limitations:

- First launch downloads ~1.5 GB of on-device models, and the initial library scan takes about 1.5 h per 10k photos (runs in background; later scans are incremental).
- The chat assistant uses a remote LLM for text reasoning — it only ever sends your text queries and its own tool results, never photos or videos. All media processing (faces, OCR, tags, search) stays local. There's a free quota.
- Android 7.0+, arm64 recommended.

Source (MIT): https://github.com/littleseven/polang
Play: <HN 的 UTM 链接>

I'd particularly like feedback on the first-run model download — it's the biggest friction point and I'm debating a lighter "basic features first, full models later" path.
```

---

## 2. r/AndroidApps（Show HN 后隔 1–2 天）

**Flair**：App（版规要求）。**附图**：发帖时上传 `google-play-listing/en-US/screenshots/` 的 `01-gallery.png`、`02-search.png`、`08-privacy.png` 三张。

**标题**：

```
PoLang – free & open-source (MIT) AI photo gallery: on-device semantic search, auto-tagging, face clustering, no ads
```

**正文**：

```
Solo dev here. PoLang is a gallery app where the AI parts run entirely on your phone — photos never get uploaded anywhere. It's MIT-licensed and the source is on GitHub, so you can verify that claim instead of trusting it.

What it does:

- Browse — a normal gallery first: grid, timeline, albums
- Search in plain language — "sunset at the beach", "photos of my daughter last summer"; also search-by-image
- Auto-tagging & face grouping — scene/object tags (Florence-2) plus an on-device VLM (Qwen3-VL-2B via MNN); label a face once, then ask for "mom's photos"; there's a person-relationship graph too
- Chat assistant — ask it to find / organize / summarize your gallery, or edit: "make it warmer", "cut this out and make an ID photo" (it always asks before changing anything)
- Editor — filters, beauty retouch, one-tap background removal, ID photos, on a self-written OpenGL ES pipeline
- Privacy — 100% of media processing is on-device. The optional chat uses a remote LLM for text only — never photos.

Trade-offs I won't hide:

- First run downloads ~1.5 GB of on-device models (Wi-Fi), and the initial scan takes ~1.5 h per 10k photos (background; incremental afterwards)
- Chat needs internet and has a free quota (email unlocks more); the rest of the app works offline
- Android 7.0+, arm64 recommended

Links: GitHub | Play | polang.net

Happy to answer anything — also genuinely interested in what would make the 1.5 GB first run less scary.
```

---

## 3. r/opensource（再隔 2–3 天）

**标题**：

```
PoLang (MIT) – on-device AI photo gallery: Kotlin Multiplatform, self-written OpenGL ES engine, MNN inference, agent layer on JetBrains Koog
```

**正文**：

```
Solo dev sharing an Android app (an iOS app shares the same Kotlin Multiplatform core, in testing) that has been my playground for "AI-first client architecture": an agent that maps natural language to on-device capabilities.

Stack highlights:

- Kotlin Multiplatform :shared module — agent orchestrator + capability registry in commonMain; JetBrains Koog drives the remote-LLM tool-calls loop
- On-device inference via MNN: Qwen3-VL-2B (VLM tagging), Florence-2 (scene/object tags), CLIP-class embeddings for semantic search
- Self-developed OpenGL ES + EGL render/beauty pipeline — no third-party beauty SDK
- Layered module boundaries (beauty-api / beauty-engine), ~50 JVM test files, ktlint + detekt gates

The app itself: photo gallery with on-device semantic search, auto-tagging, face clustering, a person-relationship graph, and conversational editing. All media processing is 100% local; only the optional chat uses a remote LLM (text only). Free, no ads.

Code (MIT): https://github.com/littleseven/polang — docs/ has the ADRs if you enjoy reading architecture decisions (ADR-005 local/remote separation, ADR-008 privacy red lines, ADR-013 the KMP contract).

Contributions welcome, especially on the model side — always looking for smaller/faster on-device models.
```

---

## 4. r/privacy（最后发；版规最严，发前必读版规与置顶，若禁自荐则改为在云照片/Google Photos 相关讨论中以评论自然提及）

**标题**：

```
On-device AI photo gallery — tagging, face grouping, semantic search, all local; MIT-licensed so you can verify (Android)
```

**正文**：

```
Solo dev sharing a tool built around a premise this sub cares about: a photo library should be able to have useful AI without the photos ever leaving the phone.

PoLang (Android, MIT, source: https://github.com/littleseven/polang):

- Semantic search ("sunset at the beach"), auto-tagging (Florence-2), face grouping, person relationships — all computed on-device (MNN runtime; Qwen3-VL-2B for VLM tagging)
- No account needed for any of that; browsing, search and tagging work fully offline
- Free, no ads, no in-app purchases

Full disclosure so nobody has to dig it out of me: the optional AI-chat assistant uses a remote LLM. It sends text only — your prompts and its tool results — never photos, videos, or media metadata. You can use the entire gallery without ever opening the chat. The local/remote boundary is written up in the repo (docs/02-ARCHITECTURE/ADR — ADR-008 defines which data may never leave the device).

The price of on-device inference: first run downloads ~1.5 GB of local models. Android 7.0+, arm64 recommended.

If you'd rather not install it: the source is there to inspect. Happy to answer questions about the architecture.
```

> ⚠️ 发帖前自查：App 内是否含任何遥测/崩溃上报（Firebase 等）。若有，r/privacy 帖子需如实加一句说明——这个版的人会扒，瞒报的代价是整个产品信誉。

---

## 5. 发布手册

### 账号
- 用**有历史的账号**：新号/低 karma 号发链接帖会被 AutoMod 静默吞掉。Reddit 建议 karma > 100、注册 > 30 天；HN 同理。
- 同一内容多版发布时**直接用本稿各版差异化文案**，不要自己再改成同一份——跨版查重是真实的。

### 时机与顺序
| 顺序 | 渠道 | 时机（北京时间） | 理由 |
|------|------|------------------|------|
| 1 | Show HN | 周二~周四 20:00–22:00（= 美东 8–10am） | HN 流量峰在美东上午；发完守 90 分钟 |
| 2 | r/AndroidApps | HN 后 1–2 天，美东上午 | 最 receptive 的版，先拿真实反馈再战其他版 |
| 3 | r/opensource | 再隔 2–3 天 | 技术向，吃 ADR/KMP 细节 |
| 4 | r/privacy | 最后 | 版规最严；若禁自荐改评论策略 |

### 互动纪律
- **首 60–90 分钟每条评论必回**（HN 和 Reddit 的算法都吃早期互动率）
- 被喷的点（1.5GB 下载、chat 为何走远程）直接认账 + 给出正在想的方案——诚实在这个人群里是加分项
- 用开发者本人身份，不用小号自问自答

### 预期问题与标准答案
| 问题 | 答案 |
|------|------|
| F-Droid？ | "Not yet — happy to look into it if there's interest"（如实；有人要就真做） |
| 为何 chat 要远程 LLM？ | 端侧 2B 跑多轮 tool-call 不可靠/太慢；远程路径只收文本（查询+工具结果），媒体数据红线在架构层硬约束（ADR-008）；相册/搜索/打标全流程无需它 |
| 模型许可证？ | 代码 MIT；端侧模型沿用各自上游许可（Qwen3 Apache-2.0、Florence-2 MIT） |
| 为何要邮箱注册？ | 仅用于提高 chat 免费额度；核心相册功能完全无账号 |
| 怎么盈利？ | 不盈利——技术研究项目，无广告无内购 |
| 1.5GB 首启太重？ | 已承认是最大摩擦点，正评估「先基础功能、后全量模型」的轻量首启路径 |

### 红线（做了会毁掉整个渠道）
- ❌ 拉朋友点赞/投票（vote manipulation：封帖+封号+子版拉黑）
- ❌ 多账号顶帖、伪装用户评论
- ❌ 标题求 upvote、"please support"
- ❌ 瞒报远程 chat / 遥测（r/privacy 会扒源码，MIT 之后源码就是承诺书）

### 发完 7 天的数据回看
1. Play Console → 商店投放效果 →「用户如何找到您的商品」→ 第三方引荐：按 UTM 分渠道看安装
2. 哪个渠道转化好 → 下月内容火力加到哪；r/privacy 若被删/被喷 → 渠道策略降级为评论参与
3. 首批评分若到 10+ → 回到 ASO 节奏，搜索词报告开始有料
