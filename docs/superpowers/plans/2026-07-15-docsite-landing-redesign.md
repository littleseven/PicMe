# polang.net 官网改造 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 polang.net 从「技术文档站」改造成「面向用户的产品落地页」,静态文件纳入 repo `docs-site/`,并用 rsync 脚本部署到腾讯云 HK 服务器。

**Architecture:** 纯静态 HTML(无构建)。新增顶层 `docs-site/`(镜像服务器 `/var/www/picme/docs-site`),从服务器反向 rsync 取回现状作为基线,重写 `index.html` 为产品落地页(内容取自 Google Play 商品信息),新增 `scripts/deploy-docs-site.sh` 做「备份→rsync→线上校验」一键部署。

**Tech Stack:** 静态 HTML + 内联 CSS;rsync/ssh 部署;腾讯云 HK nginx(现状)。对应 spec:`docs/superpowers/specs/2026-07-15-docsite-landing-redesign-design.md`。

**TDD 适配:** 静态页无单元测试;验证 = 结构检查(grep 标记)+ 最终线上 curl 校验。

---

## 文件结构

| 文件 | 责任 | 动作 |
|---|---|---|
| `docs-site/index.html` | 产品落地页首页(7 区块) | 重写 |
| `docs-site/00-INDEX.html` 及 `01-PRODUCT/`…`07-STANDARDS/`、`privacy-policy/` | 技术文档 + 隐私政策 | 从服务器取回,原样保留 |
| `docs-site/assets/icon.png` | 应用图标 | 取回(已有) |
| `docs-site/assets/screenshot-{1..8}.jpg` | Play 截图 | 新增(从 Play 下载) |
| `docs-site/assets/winxin.jpg` | 微信扫码图 | 取回(已有) |
| `scripts/deploy-docs-site.sh` | 部署脚本 | 新增 |

---

## Task 1: 把当前官网取回 repo

**Files:**
- Create: `docs-site/`(整个目录,从服务器 rsync)

- [ ] **Step 1: 从服务器反向 rsync 取回现状(排除 .bak 噪声)**

```bash
cd /Users/guoshuai/AndroidStudioProjects/langchain4android/.claude/worktrees/chat-streaming-ux-fix
mkdir -p docs-site
rsync -avz --exclude='*.bak*' --exclude='.bak' \
  ubuntu@43.161.201.142:/var/www/picme/docs-site/ docs-site/
```
预期:取回 `index.html`、`00-INDEX.html`、`01-PRODUCT/`…`07-STANDARDS/`、`privacy-policy/`、`assets/`(含 icon.png、winxin.jpg),不含 `index.html.bak.*`。

- [ ] **Step 2: 确认取回内容**

```bash
ls docs-site/
ls docs-site/assets/
```
预期:看到上述目录与文件;`docs-site/index.html` 存在。

- [ ] **Step 3: 提交基线**

```bash
git add docs-site/
git commit -m "feat(docsite): 纳入 git — 取回 polang.net 当前静态站作为基线

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 2: 下载 Google Play 截图到 assets

**Files:**
- Create: `docs-site/assets/screenshot-{1..8}.jpg`

- [ ] **Step 1: 下载 8 张 Play 截图(宽度 900)**

```bash
cd /Users/guoshuai/AndroidStudioProjects/langchain4android/.claude/worktrees/chat-streaming-ux-fix
IDS=(
  uowNedZbEaJSW3EPMv4DuXnrLO3jnHHwk1mgXAKeSlleLrt5LGjqO1A2DgZHsJVO5Pg1yRAe3l0HskCS8QrfPO8
  wyTw-PJwqoaSbnWC2k8ddSeA73z8QBuOk-P7026OzcAYOA_rDK0u01sxYNRwb16lvgseWCvxCBqQ9PmgotKnfw
  _BdQ47kHS2dG3Y9aIx_CXoBnqziYQH4U0lzcqXKJGOVvLjKsJOMtcvmftCX011orKhf4cVRAadhQ4MMODpJZMKM
  cUfIBMTtpy6ctLkYfAsSoifSxI5f8h20JzQR0_bhIKEaD5AjskXJ-Hnkmn6KJDZcoORTV-clDHvjS5-vwU6A
  rDR4We7WJK8Ki_VmaT4Nj1N5Q9IYTIxye9khbQ2g7tecNqh4RVRObAZB8fUBRmEhuGA9e0YHcxMblGw6ez-4610
  JfGwPf0umwqVKkk9jA6xMJsGdyXSHPMJEFcmWsYyPA9m6qoQ3PUHPr-ipNAPxxbHtNTGmu09bEJfPK5yb_tJIeI
  K9-2GIY81SkeytvdTGGllqFBo3Qh_BYn9BOR-Y_x1LoRrP7wAETOxMcyhbOY8AIrB6ijgLLgIYLsaEDI4tzv
  a-IctH7ZnWJm8tFxPv0AKka7tL39Ds-4d_FkUEXAE3DsCrhggKxdI12w4D6Jjn3ZTsLmk9UOuTP9SpEBVZtDzTo
)
i=1
for id in "${IDS[@]}"; do
  curl -fsSL "https://play-lh.googleusercontent.com/${id}=w900" -o "docs-site/assets/screenshot-${i}.jpg" || echo "WARN: screenshot $i 下载失败"
  i=$((i+1))
