# PicMe AccessibilityService UI 自动化测试技术方案

> **目标**：用结构化文本 UI 描述替代现有 `adb screencap` + 图像识别的测试方案，解决截图不准、识别耗 token 的问题。
> **部署形态**：先以内嵌 debug-only AccessibilityService（方案 B）落地，保持未来向独立 test APK 迁移的可能性。
> **通信方式**：Local Socket + `adb forward`，替代广播通道，降低延迟并支持双向实时请求-响应。

---

## 1. 背景与问题

当前 PicMe 的自动研发/测试流程主要依赖：

1. `adb shell screencap -p` 截图。
2. 把截图传给大模型做图像识别，或用 `scripts/screenshot-diff.py` / `scripts/ui-check.py` 做像素分析。
3. 用 `adb shell input tap <x> <y>` 做坐标点击。

存在两个核心问题：

- **不准确**：截图容易截错时机，坐标点击依赖设备分辨率，跨设备会漂移；图像识别对文字、小按钮、动态 UI 鲁棒性差。
- **浪费 token**：把整张屏幕像素发给大模型，token 消耗高，且推理结果不稳定。

本方案改为：由 Android 端 AccessibilityService 直接提取当前界面的结构化文本描述（节点树），通过 Local Socket 回传给 PC 端工具/Skill，PC 端基于文本做断言和操作。

---

## 2. 总体架构

```
┌─────────────────────────────────────────────────────────────┐
│                         PC / 开发机                          │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────┐  │
│  │ agent-tester │  │ kimi skill   │  │ regression-test  │  │
│  │  (Bash)      │  │  (Python)    │  │   (Bash)         │  │
│  └──────┬───────┘  └──────┬───────┘  └────────┬─────────┘  │
│         └─────────────────┴───────────────────┘             │
│                           │                                  │
│              ┌────────────┴────────────┐                    │
│              │  UiDriverClient (Python)  │                    │
│              │  - manage adb forward     │                    │
│              │  - JSON-RPC over socket   │                    │
│              └────────────┬────────────┘                    │
└───────────────────────────┼─────────────────────────────────┘
                            │ adb forward tcp:27183 tcp:27183
┌───────────────────────────┼─────────────────────────────────┐
│                      Android 设备                             │
│              ┌────────────┴────────────┐                    │
│              │ PicMeAccessibilityService │  ← app/src/debug/  │
│              │  - 监听 localhost:27183   │                    │
│              │  - dump Accessibility Tree│                    │
│              │  - perform click/swipe/.. │                    │
│              └────────────┬────────────┘                    │
│                           │                                  │
│              ┌────────────┴────────────┐                    │
│              │   PicMe App UI (Compose)  │                    │
│              └─────────────────────────┘                    │
└─────────────────────────────────────────────────────────────┘
```

### 2.1 组件边界

| 组件 | 位置 | 职责 | 不做什么 |
|------|------|------|----------|
| `PicMeAccessibilityService` | `app/src/debug/` | 监听 socket、dump UI 树、执行输入操作 | 不执行业务命令（如拍照、切滤镜） |
| `UiAutomationRpcServer` | `app/src/debug/` | JSON-RPC 请求分发与响应 | 不直接访问 Accessibility API |
| `AccessibilityNodeSerializer` | `app/src/debug/` | 把 `AccessibilityNodeInfo` 序列化为 JSON | 不执行操作 |
| `AccessibilityActionPerformer` | `app/src/debug/` | 封装点击、滑动、输入、返回等操作 | 不解析命令 |
| `UiDriverClient` | `scripts/ui_driver.py` | PC 端 socket 通信、adb forward 管理、高层 API | 不直接调用 Accessibility API |
| Kimi Skill | `.kimi-code/skills/picme-ui-driver/` | 把 `UiDriverClient` 暴露给 AI coding 工具 | 不做业务断言逻辑 |

### 2.2 与现有测试基础设施的关系

- **业务命令**（`capture`、`flip_camera`、`switch_filter`、`adjust_beauty` 等）继续走现有的 `AgentTestBroadcastReceiver` + `CapabilityRegistry`，不受影响。
- **UI 断言和操作**（点击“相册”、检查“拍照完成”文本是否存在）改为走 AccessibilityService + socket。
- 现有 `scripts/regression-test.sh`、`scripts/agent-tester` 逐步替换坐标点击和截图对比逻辑，但广播命令入口保留。

---

## 3. 通信协议

### 3.1 传输层

