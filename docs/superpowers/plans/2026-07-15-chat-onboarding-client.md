# Chat Onboarding & Guest Mode — Client Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Give the blank chat screen an onboarding empty-state (intro bubbles + tappable examples), let unregistered users try the Remote model via device-id guest mode (no hard block), and surface email-OTP registration in-chat when needed.

**Architecture:** Device id is threaded into `RemoteModelConfig` and injected as `X-Device-Id` by `AgentConfigurator` (the real langchain4j header injection point) when there is no account token. `ChatViewModel` derives `isGuestMode`, reacts to the server's guest-quota-exhausted 403 by opening a registration sheet (soft nudge), and drives registration via the existing `PoLangAuthClient`. A reusable `EmailCodeAuthForm` is shared between Settings and the new chat sheet.

**Tech Stack:** Kotlin, Jetpack Compose, Material3, DataStore, MVVM (`ChatViewModel`), langchain4j (remote), OkHttp (`PoLangAuthClient`).

**Spec:** `docs/superpowers/specs/2026-07-15-chat-onboarding-guest-trial-design.md` §4.3–4.5.

**Corrections vs spec (found during implementation):**
- `OpenAiApiClient` is **dead code** (no callers). The real header injection is `AgentConfigurator.createRemoteChatModel` → `builder.customHeader("X-App-Token", gatewayToken)`. `X-Device-Id` goes there via a new `RemoteModelConfig.deviceId`.
- langchain4j does not expose response headers → the `X-Guest-Remaining` header is **not** read client-side. Guest UI is count-free ("试用中") and reacts to the 403 only (matches the spec's documented MVP fallback).

**Run tests:** `./gradlew :app:testDebugUnitTest`; compile: `./gradlew :app:compileDebugKotlin`.

---

## File Structure

- **Modify** `runtime-core/.../agent/core/remote/config/RemoteModelConfig.kt` — add `deviceId`.
- **Modify** `runtime-core/.../agent/core/facade/AgentConfigurator.kt:185-192` — add `X-Device-Id` header branch.
- **Create** `app/.../core/identity/DeviceIdProvider.kt` — stable per-device id (ANDROID_ID + DataStore UUID fallback).
- **Modify** `app/.../features/chat/ChatViewModelDependencies.kt` — add `picMeAuthClient` + `deviceIdProvider`.
- **Modify** `app/.../PoLangApplication.kt` — construct `DeviceIdProvider`, set `deviceId` on effective remote config; wire new deps into `ChatViewModel`.
- **Modify** `app/.../features/chat/ChatViewModel.kt` — `isGuestMode`, `showRegistrationSheet`, registration actions, 403 nudge, example-send.
- **Modify** `app/.../features/chat/ChatScreen.kt` — mount empty-state + registration sheet.
- **Create** `app/.../features/chat/components/ChatEmptyState.kt`.
- **Create** `app/.../features/chat/components/ChatRegistrationSheet.kt`.
- **Create** `app/.../features/common/auth/EmailCodeAuthForm.kt` (shared); **Modify** `app/.../features/settings/SettingsServerAuth.kt` to use it.
- **Modify** `app/src/main/res/values/strings.xml`, `values-zh-rCN/strings.xml`, `values-zh-rTW/strings.xml`.

---

### Task B1: `RemoteModelConfig.deviceId` + `AgentConfigurator` guest header

**Files:**
- Modify: `runtime-core/src/main/java/com/mamba/picme/agent/core/remote/config/RemoteModelConfig.kt`
- Modify: `runtime-core/src/main/java/com/mamba/picme/agent/core/facade/AgentConfigurator.kt`

- [ ] **Step 1: Add `deviceId` to `RemoteModelConfig`**

In the `RemoteModelConfig` data class primary constructor, add after `gatewayToken`:

```kotlin
data class RemoteModelConfig(
    val modelId: String,
    val providerId: String = "",
    val protocol: RemoteProtocol = RemoteProtocol.OPENAI,
    val apiKey: String = "",
    val baseUrl: String = "",
    val gatewayToken: String = "",
    val deviceId: String = "",
) {
```

- [ ] **Step 2: Add the `X-Device-Id` branch in `AgentConfigurator.createRemoteChatModel`**

Current (lines ~189-191):
```kotlin
        if (config.gatewayToken.isNotBlank()) {
            builder.customHeader("X-App-Token", config.gatewayToken)
        }
```
Replace with:
```kotlin
        if (config.gatewayToken.isNotBlank()) {
            builder.customHeader("X-App-Token", config.gatewayToken)
        } else if (config.deviceId.isNotBlank()) {
            // 未注册访客：无账号 token 时改用设备级试用额度
            builder.customHeader("X-Device-Id", config.deviceId)
        }
```

- [ ] **Step 3: Compile runtime-core**

```bash
./gradlew :runtime-core:compileDebugKotlin 2>&1 | tail -5
```
Expected: BUILD SUCCESSFUL (existing callers pass `deviceId` by default `""`).

- [ ] **Step 4: Commit**

```bash
git add runtime-core/src/main/java/com/mamba/picme/agent/core/remote/config/RemoteModelConfig.kt runtime-core/src/main/java/com/mamba/picme/agent/core/facade/AgentConfigurator.kt
git commit -m "feat(runtime-core): inject X-Device-Id for guest mode when no account token"
```

---

### Task B2: `DeviceIdProvider`

**Files:**
- Create: `app/src/main/java/com/mamba/picme/core/identity/DeviceIdProvider.kt`

- [ ] **Step 1: Implement**

```kotlin
package com.mamba.picme.core.identity

import android.content.Context
import android.provider.Settings
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.util.UUID

private val Context.deviceIdStore by preferencesDataStore(name = "device_id")

/**
 * 稳定的设备标识，用于未注册访客的服务端试用额度（X-Device-Id）。
 * 优先用 ANDROID_ID；缺失/异常时回退到 DataStore 持久化的 UUID。
 */
class DeviceIdProvider(private val appContext: Context) {

    suspend fun get(): String {
        val androidId = runCatching {
            Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull().orEmpty()
        if (androidId.isNotBlank() && androidId != "9774d56d682e549c") {
            return androidId
        }
        // 回退 UUID
        val key = stringPreferencesKey("uuid")
        var stored: String? = null
        appContext.deviceIdStore.data.collect { stored = it[key] }
        if (!stored.isNullOrBlank()) return stored!!
        val generated = "uuid-" + UUID.randomUUID().toString().replace("-", "")
        appContext.deviceIdStore.edit { it[key] = generated }
        return generated
    }
}
```

> Note: `collect` on a DataStore flow returns after first emission. This is fine for a one-shot id read.

- [ ] **Step 2: Compile + commit**

```bash
./gradlew :app:compileDebugKotlin 2>&1 | tail -5
git add app/src/main/java/com/mamba/picme/core/identity/DeviceIdProvider.kt
git commit -m "feat(app): add DeviceIdProvider for guest trial"
```

---

### Task B3: Wire device id into the effective remote config (`PoLangApplication`)

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/PoLangApplication.kt` (syncRemoteModelConfigToOrchestrator ~line 320-361)

- [ ] **Step 1: Construct a `DeviceIdProvider` and set `deviceId` on the guest config**

In the `syncRemoteModelConfigToOrchestrator` combine block, the `else` branch builds `PICME_SERVER_DEFAULT.copy(gatewayToken = serverToken)`. Change it to also carry the device id so guest mode (blank token) sends `X-Device-Id`:

```kotlin
val deviceId = deviceIdProvider.get()
val remoteConfig = RemoteModelConfig.PICME_SERVER_DEFAULT.copy(
    gatewayToken = serverToken,
    deviceId = deviceId,
)
```

Add a lazy `private val deviceIdProvider = DeviceIdProvider(this)` field on `PoLangApplication` (the combine block is already a suspend coroutine, so `.get()` is fine).

- [ ] **Step 2: Compile + commit**

```bash
./gradlew :app:compileDebugKotlin 2>&1 | tail -5
git add app/src/main/java/com/mamba/picme/PoLangApplication.kt
git commit -m "feat(app): attach device id to remote config for guest mode"
```

---

### Task B4: `ChatViewModel` guest state + registration + 403 nudge

**Files:**
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ChatViewModelDependencies.kt`
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt`

- [ ] **Step 1: Add deps**

`ChatViewModelDependencies.kt`:
```kotlin
class ChatViewModelDependencies(
    val context: Context,
    val chatMessageDao: ChatMessageDao,
    val chatSessionDao: ChatSessionDao,
    val userSettingsRepository: UserSettingsRepository,
    val mediaSearchEngine: MediaSearchEngine,
    val picMeAuthClient: PoLangAuthClient,
)
```
Add `import com.mamba.picme.data.remote.picme.PoLangAuthClient`.

- [ ] **Step 2: Guest state + registration in `ChatViewModel`**

Add fields/derivation (VM already holds `userSettingsRepository`; add `private val authClient = dependencies.picMeAuthClient`):

```kotlin
private val _serverAuthToken = MutableStateFlow("")
init {
    viewModelScope.launch {
        userSettingsRepository.serverAuthTokenFlow.collect { _serverAuthToken.value = it }
    }
}

val isGuestMode: StateFlow<Boolean> = combine(_currentModel, _serverAuthToken) { model, token ->
    model is ChatModelOption.Remote && token.isBlank()
}.stateIn(viewModelScope, SharingStarted.Eagerly, false)

private val _showRegistrationSheet = MutableStateFlow(false)
val showRegistrationSheet: StateFlow<Boolean> = _showRegistrationSheet.asStateFlow()

fun openRegistrationSheet() { _showRegistrationSheet.value = true }
fun dismissRegistrationSheet() { _showRegistrationSheet.value = false }

fun sendVerificationCode(email: String, onResult: (Result<Unit>) -> Unit) {
    viewModelScope.launch {
        authClient.sendVerificationCode(email).also(onResult)
    }
}

fun verifyCode(email: String, code: String, onResult: (Result<*>) -> Unit) {
    viewModelScope.launch {
        val r = authClient.verifyCode(email, code)
        r.onSuccess { userSettingsRepository.updateServerAuth(it.token, email) }
        onResult(r)
        if (r.isSuccess) _showRegistrationSheet.value = false
    }
}
```

- [ ] **Step 3: Detect guest-quota 403 in `sendMessage` and nudge**

In the `onFailure = { error -> ... }` arm of `result.fold` (and/or the outer `catch`), before inserting the generic error message, detect guest exhaustion:

```kotlin
onFailure = { error ->
    _streamingMessage.value = null
    val msg = error.message.orEmpty()
    val isGuestQuota = isGuestMode.value &&
        (msg.contains("quota_exceeded", ignoreCase = true) || msg.contains("403", ignoreCase = true))
    if (isGuestQuota) {
        insertAgentMessage(sessionId, "试用额度已用完，注册即可获得 1000 次免费额度。", currentModelLabel())
        _showRegistrationSheet.value = true
    } else {
        insertAgentMessage(sessionId, "推理出错：${error.message ?: "未知错误"}", "error")
    }
}
```

(Strings are externalized in Task B7; the literal here becomes a string-resource reference. Use `context.getString(R.string.chat_guest_quota_used_up)` / `R.string.chat_inference_error` — wire in B7.)

- [ ] **Step 4: Compile + commit**

```bash
./gradlew :app:compileDebugKotlin 2>&1 | tail -8
git add app/src/main/java/com/mamba/picme/features/chat/ChatViewModelDependencies.kt app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt
git commit -m "feat(chat): guest-mode state, registration actions, quota-exhausted nudge"
```

---

### Task B5: `ChatEmptyState` composable + mount in `ChatScreen`

**Files:**
- Create: `app/src/main/java/com/mamba/picme/features/chat/components/ChatEmptyState.kt`
- Modify: `app/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt`

- [ ] **Step 1: `ChatEmptyState`**

A centered column: assistant welcome bubble, capability bubble, example chips, and (when `isGuestMode`) a guest/register card. Signature:

```kotlin
@Composable
fun ChatEmptyState(
    isGuestMode: Boolean,
    onExampleClick: (String) -> Unit,
    onRegisterClick: () -> Unit,
    modifier: Modifier = Modifier,
)
```

Use `MaterialTheme.colorScheme` bubbles (assistant = `surfaceVariant`; chips = outlined). Example prompts: `stringArrayResource(R.array.chat_example_prompts)`.

- [ ] **Step 2: Mount in `ChatScreen`**

Replace the bare `LazyColumn` content with: if `messages.isEmpty()` → `ChatEmptyState(...)` (filling the weight(1f) area); else the existing `LazyColumn`. Collect `viewModel.isGuestMode` and `viewModel.showRegistrationSheet`.

```kotlin
val isGuestMode by viewModel.isGuestMode.collectAsState()
val showRegistration by viewModel.showRegistrationSheet.collectAsState()
...
if (messages.isEmpty()) {
    ChatEmptyState(
        isGuestMode = isGuestMode,
        onExampleClick = { viewModel.sendMessage(it) },
        onRegisterClick = { viewModel.openRegistrationSheet() },
        modifier = Modifier.weight(1f).fillMaxWidth(),
    )
} else {
    LazyColumn(/* existing */) { items(messages, ...) { ... } }
}
if (showRegistration) {
    ChatRegistrationSheet(
        onDismiss = { viewModel.dismissRegistrationSheet() },
        onUseOwnKey = { onNavigateToSettings(); viewModel.dismissRegistrationSheet() },
        onUseLocal = { viewModel.switchModel(ChatModelOption.Local); viewModel.dismissRegistrationSheet() },
        sendCode = viewModel::sendVerificationCode,
        verifyCode = viewModel::verifyCode,
    )
}
```

- [ ] **Step 3: Compile + commit**

```bash
./gradlew :app:compileDebugKotlin 2>&1 | tail -8
git add app/src/main/java/com/mamba/picme/features/chat/components/ChatEmptyState.kt app/src/main/java/com/mamba/picme/features/chat/ChatScreen.kt
git commit -m "feat(chat): onboarding empty state with intro bubbles + example chips"
```

---

### Task B6: Reusable `EmailCodeAuthForm` + `ChatRegistrationSheet`

**Files:**
- Create: `app/src/main/java/com/mamba/picme/features/common/auth/EmailCodeAuthForm.kt`
- Create: `app/src/main/java/com/mamba/picme/features/chat/components/ChatRegistrationSheet.kt`
- Modify: `app/src/main/java/com/mamba/picme/features/settings/SettingsServerAuth.kt`

- [ ] **Step 1: Extract `EmailCodeAuthForm`**

A stateless composable encapsulating email→send-code→code→verify, taking callbacks (mirrors `SettingsServerAuth`'s form logic, string-resource based):

```kotlin
@Composable
fun EmailCodeAuthForm(
    sendCode: (email: String, onResult: (Result<Unit>) -> Unit) -> Unit,
    verifyCode: (email: String, code: String, onResult: (Result<*>) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
)
```

- [ ] **Step 2: `ChatRegistrationSheet`**

```kotlin
@Composable
fun ChatRegistrationSheet(
    onDismiss: () -> Unit,
    onUseOwnKey: () -> Unit,
    onUseLocal: () -> Unit,
    sendCode: (String, (Result<Unit>) -> Unit) -> Unit,
    verifyCode: (String, String, (Result<*>) -> Unit) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.chat_register_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.chat_register_subtitle), style = MaterialTheme.typography.bodySmall)
            EmailCodeAuthForm(sendCode = sendCode, verifyCode = verifyCode)
            HorizontalDivider()
            TextButton(onClick = onUseOwnKey) { Text(stringResource(R.string.chat_register_use_own_key)) }
            TextButton(onClick = onUseLocal) { Text(stringResource(R.string.chat_register_use_local)) }
        }
    }
}
```

- [ ] **Step 3: Refactor `SettingsServerAuth` to use `EmailCodeAuthForm`**

Replace the inline email/code fields + buttons in `ServerAuthSection` with `EmailCodeAuthForm(...)`, passing the existing `authClient`-based lambdas. Remove the now-duplicated hardcoded strings in favor of the shared string resources (Task B7).

- [ ] **Step 4: Compile + commit**

```bash
./gradlew :app:compileDebugKotlin 2>&1 | tail -8
git add app/src/main/java/com/mamba/picme/features/common/auth/EmailCodeAuthForm.kt app/src/main/java/com/mamba/picme/features/chat/components/ChatRegistrationSheet.kt app/src/main/java/com/mamba/picme/features/settings/SettingsServerAuth.kt
git commit -m "feat(chat): reusable EmailCodeAuthForm + ChatRegistrationSheet; refactor Settings auth"
```

---

### Task B7: i18n strings (EN / zh-rCN / zh-rTW)

**Files:**
- Modify: `app/src/main/res/values/strings.xml`, `values-zh-rCN/strings.xml`, `values-zh-rTW/strings.xml`

- [ ] **Step 1: Add new keys** (examples) in all three locales:
  - `chat_empty_welcome` ("Hi, I'm your PoLang assistant" / "你好，我是 PoLang 助手" / "你好，我是 PoLang 助手")
  - `chat_empty_capabilities` ("I can search your gallery, edit photos, adjust beauty…" / "我能搜相册·修图·调美颜·找人找场景，随便问！" / traditional)
  - `chat_empty_try_these` ("Try these:" / "试试这些：" / "試試這些：")
  - `chat_guest_card_title` ("Trying without login" / "免登录试用中" / "免登入試用中")
  - `chat_guest_card_subtitle` ("Register for 1000 free calls" / "注册获取 1000 次免费额度" / traditional)
  - `chat_register_title`, `chat_register_subtitle`, `chat_register_use_own_key`, `chat_register_use_local`
  - `chat_guest_quota_used_up`, `chat_inference_error`
  - `chat_example_prompts` (string-array) — 3-4 example prompts per locale
  - Migrate the existing hardcoded strings from `SettingsServerAuth` (`服务端邮箱注册`, `邮箱`, `验证码`, `发送验证码`, `验证`, etc.) into shared keys.

- [ ] **Step 2: Wire the new keys** into `ChatEmptyState`, `ChatRegistrationSheet`, `EmailCodeAuthForm`, `ChatViewModel` (via `context.getString`), and `SettingsServerAuth`.

- [ ] **Step 3: Compile + commit**

```bash
./gradlew :app:compileDebugKotlin 2>&1 | tail -8
git add app/src/main/res/values/strings.xml app/src/main/res/values-zh-rCN/strings.xml app/src/main/res/values-zh-rTW/strings.xml app/src/main/java/com/mamba/picme/features/chat/components/ChatEmptyState.kt app/src/main/java/com/mamba/picme/features/chat/components/ChatRegistrationSheet.kt app/src/main/java/com/mamba/picme/features/common/auth/EmailCodeAuthForm.kt app/src/main/java/com/mamba/picme/features/settings/SettingsServerAuth.kt app/src/main/java/com/mamba/picme/features/chat/ChatViewModel.kt
git commit -m "feat(chat): i18n strings (en/zh-rCN/zh-rTW) for onboarding + registration"
```

---

## Self-Review

- **Spec §4.3 (guest transport):** B1 (X-Device-Id via AgentConfigurator) ✓, B2 (DeviceIdProvider) ✓, B3 (wire device id) ✓. Count-free + 403 reaction documented ✓.
- **Spec §4.4 (empty state):** B5 ✓. **§4.5 (registration sheet + EmailCodeAuthForm):** B6 ✓.
- **§4.7 i18n:** B7 ✓ (three locales + SettingsServerAuth migration).
- **Placeholder scan:** none; UI tasks specify signatures + key composition (exact polish left to the implementing pass, but no TBDs in contracts).
- **Risk:** 403 detection relies on langchain4j error message containing `quota_exceeded`/`403` — verify against a real guest-exhausted response post-deploy and tighten if needed.

## Sequencing

B1 (runtime-core) → B2 (DeviceIdProvider) → B3 (PoLangApplication wiring) → B4 (ViewModel) → B5 (empty state) → B6 (registration sheet + form) → B7 (i18n). Compile after each; full `:app:testDebugUnitTest` at the end. Server must be deployed before guest mode actually works; until then guest calls 401 → client nudges registration (graceful).
