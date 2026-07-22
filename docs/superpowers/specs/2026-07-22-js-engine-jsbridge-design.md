# JS 引擎 + JSBridge 集成设计（MVP）

- **日期**：2026-07-22
- **模块**：`:runtime-core`（新增 `js` 子包）
- **状态**：自主执行（用户离线），待用户审阅
- **范围**：在 PoLang Android 端引入一个可执行 JavaScript 的运行时，并实现 **JSBridge**（原生 Kotlin ↔ JS 双向调用）；产出可行性结论、过审风险评估、引擎无关抽象、最小可用实现（MVP）与单测。
- **关键词**：Rhino、QuickJS、JSBridge、沙箱、Google Play Device and Network Abuse、动态代码合规

---

## 1. 背景与现状

PoLang 当前是**纯原生**应用（Kotlin + Jetpack Compose）。全仓检索确认：

- 无任何 `WebView` / `@JavascriptInterface` / `evaluateJavascript` / `addJavascriptInterface` 使用；
- 无任何 JS 引擎依赖（quickjs / hermes / duktape / v8 / rhino / jsc 均不存在）；
- 依赖以原生为主：CameraX、MLKit、MediaPipe、ONNX、MNN、Retrofit/OkHttp、Moshi、Compose；
- 设备 ABI **仅 arm64-v8a**；minSdk 24 / targetSdk 35；Java 17。

**为何需要 JS 引擎 + JSBridge**（产品动机）：

1. **能力编排的另一种表达载体**：Agent Runtime（`CapabilityRegistry` + `CommandExecutor`）当前只能由 LLM 输出 `AgentCommand` 或用户点击触发。引入 JS 后，可以把"一组能力的组合脚本"以轻量 JS 形式表达（如相册批处理、滤镜参数脚本），由 Agent 下载/选择脚本并在端侧解释执行，拓展"Agent 可调度资源"的边界。
2. **未来插件化试验场**：为"智能相册插件/编辑器脚本"预留载体（与 PRODUCT.md 的相册+图像编辑重心一致）。
3. **跨平台逻辑复用**：部分纯算法/参数计算逻辑可用 JS 描述，便于与 Web/iOS 侧共享。

**核心命题是"可行性 + 过审风险"**：在合规前提下，验证 JS 引擎可被端侧承载、JSBridge 双向通路可用、且不触发应用商店动态代码审核。

---

## 2. 调研结论

### 2.1 引擎选型对比（Android 可用、有现成绑定）

| 引擎 | 引入方式 | arm64-v8a | 体积 | JSBridge | JVM 单测可跑 | License | 活跃度 |
|---|---|---|---|---|---|---|---|
| **Rhino** (`org.mozilla:rhino-runtime`) | Maven Central，jar | ✅ 纯 Java，无 .so | ~1.5MB jar | 中（注入宿主对象） | **✅ 是** | MPL 2.0（库友好） | 维护中（1.7.14.1 / 2024） |
| QuickJS（`wang.harlon.quickjs:wrapper-android`） | Maven Central | ✅ | ~700KB-1MB .so | 好 | ❌ 否（需 .so/Robolectric） | MIT | 活跃 |
| QuickJS（`io.github.dokar3:quickjs-kt-android`） | Maven Central | ✅ | ~700KB-1MB .so | 好（Kotlin DSL） | ❌ 否 | Apache 2.0 | 活跃（19 版本） |
| app.cash.quickjs | Maven Central | ✅ | .so | 好 | ❌ 否 | Apache 2.0 | **停滞（2021）** |
| Hermes | 源码 CMake/NDK 自编译 | ✅ | 大 | 自实现 | ❌ 否 | MIT | 活跃但面向 RN |
| WebView + JS（系统） | 无 | ✅ | 0 | `@JavascriptInterface` | ❌ 否 | 系统 | 系统 |

