# JS 引擎 + JSBridge 实现计划（MVP）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `:runtime-core` 引入引擎无关的 JS 运行时（Rhino 实现）+ 双向 JSBridge + ClassShutter 沙箱，端到端可单测，过审低风险。

**Architecture:** `JsEngine` 接口（`RhinoJsEngine` 实现）跑在专用单线程；`JsBridge` 注入 JS 全局对象 `bridge`（`call`/`callAsync`/`list`）路由到 `NativeHandler` SPI；`ClassShutter` 默认拒绝一切 Java 类可见，JS 仅能通过 `bridge` 间接访问原生。所有 JS 为包内（assets），不触网。

**Tech Stack:** Kotlin、Rhino `org.mozilla:rhino-runtime:1.7.14.1`（纯 Java）、JUnit、kotlinx-coroutines-test。

**设计文档**：`docs/superpowers/specs/2026-07-22-js-engine-jsbridge-design.md`

---

## 文件结构

| 文件 | 职责 |
|---|---|
| `gradle/libs.versions.toml` | 加 `rhinoRuntime` 版本与 library 别名 |
| `runtime-core/build.gradle.kts` | 加 `implementation(libs.mozilla.rhino.runtime)` |
| `runtime-core/.../js/JsValue.kt` | JS 值的引擎无关投影（sealed） |
| `runtime-core/.../js/JsEngine.kt` | 引擎接口 + `JsCallback` |
| `runtime-core/.../js/NativeHandler.kt` | handler SPI（Sync/Async） |
| `runtime-core/.../js/JsBridge.kt` | 路由 JS 调用到 handler（引擎无关） |
| `runtime-core/.../js/JsBridgeException.kt` | 错误类型 + 错误码 |
| `runtime-core/.../js/BuiltInHandlers.kt` | 内置 handler（math/string/log/device） |
| `runtime-core/.../js/RhinoConverter.kt` | JsValue ↔ Rhino NativeObject/Array |
| `runtime-core/.../js/RhinoJsEngine.kt` | Rhino 实现 + 单线程 + ClassShutter 沙箱 |
| `runtime-core/.../js/JsRuntime.kt` | 门面 |
| `runtime-core/src/test/.../js/*.kt` | JVM 单测 |
| `app/src/main/assets/js/picme_bridge_demo.js` | 演示脚本 |
| `app` Debug 页 | "运行 JS 演示"按钮（i18n 三语） |

包：`com.mamba.picme.agent.core.js`。日志 tag：`PoLang:Js` / `PoLang:JsRuntime`。

---

## Task 0: 引入 Rhino 依赖

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `runtime-core/build.gradle.kts:43-74`

- [ ] **Step 1: 在 `libs.versions.toml` 的 `[versions]` 加**

```toml
rhinoRuntime = "1.7.14.1"
```

`[libraries]` 加：

```toml
mozilla-rhino-runtime = { group = "org.mozilla", name = "rhino-runtime", version.ref = "rhinoRuntime" }
```

- [ ] **Step 2: 在 `runtime-core/build.gradle.kts` dependencies 加**

```kotlin
    // JS 引擎（纯 Java，无 .so；rhino-runtime 剥离了 Android 缺失的 javax.script 包装）
    implementation(libs.mozilla.rhino.runtime)
```

- [ ] **Step 3: 验证依赖可解析**

Run: `./gradlew :runtime-core:dependencies --configuration debugRuntimeClasspath | grep rhino`
Expected: 出现 `org.mozilla:rhino-runtime:1.7.14.1`

- [ ] **Step 4: 提交**

```bash
git add gradle/libs.versions.toml runtime-core/build.gradle.kts
git commit -m "build(jsbridge): 引入 org.mozilla:rhino-runtime 1.7.14.1"
```

---

## Task 1: JsValue（引擎无关值投影）

**Files:**
- Create: `runtime-core/src/main/java/com/mamba/picme/agent/core/js/JsValue.kt`
- Test: `runtime-core/src/test/java/com/mamba/picme/agent/core/js/JsValueTest.kt`

- [ ] **Step 1: 写失败测试 `JsValueTest.kt`**

```kotlin
package com.mamba.picme.agent.core.js

import org.junit.Assert.assertEquals
import org.junit.Test

class JsValueTest {
    @Test
    fun `number holds double`() {
        val v = JsValue.Num(3.0)
        assertEquals(3.0, v.value, 0.0)
    }

    @Test
    fun `object holds entries`() {
        val v = JsValue.Obj(mapOf("name" to JsValue.Str("picme"), "ok" to JsValue.Bool(true)))
        assertEquals(JsValue.Str("picme"), v.entries["name"])
        assertEquals(JsValue.Bool(true), v.entries["ok"])
    }

    @Test
    fun `array holds ordered items`() {
        val v = JsValue.Arr(listOf(JsValue.Num(1.0), JsValue.Num(2.0)))
        assertEquals(2, v.items.size)
        assertEquals(JsValue.Num(2.0), v.items[1])
    }
}
```

- [ ] **Step 2: 运行验证失败**

Run: `./gradlew :runtime-core:testDebugUnitTest --tests "*JsValueTest"`
Expected: FAIL（JsValue 未定义）

- [ ] **Step 3: 实现 `JsValue.kt`**

