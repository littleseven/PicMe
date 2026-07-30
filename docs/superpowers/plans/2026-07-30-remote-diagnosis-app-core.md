# Remote Diagnosis (App Core) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the app-side **data layer** for remote diagnosis: collect a sanitized text diagnostic bundle, and an HTTP client that reports it / polls status / confirms fix against the picme server's `/diag/*` API.

**Architecture:** `DiagBundleCollector` reads the existing `Logger.logs` ring buffer (+ injected version/gitSha/device info) and runs `DiagSanitizer` (regex redaction of emails/tokens/paths/coords). `DiagClient` (OkHttp + org.json, mirroring `PoLangAuthClient`) talks to `https://api.polang.net/diag/*` with `X-App-Token`. All pure-Kotlin / JVM-testable; no Android UI in this plan (chat integration is a separate plan).

**Tech Stack:** Kotlin, OkHttp, org.json, JUnit4.

**Spec:** `docs/superpowers/specs/2026-07-30-remote-diagnosis-design.md` (§2 手机端, §6.1). Server API contract is locked by the already-implemented+tested server plan (`DiagRoute`).

**Scope note:** App plan 1 of 2 (core data layer). Plan 2 = chat UI integration (`ChatViewModel.submitDiagnosis` + `ChatInputArea` 「诊断」入口 + 根因/确认展示). This plan is independently testable.

---

## File Structure

**Create:**
- `app/src/main/java/com/mamba/picme/core/diag/DiagSanitizer.kt` — regex redaction
- `app/src/main/java/com/mamba/picme/core/diag/DiagBundle.kt` — `DiagBundle` + `DiagJobStatus` data classes + JSON helper
- `app/src/main/java/com/mamba/picme/core/diag/DiagBundleCollector.kt` — assemble + sanitize bundle from `Logger.logs`
- `app/src/main/java/com/mamba/picme/data/remote/picme/DiagClient.kt` — HTTP client (mirror `PoLangAuthClient`)
- `app/src/test/java/com/mamba/picme/core/diag/DiagSanitizerTest.kt`
- `app/src/test/java/com/mamba/picme/core/diag/DiagBundleCollectorTest.kt`
- `app/src/test/java/com/mamba/picme/data/remote/picme/DiagClientTest.kt`

**Modify:**
- `app/build.gradle.kts` — add `GIT_SHA` `buildConfigField` (so the collector caller can pass `BuildConfig.GIT_SHA`)

---

## Task 1: GIT_SHA buildConfigField

**Files:**
- Modify: `app/build.gradle.kts` (inside `android { defaultConfig { ... } }`)

- [ ] **Step 1: Add a git-sha helper + buildConfigField**

In `app/build.gradle.kts`, add near the top of the file (after `plugins {}`, before/after `android {}` — anywhere at file scope):

```kotlin
fun gitShortSha(): String = try {
    val proc = Runtime.getRuntime().exec(arrayOf("git", "rev-parse", "--short", "HEAD"), emptyArray(), rootDir)
    proc.inputStream.bufferedReader().readText().trim().ifEmpty { "unknown" }
} catch (e: Exception) {
    "unknown"
}
```

Inside `android { defaultConfig { ... } }` (alongside the existing `buildConfigField` lines), add:

```kotlin
        buildConfigField("String", "GIT_SHA", "\"${gitShortSha()}\"")
```

- [ ] **Step 2: Verify it compiles + generates the field**

Run: `./gradlew :app:assembleDebug 2>&1 | tail -5` (or at minimum `:app:compileDebugKotlin`)
Expected: BUILD SUCCESSFUL. Then confirm the field exists:

```bash
grep -r "String GIT_SHA" app/build/generated/source/buildConfig/ 2>/dev/null | head -1
```
Expected: a line like `public static final String GIT_SHA = "<sha>";`.

- [ ] **Step 3: Commit**

```bash
git add app/build.gradle.kts
git commit -m "build(app): 注入 BuildConfig.GIT_SHA 供远程诊断上报构建版本"
```

> Note: `:app:assembleDebug` may be slow / hit unrelated env failures (MNN native etc., per known test-env pitfalls). If it fails for reasons unrelated to this one-line change, fall back to `:app:compileDebugKotlin` + the grep check as verification.

---

## Task 2: DiagSanitizer (regex redaction)

**Files:**
- Create: `app/src/main/java/com/mamba/picme/core/diag/DiagSanitizer.kt`
- Test: `app/src/test/java/com/mamba/picme/core/diag/DiagSanitizerTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/mamba/picme/core/diag/DiagSanitizerTest.kt`:

