#!/usr/bin/env python3
"""一键打开相册并搜索照片（单会话，最小化 RPC 往返）。

相比分步调用 ui_driver.py，本脚本：
1. 只建立一次 UiDriverClient 连接
2. 用 wait_for 主动轮询替代固定 sleep
3. 把 navigate_to broadcast、click、input_text 串行在单个脚本里

用法：
    python3 scripts/gallery_search.py "6月湖边的美女"
"""

import argparse
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.absolute()))

from ui_driver import UiDriverClient, UiNode


def navigate_to_gallery() -> None:
    """通过 agent-test broadcast 导航到相册（不阻塞）"""
    json_cmd = '{"method":"navigate_to","params":{"destination":"gallery"}}'
    # 必须把整个 intent 作为单个 shell 命令传递，避免 JSON 中的 : 被 am 解析成 data URI
    shell_cmd = (
        "am broadcast -n com.mamba.picme/.testing.agent.bridge.AgentTestBroadcastReceiver "
        f"-a com.mamba.picme.AGENT_TEST --es json '{json_cmd}'"
    )
    subprocess.run(["adb", "shell", shell_cmd], capture_output=True, text=True, check=False)


def has_child_text(node: UiNode, substring: str) -> bool:
    return any(
        substring in (child.text or "")
        for child in node.children
    )


def is_search_edit(node: UiNode) -> bool:
    """判断节点是否为相册顶部搜索框。"""
    if node.class_name != "android.widget.EditText":
        return False
    # 搜索框位于顶部栏（y < 400），避免误认其他输入框
    if node.bounds.top >= 400:
        return False
    return True


def main() -> int:
    parser = argparse.ArgumentParser(description="Open gallery and search photos")
    parser.add_argument("query", help="Search query, e.g. '6月湖边的美女'")
    parser.add_argument("--device", help="adb device serial")
    parser.add_argument("--port", type=int, default=27183, help="RPC port")
    parser.add_argument("--no-navigate", action="store_true", help="Skip navigate broadcast")
    parser.add_argument("--screenshot", type=str, default="/tmp/gallery_search_result.png",
                        help="Screenshot output path")
    args = parser.parse_args()

    start = time.time()

    if not args.no_navigate:
        navigate_to_gallery()

    with UiDriverClient(device=args.device, local_port=args.port) as client:
        connect_time = time.time()
        print(f"[+] RPC connected in {connect_time - start:.2f}s")

        # 1. 等待相册页准备好：可能是“搜索照片”按钮，也可能搜索框已经打开
        # 1. 等待相册页准备好：可能是“搜索照片”按钮，也可能搜索框已经打开
        # 先快速检查是否已在搜索模式，避免为等按钮轮询 6 秒
        edits = client.find_nodes(class_name="android.widget.EditText")
        search_edit = next((n for n in edits if is_search_edit(n)), None)

        if search_edit is None:
            # 未在搜索模式，等待并点击“搜索照片”按钮
            search_btn = client.wait_for(
                content_description="搜索照片",
                timeout_ms=5000,
                poll_ms=200,
            )
            if search_btn is None:
                print("[-] 相册搜索入口未出现", file=sys.stderr)
                return 1
            if not client.click(content_description="搜索照片"):
                print("[-] 点击搜索按钮失败", file=sys.stderr)
                return 1
            deadline = time.time() + 3.0
            while time.time() < deadline:
                edits = client.find_nodes(class_name="android.widget.EditText")
                search_edit = next((n for n in edits if is_search_edit(n)), None)
                if search_edit is not None:
                    break
                time.sleep(0.15)
            if search_edit is None:
                print("[-] 搜索输入框未出现", file=sys.stderr)
                return 1

        print(f"[+] Gallery ready in {time.time() - start:.2f}s")

        # 4. 输入搜索词（Compose TextField 会自动触发搜索）
        # bounds 在动画后可能轻微漂移，最多重试 3 次
        input_ok = False
        for attempt in range(3):
            edits = client.find_nodes(class_name="android.widget.EditText")
            fresh_edit = next((n for n in edits if is_search_edit(n)), None)
            if fresh_edit is None:
                time.sleep(0.1)
                continue
            if client.input_text(args.query, bounds=fresh_edit.bounds):
                input_ok = True
                break
            time.sleep(0.1)

        if not input_ok:
            print("[-] 输入搜索词失败", file=sys.stderr)
            return 1
        print(f"[+] Query input in {time.time() - start:.2f}s")

        # 5. 等待搜索结果出现
        result_marker = f'搜索 "{args.query}"'
        result_node = client.wait_for(
            predicate=lambda n: n.text is not None and result_marker in n.text,
            timeout_ms=10000,
            poll_ms=200,
        )
        elapsed = time.time() - start
        if result_node is None:
            print(f"[-] 搜索结果未在 {elapsed:.2f}s 内出现", file=sys.stderr)
            return 1

        print(f"[+] Search result: {result_node.text} (total {elapsed:.2f}s)")

    # 6. 截图（exec-out 直传电脑，设备零残留，不污染相册/MediaStore）
    with open(args.screenshot, "wb") as f:
        subprocess.run(["adb", "exec-out", "screencap", "-p"], stdout=f, check=False)
    print(f"[+] Screenshot: {args.screenshot}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