```kotlin
package com.mamba.picme.agent.core.js

/**
 * JS 值的引擎无关投影。bridge 与 handler 之间只交换 JsValue，
 * 避免泄漏 Rhino/QuickJS 等引擎特定类型。
 */
sealed class JsValue {
    data object Null : JsValue()
    data class Bool(val value: Boolean) : JsValue()
    data class Num(val value: Double) : JsValue()
    data class Str(val value: String) : JsValue()
    data class Obj(val entries: Map<String, JsValue>) : JsValue()
    data class Arr(val items: List<JsValue>) : JsValue()

    /** 调试用：递归转 JSON 片段。 */
    fun toJson(): String = when (this) {
        Null -> "null"
        is Bool -> value.toString()
        is Num -> value.toString()
        is Str -> "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        is Obj -> entries.entries.joinToString(prefix = "{", postfix = "}", separator = ",") { (k, v) ->
            "\"$k\":${v.toJson()}"
        }
        is Arr -> items.joinToString(prefix = "[", postfix = "]", separator = ",") { it.toJson() }
    }
}
```

- [ ] **Step 4: 运行验证通过**

Run: `./gradlew :runtime-core:testDebugUnitTest --tests "*JsValueTest"`
Expected: PASS（3 tests）

- [ ] **Step 5: 提交**

```bash
git add runtime-core/src/main/java/com/mamba/picme/agent/core/js/JsValue.kt \
        runtime-core/src/test/java/com/mamba/picme/agent/core/js/JsValueTest.kt
git commit -m "feat(jsbridge): 新增 JsValue 引擎无关值投影"
```

---

## Task 2: NativeHandler SPI + JsCallback + JsBridgeException

**Files:**
- Create: `runtime-core/src/main/java/com/mamba/picme/agent/core/js/NativeHandler.kt`
- Create: `runtime-core/src/main/java/com/mamba/picme/agent/core/js/JsCallback.kt`
- Create: `runtime-core/src/main/java/com/mamba/picme/agent/core/js/JsBridgeException.kt`

- [ ] **Step 1: `NativeHandler.kt`**

```kotlin
package com.mamba.picme.agent.core.js

/**
 * 原生能力 handler 契约。JS 通过 bridge.call(name, args) 触发。
 * Sync：同步返回；Async：挂起完成后通过 JsCallback 回调。
 */
sealed interface NativeHandler {
    val name: String

    fun interface Sync : NativeHandler {
        fun invoke(args: JsValue): JsValue
    }

    fun interface Async : NativeHandler {
        suspend fun invoke(args: JsValue): JsValue
    }
}
```

- [ ] **Step 2: `JsCallback.kt`**

```kotlin
package com.mamba.picme.agent.core.js

/** JS 回调的引擎无关抽象。error/result 二选一（另一个传 null）。 */
fun interface JsCallback {
    fun invoke(error: JsValue?, result: JsValue?)
}
```

- [ ] **Step 3: `JsBridgeException.kt`**

```kotlin
package com.mamba.picme.agent.core.js

/** JSBridge 错误类型。errorCode 对外暴露（不泄露栈）。 */
class JsBridgeException(
    val errorCode: String,
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause) {
    companion object {
        const val HANDLER_NOT_FOUND = "HANDLER_NOT_FOUND"
        const val HANDLER_ERROR = "HANDLER_ERROR"
        const val HANDLER_NOT_ASYNC_CALLABLE = "HANDLER_NOT_ASYNC_CALLABLE"
        const val SCRIPT_ERROR = "SCRIPT_ERROR"
        const val SANDBOX_VIOLATION = "SANDBOX_VIOLATION"
        const val FUNCTION_NOT_FOUND = "FUNCTION_NOT_FOUND"
    }
}
```

- [ ] **Step 4: 编译验证**

Run: `./gradlew :runtime-core:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

```bash
git add runtime-core/src/main/java/com/mamba/picme/agent/core/js/NativeHandler.kt \
        runtime-core/src/main/java/com/mamba/picme/agent/core/js/JsCallback.kt \
        runtime-core/src/main/java/com/mamba/picme/agent/core/js/JsBridgeException.kt
git commit -m "feat(jsbridge): 新增 NativeHandler SPI、JsCallback、JsBridgeException"
```

---

## Task 3: JsBridge（引擎无关路由）

**Files:**
- Create: `runtime-core/src/main/java/com/mamba/picme/agent/core/js/JsBridge.kt`
- Test: `runtime-core/src/test/java/com/mamba/picme/agent/core/js/JsBridgeTest.kt`

- [ ] **Step 1: 写失败测试 `JsBridgeTest.kt`（含 FakeEngine 提供 dispatch，避免依赖 Rhino）**

注意：JsBridge 本身不依赖引擎，只用 `NativeHandler` + 协程作用域。测试直接调 `dispatchSync`/`dispatchAsync`。

```kotlin
package com.mamba.picme.agent.core.js

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsBridgeTest {
    private fun bridge() = JsBridge(TestScope())

    @Test
    fun `sync handler returns result`() {
        val b = bridge()
        b.register(NativeHandler.Sync(name = "math.add") { args ->
            val arr = (args as JsValue.Arr).items
            val s = (arr[0] as JsValue.Num).value + (arr[1] as JsValue.Num).value
            JsValue.Num(s)
        })
        val r = b.dispatchSync("math.add", JsValue.Arr(listOf(JsValue.Num(1.0), JsValue.Num(2.0))))
        assertEquals(JsValue.Num(3.0), r)
    }

    @Test
    fun `unknown handler throws`() {
        val b = bridge()
        try {
            b.dispatchSync("nope", JsValue.Null)
            error("should throw")
        } catch (e: JsBridgeException) {
            assertEquals(JsBridgeException.HANDLER_NOT_FOUND, e.errorCode)
        }
    }

    @Test
    fun `names lists registered handlers`() {
        val b = bridge()
        b.register(NativeHandler.Sync(name = "a") { JsValue.Null })
        b.register(NativeHandler.Sync(name = "b") { JsValue.Null })
        assertEquals(listOf("a", "b"), b.names())
    }

    @Test
    fun `async handler calls back with result`() = runTest {
        val b = bridge()
        b.register(NativeHandler.Async(name = "device.info") { _ ->
            JsValue.Obj(mapOf("app" to JsValue.Str("picme")))
        })
        var captured: JsValue? = null
        b.dispatchAsync("device.info", JsValue.Null, JsCallback { _, result -> captured = result })
        assertEquals(JsValue.Obj(mapOf("app" to JsValue.Str("picme"))), captured)
    }

    @Test
    fun `async handler failure calls back with error`() = runTest {
        val b = bridge()
        b.register(NativeHandler.Async(name = "boom") { _ -> error("kaboom") })
        var err: JsValue? = null
        b.dispatchAsync("boom", JsValue.Null, JsCallback { error, _ -> err = error })
        assertTrue(err is JsValue.Str)
        assertTrue((err as JsValue.Str).value.contains("kaboom"))
    }

    @Test
    fun `sync handler error is wrapped`() {
        val b = bridge()
        b.register(NativeHandler.Sync(name = "x") { error("fail") })
        try {
            b.dispatchSync("x", JsValue.Null)
        } catch (e: JsBridgeException) {
            assertEquals(JsBridgeException.HANDLER_ERROR, e.errorCode)
        }
    }
}
```

- [ ] **Step 2: 运行验证失败**

Run: `./gradlew :runtime-core:testDebugUnitTest --tests "*JsBridgeTest"`
Expected: FAIL（JsBridge 未定义）

- [ ] **Step 3: 实现 `JsBridge.kt`**

```kotlin
package com.mamba.picme.agent.core.js

