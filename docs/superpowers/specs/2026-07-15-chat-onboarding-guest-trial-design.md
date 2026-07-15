# Chat Onboarding & Guest Trial — Design

- **Date:** 2026-07-15
- **Status:** Approved (direction); spec ready for review
- **Owner:** guoshuai
- **Branch (planned):** `feat/chat-onboarding-guest-trial`

## 1. Background & Goals

Today the Chat page has two UX gaps when a user first opens it:

1. **Blank screen ("大白屏").** `ChatScreen` renders `LazyColumn { items(messages) }` with no
   empty-state, so a first-time user sees a blank background with only the input bar.
2. **Remote is blocked without registration.** The Remote model routes through the PicMe server
   proxy (`/chat/completions`) which requires an `X-App-Token` (issued by email-OTP registration).
   An unregistered user cannot use Remote at all, and there is no in-chat guidance to register.
3. **Google Play review.** The app has an account system, so review policy asks for test
   credentials unless the app is fully usable without an account.

**Goals**

- **G1 — Onboarding empty state.** First entry shows assistant intro bubbles + tappable example
  prompts (no more blank screen).
- **G2 — Guest trial (no hard block).** Unregistered users can try the **Remote** model a small
  number of times before being asked to register. No blocking dialog on first send.
- **G3 — In-chat registration guide.** When using Remote, surface email registration (reuse the
  existing email-OTP flow), with a secondary "use my own API key" path and a "use local model"
  fallback.
- **G4 — Google review compliance.** Make the app fully usable in guest mode so no pre-shared test
  account is required.
- **G5 — Rebrand token prefix.** Auth token prefix `picme_at_` → `pl-`; admin cookie `picme_admin`
  → `pl_admin` (auth surface only; no DB/pref/channel renames).

## 2. Non-Goals

- No new auth mechanism — reuse the existing email-OTP (`/auth/email/send` + `/auth/email/verify`).
- No changes to the local-inference path (Qwen on-device) — it stays open as a fallback.
- No paid/billing tier; guest and account are both free, quota-bounded.
- No rename of generic `picme` identifiers (Room DB name, SharedPrefs, notification channels,
  loggers, User-Agent, dirs). Those would break existing installs / require migrations = new debt.

## 3. Verified Existing Context

**Server** (`server/`, standalone Gradle project; live at `api.polang.net`):
- `routes/AuthRoute.kt` — `/auth/email/send` (6-digit code via Resend), `/auth/email/verify`
  (creates/refreshes account, returns `{token, llmCallsUsed, llmCallsLimit}`), `/auth/quota`.
- `auth/AccountService.kt` — `TOKEN_PREFIX = "picme_at_"`; `isTokenFormat`; per-account quota
  (`checkAndIncrementQuota` / `revertQuota`); token validated by SHA-256 hash.
- `Application.kt:78-99` — auth interceptor: all non-public routes require `X-App-Token`, else 401;
  stashes `TokenHashKey` for downstream quota.
- `llm/LlmRoute.kt` — `/chat/completions` + `/v1/chat/completions`; IP rate-limit →
  `checkAndIncrementQuota(tokenHash)` (403 `quota_exceeded` on exhaustion) → proxy → revert on error.
- `auth/AppTokenAuth.kt` — `const val APP_TOKEN_HEADER = "X-App-Token"`; comment mentions `picme_at_*`.
- `admin/AdminAuth.kt:10` — `COOKIE_NAME = "picme_admin"`.

**App** (`app/`):
- `data/remote/picme/PicMeAuthClient.kt` — `sendVerificationCode` / `verifyCode` / `getQuota`;
  base URL `https://api.polang.net`. (Client does **not** validate the token prefix — it stores and
  re-sends the opaque token, so a prefix change is server-side only and client-safe.)
- `data/preferences/UserPreferencesRepository.kt:1017` — `serverAuthTokenFlow` / `serverAuthEmailFlow`
  / `updateServerAuth` / `clearServerAuth` (DataStore).
