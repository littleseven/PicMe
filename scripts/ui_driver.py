#!/usr/bin/env python3
"""UiDriverClient - PC端 AccessibilityService 驱动客户端."""

import json
import socket
import subprocess
import sys
import time
from dataclasses import dataclass
from typing import Any, Optional


@dataclass
class Bounds:
    left: int
    top: int
    right: int
    bottom: int

    @property
    def center_x(self) -> int:
        return (self.left + self.right) // 2

    @property
    def center_y(self) -> int:
        return (self.top + self.bottom) // 2


@dataclass
class UiNode:
    id: str
    package_name: Optional[str]
    class_name: Optional[str]
    text: Optional[str]
    content_description: Optional[str]
    hint: Optional[str]
    bounds: Bounds
    clickable: bool
    long_clickable: bool
    scrollable: bool
    enabled: bool
    checked: bool
    selected: bool
    focused: bool
    children: list["UiNode"]

    @staticmethod
    def from_json(data: dict) -> "UiNode":
        bounds = data.get("bounds", {})
        return UiNode(
            id=data.get("id", ""),
            package_name=data.get("packageName"),
            class_name=data.get("className"),
            text=data.get("text"),
            content_description=data.get("contentDescription"),
            hint=data.get("hint"),
            bounds=Bounds(
                left=bounds.get("left", 0),
                top=bounds.get("top", 0),
                right=bounds.get("right", 0),
                bottom=bounds.get("bottom", 0),
            ),
            clickable=data.get("clickable", False),
            long_clickable=data.get("longClickable", False),
            scrollable=data.get("scrollable", False),
            enabled=data.get("enabled", False),
            checked=data.get("checked", False),
            selected=data.get("selected", False),
            focused=data.get("focused", False),
            children=[UiNode.from_json(c) for c in data.get("children", [])],
        )


class UiDriverError(Exception):
    pass


