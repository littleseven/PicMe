# Remote Diagnosis Chat-UI v2（诊断作为 chat 输入模式）

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans. `- [ ]` 为步骤标记。

**Goal:** 把远程诊断从"旁路胶囊"改成**chat 的一等输入模式**：二态「诊断」toggle 激活后，**发送按钮**触发诊断（建用户气泡 + 清输入，复用 sendMessage 的动作）；根因作为助手气泡、**确认按钮内嵌在气泡里**（去掉独立 sheet）。

**背景（v1 的问题）**：胶囊无二态、不走发送按钮、无用户气泡/文字残留、形态割裂。v2 全部回归 chat 发送通道。

**Tech Stack:** Kotlin / Jetpack Compose（Material3）/ Coroutines。基于已合并的 v1（DiagController/DiagClient/DiagBundleCollector/BuildConfig 已就位）。

**改动范围（修订 v1）：**
- `ChatScreen.kt`：`ChatMessageUi` 加 `diagConfirm` 字段；`ChatMessageItem` 渲染内嵌按钮；`ChatInputArea` 诊断胶囊→二态 toggle + 发送路由；消息列表透传 `onDiagConfirm`；**移除** DiagConfirmSheet 渲染。
- `ChatViewModel.kt`：`submitDiagnosis` 建用户气泡 + 根因气泡带 `diagConfirm(pending)`；`confirmDiagnosis` 更新气泡为 resolved/结果。
- 删 `components/DiagConfirmSheet.kt`（被内嵌按钮取代）。`DiagController` 保留作纯状态（或内联到 message 字段，见 Task 1 决定）。

---

## Task 1: ChatMessageUi 加 diagConfirm 字段 + DiagConfirmUi 模型

**Files:** `ChatScreen.kt`（ChatMessageUi data class）

- [ ] **Step 1:** 在 `ChatMessageUi` data class 加可空字段（默认 null，不影响既有构造）：
```kotlin
    /** 诊断根因气泡的内嵌确认动作；非空且 pending 时渲染 [推送]/[PR] 按钮。 */
    val diagConfirm: DiagConfirmUi? = null,
```
并在同文件加（ChatMessageUi 附近）：
```kotlin
/** 诊断确认内嵌按钮状态。pending=true 显示按钮；false 则已处理（按钮消失）。 */
data class DiagConfirmUi(val jobId: Int, val pending: Boolean)
```
- [ ] **Step 2:** `./gradlew :app:compileDebugKotlin -q | tail -3` → BUILD SUCCESSFUL（默认 null，6 处测试构造不破）。
- [ ] **Step 3:** Commit `refactor(app): ChatMessageUi 加 diagConfirm 字段 + DiagConfirmUi`

---

## Task 2: ChatMessageItem 渲染内嵌确认按钮

**Files:** `ChatScreen.kt`（ChatMessageItem）

- [ ] **Step 1:** 给 `ChatMessageItem` 加回调参数：`onDiagConfirm: (Int, String) -> Unit = { _, _ -> }`。
- [ ] **Step 2:** 在气泡 `Column` 内、markdown 文本之后，加条件按钮行：
```kotlin
                // 诊断根因：内嵌确认按钮（pending 时显示）
                message.diagConfirm?.let { dc ->
                    if (dc.pending) {
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onDiagConfirm(dc.jobId, "push") }) {
                                Text(stringResource(R.string.diag_sheet_push))
                            }
                            Button(onClick = { onDiagConfirm(dc.jobId, "pr") }) {
                                Text(stringResource(R.string.diag_sheet_pr))
                            }
                        }
                    }
                }
```
（复用既有 diag_sheet_push/pr 文案；位置在 `when{...}` 的 else 分支 markdown 之后，需读 ChatMessageItem 的 else 分支确认插入点。）
- [ ] **Step 3:** 在消息列表渲染处（LazyColumn items → ChatMessageItem(...)）透传 `onDiagConfirm = { jobId, mode -> viewModel.confirmDiagnosis(mode) }`。（jobId 参数可忽略——confirmDiagnosis 用 activeDiag；回调签名保留 jobId 供将来多任务。）
- [ ] **Step 4:** compileDebugKotlin 过 → Commit `feat(app): ChatMessageItem 诊断根因气泡内嵌确认按钮`

---

## Task 3: ChatInputArea 二态 toggle + 发送路由

**Files:** `ChatScreen.kt`（ChatInputArea + ChatTextInputMode）

- [ ] **Step 1:** `ChatInputArea` 加状态 `var diagMode by remember { mutableStateOf(false) }`。
- [ ] **Step 2:** 诊断胶囊改成 toggle（二态视觉）：
```kotlin
                // 远程诊断模式 toggle（二态）
                val diagActive = diagMode  // 由 ChatInputArea 传入
                CapsuleButton(
                    icon = Icons.Rounded.Code,
                    label = stringResource(R.string.diag_icon_desc),
                    onClick = onToggleDiag,
                    enabled = !isProcessing,
                    // active 态高亮：用不同容器色/描边（CapsuleButton 若不支持 active 参数，外层包一层 background）
                )
```
（若 `CapsuleButton` 无 active 形参，外层用 `Box(background = if(diagActive) primaryContainer else transparent)` 包裹体现二态。需要先读 CapsuleButton 签名决定。）
- [ ] **Step 3:** 发送路由：`ChatTextInputMode` 的 `onSend` 在文本分支里，按 diagMode 分流：
```kotlin
                text.isNotBlank() -> {
                    if (diagMode) onDiagnose(text.trim()) else onSendMessage(text.trim())
                    text = ""
                    keyboardController?.hide()
                }
```
（onDiagnose 已存在 = viewModel.submitDiagnosis。需把 diagMode + onToggleDiag 透传进 ChatTextInputMode。）
- [ ] **Step 4:** compileDebugKotlin 过 → Commit `feat(app): 诊断二态 toggle + 发送按钮路由到诊断模式`