- **协议**：TCP over localhost。
- **端口**：`127.0.0.1:27183`。
- **暴露方式**：`adb forward tcp:27183 tcp:27183`。
- **消息格式**：Line-Delimited JSON-RPC 2.0，每条消息以 `\n` 结尾。

### 3.2 JSON-RPC 方法

| 方法 | 参数 | 返回 | 说明 |
|------|------|------|------|
| `ui.dump` | `package?: string`, `maxDepth?: int`, `includeInvisible?: bool` | UI 节点树 | 导出当前窗口节点树 |
| `ui.find` | `text?: string`, `contentDescription?: string`, `className?: string`, `clickable?: bool`, `scrollable?: bool` | 节点列表 | 按条件查找节点 |
| `action.click` | `text?`, `bounds?`, `nodeId?` | `{success, node?}` | 点击指定节点 |
| `action.longClick` | 同上 | 同上 | 长按 |
| `action.swipe` | `start: {x,y}`, `end: {x,y}`, `durationMs?: int` | `{success}` | 滑动 |
| `action.input` | `text?`, `nodeId?`, `value: string` | `{success}` | 输入文本 |
| `action.pressBack` | - | `{success}` | 返回键 |
| `action.waitForIdle` | `timeoutMs?: int` | `{success}` | 等待 UI 空闲 |
| `ping` | - | `{pong: true}` | 心跳 |

### 3.3 请求/响应示例

请求：
```json
{"jsonrpc":"2.0","id":1,"method":"ui.dump","params":{"package":"com.mamba.picme","maxDepth":50}}
```

响应：
```json
{"jsonrpc":"2.0","id":1,"result":{"window":{"title":"PicMe","width":1080,"height":2400},"nodes":[{"id":"0","packageName":"com.mamba.picme","className":"android.widget.Button","text":"相册","bounds":{"left":0,"top":100,"right":200,"bottom":300},"clickable":true,"children":[]}]}}
```

---

## 4. 数据模型

### 4.1 UI 节点

```json
{
  "id": "string",
  "packageName": "string",
  "className": "string",
  "text": "string",
  "contentDescription": "string",
  "hint": "string",
  "bounds": {
    "left": 0,
    "top": 0,
    "right": 1080,
    "bottom": 2400
  },
  "clickable": true,
  "longClickable": false,
  "scrollable": false,
  "enabled": true,
  "checked": false,
  "selected": false,
  "focused": false,
  "children": []
}
```

### 4.2 窗口元数据

```json
{
  "window": {
    "title": "PicMe",
    "width": 1080,
    "height": 2400,
    "timestampMs": 1751370603842
  }
}
```

### 4.3 节点 ID 生成

- 使用遍历序号（BFS 深度优先均可），例如 `"0"`, `"0.1"`, `"0.1.2"`。
- 保证同一次 dump 内唯一即可，不需要跨会话稳定。

---

## 5. Android 服务端实现

### 5.1 代码结构

```
app/src/debug/
├── java/com/mamba/picme/testing/accessibility/
│   ├── PicMeAccessibilityService.kt
│   ├── UiAutomationRpcServer.kt
│   ├── AccessibilityNodeSerializer.kt
│   ├── AccessibilityActionPerformer.kt
│   └── model/
│       ├── UiNode.kt
│       ├── UiWindow.kt
│       └── RpcRequest.kt / RpcResponse.kt
├── res/
│   ├── xml/accessibility_service_config.xml
│   └── values/accessibility_service_strings.xml
└── AndroidManifest.xml
```

### 5.2 PicMeAccessibilityService

- 继承 `android.accessibilityservice.AccessibilityService`。
- 在 `onServiceConnected()` 中启动 `UiAutomationRpcServer`。
- 在 `onInterrupt()` 和 `onUnbind()` 中关闭 socket server。
- 提供 `getRootNode()` 给 RPC server 获取当前窗口根节点。

### 5.3 UiAutomationRpcServer

- 使用 `ServerSocket(27183, 0, InetAddress.getByName("127.0.0.1"))`。
- 每个连接启动一个独立协程（Kotlin Coroutine）或线程处理请求。
- 主线程调用 Accessibility API，避免并发访问同一窗口树。

### 5.4 AccessibilityNodeSerializer

