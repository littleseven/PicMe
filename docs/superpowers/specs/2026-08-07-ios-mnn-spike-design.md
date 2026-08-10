# MNN iOS 编译与推理验证 Spike 报告

> 📜 **历史 Spike 报告**（Phase 2.1 排雷）。结论已沉淀进路线图 `2026-08-07-polang-kmp-ios-transformation.md`；保留作审计链（linker `-ObjC`/Precision_High 档位坑）。⚠️ 本文「补验 B（Qwen3-VL-2B）为 Phase 6.1 TAG 前置」框架已过时——默认 Pass3 改走 Florence-2（ORT），补验 B 仅备选路径需（见 `IOS_TASK_STATUS.md` §6.1）。归类见 `docs/01-PRODUCT/IOS_DOC_INDEX.md` §2.1。


> **日期**：2026-08-07
> **关联**：`docs/superpowers/plans/2026-08-07-polang-kmp-ios-transformation.md` Phase 2.1
> **结论**：⚠️ **有条件 GO** — CPU 推理路径已验证通过；**Metal 输出全 0 未解决（正确性未验证）、Qwen3-VL-2B 未验证**，两项补验通过前不构成完整 GO（见 §4「阻塞补验项」，2026-08-07 review 修订）

---

## 1. 验证目标

验证 MNN 推理引擎能否在 iOS 上编译为 framework、加载模型、执行推理，作为 PoLang KMP 跨端改造的技术地基确认。这是整个 iOS 改造计划中最大的技术不确定性，前置排雷。

## 2. 验证环境

| 项 | 值 |
|----|-----|
| MNN 源码 | `/Users/guoshuai/code/MNN/`（本地 fork，3.5.0） |
| 构建脚本 | `build_lib.sh --ios` / `--ios-simulator` |
| 测试设备 | iPhone（郭帅的iPhone，arm64，iOS 26.6） |
| 测试模型 | `det_500m.mnn`（RetinaFace 人脸检测，1.2MB，从 Android 设备提取） |
| Xcode | 16.4（Build 16F6） |
| 签名 | Apple Development（Personal Team，免费 Apple ID） |

## 3. 验证结果

### 3.1 Framework 编译

**构建命令**：`bash build_lib.sh --ios`（真机 arm64）/ `--ios-simulator`（模拟器 x86_64 + arm64）

**编译配置**（`build_lib.sh` 内置，已验证覆盖项目全部需求）：

| 编译项 | 状态 | 说明 |
|--------|------|------|
| Metal 后端 | ✅ | `MetalBackend`、`MTLDevice`、`MTLComputePipelineState` 符号验证通过 |
| ARM82 (FP16) | ✅ | half-precision NEON |
| LLM 引擎 | ✅ | `Llm::response`、`generate`、`tokenizer_encode`、`sample` |
| 多模态/视觉 | ✅ | `Llm::response(MultimodalPrompt)` — Qwen3-VL 图文推理关键 API |
| Omni 引擎 | ✅ | `Transformer::Omni` — 多模态推理入口 |
| Audio | ✅ | `PromptAudioPart` |
| OpenCV (imgcodecs) | ✅ | 图像解码 |
| Diffusion | ✅ | |

**产物**：

| 产物 | 大小 | 位置 |
|------|------|------|
| `MNN.framework`（真机 arm64） | 10MB（静态库） | `mnn_builds/ios/device/MNN.framework/` |
| `MNN.framework`（模拟器 universal） | 18MB | `mnn_builds/ios/simulator/MNN.framework/` |

**编译注意**：
- 只有 `half.hpp` 的 FP infinity warnings（无害），零 error
- `LLM_SUPPORT_VISION` CMake 变量报 "not used"——Vision 支持由 `MNN_BUILD_LLM=ON` 自动包含
- 静态库（`MNN_BUILD_SHARED_LIBS=false`），集成到 Xcode 时走静态链接

### 3.2 Xcode 集成

**集成方式**：预编译 framework 直接放入工程目录，`FRAMEWORK_SEARCH_PATHS` 指向 `$(SRCROOT)`。

**关键 linker 配置**（踩过的坑）：
- `OTHER_LDFLAGS = -ObjC`：**必须**。MNN 是 Objective-C++ 静态库，不加 `-ObjC` 链接器不加载全部符号，出现 "Undefined symbols for architecture arm64"
- `Metal.framework`、`UIKit.framework`、`Foundation.framework`：需在 Frameworks build phase **显式链接**。即使 pbxproj 里有 PBXFileReference，不加入 Frameworks phase 链接器不会自动拉
- 不需要 Embed Frameworks（静态库直接链接，不产生动态 framework）

### 3.3 真机推理结果

**模型**：`det_500m.mnn`（RetinaFace 500m，输入 `1x3x640x640` float32）

| 指标 | CPU（4 线程） | Metal |
|------|--------------|-------|
| 单次推理（含初始化） | 15.72 ms | 0.88 ms |
| 100 次 benchmark 平均 | **9.95 ms** | **15.83 ms** |
| 输出元素数 | 12800 | 12800 |
| 输出值 | 有值 ✅ | 全 0 ⚠️（见下） |

**关键发现**：