```kotlin
package com.mamba.picme.core.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagSanitizerTest {

    @Test
    fun `redacts email addresses`() {
        assertEquals("user <email> logged in", DiagSanitizer.sanitize("user a@b.com logged in"))
    }

    @Test
    fun `redacts pl app tokens`() {
        assertEquals("auth=<token>", DiagSanitizer.sanitize("auth=pl-0123456789abcdef0123456789abcdef"))
    }

    @Test
    fun `redacts absolute media and filesystem paths`() {
        val out = DiagSanitizer.sanitize("saved /storage/emulated/0/DCIM/IMG.jpg and /data/data/com.mamba.picme/x")
        assertTrue("path redacted: $out", !out.contains("/storage/") && !out.contains("/data/data/"))
    }

    @Test
    fun `redacts content uris`() {
        val out = DiagSanitizer.sanitize("loaded content://media/external/images/media/42")
        assertEquals("loaded <path>", out)
    }

    @Test
    fun `redacts gps coordinate pairs`() {
        val out = DiagSanitizer.sanitize("loc=31.23040,121.47370")
        assertEquals("loc=<coord>", out)
    }

    @Test
    fun `leaves clean log text unchanged`() {
        val clean = "PoLang:Camera Preview started at 30fps"
        assertEquals(clean, DiagSanitizer.sanitize(clean))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.core.diag.DiagSanitizerTest" 2>&1 | tail -15`
Expected: COMPILE FAIL — `DiagSanitizer` unresolved.

- [ ] **Step 3: Create DiagSanitizer**

Create `app/src/main/java/com/mamba/picme/core/diag/DiagSanitizer.kt`:

```kotlin
package com.mamba.picme.core.diag

/**
 * 诊断包脱敏（ADR-008 红线守门）：把日志里的邮箱、App Token、文件/media 路径、
 * content uri、GPS 坐标替换为占位符。诊断包是纯文本，绝不含图片/视频字节。
 *
 * 注：人名是任意自由文本、无法可靠识别；日志一般不含人名（人名存于本地 DB），
 * 故 MVP 不做人名 redaction，留待二期按需处理。
 */
object DiagSanitizer {
    private val email = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val token = Regex("pl-[0-9a-fA-F]{16,}")
    private val contentUri = Regex("content://[\\w./-]+")
    private val absPath = Regex("/(?:storage|sdcard|data|mnt|var|tmp|Users|home)(?:/[^\\s\"]*)?")
    private val coord = Regex("-?\\d{1,3}\\.\\d{4,},\\s*-?\\d{1,3}\\.\\d{4,}")

    fun sanitize(text: String): String = text
        .replace(email, "<email>")
        .replace(token, "<token>")
        .replace(contentUri, "<path>")
        .replace(absPath, "<path>")
        .replace(coord, "<coord>")
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.core.diag.DiagSanitizerTest" 2>&1 | tail -15`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/core/diag/DiagSanitizer.kt \
        app/src/test/java/com/mamba/picme/core/diag/DiagSanitizerTest.kt
git commit -m "feat(app): DiagSanitizer 诊断包脱敏（邮箱/token/路径/坐标）+ 单测"
```

---

## Task 3: DiagBundle + DiagBundleCollector

**Files:**
- Create: `app/src/main/java/com/mamba/picme/core/diag/DiagBundle.kt`
- Create: `app/src/main/java/com/mamba/picme/core/diag/DiagBundleCollector.kt`
- Test: `app/src/test/java/com/mamba/picme/core/diag/DiagBundleCollectorTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/mamba/picme/core/diag/DiagBundleCollectorTest.kt`:

```kotlin
package com.mamba.picme.core.diag