> 效果：① toggle 有激活态视觉（二态）② 走发送按钮 ③ 发后 text="" 清空（修文字残留）。

---

## Task 4: ChatViewModel —— 用户气泡 + 根因气泡带 diagConfirm + 确认更新

**Files:** `ChatViewModel.kt`

- [ ] **Step 1:** `submitDiagnosis` 开头先建**用户气泡**（诊断标记），再建"分析中"助手气泡：
```kotlin
    fun submitDiagnosis(description: String) {
        if (description.isBlank()) return
        // 用户气泡（诊断标记）——和普通用户消息一样有气泡
        upsertMessage(UUID.randomUUID().toString(), ChatMessageType.USER_TEXT, "🔍 " + description)
        viewModelScope.launch {
            ... // 原有 token/bundle/report/poll 逻辑不变
            // DIAGNOSED 时：根因助手气泡带 diagConfirm(pending)
            upsertDiagMessage(msgId, context.getString(R.string.diag_root_cause, rc),
                diagConfirm = DiagConfirmUi(jobId, pending = true))
            ... // 不再 diagController.requestConfirm —— 状态进 message
        }
    }
```
（需把 upsertDiagMessage 扩展为可带 diagConfirm 参数；USER_TEXT 气泡用新 upsertMessage helper 或直接 _messages.update。）
- [ ] **Step 2:** `confirmDiagnosis(mode)`：成功后把根因气泡的 `diagConfirm` 置 pending=false（按钮消失）+ 内容更新为"修复中…"；pollFix 完成后内容更新为结果。
- [ ] **Step 3:** 移除对 `diagController`/`pendingDiagConfirm`/`cancelDiagConfirm` 的依赖（状态改由 message.diagConfirm 承载）；可保留 DiagController 类不删（低风险），只是 ChatViewModel 不再用它驱动 UI。
- [ ] **Step 4:** compileDebugKotlin 过 → Commit `feat(app): 诊断用户气泡 + 根因气泡内嵌确认状态（替代 sheet）`

---

## Task 5: 移除 DiagConfirmSheet + ChatScreen 渲染

**Files:** `ChatScreen.kt`，删 `components/DiagConfirmSheet.kt`

- [ ] **Step 1:** 删 `ChatScreen` 里 `pendingDiag by viewModel.pendingDiagConfirm.collectAsState()` + `DiagConfirmSheet(...)` 那段（被内嵌按钮取代）。
- [ ] **Step 2:** `git rm app/src/main/java/com/mamba/picme/features/chat/components/DiagConfirmSheet.kt`
- [ ] **Step 3:** compileDebugKotlin 过 → Commit `refactor(app): 移除 DiagConfirmSheet（改为气泡内嵌按钮）`

---

## Task 6: 文案 + 全量构建

- [ ] 用户气泡前缀 "🔍 "（可考虑抽 `diag_user_prefix` 文案，但前缀符号可接受硬编码；描述部分是用户输入）。确认 diag_sheet_push/pr/diag_root_cause 等 v1 已有的文案仍被复用。补任何缺失三语。
- [ ] `./gradlew :app:assembleDebug 2>&1 | tail -5` → BUILD SUCCESSFUL（或 compileDebugKotlin 兜底）。
- [ ] Commit `feat(app): 远程诊断 chat-UI v2 三语文案收尾`

---

## Self-Review

- **修了 v1 的 4 个问题**：① 二态 toggle（T3）② 走发送按钮（T3 onSend 分流）③ 用户气泡 + 清输入（T4 用户气泡 + T3 text=""）④ 形态（诊断=输入模式，根因/结果=助手气泡，确认内嵌）。
- **复用**：DiagClient/DiagBundleCollector/BuildConfig/diag_* 文案不变；sendMessage 的建气泡/清输入动作在 ChatInputArea 复用。
- **移除**：DiagConfirmSheet（独立 modal）→ 内嵌按钮。DiagController 不再驱动 UI（状态进 message.diagConfirm）。
- **风险点**：CapsuleButton 是否支持 active 二态视觉（T3 需读其签名，必要时外层包背景）；ChatMessageItem 的 else 分支插入点（T2 需读确认）；onDiagConfirm 回调从 LazyColumn 透传到 ChatMessageItem。

## Done criteria

- [ ] compileDebugKotlin / assembleDebug 过。
- [ ] 诊断 toggle 有激活态；发送走发送键；发后有用户气泡 + 输入清空；根因气泡内嵌 [推送]/[PR]；点按钮后按钮消失、变"修复中…→结果"。
- [ ] DiagConfirmSheet 删除。