1. **推理全链路通过**：模型加载 → session 创建 → resize → forward → 输出读取，CPU 和 Metal 均成功
2. **resize 是必须步骤**：`det_500m.mnn` 输入 shape 有动态维度 `[1,3,-1,-1]`，必须先 `net->resizeTensor(input, {1,3,640,640})` + `net->resizeSession(session)` 才能 `runSession`，否则报 "Can't run session because not resized"
3. **Metal 单次推理 0.88ms 极快**，但 100 次平均 15.83ms：benchmark 每轮做 `copyToHostTensor`（GPU→CPU 回读），同步开销远大于推理本身。实际使用中 Metal 跑连续推理不每轮回读会快得多
4. **Metal 输出全 0（✅ 已解决，2026-08-07 晚补验 A）**：根因证实为假设 (b)——旧代码直接写 `input->host<float>()`，数据从未上传到 device。走 `ImageProcess::convert` 后数据正常到达，但暴露了更深的坑：**MNN Metal 默认 `Precision_Normal`（fp16）档位输出数值完全错误**（与 CPU 对比 cos ≈ -0.5 系统性负相关），必须显式设 `precision = Precision_High`（cos=1.000000）或 `Precision_Low`（cos≥0.99996）。详见 §4 补验 A
5. `set multi tuning mode is not permitted, please check cl_mode:0` 警告无害（Metal 后端不涉及 OpenCL tuning）

## 4. 对改造计划的影响

### ✅ 确认可行

- **MNN iOS 编译**：`build_lib.sh --ios` 一键完成，配置覆盖项目全部需求
- **Metal 后端**：可用，符号完整，初始化成功
- **人脸检测模型**：`det_500m.mnn` iOS 推理通过，性能可接受（CPU ~10ms/帧）
- **LLM/Vision API**：`Llm::response(MultimodalPrompt)` 可用，Qwen3-VL-2B 推理路径有保障

### 🔴 阻塞补验项（2026-08-07 review 修订）

- **Metal 正确性验证（补验 A）**：✅ **已完成（2026-08-07 晚，PASS 附条件）**。用合成 RGBA 图像经 `ImageProcess::convert` 走生产路径输入 `det_500m.mnn`，CPU vs Metal 全 9 个输出张量逐一对比。结果：
  - `Precision_Normal`（fp16，MNN Metal **默认档位**）：❌ 全部 9 个输出 cos ≈ -0.10 ~ -0.64，数值完全错误——**默认 fp16 路径在本设备（iOS 26.6 + MNN 3.5.0）上不可用**
  - `Precision_High`（fp32）：✅ 全部 cos = 1.000000，maxDiff ≤ 0.0003
  - `Precision_Low`：✅ 全部 cos ≥ 0.99996，maxDiff ≤ 0.04
  - **结论：Metal 路径可用，但必须显式设置 `backendConfig.precision = Precision_High`（或 Low），禁用默认 Normal 档位**。此坑须纳入 Phase 6.1 MetalGuardian 设计（precision 作为后端配置的强制项），并在 MNN 升级 3.6.1 后复测 Normal 档位是否修复
  - 原 spike「Metal 输出全 0」根因确认：旧代码直接写 `input->host<float>()`，数据从未上传到 device（假设 b 证实）；走 `ImageProcess::convert` 后数据正常到达
  - 附带发现：`ScheduleConfig.backendConfig` 默认 nullptr，解引用即 SIGSEGV，需显式构造
  - 验证代码：`tmp/mnn-ios-spike/MnnSpike/main.mm`（`runCorrectnessCheck`）
- **Qwen3-VL-2B（1.4–2.18GB）iOS 真机验证（补验 B）**：⏸️ **暂缓（用户决策 2026-08-07）**。风险仍在案：TAG 核心 VLM 在 iOS 未验证（内存峰值/Metal 算子覆盖/首 token 延迟/多模型共存），失败预案实际替代路径极少（MLX 不支持 iOS、CoreML LLM 支持有限）。恢复验证的触发点：Phase 5 启动前 或 Phase 6.1 TAG 接入前，届时必须同步验证 precision 档位（补验 A 已证明 Metal fp16 数值路径有坑）
- **MNN 版本核实（补验 C）**：✅ **已核实（2026-08-07），结论：升级降级为条件触发项**。本地 fork 为 `3.5.0-64-g9ad00c85`（3.5.0 后 64 个提交），**已包含 Qwen3-VL 支持**（2025/10/17 支持合入 `c67a9615` + 2026/05/09 bilinear sampling bugfix `9d76f8a7`，均在 HEAD）。补验 B 可直接用现有版本；升级 3.6.1 仅在以下情况触发：补验 B 跑不通且排查指向已修复的上游 bug。另注意版本一致性——Android 生产共用此 fork，iOS 单独升级会造成双端行为不一致，要升需双端同升 + Android 回归。可选复测：3.6.1 下 Metal Normal(fp16) 档位是否修复（非阻塞）

### 📋 对 Phase 5（iOS App 骨架）的输入

- iOS 集成 MNN 的 linker 配置：`-ObjC` + 显式链接 Metal/UIKit/Foundation
- 动态输入模型必须 resize 后才能推理
- framework 产物位置：`mnn_builds/ios/device/MNN.framework`
- 构建脚本已就绪，无需额外开发

## 5. Spike 产物

- **Spike 工程源码**：`tmp/mnn-ios-spike/`（MnnSpike.xcodeproj + main.mm + Info.plist）
- **Framework 产物**：`/Users/guoshuai/code/MNN/mnn_builds/ios/{device,simulator}/MNN.framework`
- **构建脚本**：`/Users/guoshuai/code/MNN/build_lib.sh`

## 6. 结论

**有条件 GO**（2026-08-07 二次修订）。MNN iOS 编译与 CPU 推理路径已确认；**Metal 路径经补验 A 验证可用，但强制 `precision = Precision_High/Low`（默认 Normal/fp16 档位数值完全错误，已定位）**。剩余阻塞补验：B（Qwen3-VL-2B 真机推理，须同步验证 precision 档位）、C（MNN 3.6.1 升级复测）。两项通过前 Phase 2.1 不算全绿，不据此启动 Phase 5。
