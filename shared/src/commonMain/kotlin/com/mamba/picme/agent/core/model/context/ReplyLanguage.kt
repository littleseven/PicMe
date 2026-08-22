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
 *   仅当设置为 [AppLanguage.SYSTEM] 时参与解析。容忍 iOS 下划线分隔形式（`zh_TW`、
 *   `zh_Hant`，先归一为 BCP-47）；粤语（`yue`/`yue-*`）按中文同规则解析（iOS 粤语系统
 *   语言下 UI 回退繁中，chat 回复语言须与之一致）。
 */
fun AppLanguage.toReplyLanguage(systemLocaleTag: String): ReplyLanguage = when (this) {
    AppLanguage.ENGLISH -> ReplyLanguage.ENGLISH
    AppLanguage.CHINESE -> ReplyLanguage.SIMPLIFIED_CHINESE
    AppLanguage.TRADITIONAL_CHINESE -> ReplyLanguage.TRADITIONAL_CHINESE
    AppLanguage.SYSTEM -> resolveSystemReplyLanguage(systemLocaleTag)
}

private fun resolveSystemReplyLanguage(localeTag: String): ReplyLanguage {
    // 先归一为 BCP-47 形态：iOS Locale.identifier 用下划线分隔（如 zh_TW、zh_Hant）
    val tag = localeTag.replace('_', '-').lowercase()
    // yue（粤语）按中文同规则解析：iOS 粤语系统语言下 UI 回退繁中，chat 回复语言须与之一致
    val isChinese = tag == "zh" || tag.startsWith("zh-") || tag == "yue" || tag.startsWith("yue-")
    if (!isChinese) return ReplyLanguage.ENGLISH
    return if (tag.contains("-hant") || tag.contains("-tw") ||
        tag.contains("-hk") || tag.contains("-mo")
    ) {
        ReplyLanguage.TRADITIONAL_CHINESE
    } else {
        ReplyLanguage.SIMPLIFIED_CHINESE
    }
}

/**
 * 追加到 chat system prompt 最末尾的语言规则段（RemoteChatEngine.buildPromptSuffix 拼装）。
 * 规则文本本身用目标语言书写（自我强化），并显式对抗全中文 base prompt 与中文工具输出的引力。
 * 三语文本集中此处，双端共用，防漂移。
 */
internal fun replyLanguageRuleSegment(language: ReplyLanguage): String = when (language) {
    ReplyLanguage.ENGLISH ->
        "The app's UI language is English. Always reply to the user in English, regardless of the language of this prompt, tool descriptions, or tool outputs. Tool results may be in Chinese — summarize and present them in English."
    ReplyLanguage.SIMPLIFIED_CHINESE ->
        "App 界面语言为简体中文。请始终用简体中文回复用户，无论本提示词、工具描述或工具返回内容使用何种语言。工具返回内容可能是英文或其他语言——请用简体中文总结转述。"
    ReplyLanguage.TRADITIONAL_CHINESE ->
        "App 介面語言為繁體中文。請始終用繁體中文回覆使用者，無論本提示詞、工具描述或工具回傳內容使用何種語言。工具回傳內容可能是英文或其他語言——請用繁體中文總結轉述。"
}
