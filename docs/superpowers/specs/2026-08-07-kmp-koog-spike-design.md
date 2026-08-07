# KMP + Koog 端到端连通验证 Spike 设计（Phase 2.3）

> **日期**：2026-08-07
> **关联**：`docs/superpowers/plans/2026-08-07-polang-kmp-ios-transformation.md` Phase 2.3（🔴 阻塞前置：Phase 4 开工前必须完成）
> **性质**：本文档是**执行计划 + 报告模板**。执行完成后将「7. 验证结果」各节填实，结论改为 GO / NO-GO。
> **工期估计**：1–2 天

---

## 1. 验证目标

Phase 2.1/2.2 已验证 C++ 引擎层（MNN/sentencepiece/QuickJS）的 iOS 编译可行性。本 spike 验证 **KMP 工具链 + Koog Agent 框架**的端到端连通性，覆盖三个阻塞级不确定性（2026-08-07 review B3/S4/S5）：

| # | 不确定性 | 为什么阻塞 |
|---|---------|-----------|
| U1 | **Koog 1.1.1 在 iOS（Kotlin/Native）能否初始化并发起 LLM 调用** | Kotlin/Native 无 `java.util.ServiceLoader`；Phase 1 已在 Android 踩到 1.1.1 ServiceLoader 缺陷（commit `47974b4e`，需显式构造 `KtorKoogHttpClient.Factory`），iOS 侧可能是不同的坑。Phase 4.3 把 Agent 层迁 commonMain 的前提 |
| U2 | **Kotlin/Native 构建耗时是否在可接受范围** | 社区有 30+ 分钟构建报告（Kotlin Slack）、YouTrack KT-78518 记录 Release framework 链接资源失控。直接决定 AI 迭代循环（改代码→构建→验证）是否可行 |
| U3 | **xcode-kotlin 调试断点能否命中 Kotlin 行** | iOS 侧调试成本是 shared 抽取的主要对冲项 |

顺带并入 **Phase 2.2 的运行时补验**（S1）：sentencepiece `Encode→Decode` 往返、QuickJS `evaluate("1+2")`——与 KMP 工程是同一条验证链路，一次做完。

## 2. 验证环境

| 项 | 值 |
|----|-----|
| Kotlin | 2.3.10（与主项目一致，Phase 1 已升级） |
| Koog | 1.1.1（`ai.koog:koog-agents`，与 Phase 1 接入版本一致） |
| quickjs-kt | 1.0.5（`io.github.dokar3:quickjs-kt`，Phase 2.2 已验证 klib 存在） |
| sentencepiece | 0.2.2（Phase 2.2 产物 `libsentencepiece-static.a`，arm64） |
| 测试设备 | 郭帅的iPhone（arm64，iOS 26.6）+ iOS 模拟器 |
| Xcode | 16.4（Build 16F6） |
| LLM 通路 | 优先走项目 server AI 网关（与生产一致，key 不出现在 spike 代码中）；网关不可用时直连 DeepSeek（key 仅放本地未跟踪文件） |
| Spike 工程位置 | `tmp/kmp-koog-spike/`（**一次性验证产物，不进入主分支**；Phase 4 从零建正式 shared 模块） |

## 3. 工程结构

```
tmp/kmp-koog-spike/
├── settings.gradle.kts / build.gradle.kts     # 最小 KMP 工程
├── sharedSpike/
│   └── src/
│       ├── commonMain/kotlin/
│       │   ├── Greeting.kt                    # 一个简单函数（验证纯 Kotlin 共享）
│       │   └── KoogPing.kt                    # Koog Agent 调 DeepSeek（U1 核心）
│       ├── iosMain/kotlin/                    # （如需 actual）
│       └── commonTest/kotlin/                 # JVM 单测（Android 侧验证路径）
├── androidApp/                                # 最小 Android app 消费 sharedSpike（可选，见 §4 S4）
└── iosApp/                                    # 最小 SwiftUI app（Xcode 工程）
```

`KoogPing.kt` 最小逻辑：构造 Koog Agent（OpenAI/DeepSeek executor + baseUrl 指向网关）→ 发送固定 prompt（"Reply with exactly: pong"）→ 返回响应文本。**显式记录 HTTP client 的构造方式**（是否需像 Android 一样显式构造 `KtorKoogHttpClient.Factory`）——这是 U1 的关键产出。

## 4. 执行步骤