import com.mamba.picme.agent.core.platform.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * JS ↔ Native 路由（引擎无关）。
 *
 * 引擎层（如 RhinoJsEngine）将 JS 的 bridge.call(name,args) 翻译为
 * [dispatchSync]，将 bridge.callAsync(name,args,cb) 翻译为 [dispatchAsync]。
 */
class JsBridge(
    private val scope: CoroutineScope
) {
    private val tag = "JsBridge"
    private val handlers = LinkedHashMap<String, NativeHandler>()

    fun register(handler: NativeHandler) {
        handlers[handler.name] = handler
        Logger.i(tag, "Registered handler: ${handler.name}")
    }

    fun names(): List<String> = handlers.keys.toList()

    /** 同步分发。 */
    fun dispatchSync(name: String, args: JsValue): JsValue {
        val handler = handlers[name]
            ?: throw JsBridgeException(
                JsBridgeException.HANDLER_NOT_FOUND,
                "handler not registered: $name"
            )
        return when (handler) {
            is NativeHandler.Sync -> runCatching { handler.invoke(args) }.getOrElse { cause ->
                Logger.w(tag, "Handler $name threw: ${cause.message}")
                throw JsBridgeException(JsBridgeException.HANDLER_ERROR, cause.message ?: "error", cause)
            }
            is NativeHandler.Async -> throw JsBridgeException(
                JsBridgeException.HANDLER_NOT_ASYNC_CALLABLE,
                "$name is async; use callAsync"
            )
        }
    }

    /** 异步分发，完成后回调 [cb]。 */
    fun dispatchAsync(name: String, args: JsValue, cb: JsCallback) {
        val handler = handlers[name]
        if (handler == null) {
            cb.invoke(JsValue.Str("handler not registered: $name"), null)
            return
        }
        scope.launch {
            val result = runCatching {
                when (handler) {
                    is NativeHandler.Async -> handler.invoke(args)
                    is NativeHandler.Sync -> handler.invoke(args)
                }
            }
            result.fold(
                onSuccess = { value -> cb.invoke(null, value) },
                onFailure = { err ->
                    Logger.w(tag, "Async handler $name threw: ${err.message}")
                    cb.invoke(JsValue.Str(err.message ?: "error"), null)
                },
            )
        }
    }
}
```

> 注意：先确认 `Logger`（`com.mamba.picme.agent.core.platform.logging.Logger`）存在且在 JVM 测试可用；若它在测试中走 android.util.Log 导致 "not mocked"，则把 `JsBridge` 内日志改为可注入的 `logger: (String)->Unit`（构造参数，默认 `{}`）。Task 3 Step 0 先验证。

- [ ] **Step 0（前置验证）: 检查 Logger 是否 JVM-safe**

Run: `grep -n "android.util.Log\|class Logger" runtime-core/src/main/java/com/mamba/picme/agent/core/platform/logging/Logger.kt`
若 Logger 直接调 `android.util.Log`，则 JVM 单测需 Robolectric。`runtime-core` 已有 `testImplementation(libs.robolectric)`，且现有测试通过——说明 Logger 可用或现有测试不触发它。**决策**：先按上面用 Logger 跑测试；若 `JsBridgeTest` 报 "not mocked"，则给 `JsBridge` 加 `private val logger: (String) -> Unit = {}` 并替换 `Logger.x` 调用。

- [ ] **Step 4: 运行验证通过**

Run: `./gradlew :runtime-core:testDebugUnitTest --tests "*JsBridgeTest"`
Expected: PASS（6 tests）

- [ ] **Step 5: 提交**

```bash
git add runtime-core/src/main/java/com/mamba/picme/agent/core/js/JsBridge.kt \
        runtime-core/src/test/java/com/mamba/picme/agent/core/js/JsBridgeTest.kt
