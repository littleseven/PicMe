# MNN 模型卸载测试用例文档

> **文档编号**: TECH-SPEC-MNN-TEST-001
> **关联模块**: `agent-core/src/main/java/com/picme/agent/core/mnn/MnnResourceManager.kt`
> **最后更新**: 2026-06-17

---

## 1. 测试环境准备

### 1.1 硬件要求

- Android 设备（API 24+）
- 已安装 PicMe 调试版 APK
- LLM 模型（qwen3_5_2b）已下载
- ASR 模型（sherpa-mnn-zipformer-zh-en）已下载

### 1.2 ADB 日志准备

```bash
# 清除旧日志
adb logcat -c

# 开启过滤日志（保持终端运行）
adb logcat -s "MnnResourceManager:*" "LocalLlmEngine:*" "SherpaMnnAsr:*" "VoiceCommand:*" "AgentOrchestrator:*" -v time
```

### 1.3 内存监控（可选）

```bash
# 监控进程内存
adb shell dumpsys meminfo com.mamba.picme | grep -E "TOTAL|Java Heap|Native Heap"

# 或持续监控
while true; do
    adb shell dumpsys meminfo com.mamba.picme | grep "TOTAL PSS"
    sleep 5
done
```

---

## 2. 测试用例

### TC-001: 后台自动卸载（核心用例）

**目的**: 验证 App 进入后台后，LLM 和 ASR 按预期时间线卸载

**前置条件**:
- App 已启动，进入相机页
- ASR 初始化成功（`acquireAsr()`）
- LLM 已加载（`acquireLlm()`）

**操作步骤**:

| 步骤 | 操作 | 期望日志 |
|------|------|----------|
| 1 | 打开 PicMe，进入相机页 | `ASR acquired by SherpaMnnAsrEngine, refCount=1` |
| 2 | 说一句话触发语音指令 | `LLM acquired by LocalLlmEngine, refCount=1` |
| 3 | 按 Home 键回到桌面 | `App entered background, scheduling unload` |
| 4 | 等待 30 秒 | `Background timeout, triggering soft trim for all` |
| 5 | 继续等待 30 秒（累计 60s） | `Background force unload timeout, triggering safe unload` |
| 6 | 观察最终状态 | `LLM fully unloaded` + `ASR fully unloaded` |

**验证标准**:
- [ ] 30s 时触发 softTrim（LLM trimMemory + ASR stopStreaming）
- [ ] 60s 时触发 safeUnload（LLM performUnload + ASR performUnload）
- [ ] 内存下降（Native Heap 减少 1-2GB）

**通过标准**: 所有期望日志按顺序出现，无崩溃

---

### TC-002: 页面退出释放 ASR

**目的**: 验证离开相机页时 ASR 正确释放，LLM 根据引用状态处理

**前置条件**:
- App 在相机页，ASR 已初始化

**操作步骤**:

| 步骤 | 操作 | 期望日志（LLM 未加载） | 期望日志（LLM 已加载） |
|------|------|------------------------|------------------------|
| 1 | 打开相机页 | `ASR acquired, refCount=1` | `ASR acquired, refCount=1` |
| 2 | 发送语音指令（可选） | — | `LLM acquired, refCount=1` |
| 3 | 点击底部导航切换到相册页 | `ASR released, refCount=0` | `ASR released, refCount=0` |
| 4 | 观察释放行为 | `ASR safe to unload` → `ASR fully unloaded` | `ASR soft release (LLM still active)` |

**验证标准**:
- [ ] 切换页面后 `VoiceCommandCoordinator released` 出现
- [ ] ASR refCount 正确递减到 0
- [ ] 无 LLM 引用时 ASR 真正释放；有 LLM 引用时 ASR 软释放

**通过标准**: ASR 不再占用内存，无资源泄漏

---

### TC-003: 内存压力紧急释放

**目的**: 验证系统内存紧张时立即释放模型

**前置条件**:
- App 在相机页，LLM + ASR 均已加载

**操作步骤**:

| 步骤 | 操作 | 期望日志 |
|------|------|----------|
| 1 | 进入相机页，触发语音 | `LLM acquired` + `ASR acquired` |
| 2 | 执行 ADB 命令模拟内存压力 | — |
| 3 | 观察日志 | `Memory pressure: LOW/CRITICAL, force unload` |
| 4 | 验证释放 | `LLM fully unloaded` + `ASR fully unloaded` |

