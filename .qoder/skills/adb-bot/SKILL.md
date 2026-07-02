---
name: adb-bot
description: |
  通用 Android adb 调试与设备控制参考。用于检查设备连接、启动/停止应用、过滤日志、截屏、
  拉取文件、查看性能指标等基础 adb 操作。不处理 PicMe 专属测试命令或 UI 自动化。
version: 2.0.0
created: 2026-05-03
updated: 2026-07-02
maintainer: [RD] 全栈工程师
tags:
  - adb
  - android
  - debug
  - device
---

# PicMe ADB 基础参考

> **定位**：通用 Android adb 调试与设备控制。
> **触发时机**：需要连接设备、启动/停止应用、收集日志、截屏、查看性能指标等基础 adb 操作时启用。
> **PicMe 专属命令**：使用 [agent-test-expert](/agent-test-expert)。
> **UI 自动化**：使用 [accessibility-ui-driver](/accessibility-ui-driver)。

---

## 快速开始

```bash
# 检查设备
adb devices

# 启动 PicMe
adb shell am start -n com.mamba.picme/.MainActivity

# 强制停止
adb shell am force-stop com.mamba.picme

# 过滤 PicMe 日志
adb logcat -s PicMe:* *:S

# 截屏（仅用于最终视觉验证，UI 状态优先用 accessibility dump）
adb shell screencap -p /sdcard/screen.png
adb pull /sdcard/screen.png /tmp/screen.png
```

---

## 设备连接

```bash
adb devices                    # 列出已连接设备
adb -s <serial> shell ...      # 指定设备执行命令
adb kill-server && adb start-server   # 重启 adb 服务
```

---

## 应用生命周期

```bash
adb shell pidof com.mamba.picme                       # 检查应用是否运行
adb shell am start -n com.mamba.picme/.MainActivity   # 启动应用
adb shell am force-stop com.mamba.picme               # 强制停止
adb shell pm clear com.mamba.picme                    # 清除应用数据
```

---

## 日志

```bash
# 实时过滤 PicMe 标签
adb logcat -s PicMe:* *:S

# 清除后重新捕获
adb logcat -c
adb shell am start -n com.mamba.picme/.MainActivity
adb logcat -s PicMe:*

# 导出到文件并搜索异常
adb logcat -d > /tmp/logcat.txt
grep -iE "error|exception|fatal|failed" /tmp/logcat.txt
```

---

## UI 交互（基础 fallback）

> **优先使用 [accessibility-ui-driver](/accessibility-ui-driver)**，以下命令仅在无法启用 AccessibilityService 时使用。

```bash
# 点击坐标
adb shell input tap 500 1500

# 滑动
adb shell input swipe 300 1000 300 500

# 按键
adb shell input keyevent KEYCODE_BACK
adb shell input keyevent KEYCODE_CAMERA
adb shell input keyevent KEYCODE_VOLUME_UP
```

---

## 截屏与录屏

```bash
# 截屏
adb shell screencap -p /sdcard/screen.png
adb pull /sdcard/screen.png /tmp/screen.png

# 录屏（默认 180 秒）
adb shell screenrecord /sdcard/video.mp4
adb pull /sdcard/video.mp4 /tmp/video.mp4
```

---

## 文件与数据

```bash
# 拉取 SharedPreferences
adb shell run-as com.mamba.picme cat /data/data/com.mamba.picme/shared_prefs/*.xml

# 拉取数据库
adb shell run-as com.mamba.picme cat /data/data/com.mamba.picme/databases/*.db > /tmp/app.db

# 推送测试资源
adb push test_image.jpg /sdcard/Pictures/
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Pictures/test_image.jpg

# 拉取 accessibility UI dump（备用）
adb shell uiautomator dump /sdcard/window_dump.xml
adb pull /sdcard/window_dump.xml
```

---

## 性能

```bash
# FPS / 帧渲染
adb shell dumpsys gfxinfo com.mamba.picme | grep -iE "jank|frame|percentile"

# 内存
adb shell dumpsys meminfo com.mamba.picme

# CPU
adb shell top -p $(adb shell pidof com.mamba.picme) -n 1
```

---

## Activity / Window

```bash
# 当前顶部 Activity
adb shell dumpsys activity top | grep -i "ACTIVITY"

# 窗口信息
adb shell dumpsys window displays
```

---

## 故障排除

| 症状 | 检查/修复 |
|------|----------|
| 设备未识别 | `adb devices`；重新插拔 USB；`adb kill-server && adb start-server` |
| 日志无输出 | 确认 PID 正确；先用 `adb logcat -d` 全量查看 |
| 截屏失败 | 检查设备连接和 `/sdcard/` 可写性 |
| 文件 run-as 失败 | 应用必须是 debug 包；或先 `adb shell` 再 `su` |

---

## 相关 Skill

- [agent-test-expert](/agent-test-expert) — PicMe 专属 JSON 测试命令
- [accessibility-ui-driver](/accessibility-ui-driver) — 结构化 UI dump 与精准交互
- [auto-dev-loop](/auto-dev-loop) — 完整开发自循环
- [ui-automation-expert](/ui-automation-expert) — UI 自动化策略

## 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| 2.0.0 | 2026-07-02 | 移除 PicMe 专属 TEST_COMMAND 内容，改为通用 adb 参考；指向 agent-test-expert 和 accessibility-ui-driver |
| 1.1.0 | 2026-05-31 | 统一格式，添加定位块 |
| 1.0.0 | 2026-05-03 | 初始版本 |
