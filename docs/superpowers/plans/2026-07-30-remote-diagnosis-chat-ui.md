# Remote Diagnosis (Chat UI) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Wire the remote-diagnosis data layer into the chat UX: a「诊断」input-bar icon triggers `submitDiagnosis`, status/root-cause surface as chat messages, and a bottom sheet lets the user confirm push/pr.

**Architecture:** `DiagController` (pure Kotlin, mirrors `WriteConfirmationController`) holds the pending-confirm state (testable). `ChatViewModel` owns the report→poll→confirm→poll flow (uses injected `DiagClient` + `DiagBundleCollector` + `serverAuthTokenFlow`). `ChatInputArea` gets a leading「诊断」icon; `DiagConfirmSheet` (`ModalBottomSheet`) mirrors the existing write-confirmation sheet. Status updates reuse `AGENT_TEXT` messages via an `upsertDiagMessage` helper.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), Coroutines, existing `DiagClient`/`DiagBundleCollector`/`BuildConfig.GIT_SHA`.

**Spec:** `docs/superpowers/specs/2026-07-30-remote-diagnosis-design.md` (§2 触发, §4 数据流). Trigger = input-bar icon (confirmed). Depends on the already-merged server + app-core + worker.

---

## File Structure

**Create:**
- `app/src/main/java/com/mamba/picme/features/chat/DiagController.kt` — pending-confirm state machine
- `app/src/main/java/com/mamba/picme/features/chat/components/DiagConfirmSheet.kt` — bottom sheet
- `app/src/test/java/com/mamba/picme/features/chat/DiagControllerTest.kt`

**Modify:**
- `app/src/main/java/com/mamba/picme/features/chat/ChatViewModelDependencies.kt` — add `diagClient`
- `app/src/main/java/com/mamba/picme/di/AppContainer.kt` — construct `DiagClient()`
- `app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt` — `submitDiagnosis`/`confirmDiagnosis`/poll + `diagController` + `upsertDiagMessage`
- `app/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt` — `ChatInputArea` icon + render `DiagConfirmSheet`
- `app/src/main/res/values/strings.xml` + `values-zh-rCN/` + `values-zh-rTW/` — i18n

---

## Task 1: DiagController (pure state, TDD)

**Files:** `DiagController.kt`, `DiagControllerTest.kt`

- [ ] **Step 1: Failing test**

`app/src/test/java/com/mamba/picme/features/chat/DiagControllerTest.kt`:
```kotlin
package com.mamba.picme.features.chat

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DiagControllerTest {
    private lateinit var c: DiagController

    @Before fun setUp() { c = DiagController() }

    @Test fun `requestConfirm exposes pending`() {
        c.requestConfirm(7, "NPE GalleryScreen") {}
        val p = c.pending.value
        assertEquals(7, p?.jobId)
        assertEquals("NPE GalleryScreen", p?.rootCause)
    }

    @Test fun `resolve with mode clears and callbacks`() {
        var received: String? = "<none>"
        c.requestConfirm(1, "rc") { received = it }
        c.resolve("pr")
        assertNull(c.pending.value)
        assertEquals("pr", received)
    }

    @Test fun `resolve null cancels`() {
        var received: String? = "<none>"
        c.requestConfirm(1, "rc") { received = it }
        c.resolve(null)
        assertNull(c.pending.value)
        assertEquals(null, received)
    }

    @Test fun `resolve with no pending is no-op`() {
        c.resolve("push") // 不抛
        assertNull(c.pending.value)
    }

    @Test fun `clear drops pending without callback`() {
        var called = false
        c.requestConfirm(1, "rc") { called = true }
        c.clear()
        assertNull(c.pending.value)
        assertTrue(!called)
    }
}
```

- [ ] **Step 2: Run → FAIL (DiagController unresolved)**

`./gradlew :app:testDebugUnitTest --tests "com.mamba.picme.features.chat.DiagControllerTest" -q | tail -5`

- [ ] **Step 3: Create DiagController**

