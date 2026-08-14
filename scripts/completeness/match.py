#!/usr/bin/env python3
"""完整性闸门匹配器:Figma 帧 node 列表 ↔ iOS a11y 列表。
仅判「在不在」(label + role 匹配);尺寸/位置偏差属还原度,另处理。
纯函数 + CLI(env 无 pytest,故提供 standalone 入口)。
用法: python3 match.py <figma_nodes.json> <ios_nodes.json> [state_label]
      两 JSON 格式: {"state":..., "nodes":[{"label","role","x","y","w","h"}, ...]}
退出码: 0=无 missing(通过), 1=有 missing(完整性缺口)。
"""
from __future__ import annotations
import json
import sys
from dataclasses import dataclass, field
from typing import List


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
    missing: List[NormNode] = field(default_factory=list)  # Figma 有、iOS 无 → 失败
    extra: List[NormNode] = field(default_factory=list)    # iOS 多 → 警告

    @property
    def ok(self) -> bool:
        return len(self.missing) == 0


def _key(n: NormNode):
    return (n.label.strip().lower(), n.role.strip().lower())


def compare(figma_nodes: List[NormNode], ios_nodes: List[NormNode]) -> Report:
    """逐元素匹配;label+role 去空白小写后作键。"""
    f = {_key(n): n for n in figma_nodes if n.label.strip()}
    i = {_key(n): n for n in ios_nodes if n.label.strip()}
    missing = [n for k, n in f.items() if k not in i]
    extra = [n for k, n in i.items() if k not in f]
    return Report(missing=missing, extra=extra)


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
    print(f"[{state}] {'OK' if rep.ok else 'GAP'}  missing={len(rep.missing)} extra={len(rep.extra)}")
    for m in rep.missing:
        print(f"  🔴 缺(iOS 未实现): {m.label} ({m.role})")
    for e in rep.extra:
        print(f"  🟡 多(iOS 额外): {e.label} ({e.role})")
    return 0 if rep.ok else 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