**ADB 命令**:
```bash
# 模拟 RUNNING_CRITICAL
adb shell am send-trim-memory com.mamba.picme RUNNING_CRITICAL

# 或模拟 COMPLETE
adb shell am send-trim-memory com.mamba.picme COMPLETE
```

**验证标准**:
- [ ] 命令执行后立即触发 safeUnload
- [ ] 无 30s/60s 延迟
- [ ] 模型完全释放

**通过标准**: 紧急释放成功，App 不崩溃

---

### TC-004: 引用计数正确性（边界测试）

**目的**: 验证复杂场景下引用计数不泄漏、不负值

**前置条件**:
- App 已启动

**操作步骤**:

| 步骤 | 操作 | 期望 refCount | 期望状态 |
|------|------|---------------|----------|
| 1 | 打开相机页 | llm=0, asr=1 | ASR_ONLY |
| 2 | 发送语音指令 | llm=1, asr=1 | SHARED |
| 3 | 切换到相册页（ASR release） | llm=1, asr=0 | LLM_ONLY |
| 4 | 进入文字聊天 | llm=1, asr=0 | LLM_ONLY |
| 5 | 文字聊天结束（LLM release） | llm=0, asr=0 | IDLE |
| 6 | 再次进入相机页 | llm=0, asr=1 | ASR_ONLY |
| 7 | 再次发送语音 | llm=1, asr=1 | SHARED |
| 8 | 按 Home 键 | — | 调度卸载 |
| 9 | 等待 60s | llm=0, asr=0 | IDLE |

**验证标准**:
- [ ] 每个步骤 refCount 符合预期
- [ ] 无负值出现
- [ ] 重复进入/退出不累积泄漏

**通过标准**: 引用计数始终非负，最终可回到 IDLE

---

### TC-005: 前台恢复可重新加载

**目的**: 验证卸载后重新进入前台，模型可正常恢复

**前置条件**:
- 已完成 TC-001，模型已卸载

**操作步骤**:

| 步骤 | 操作 | 期望日志 |
|------|------|----------|
| 1 | 后台 60s，确认模型已卸载 | `LLM fully unloaded` + `ASR fully unloaded` |
| 2 | 重新打开 PicMe | `App entered foreground` |
| 3 | 进入相机页 | `ASR acquired, refCount=1` |
| 4 | 发送语音指令 | `LLM acquired, refCount=1` |
| 5 | 验证功能正常 | 语音识别成功，LLM 推理成功 |

**验证标准**:
- [ ] 前台恢复后模型可重新加载
- [ ] 语音识别功能正常
- [ ] LLM 推理功能正常

**通过标准**: 功能完全恢复，无异常

---

## 3. 自动化测试脚本

### 3.1 Shell 测试脚本