done
```

- [ ] **Step 2: 确认截图已下载且为有效图片**

```bash
ls -la docs-site/assets/screenshot-*.jpg
file docs-site/assets/screenshot-1.jpg
```
预期:8 个文件;`file` 输出 JPEG image data。失败的可在实现时补抓或减少到可用张数。

- [ ] **Step 3: 提交**

```bash
git add docs-site/assets/screenshot-*.jpg
git commit -m "feat(docsite): 下载 Google Play 截图到 assets

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 3: 编写部署脚本

**Files:**
- Create: `scripts/deploy-docs-site.sh`

- [ ] **Step 1: 写脚本(完整内容)**

创建 `scripts/deploy-docs-site.sh`:

```bash
#!/usr/bin/env bash
# 部署 polang.net 官网静态站到腾讯云 HK 服务器。
# 用法: ./scripts/deploy-docs-site.sh
# 流程: 远端备份(同目录兄弟位置)→ rsync(--delete 镜像 repo)→ curl 线上校验。
set -euo pipefail
export LC_ALL=C

HOST="ubuntu@43.161.201.142"
REMOTE_DIR="/var/www/picme/docs-site"
BACKUP_ROOT="/var/www/picme/docs-site-backups"
LOCAL_DIR="$(cd "$(dirname "$0")/.." && pwd)/docs-site"
TS="$(date +%Y%m%d-%H%M%S)"
MARKER="零图片上传隐私安全"

echo "==> [1/3] 备份远端 $REMOTE_DIR -> $BACKUP_ROOT/docs-site.bak.$TS"
ssh -o ConnectTimeout=15 "$HOST" "mkdir -p $BACKUP_ROOT && cp -r $REMOTE_DIR $BACKUP_ROOT/docs-site.bak.$TS"

echo "==> [2/3] rsync $LOCAL_DIR -> $HOST:$REMOTE_DIR (--delete 镜像)"
rsync -avz --delete "$LOCAL_DIR/" "$HOST:$REMOTE_DIR/"

echo "==> [3/3] 校验线上首页标记: $MARKER"
if curl -s --max-time 20 https://polang.net/ | grep -q "$MARKER"; then
  echo "✅ 部署成功: https://polang.net/"
else
  echo "❌ 校验失败:首页未检测到标记。回滚命令:"
  echo "  ssh $HOST \"rm -rf $REMOTE_DIR && cp -r $BACKUP_ROOT/docs-site.bak.$TS $REMOTE_DIR\""
  exit 1
fi
```

- [ ] **Step 2: 加可执行权限并语法检查**

```bash
chmod +x scripts/deploy-docs-site.sh
bash -n scripts/deploy-docs-site.sh && echo "syntax OK"
```
预期:`syntax OK`。

- [ ] **Step 3: 提交**

