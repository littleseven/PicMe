# Agent Test 详细参考

## JSON 测试用例格式

```json
{
  "caseId": "TC-CAMERA-02",
  "name": "前后摄像头切换验证",
  "category": "CAMERA",
  "priority": "P0",
  "steps": [
    {
      "description": "等待相机页面就绪",
      "wait": {"condition": "scene == 'CAMERA'", "timeout": 5000},
      "assert": {"scene": "CAMERA"}
    },
    {
      "description": "切换到前置摄像头",
      "action": {"method": "flip_camera", "params": {}},
      "delay": 1500
    },
    {
      "description": "截屏记录前置预览",
      "screenshot": "camera_front"
    }
  ]
}
```

## Step 字段说明

| 字段 | 类型 | 说明 |
|------|------|------|
| `description` | string | 步骤描述 |
| `action` | object | 执行命令，`method` + `params` |
| `wait` | object | 等待条件，`condition` + `timeout` |
| `assert` | object | 断言验证 |
| `delay` | number | 延迟（毫秒） |
| `screenshot` | string | 截屏标记名称 |

## 底层 adb 命令（调试用）

```bash
# 显式广播发送 JSON 命令
adb shell "am broadcast -n 'com.mamba.picme/.testing.agent.bridge.AgentTestBroadcastReceiver' -a com.mamba.picme.AGENT_TEST --es json '{\"method\":\"flip_camera\",\"params\":{}}'"

# 查看广播注册状态
adb shell dumpsys activity broadcasts | grep com.mamba.picme.AGENT_TEST

# 查看应用进程
adb shell ps | grep com.mamba.picme

# 按 PID 查看日志
adb logcat --pid=$(adb shell pidof com.mamba.picme) -d
```

## 输出目录结构

```
scripts/auto_test_output/agent_v2_20260606_165436/
├── report.json              # 测试报告
├── test.log                 # 执行日志
├── screenshots/             # 截屏目录
├── perf_fps.txt             # FPS 数据
├── perf_memory.txt          # 内存数据
└── perf_temp.txt            # 温度数据
```
