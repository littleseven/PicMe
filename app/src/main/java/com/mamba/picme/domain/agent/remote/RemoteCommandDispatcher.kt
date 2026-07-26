package com.mamba.picme.domain.agent.remote

import android.content.Context
import android.view.WindowManager
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentScene
import com.mamba.picme.agent.core.model.config.AiAgentMode
import com.mamba.picme.agent.core.model.context.PageContext
import com.mamba.picme.agent.core.facade.AgentOrchestrator
import com.mamba.picme.agent.core.inference.remote.tool.PoLangToolService
import com.mamba.picme.core.common.Logger
import com.mamba.picme.data.local.ChatMessageDao
import com.mamba.picme.data.local.ChatMessageEntity
import com.mamba.picme.data.local.ChatSessionDao
import com.mamba.picme.data.local.ChatSessionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID

/**
 * 远程命令调度器
 *
 * 接收飞书消息，统一通过 [AgentOrchestrator.processRemoteImInput] 处理，
 * 使用 ReAct 循环完成应用内 UI 自动化，并将结果通过 [FeishuChannelHandler] 回复给用户。
 *
 * **架构（2026-06-18，ADR-006 Phase 5）**：
 * - ReAct Agent 生命周期由 [AgentConfigurator] 管理（懒创建、缓存、清理）
 * - [AgentOrchestrator] 提供统一的 `processRemoteImInput()` 入口
 * - 本调度器仅负责：消息接收 → 调用 Orchestrator → 结果回复
 *
 * **聊天记录同步（2026-06-19）**：
 * - 飞书消息收发同步写入本地 Room 数据库（sessionId = "feishu"）
 * - 用户可在 App 内聊天页面查看与飞书的完整对话历史
 *
 * **ANR 防护**：
 * - 前一个任务未完成时收到新消息，自动取消旧任务
 * - 超时保护（120 秒），避免 LLM 推理长时间占用 CPU
 * - 使用 Dispatchers.IO 避免阻塞 Default 调度器
 */