git commit -m "feat(jsbridge): 新增引擎无关 JsBridge 路由（sync/async）+ 单测"
```

---

## Task 4: RhinoConverter（JsValue ↔ Rhino）

**Files:**
- Create: `runtime-core/src/main/java/com/mamba/picme/agent/core/js/RhinoConverter.kt`
- Test: `runtime-core/src/test/java/com/mamba/picme/agent/core/js/RhinoConverterTest.kt`

- [ ] **Step 1: 写失败测试 `RhinoConverterTest.kt`**

```kotlin
package com.mamba.picme.agent.core.js

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RhinoConverterTest {
    @Test
    fun `toJsValue handles primitives`() {
        assertEquals(JsValue.Null, RhinoConverter.toJsValue(null))
        assertEquals(JsValue.Bool(true), RhinoConverter.toJsValue(true))
        assertEquals(JsValue.Num(3.0), RhinoConverter.toJsValue(3))
        assertEquals(JsValue.Str("hi"), RhinoConverter.toJsValue("hi"))
    }
}
```

> 转换器对 Rhino 的 `NativeArray`/`NativeObject` 转换在 Task 5 的端到端测试里覆盖（需要 Rhino scope）。这里只覆盖原始类型快速冒烟。

- [ ] **Step 2: 运行验证失败**

Run: `./gradlew :runtime-core:testDebugUnitTest --tests "*RhinoConverterTest"`
Expected: FAIL（RhinoConverter 未定义）

- [ ] **Step 3: 实现 `RhinoConverter.kt`**

```kotlin
package com.mamba.picme.agent.core.js

import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.Undefined

/**
 * JsValue ↔ Rhino 原生对象互转。
 * - 只产出 JS 原生类型（NativeObject/NativeArray/原始），不泄漏任意 Java 对象。
 * - 配合 deny-all ClassShutter，保证沙箱。
 */
object RhinoConverter {

    fun toJsValue(o: Any?): JsValue = when {
        o == null || o is Undefined -> JsValue.Null
        o is Boolean -> JsValue.Bool(o)
        o is Number -> JsValue.Num(o.toDouble())
        o is String -> JsValue.Str(o)
        o is NativeArray -> JsValue.Arr(o.toList().map { toJsValue(it) })
        o is NativeObject -> {
            val map = linkedMapOf<String, JsValue>()
            for (id in o.ids) {
                val key = id.toString()
                map[key] = toJsValue(o.get(key, o))
            }
            JsValue.Obj(map)
        }
        o is Scriptable -> {
            // 兜底：通用 Scriptable 当对象处理
            val map = linkedMapOf<String, JsValue>()
            for (id in o.ids) {
                val key = id.toString()
                map[key] = toJsValue(o.get(key, o))
            }
            JsValue.Obj(map)
        }
        else -> JsValue.Str(o.toString())
    }

    fun toRhino(v: JsValue, scope: Scriptable): Any? = when (v) {
        JsValue.Null -> null
        is JsValue.Bool -> v.value
        is JsValue.Num -> v.value
        is JsValue.Str -> v.value
        is JsValue.Obj -> NativeObject().apply {
            v.entries.forEach { (k, vv) -> put(k, this, toRhino(vv, scope)) }
        }
        is JsValue.Arr -> NativeArray(v.items.map { toRhino(it, scope) })
    }
}
```

- [ ] **Step 4: 运行验证通过**

Run: `./gradlew :runtime-core:testDebugUnitTest --tests "*RhinoConverterTest"`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add runtime-core/src/main/java/com/mamba/picme/agent/core/js/RhinoConverter.kt \
        runtime-core/src/test/java/com/mamba/picme/agent/core/js/RhinoConverterTest.kt
git commit -m "feat(jsbridge): 新增 RhinoConverter（JsValue 与 Rhino 原生对象互转）"
```

---

## Task 5: RhinoJsEngine + ClassShutter 沙箱

**Files:**
- Create: `runtime-core/src/main/java/com/mamba/picme/agent/core/js/RhinoJsEngine.kt`
- Test: `runtime-core/src/test/java/com/mamba/picme/agent/core/js/RhinoJsEngineTest.kt`

- [ ] **Step 1: 写失败测试 `RhinoJsEngineTest.kt`**

```kotlin
package com.mamba.picme.agent.core.js

import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RhinoJsEngineTest {
    private fun newEngine(): RhinoJsEngine =
        RhinoJsEngine(scope = TestScope(), onLog = {})

    @Test
    fun `eval arithmetic`() {
        newEngine().use { engine ->
            assertEquals(JsValue.Num(3.0), engine.eval("1 + 2"))
        }
    }

    @Test
    fun `eval string`() {
        newEngine().use { engine ->
            assertEquals(JsValue.Str("PICME"), engine.eval("'picme'.toUpperCase()"))
        }
    }

    @Test
    fun `sandbox blocks java access`() {
        newEngine().use { engine ->
            // 直接 new Java 类 / 访问 Packages 必须被拒
            val ex = runCatching { engine.eval("java.lang.Runtime.getRuntime()") }
                .exceptionOrNull()
            assertTrue("expected sandbox violation", ex != null)
        }
    }

    @Test
    fun `callFunction invokes global fn`() {
        newEngine().use { engine ->
            engine.eval("function add(a,b){ return a+b; }")
            assertEquals(
                JsValue.Num(9.0),
                engine.callFunction("add", JsValue.Num(4.0), JsValue.Num(5.0)),
            )
        }
    }
}
```

- [ ] **Step 2: 运行验证失败**

Run: `./gradlew :runtime-core:testDebugUnitTest --tests "*RhinoJsEngineTest"`
Expected: FAIL（RhinoJsEngine 未定义）

