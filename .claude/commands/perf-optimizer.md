# 性能优化专家 (Performance Optimizer)

> **定位**：诊断内存泄漏、卡顿、帧率下降，确保满足 [PERF] 红线指标。
> **触发时机**：用户反馈卡顿、FPS 下降、OOM、启动慢、相册滑动不流畅时。

---

## 核心指标红线

| 指标 | 目标 | 测量方式 |
|------|------|----------|
| 冷启动 | < 500ms | `Application.onCreate` → 首帧预览 |
| 快门延迟 | < 50ms | 点击 → 触感/音效触发 |
| 交互反馈 | < 100ms | 滑杆变更 → 画面变化 |
| 预览帧率 | ≥ 55fps | 调试浮层 FPS 计数 |
| GPU 处理 | < 300ms (1080p) | `PoLang:PhotoProcessor` 日志 |
| 相册滑动 | 120fps 目标 | `dumpsys gfxinfo` jank 计数 |

---

## 诊断流程

### Step 1: 确定性能瓶颈类型

```bash
# 收集基础性能数据
adb shell dumpsys meminfo com.mamba.picme
adb shell dumpsys gfxinfo com.mamba.picme | grep -i "jank\|frame"
adb logcat -d | grep -E "PoLang:.*elapsed|PoLang:.*FPS|PoLang:.*perf"
```

| 症状 | 瓶颈类型 | 工具 |
|------|----------|------|
| 界面卡顿、掉帧 | UI/渲染线程 | Systrace / Perfetto |
| 内存持续增长 | 内存泄漏 | Memory Profiler / LeakCanary |
| 启动慢 | 初始化阻塞 | CPU Profiler / Method Tracing |
| 相册滑动卡 | RecyclerView/Compose 优化 | Layout Inspector |
| 拍照后处理慢 | GPU/Shader 性能 | GPU Profiler / 自定义计时 |

### Step 2: 使用 Android Studio Profiler

**CPU Profiler**：
- 采样方式：`Trace Java Methods`（精度高）或 `Sample Java Methods`（开销低）
- 关注：主线程是否有耗时操作（> 16ms）

**Memory Profiler**：
- 抓取 Heap Dump → 检查 retained size 异常增长
- 关注：`Bitmap`、`ByteArray`、`Native` 内存

**GPU Profiler**：
- 检查 `renderThread` 每帧耗时
- 目标：每帧 < 16.67ms（60fps）或 < 8.33ms（120fps）

### Step 3: 日志分析

```bash
# 提取性能相关日志
adb logcat -d | grep -E "PoLang:.*elapsed|PoLang:.*FPS|PoLang:.*perf|PoLang:.*memory"

# 检查是否有超时警告
grep -i "timeout\|slow\|jank\|dropped" scripts/auto_test_output/*/logcat_picme.txt
```

---

## 常见性能陷阱

| 陷阱 | 症状 | 修复 |
|------|------|------|
| **主线程 IO** | ANR / 卡顿 | 移至 `Dispatchers.IO` / 后台线程 |
| **Bitmap 未复用** | OOM / 频繁 GC | 使用 `BitmapFactory.Options.inBitmap` |
| **FBO 每帧创建** | GPU 帧率下降 | 初始化时创建，复用 |
| **StateFlow 高频更新** | 重组风暴 | 使用 `debounce` / `sample` |
| **相册无预加载** | 滑动白屏 | `LazyColumn` 设置 `prefetch` / 自定义预加载 |
| **LeakCanary 报警** | Activity/Fragment 泄漏 | 检查 `DisposableEffect` 清理、匿名内部类 |

---

## 性能基线对比

使用 `./scripts/perf-baseline.sh` 自动提取：

```bash
# 运行性能基线测试
./scripts/perf-baseline.sh

# 输出示例
FPS: 58.2 (目标: ≥ 55) ✅
PhotoProcess: 245ms (目标: < 300ms) ✅
MemoryPeak: 186MB (基线: 180MB) ⚠️
```

## 相关文件

- [docs/01-PRODUCT/FEATURES.md](docs/01-PRODUCT/FEATURES.md) — 性能指标定义
- [av-gl-expert](/av-gl-expert) — GPU/Shader 性能诊断
- [compose-ui-expert](/compose-ui-expert) — Compose 重组性能优化
- [qa-acceptance](/qa-acceptance) — 性能验收标准
- [error-healer](/error-healer) — 编译错误修复

## 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| 1.0.0 | 2026-05-25 | 初始版本 |
