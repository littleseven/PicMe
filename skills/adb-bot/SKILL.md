---
name: adb-bot
description: Use when connecting Android devices, controlling app lifecycle, filtering logs, taking screenshots, pulling files, or checking performance metrics via adb
version: 2.0.0
created: 2026-05-03
updated: 2026-08-03
maintainer: [RD] 全栈工程师
tags:
  - adb
  - android
  - debug
  - device
---


# PoLang ADB 参考

> **定位**：通过 adb 命令自动化控制 PoLang 相机应用并调试 Android 设备。
> **触发时机**：用户需要 adb 自动化控制、截屏分析、日志过滤或性能监控时自动启用。


综合 adb 控制与调试能力，支持 PoLang 相机应用的自动化测试和设备状态诊断。

## 快速开始

### 1. 启动应用并拍照
```bash
adb shell am start -n com.mamba.picme/.MainActivity
sleep 3
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action "capture"
```

### 2. 截屏并拉取
```bash
adb shell screencap -p /sdcard/screen.png
adb pull /sdcard/screen.png /tmp/screen.png
```

### 3. 过滤日志
```bash
adb logcat -s PoLang:* *:S
```

---

## 一、应用控制（Control）

基于 CameraTestCommand 架构，通过 adb broadcast 远程控制相机界面。

### 前置条件
- 设备已连接：`adb devices`
- 应用已安装并运行在前台

### 基础操作

| 命令 | adb 命令 | 说明 |
|------|----------|------|
| 拍照 | `--es action "capture"` | 触发快门拍照 |
| 切换摄像头 | `--es action "flip_camera"` | 前/后置摄像头切换 |
| 获取状态 | `--es action "get_state"` | 获取当前相机状态 |

### 模式设置
```bash
# 切换拍照模式 (photo/video/pro)
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action "set_mode" --es mode "video"

# 切换场景 (none/night/moon)
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action "set_scene" --es scene "night"

# 切换画幅 (4_3/16_9/full)
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action "set_ratio" --es ratio "16_9"
```

### 美颜设置
```bash
# 设置美颜参数 (0-100)
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND \
    --es action "set_beauty" \
    --ei smooth 80 \
    --ei whiten 60 \
    --ei slim_face 50 \
    --ei big_eye 30
```

### 滤镜与风格
```bash
# 设置滤镜
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action "set_filter" --es filter "leica_classic"

# 设置风格滤镜
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action "set_style" --es style "toon"
```

### 相机参数
```bash
# 设置曝光补偿 (-2 ~ 2)
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action "set_exposure" --ei exposure 1

# 设置缩放
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action "set_zoom" --ef zoom 2.0
```

### 面板控制
```bash
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action "toggle_beauty"
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action "toggle_filter"
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action "toggle_settings"
```

### 完整拍照流程示例
```bash
#!/bin/bash
# 启动应用
adb shell am start -n com.mamba.picme/.MainActivity
sleep 3

# 确保后置摄像头
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action "flip_camera"
sleep 1

# 设置美颜
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND \
    --es action "set_beauty" --ei smooth 80 --ei whiten 60
sleep 0.5

# 拍照
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action "capture"
```

### Gallery / MediaPager 命令（2026-05 新增）

| 命令 | adb 命令 | 说明 |
|------|----------|------|
| 进入相册 | `--es action "enter_gallery"` | 从相机页导航到相册 |
| 打开照片 | `--es action "open_photo" --ei index 0` | 打开指定索引的图片 |
| 进入编辑 | `--es action "start_edit"` | 进入图片编辑模式 |
| 保存编辑 | `--es action "save_edit"` | 保存编辑结果 |
| 取消编辑 | `--es action "cancel_edit"` | 取消编辑 |
| 设置磨皮 | `--es action "set_smooth" --ei value 50` | 编辑模式磨皮 0-100 |
| 设置美白 | `--es action "set_whiten" --ei value 30` | 编辑模式美白 0-100 |
| 设置滤镜 | `--es action "set_edit_filter" --es filter "leica_classic"` | 编辑模式色调滤镜 |
| 触发 OCR | `--es action "start_ocr"` | 识别当前图片文字 |
| 关闭 OCR | `--es action "dismiss_ocr"` | 关闭 OCR 浮层 |
| 切换关键点 | `--es action "toggle_landmark"` | 人脸关键点覆盖层 |
| 切换信息 | `--es action "toggle_info"` | 信息浮层显示 |
| 删除照片 | `--es action "delete_photo"` | 删除当前照片 |
| 分享照片 | `--es action "share_photo"` | 分享当前照片 |