class RemoteCommandDispatcher(
    private val channelHandler: FeishuChannelHandler,
    context: Context,
    private val chatMessageDao: ChatMessageDao,
    private val chatSessionDao: ChatSessionDao
) {

    private val tag = "RemoteDispatcher"
    private val appContext = context.applicationContext
    private val orchestrator = AgentOrchestrator.getInstance(context)

    /** 飞书会话固定 ID */
    private val feishuSessionId = "feishu"

    /** 当前正在执行的 Job，用于新消息到达时取消旧任务 */
    @Volatile
    private var currentJob: Job? = null

    /** ReAct 循环超时（毫秒）— 多轮交互需要更长 timeout */
    private val TIMEOUT_MS = 120_000L

    /**
     * 接收飞书消息并启动 ReAct Agent 处理
     *
     * 统一通过 [AgentOrchestrator.processRemoteImInput] 执行 ReAct 循环，
     * 当 Agent 不可用时回退到原有 [AgentOrchestrator.processUserInput] 路径。
     * 所有收发消息同步写入本地聊天记录。
     *
     * **飞书拍照追踪**：若命令包含拍照意图，设置 [FeishuPhotoTracker] 状态，
     * 照片保存完成后自动发送到飞书。
     */
    suspend fun dispatch(text: String, messageId: String) {
        Logger.i(tag, "远程命令: text='$text', messageId=$messageId")
        currentJob?.cancel()

        // 确保飞书会话元数据存在
        ensureFeishuSession()

        // 持久化收到的飞书用户消息
        saveUserMessage(text)

        // 若用户请求包含拍照，标记追踪器（照片保存后自动发送）
        if (text.contains("拍照") || text.contains("拍张") || text.contains("拍照片")) {
            FeishuPhotoTracker.startCapture(messageId)
            Logger.i(tag, "飞书拍照追踪已启动: messageId=$messageId")
        }

        // ── 快速通道：相册搜索 + 预览 ──
        // 对于明确的“搜索照片”指令（可能附带“预览第 N 张”），直接走工具调用，避免依赖 LLM 是否遵循 prompt。
        val directSearchQuery = extractSearchQuery(text)
        if (directSearchQuery != null) {
            withContext(Dispatchers.IO) {
                channelHandler.sendMessage("⏳ 正在搜索照片...", messageId)
                val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                val previewIndex = extractPreviewIndex(text)
                val result = if (wm != null) {
                    executeDirectGallerySearch(directSearchQuery, previewIndex, wm)
                } else {
                    "❌ WindowManager 不可用"
                }
                saveAgentMessage(result)
                channelHandler.sendMessage(result, messageId)
            }
            return
        }

        withContext(Dispatchers.IO) {
            channelHandler.sendMessage("⏳ 正在处理您的请求...", messageId)

            val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (wm != null) {
                // ── ReAct Agent 路径（统一走 AgentOrchestrator）──
                try {
                    val result = withTimeout(TIMEOUT_MS) {
                        orchestrator.processRemoteImInput(text, wm, TIMEOUT_MS)
                    }
                    val reply = result.fold(
                        onSuccess = { it },
                        onFailure = { error -> "❌ ${error.message ?: "未知错误"}" }
                    )
                    Logger.i(tag, "远程命令执行完毕，回复：$reply")

                    // [飞书拍照] 如果包含拍照命令，Agent 回复改为"处理中"提示
                    // 实际拍照成功/失败由 observeFeishuPhotoCapture 通知
                    val isPhotoCommand = text.contains("拍照") || text.contains("拍张") || text.contains("拍照片")
                    val finalReply = if (isPhotoCommand) {
                        // 如果 Agent 已经说了类似"拍好了"的话，保持不变
                        // 否则替换为处理中提示
                        if (reply.contains("拍") && (reply.contains("好") || reply.contains("成功") || reply.contains("完成"))) {
                            "📸 正在拍照，请稍候..."
                        } else {
                            reply
                        }
                    } else {
                        reply
                    }

                    // 持久化 Agent 回复
                    saveAgentMessage(finalReply)
                    channelHandler.sendMessage(finalReply, messageId)
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    val timeoutMsg = "⏰ 处理超时（${TIMEOUT_MS / 1000}秒），请稍后重试"
                    saveAgentMessage(timeoutMsg)
                    channelHandler.sendMessage(timeoutMsg, messageId)
                }
            } else {
                // ── 回退路径 ──
                Logger.i(tag, "WindowManager 不可用，回退到原有路径")
                fallbackProcess(text, messageId)
            }
        }
    }

    /**
     * 回退到原有的 AgentOrchestrator 流程
     */
    private suspend fun fallbackProcess(text: String, messageId: String) {
        try {
            val agentContext = AgentContext(scene = AgentScene.CHAT)
            orchestrator.pushModeOverride(AiAgentMode.REMOTE)
            try {
                val result = withTimeout(30_000L) {
                    orchestrator.processUserInput(
                        input = text,
                        agentContext = agentContext,
                        pageContext = PageContext.None
                    )
                }
                val reply = result.fold(
                    onSuccess = { action -> formatActionReply(action) },
                    onFailure = { error -> "❌ 处理失败: ${error.message ?: "未知错误"}" }
                )
                saveAgentMessage(reply)
                channelHandler.sendMessage(reply, messageId)
            } finally {
                orchestrator.popModeOverride()
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            val timeoutMsg = "⏰ 处理超时，请稍后重试"
            saveAgentMessage(timeoutMsg)
            channelHandler.sendMessage(timeoutMsg, messageId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            val cancelMsg = "⏹️ 上一个任务已取消，正在处理新请求..."
            saveAgentMessage(cancelMsg)
            channelHandler.sendMessage(cancelMsg, messageId)
        } catch (e: Exception) {
            val errorMsg = "❌ 处理异常: ${e.message ?: "未知错误"}"
            saveAgentMessage(errorMsg)
            channelHandler.sendMessage(errorMsg, messageId)
        }
    }

    private fun formatActionReply(action: AgentAction): String {
        return when (action) {
            is AgentAction.TextReply -> action.message
            is AgentAction.MediaResults -> "找到 ${action.totalCount} 张「${action.query}」的照片"
            is AgentAction.Success -> "✅ 操作已执行"
            is AgentAction.Error -> "❌ ${action.message}"
            is AgentAction.BatchResult -> {
                val parts = action.results.mapIndexed { i, r ->
                    when (r) {
                        is AgentAction.Success -> "  ✅ 步骤${i + 1}: 成功"
                        is AgentAction.TextReply -> "  💬 步骤${i + 1}: ${r.message}"
                        is AgentAction.Error -> "  ❌ 步骤${i + 1}: ${r.message}"
                        is AgentAction.BatchResult -> "  📦 步骤${i + 1}: 批量完成"
                        is AgentAction.MediaResults -> "  🖼️ 步骤${i + 1}: 找到 ${r.totalCount} 张照片"
                    }
                }
                "📋 批量执行结果:\n${parts.joinToString("\n")}"
            }
        }
    }

    // ── 聊天记录持久化 ─────────────────────────────────────────────

    private suspend fun ensureFeishuSession() {
        try {
            val existing = chatSessionDao.getSession(feishuSessionId)
            if (existing == null) {
                chatSessionDao.insertSession(
                    ChatSessionEntity(
                        sessionId = feishuSessionId,
                        title = "飞书远程控制"
                    )
                )
                Logger.i(tag, "Created feishu chat session")
            }
        } catch (e: Exception) {
            Logger.w(tag, "Failed to ensure feishu session", e)
        }
    }

    private suspend fun saveUserMessage(content: String) {
        try {
            chatMessageDao.insertMessage(
                ChatMessageEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = feishuSessionId,
                    type = "user_text",
                    content = content,
                    modelUsed = null
                )
            )
            chatSessionDao.touchSession(feishuSessionId)
        } catch (e: Exception) {
            Logger.w(tag, "Failed to save user message", e)
        }
    }

    private suspend fun saveAgentMessage(content: String) {
        try {
            chatMessageDao.insertMessage(
                ChatMessageEntity(
                    id = UUID.randomUUID().toString(),
                    sessionId = feishuSessionId,
                    type = "agent_text",
                    content = content,
                    modelUsed = "feishu_remote"
                )
            )
            chatSessionDao.touchSession(feishuSessionId)
        } catch (e: Exception) {
            Logger.w(tag, "Failed to save agent message", e)
        }
    }

    /**
     * 从用户输入中提取相册搜索关键词。
     * 支持的句式：
     * - 搜索去年夏天小孩的照片
     * - 进入相册，搜索“去年夏天小孩”
     * - 查找上海的照片
     * - 打开相册，搜索7月的美女，预览第四张
     * - 打开相册，预览7月1日的第四张美女图片
     *
     * 遇到“预览/查看/打开/点击/第 N 张”等后续动作词时停止，避免把预览指令也当成搜索词。
     */
    private fun extractSearchQuery(text: String): String? {
        val cleaned = text.replace("[\"“”]".toRegex(), "")
        val stopWords = "，?(?:预览|查看|打开|点击|第[一二三四五六七八九十0-9]+张)"
        val patterns = listOf(
            // 显式搜索动词
            "搜索[:：]?(.+?)(?:的照片|(?=$stopWords)|\$)".toRegex(),
            "查找[:：]?(.+?)(?:的照片|(?=$stopWords)|\$)".toRegex(),
            "找(.+?)(?:的照片|(?=$stopWords)|\$)".toRegex(),
            // 省略搜索动词、直接“预览/查看/打开 X 的第 N 张”
            "预览(.+?)第[一二三四五六七八九十0-9]+张".toRegex(),
            "查看(.+?)第[一二三四五六七八九十0-9]+张".toRegex(),
            "打开(.+?)第[一二三四五六七八九十0-9]+张".toRegex()
        )
        for (pattern in patterns) {
            pattern.find(cleaned)?.groupValues?.get(1)?.trim()?.let {
                // 过滤掉纯“相册”这种无意义 query
                if (it.isNotBlank() && it != "相册") return it
            }
        }
        return null
    }

    /**
     * 从用户输入中提取“预览第 N 张”的序号。
     * 支持中文数字（第四张）和阿拉伯数字（第4张）。
     */
    private fun extractPreviewIndex(text: String): Int? {
        val matchResult = "第([一二三四五六七八九十0-9]+)张".toRegex().find(text) ?: return null
        return chineseNumberToInt(matchResult.groupValues[1])
    }

    /**
     * 中文/阿拉伯数字转 Int。
     */
    private fun chineseNumberToInt(text: String): Int? {
        text.toIntOrNull()?.let { return it }
        val chars = mapOf(
            '一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5,
            '六' to 6, '七' to 7, '八' to 8, '九' to 9
        )
        var result = 0
        var temp = 0
        for (c in text) {
            when {
                c in chars -> temp = chars[c]!!
                c == '十' -> {
                    result += if (temp == 0) 10 else temp * 10
                    temp = 0
                }
                c == '百' -> {
                    result += if (temp == 0) 100 else temp * 100
                    temp = 0
                }
                else -> return null
            }
        }
        result += temp
        return if (result > 0) result else null
    }

    /**
     * 直接执行相册搜索（并可选预览第 N 张），不经过 LLM ReAct 循环。
     */
    private fun executeDirectGallerySearch(query: String, previewIndex: Int?, wm: WindowManager): String {
        return try {
            val toolService = PoLangToolService(wm)
            val navigateResult = toolService.navigateTo("gallery")
            if (navigateResult.startsWith("Error:")) {
                return "❌ 进入相册失败：$navigateResult"
            }
            val searchResult = toolService.searchPhotos(query)
            if (searchResult.startsWith("Error:")) {
                return "❌ 搜索失败：$searchResult"
            }
            if (previewIndex != null) {
                val clickResult = toolService.clickGalleryItem(previewIndex)
                return if (clickResult.startsWith("Error:")) {
                    "✅ 已搜索“$query”，但预览第 ${previewIndex} 张失败：$clickResult"
                } else {
                    "✅ 已搜索“$query”并预览第 ${previewIndex} 张照片"
                }
            }
            "✅ 已完成相册搜索：$searchResult"
        } catch (e: Exception) {
            Logger.e(tag, "Direct gallery search failed", e)
            "❌ 搜索执行异常：${e.message ?: "未知错误"}"
        }
    }
}