```bash
git add scripts/deploy-docs-site.sh
git commit -m "feat(docsite): 新增官网 rsync 部署脚本(备份+校验)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 4: 重写 index.html 为产品落地页

**Files:**
- Modify: `docs-site/index.html`(整体重写)

- [ ] **Step 1: 用下面完整内容覆写 `docs-site/index.html`**

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>PoLang 破浪相册 · 隐私优先的智能相册</title>
  <meta name="description" content="零图片上传隐私安全,用自然语言搜索本地相册、编辑照片。AI 对话相册、智能搜索、专业美颜、创意滤镜,全部本地处理。">
  <style>
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body { font-family: -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif; color: #1e293b; background: #f8fafc; line-height: 1.6; }
    a { color: inherit; text-decoration: none; }
    .container { max-width: 1080px; margin: 0 auto; padding: 0 20px; }

    .topnav { position: sticky; top: 0; z-index: 10; background: rgba(255,255,255,.85); backdrop-filter: blur(8px); border-bottom: 1px solid #e2e8f0; }
    .topnav-inner { display: flex; align-items: center; justify-content: space-between; height: 56px; }
    .brand { display: flex; align-items: center; gap: 8px; font-weight: 700; font-size: 18px; }
    .brand img { width: 28px; height: 28px; border-radius: 7px; }
    .nav-links { display: flex; gap: 18px; font-size: 14px; color: #475569; }
    .nav-links a:hover { color: #2563eb; }

    .hero { text-align: center; padding: 64px 20px 48px; }
    .hero-icon { width: 96px; height: 96px; border-radius: 22px; box-shadow: 0 8px 24px rgba(0,0,0,.15); }
    .hero h1 { font-size: 34px; margin: 18px 0 6px; }
    .hero h1 .en { color: #64748b; font-weight: 600; font-size: 22px; margin-left: 8px; }
    .hero .tagline { font-size: 19px; color: #334155; max-width: 720px; margin: 0 auto; }
    .badges { display: flex; gap: 10px; justify-content: center; flex-wrap: wrap; margin: 16px 0 24px; }
    .badge { background: #ecfdf5; color: #047857; border: 1px solid #a7f3d0; border-radius: 999px; padding: 5px 14px; font-size: 13px; font-weight: 600; }
    .cta-row { display: flex; gap: 14px; justify-content: center; flex-wrap: wrap; }
    .btn { display: inline-flex; align-items: center; gap: 8px; padding: 12px 24px; border-radius: 12px; font-weight: 600; font-size: 15px; transition: transform .1s; }
    .btn:hover { transform: translateY(-1px); }
    .btn-play { background: #fff; color: #1f2937; border: 1px solid #e5e7eb; box-shadow: 0 4px 14px rgba(0,0,0,.08); }
    .btn-github { background: #0f172a; color: #fff; }

    section { padding: 48px 0; }
    .section-title { font-size: 26px; font-weight: 700; text-align: center; margin-bottom: 8px; }
    .section-sub { text-align: center; color: #64748b; margin-bottom: 32px; }

    .grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 18px; }
    .card { background: #fff; border: 1px solid #e2e8f0; border-radius: 14px; padding: 22px; transition: box-shadow .15s; }
    .card:hover { box-shadow: 0 8px 24px rgba(0,0,0,.06); }
    .card .icon { font-size: 28px; }
    .card h3 { font-size: 17px; margin: 8px 0 6px; }
    .card p { font-size: 14px; color: #475569; }

    .privacy { background: linear-gradient(135deg, #2563eb, #7c3aed); color: #fff; border-radius: 16px; padding: 36px; }
    .privacy .section-title, .privacy .section-sub { color: #fff; }
    .privacy-list { max-width: 720px; margin: 0 auto; }
    .privacy-list .item { display: flex; align-items: flex-start; gap: 12px; padding: 10px 0; font-size: 16px; }
    .privacy-list .check { font-size: 20px; }

    .shots { display: flex; gap: 14px; overflow-x: auto; padding: 8px 4px; }
    .shots img { height: 440px; border-radius: 16px; border: 1px solid #e2e8f0; flex-shrink: 0; }

    .download { text-align: center; }
    .download .qr { width: 160px; height: 160px; border-radius: 12px; margin: 18px auto 8px; display: block; border: 1px solid #e2e8f0; object-fit: cover; }
    .download .hint { color: #64748b; font-size: 13px; }

    footer { background: #0f172a; color: #94a3b8; padding: 28px 0; margin-top: 32px; }
    footer .inner { display: flex; flex-wrap: wrap; gap: 16px; justify-content: space-between; align-items: center; }

    @media (max-width: 768px) {
      .hero h1 { font-size: 26px; }
      .hero .tagline { font-size: 16px; }
      .grid { grid-template-columns: 1fr; }
      .privacy { padding: 24px; }
      .shots img { height: 360px; }
      .nav-links { gap: 12px; }
    }
  </style>
</head>
<body>
  <nav class="topnav">
    <div class="container topnav-inner">
      <a class="brand" href="/"><img src="assets/icon.png" alt="PoLang">破浪相册</a>
      <div class="nav-links">
        <a href="#features">功能</a>
        <a href="#privacy">隐私</a>
        <a href="https://github.com/littleseven/langchain4android" target="_blank" rel="noopener">GitHub</a>
      </div>
    </div>
  </nav>

  <header class="hero container">
    <img class="hero-icon" src="assets/icon.png" alt="破浪相册 PoLang">
    <h1>破浪相册 <span class="en">PoLang</span></h1>
    <p class="tagline">零图片上传隐私安全,用自然语言搜索本地相册、编辑照片。</p>
    <div class="badges">
      <span class="badge">✓ 免费</span>
      <span class="badge">✓ 无广告</span>
      <span class="badge">✓ 本地隐私</span>
    </div>
    <div class="cta-row">
      <a class="btn btn-play" href="https://play.google.com/store/apps/details?id=com.mamba.picme" target="_blank" rel="noopener">▶ Google Play 下载</a>
      <a class="btn btn-github" href="https://github.com/littleseven/langchain4android" target="_blank" rel="noopener">GitHub 仓库</a>
    </div>
  </header>

  <section id="features" class="container">
    <h2 class="section-title">核心功能</h2>
    <p class="section-sub">一个会用自然语言沟通的智能相册</p>
    <div class="grid">
      <div class="card"><div class="icon">🤖</div><h3>对话式 AI 助手</h3><p>像聊天一样管理相册:"找去年夏天的照片""把这张调暖一点",AI 听懂就做。</p></div>
      <div class="card"><div class="icon">🖼️</div><h3>智能相册</h3><p>自动给照片打标签(场景/人物/物体/活动),自然语言搜索,智能分类与 AI 相册。</p></div>
      <div class="card"><div class="icon">✨</div><h3>AI 美颜编辑</h3><p>GPU 加速专业美颜:磨皮、美白、瘦脸、大眼、唇色、腮红,一键 AI 智能优化。</p></div>
      <div class="card"><div class="icon">🎨</div><h3>创意滤镜</h3><p>徕卡(经典/鲜艳/黑白)、胶片(金/富士/复古)、实时 GPU 特效,支持批量。</p></div>
      <div class="card"><div class="icon">📷</div><h3>实时美颜相机</h3><p>零延迟快门(&lt;50ms),人脸追踪,运动中妆容不飞。</p></div>
      <div class="card"><div class="icon">📱</div><h3>飞书远程控制(实验)</h3><p>在飞书 IM 里浏览、搜索、编辑相册,结果直接回到聊天。</p></div>
    </div>
  </section>

  <section id="privacy" class="container">
    <div class="privacy">
      <h2 class="section-title">🔒 隐私优先,从架构开始</h2>
      <p class="section-sub">你的照片,只属于你</p>
      <div class="privacy-list">
        <div class="item"><span class="check">✅</span><span>所有 AI 处理在设备本地完成 —— 人脸、照片、对话绝不上传云端。</span></div>
        <div class="item"><span class="check">✅</span><span>不采集任何用户数据(对应 Google Play「数据安全」声明)。</span></div>
        <div class="item"><span class="check">✅</span><span>本地 AI 模型离线可用,无网络也能搜索与编辑。</span></div>
      </div>
    </div>
  </section>

  <section class="container">
    <h2 class="section-title">一眼所见</h2>
    <p class="section-sub">左右滑动查看</p>
    <div class="shots">
      <img src="assets/screenshot-1.jpg" alt="截图 1">
      <img src="assets/screenshot-2.jpg" alt="截图 2">
      <img src="assets/screenshot-3.jpg" alt="截图 3">
      <img src="assets/screenshot-4.jpg" alt="截图 4">
      <img src="assets/screenshot-5.jpg" alt="截图 5">
      <img src="assets/screenshot-6.jpg" alt="截图 6">
      <img src="assets/screenshot-7.jpg" alt="截图 7">
      <img src="assets/screenshot-8.jpg" alt="截图 8">
    </div>
  </section>

  <section class="download container">
    <h2 class="section-title">立即体验</h2>
    <a class="btn btn-play" href="https://play.google.com/store/apps/details?id=com.mamba.picme" target="_blank" rel="noopener">▶ Google Play 下载</a>
    <img class="qr" src="assets/winxin.jpg" alt="微信扫码下载">
    <p class="hint">微信扫码 · 或在 Google Play 搜索「觅影相册」</p>
  </section>

  <footer>
    <div class="container inner">
      <span>© PoLang — 雕刻时光,瞬间永恒。</span>
      <div class="nav-links">
        <a href="privacy-policy/index.html">隐私政策</a>
        <a href="00-INDEX.html">技术文档</a>
        <a href="https://github.com/littleseven/langchain4android" target="_blank" rel="noopener">GitHub</a>
      </div>
    </div>
  </footer>
</body>
</html>
```