> 出处：[Rhino releases](https://github.com/mozilla/rhino/releases)、[quickjs-wrapper](https://github.com/HarlonWang/quickjs-wrapper)、[dokar3/quickjs-kt](https://mvnrepository.com/artifact/io.github.dokar3/quickjs-kt-android)、[app.cash.quickjs](https://central.sonatype.com/artifact/app.cash.quickjs/quickjs-android)、[facebook/hermes](https://github.com/facebook/hermes)、[QuickJS 官方](https://bellard.org/quickjs/)。

### 2.2 过审风险评估（核心）

**Google Play「Device and Network Abuse」政策原文**（[出处](https://support.google.com/googleplay/android-developer/answer/16559646?hl=en)，[Developer Program Policy](https://support.google.com/googleplay/android-developer/answer/17190352?hl=en)）：

> "an app may not download executable code (such as dex, JAR, .so files) from a source other than Google Play. **This restriction does not apply to code that runs in a virtual machine or an interpreter where either provides indirect access to Android APIs (such as JavaScript in a webview or browser).**"
>
> "Apps or third-party code, like SDKs, with interpreted languages (JavaScript, Python, Lua, etc.) loaded at run time (for example, not packaged with the app) must not allow potential violations of Google Play policies."

**关键结论**：

1. ✅ **运行在"解释器/虚拟机"中、仅"间接访问"Android API 的代码，被明确豁免**于"禁止下载可执行代码"条款。一个受控的 JS 引擎 + 受控 JSBridge（白名单 handler、不暴露任意原生 API）= 间接访问 = **合规**。
2. ✅ **包内 JS**（随 APK 打包）完全合规。
3. ⚠️ **运行时下载的 JS**（非包内）允许，但"不得造成 Google Play 政策潜在违规"——即必须沙箱化、不得执行绕过审核的任意代码。属**灰色**，MVP 不触碰。
4. ❌ **WebView + `@JavascriptInterface` 加载不可信 http:// 内容** = 明确违规示例。MVP 不走 WebView 加载远程内容路线。

**真实判例**：React Native CodePush（[GitHub #498](https://github.com/microsoft/react-native-code-push/issues/498)）因 `codePush.sync()` **下载并执行 JS bundle** 被判 Device and Network Abuse 违规，移除后过审。→ **下载+执行远程 JS = 高风险**，MVP 坚决不做。

**国内应用商店**：

- **华为**（[审核指南 2.5](https://developer.huawei.com/consumer/cn/doc/50104)）：禁止"滥用热更新或插件化技术**动态加载恶意代码**"——针对的是"恶意"+"滥用"，合规的包内 JS / 受控沙箱不在禁止之列。
- **小米**：开发者生态政策 + 分级管理，同向。
- ⚠️ **微信小程序**：[明确禁用 JS 解释器](https://developers.weixin.qq.com/community/minihome/doc/0000ae500e4fd0541f2ea33755b801)（禁 `eval`/`new Function`）——但这是**小程序沙箱平台**规则，**不适用于原生 App 上架华为/小米/应用宝**。切勿混淆。

**风险等级矩阵**：

| 行为 | Google Play | 国内商店 | MVP 是否采用 |
|---|---|---|---|
| 执行包内 JS（assets） | ✅ 合规 | ✅ 合规 | ✅ 采用 |
| 受控 JSBridge（白名单 handler） | ✅ 豁免（间接访问） | ✅ 合规 | ✅ 采用 |
| 沙箱阻断 JS→Java 逃逸 | ✅ 降低风险 | ✅ 降低风险 | ✅ 采用 |
| 下载并执行远程 JS | ❌ 违规（CodePush 判例） | ❌ 滥用热更新 | ❌ **不做** |
| WebView 加载不可信 http JS | ❌ 明确违规 | ❌ 违规 | ❌ **不做** |

---

## 3. 已锁定决策

| 决策点 | 选择 | 理由 |
|---|---|---|
| MVP 引擎 | **Rhino**（`org.mozilla:rhino-runtime:1.7.14.1`） | 纯 Java 无 .so；**纯 JVM 可单测**（契合本项目 ~50 个 JVM 单测文化）；零 ABI 风险；明确属于"解释器间接访问"豁免；MPL 2.0 库友好 |
| Rhino 产物 | **`rhino-runtime`**（非 `rhino-engine`） | Android 无 `javax.script`（JSR-223）；`rhino-engine` 依赖 `java.scripting` 会编译/运行失败，`rhino-runtime` 已剥离 ScriptEngine 包装 |
| 架构 | **引擎无关** `JsEngine` 接口 + `RhinoJsEngine` 实现 | 未来换 QuickJS 只改一个适配器，bridge/handler 不变 |
| JS 落点 | **仅包内 JS**（`assets/js/*.js`） | 过审合规；MVP 不碰远程 JS |
| 落点模块 | **`:runtime-core` 新增 `js` 子包**（`com.mamba.picme.agent.core.js`） | 与 `CapabilityRegistry`/`CommandExecutor` 同模块，bridge 可直接引用能力类型；避免新增 Gradle 模块的 plumbing |
| 安全 | **`ClassShutter` 沙箱**阻断 JS→任意 Java/反射 | 合规"间接访问"保证 + 防逃逸 |
| 异步模型 | 同步 handler 为主 + callback 式异步 handler | Rhino 注入宿主对象天然支持同步；异步用 JS 回调函数，原生协程完成后回调 |
| 测试 | JVM 单测为主（Rhino 可纯 JVM 跑） | 项目测试文化；无需设备 |

**非目标（YAGNI，MVP 不做）**：

- ❌ 远程 JS 下载/执行（过审高风险）
- ❌ ES Modules / 完整 CommonJS（用最简 `eval` + 全局 `bridge` 对象）
- ❌ JS↔Capability 完整 dispatch（需 `AgentContext`，过重；MVP 提供 `NativeHandler` SPI，应用层可自行桥接）
- ❌ 独立 `:jsbridge` Gradle 模块（API 稳定后再抽离，见 §11）
- ❌ 性能压测 / JS 调试器 / sourcemap

---

## 4. 架构总览

全部新增落在 `:runtime-core`，`js` 子包。无现有文件改动（除 `build.gradle.kts` 加一行依赖）。

| 文件 | 动作 | 职责 |
|---|---|---|
| `runtime-core/build.gradle.kts` | 改 | `testImplementation`/`implementation` 加 `org.mozilla:rhino-runtime:1.7.14.1` |
| `gradle/libs.versions.toml` | 改 | 加 `rhinoRuntime = "1.7.14.1"` 版本与 library 别名 |
| `js/JsEngine.kt` | 新增 | 引擎无关接口：`eval`、`callFunction`、`injectBridge`、`close` |
| `js/JsValue.kt` | 新增 | JS 值的 Kotlin 投影（sealed：Null/Bool/Num/Str/Obj/Arr） |
| `js/RhinoJsEngine.kt` | 新增 | Rhino 实现 + `ClassShutter` 沙箱 |
| `js/JsBridge.kt` | 新增 | 注入 `bridge` 宿主对象；路由 `bridge.call(name, args[, cb])` 到 `NativeHandler` |
| `js/NativeHandler.kt` | 新增 | handler SPI（`name`、`invoke`，支持同步/异步） |
| `js/BuiltInHandlers.kt` | 新增 | 内置 demo handler：`math.add`、`string.upper`、`log`、`device.info`（只读） |
| `js/JsRuntime.kt` | 新增 | 门面：加载 assets JS + 挂载 bridge + 暴露 `runScript`/`eval` |
| `js/JsBridgeException.kt` | 新增 | 错误类型（handler 未注册/参数错/超时/沙箱违规） |
| `app/src/main/assets/js/picme_bridge_demo.js` | 新增 | 演示脚本，端到端验证 bridge |
| `runtime-core/src/test/.../js/*` | 新增 | JVM 单测：引擎/bridge 同步异步/沙箱/错误 |
| `app` Debug 入口 | 改 | 在 Debug 页加"运行 JS 演示"按钮（i18n 三语），真机肉眼验证 |

### 4.1 分层与依赖方向

```
App (Debug 入口 / 未来 Capability 桥接)
    ↓
JsRuntime (门面)  ──→  BuiltInHandlers
    ↓                       ↑ (注册)
JsBridge ──inject──→ JsEngine(RhinoJsEngine)
    ↓                       │ ClassShutter 沙箱
NativeHandler SPI          JS global: bridge, console
```

- App 只依赖 `JsRuntime` / `JsBridge` / `NativeHandler`（稳定 API）；
- `RhinoJsEngine` 是实现细节，可替换；
- JS 与原生之间**唯一通道**是 `bridge` 对象（受 `ClassShutter` 强制，JS 无法绕过它直接调 Java）。

---

## 5. 组件设计

### 5.1 JsEngine（引擎无关接口）

```kotlin
interface JsEngine : Closeable {
    fun eval(script: String): JsValue
    fun callFunction(name: String, vararg args: Any?): JsValue
    fun injectBridge(bridge: Any)          // 注入宿主对象为 JS 全局
    fun invokeCallback(callbackRef: Any, vararg args: Any?)  // 异步回调 JS
    override fun close()
}
```

### 5.2 JsBridge

- 持有 `JsEngine` 与 `Map<String, NativeHandler>`；
- `register(handler)` 注册；
- 暴露为 JS 全局对象 `bridge`，方法：
  - `bridge.call(name, args)` → 同步：查 handler → `invoke` → 返回结果（JSON 化）；
  - `bridge.callAsync(name, args, callback)` → 异步：handler 返回 `Deferred`/挂起，完成后 `invokeCallback`；
- `bridge.list()` → 返回已注册 handler 名（调试用）。

### 5.3 NativeHandler（SPI）

```kotlin
interface NativeHandler {
    val name: String
    val isAsync: Boolean get() = false
    fun invoke(args: JsValue): JsValue              // 同步
    suspend fun invokeAsync(args: JsValue): JsValue  // 异步（isAsync=true 时用）
}
```

### 5.4 沙箱（ClassShutter）

Rhino 提供 `ClassShutter`：在 JS 试图访问任何 Java 类时被回调。实现为**默认拒绝**：

- 仅放行注入的 `bridge` 相关类；
- 拒绝 `java.*`、`android.*`、`javax.*`、`org.mozilla.javascript.*`、反射相关；
- 同时 `Context.setOptimizationLevel(-1)`（解释模式，避免生成字节码类带来意外）。

→ JS 只能通过 `bridge` 这一受控通道触达原生，构成"间接访问"，满足 Google Play 豁免条款与安全要求。

### 5.5 JsRuntime（门面）

```kotlin
class JsRuntime(coroutineScope: CoroutineScope) : Closeable {
    fun register(handler: NativeHandler)
    fun loadAsset(assetPath: String): JsValue      // 读 assets/js/*.js 并 eval
    fun eval(script: String): JsValue
    fun runDemo(): JsValue                          // 跑 picme_bridge_demo.js
}
```

---

## 6. 数据流

**JS → Native（同步）**：

```
JS: bridge.call("math.add", [1,2])
 → JsBridge.call("math.add", [1,2])
 → handlers["math.add"].invoke([1,2])
 → JsValue.Num(3)
 → Rhino 转 JS number 返回
```

**JS → Native（异步）**：

```
JS: bridge.callAsync("device.info", [], function(err, info){...})
 → JsBridge 在协程内 invokeAsync
 → 完成后 engine.invokeCallback(cb, [null, info])
 → JS 回调执行
```

**Native → JS**：

```
native: engine.callFunction("onAgentEvent", payload)
 → JS 全局函数 onAgentEvent(payload) 被调用
```

---

## 7. 安全与合规清单（实现 + 申报）

- [x] 仅执行包内 JS（`assets/js/`），不从网络拉取；
- [x] `ClassShutter` 默认拒绝，JS 无法逃逸到 Java/Android；
- [x] handler 白名单：JS 只能调已注册 handler，无任意 API 暴露；
- [x] 解释模式（`-1`），不生成可被滥用字节码；
- [x] bridge 调用带超时（复用 `CommandExecutor` 的超时思想）；
- [x] 错误不外泄栈/路径（对外仅返回 `JsBridgeException` 码 + 通用信息）；
- **Play Console 数据安全**：JS 引擎本身不收集/传输数据；若未来 handler 触网需另申报。MVP 不触网。
- **权限**：无新增权限。

---

## 8. 错误处理

| 场景 | 处理 |
|---|---|
| handler 未注册 | `JsBridgeException(HANDLER_NOT_FOUND)` |
| 参数反序列化失败 | `JsBridgeException(INVALID_PARAMS)` |
| handler 抛异常 | 捕获 → `JsBridgeException(HANDLER_ERROR, cause)`；同步返回 `{__error:...}`，异步 `cb(error)` |
| 异步超时 | 协程超时 → `cb(timeoutError)` |
| 沙箱违规（JS 访问禁用类） | Rhino 抛 `SecurityException` → 包装为 `JsBridgeException(SANDBOX_VIOLATION)` |
| JS 语法错误 | `JsBridgeException(SCRIPT_ERROR)` |

---

## 9. 测试策略

纯 JVM 单测（`runtime-core/src/test/.../js/`），无需设备：

- `RhinoJsEngineTest`：eval 算术/字符串；callFunction；注入对象。
- `JsBridgeTest`：注册 → `bridge.call` 同步返回；未注册报错；参数序列化。
- `JsBridgeAsyncTest`：`callAsync` + 回调被调用；超时报错。
- `JsSandboxTest`：JS 尝试 `java.lang.Runtime` / `Packages` 被拒（`SecurityException`）。
- `BuiltInHandlersTest`：`math.add`/`string.upper`/`device.info` 行为。
- `JsRuntimeTest`：加载 assets 演示脚本（用测试 resources 模拟）端到端跑通。

---

## 10. MVP 范围

交付物：

1. 引擎无关抽象（`JsEngine`/`JsValue`）+ Rhino 实现；
2. `JsBridge` 双向（同步 + 异步回调）+ handler SPI；
3. 沙箱（`ClassShutter`）；
4. 内置 handler（math/string/log/device.info）；
5. `JsRuntime` 门面 + 包内演示 JS；
6. JVM 单测（上述 6 组）；
7. App Debug 入口按钮（i18n 三语）触发演示，便于真机肉眼验证；
8. 编译通过 + ktlint/detekt 通过。

**验收**：单测全绿；debug APK 安装后点"运行 JS 演示"能在 logcat 看到 JS 调原生 handler 的往返日志。

---

## 11. 未来演进（非 MVP）

- **换 QuickJS**：新增 `QuickJsEngine : JsEngine` 适配器，bridge/handler 不变；用于需要高 ES 兼容或高吞吐场景。
- **抽离 `:jsbridge` 模块**：API 稳定后从 runtime-core 抽出，clean boundary。
- **JS→Capability dispatch**：实现 `AgentCapabilityHandler`，让 JS 触发 `CapabilityRegistry.dispatch`（需构造 `AgentContext`，按场景灰度）。
- **远程 JS（高合规门槛）**：若确需，须第一方 HTTPS 白名单 + 完整性校验 + 强沙箱 + 数据安全申报；默认不开启。