`app/src/main/java/com/mamba/picme/features/chat/DiagController.kt`:
```kotlin
package com.mamba.picme.features.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 待用户确认的诊断修复请求（根因已出，等用户选交付方式）。 */
data class PendingDiagConfirm(
    val jobId: Int,
    val rootCause: String,
    val onResolved: (String?) -> Unit, // "push" | "pr" | null(取消)
)

/**
 * 远程诊断「待确认」状态机（仿 WriteConfirmationController，纯 Kotlin 可单测）。
 * 同一时刻最多一个；resolve 后清空。ChatViewModel 在 DIAGNOSED 时 requestConfirm，
 * DiagConfirmSheet 展示根因 + 按钮，用户选 mode → resolve。
 */
class DiagController {
    private val _pending = MutableStateFlow<PendingDiagConfirm?>(null)
    val pending: StateFlow<PendingDiagConfirm?> = _pending.asStateFlow()

    fun requestConfirm(jobId: Int, rootCause: String, onResolved: (String?) -> Unit) {
        _pending.value = PendingDiagConfirm(jobId, rootCause, onResolved)
    }

    /** UI 入口：mode="push"|"pr" 确认；null=取消。无 pending 时 no-op。 */
    fun resolve(mode: String?) {
        val cur = _pending.value ?: return
        _pending.value = null
        cur.onResolved(mode)
    }

    /** 流程结束/被打断时清空（不回调）。 */
    fun clear() {
        _pending.value = null
    }
}
```

- [ ] **Step 4: Run → PASS (5)** ; **Step 5: Commit** `feat(app): DiagController 诊断确认状态机 + 单测`

---

## Task 2: DI — inject DiagClient

**Files:** `ChatViewModelDependencies.kt`, `AppContainer.kt`

- [ ] **Step 1:** In `ChatViewModelDependencies.kt`, add a field next to `picMeAuthClient`:
```kotlin
    val diagClient: com.mamba.picme.data.remote.picme.DiagClient,
```
- [ ] **Step 2:** In `AppContainer.kt`, in the `ChatViewModelDependencies(...)` construction (near `picMeAuthClient = PoLangAuthClient(),`), add:
```kotlin
            diagClient = com.mamba.picme.data.remote.picme.DiagClient(),
```
- [ ] **Step 3: Verify** `./gradlew :app:compileDebugKotlin -q | tail -5` → BUILD SUCCESSFUL.
- [ ] **Step 4: Commit** `feat(app): 注入 DiagClient 到 ChatViewModelDependencies`

---

## Task 3: ChatViewModel — submitDiagnosis + poll + confirm

**Files:** `ChatViewModel.kt`

- [ ] **Step 1:** Add field + state near other controllers (e.g., after `writeConfirmationController`):
```kotlin
    val diagController = DiagController()
    private val diagClient = dependencies.diagClient

    /** 当前活跃诊断（用于 confirm 阶段回填 token/jobId/msgId）。 */
    private data class ActiveDiag(val token: String, val jobId: Int, val msgId: String)
    private var activeDiag: ActiveDiag? = null
```
Add imports: `DiagBundleCollector`, `BuildConfig`, `android.os.Build`, `kotlinx.coroutines.isActive`, `kotlinx.coroutines.delay`, `first`.

