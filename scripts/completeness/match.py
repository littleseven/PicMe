#!/usr/bin/env python3
"""完整性闸门匹配器(精修版):Figma 帧 node ↔ iOS a11y 列表。
判「在不在」:按 canonical 标签匹配(role 软匹配——Figma 文字占位 vs iOS 图标按钮,
role 本就不同,故 role 不作 fail 判据,只作 warning)。
噪声处理:
  1) ALIAS——iOS 图标按钮按 Material id 标(mat_autofix 等),Figma 用中文标签;
     用别名表归一到同一 canonical(美颜↔mat_autofix)。
  2) DEBUG——iOS debug 叠加层(beauty.*/camera.*/face.*/gallery.* 的 key:value、
     截屏/cpu/引擎切换/pager 描述)不在 canonical 帧,过滤掉(仅影响 iOS extra)。
纯函数 + CLI(env 无 pytest)。用法:python3 match.py <figma.json> <ios.json> [state]
"""
from __future__ import annotations
import json
import re
import sys
from dataclasses import dataclass, field
from typing import List

# Figma 中文标签 ↔ iOS Material 图标 id(右列 6 控件)
ALIAS = {
    "美颜": ["mat_autofix"],
    "比例": ["mat_aspect_ratio", "画面比例"],
    "辅助线": ["mat_grid_on"],
    "场景": ["mat_landscape"],
    "滤镜": ["mat_filter_b_and_w"],
    "专业": ["mat_tune"],
    "无": ["不启用", "none"],  # grid/scene 关闭项:iOS"不启用" ↔ Android"无"
    "色温(k)": ["色温", "色温（K）", "色温(K)"],  # 全/半角括号变体
}
# 反查:iOS id/标签 → canonical 中文
_REVERSE = {v: k for k, vals in ALIAS.items() for v in vals}

# iOS debug 叠加层过滤(debug build 才有的运行时信息,不在 canonical 设计帧)
_DEBUG_KEYVAL = re.compile(r"^(beauty|camera|face|gallery)\.", re.I)
_DEBUG_LABELS = {"截屏", "cpu", "mnn", "mediapipe", "/", "垂直滚动条", "4页", "垂直滚动条, 4页"}


@dataclass(frozen=True)
class NormNode:
    label: str
    role: str
    x: float
    y: float
    w: float
    h: float


@dataclass
class Report:
    missing: List[NormNode] = field(default_factory=list)  # Figma 有、iOS 无 → fail
    extra: List[NormNode] = field(default_factory=list)    # iOS 多 → 警告
    role_mismatch: List[tuple] = field(default_factory=list)  # label 对、role 不同 → warning

    @property
    def ok(self) -> bool:
        return len(self.missing) == 0


def _canonical(label: str):
    """归一标签;iOS 图标 id → 中文,debug → None(过滤)。"""
    s = label.strip()
    if not s:
        return None
    if _DEBUG_KEYVAL.match(s) or s in _DEBUG_LABELS:
        return None
    # 滑杆数值是状态相关的(36/-- 随参数变),非结构完整性 → 不作匹配判据
    if re.fullmatch(r"\d+", s) or s == "--":
        return None
    # 状态胶囊(Beauty: ...fps/engine)是运行时文案 → 双侧归一为同一 canonical
    if s.startswith("Beauty:"):
        return "beauty_status"
    if s in _REVERSE:           # iOS id/标签 → canonical 中文(小写归一)
        return _REVERSE[s].lower()
    return s.lower()


def _is_debug(label: str) -> bool:
    return _canonical(label) is None


def compare(figma_nodes: List[NormNode], ios_nodes: List[NormNode]) -> Report:
    """Figma 侧不过滤(debug 不在 Figma);iOS 侧过滤 debug。按 canonical 匹配,role 软。"""
    f = {}  # canonical -> NormNode
    for n in figma_nodes:
        c = _canonical(n.label)
        if c and c not in f:
            f[c] = n
    i = {}
    for n in ios_nodes:
        c = _canonical(n.label)
        if not c:
            continue  # debug → 跳过
        if c not in i:
            i[c] = n
    missing = [n for c, n in f.items() if c not in i]
    extra = [n for c, n in i.items() if c not in f]
    role_mismatch = [(fn, i[fc]) for fc, fn in f.items() if fc in i and fn.role != i[fc].role]
    return Report(missing=missing, extra=extra, role_mismatch=role_mismatch)


def _load(path: str) -> List[NormNode]:
    data = json.load(open(path, encoding="utf-8"))
    nodes = data.get("nodes", data) if isinstance(data, dict) else data
    out = []
    for n in nodes:
        out.append(NormNode(
            label=str(n.get("label", n.get("text", ""))),
            role=str(n.get("role", n.get("type", "generic"))),
            x=float(n.get("x", 0)), y=float(n.get("y", 0)),
            w=float(n.get("w", n.get("width", 0))), h=float(n.get("h", n.get("height", 0))),
        ))
    return out


def main(argv):
    if len(argv) < 3:
        print("usage: match.py <figma_nodes.json> <ios_nodes.json> [state]")
        return 2
    figma_path, ios_path = argv[1], argv[2]
    state = argv[3] if len(argv) > 3 else "?"
    rep = compare(_load(figma_path), _load(ios_path))
    print(f"[{state}] {'OK' if rep.ok else 'GAP'}  missing={len(rep.missing)} extra={len(rep.extra)} role_mismatch={len(rep.role_mismatch)}")
    for m in rep.missing:
        print(f"  🔴 缺(iOS 未实现): {m.label} ({m.role})")
    for e in rep.extra:
        print(f"  🟡 多(iOS 额外): {e.label} ({e.role})")
    for fn, i_n in rep.role_mismatch:
        print(f"  ⚪ role 异: {fn.label}  figma={fn.role} ios={i_n.role}")
    return 0 if rep.ok else 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