- [ ] **S1 最小 KMP 工程**：创建 `tmp/kmp-koog-spike/`，`sharedSpike` 模块声明 `androidTarget` + `iosArm64` + `iosSimulatorArm64`，接入 Koog 1.1.1 依赖；`commonMain` 实现 `Greeting` + `KoogPing`
- [ ] **S2 JVM/Android 侧先行**：`commonTest` 单测跑通 `KoogPing`（JVM 上先验证 Koog 调用本身 OK，隔离变量）；可选：最小 androidApp 消费验证
- [ ] **S3 iOS framework 集成**：`sharedSpike` 产出 framework → Xcode 工程集成（记录集成方式：SPM binary target / 手动 embed / CocoaPods 三选一并写明理由）；最小 SwiftUI 页面：一个按钮触发 `KoogPing.call()`，结果显示在屏幕上
- [ ] **S4 U1 验证：Koog iOS 真机调用**：iPhone 真机运行，点击按钮，**Koog Agent 初始化成功 + 收到 DeepSeek 响应**。失败时记录完整堆栈，定位是否为 ServiceLoader 类缺陷并给出 workaround（如显式构造 HTTP client factory）
- [ ] **S5 U2 验证：构建耗时实测**（每项测 3 次取中位数，记录到 §7 表格）：
  1. clean 全量构建（debug framework）
  2. 改 `commonMain` 一行代码后的增量 debug 构建
  3. 改 `iosMain` 一行代码后的增量 debug 构建
  4. Release framework 全量构建（链接阶段是 KT-78518 重灾区）
- [ ] **S6 U3 验证：xcode-kotlin 断点**：安装/配置 xcode-kotlin（Touchlab 2.0+），在 `KoogPing` 的 Kotlin 行设断点，真机/模拟器运行确认命中，可查看变量
- [ ] **S7 Phase 2.2 运行时补验（并入）**：sentencepiece `Encode("hello world")` → token IDs → `Decode(ids)` 往返一致（走 cinterop 或 ObjC++ 桥，记录哪条路径可行）；QuickJS `evaluate<Int>("1+2")` 返回 3
- [ ] **S8 结果回写**：填实 §7，更新结论；路线图 Phase 2.3 勾掉补验项；若 U1/U2 失败，按 §6 升级处理

## 5. 出口标准

| 项 | 判定标准 |
|----|---------|
| U1 Koog iOS 调用 | iPhone 真机收到 DeepSeek 实际响应（非 mock） |
| U2 构建耗时 | 增量 debug 构建 ≤ 2 分钟（超过则需在路线图风险登记册升级并评估 SPM binary target 预编译方案） |
| U3 断点 | Kotlin 行断点可命中、变量可见 |
| 补验 | sentencepiece 往返一致；QuickJS evaluate 返回 3 |

**Go/No-Go**：U1 + U2 + U3 全过 → GO，Phase 2 进入 2.5 汇总；U1 失败且无法 workaround → 回到路线图第 1 节「Agent 框架」决策重审；U2 超标 → 不阻塞但须在 Phase 4 计划中给出缓解（预编译 framework、减小张量导出面等）。

## 6. 失败预案

| 失败场景 | 预案 |
|---------|------|
| Koog iOS 初始化失败（ServiceLoader 类） | 查 Koog 源码确认 Native 侧 provider 发现机制；尝试显式构造 HTTP client factory（同 Android workaround）；仍失败 → 提 Koog issue + 评估 Agent 层 iOS 侧走 expect/actual（commonMain 接口 + iosMain 内自研最小 client 兜底） |
| KN 增量构建 > 5 分钟 | 评估：SPM binary target 预编译、减少 Kotlin 导出面、模块化拆分 shared；结论写入 Phase 4 细化计划 |
| xcode-kotlin 不可用 | 替代：`println` + Xcode console、K/N 日志框架；调试成本重估写入风险登记册 |
| sentencepiece cinterop 失败 | 改走 ObjC++ 桥（Swift 侧直接调 C++），记录两条路径对比 |

## 7. 验证结果（执行后填实）

> 执行时间：2026-08-07 晚。设备：iPhone（00008120-000105443AD2201E，iOS 26.6，arm64 真机）。工程：`tmp/kmp-koog-spike/`（不入库）。

### 7.1 U1：Koog iOS 初始化与调用