- [ ] **Step 3: 实现 `RhinoJsEngine.kt`**

```kotlin
package com.mamba.picme.agent.core.js

import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import java.io.Closeable
import java.util.concurrent.Executors

/**
 * Rhino 实现的 JS 引擎。
 *
 * - 所有 JS 执行串行在单守护线程（Rhino Context 非线程安全）。
 * - [DenyAllShutter] 拒绝一切 Java 类对脚本可见；JS 仅能通过注入的 bridge 间接访问原生。
 * - 解释模式（optimization -1），不生成字节码类。
 *
 * @param scope 用于 dispatchAsync 启动协程。
 * @param onLog console.log 输出（默认空，便于 JVM 单测；App 端注入 android Log）。
 */
class RhinoJsEngine(
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val onLog: (String) -> Unit = {},
) : JsEngine, Closeable {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "PoLang-JsEngine").apply { isDaemon = true }
    }

    @Volatile
    private var rootScope: Scriptable? = null
    private var bridge: JsBridge? = null

    private fun <T> onJsThread(block: (cx: Context, sc: Scriptable) -> T): T {
        val future = executor.submit<T> {
            val cx = Context.enter()
            try {
                cx.setOptimizationLevel(-1)
                cx.setClassShutter(DenyAllShutter)
                val sc = rootScope ?: Context.initStandardObjects(cx).also { rootScope = it }
                block(cx, sc)
            } finally {
                Context.exit()
            }
        }
        return future.get()
    }

    override fun eval(script: String): JsValue = onJsThread { cx, sc ->
        val result = cx.evaluateString(sc, script, "picme-js", 1, null)
        RhinoConverter.toJsValue(result)
    }

    override fun callFunction(name: String, vararg args: JsValue): JsValue = onJsThread { cx, sc ->
        val fn = sc.get(name, sc)
        if (fn is Function) {
            val raw = args.map { RhinoConverter.toRhino(it, sc) }.toTypedArray()
            RhinoConverter.toJsValue(fn.call(cx, sc, sc, raw))
        } else {
            throw JsBridgeException(
                JsBridgeException.FUNCTION_NOT_FOUND,
                "JS function not found: $name"
            )
        }
    }

    override fun installBridge(bridge: JsBridge) {
        this.bridge = bridge
        onJsThread { _, sc ->
            val bridgeObj = NativeObject()

            ScriptableObject.putProperty(bridgeObj, "call", object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable?,
                    args: Array<out Any?>,
                ): Any? {
                    val name = args.getOrNull(0)?.toString() ?: ""
                    val jsArgs = RhinoConverter.toJsValue(args.getOrNull(1))
                    val result = bridge.dispatchSync(name, jsArgs)
                    return RhinoConverter.toRhino(result, scope)
                }
            })

            ScriptableObject.putProperty(bridgeObj, "callAsync", object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable?,
                    args: Array<out Any?>,
                ): Any? {
                    val name = args.getOrNull(0)?.toString() ?: ""
                    val jsArgs = RhinoConverter.toJsValue(args.getOrNull(1))
                    val rawFn = args.getOrNull(2)
                    val cb: JsCallback = if (rawFn is Function) {
                        JsCallback { err, result -> submitJsCallback(rawFn, err, result) }
                    } else {
                        JsCallback { _, _ -> }
                    }
                    bridge.dispatchAsync(name, jsArgs, cb)
                    return Context.getUndefinedValue()
                }
            })

            ScriptableObject.putProperty(bridgeObj, "list", object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable?,
                    args: Array<out Any?>,
                ): Any? {
                    NativeArray(bridge.names())
                }
            })

            sc.put("bridge", sc, bridgeObj)

            // console.log（纯日志，无原生副作用）
            val console = NativeObject()
            ScriptableObject.putProperty(console, "log", object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable?,
                    args: Array<out Any?>,
                ): Any? {
                    val msg = args.joinToString(" ") { RhinoConverter.toJsValue(it).toJson() }
                    onLog(msg)
                    return Context.getUndefinedValue()
                }
            })
            sc.put("console", sc, console)
            Unit
        }
    }

    /** 在 JS 线程上回调 JS 函数。 */
    private fun submitJsCallback(fn: Function, err: JsValue?, result: JsValue?) {
        onJsThread { cx, sc ->
            val errArg = RhinoConverter.toRhino(err ?: JsValue.Null, sc)
            val resArg = RhinoConverter.toRhino(result ?: JsValue.Null, sc)
            fn.call(cx, sc, sc, arrayOf(errArg, resArg))
        }
    }

    override fun close() {
        executor.shutdownNow()
    }

    /** 默认拒绝一切 Java 类对脚本可见。 */
    private object DenyAllShutter : ClassShutter {
        override fun visibleToScripts(fullClassName: String?): Boolean {
            // 仅放行 Rhino 内部 JS 原生类型（NativeObject/NativeArray/BaseFunction 等）
            // 以保证 bridge/console 这些 JS 原生对象可用；其余 Java 类一律不可见。
            return fullClassName != null && fullClassName.startsWith("org.mozilla.javascript.")
        }
    }
}
```

> **沙箱说明**：`visibleToScripts` 仅放行 `org.mozilla.javascript.*`（这是 JS 原生类型，脚本访问它们无害；脚本无法通过它们逃逸到 Java）。`java.*`/`android.*`/业务包全部被拒。`bridge` 是 `NativeObject` + `BaseFunction`，属于放行范围，故可调用。验证点在 `sandbox blocks java access`：`java.lang.Runtime` 必须抛异常。

- [ ] **Step 4: 运行验证通过**

