#!/usr/bin/env python3
"""采集剩余相机状态:beauty_makeup(切 MAKEUP tab)+ focusing/capturing(瞬态,尽力)。
permission_denied 需 revoke 相机权限(侵入式),本轮跳过,留备注。
复用 capture_camera_auto 的辅助函数。
"""
import sys, time, subprocess
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
from capture_camera_auto import click_target, save_tree, shot  # noqa: E402
from ui_driver import UiDriverClient  # noqa: E402


def raw_tap(x, y, delay=0.7):
    subprocess.run(["adb", "shell", "input", "tap", str(x), str(y)], check=False)
    time.sleep(delay)


def main():
    with UiDriverClient() as c:
        # 1) beauty MAKEUP tab(美颜面板默认 FACE,点 妆容 切 makeup)
        click_target(c, "cd", "美颜"); time.sleep(1.2)
        ok = click_target(c, "cd", "妆容"); time.sleep(1.0)
        n = save_tree(c, "panel_beauty_makeup")
        print(f"==> panel_beauty_makeup  tab_click={ok}  tree={n}  png={shot('panel_beauty_makeup')}")
        raw_tap(600, 1000)  # 关闭美颜面板

        # 2) focusing:点预览触发对焦环(~1.5s),快速截图抓环
        raw_tap(600, 1200, delay=0.30)
        try:
            save_tree(c, "focusing")
        except Exception as e:
            print("   focusing tree err:", e)
        print(f"==> focusing  png={shot('focusing')}")

        # 3) capturing:点快门触发闪屏(~80ms 瞬态),截图尽力抓;抓不到则该态按 token 值建帧(未锚定)
        raw_tap(600, 2481, delay=0.05)  # 快门中心 (497,2378,704,2585)
        print(f"==> capturing  png={shot('capturing')}  (瞬态,可能未抓到闪屏)")

    print("\n完成。permission_denied 已跳过(侵入式,留备注)。")


if __name__ == "__main__":
    main()