- [ ] **Step 2:** Add helpers + entry points (append near other public methods):
```kotlin
    /** UI「诊断」icon 入口：把输入框文本作为问题描述。 */
    fun submitDiagnosis(description: String) {
        if (description.isBlank()) return
        viewModelScope.launch {
            val token = userSettingsRepository.serverAuthTokenFlow.first()
            val msgId = "diag_${System.currentTimeMillis()}"
            if (token.isBlank()) {
                upsertDiagMessage(msgId, "⚠️ 远程诊断需要先登录账号（设置 → 账号）")
                return@launch
            }
            val bundle = DiagBundleCollector.collect(
                appVersion = BuildConfig.VERSION_NAME,
                gitSha = BuildConfig.GIT_SHA,
                deviceModel = android.os.Build.MODEL,
                androidVersion = android.os.Build.VERSION.RELEASE,
            )
            upsertDiagMessage(msgId, "🔍 已提交诊断请求，云主机分析中…")
            val jobId = diagClient.reportDiagnosis(token, description, bundle).getOrElse { e ->
                upsertDiagMessage(msgId, "❌ 上报失败：${e.message}"); return@launch
            }
            activeDiag = ActiveDiag(token, jobId, msgId)
            pollDiagnose(token, jobId, msgId)
        }
    }

    private suspend fun pollDiagnose(token: String, jobId: Int, msgId: String) {
        var delayMs = 2000L
        while (isActive) {
            kotlinx.coroutines.delay(delayMs); delayMs = (delayMs * 2).coerceAtMost(15000)
            val st = diagClient.fetchDiagStatus(token, jobId).getOrNull() ?: continue
            when (st.status) {
                "DIAGNOSED" -> {
                    val rc = st.rootCause.orEmpty()
                    upsertDiagMessage(msgId, "🔍 **根因分析**\n\n$rc\n\n_请在弹窗选择修复方式（推送 / PR）_")
                    diagController.requestConfirm(jobId, rc) { mode ->
                        if (mode != null) confirmDiagnosis(mode)
                    }
                    return
                }
                "DIAGNOSE_FAILED" -> { upsertDiagMessage(msgId, "❌ 诊断失败：${st.rootCause ?: "未知"}"); return }
            }
        }
    }

    /** DiagConfirmSheet 选定 mode 后调用。 */
    fun confirmDiagnosis(mode: String) {
        val ad = activeDiag ?: return
        viewModelScope.launch {
            diagController.clear()
            diagClient.confirmFix(ad.token, ad.jobId, mode).onFailure {
                upsertDiagMessage(ad.msgId, "❌ 确认失败：${it.message}"); return@launch
            }
            upsertDiagMessage(ad.msgId, "🔧 修复中（${if (mode == "pr") "开 PR" else "推送分支"}）…")
            pollFix(ad.token, ad.jobId, ad.msgId)
        }
    }

    private suspend fun pollFix(token: String, jobId: Int, msgId: String) {
        var delayMs = 3000L
        while (isActive) {
            kotlinx.coroutines.delay(delayMs); delayMs = (delayMs * 2).coerceAtMost(20000)
            val st = diagClient.fetchDiagStatus(token, jobId).getOrNull() ?: continue
            when (st.status) {
                "FIXED", "FIXED_UNVERIFIED" -> {
                    val verified = if (st.status == "FIXED") "✅ 自检通过" else "⚠️ 未自检"
                    val link = st.compareUrl ?: st.fixBranch
                    upsertDiagMessage(msgId, "✅ 已修复\n\n分支：`${st.fixBranch}`（$verified）" +
                        (link?.let { "\n\n$it" } ?: ""))
                    activeDiag = null
                    return
                }
                "FIX_FAILED" -> { upsertDiagMessage(msgId, "❌ 修复失败：${st.rootCause ?: "未知"}"); activeDiag = null; return }
            }
        }
    }

    fun cancelDiagConfirm() = diagController.resolve(null)

    /** 追加或更新一条 AGENT_TEXT 诊断消息（内存态，不落 Room）。 */
    private fun upsertDiagMessage(id: String, content: String) {
        _messages.update { msgs ->
            val idx = msgs.indexOfFirst { it.id == id }
            if (idx >= 0) msgs.toMutableList().apply { this[idx] = this[idx].copy(content = content) }
            else msgs + ChatMessageUi(id = id, type = ChatMessageType.AGENT_TEXT, content = content)
        }
    }
```

- [ ] **Step 3: Verify** `./gradlew :app:compileDebugKotlin -q | tail -5` → BUILD SUCCESSFUL.
- [ ] **Step 4: Commit** `feat(app): ChatViewModel 远程诊断流程（submit/轮询/确认/修复）`

> Notes: `_messages.update{}` requires `import kotlinx.coroutines.flow.update`. `ChatMessageUi`/`ChatMessageType` are in `ChatScreen.kt` (same `features.chat` package — but ChatViewModel is in `features.chat` too, so directly accessible). `serverAuthTokenFlow.first()` is suspend.

---

## Task 4: ChatInputArea —「诊断」icon

**Files:** `ChatScreen.kt`

- [ ] **Step 1:** `ChatInputArea(...)` (around line 1069) — add an `onDiagnose: () -> Unit` param and a leading icon button. In the `ChatScreen` call site (line ~495 `ChatInputArea(...)`), pass `onDiagnose = { viewModel.submitDiagnosis(currentInput) }` where `currentInput` is the input field state (capture the text-field text).

  Implementation detail: the input text lives in `ChatInputArea`'s own `var` state. To let the icon send the current text, hoist: capture the text at the `ChatScreen` level (the `onSendMessage` already receives text via the inner `onSend`). Mirror that: add `onDiagnose: (String) -> Unit` and have the inner send path also expose the current text to the diag icon. Simplest: in `ChatTextInputMode` (around line 1157), next to `onSend = { ... onSendMessage(text.trim()) }`, the diag icon reads the same `text` state.

  Concretely: add to `ChatInputArea` params `onDiagnose: (String) -> Unit`, render an `IconButton` (Icons.Default.BugReport) before/after the text field that calls `onDiagnose(text.trim())`; at the call site pass `onDiagnose = viewModel::submitDiagnosis`.