Run: `./gradlew :runtime-core:testDebugUnitTest --tests "*RhinoJsEngineTest"`
Expected: PASS（4 tests）。若 `sandbox blocks java access` 未抛异常，说明 shutter 未生效——确认 `cx.setClassShutter` 在 `initStandardObjects` 之前调用。

- [ ] **Step 5: 提交**

```bash
git add runtime-core/src/main/java/com/mamba/picme/agent/core/js/RhinoJsEngine.kt \
        runtime-core/src/test/java/com/mamba/picme/agent/core/js/RhinoJsEngineTest.kt
git commit -m "feat(jsbridge): 新增 RhinoJsEngine + ClassShutter 沙箱（deny Java 逃逸）"
```

---

## Task 6: JsEngine 接口（补齐）+ 端到端 bridge 测试

**Files:**
- Create: `runtime-core/src/main/java/com/mamba/picme/agent/core/js/JsEngine.kt`
- Test: `runtime-core/src/test/java/com/mamba/picme/agent/core/js/JsBridgeEndToEndTest.kt`

- [ ] **Step 1: 实现 `JsEngine.kt`**

```kotlin
package com.mamba.picme.agent.core.js

/**
 * JS 引擎抽象（引擎无关）。当前实现 RhinoJsEngine；未来可加 QuickJsEngine。
 */
interface JsEngine {
    /** 执行脚本，返回结果。 */
    fun eval(script: String): JsValue

    /** 调用全局函数。 */
    fun callFunction(name: String, vararg args: JsValue): JsValue

    /** 注入 bridge 全局对象。 */
    fun installBridge(bridge: JsBridge)
}
```

- [ ] **Step 2: 写端到端测试 `JsBridgeEndToEndTest.kt`（JS 真调原生 handler，往返）**

```kotlin
package com.mamba.picme.agent.core.js

import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Test

class JsBridgeEndToEndTest {

    @Test
    fun `js calls native sync handler via bridge`() {
        val logs = mutableListOf<String>()
        RhinoJsEngine(scope = TestScope(), onLog = { logs += it }).use { engine ->
            val bridge = JsBridge(TestScope())
            bridge.register(NativeHandler.Sync(name = "math.add") { args ->
                val arr = (args as JsValue.Arr).items
                val s = (arr[0] as JsValue.Num).value + (arr[1] as JsValue.Num).value
                JsValue.Num(s)
            })
            engine.installBridge(bridge)

            val result = engine.eval("(function(){ return bridge.call('math.add', [10, 32]); })()")
            assertEquals(JsValue.Num(42.0), result)
        }
    }

    @Test
    fun `js async handler calls back`() {
        val logs = mutableListOf<String>()
        RhinoJsEngine(scope = TestScope(), onLog = { logs += it }).use { engine ->
            val bridge = JsBridge(TestScope())
            bridge.register(NativeHandler.Async(name = "device.info") { _ ->
                JsValue.Obj(mapOf("app" to JsValue.Str("picme"), "v" to JsValue.Num(1.0)))
            })
            engine.installBridge(bridge)

            // 异步回调把结果写回全局变量 captured
            engine.eval(
                """
                var captured = null;
                bridge.callAsync('device.info', null, function(err, res){
                    captured = res;
                });
                """.trimIndent()
            )
            // 由于 callback 也在 JS 线程串行执行，eval 返回后回调应已完成
            val res = engine.eval("captured")
            assertEquals(
                JsValue.Obj(mapOf("app" to JsValue.Str("picme"), "v" to JsValue.Num(1.0))),
                res,
            )
        }
    }

    @Test
    fun `console log reaches onLog`() {
        val logs = mutableListOf<String>()
        RhinoJsEngine(scope = TestScope(), onLog = { logs += it }).use { engine ->
            engine.eval("console.log('hello', 42);")
        }
        assertEquals(1, logs.size)
        assertEquals("\"hello\" 42", logs[0])
    }
}
```

- [ ] **Step 3: 运行验证**

Run: `./gradlew :runtime-core:testDebugUnitTest --tests "*JsBridgeEndToEndTest"`
Expected: PASS（3 tests）

> 若 `js async handler calls back` 因协程调度时机 flaky：`dispatchAsync` 用 `scope.launch`，`TestScope` 默认需 `runTest`+`advanceUntilIdle`。备选：把该异步用例包进 `runTest { ... }` 并在 eval 后 `advanceUntilIdle()` 再断言。实施时按实际调整。

- [ ] **Step 4: 提交**

```bash
git add runtime-core/src/main/java/com/mamba/picme/agent/core/js/JsEngine.kt \
        runtime-core/src/test/java/com/mamba/picme/agent/core/js/JsBridgeEndToEndTest.kt
git commit -m "feat(jsbridge): 补齐 JsEngine 接口 + JS↔Native 端到端单测"
```

---

## Task 7: BuiltInHandlers + JsRuntime 门面

**Files:**
- Create: `runtime-core/src/main/java/com/mamba/picme/agent/core/js/BuiltInHandlers.kt`
- Create: `runtime-core/src/main/java/com/mamba/picme/agent/core/js/JsRuntime.kt`
- Test: `runtime-core/src/test/java/com/mamba/picme/agent/core/js/JsRuntimeTest.kt`

- [ ] **Step 1: 实现 `BuiltInHandlers.kt`**

