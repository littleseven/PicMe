#!/usr/bin/env python3
"""自动采集相机页各状态(Android ground truth)。
用 ui_driver(accessibility 驱动点击)+ adb screencap,无需手动操作。
产物:specs/screens/refs/android/camera-<state>.{png,txt}(png=视觉地面真值,txt=a11y 树)。
"""
import sys, time, subprocess
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
from ui_driver import UiDriverClient, Bounds  # noqa: E402

OUT = Path(__file__).resolve().parents[1] / "specs" / "screens" / "refs" / "android"
OUT.mkdir(parents=True, exist_ok=True)
PKG = "com.mamba.picme"
# 预览区中央点一下 = 关闭面板(camera.yaml dismiss 规则,不会退出相机)
PREVIEW_TAP = Bounds(520, 900, 680, 1060)

# 右列控件(顶→底):美颜/比例/网格(icon-only,无文本)/场景/滤镜/专业模式
PANELS = [
    ("panel_beauty_face", "cd", "美颜"),
    ("panel_ratio", "cd", "画面比例"),
    ("panel_grid", "bounds", Bounds(992, 629, 1148, 785)),
    ("panel_scene", "cd", "场景"),
    ("panel_filter", "cd", "滤镜"),
    ("panel_pro", "cd", "专业模式"),
]


def shot(st):
    p = OUT / f"camera-{st}.png"
    with open(p, "wb") as f:
        subprocess.run(["adb", "exec-out", "screencap", "-p"], stdout=f, check=True)
    return p.stat().st_size


def save_tree(c, st):
    tree = c.dump_ui(package=PKG)
    lines = []

    def walk(n, d=0):
        t = (n.text or "").strip()
        cd = (n.content_description or "").strip()
        flag = "clickable" if n.clickable else ""
        if t or cd or n.clickable:
            lines.append(
                f"{'  ' * d}[{flag}] text={t!r} cd={cd!r} "
                f"cls={(n.class_name or '').split('.')[-1]} "
                f"b=({n.bounds.left},{n.bounds.top},{n.bounds.right},{n.bounds.bottom})"
            )
        for ch in n.children:
            walk(ch, d + 1)

    walk(tree)
    (OUT / f"camera-{st}.txt").write_text("\n".join(lines))
    return len(lines)


def click_target(c, kind, target):
    if kind == "bounds":
        return c.click(bounds=target)
    # cd 优先,失败回退 text,再回退 find_node+bounds
    for attempt in ("cd", "text"):
        try:
            if attempt == "cd":
                if c.click(content_description=target):
                    return True
            else:
                if c.click(text=target):
                    return True
        except Exception:
            pass
    n = c.find_node(
        lambda n: (n.text or "").strip() == target or (n.content_description or "").strip() == target
    )
    return bool(n and c.click(bounds=n.bounds))


def main():
    with UiDriverClient() as c:
        # idle(刚进相机页,面板应已关)
        print("==> idle")
        n = save_tree(c, "idle")
        print(f"   tree={n} lines  png={shot('idle')} bytes")

        for st, kind, target in PANELS:
            ok = click_target(c, kind, target)
            time.sleep(1.2)  # 等面板动画
            n = save_tree(c, st)
            sz = shot(st)
            print(f"==> {st}  click={ok}  tree={n} lines  png={sz} bytes")
            # 关闭面板:此时面板必开,点 scrim 区(预览上半,y≈1000,面板在底部 35%)
            # 用 raw input tap(无需 node),camera.yaml dismiss 规则关闭面板,不会退出相机
            subprocess.run(["adb", "shell", "input", "tap", "600", "1000"], check=False)
            time.sleep(0.8)

        print("\n完成。产物:", OUT)


if __name__ == "__main__":
    main()