### 完整相册编辑流程示例
```bash
#!/bin/bash
# 启动应用
adb shell am start -n com.mamba.picme/.MainActivity
sleep 3

# 进入相册
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action "enter_gallery"
sleep 2

# 打开第一张照片
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action "open_photo" --ei index 0
sleep 1

# 进入编辑模式
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action "start_edit"
sleep 2

# 设置磨皮 50、美白 30
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action "set_smooth" --ei value 50
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action "set_whiten" --ei value 30
sleep 2

# 保存编辑
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action "save_edit"
```

### 命令验证
```bash
# 清除日志后执行命令
adb logcat -c
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action "capture"
sleep 1

# 查看 PoLang:CameraTest / PoLang:GalleryTest 标签日志
adb logcat -d | grep -E "PoLang:(CameraTest|GalleryTest)"
```

预期输出：
```
PoLang:CameraTest: Broadcast received: capture
PoLang:CameraTest: Dispatching command: Capture
PoLang:CameraTest: Command emitted successfully: Capture
PoLang:CameraTest: Executing command: 拍照
```

---

## 二、设备调试（Debug）

### 截屏分析

#### 自动截屏并查看
```bash
adb shell screencap -p /sdcard/screen.png
adb pull /sdcard/screen.png /tmp/screen.png
```

#### 连续截屏监控
```bash
for i in 1 2 3; do
    adb shell screencap -p /sdcard/screen_$i.png
    adb pull /sdcard/screen_$i.png /tmp/screen_$i.png
    sleep 2
done
```

### 日志分析

#### 实时过滤日志
```bash
# 过滤特定标签
adb logcat -s PoLang:* *:S

# 过滤多个标签
adb logcat -s PoLang:BeautyRenderer:FaceMakeupPass:* *:S

# 清除后重新捕获
adb logcat -c
adb shell am force-stop com.mamba.picme
adb shell am start -n com.mamba.picme/.MainActivity
adb logcat -s PoLang:*
```

#### 导出日志到文件
```bash
adb logcat -d > /tmp/logcat.txt
grep -i "error\|exception\|failed" /tmp/logcat.txt
```

### 应用状态检查

```bash
# 检查应用是否运行
adb shell pidof com.mamba.picme

# 强制重启应用
adb shell am force-stop com.mamba.picme
adb shell am start -n com.mamba.picme/.MainActivity

# 检查 GPU/渲染状态
adb shell dumpsys gfxinfo com.mamba.picme
```

### 文件操作

```bash
# 拉取 SharedPreferences
adb shell run-as com.mamba.picme cat /data/data/com.mamba.picme/shared_prefs/*.xml

# 拉取数据库
adb shell run-as com.mamba.picme cat /data/data/com.mamba.picme/databases/*.db > /tmp/app.db

# 推送测试资源
adb push test_image.jpg /sdcard/Pictures/
```

### 渲染问题专项调试

```bash
# 检查 OpenGL 错误
adb logcat -d | grep -i "gl_error\|shader\|compile\|link"

# 检查 Shader 编译状态
adb logcat -d | grep -i "shader.*compiled\|program.*linked"

# 验证纹理加载
adb logcat -d | grep -i "texture\|bitmap\|load"
```

### 性能监控

```bash
# FPS 监控
adb shell dumpsys gfxinfo com.mamba.picme | grep -i "jank\|frame"

# 内存使用
adb shell dumpsys meminfo com.mamba.picme
```