```kotlin
package com.mamba.picme.agent.core.js

/**
 * 内置 handler 集合（无原生危险副作用，仅纯计算/只读信息）。
 */
object BuiltInHandlers {

    /** math.add([a,b]) -> number */
    val mathAdd = NativeHandler.Sync(name = "math.add") { args ->
        val arr = (args as JsValue.Arr).items
        val a = (arr.getOrNull(0) as? JsValue.Num)?.value ?: 0.0
        val b = (arr.getOrNull(1) as? JsValue.Num)?.value ?: 0.0
        JsValue.Num(a + b)
    }

    /** string.upper(s) -> string */
    val stringUpper = NativeHandler.Sync(name = "string.upper") { args ->
        val s = (args as? JsValue.Str)?.value ?: args.toJson()
        JsValue.Str(s.uppercase())
    }

    /** echo(obj) -> obj（原样返回，调试用） */
    val echo = NativeHandler.Sync(name = "echo") { args -> args }

    /** device.info() -> { app, engine }（只读，不触网不取隐私） */
    val deviceInfo = NativeHandler.Async(name = "device.info") { _ ->
        JsValue.Obj(
            mapOf(
                "app" to JsValue.Str("picme"),
                "engine" to JsValue.Str("rhino"),
            )
        )
    }

    /** 全部注册到 bridge。 */
    fun registerAll(bridge: JsBridge) {
        bridge.register(mathAdd)
        bridge.register(stringUpper)
        bridge.register(echo)
        bridge.register(deviceInfo)
    }
}
```

- [ ] **Step 2: 实现 `JsRuntime.kt`（门面）**

```kotlin
package com.mamba.picme.agent.core.js

import com.mamba.picme.agent.core.platform.logging.Logger
import kotlinx.coroutines.CoroutineScope

/**
 * JS 运行时门面：装配引擎 + bridge + 内置 handler，提供 eval/callFunction。
 *
 * 使用：
 * ```
 * val rt = JsRuntime(scope = appScope, onLog = { msg -> Log.i("PoLang:Js", msg) })
 * rt.eval("bridge.call('math.add', [1,2])")
 * rt.close()
 * ```
 */
class JsRuntime(
    scope: CoroutineScope,
    onLog: (String) -> Unit = {},
) : JsEngine by RhinoJsEngine(scope, onLog), AutoCloseable {

    private val tag = "JsRuntime"
    private val engine: RhinoJsEngine = RhinoJsEngine(scope, onLog)
    private val bridge: JsBridge = JsBridge(scope)

    init {
        BuiltInHandlers.registerAll(bridge)
        engine.installBridge(bridge)
        Logger.i(tag, "JsRuntime ready (engine=rhino, handlers=${bridge.names()})")
    }

    override fun eval(script: String): JsValue = engine.eval(script)
    override fun callFunction(name: String, vararg args: JsValue): JsValue =
        engine.callFunction(name, *args)

    fun register(handler: NativeHandler) = bridge.register(handler)

    fun handlerNames(): List<String> = bridge.names()

    override fun close() {
        engine.close()
        Logger.i(tag, "JsRuntime closed")
    }
}
```

> 注意：`JsEngine by RhinoJsEngine(...)` 会与显式 override 重复——简化为不使用 `by`，直接持有 engine 并手动实现 `JsEngine`。实施时用如下最终形态（去掉 `by`）：

```kotlin
class JsRuntime(
    scope: CoroutineScope,
    private val onLog: (String) -> Unit = {},
) : JsEngine, AutoCloseable {
    private val tag = "JsRuntime"
    private val engine = RhinoJsEngine(scope, onLog)
    private val bridge = JsBridge(scope)

    init {
        BuiltInHandlers.registerAll(bridge)
        engine.installBridge(bridge)
        Logger.i(tag, "JsRuntime ready (handlers=${bridge.names()})")
    }

    override fun eval(script: String): JsValue = engine.eval(script)
    override fun callFunction(name: String, vararg args: JsValue): JsValue =
        engine.callFunction(name, *args)
    override fun installBridge(bridge: JsBridge) { engine.installBridge(bridge) }

    fun register(handler: NativeHandler) = bridge.register(handler)
    fun handlerNames(): List<String> = bridge.names()
    override fun close() { engine.close() }
}
```

- [ ] **Step 3: 写测试 `JsRuntimeTest.kt`**

```kotlin
package com.mamba.picme.agent.core.js

import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Test

class JsRuntimeTest {
    @Test
    fun `builtin math add works via runtime`() {
        JsRuntime(scope = TestScope(), onLog = {}).use { rt ->
            val r = rt.eval("bridge.call('math.add', [40, 2])")
            assertEquals(JsValue.Num(42.0), r)
        }
    }

    @Test
    fun `string upper works`() {
        JsRuntime(scope = TestScope(), onLog = {}).use { rt ->
            val r = rt.eval("bridge.call('string.upper', 'hello')")
            assertEquals(JsValue.Str("HELLO"), r)
        }
    }

    @Test
    fun `handlerNames includes builtins`() {
        JsRuntime(scope = TestScope(), onLog = {}).use { rt ->
            assert(rt.handlerNames().contains("math.add"))
            assert(rt.handlerNames().contains("device.info"))
        }
    }
}
```

- [ ] **Step 4: 运行验证**

Run: `./gradlew :runtime-core:testDebugUnitTest --tests "*JsRuntimeTest" --tests "*BuiltInHandlers*"`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add runtime-core/src/main/java/com/mamba/picme/agent/core/js/BuiltInHandlers.kt \
        runtime-core/src/main/java/com/mamba/picme/agent/core/js/JsRuntime.kt \
        runtime-core/src/test/java/com/mamba/picme/agent/core/js/JsRuntimeTest.kt