- 递归遍历 `AccessibilityNodeInfo`。
- 过滤：默认只保留 `packageName == com.mamba.picme` 的节点，避免把系统状态栏、导航栏 dump 进来。
- 限制：支持 `maxDepth` 和 `includeInvisible` 参数。
- 回收：对要返回的节点使用 `AccessibilityNodeInfo.obtain()` 复制，避免跨调用访问已回收节点。

### 5.5 AccessibilityActionPerformer

- `clickByText` / `clickByBounds` / `clickByNodeId`：先 find 节点，再调用 `node.performAction(AccessibilityNodeInfo.ACTION_CLICK)`。
- `swipe`：通过 `GestureDescription` 构造手势，调用 `dispatchGesture()`。
- `input`：先 `ACTION_FOCUS` 再 `ACTION_SET_TEXT`。
- `pressBack`：调用 `service.performGlobalAction(GLOBAL_ACTION_BACK)`。
- `waitForIdle`：轮询 `rootInActiveWindow` 直到连续两次 dump 一致或超时。

### 5.6 Manifest 与配置

`app/src/debug/AndroidManifest.xml`（仅 debug 合并）：
```xml
<service
    android:name=".testing.accessibility.PicMeAccessibilityService"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"
    android:exported="true">
    <intent-filter>
        <action android:name="android.accessibilityservice.AccessibilityService" />
    </intent-filter>
    <meta-data
        android:name="android.accessibilityservice"
        android:resource="@xml/accessibility_service_config" />
</service>
```

`res/xml/accessibility_service_config.xml`：
```xml
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeWindowContentChanged|typeWindowsChanged"
    android:accessibilityFlags="flagRetrieveInteractiveWindows|flagReportViewIds"
    android:canRetrieveWindowContent="true"
    android:description="@string/accessibility_service_description" />
```

### 5.7 启用引导

AccessibilityService 无法由 App 自动启动，必须用户在系统设置中手动开启。PC 端客户端在连接前检查：

```bash
adb shell settings get secure enabled_accessibility_services | grep com.mamba.picme
```

未启用时：
1. 打印错误并提示手动开启。
2. 可自动打开设置页：
   ```bash
   adb shell am start -a android.settings.ACCESSIBILITY_SETTINGS
   ```
3. 退出测试脚本，等待用户开启后重试。

---

## 6. PC 端客户端

### 6.1 文件位置

```
scripts/ui_driver.py
```

### 6.2 核心 API

```python
class UiDriverClient:
    def __init__(self, device: str | None = None, local_port: int = 27183, remote_port: int = 27183):
        ...

    def __enter__(self) -> "UiDriverClient": ...
    def __exit__(self, *args) -> None: ...

    def dump_ui(self, package: str | None = None, max_depth: int = 50) -> UiTree: ...
    def find_nodes(self, text: str | None = None, content_desc: str | None = None,
                   class_name: str | None = None, clickable: bool | None = None) -> list[UiNode]: ...
    def click(self, text: str | None = None, node_id: str | None = None, bounds: Bounds | None = None) -> bool: ...
    def long_click(self, text: str | None = None, node_id: str | None = None) -> bool: ...
    def swipe(self, start: Point, end: Point, duration_ms: int = 300) -> bool: ...
    def input_text(self, value: str, text: str | None = None, node_id: str | None = None) -> bool: ...
    def press_back(self) -> bool: ...
    def wait_for_idle(self, timeout_ms: int = 5000) -> bool: ...
    def wait_for(self, text: str, timeout_ms: int = 5000, poll_ms: int = 200) -> UiNode | None: ...
```

### 6.3 连接管理

- `__enter__` 时：
  1. 检查设备连接。
  2. 检查 AccessibilityService 是否启用。
  3. 执行 `adb forward tcp:<local_port> tcp:<remote_port>`。
  4. 连接 socket，超时 5s。
  5. 发送 `ping` 验证服务存活。
- `__exit__` 时：
  1. 关闭 socket。
  2. 移除 forward（可选，通常保留不影响）。

### 6.4 错误处理

| 错误 | 行为 |
|------|------|
| 无设备 | 抛出 `NoDeviceError` |
| AccessibilityService 未启用 | 抛出 `AccessibilityNotEnabledError`，附开启指引 |
| `adb forward` 失败 | 重试 3 次 |
| Socket 连接失败 | 重试 3 次，每次退避 500ms |
| 请求超时 | 抛出 `UiDriverTimeoutError` |
| 操作返回 false | 返回 `ActionResult(success=False, current_ui=...)` |

---

## 7. Kimi Skill 集成

### 7.1 Skill 设计