```bash
#!/bin/bash
# scripts/test-mnn-unload.sh
# MNN 模型卸载自动化测试脚本

set -e

PACKAGE="com.mamba.picme"
LOG_FILE="/tmp/mnn_unload_test_$(date +%Y%m%d_%H%M%S).log"
TAGS="MnnResourceManager:LocalLlmEngine:SherpaMnnAsr:VoiceCommand"

echo "=== MNN Unload Test ==="
echo "Log file: $LOG_FILE"

# 清理日志
adb logcat -c

# 启动日志收集
adb logcat -s "$TAGS" -v time > "$LOG_FILE" &
LOG_PID=$!

# 测试函数
run_test() {
    local test_name=$1
    local steps=$2
    echo ""
    echo "=== $test_name ==="
    eval "$steps"
}

# TC-001: 后台自动卸载
tc_001() {
    echo "Step 1: Launch app and enter camera"
    adb shell am start -n ${PACKAGE}/.MainActivity
    sleep 3

    echo "Step 2: Trigger voice command (simulate tap on voice button)"
    # 模拟点击语音按钮（需根据实际坐标调整）
    adb shell input tap 540 1800
    sleep 2

    echo "Step 3: Press Home"
    adb shell input keyevent KEYCODE_HOME
    sleep 2

    echo "Step 4: Wait 35s for softTrim"
    sleep 35

    echo "Step 5: Wait 30s more for safeUnload (total 65s)"
    sleep 30

    echo "Step 6: Check logs"
    if grep -q "LLM fully unloaded" "$LOG_FILE" && grep -q "ASR fully unloaded" "$LOG_FILE"; then
        echo "✓ TC-001 PASSED"
    else
        echo "✗ TC-001 FAILED"
        echo "Expected: LLM fully unloaded + ASR fully unloaded"
        grep -E "unloaded|trimmed|soft released" "$LOG_FILE" | tail -10
    fi
}

# TC-003: 内存压力紧急释放
tc_003() {
    echo "Step 1: Launch app and enter camera"
    adb shell am start -n ${PACKAGE}/.MainActivity
    sleep 3

    echo "Step 2: Trigger voice command"
    adb shell input tap 540 1800
    sleep 2

    echo "Step 3: Send trim memory command"
    adb shell am send-trim-memory ${PACKAGE} RUNNING_CRITICAL
    sleep 2

    echo "Step 4: Check logs"
    if grep -q "Memory pressure:.*force unload" "$LOG_FILE" && \
       grep -q "LLM fully unloaded" "$LOG_FILE"; then
        echo "✓ TC-003 PASSED"
    else
        echo "✗ TC-003 FAILED"
        grep -E "Memory pressure|force unload|fully unloaded" "$LOG_FILE" | tail -10
    fi
}

# 执行测试
run_test "TC-001: Background Auto Unload" tc_001

# 清理并重新收集日志
kill $LOG_PID 2>/dev/null
adb logcat -c
adb logcat -s "$TAGS" -v time > "$LOG_FILE" &
LOG_PID=$!

run_test "TC-003: Memory Pressure Emergency Unload" tc_003

# 清理
kill $LOG_PID 2>/dev/null

echo ""
echo "=== Test Complete ==="
echo "Full log: $LOG_FILE"
```

### 3.2 使用说明

```bash
# 1. 确保设备连接
adb devices

# 2. 运行测试
chmod +x scripts/test-mnn-unload.sh
./scripts/test-mnn-unload.sh

# 3. 查看详细日志
cat /tmp/mnn_unload_test_*.log
```

---

## 4. 手动测试检查清单

### 4.1 测试前检查

- [ ] 设备已连接，`adb devices` 显示设备
- [ ] PicMe 调试版已安装
- [ ] LLM 模型已下载（设置 → AI 模型管理）
- [ ] ASR 模型已下载
- [ ] 日志过滤命令已运行

### 4.2 测试中记录

| 测试项 | 开始时间 | 结束时间 | 结果 | 备注 |
|--------|----------|----------|------|------|
| TC-001 | | | | |
| TC-002 | | | | |
| TC-003 | | | | |
| TC-004 | | | | |
| TC-005 | | | | |

### 4.3 测试后检查

- [ ] 无崩溃、无 ANR
- [ ] 内存最终回到 IDLE 基线
- [ ] 功能可恢复

---

## 5. 常见问题排查

### Q1: 后台 60s 后模型未卸载

**排查步骤**:
1. 检查日志是否有 `App entered background`
2. 检查 `activityCount` 是否正确（是否有后台 Service 保持 Activity）
3. 检查 `backgroundUnloadScheduled` 是否为 true（可能被前台事件取消）

### Q2: ASR 释放后 recognizer 仍存在

**排查步骤**:
1. 检查日志是否有 `VoiceCommandCoordinator released`
2. 检查 `SherpaMnnAsrEngine.release()` 是否被调用
3. 检查 refCount 是否为 0

### Q3: LLM unload 导致 ASR 崩溃

**排查步骤**:
1. 检查 refCount 是否正确（不应在 ASR 引用存在时 safeUnload LLM）
2. 检查 `MnnResourceManager` 的协调逻辑
3. 查看崩溃堆栈是否涉及 MNN 全局状态

---

## 6. 相关文件

| 文件 | 说明 |
|------|------|
| `docs/03-TECHNICAL-SPECS/MNN_RESOURCE_MANAGER_DESIGN.md` | 设计文档 |
| `docs/03-TECHNICAL-SPECS/MNN_UNLOAD_TRIGGER_MECHANISM.md` | 触发机制文档 |
| `agent-core/src/main/java/com/picme/agent/core/mnn/MnnResourceManager.kt` | 协调管理器 |
| `scripts/test-mnn-unload.sh` | 自动化测试脚本（本文件配套） |