- [ ] **Step 2: 结构校验(7 区块 + 关键标记齐全)**

```bash
cd /Users/guoshuai/AndroidStudioProjects/langchain4android/.claude/worktrees/chat-streaming-ux-fix
grep -c 'id="features"' docs-site/index.html      # 预期 1
grep -c 'id="privacy"' docs-site/index.html       # 预期 1
grep -c 'class="shots"' docs-site/index.html      # 预期 1
grep -c 'screenshot-1.jpg' docs-site/index.html   # 预期 1
grep -c 'Google Play 下载' docs-site/index.html    # 预期 2(Hero + 下载区)
grep -c '技术文档' docs-site/index.html            # 预期 1(仅页脚)
grep -c '关于 PoLang' docs-site/index.html         # 预期 0(已移除技术段落)
grep -c '文档目录' docs-site/index.html            # 预期 0(已移除)
```

- [ ] **Step 3: 提交**

```bash
git add docs-site/index.html
git commit -m "feat(docsite): 重做首页为面向用户的产品落地页

Hero + 核心功能6卡片 + 隐私优先 + 截图 + 下载 + 页脚;
移除「文档目录」「关于 PoLang」技术段落,技术文档降级为页脚链接。
内容取自 Google Play 商品信息。

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 5: 部署 + 线上校验

**Files:** 无(运行 Task 3 脚本)

- [ ] **Step 1: 跑部署脚本**

```bash
./scripts/deploy-docs-site.sh
```
预期:输出 `[1/3] 备份` → `[2/3] rsync` → `[3/3] 校验` → `✅ 部署成功: https://polang.net/`。

