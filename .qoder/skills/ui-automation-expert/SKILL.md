---
name: ui-automation-expert
description: |
  PicMe UI 自动化测试专家。提供基于 accessibility 节点、坐标系统和 Compose Test 的精准 UI 交互方案，替代脆弱的图像识别点击。用于自动化测试、功能验证和 QA 验收。
version: 1.0.0
created: 2026-05-31
updated: 2026-05-31
maintainer: [RD] 全栈工程师 + [QA] 质量专家
tags:
  - ui
  - automation
  - testing
  - adb
  - compose
  - accessibility
---


# UI 自动化测试专家

> **定位**：PicMe UI 自动化测试专家，提供基于 accessibility 节点、坐标系统和 Compose Test 的精准 UI 交互方案。
> **触发时机**：用户需要 UI 自动化测试、精准点击方案或替代图像识别点击时自动启用。


> **核心原则**：使用 accessibility 节点或 Compose Test 进行精准交互，禁止基于图像识别的模拟点击。

## 为什么图像识别点击不可靠

| 问题 | 说明 |
|------|------|
| 分辨率依赖 | 不同设备截图尺寸不同，匹配失败 |
| 主题敏感 | 深色模式、动态颜色导致像素变化 |
| 动画干扰 | 过渡动画期间截图时机难以把握 |
| 位置漂移 | 不同语言、字体大小导致按钮位置变化 |
| 维护成本高 | UI 微调就需要重新截图 |

## 推荐方案（按优先级）

### 方案 1: Compose UI Test（首选，适用于 Compose 页面）

```kotlin
// 使用语义标签精准定位
composeTestRule.onNodeWithTag("exposure_slider")
    .performTouchInput { swipeRight() }

composeTestRule.onNodeWithContentDescription("切换摄像头")
    .performClick()

// 验证状态
composeTestRule.onNodeWithText("专业模式")
    .assertIsDisplayed()
```

**在代码中添加 testTag**：
```kotlin
Slider(
    modifier = Modifier.testTag("exposure_slider"),
    value = exposureValue,
    onValueChange = { }
)
```

### 方案 2: PicMe Accessibility UI Driver（推荐，运行时混合页面）

**REQUIRED SUB-SKILL:** 使用 [accessibility-ui-driver](/accessibility-ui-driver) 获取完整 API 和设置步骤。

适用于已运行 PicMe 时的精准交互，无需截图识别：

```bash
# 启用服务并建立端口转发后
python3 scripts/ui_driver.py click --content-description "搜索照片"
python3 scripts/ui_driver.py input --bounds '{"left":325,"top":166,"right":1135,"bottom":322}' --value "猫"
python3 scripts/ui_driver.py dump
```

Python 示例：

```python
import sys
sys.path.insert(0, "scripts")
from ui_driver import UiDriverClient

with UiDriverClient() as client:
    client.click(content_description="搜索照片")
    edit = client.find_node(
        lambda n: n.class_name == "android.widget.EditText"
                  and any("搜索照片" in (c.text or "") for c in n.children)
    )
    if edit:
        client.input_text("猫", bounds=edit.bounds)
```

**优势**：
- 直接读取 accessibility 节点（text / contentDescription / className / bounds）
- 比 `uiautomator dump` 更稳定，且已封装为 JSON-RPC 客户端
- 不依赖截图，token 消耗远低于图像识别

### 方案 3: uiautomator dump（备用）

通过 `adb shell uiautomator dump` 获取页面 XML：

```bash
adb shell uiautomator dump /sdcard/window_dump.xml
adb pull /sdcard/window_dump.xml
```

仅在没有启用 PicMeAccessibilityService 时使用。

### 方案 4: 坐标系统（基于比例，非绝对像素）

当必须使用时，使用屏幕比例而非绝对坐标：

```bash
SCREEN=$(adb shell wm size)
WIDTH=$(echo $SCREEN | grep -o '[0-9]*' | head -1)
HEIGHT=$(echo $SCREEN | grep -o '[0-9]*' | tail -1)
X=$((WIDTH * 50 / 100))
Y=$((HEIGHT * 80 / 100))
adb shell input tap $X $Y
```

