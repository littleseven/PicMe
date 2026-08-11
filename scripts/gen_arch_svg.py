#!/usr/bin/env python3
"""生成 PoLang 架构图 SVG（docs/assets/architecture.svg）。

网格化手工布局：统一盒宽、严格垂直中轴、双栏等宽——避免 Mermaid/Graphviz
自动布局的不可控排版。改内容只改 BOXES/ARROWS 数据，重跑脚本即可：

    python3 scripts/gen_arch_svg.py
"""

import unicodedata

W, MARGIN, GAP = 1240, 40, 40
INNER_W = W - 2 * MARGIN          # 1160
COL_W = (INNER_W - GAP) // 2      # 560（双栏单盒宽）

TITLE_H, LINE_H, PAD_V, PAD_H = 30, 22, 18, 24
BAND_GAP = 72                     # 层间距（箭头 + 标签）

FONT = "-apple-system, 'PingFang SC', 'Noto Sans CJK SC', 'Microsoft YaHei', sans-serif"
MONO = "ui-monospace, 'SF Mono', Menlo, Consolas, monospace"

LAYER = {
    "client": ("#E8F0FE", "#1A73E8", "📱"),
    "shared": ("#E6F4EA", "#188038", "🧠"),
    "infra":  ("#FEF7E0", "#B26A00", "🧩"),
    "server": ("#FEF7E0", "#B26A00", "🖥️"),
    "remote": ("#FCE8E6", "#D93025", "☁️"),
    "media":  ("#F3E8FD", "#9334E6", "🎨"),
}


def text_w(s: str) -> int:
    """估算文本像素宽（CJK≈14px，ASCII≈8px @15px 字号）。"""
    w = 0
    for c in s:
        w += 14 if unicodedata.east_asian_width(c) in "WF" else 8
    return w


class Box:
    def __init__(self, layer, title, lines, x, w):
        self.fill, self.stroke, self.icon = LAYER[layer]
        self.title, self.lines = title, lines
        self.x, self.w = x, w
        self.h = PAD_V + TITLE_H + LINE_H * len(lines) + PAD_V - 6
        self.y = 0

    @property
    def cx(self):
        return self.x + self.w / 2

    @property
    def header_h(self):
        return PAD_V + TITLE_H - 2


# ── 图内容（SSOT：改这里） ──────────────────────────────────────────────

def build_bands():
    bands = []

    def pair(layer, lt, ll, rt, rl):
        left = Box(layer, lt, ll, MARGIN, COL_W)
        right = Box(layer, rt, rl, MARGIN + COL_W + GAP, COL_W)
        h = max(left.h, right.h)
        left.h = right.h = h
        bands.append([left, right])

    def single(layer, t, l):
        bands.append([Box(layer, t, l, MARGIN, INNER_W)])

    pair("client",
         ":androidApp · Kotlin / Jetpack Compose",
         ["features/ ── UI（Compose）+ ViewModel",
          "AndroidAgentComposition ── 组合根",
          "ChatToolService · CameraToolService",
          "RemoteControlToolService（飞书 / TG）"],
         "iosApp/ · SwiftUI",
         ["Features/ ── 相机 · 相册 · Chat · 设置",
          "IosAgentComposition ── 组合根",
          "ChatAgentBridge ── SKIE 消费 shared",
          "Metal 4-pass 美颜 · MNN / MediaPipe"])

    single("shared",
           ":shared · Agent 编排层（KMP：commonMain + androidMain + iosMain）",
           ["commonMain（引擎无关）：AgentOrchestrator → CapabilityRegistry → dispatch",
            "PrivacyGuard：媒体处理 100% 钉在端侧，仅文本 / 元数据上云（隐私红线）",
            "Chat 链 streamChat → KoogReActAgent + ChatToolService",
            "Camera 链 processCameraInput → KoogReActAgent + CameraToolService",
            "MemoryManager ── 多轮对话记忆（持久化，重启恢复）",
            "androidMain：LocalLlmEngine（端侧 VLM 打标）· 语音 · DataStore",
            "iosMain：ChatAgentBridge · IosChatGalleryCapability（端侧 VLM 仍 stub）"])

    single("infra",
           "Koog · JetBrains KMP Agent 框架（外部依赖 · 双端共用）",
           ["AIAgent · ToolRegistry · ChatMemory · graphStrategy(poLangSingleRunStrategy)",
            "OpenAILLMClient · PromptExecutor · EventHandler 流式 SSE"])

    single("server",
           "server/ · Ktor 后端（独立 Gradle 工程 · api.polang.net）",
           ["AI 网关（Cloudflare AI Gateway / 腾讯 TokenHub）· 账号 · 免费额度",
            "管理后台 · 遥测 · /download（Android APK + iOS Ad-Hoc 分发）"])

    single("remote",
           "远程 LLM · DeepSeek / 通义 …（OpenAI 兼容 · tool_calls · 多轮 · 流式）",
           ["⚠ 只收文本 / 元数据 —— 用户图片 / 视频文件绝不上传"])

    pair("media",
         "端侧媒体处理 · Android（Gradle 模块）",
         [":engines:beauty-engine  OpenGL ES 美颜",
          ":engines:mnn-core       MNN 推理 JNI",
          ":engines:agent-native   VLM 打标 JNI",
          ":engines:sentencepiece  tokenizer JNI",
          "人脸 · 美颜 · OCR · 抠图/证件照 · 语义搜索 · 打标 · 聚类"],
         "端侧媒体处理 · iOS（Framework / 原生管线）",
         ["Metal 4-pass 美颜（相机实时）",
          "MNN.framework ── 人脸检测 · Florence-2",
          "MediaPipe ── 468 关键点（双源之一）",
          "TagDatabase（GRDB）── 打标 / 聚类存储",
          "端侧 VLM（Qwen3-VL）打标 ── 待接 stub"])
    return bands