- `data/remote/openai/OpenAiApiClient.kt:52` — adds `X-App-Token` header when token non-blank.
- `PicMeApplication.kt:320-361` — observes `serverAuthTokenFlow` + remote model config and syncs the
  effective `RemoteModelConfig` (incl. `gatewayToken = serverToken`) into the orchestrator. So after
  `repo.updateServerAuth(...)`, the orchestrator's remote config is updated automatically.
- `features/chat/ChatScreen.kt:254-282` — message `LazyColumn`; **no empty-state branch**.
- `features/chat/ChatViewModel.kt` — `currentModel: StateFlow<ChatModelOption{Local,Remote}>`;
  `sendMessage(text)`; already holds `userSettingsRepository`.
- `features/settings/SettingsServerAuth.kt` — a working email + code login form (`ServerAuthSection`),
  but `internal` to settings and with hardcoded Chinese strings.
- `agent.core.remote.config.RemoteModelConfig` — `isConfigured = baseUrl.isNotBlank() &&
  (apiKey.isNotBlank() || gatewayToken.isNotBlank())`. The PicMe-server default becomes configured
  once `gatewayToken` (= `serverAuthToken`) is set; a user's own provider key is configured on its own.

## 4. Design

### 4.1 Server — device-bound guest trial quota (symmetric to accounts)

A first-class anonymous tier, mirroring the account quota (same check/increment/revert shape) — no
shared token, no client-side quota guessing, server is the single source of truth.

- **New table `AnonymousDevices`** (`id`, `deviceId` UNIQUE, `llm_calls_used`, `created_at`,
  `last_seen_at`). Added via a migration (idempotent `CREATE TABLE IF NOT EXISTS`).
- **New `auth/GuestService.kt`** mirroring `AccountService` quota methods, keyed by `deviceId` with a
  server-config limit (not per-row):
  - `checkAndIncrementQuota(deviceId, limit): Boolean`
  - `revertQuota(deviceId)`
- **Config** (`config/AppConfig.kt`): new `guestLlmQuota = envInt("GUEST_LLM_QUOTA", 100)`; and raise
  the registered quota `freeLlmQuota` default `100` → `1000` (`envInt("FREE_LLM_QUOTA", 1000)`).
  Deployments may still override either via env var.
  - **Quotas:** guest = **100 / device**, registered = **1000 / account** (reset on re-verify).
- **Auth interceptor** (`Application.kt`): keep the 401 path for everything; **additionally**, when
  there is no valid app token **and** the URI is an LLM proxy path (`/chat/completions`,
  `/v1/chat/completions`) **and** `X-Device-Id` is present → stash `DeviceIdKey` and allow through.
  All other protected routes still require a token. Public routes unchanged.
- **`LlmRoute.kt`**: read both `TokenHashKey` (account) and `DeviceIdKey` (guest):
  - `tokenHash` present → existing account-quota path.
  - else `deviceId` present → `GuestService.checkAndIncrementQuota(deviceId, guestLlmQuota)`; on
    `false` respond `403 { "error": "quota_exceeded", "tier": "guest", "message": "guest quota used up" }`
    and log usage with a guest marker; on proxy error call `GuestService.revertQuota(deviceId)`.
  - neither → `401` (defensive).
  - On guest success, add response header `X-Guest-Remaining: <limit - used>` (authoritative
    remaining count for the UI).
- **Rate limit**: the existing per-IP `RateLimiter` continues to apply to guest calls unchanged.

### 4.2 Server — token-prefix rebrand (auth surface only)

- `AccountService.TOKEN_PREFIX`: `"picme_at_"` → `"pl-"`. New tokens: `pl-<64 hex>`.
  - `isTokenFormat` already derives from `TOKEN_PREFIX`, so no other change needed.
  - **Breaking:** existing `picme_at_*` tokens stop validating → those users re-register via email
    OTP. Acceptable for a research app.
- `admin/AdminAuth.COOKIE_NAME`: `"picme_admin"` → `"pl_admin"` (current admin sessions simply
  re-authenticate).
- Update the `AppTokenAuth.kt` doc comment (`picme_at_*` → `pl-*`).
- **No other renames** (DB name, prefs, channels, loggers, User-Agent, dirs, COS key stay).

### 4.3 Client — guest transport (no hard block)