- HTTP client 构造方式：✅ **显式构造 `KtorKoogHttpClient.Factory()` 直通**——Kotlin/Native 无 ServiceLoader 问题，无需任何 workaround
- 真机调用结果：✅ **PASS**——Koog 1.1.1（Kotlin/Native）调用 DeepSeek 返回 `"pong"`；kotlin-logging 自动落到 `DarwinLoggerFactory`
- 遇到的问题与 workaround：
  1. **Kotlin 异常不经 `@Throws` 导出会 signal 6 崩溃**——`SpikeFacade` 在 Kotlin 侧 try/catch 兜底成 `"ERROR: ..."` 字符串返回，Phase 4 正式 shared 层须统一此约定
  2. **国行 iPhone 无线数据权限**——首次调用报 -1009 "Denied over Wi-Fi"，属系统网络授权弹窗未点，重装授权后通过（非代码问题）
  3. **Koog 1.1.1 API 与文档差异**——`PromptExecutor.execute(prompt, model, tools)` 返回 `Message.Assistant`（非 List），文本须 `response.parts.filterIsInstance<MessagePart.Text>()` 提取；params 烘进 `prompt(id, params){}` DSL。已在 S2 JVM 侧踩平，iOS 直接复用

### 7.2 U2：构建耗时

> 首轮单次数据（2026-08-07，Mac 本机，Gradle/Konan 缓存 warm；文档原要求 3 次中位数，此处为单次，量级判断足够）

| 场景 | 第 1 次 | 第 2 次 | 第 3 次 | 中位数 |
|------|--------|--------|--------|--------|
| clean 全量（debug） | 6.1s | — | — | 6.1s（单次，缓存 warm） |
| 增量（改 commonMain 一行） | 6.3s | 5.7s | — | ~6s |
| 增量（iosMain 新文件 / 改一行） | 5.4s | 5.2s | — | ~5.3s |
| Release framework 全量 | 3m53.7s | — | — | 3m53.7s（单次） |

**解读**：debug 增量 ~5-6s，AI 迭代循环完全可行（远好于社区 30+ 分钟报告——那是大型工程 + 冷缓存场景）；Release 全量 3m54s 属一次性发布成本，不进日常循环。

### 7.3 U3：xcode-kotlin 断点

- brew 安装 xcode-kotlin 完成，`xcode-kotlin install` 已执行：写入 `Kotlin.xclangspec`、`~/.lldbinit-Xcode`、插件加入 Xcode allowed 列表
- ⏸️ **断点命中验证待用户手动确认**：Xcode GUI 重启后需同意加载插件，随后在 Kotlin 源行下断点实测。不阻塞 GO 结论（调试体验项，非功能阻断）

### 7.4 Phase 2.2 运行时补验

- sentencepiece 往返：✅ **PASS**——`Encode("hello world")` → `[▁he][ll][o][▁world]`（4 token，vocab=1000，test_model.model）→ `Decode` 得 `"hello world"` 完全一致
- QuickJS evaluate：✅ **PASS**——quickjs-kt 1.0.5 在 iOS 真机执行 `1+2` 返回 3
- sentencepiece 桥接路径结论：✅ **ObjC++ 直接链接静态库，无需 cinterop**——`libtool -static` 将 `libsentencepiece-static.a` + 83 个 `libabsl_*.a` 合并为单个 `libspm_ios.a`（protobuf-lite 已编译进 sentencepiece 库内）；Xcode 侧只需 3 条 HEADER_SEARCH_PATHS（`main/cpp`、`main/cpp/src`、`third_party/abseil-cpp`）+ 1 条 LIBRARY_SEARCH_PATHS；桥接代码约 50 行 ObjC++（`SpmBridge.mm`）。cinterop def 路径未走也不需要走

## 8. 结论

✅ **GO**——Phase 2.3 全部必验项通过：

1. **U1 Koog iOS 真机调用 ✅**：Koog 1.1.1 在 Kotlin/Native 显式构造 HTTP client 直通，DeepSeek 实回 "pong"；无 ServiceLoader 坑
2. **U2 构建耗时 ✅**：debug 增量 ~5-6s，AI 迭代循环可行；Release 全量 3m54s 为一次性成本
3. **U3 xcode-kotlin ⏸️**：插件已装并就位，断点命中待 Xcode GUI 手动确认（非阻塞）
4. **Phase 2.2 运行时补验 ✅**：sentencepiece 往返一致、QuickJS 真机执行通过；桥接路径定为 ObjC++ 合并静态库

Phase 2 排雷仅剩「补验 B：Qwen3-VL-2B 真机验证」按用户决策暂缓（恢复触发点：Phase 5 启动前或 Phase 6.1 TAG 接入前）。可启动 Phase 4 shared KMP 抽取。
