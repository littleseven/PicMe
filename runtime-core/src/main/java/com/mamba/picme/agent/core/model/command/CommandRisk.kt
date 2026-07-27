package com.mamba.picme.agent.core.model.command

/**
 * 命令风险分级（纯数据映射，无业务逻辑）—— **写操作确认策略的单一来源（SSOT）**。
 *
 * 等级：
 * - [READ_ONLY]：只读操作，直接执行，无需用户确认
 * - [REVERSIBLE_WRITE]：可逆写操作（收藏/选中），需用户确认
 * - [DESTRUCTIVE]：破坏性操作（删除/分享外发），需用户确认且 UI 用警示色
 *
 * ## 写操作确认两层策略（刻意设计，非缺陷）
 *
 * 同一条写命令（如 `delete_media`）可由两条链路触发，**确认手段不同是刻意的**：
 *
 * - **Tier A — JS 沙箱内（`capability.dispatch`）**：JS 可由 `gallery.query` 计算出大批量 id
 *   再批量删除，风险高且来源是「计算结果」而非显式指定。故必须经应用内确认
 *   （`WriteConfirmationController`，带缩略图预览，且仅在脚本生命周期内有效——防孤儿确认），
 *   由 [CapabilityDispatchHandler] 在 dispatch 前调 `CommandRisk.ofMethod` 分级并请求确认。
 * - **Tier B — 顶层 @Tool 直调（`ChatToolService.delete_media` 等）**：远程 ReAct LLM 显式下发
 *   目标，由 ReAct 循环对用户透明；删除另经系统 MediaStore 授权框兜底。不重复弹应用内确认。
 *
 * 两条链路最终都汇聚到 `ChatMediaWriteCapability`（CHAT 场景媒体写执行点），区别仅在
 * 「确认发生在 dispatch 之前（Tier A）还是依赖系统授权（Tier B）」。详见
 * `docs/02-ARCHITECTURE/AGENT_ARCHITECTURE.md` 路由策略。
 *
 * **维护约定**：新增破坏型 method 时，必须同步登记两处——
 * ① 本表 [ofMethod] 的风险分级；② app 层 CapabilityDispatchHandler 的 method 白名单
 * （buildCommand 的 when 分支与 SUPPORTED_METHODS）。漏登本表会被默认 READ_ONLY 直通，
 * 漏登白名单则 JS 调不通。
 */
enum class CommandRisk {
    READ_ONLY,
    REVERSIBLE_WRITE,
    DESTRUCTIVE;

    companion object {
        /**
         * 按 method 名（见 [AgentCommand.getMethodName]）查风险等级。
         * 未列出的 method 一律视为 [READ_ONLY]。
         */
        fun ofMethod(method: String): CommandRisk = when (method) {
            "delete_media", "share_media" -> DESTRUCTIVE
            "favorite_media", "select_media",
            "remember_person_relation", "forget_person_relation",
            "remember_fact", "forget_fact" -> REVERSIBLE_WRITE
            else -> READ_ONLY
        }
    }
}
