# MNN iOS 集成 Skill

> **定位**：MNN.framework（arm64）iOS 构建 / embed 与人脸检测推理诊断，对标 Android mnn-integration + mnn-landmark-diagnosis。
> **触发时机**：iOS 集成 MNN、构建 framework、人脸检测异常、precision / SIGSEGV 崩溃时。

## 构建

```bash
# engines/mnn-core 收编的封装（5.1 从 tmp/ 搬正归档）
./engines/mnn-core/build_lib.sh --ios
# 产出 arm64 MNN.framework，搬正到 iosApp/Frameworks/
```

## embed 与链接

- 把 `MNN.framework` 加到 iosApp target 的 **Embed Frameworks**。
- `OTHER_LDFLAGS = -ObjC`（必须，否则 Objective-C category 不加载）。
- 显式链接 `Metal.framework` / `UIKit.framework` / `Foundation.framework`。

## 人脸检测（Phase 5：det_500m + 106 关键点）

RetinaFace + 106 关键点，C++ 产物经 `engines/mnn-core` iOS 封装。106pt 解析 / 映射逻辑与 Android 同源（见 [coordinate-system-standard](/coordinate-system-standard)、[mnn-landmark-diagnosis](/mnn-landmark-diagnosis)）。

## 关键坑（补验 A 实证，必读）

| 坑 | 症状 | 修复 |
|----|------|------|
| **precision 默认 Normal（fp16）** | 数值完全错误（关键点飞） | `precision = Precision_High`（必须显式设） |
| **backendConfig 为 nullptr** | SIGSEGV（解引用） | 显式构造 `BackendConfig`，禁用默认 nullptr |

```cpp
// 正确：显式 precision + backendConfig
BackendConfig config;          // 非 nullptr
ScheduleConfig sched;
sched.backendConfig = &config;
Interpreter* interp = Interpreter::createFromBuffer(...);
interp->setSession(sched, Precision_High);  // 不要用默认
```

> ⚠️ Android 默认配置能跑 ≠ iOS 默认能跑——坑 1/2 是 iOS 独有或暴露更强。

## 集成范围

- **Phase 5**：仅人脸检测（det_500m，warp 滤镜前置 + 相册调试）。
- **Phase 6.1（未来）**：TAG 全栈、Qwen3-VL-2B（补验 B 暂缓；恢复时同步验证 precision 档位）。

## 诊断检查清单

- [ ] framework 已 embed 且 `-ObjC` 已设？
- [ ] precision 显式 `Precision_High`？
- [ ] backendConfig 非 nullptr？
- [ ] 模型文件（det_500m）打包进 app 且路径正确？
- [ ] 106pt 输出与 Android 对照一致？（坐标体系同源）

## 相关文件

- [mnn-integration](/mnn-integration) — Android MNN 接入
- [mnn-landmark-diagnosis](/mnn-landmark-diagnosis) — 106pt 对齐诊断
- [coordinate-system-standard](/coordinate-system-standard) — 106pt 坐标体系
- [metal-render-expert](/metal-render-expert) — warp 滤镜消费关键点

## 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| 1.0.0 | 2026-08-08 | 初始版本（Phase 5.1/5.4 人脸检测） |