---

## 三、自动化测试流程

### 完整调试流程（控制 + 调试结合）
```bash
#!/bin/bash
# 1. 确保应用运行
if ! adb shell pidof com.mamba.picme > /dev/null; then
    adb shell am start -n com.mamba.picme/.MainActivity
    sleep 3
fi

# 2. 清除日志
adb logcat -c

# 3. 截屏（操作前）
adb shell screencap -p /sdcard/before.png
adb pull /sdcard/before.png /tmp/before.png

# 4. 执行相机操作
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action "set_filter" --es filter "leica_classic"
sleep 0.5
adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action "capture"

# 5. 等待渲染完成
sleep 1

# 6. 截屏（操作后）
adb shell screencap -p /sdcard/after.png
adb pull /sdcard/after.png /tmp/after.png

# 7. 收集日志
adb logcat -d > /tmp/logcat.txt
```

---

## 四、常用命令速查

| 命令 | 用途 |
|------|------|
| `adb devices` | 检查设备连接 |
| `adb shell screencap -p /sdcard/screen.png` | 截屏 |
| `adb pull /sdcard/screen.png /tmp/` | 拉取文件 |
| `adb logcat -c` | 清除日志 |
| `adb logcat -s TAG:*` | 过滤日志 |
| `adb shell input tap x y` | 模拟点击 |
| `adb shell input swipe x1 y1 x2 y2` | 模拟滑动 |
| `adb shell am start -n pkg/.Activity` | 启动 Activity |
| `adb shell am force-stop pkg` | 强制停止应用 |
| `adb shell am broadcast -a com.mamba.picme.TEST_COMMAND --es action "capture"` | 拍照 |
| `adb shell pidof pkg` | 检查进程是否存在 |
| `adb shell dumpsys gfxinfo pkg` | GPU 渲染信息 |
| `adb shell dumpsys meminfo pkg` | 内存信息 |

---

## 五、故障排除

### 命令无响应
1. 确认应用在前台运行：`adb shell pidof com.mamba.picme`
2. 清除日志后重试：`adb logcat -c && adb shell am broadcast ... && sleep 1 && adb logcat -d`
3. 检查 `adb logcat | grep PoLang:(CameraTest|GalleryTest)` 是否有接收日志
4. 如果显示 `Background execution not allowed`，说明静态接收器被限制——**GalleryScreen 和 CameraScreen 各自通过 DisposableEffect 动态注册接收器，导航切换时必须确保目标 Screen 已挂载**
5. **常见陷阱**：修改代码后只运行 `compileDebugKotlin` 不会重新生成 APK，必须运行 `assembleDebug`
6. **常见陷阱**：`GalleryScreen` 的 `LaunchedEffect` 闭包不能读取 `remember` 局部变量（会被捕获旧值），命令处理时须用 `viewModel.allMedia.value` 读取最新状态

### 状态未更新
- 状态快照在 CameraScreen 的 LaunchedEffect 中更新
- 首次进入相机界面可能需要等待状态初始化

### 截屏失败
- 检查设备是否连接：`adb devices`
- 检查存储权限：`adb shell ls /sdcard/`

---

## 六、技术说明

- **广播 Action**: `com.mamba.picme.TEST_COMMAND`
- **动态注册**: CameraScreen 通过 DisposableEffect 注册 BroadcastReceiver
- **命令分发**: CameraTestCommandDispatcher 使用 SharedFlow 分发命令
- **状态更新**: LaunchedEffect 定期更新 CameraTestStateSnapshot

---

## 相关文件

- [commands.md](commands.md) - 完整相机控制命令列表
- [ui-driver](/ui-driver) — UI Driver
- [image-quality-checker](/image-quality-checker) — 截图质量分析

## 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| 1.1.0 | 2026-05-31 | 统一格式规范，添加定位块 |
| 1.0.0 | 2026-05-03 | 初始版本 |