### 方案 5: Espresso Test（适用于传统 View）

```kotlin
onView(withId(R.id.shutter_button))
    .perform(click())

onView(withText("确认"))
    .check(matches(isDisplayed()))
```

## 推荐触发方式

按优先级排列：

```bash
# ✅ 首选：通过 AgentTestBroadcastReceiver 发送 JSON 命令
# 复用与 LLM 相同的 AgentCommand 解析路径，统一、可靠、可扩展
adb shell "am broadcast -n com.mamba.picme/.testing.agent.bridge.AgentTestBroadcastReceiver -a com.mamba.picme.AGENT_TEST --es json '{\"method\":\"capture\",\"params\":{}}'"
adb shell "am broadcast -n com.mamba.picme/.testing.agent.bridge.AgentTestBroadcastReceiver -a com.mamba.picme.AGENT_TEST --es json '{\"method\":\"navigate_to\",\"params\":{\"destination\":\"gallery\"}}'"

# ✅ 次选：通过 ui_driver.py 进行 accessibility 节点级交互
python3 scripts/ui_driver.py click --content-description "快门"
python3 scripts/ui_driver.py dump
python3 scripts/ui_driver.py back

# ✅ 通过 am start 启动特定页面
adb shell am start -n com.mamba.picme/.MainActivity

# ✅ 通过 input keyevent 模拟硬件按键
adb shell input keyevent KEYCODE_CAMERA
adb shell input keyevent KEYCODE_VOLUME_UP

# ❌ 避免：基于坐标的盲目点击
adb shell input tap 500 1500

# ❌ 避免：基于图像识别的点击
# 任何需要截图比对再点击的方式

# ❌ 避免：旧版 TEST_COMMAND 广播（已废弃）
# adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action "capture"
```

## 测试数据准备

对于需要特定状态（如人脸在画面中）的测试：

1. **使用测试图片**：通过 adb 推送测试图片到相册
   ```bash
   adb push test_face.jpg /sdcard/DCIM/
   adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/DCIM/test_face.jpg
   ```

2. **使用 Mock 数据**：在 Debug 构建中注入模拟的人脸检测数据

3. **使用视频循环**：推送包含人脸的视频作为相机输入（需特定设备支持）

## 验证策略

| 验证目标 | 推荐方法 |
|---------|---------|
| 页面是否打开 | `adb shell dumpsys activity top` 检查 Activity 栈 |
| 组件是否显示 | Compose Test `assertIsDisplayed()` 或 `ui_driver.py dump` |
| 文本/按钮是否存在 | `python3 scripts/ui_driver.py find --content-description "..."` |
| 状态是否更新 | 日志过滤 `adb logcat -s PicMe:*` |
| 最终视觉质量 | 截图对比，仅用于最终验收，不用于交互触发 |

## 与 image-quality-checker 的协作

- **ui-automation-expert**：负责触发交互、验证功能逻辑
- **image-quality-checker**：负责最终截图验收、检查渲染质量

**正确流程**：
```
ui-automation-expert 触发拍照 → 验证日志输出正确 → image-quality-checker 截图检查画面质量
```

## 红线规则

**禁止以下行为**：
1. 基于截图像素匹配来定位点击位置
2. 使用绝对坐标进行点击（必须使用比例或 accessibility 节点）
3. 在动画过渡期间执行点击操作
4. 不验证操作结果就继续下一步

## 相关文件

- [accessibility-ui-driver](/accessibility-ui-driver) — PicMe Accessibility UI Driver 使用指南
- [adb-bot](/adb-bot) — adb 设备控制
- [image-quality-checker](/image-quality-checker) — 截图质量分析
- [layout-inspector-expert](/layout-inspector-expert) — UI 布局诊断

## 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| 1.1.0 | 2026-07-02 | 新增 PicMe Accessibility UI Driver 作为运行时混合页面首选方案 |
| 1.0.0 | 2026-05-31 | 初始版本 |