新增 skill：`picme-ui-driver`

暴露给大模型的能力：

- `get_ui_state(package?)`：返回当前界面结构化文本摘要。
- `find_ui_element(text|id|desc)`：查找并返回节点。
- `tap_ui_element(text|id)`：点击。
- `input_text(text|id, value)`：输入。
- `press_back()`：返回。
- `wait_for_ui_element(text, timeout)`：等待元素出现。

### 7.2 UI 树压缩格式

Skill 把 JSON 节点树压缩成 LLM 易读文本，例如：

```
Window: PicMe (1080x2400)
- [Text] "相册"
- [Button] "快门" clickable bounds=(480,2100,600,2220)
- [Icon] "切换摄像头" clickable contentDesc="切换摄像头"
```

压缩规则：
- 隐藏无 text、无 contentDescription、不可点击、无子节点的叶子节点。
- 保留所有可交互节点（clickable/longClickable/scrollable/editable）。
- 保留有文本或描述信息的节点。

---

## 8. 迁移与落地计划

### Phase 1：最小闭环（1~2 周）

1. 创建 `app/src/debug/` 源集。
2. 实现 `PicMeAccessibilityService` + `UiAutomationRpcServer`。
3. 实现 `AccessibilityNodeSerializer` 和 `AccessibilityActionPerformer`。
4. 实现 `scripts/ui_driver.py` 基础 API（dump / click / press_back / ping）。
5. 写一个最小验证脚本：启动 App → dump 相机页 → 点击“相册”→ dump 相册页。

### Phase 2：替换回归测试（2~3 周）

1. 改写 `regression-test.sh` 中基于坐标点击和截图对比的用例。
2. 移除或降级 `scripts/screenshot-diff.py` 在回归流程中的使用。
3. 补全 `ui_driver.py` API（swipe、input、wait_for 等）。
4. 在关键页面补充 Compose 语义信息（`contentDescription`、`testTag`），提高节点可识别性。

### Phase 3：Skill 化（1 周）

1. 创建 `.kimi-code/skills/picme-ui-driver/`。
2. 封装 `UiDriverClient` 为 Skill 函数。
3. 让 AI coding 工具可以通过 Skill 获取 UI 状态并执行操作。

### Phase 4：向独立 test APK 迁移（可选）

1. 将 `PicMeAccessibilityService` 相关代码迁移到独立模块/应用。
2. 修改 PC 端客户端以支持选择目标包名。
3. 此时可对 release 包进行测试。

---

## 9. 风险与应对

| 风险 | 影响 | 应对 |
|------|------|------|
| AccessibilityService 必须手动开启 | 首次 CI/开发 setup 成本高 | 写 setup 脚本并打印清晰指引；开启后长期有效 |
| Compose 组件缺少语义信息 | 节点树为空或不可识别 | 逐步补 `contentDescription` / `testTag`；先在有语义的页面验证 |
| 系统状态栏/导航栏干扰 | dump 结果包含无关节点 | 默认按 `packageName` 过滤 |
| 后台服务被杀 | socket 断开 | 客户端自动重连 + 提示重新开启服务 |
| 安全/隐私审查 | debug 包带无障碍服务 | 仅在 `src/debug/` 声明，release 构建不包含 |
| 多设备并发测试 | 端口冲突 | 每个设备使用不同 local port |

---

## 10. 验收标准

- [ ] debug APK 能正常安装并开启 AccessibilityService。
- [ ] `UiDriverClient` 能通过 socket 成功 dump 出 PicMe 当前界面节点树。
- [ ] 节点树中至少能识别“快门”“相册”“切换摄像头”等核心按钮。
- [ ] 能通过文本点击“相册”并成功进入相册页。
- [ ] `regression-test.sh --gallery` 不再依赖坐标点击完成“进入相册”步骤。
- [ ] Kimi skill 能获取 UI 状态并执行点击操作。

---

## 11. 相关文档与代码

- 顶层治理：`/AGENTS.md`
- 现有测试广播：`app/src/main/java/com/mamba/picme/testing/agent/bridge/AgentTestBroadcastReceiver.kt`
- 现有测试框架：`app/src/main/java/com/mamba/picme/testing/agent/core/AgentTestFramework.kt`
- 现有回归脚本：`scripts/regression-test.sh`
- 现有截图对比：`scripts/screenshot-diff.py`
- 本设计文档：`docs/superpowers/specs/2026-07-02-accessibility-ui-automation-design.md`
