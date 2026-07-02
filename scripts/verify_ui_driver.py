#!/usr/bin/env python3
"""最小验证脚本：dump 当前 PicMe 界面并尝试点击相册入口."""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from ui_driver import UiDriverClient, format_ui_tree


def main():
    print("Connecting to PicMeAccessibilityService...")
    with UiDriverClient() as client:
        print("Dumping UI...")
        tree = client.dump_ui(package="com.mamba.picme")
        print(format_ui_tree(tree))

        gallery = client.find_nodes(text="相册")
        if gallery:
            print(f"Found gallery node: {gallery[0].text}, clicking...")
            client.click(text="相册")
            print("Clicked gallery")
        else:
            print("No '相册' node found, skipping click")


if __name__ == "__main__":
    main()