- [ ] **Step 2: 二次线上校验**

```bash
curl -s --max-time 20 https://polang.net/ | grep -oE '<p class="tagline">[^<]*</p>'
curl -s --max-time 20 https://polang.net/ | grep -c '核心功能'
```
预期:tagline 含「零图片上传隐私安全…」;「核心功能」计数 ≥ 1。

- [ ] **Step 3(若校验失败): 回滚**

```bash
ssh ubuntu@43.161.201.142 'ls -t /var/www/picme/docs-site-backups/ | head -1'
# 用最近一个备份回滚:
ssh ubuntu@43.161.201.142 'set -e; D=/var/www/picme/docs-site; B=$(ls -t /var/www/picme/docs-site-backups | head -1); rm -rf $D && cp -r /var/www/picme/docs-site-backups/$B $D'
```

---

## Self-Review(已做)

- **Spec 覆盖**:spec 第 5.1(仓库结构)= Task 1;5.2(部署脚本)= Task 3;5.3(首页 7 区块)= Task 4;5.4(技术文档降级)= Task 4 页脚链接 + Task 1 保留;5.5(静态+内联 CSS+mobile)= Task 4;截图(5.3/6)= Task 2;验收(第 8 节)= Task 4 Step2 + Task 5。✓
- **占位符**:无 TBD/TODO;截图 URL、脚本、HTML 均为完整内容。✓
- **一致性**:`screenshot-{1..8}.jpg` 在 Task 2 产出、Task 4 引用一致;`winxin.jpg` 与服务器现状一致;部署标记「零图片上传隐私安全」与 Hero tagline 一致。✓