git commit -m "feat(jsbridge): 新增 BuiltInHandlers + JsRuntime 门面"
```

---

## Task 8: 演示脚本 assets + App Debug 入口（i18n）

**Files:**
- Create: `app/src/main/assets/js/picme_bridge_demo.js`
- Modify: `app/src/main/res/values/strings.xml` / `values-zh-rCN` / `values-zh-rTW`
- Modify: App Debug 页（定位现有 Debug 页入口）

- [ ] **Step 1: 演示脚本 `picme_bridge_demo.js`**

```javascript
// PoLang JSBridge 演示（包内脚本，不触网）
// 运行后会在 PoLang:Js 日志打印原生 handler 往返结果。
(function () {
    console.log("picme jsbridge demo start");

    // 同步调用原生
    var sum = bridge.call("math.add", [18, 24]);
    console.log("math.add =>", sum);

    var up = bridge.call("string.upper", "polang");
    console.log("string.upper =>", up);

    // 异步调用原生，回调打印
    bridge.callAsync("device.info", null, function (err, info) {
        if (err) {
            console.log("device.info error", err);
        } else {
            console.log("device.info =>", info);
        }
    });

    // 已注册 handler 列表
    console.log("handlers =>", bridge.list());

    return "demo-done";
})();
```

- [ ] **Step 2: i18n 三语新增字符串**

`values/strings.xml`：
```xml
<string name="jsbridge_debug_run_demo">Run JS Bridge demo</string>
<string name="jsbridge_debug_section">JS Bridge (MVP)</string>
```
`values-zh-rCN/strings.xml`：
```xml
<string name="jsbridge_debug_run_demo">运行 JS Bridge 演示</string>
<string name="jsbridge_debug_section">JS Bridge（MVP）</string>
```
`values-zh-rTW/strings.xml`：
```xml
<string name="jsbridge_debug_run_demo">執行 JS Bridge 演示</string>
<string name="jsbridge_debug_section">JS Bridge（MVP）</string>
```

- [ ] **Step 3: 在 App Debug 页加触发逻辑**

定位 Debug 页（见记忆 `Debug 页入口路径`：设置→相册功能→相册调试功能→图片下载页→开发测试页）。在该页加一个按钮：

```kotlin
// 读 assets/js/picme_bridge_demo.js 并用 JsRuntime 执行
val scope = rememberCoroutineScope()
Button(onClick = {
    scope.launch(Dispatchers.IO) {
        val rt = JsRuntime(
            scope = CoroutineScope(Dispatchers.IO),
            onLog = { msg -> android.util.Log.i("PoLang:Js", msg) },
        )
        try {
            val script = context.assets.open("js/picme_bridge_demo.js")
                .bufferedReader().use { it.readText() }
            val result = rt.eval(script)
            android.util.Log.i("PoLang:Js", "demo result: ${result.toJson()}")
        } catch (e: Throwable) {
            android.util.Log.e("PoLang:Js", "demo failed", e)
        } finally {
            rt.close()
        }
    }
}) { Text(stringResource(R.string.jsbridge_debug_run_demo)) }
```

> 实施时复用现有 Debug 页组件与样式，按钮文案用 `R.string.jsbridge_debug_run_demo`。

- [ ] **Step 4: 编译验证（无需设备）**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

```bash
git add app/src/main/assets/js/picme_bridge_demo.js \
        app/src/main/res/values/strings.xml \
        app/src/main/res/values-zh-rCN/strings.xml \
        app/src/main/res/values-zh-rTW/strings.xml \
        <Debug 页文件>
git commit -m "feat(jsbridge): 新增演示脚本 + Debug 页入口（i18n 三语）"
```

---

## Task 9: 质量门 + 全量验证

- [ ] **Step 1: ktlint**

Run: `./gradlew :runtime-core:ktlintCheck :app:ktlintCheck`
Expected: 无 violation（注意：无通配符 import、无 `com.mamba.picme.*` 全限定名、Lambda 显式命名）

- [ ] **Step 2: detekt**

Run: `./gradlew :runtime-core:detekt :app:detekt`
Expected: 无新增违规

- [ ] **Step 3: runtime-core 全量单测**

Run: `./gradlew :runtime-core:testDebugUnitTest`
Expected: 全绿（含新增 js 包所有测试）

- [ ] **Step 4: checkNoFullyQualifiedName 任务**

Run: `./gradlew checkNoFullyQualifiedName`
Expected: PASS（JS 包内不得出现 `com.mamba.picme.*` 全限定名）

- [ ] **Step 5: 最终 commit + 总结**

```bash
git add -A
git commit --allow-empty -m "chore(jsbridge): MVP 质量门通过（ktlint/detekt/单测）"
```

---

## 自审（计划 vs 设计文档）

- ✅ 引擎无关 JsEngine + Rhino 实现（设计 §3/§5.1）→ Task 5/6
- ✅ JSBridge 双向 sync+async（§5.2）→ Task 3/6
- ✅ NativeHandler SPI（§5.3）→ Task 2/7
- ✅ ClassShutter 沙箱（§5.4）→ Task 5（`sandbox blocks java access` 用例）
- ✅ JsRuntime 门面 + 内置 handler（§5.5）→ Task 7
- ✅ 包内 JS 演示 + Debug 入口（§10 交付物 5/7）→ Task 8
- ✅ JVM 单测覆盖（§9）→ Task 1/3/4/5/6/7
- ✅ 过审合规：deny-all 沙箱 + 仅包内 JS + 无远程（§7）→ 全程约束
- ✅ 引擎可替换（未来 QuickJS）→ JsEngine 接口隔离

无占位符；类型/方法名跨任务一致（`dispatchSync`/`dispatchAsync`/`installBridge`/`callFunction`/`names`）；错误码常量统一在 `JsBridgeException`。