import com.mamba.picme.core.common.Logger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DiagBundleCollectorTest {

    @Before
    fun setUp() {
        Logger.clear()
    }

    @Test
    fun `collect assembles logs version and device info`() {
        Logger.i("Camera", "Preview started")
        Logger.e("Gallery", "boom")

        val bundle = DiagBundleCollector.collect(
            appVersion = "1.0.29",
            gitSha = "abc1234",
            deviceModel = "Pixel 8",
            androidVersion = "14",
        )

        assertEquals("1.0.29", bundle.appVersion)
        assertEquals("abc1234", bundle.gitSha)
        assertEquals("Pixel 8", bundle.deviceModel)
        assertEquals("14", bundle.androidVersion)
        assertTrue("logs contain both entries", bundle.logs.contains("Preview started") && bundle.logs.contains("boom"))
        assertTrue("logs carry PoLang tag", bundle.logs.contains("PoLang:"))
        assertNull(bundle.crashTrace)
    }

    @Test
    fun `collect sanitizes sensitive paths in logs`() {
        Logger.i("Storage", "saved /storage/emulated/0/DCIM/IMG.jpg")

        val bundle = DiagBundleCollector.collect("1.0.29", "abc1234", "Pixel 8", "14")

        assertTrue("media path redacted: ${bundle.logs}", !bundle.logs.contains("/storage/"))
        assertTrue(bundle.logs.contains("<path>"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.core.diag.DiagBundleCollectorTest" 2>&1 | tail -15`
Expected: COMPILE FAIL — `DiagBundle` / `DiagBundleCollector` unresolved.

- [ ] **Step 3: Create DiagBundle data classes**

Create `app/src/main/java/com/mamba/picme/core/diag/DiagBundle.kt`:

```kotlin
package com.mamba.picme.core.diag

import org.json.JSONObject

/** 脱敏后的纯文本诊断包（与 server 端 DiagRoute.DiagBundle 契约一致）。 */
data class DiagBundle(
    val logs: String,
    val crashTrace: String?,
    val appVersion: String,
    val gitSha: String,
    val deviceModel: String,
    val androidVersion: String,
) {
    fun toJsonObject(): JSONObject {
        val o = JSONObject()
            .put("logs", logs)
            .put("appVersion", appVersion)
            .put("gitSha", gitSha)
            .put("deviceModel", deviceModel)
            .put("androidVersion", androidVersion)
        if (crashTrace != null) o.put("crashTrace", crashTrace)
        return o
    }
}

/** server /diag/jobs/{id} 回传的任务状态（手机端展示用）。 */
data class DiagJobStatus(
    val jobId: Int,
    val status: String,
    val rootCause: String?,
    val fixBranch: String?,
    val compareUrl: String?,
    val tested: Boolean,
)
```

- [ ] **Step 4: Create DiagBundleCollector**

Create `app/src/main/java/com/mamba/picme/core/diag/DiagBundleCollector.kt`:

```kotlin
package com.mamba.picme.core.diag

import com.mamba.picme.core.common.Logger

/**
 * 收集纯文本诊断包并脱敏。version/gitSha/deviceInfo 由调用方注入
 *（BuildConfig 在 JVM 单测不可用，故不在此直接读）。
 *
 * 日志来自既有 [Logger.logs] 内存环形缓冲（最多 500 条，最新在前）。
 */
object DiagBundleCollector {
    private const val MAX_LOG_LINES = 1000

    fun collect(
        appVersion: String,
        gitSha: String,
        deviceModel: String,
        androidVersion: String,
        crashTrace: String? = null,
    ): DiagBundle {
        val logs = Logger.logs.value
            .take(MAX_LOG_LINES)
            .joinToString("\n") { e -> "${e.timestamp} ${e.level} PoLang:${e.tag}: ${e.message}" }
        return DiagBundle(
            logs = DiagSanitizer.sanitize(logs),
            crashTrace = crashTrace?.let { DiagSanitizer.sanitize(it) },
            appVersion = appVersion,
            gitSha = gitSha,
            deviceModel = deviceModel,
            androidVersion = androidVersion,
        )
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.core.diag.DiagBundleCollectorTest" 2>&1 | tail -15`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/mamba/picme/core/diag/DiagBundle.kt \
        app/src/main/java/com/mamba/picme/core/diag/DiagBundleCollector.kt \
        app/src/test/java/com/mamba/picme/core/diag/DiagBundleCollectorTest.kt
git commit -m "feat(app): DiagBundle + DiagBundleCollector（读 Logger.logs + 脱敏）+ 单测"
```

---

## Task 4: DiagClient (HTTP, mirror PoLangAuthClient)

**Files:**
- Create: `app/src/main/java/com/mamba/picme/data/remote/picme/DiagClient.kt`
- Test: `app/src/test/java/com/mamba/picme/data/remote/picme/DiagClientTest.kt`

- [ ] **Step 1: Write the failing test (request JSON contract)**

Create `app/src/test/java/com/mamba/picme/data/remote/picme/DiagClientTest.kt`:

```kotlin
package com.mamba.picme.data.remote.picme

import com.mamba.picme.core.diag.DiagBundle
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagClientTest {

    @Test
    fun `report request body matches server DiagReportRequest contract`() {
        val bundle = DiagBundle(
            logs = "PoLang:Camera preview",
            crashTrace = null,
            appVersion = "1.0.29",
            gitSha = "abc1234",
            deviceModel = "Pixel 8",
            androidVersion = "14",
        )
        val json = DiagClient.buildReportBody("crash on open", bundle)
        val obj = JSONObject(json)
        assertEquals("crash on open", obj.getString("description"))
        val b = obj.getJSONObject("bundle")
        assertEquals("PoLang:Camera preview", b.getString("logs"))
        assertEquals("1.0.29", b.getString("appVersion"))
        assertEquals("abc1234", b.getString("gitSha"))
        assertEquals("Pixel 8", b.getString("deviceModel"))
        assertEquals("14", b.getString("androidVersion"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.data.remote.picme.DiagClientTest" 2>&1 | tail -15`
Expected: COMPILE FAIL — `DiagClient` unresolved.

- [ ] **Step 3: Create DiagClient**

Create `app/src/main/java/com/mamba/picme/data/remote/picme/DiagClient.kt`:

```kotlin
package com.mamba.picme.data.remote.picme

import com.mamba.picme.core.diag.DiagBundle
import com.mamba.picme.core.diag.DiagJobStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 远程诊断 HTTP 客户端，镜像 [PoLangAuthClient] 的风格（OkHttp + org.json + X-App-Token）。
 * 与 server 端 DiagRoute 契约一致：POST /diag/report、GET /diag/jobs/{id}、POST /diag/jobs/{id}/confirm。
 */
class DiagClient(
    private val baseUrl: String = DEFAULT_BASE_URL,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json".toMediaType()

    suspend fun reportDiagnosis(token: String, description: String, bundle: DiagBundle): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("$baseUrl/diag/report")
                    .header("X-App-Token", token)
                    .post(buildReportBody(description, bundle).toRequestBody(jsonMedia))
                    .build()
                val resp = client.newCall(req).execute()
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: $body")
                JSONObject(body).getInt("jobId")
            }
        }

    suspend fun fetchDiagStatus(token: String, jobId: Int): Result<DiagJobStatus> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("$baseUrl/diag/jobs/$jobId")
                    .header("X-App-Token", token)
                    .get()
                    .build()
                val resp = client.newCall(req).execute()
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: $body")
                val json = JSONObject(body)
                DiagJobStatus(
                    jobId = json.getInt("jobId"),
                    status = json.getString("status"),
                    rootCause = json.optString("rootCause").takeIf { it.isNotBlank() },
                    fixBranch = json.optString("fixBranch").takeIf { it.isNotBlank() },
                    compareUrl = json.optString("compareUrl").takeIf { it.isNotBlank() },
                    tested = json.optBoolean("tested", false),
                )
            }
        }

    suspend fun confirmFix(token: String, jobId: Int, mode: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject().put("mode", mode).toString()
                val req = Request.Builder()
                    .url("$baseUrl/diag/jobs/$jobId/confirm")
                    .header("X-App-Token", token)
                    .post(body.toRequestBody(jsonMedia))
                    .build()
                val resp = client.newCall(req).execute()
                if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            }
        }

    companion object {
        private const val DEFAULT_BASE_URL = "https://api.polang.net"

        /** 构造 /diag/report 请求体（抽出以便单测契约）。 */
        fun buildReportBody(description: String, bundle: DiagBundle): String =
            JSONObject()
                .put("description", description)
                .put("bundle", bundle.toJsonObject())
                .toString()
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.data.remote.picme.DiagClientTest" 2>&1 | tail -15`
Expected: PASS (1 test).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/mamba/picme/data/remote/picme/DiagClient.kt \
        app/src/test/java/com/mamba/picme/data/remote/picme/DiagClientTest.kt
git commit -m "feat(app): DiagClient 远程诊断 HTTP 客户端（report/jobs/confirm）+ 契约单测"
```

---

## Self-Review

- **Spec coverage:** §6.1 `DiagSanitizer` (Task 2), `DiagBundleCollector` + `Logger` ring buffer reuse (Task 3, no Logger change needed — it already exposes `logs`), `DiagClient` (Task 4), version/gitSha via `BuildConfig` (Task 1). Crash capture intentionally deferred (no existing handler; MVP bundle has no crashTrace).
- **Contract consistency:** `DiagBundle` field names match server `DiagRoute.DiagBundle` exactly (logs, crashTrace, appVersion, gitSha, deviceModel, androidVersion); `buildReportBody` wraps `{description, bundle}` matching server `DiagReportRequest`.
- **No placeholders:** every step has complete code or exact commands.
- **Refinement vs spec:** (a) no `Logger.kt` change (ring buffer already exists); (b) person-name redaction deferred (unreliable on free text; logs rarely contain names).

## Done criteria for this plan

- [ ] `./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.core.diag.*" --tests "com.mamba.picme.data.remote.picme.DiagClientTest"` passes (9 tests).
- [ ] `BuildConfig.GIT_SHA` generated.
- [ ] 4 tasks committed (own files only).
