---
name: dev-loop
description: Use when running the full PoLang development self-heal loop from code check through install, device verification, and report generation
version: 2.2.0
created: 2026-05-03
updated: 2026-08-03
maintainer: [RD] 全栈工程师
tags:
  - android
  - build
  - test
  - automation
  - ci
---


# Dev Loop

> **定位**：PoLang 开发自循环自动化，一键完成编译到报告完整闭环。
> **触发时机**：用户需要快速验证改动、执行完整开发闭环或 CI 检查时自动启用。


## 设计目标

消除当前 AI Agent 工作流中**编译后需人工干预**的断点：

```
修改前: 代码修改 → ./gradlew assembleDebug → [人工] adb install → [人工] 打开应用 → [人工] 验证
修改后: 代码修改 → ./scripts/auto-dev-loop.sh → 全自动闭环（含报告）
```

## 快速开始

### 标准自循环（推荐）

代码修改后执行：

```bash
# 在项目根目录执行
./scripts/auto-dev-loop.sh
```

自动完成：
1. **代码检查** — ktlint + detekt + JVM unit tests
2. **编译** — `./gradlew :androidApp:assembleDebug`
3. **安装** — 自动 `adb install -r`
4. **设备验证** — 启动应用 + 截屏 + 执行 JSON 命令（通过 AgentTestBroadcastReceiver）+ 收集日志
5. **报告生成** — Markdown 格式报告 + 所有日志/截图归档

### 快速模式（仅编译+安装+启动）

```bash
./scripts/auto-dev-loop.sh --quick
```

### 纯代码检查（无设备）

```bash
./scripts/auto-dev-loop.sh --no-install
```

### 带拍照质量分析

```bash
./scripts/auto-dev-loop.sh --capture
```

### 回归测试（P0 用例）

```bash
./scripts/regression-test.sh           # 全部 P0 用例
./scripts/regression-test.sh --camera  # 仅相机模块
./scripts/regression-test.sh --beauty  # 仅美颜模块
```

## 工作流集成

### 在 AI Agent 工作流中使用

**场景1: 代码修改后的标准验证**
```
1. RD 完成代码修改
2. 执行: ./scripts/auto-dev-loop.sh
3. 读取报告: scripts/auto_test_output/<timestamp>/report.md
4. 如果有失败 → 自动修复 → 重新执行
5. 如果全部通过 → 进入 CR/QA 环节
```

**场景2: 修复 Bug 后的定向回归**
```
1. 修复美颜相关 Bug
2. 执行: ./scripts/regression-test.sh --beauty
3. 验证美颜滑杆、滤镜切换是否正常
```

**场景3: PR 提交前的完整验证**
```
1. 执行: ./scripts/ai-gate.sh（代码级检查）
2. 执行: ./scripts/auto-dev-loop.sh（设备级验证）
3. 执行: ./scripts/regression-test.sh（端到端回归）
4. 全部通过 → 提交代码
```

## 输出目录结构

```
scripts/auto_test_output/
└── 20260509_143022/                    # 时间戳目录
    ├── report.md                       # 汇总报告
    ├── ktlint.log                      # 格式检查日志
    ├── detekt.log                      # 静态分析日志
    ├── unit_test.log                   # 单元测试日志
    ├── build.log                       # 编译日志
    ├── install.log                     # 安装日志
    ├── screen_startup.png              # 启动截屏
    ├── screen_after_capture.png        # 拍照后截屏
    ├── logcat_picme.txt                # PoLang 标签日志
    └── instrumented_test.log           # Instrumented test 日志
```

## 脚本参数对照

| 脚本 | 参数 | 说明 |
|------|------|------|
| `auto-dev-loop.sh` | `--no-install` | 跳过设备安装 |
| `auto-dev-loop.sh` | `--no-test` | 跳过设备端测试 |
| `auto-dev-loop.sh` | `--capture` | 自动拍照并分析质量 |
| `auto-dev-loop.sh` | `--quick` | 快速模式（仅编译+安装+截屏） |
| `regression-test.sh` | `--camera` | 仅执行相机测试 |
| `regression-test.sh` | `--gallery` | 仅执行相册测试 |
| `regression-test.sh` | `--beauty` | 仅执行美颜测试 |
| `regression-test.sh` | `--ci` | CI 模式（失败快速退出） |

## 故障排除

### 设备未连接
```
[WARN] 未检测到连接的设备，跳过设备端验证
```
**解决**: 连接设备后重试，或添加 `--no-install` 仅做代码检查。

### 安装失败（签名冲突）
```
尝试卸载后重装...
```
脚本会自动尝试卸载后重装，无需人工干预。

### Instrumented Test 无设备任务
```
[WARN] Instrumented Tests 跳过（无设备或无测试任务）
```
如果项目未配置 `connectedDebugAndroidTest` 任务，此警告可忽略。

### 截屏坐标不准确（Gallery 测试）
`regression-test.sh` 中的相册入口坐标基于常见分辨率计算：
```bash
local tap_x=$((w * 75 / 100))
local tap_y=$((h * 95 / 100))
```
如果 UI 布局变化，需更新坐标。

## 扩展指南

### 添加新的回归测试用例

在 `regression-test.sh` 中添加新函数：

```bash
# ============================================
# TC-NEW-01: 新功能描述
# ============================================
tc_new_01_feature() {
    print_test_header "TC-NEW-01: 新功能描述"

    # 执行测试步骤（JSON 命令通过 AgentTestBroadcastReceiver）
    adb shell "am broadcast -n com.mamba.picme/.testing.agent.bridge.AgentTestBroadcastReceiver -a com.mamba.picme.AGENT_TEST --es json '{\"method\":\"xxx\",\"params\":{}}'"
    sleep 1
    screenshot "tc_new_01"

    # 验证结果
    if [ 条件满足 ]; then
        log_pass "测试通过"
    else
        log_fail "测试失败"
    fi
}
```

然后在主流程中注册：
```bash
if [ "$TEST_ALL" = true ] || [ "$TEST_NEWMODULE" = true ]; then
    tc_new_01_feature
fi
```

### 集成到 CI/CD

在 GitHub Actions / GitLab CI 中添加步骤：

```yaml
- name: Auto Dev Loop
  run: |
    ./scripts/auto-dev-loop.sh --ci
    
- name: Regression Test
  if: success()
  run: |
    ./scripts/regression-test.sh --ci
```

## 相关文件

- `scripts/auto-dev-loop.sh` — Dev Loop
- `scripts/regression-test.sh` — 端到端回归测试（JSON 命令驱动）
- `scripts/ai-gate.sh` — 代码级质量门禁
- `/android-build-debug` — 编译调试参考
- `/adb-bot` — adb 命令参考
- `/ui-driver` — 结构化 UI 自动化（主要测试方法）
- `/image-quality-checker` — 图片质量分析
- `/compose-ui-expert` — UI 验证参考
- `/perf-optimizer` — 性能基线对比
- `/i18n-validator` — 多语言验证
- `/ios-dev-loop` — iOS 闭环对标（simctl / screenshot-diff）

## 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| 1.1.0 | 2026-05-03 | 初始版本 |
