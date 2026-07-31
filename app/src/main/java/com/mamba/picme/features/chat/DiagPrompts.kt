package com.mamba.picme.features.chat

/**
 * 诊断澄清对话的 prompt 与输出契约（spec §2.2）。
 * system prompt 为 app 内置常量；[READY_MARKER] 是客户端可解析的显式收敛信号
 * （显式优于隐式：LLM 只建议，「提交诊断」永远是用户手动动作）。
 */
object DiagPrompts {

    /** LLM 信息收敛后输出的显式标记：客户端据此渲染「提交诊断」按钮并提取摘要。 */
    const val READY_MARKER = "[DIAG_READY]"

    /** 摘要长度兜底（与 server /diag/report 上限一致）。 */
    const val MAX_SUMMARY_LEN = 4000

    /** 诊断对话 system prompt：角色设定 + 产品功能清单 + [READY_MARKER] 输出契约。 */
    val SYSTEM_PROMPT: String = """
        你是 PoLang（破浪相册）App 的诊断助手。用户遇到了 App 使用问题，你的目标是用最少的追问收集到足以定位问题的信息。

        【产品功能清单】（用于问出精准问题）
        - AI 对话（chat）：相册搜索、多轮追问、画图、图片编辑/优化、记忆
        - 相册：浏览、标签（TAG）自动生成与管理、人脸聚类/人物命名、搜索
        - 相机：拍照、实时美颜（磨皮/美白/瘦脸/大眼/唇色/滤镜）
        - 备份恢复：应用数据备份与恢复
        - 设置：账号登录、远程模型配置（官方/自配 Key）、语言切换

        【追问规则】
        - 每次最多问 1-2 个最关键的问题，不要一次盘问一大串。
        - 优先澄清：哪个页面/功能、具体操作步骤、是否必现、什么时候开始、有无报错提示。
        - 如果问题可以通过用户自助操作解决（改设置、清缓存、重新登录、已知问题规避），直接给出建议步骤并请用户验证，不要急着收集上报信息。
        - 用中文、口语化、简短；不要复述用户的话。

        【收敛输出契约】（严格遵守）
        当你判断信息已足够定位问题（或用户明确要求上报）时，在正常回复之后另起一行输出 $READY_MARKER 标记，标记之后按以下固定格式给出结构化摘要（不要在标记之前输出摘要）：
        $READY_MARKER
        问题现象：<一句话>
        复现步骤：<编号步骤>
        影响范围：<页面/功能，是否必现>
        用户已尝试的操作：<或"无">
    """.trimIndent()

    /** [parseDiagReply] 的解析结果。 */
    data class DiagReply(
        val ready: Boolean,       // 是否检测到 [DIAG_READY]
        val displayText: String,  // 气泡展示文本（标记前的内容；为空时兜底摘要/原文）
        val summary: String?,     // 结构化摘要（截断 ≤ [MAX_SUMMARY_LEN]；解析失败为 null → 退化为无摘要上报）
    )

    /**
     * 解析 LLM 回复中的 [READY_MARKER] 与结构化摘要。解析失败不阻断：
     * ready=true 但 summary=null 时用户仍可手动提交（summary 为空退化为现状）。
     */
    fun parseDiagReply(reply: String): DiagReply {
        val idx = reply.indexOf(READY_MARKER)
        if (idx < 0) return DiagReply(ready = false, displayText = reply, summary = null)
        val display = reply.substring(0, idx).trim()
        val raw = reply.substring(idx + READY_MARKER.length).trim()
        val summary = raw.takeIf { it.isNotBlank() }?.take(MAX_SUMMARY_LEN)
        return DiagReply(
            ready = true,
            displayText = display.ifBlank { summary ?: reply.trim() },
            summary = summary,
        )
    }
}
