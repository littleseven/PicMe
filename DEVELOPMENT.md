# PicMe 开发指南

> **定位**：面向所有开发者（人类与 AI）的通用开发参考，包含环境配置、构建命令、调试技巧与发布流程。
> **项目根目录**：`~/AndroidStudioProjects/langchain4android`（应用名 PicMe，仓库名 langchain4android）。
> **AI 工具配置**：各 AI 辅助工具的配置位置见 `AI_TOOLS.md`。

---

## 双螺旋演进原则（Spec-Code Co-evolution）

本项目的 **Spec（文档）** 与 **代码** 处于双螺旋演进状态：

- **鼓励深入理解需求并反哺 Spec**：在实现过程中对需求产生的新洞察，应当即时回馈到 Spec 中。Spec 不是冻结的契约，而是随实现共同生长的活文档。
- **唯一红线**：在任何提交点，代码都必须与当前主干分支的 Spec **完全兼容**。代码不能偏离 Spec 而无文档记录。
- **原子性要求**：任何因实现而导致的 Spec 洞察或修正，必须在**同一原子变更（commit/PR）**中更新对应文档。禁止"先改代码、后补文档"的滞后同步。
- **详细工作流**：见 `docs/05-DEVELOPMENT/DEVELOPMENT.md`。

> 简言之：Spec 与代码互相缠绕、共同上升；但每一级螺旋都必须咬合紧密，不能脱节。

---

## 开发环境

### 必需工具
- **Android Studio**: 最新稳定版（Meerkat 2024.3+）
- **JDK**: 17（Android Studio 内置或独立安装）
- **Android SDK**: API 24-36（`compileSdk = 36`，`targetSdk = 35`，`minSdk = 24`）
- **Git**: 版本控制
- **NDK**: r26+（用于 `beauty-engine` / `runtime-core` / `sentencepiece`  native 构建）

### 可选工具
- **scrcpy**: 屏幕镜像 (`brew install scrcpy`)
- **ADB**: Android 调试桥 (`brew install android-platform-tools`)

## 快速开始

```bash
# 克隆项目
git clone git@github.com:littleseven/langchain4android.git
cd langchain4android

# 构建调试版本
./gradlew :app:assembleDebug

# 安装到设备（APK 路径以实际输出为准）
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 查看 PicMe 日志
adb logcat -s "PicMe:*"
```

## 项目构建

### 常用 Gradle 命令

```bash
# 构建调试 APK
./gradlew :app:assembleDebug

# 构建发布 APK
./gradlew :app:assembleRelease

# 构建发布 AAB（Google Play）
./gradlew :app:bundleRelease

# 运行单元测试
./gradlew test

# 运行仪器测试
./gradlew connectedAndroidTest

# 清理构建
./gradlew clean

# 刷新依赖
./gradlew --refresh-dependencies

# Lint 与静态检查
./gradlew lint
./gradlew detekt
./gradlew ktlintCheck
```

### 模块概览

| 模块 | 说明 |
|------|------|
| `:app` | 主应用模块（UI + DI + Data + Features） |
| `:agent-core` | LangChain4j Java 基础库（ChatModel / Tool / AiServices / ChatMemory） |
| `:runtime-core` | Agent Runtime 核心（AgentOrchestrator、编排、本地/远程推理、语音 ASR） |
| `:beauty-api` | 美颜接口契约层 |
| `:beauty-engine` | 美颜引擎（OpenGL ES + EGL 渲染管线） |
| `:sentencepiece` | 本地分词库 JNI 包装 |

### 构建变体
- `debug`: 开发调试版本
- `release`: 发布版本（需要签名配置）
- 通过 `picme.release.plain=true` 可构建不混淆的 release-plain 包

### Kotlin / Java 代码风格
- 使用项目配置的 `.editorconfig`
- 缩进: 4 空格 (Kotlin/Java)
- 缩进: 2 空格 (XML/JSON/MD)
- 提交前建议运行 `./gradlew ktlintFormat`

## 调试工具

### ADB 常用命令

```bash
# 查看设备
adb devices

# 安装 APK
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 查看 PicMe 日志
adb logcat -s PicMe:*

# 只看错误
adb logcat *:E

# 清除日志
adb logcat -c

# 进入 shell
adb shell

# 截图
adb shell screencap /sdcard/screen.png && adb pull /sdcard/screen.png

# 启动耗时测量
adb shell am start -W com.mamba.picme/.MainActivity
```

### 自动化脚本

```bash
# 编译 → 安装 → 启动 → 截屏 → 日志 闭环
./scripts/auto-dev-loop.sh

# 代码质量门禁
./scripts/ai-gate.sh

# 变更影响分析
./scripts/impact-analyzer.sh

# 文档同步检查
./scripts/doc-sync-guardian.sh
```

## IDE 快捷键

### Android Studio (macOS)
- `Cmd + Shift + A`: 查找动作
- `Cmd + O`: 查找类
- `Cmd + Shift + O`: 查找文件
- `Cmd + L`: 跳转到行
- `Cmd + B`: 跳转到定义
- `Option + Cmd + L`: 格式化代码

## 模拟器/真机调试

### 创建模拟器
1. Android Studio → Device Manager
2. Create Device
3. 选择 Pixel 系列
4. 下载系统镜像 (推荐 API 33+，仅 arm64-v8a)

### 真机调试
1. 开启开发者选项（设置 → 关于手机 → 点击版本号 7 次）
2. 开启 USB 调试
3. 连接 USB，允许调试授权

> 注意：本项目仅保留 `arm64-v8a` ABI，x86 / armeabi-v7a 模拟器无法安装。

## 性能分析

### Android Profiler
- CPU: 方法追踪
- Memory: 内存分配
- Network: 网络请求
- Energy: 电量消耗

### 关键性能红线
- 交互响应 < 100ms
- 快门延迟 < 50ms
- 相机预览帧率稳定 30fps
- TAG 全量扫描在后台完成，不阻塞 UI

## 版本发布

### 版本号规则
- `versionCode`: 整数，每次发布递增
- `versionName`: 语义化版本 (如 1.0.4)
- 修改位置：`app/build.gradle.kts` 中 `defaultConfig.versionCode` / `versionName`

### 签名配置
发布签名通过环境变量或 `~/.gradle/gradle.properties` 注入：

```properties
PICME_RELEASE_STORE_FILE=/path/to/keystore
PICME_RELEASE_STORE_PASSWORD=your_password
PICME_RELEASE_KEY_ALIAS=your_alias
PICME_RELEASE_KEY_PASSWORD=your_password
```

或在构建时通过环境变量传入：
```bash
PICME_RELEASE_STORE_FILE=... PICME_RELEASE_STORE_PASSWORD=... \
  ./gradlew :app:assembleRelease
```

### Google Play 发布流程
1. 在 `app/build.gradle.kts` 更新 `versionCode` 与 `versionName`
2. 编写/更新 `docs/01-PRODUCT/RELEASE_NOTES_vX.Y.Z.md`
3. 构建 AAB：`./gradlew :app:bundleRelease`
4. 上传 `app/build/outputs/bundle/release/app-release.aab` 到 Google Play Console
5. 填写对应语种的 Release Note

---

## kimi-cli 快捷入口

```bash
# 进入项目目录并启动 kimi-cli
cd ~/AndroidStudioProjects/langchain4android
kimi-cli chat

# 或使用项目脚本
source scripts/kimi-cli.sh
kbuild    # 构建调试 APK
ktest     # 运行单元测试
klogs     # 查看 PicMe 日志
```

---

*项目路径: ~/AndroidStudioProjects/langchain4android*
