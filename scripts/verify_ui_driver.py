#!/usr/bin/env python3
"""最小验证脚本：确保回到 PoLang 主界面，点击搜索，验证进入搜索模式."""

import time
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from ui_driver import UiDriverClient, format_ui_tree


def is_search_mode_active(client: UiDriverClient) -> bool:
    return bool(client.find_nodes(content_description="关闭搜索"))


def main():
    print("Connecting to PoLangAccessibilityService...")
    with UiDriverClient() as client:
        # Ensure we start from the main screen.
        if is_search_mode_active(client):
            print("Search mode already active, pressing back...")
            client.press_back()
            time.sleep(1)

        print("\n=== Before click ===")
        tree = client.dump_ui(package="com.mamba.picme")
        print(format_ui_tree(tree))

        if is_search_mode_active(client):
            print("\n⚠️  Still in search mode, cannot click search button")
            return

        print("\nClicking 搜索照片...")
        ok = client.click(content_description="搜索照片")
        print(f"click result: {ok}")
        time.sleep(1)

        print("\n=== After click ===")
        tree = client.dump_ui(package="com.mamba.picme")
        print(format_ui_tree(tree))

        # Verify search mode is active
        if is_search_mode_active(client):
            print("\n✅ Integration test passed: search mode entered")
        else:
            print("\n❌ Integration test failed: search mode not detected after click")
            sys.exit(1)


if __name__ == "__main__":
    main()
