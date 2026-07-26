package com.mamba.picme.agent.core.model.command

/**
 * 命令风险分级（纯数据映射，无业务逻辑）。
 *
 * 供「JS → CapabilityRegistry 写通路」（capability.dispatch）做确认分级：
 * - [READ_ONLY]：只读操作，直接执行，无需用户确认
 * - [REVERSIBLE_WRITE]：可逆写操作（收藏/选中），需用户确认
 * - [DESTRUCTIVE]：破坏性操作（删除/分享外发），需用户确认且 UI 用警示色
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
