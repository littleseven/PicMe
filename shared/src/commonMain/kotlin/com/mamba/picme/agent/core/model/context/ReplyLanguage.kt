package com.mamba.picme.agent.core.model.context

import com.mamba.picme.domain.model.AppLanguage

/**
 * Chat 回复语言（解析后的具体语言，非设置项）。
 *
 * 与 [AppLanguage] 分离：`AppLanguage.SYSTEM` 不是具体回复语言，必须经
 * [toReplyLanguage] 结合系统 locale 解析后使用。
 *
 * 先行落地供助手性格段选语言（2026-08-22-assistant-persona-design.md）；
 * 「回复语言跟随界面语言」规则段复用本枚举（2026-08-22-chat-reply-language-design.md）。
 */
enum class ReplyLanguage { SIMPLIFIED_CHINESE, TRADITIONAL_CHINESE, ENGLISH }

/**
 * 把 App 界面语言设置解析为具体回复语言。
 *
 * @param systemLocaleTag 系统 locale 的 BCP-47 tag（Android: `Locale.getDefault().toLanguageTag()`），
 *   仅当设置为 [AppLanguage.SYSTEM] 时参与解析。
 */
fun AppLanguage.toReplyLanguage(systemLocaleTag: String): ReplyLanguage = when (this) {
    AppLanguage.ENGLISH -> ReplyLanguage.ENGLISH
    AppLanguage.CHINESE -> ReplyLanguage.SIMPLIFIED_CHINESE
    AppLanguage.TRADITIONAL_CHINESE -> ReplyLanguage.TRADITIONAL_CHINESE
    AppLanguage.SYSTEM -> resolveSystemReplyLanguage(systemLocaleTag)
}

private fun resolveSystemReplyLanguage(localeTag: String): ReplyLanguage {
    val tag = localeTag.lowercase()
    if (!tag.startsWith("zh")) return ReplyLanguage.ENGLISH
    return if (tag.contains("hant") || tag.contains("-tw") ||
        tag.contains("-hk") || tag.contains("-mo")
    ) {
        ReplyLanguage.TRADITIONAL_CHINESE
    } else {
        ReplyLanguage.SIMPLIFIED_CHINESE
    }
}