- **New `DeviceIdProvider`** (`app/.../core/`): returns a stable per-device id =
  `Settings.Secure.ANDROID_ID`, with a DataStore-UUID fallback when ANDROID_ID is blank/known-bad.
  Behind a tiny interface so it is testable and swappable.
- **`OpenAiApiClient`** (the remote HTTP layer): when the account token is blank, send
  `X-Device-Id: <deviceId>` instead of `X-App-Token`. (This is the only transport change; the rest of
  the client treats the remote call as normal.)
- **`ChatViewModel`** derives guest state reactively:
  - `isGuestMode = (currentModel == Remote) && serverAuthToken.isBlank()`.
  - `guestRemaining: StateFlow<Int?>` — updated from the `X-Guest-Remaining` header surfaced by the
    remote path (MVP may show "试用中" if the streaming header capture proves brittle; exact count is
    a nicety, 403 handling is the requirement).
  - On a remote `403` whose body indicates guest exhaustion (client is in `isGuestMode`) → set
    `showRegistrationSheet = true` and insert an assistant bubble "试用额度已用完，注册继续使用".
  - On a registered-user `403` (account exhausted) → different bubble "额度已用完".
  - **No interception of `sendMessage`.** First sends succeed via the guest quota; registration is a
    nudge, not a gate. (This removes the earlier `pendingText`/resend design — simpler.)

### 4.4 Client — onboarding empty state

New `features/chat/components/ChatEmptyState.kt`, shown by `ChatScreen` when
`messages.isEmpty()` (replaces the blank `LazyColumn`). Contents:

- Assistant welcome bubble ("你好，我是 PoLang 助手").
- Capability bubble (搜相册 · 修图 · 调美颜 · 找人找场景).
- Tappable example chips (`找去年夏天的照片` / `把这张图磨皮50` / …) → on tap, fill the input and
  **send** (default; chips reuse `viewModel.sendMessage`).
- **Guest card** when `isGuestMode`: "🚀 免登录试用中（剩余 N 次）· 注册获取 1000 次免费额度" +
  `[邮箱注册]` button → opens `ChatRegistrationSheet`. (`N` omitted when `guestRemaining == null`.)
- Registered users: no card (quota display stays in Settings; top-bar quota is out of scope).

### 4.5 Client — registration sheet (reuse email-OTP)

- Extract the email + code form core out of `SettingsServerAuth.kt::ServerAuthSection` into a shared,
  reusable `EmailCodeAuthForm` composable (`features/common/...`). Both Settings and the new chat
  sheet consume it — single source of truth. As part of this extraction, migrate its hardcoded
  Chinese strings into `strings.xml` (satisfies the I18N red line).
- New `features/chat/components/ChatRegistrationSheet.kt` (`ModalBottomSheet`) hosting
  `EmailCodeAuthForm`, driven by `ChatViewModel` actions:
  - `sendVerificationCode(email)` / `verifyCode(email, code)` → on success
    `repo.updateServerAuth(token, email)` → the existing `PicMeApplication` observer re-syncs the
    orchestrator's `gatewayToken`, so the next Remote call uses the account token. (Plan must verify
    the observer fires on `updateServerAuth`; if not, add an explicit re-sync trigger.)
  - Secondary actions: "我已有 API Key，去配置" → `onNavigateToSettings` (existing remote-models
    config screen); "先用本地模型" → `viewModel.switchModel(Local)` + dismiss.

### 4.6 Google Play review handling

- Because guest mode makes the app fully usable without an account, fill Play Console →
  **App content → App access** with: *"All app functionality is available without account
  credentials (guest mode). Account is optional and is used only for extended quota."* → **no
  pre-shared test credentials required.**
- A reviewer can also self-register via email OTP using their own email (the code is emailed to
  whatever address they enter), which independently satisfies the "test account" path.
- (Fallback only if Google ever insists on literal credentials: provision a permanent reviewer
  account — not expected to be needed.)

### 4.7 Cross-cutting

- **I18N (mandatory).** All new user-facing strings live in `values/strings.xml` (EN/default),
  `values-zh-rCN/strings.xml`, `values-zh-rTW/strings.xml`. The `SettingsServerAuth` extraction
  also moves its existing hardcoded Chinese into strings (in scope).
