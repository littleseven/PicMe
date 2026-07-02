# PicMe Accessibility UI Driver 使用说明

本文档介绍如何手动在本地设备上启用、验证和使用基于 `AccessibilityService` 的结构化 UI 自动化测试能力。

> 适用场景：你已经拉取包含本功能的最新 `main` 分支，并希望在真机/模拟器上手动验证 `scripts/ui_driver.py` 与 PicMe AccessibilityService 的交互。

---

## 1. 前置条件

| 项目 | 要求 |
|------|------|
| 代码分支 | `main`（包含 `app/src/debug/java/com/mamba/picme/testing/accessibility/`） |
| 构建工具 | Android Studio 或 `./gradlew` |
| Python | 3.8+（macOS/Linux 通常自带） |
| adb | 已配置并在 PATH 中 |
| 设备 | Android 真机或模拟器，已开启开发者选项和 USB 调试 |

---

## 2. 编译并安装 Debug APK

在工程根目录执行：

```bash
./gradlew :app:installDebug --no-daemon
```

成功后会看到 `BUILD SUCCESSFUL`，APK 会安装到当前连接的设备。

验证安装：

```bash
adb shell pm list packages | grep com.mamba.picme
```

应输出：

```
package:com.mamba.picme
```

---

## 3. 启用 PicMeAccessibilityService

AccessibilityService 不会自动启用，需要手动在系统设置中打开，或通过 adb 命令开启。

### 方式 A：通过系统设置（推荐首次使用）

1. 打开设备的 **设置 → 无障碍 → 已安装的服务**（路径可能因厂商而异）
2. 找到 **PicMe Accessibility Service**
3. 打开开关
4. 在弹出的权限确认对话框中点击 **允许**

### 方式 B：通过 adb（适合自动化脚本）

```bash
adb shell settings put secure enabled_accessibility_services com.mamba.picme/.testing.accessibility.PicMeAccessibilityService
```

验证是否启用：

```bash
adb shell settings get secure enabled_accessibility_services
```

应包含：

```
com.mamba.picme/.testing.accessibility.PicMeAccessibilityService
```

> 注意：部分国产 ROM 可能限制通过 adb 开启无障碍服务，若命令执行后未生效，请使用方式 A。

---

## 4. 启动 PicMe 应用

```bash
adb shell am start -n com.mamba.picme/.MainActivity
```

等待应用进入主界面（相册网格）。

---

## 5. 建立 PC ↔ 设备的通信通道

AccessibilityService 在设备本地监听 `127.0.0.1:27183`。通过 adb forward 将该端口映射到开发机：

```bash
adb forward tcp:27183 tcp:27183
```

验证端口是否已转发：

```bash
adb forward --list
```

应输出类似：

```
<device-serial> tcp:27183 tcp:27183
```

> 每次重新插拔设备或重启 adb server 后，都需要重新执行 `adb forward`。

---

## 6. 使用 ui_driver.py 手动操作

`scripts/ui_driver.py` 是 PC 端的 Python 客户端，通过 JSON-RPC 与设备上的 AccessibilityService 通信。

### 6.1 查看帮助

```bash
python3 scripts/ui_driver.py --help
```

### 6.2 获取当前界面结构（dump）

```bash
python3 scripts/ui_driver.py dump
```

输出示例（节选）：

```text
[android.widget.FrameLayout] android.widget.FrameLayout bounds=(0,0,1200,2670)
  [android.view.View] android.view.View bounds=(0,0,1200,2670)
    ...
    [android.view.View] android.view.View clickable, bounds=(719,166,875,322)
      [android.view.View] 搜索照片 bounds=(758,205,836,283)
      [android.widget.Button] android.widget.Button bounds=(732,179,862,309)
```

每一行格式：

```text
[ClassName] text_or_contentDescription bounds=(left,top,right,bottom) [flags]
```

### 6.3 点击元素

支持通过 `text`、`content_description` 或 `bounds` 定位元素。

```bash
# 通过 contentDescription 点击搜索按钮
python3 scripts/ui_driver.py click --content-description "搜索照片"

# 通过文本点击（会匹配包含该文本的节点）
python3 scripts/ui_driver.py click --text "2026-07-02"

# 通过 bounds 点击（需要传入 JSON）
python3 scripts/ui_driver.py click --bounds '{"left":758,"top":205,"right":836,"bottom":283}'
```