# 层间连接：(from_band, to_band, label, dashed, split)
# label 且 dashed=None 时画分隔标签（无箭头）
ARROWS = [
    (0, 1, "自然语言 → 意图编排（双端同一编排层）", False, True),
    (1, 2, "文本 / 指令 → 远程推理编排（OpenAI 兼容 tool_calls）", False, False),
    (2, 3, "HTTPS", False, False),
    (3, 4, "", False, False),
    (4, 5, "▲ 上方「文本推理」链路 ｜ 下方「端侧媒体处理」链路 —— 媒体数据不出端 ▼", None, False),
]


# ── 渲染 ────────────────────────────────────────────────────────────────

def esc(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def main():
    bands = build_bands()
    y = MARGIN
    for band in bands:
        for b in band:
            b.y = y
        y += band[0].h + BAND_GAP
    total_h = y - BAND_GAP + MARGIN

    out = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{total_h}" '
        f'viewBox="0 0 {W} {total_h}" font-family="{FONT}">',
        '<rect width="100%" height="100%" fill="#FFFFFF"/>',
        '<defs>'
        '<marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" '
        'markerWidth="7" markerHeight="7" orient="auto-start-reverse">'
        '<path d="M 0 0 L 10 5 L 0 10 z" fill="#5F6368"/></marker>'
        '<filter id="shadow" x="-10%" y="-10%" width="120%" height="130%">'
        '<feDropShadow dx="0" dy="2.5" stdDeviation="4" flood-color="#3C4043" flood-opacity="0.16"/>'
        '</filter>'
        '</defs>',
    ]

    # 箭头（画在盒下层）
    for fi, ti, label, dashed, split in ARROWS:
        fy = bands[fi][0].y + bands[fi][0].h
        ty = bands[ti][0].y
        my = (fy + ty) / 2
        if dashed is not None:  # None = 分隔标签（无箭头）
            style = 'stroke="#5F6368" stroke-width="1.6" fill="none" marker-end="url(#arrow)"'
            if dashed:
                style = style.replace('stroke-width="1.6"', 'stroke-width="1.6" stroke-dasharray="6 4"')
            if split:  # 双栏 → 下一层：两根斜线汇入
                for b in bands[fi]:
                    out.append(f'<path d="M {b.cx} {fy} L {b.cx} {my} L {W/2} {ty-4}" {style}/>')
            else:
                fx = bands[fi][0].cx if len(bands[fi]) == 1 else W / 2
                out.append(f'<path d="M {fx} {fy} L {fx} {ty-4}" {style}/>')
        if label:
            lw = text_w(label)
            out.append(f'<rect x="{W/2 - lw/2 - 8}" y="{my - 12}" width="{lw + 16}" height="24" rx="12" fill="#FFFFFF" stroke="#DADCE0"/>')
            out.append(f'<text x="{W/2}" y="{my + 5}" font-size="13" fill="#5F6368" text-anchor="middle">{esc(label)}</text>')

    # 盒子
    for band in bands:
        for b in band:
            r = 14
            out.append(f'<rect x="{b.x}" y="{b.y}" width="{b.w}" height="{b.h}" rx="{r}" '
                       f'fill="{b.fill}" stroke="{b.stroke}" stroke-width="1.8" filter="url(#shadow)"/>')
            # 标题色带（仅顶部圆角）
            hh = b.header_h
            out.append(f'<path d="M {b.x} {b.y + hh} L {b.x} {b.y + r} Q {b.x} {b.y} {b.x + r} {b.y} '
                       f'L {b.x + b.w - r} {b.y} Q {b.x + b.w} {b.y} {b.x + b.w} {b.y + r} '
                       f'L {b.x + b.w} {b.y + hh} Z" fill="{b.stroke}" fill-opacity="0.12"/>')
            out.append(f'<text x="{b.x + PAD_H}" y="{b.y + PAD_V + 18}" font-size="16" '
                       f'font-weight="700" fill="{b.stroke}">{b.icon}  {esc(b.title)}</text>')
            for i, line in enumerate(b.lines):
                ly = b.y + PAD_V + TITLE_H + 12 + i * LINE_H
                mono = line.startswith(":") or line.startswith("commonMain") or line.startswith("androidMain") or line.startswith("iosMain")
                warn = line.startswith("⚠")
                fam = MONO if mono else FONT
                color = "#D93025" if warn else "#3C4043"
                # 行首小圆点
                out.append(f'<circle cx="{b.x + PAD_H + 3}" cy="{ly - 4.5}" r="2.6" '
                           f'fill="{b.stroke}" fill-opacity="0.55"/>')
                out.append(f'<text x="{b.x + PAD_H + 14}" y="{ly}" font-size="13.5" fill="{color}" '
                           f'font-family="{fam}">{esc(line)}</text>')

    out.append("</svg>")
    svg = "\n".join(out)
    path = "docs/assets/architecture.svg"
    with open(path, "w") as f:
        f.write(svg)
    print(f"✅ {path} ({W}x{total_h})")


if __name__ == "__main__":
    main()