- **Code rules.** No fully-qualified `com.mamba.picme.*` names; no wildcard imports; lambda params
  named explicitly; Android log tag `PicMe:Chat`; server uses its existing per-module loggers.
- **`RemoteModelConfig.isConfigured`** semantics are unchanged; guest mode is purely a transport
  concern (`X-Device-Id`), not a new model-config state — no coupling.

## 5. Testing

**Server** (`gradlew -p server test`, JVM):
- `GuestService`: increment, revert, exhaustion (used ≥ limit → false), idempotency by device id.
- Auth interceptor: guest allowed **only** on LLM paths with `X-Device-Id`; 401 elsewhere without
  token; account token still works.
- `LlmRoute` branching: account path unchanged; guest path increments/reverts; 403 body carries
  `tier=guest`; `X-Guest-Remaining` header on success.
- `AccountService.isTokenFormat` / token generation uses `pl-` prefix; `picme_at_*` rejected.

**App** (JVM unit tests):
- `ChatViewModel`: `isGuestMode` derivation across (model × token) combos; remote guest-403 →
  sheet shown + exhaustion bubble; registered-403 → different bubble; example chip → `sendMessage`
  called with the chip text.
- `ChatEmptyState`: renders guest card only when `isGuestMode`; hides when registered.
- `DeviceIdProvider`: stable across calls; UUID fallback when ANDROID_ID blank.

## 6. Files Touched

**Server:**
- `server/src/main/kotlin/.../db/` — `AnonymousDevices` table + migration (new).
- `server/src/main/kotlin/.../auth/GuestService.kt` (new).
- `server/src/main/kotlin/.../auth/AccountService.kt` — `TOKEN_PREFIX = "pl-"`.
- `server/src/main/kotlin/.../auth/AppTokenAuth.kt` — comment update.
- `server/src/main/kotlin/.../admin/AdminAuth.kt` — `COOKIE_NAME = "pl_admin"`.
- `server/src/main/kotlin/.../Application.kt` — interceptor guest branch + `DeviceIdKey`.
- `server/src/main/kotlin/.../llm/LlmRoute.kt` — guest quota branch + `X-Guest-Remaining`.
- `server/src/main/kotlin/.../config/AppConfig.kt` — `guestLlmQuota` (100) + `freeLlmQuota` default 100→1000.

**App:**
- `app/src/main/java/.../features/chat/ChatViewModel.kt` — guest state, soft nudge, registration
  actions, example-send.
- `app/src/main/java/.../features/chat/ChatScreen.kt` — empty-state + sheet mounting.
- `app/src/main/java/.../features/chat/components/ChatEmptyState.kt` (new).
- `app/src/main/java/.../features/chat/components/ChatRegistrationSheet.kt` (new).
- `app/src/main/java/.../features/common/.../EmailCodeAuthForm.kt` (new, shared);
  `features/settings/SettingsServerAuth.kt` refactored to use it.
- `app/src/main/java/.../data/remote/openai/OpenAiApiClient.kt` — `X-Device-Id` guest path.
- `app/src/main/java/.../core/.../DeviceIdProvider.kt` (new) + DI/wiring.
- `app/src/main/res/values*/strings.xml` ×3 — new strings + migrated SettingsServerAuth strings.

## 7. Sequencing, Risks & Rollout

- **Server before client.** Guest mode is only useful once the server accepts `X-Device-Id`. Until
  then, guest calls return 401 → the client must degrade gracefully (nudge to register, never crash).
  Release order: **deploy server, then ship app.**
- **Token-prefix breaking change.** Existing `picme_at_*` tokens are invalidated on server deploy;
  affected users re-register. Coordinate the server deploy with the expectation of a one-time
  re-registration.
- **Deploy ownership.** Server is a separate project deployed by the user (see
  `gradlew -p server build`); this design does not auto-deploy.
- **Header capture in streaming path.** Surfacing `X-Guest-Remaining` from a streaming chat response
  may need a small plumbing change in the remote client; if brittle, MVP ships count-free
  ("试用中") and reacts to 403 only. Exact count is a follow-on nicety, not a blocker.