- [ ] **Step 2: Verify** `./gradlew :app:compileDebugKotlin -q | tail -5` → BUILD SUCCESSFUL.
- [ ] **Step 3: Commit** `feat(app): ChatInputArea「诊断」入口 icon`

> i18n: the icon's contentDescription is a new string (Task 6).

---

## Task 5: DiagConfirmSheet — bottom sheet

**Files:** `components/DiagConfirmSheet.kt`, `ChatScreen.kt`

- [ ] **Step 1:** Create `DiagConfirmSheet` mirroring the write-confirmation sheet (ChatScreen ~603-663):
```kotlin
@Composable
fun DiagConfirmSheet(rootCause: String, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState()
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        androidx.compose.foundation.layout.Column(
            modifier = androidx.compose.ui.Modifier.padding(24.dp).padding(bottom = 32.dp)
        ) {
            androidx.compose.material3.Text("远程诊断 · 根因", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
            androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(12.dp))
            androidx.compose.material3.Text(rootCause, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
            androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(20.dp))
            androidx.compose.foundation.layout.Row {
                androidx.compose.material3.Button(onClick = { onPick("push") }) { androidx.compose.material3.Text("推送修复分支") }
                androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.width(12.dp))
                androidx.compose.material3.Button(onClick = { onPick("pr") }) { androidx.compose.material3.Text("修复并开 PR") }
                androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.width(12.dp))
                androidx.compose.material3.TextButton(onClick = { onPick(null) }) { androidx.compose.material3.Text("取消") }
            }
        }
    }
}
```
(Use real imports; the fully-qualified form above is for plan readability — add proper imports in the file.)

- [ ] **Step 2:** In `ChatScreen` (next to `pendingWriteConfirmation` handling, ~603), add:
```kotlin
val pendingDiag by viewModel.diagController.pending.collectAsState()
pendingDiag?.let { p ->
    DiagConfirmSheet(
        rootCause = p.rootCause,
        onPick = { mode ->
            if (mode != null) viewModel.confirmDiagnosis(mode) else viewModel.cancelDiagConfirm()
        },
        onDismiss = { viewModel.cancelDiagConfirm() },
    )
}
```
- [ ] **Step 3: Verify** `./gradlew :app:compileDebugKotlin -q | tail -5` → BUILD SUCCESSFUL.
- [ ] **Step 4: Commit** `feat(app): DiagConfirmSheet 根因确认底部弹窗`

---

## Task 6: i18n strings + full build

**Files:** `values/strings.xml`, `values-zh-rCN/strings.xml`, `values-zh-rTW/strings.xml`

- [ ] **Step 1:** Add strings for: diag icon contentDescription, sheet title, buttons (推送修复分支 / 修复并开 PR / 取消), and the in-message texts if any are pulled to resources. Move the Chinese literals from T3/T5 into resources where feasible (at minimum the icon description + sheet title/buttons).

- [ ] **Step 2: Verify** `./gradlew :app:assembleDebug 2>&1 | tail -5` → BUILD SUCCESSFUL (or `:app:compileDebugKotlin` if assembleDebug hits unrelated native env issues). 
- [ ] **Step 3: Commit** `feat(app): 远程诊断 chat-UI 三语文案`

---

## Self-Review

- **Spec coverage:** §2 触发 (icon, T4), 根因回 chat (AGENT_TEXT, T3), 确认 push/pr (sheet, T5), 未登录提示 (T3). §6.1 DiagBundleCollector/BuildConfig reused.
- **Contract consistency:** uses `DiagClient.reportDiagnosis/fetchDiagStatus/confirmFix` + `DiagJobStatus.status` values (QUEUED/DIAGNOSED/FIX_REQUESTED/FIXED/FIXED_UNVERIFIED/*_FAILED) from the merged app-core.
- **Testability:** DiagController (T1) unit-tested; ViewModel flow (T3) + UI (T4/T5) build-verified (consistent with codebase — ViewModels/Compose aren't unit-tested; underlying DiagClient + DiagController are).
- **No placeholders:** T1 full code+tests; T2-T6 concrete code with exact insert points.

## Done criteria

- [ ] `DiagControllerTest` 5 tests pass.
- [ ] `:app:compileDebugKotlin` (or assembleDebug) succeeds.
- [ ] 三语文案同步。
- [ ] 6 tasks committed (own files only).