class UiDriverClient:
    def __init__(
        self,
        device: Optional[str] = None,
        local_port: int = 27183,
        remote_port: int = 27183,
    ):
        self.device = device
        self.local_port = local_port
        self.remote_port = remote_port
        self._socket: Optional[socket.socket] = None
        self._reader: Optional[Any] = None
        self._writer: Optional[Any] = None
        self._seq = 0

    def __enter__(self) -> "UiDriverClient":
        self._ensure_adb_forward()
        self._connect()
        if not self.ping():
            raise UiDriverError("Ping failed")
        return self

    def __exit__(self, exc_type, exc_val, exc_tb) -> None:
        self.close()

    def _ensure_adb_forward(self) -> None:
        cmd = ["adb"]
        if self.device:
            cmd.extend(["-s", self.device])
        cmd.extend(["forward", f"tcp:{self.local_port}", f"tcp:{self.remote_port}"])
        result = subprocess.run(cmd, capture_output=True, text=True)
        if result.returncode != 0:
            raise UiDriverError(f"adb forward failed: {result.stderr}")

    def _connect(self) -> None:
        self._socket = socket.create_connection(("127.0.0.1", self.local_port), timeout=5.0)
        self._reader = self._socket.makefile("r")
        self._writer = self._socket.makefile("w")

    def _call(self, method: str, params: Optional[dict] = None) -> dict:
        self._seq += 1
        request = {
            "jsonrpc": "2.0",
            "id": self._seq,
            "method": method,
            "params": params or {},
        }
        self._writer.write(json.dumps(request, ensure_ascii=False) + "\n")
        self._writer.flush()
        line = self._reader.readline()
        if not line:
            raise UiDriverError("Empty response from server")
        response = json.loads(line)
        if "error" in response:
            raise UiDriverError(f"RPC error: {response['error']}")
        return response.get("result", {})

    def ping(self) -> bool:
        result = self._call("ping")
        return result.get("pong", False)

    def dump_ui(self, package: Optional[str] = None, max_depth: int = 50) -> UiNode:
        result = self._call("ui.dump", {"package": package, "maxDepth": max_depth})
        return UiNode.from_json(result.get("nodes", {}))

    def find_nodes(
        self,
        text: Optional[str] = None,
        content_description: Optional[str] = None,
        class_name: Optional[str] = None,
        clickable: Optional[bool] = None,
        scrollable: Optional[bool] = None,
    ) -> list[UiNode]:
        params: dict[str, Any] = {}
        if text is not None:
            params["text"] = text
        if content_description is not None:
            params["contentDescription"] = content_description
        if class_name is not None:
            params["className"] = class_name
        if clickable is not None:
            params["clickable"] = clickable
        if scrollable is not None:
            params["scrollable"] = scrollable
        result = self._call("ui.find", params)
        return [UiNode.from_json(n) for n in result.get("nodes", [])]

    def click(self, text: Optional[str] = None, bounds: Optional[Bounds] = None) -> bool:
        params: dict[str, Any] = {}
        if text is not None:
            params["text"] = text
        if bounds is not None:
            params["bounds"] = {
                "left": bounds.left,
                "top": bounds.top,
                "right": bounds.right,
                "bottom": bounds.bottom,
            }
        result = self._call("action.click", params)
        return result.get("success", False)

    def long_click(self, text: Optional[str] = None) -> bool:
        result = self._call("action.longClick", {"text": text})
        return result.get("success", False)

    def swipe(
        self, start: tuple[int, int], end: tuple[int, int], duration_ms: int = 300
    ) -> bool:
        result = self._call(
            "action.swipe",
            {
                "start": {"x": start[0], "y": start[1]},
                "end": {"x": end[0], "y": end[1]},
                "durationMs": duration_ms,
            },
        )
        return result.get("success", False)

    def input_text(self, value: str, text: Optional[str] = None) -> bool:
        result = self._call("action.input", {"value": value, "text": text})
        return result.get("success", False)

    def press_back(self) -> bool:
        result = self._call("action.pressBack")
        return result.get("success", False)

    def wait_for_idle(self, timeout_ms: int = 5000) -> bool:
        result = self._call("action.waitForIdle", {"timeoutMs": timeout_ms})
        return result.get("success", False)

    def wait_for(
        self, text: str, timeout_ms: int = 5000, poll_ms: int = 200
    ) -> Optional[UiNode]:
        deadline = time.time() + timeout_ms / 1000.0
        while time.time() < deadline:
            nodes = self.find_nodes(text=text)
            if nodes:
                return nodes[0]
            time.sleep(poll_ms / 1000.0)
        return None

    def close(self) -> None:
        try:
            if self._reader:
                self._reader.close()
            if self._writer:
                self._writer.close()
            if self._socket:
                self._socket.close()
        except Exception:
            pass


def format_ui_tree(node: UiNode, indent: int = 0) -> str:
    prefix = "  " * indent
    label = node.text or node.content_description or node.class_name or "Unknown"
    info = []
    if node.clickable:
        info.append("clickable")
    if node.scrollable:
        info.append("scrollable")
    info.append(
        f"bounds=({node.bounds.left},{node.bounds.top},{node.bounds.right},{node.bounds.bottom})"
    )
    lines = [f"{prefix}[{node.class_name or 'Node'}] {label} {', '.join(info)}"]
    for child in node.children:
        lines.extend(format_ui_tree(child, indent + 1))
    return "\n".join(lines)


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python3 scripts/ui_driver.py dump|click <args>")
        sys.exit(1)

    command = sys.argv[1]
    with UiDriverClient() as client:
        if command == "dump":
            tree = client.dump_ui(package="com.mamba.picme")
            print(format_ui_tree(tree))
        elif command == "click":
            text = sys.argv[2] if len(sys.argv) > 2 else ""
            ok = client.click(text=text)
            print(f"click result: {ok}")
        else:
            print(f"Unknown command: {command}")