若目标节点本身不可点击，客户端会自动向上查找到可点击的父节点再执行点击。

### 6.4 长按

```bash
python3 scripts/ui_driver.py long-click --content-description "搜索照片"
```

### 6.5 输入文本

```bash
# 先点击搜索进入搜索模式
python3 scripts/ui_driver.py click --content-description "搜索照片"

# 在搜索框输入文本
python3 scripts/ui_driver.py input --text "搜索照片，如 猫、去年夏天、上海..." --value "猫"
```

> `input` 命令会查找匹配文本的 `EditText` 节点，聚焦后通过 AccessibilityService 的 `ACTION_SET_TEXT` 设置文本。

### 6.6 返回键

```bash
python3 scripts/ui_driver.py back
```

### 6.7 滑动

```bash
python3 scripts/ui_driver.py swipe \
  --start-x 600 --start-y 2000 \
  --end-x 600 --end-y 500 \
  --duration 300
```

### 6.8 查找节点

```bash
python3 scripts/ui_driver.py find --content-description "关闭搜索"
```

输出为 JSON 数组，包含匹配节点的完整信息。

---

## 7. 运行集成验证脚本

`scripts/verify_ui_driver.py` 是一个端到端验证脚本，会自动：

1. 如果当前在搜索模式，先按返回键回到主界面
2. dump 主界面
3. 点击「搜索照片」
4. dump 搜索界面
5. 验证是否出现「关闭搜索」，确认进入搜索模式

执行：

```bash
python3 scripts/verify_ui_driver.py
```

成功输出：

```text
✅ Integration test passed: search mode entered
```

失败会输出：

```text
❌ Integration test failed: search mode not detected after click
```

并返回非零退出码。

---

## 8. 完整手动测试流程示例

```bash
# 1. 安装 debug APK
./gradlew :app:installDebug --no-daemon

# 2. 启用 AccessibilityService
adb shell settings put secure enabled_accessibility_services com.mamba.picme/.testing.accessibility.PicMeAccessibilityService

# 3. 启动应用
adb shell am start -n com.mamba.picme/.MainActivity

# 4. 等待应用启动
sleep 3

# 5. 建立端口转发
adb forward tcp:27183 tcp:27183

# 6. 查看界面
python3 scripts/ui_driver.py dump

# 7. 执行集成验证
python3 scripts/verify_ui_driver.py
```

---

## 9. 常见问题

### 9.1 `ConnectionRefusedError` 或 `RPC error`

可能原因：

- AccessibilityService 未启用 → 按第 3 步重新启用
- adb forward 未建立 → 重新执行 `adb forward tcp:27183 tcp:27183`
- 应用被杀死 → 重新启动 PicMe
- 服务尚未初始化完成 → 等待 2-3 秒后重试

### 9.2 点击成功但界面没有反应

- 检查目标节点是否真实可交互（有些 View 只是装饰性容器）
- 尝试使用更上层的 `contentDescription` 定位，或直接使用 `bounds`
- 某些自定义 View 可能不响应 Accessibility 点击，可改用 `swipe` 或 `input`

### 9.3 dump 输出为空或只有 FrameLayout

- 应用可能不在前台 → 重新 `adb shell am start -n com.mamba.picme/.MainActivity`
- 当前窗口可能是系统弹窗/悬浮窗 → 关闭弹窗后再 dump

### 9.4 通过 adb 启用 AccessibilityService 后未生效

部分厂商 ROM 会拦截该设置，请改用系统设置界面手动开启。

### 9.5 脚本执行时提示找不到 `ui_driver` 模块

确保在工程根目录执行脚本，或设置正确的 `PYTHONPATH`：

```bash
export PYTHONPATH="$(pwd)/scripts:$PYTHONPATH"
python3 scripts/verify_ui_driver.py
```

---

## 10. 安全与隐私提示

- AccessibilityService 拥有较高的系统权限，仅用于 Debug 构建的自动化测试
- Release 构建不会包含该服务
- 不要在生产环境或他人设备上随意开启此服务

---

## 11. 下一步

验证通过后，你可以：

- 基于 `scripts/ui_driver.py` 编写更复杂的自动化测试用例
- 将 `dump` 结果直接喂给 LLM，让 AI 基于结构化文本描述进行 UI 理解和操作决策
- 探索 Phase 2：在 App 进程内直接消费 UI 结构化数据并本地调用大模型（无需经过 PC）
