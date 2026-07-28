package com.mamba.picme.agent.core.platform.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.agent.core.platform.thread.ThreadPoolManager
import com.mamba.data.message.AiMessage
import com.mamba.data.message.ChatMessage
import com.mamba.data.message.ImageContent
import com.mamba.data.message.SystemMessage
import com.mamba.data.message.TextContent
import com.mamba.data.message.ToolExecutionResultMessage
import com.mamba.data.message.UserMessage
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

private val Context.agentMemoryDataStore: DataStore<Preferences> by preferencesDataStore(name = "agent_memory")

/**
 * 对话记忆管理器
 *
 * 负责对话历史的持久化、上下文窗口管理和记忆隔离。
 * 按场景分 session 存储，支持跨会话记忆恢复。
 * **线程模型**：所有 DataStore 读写操作由 [ThreadPoolManager] 集中管理的专用单线程
 *（PoLang-DataStore-Thread）串行执行，与本地 LLM 推理和网络请求完全隔离，
 * 不会相互阻塞或竞争。
 *
 * @param context Application Context
 */
class MemoryManager(private val context: Context) {

    private val tag = "MemoryManager"
    private val dataStore = context.agentMemoryDataStore

    private val dataStoreDispatcher = ThreadPoolManager.getInstance().dataStoreDispatcher

    /**
     * 每个 session 的最大消息数
     */
    private val maxMessagesPerSession = 20

    /**
     * 构建 prompt 时保留的最大历史轮数（一轮 = user + assistant）
     */
    private val maxHistoryRounds = 5

    /**
     * 加载指定 session 的对话历史
     *
     * @param sessionId 会话 ID（如 "camera", "gallery"）
     * @return 消息列表
     */
    suspend fun loadHistory(sessionId: String): List<ChatMessage> = withContext(dataStoreDispatcher) {
        return@withContext try {
            val key = stringPreferencesKey("memory_$sessionId")
            val jsonStr = withTimeout(5000) {
                dataStore.data.map { preferences ->
                    preferences[key] ?: "[]"
                }.first()
            }

            if (jsonStr == "[]") {
                emptyList()
            } else {
                parseMessagesFromJson(jsonStr)
            }
        } catch (exception: TimeoutCancellationException) {
            Logger.w(tag, "Timeout loading history for session $sessionId")
            emptyList()
        } catch (exception: Exception) {
            Logger.w(tag, "Failed to load history for session $sessionId", exception)
            emptyList()
        }
    }

    /**
     * 保存对话历史到指定 session
     *
     * @param sessionId 会话 ID
     * @param messages 消息列表
     */
    suspend fun saveHistory(sessionId: String, messages: List<ChatMessage>) = withContext(dataStoreDispatcher) {
        return@withContext try {
            val trimmed = trimToMaxSize(messages)
            val key = stringPreferencesKey("memory_$sessionId")
            val jsonStr = encodeMessagesToJson(trimmed)

            withTimeout(5000) {
                dataStore.edit { preferences ->
                    preferences[key] = jsonStr
                }
            }
            Logger.d(tag, "Saved ${trimmed.size} messages to session $sessionId")
        } catch (exception: TimeoutCancellationException) {
            Logger.w(tag, "Timeout saving history for session $sessionId")
        } catch (exception: Exception) {
            Logger.e(tag, "Failed to save history for session $sessionId", exception)
        }
    }

    /**
     * 追加消息到指定 session 的历史
     *
     * @param sessionId 会话 ID
     * @param message 新消息
     */
    /**
     * 原子追加消息：read-modify-write 在单个 DataStore [edit] 块内完成，
     * 避免并发追加丢更新（旧实现 load→改→save 跨两次 DataStore 操作，fire-and-forget 并发会覆盖）。
     */
    suspend fun appendMessage(sessionId: String, message: ChatMessage) = withContext(dataStoreDispatcher) {
        val key = stringPreferencesKey("memory_$sessionId")
        try {
            withTimeout(5000) {
                dataStore.edit { preferences ->
                    val current = preferences[key]?.let { parseMessagesFromJson(it) } ?: emptyList()
                    val updated = current.toMutableList().apply { add(message) }
                    preferences[key] = encodeMessagesToJson(trimToMaxSize(updated))
                }
            }
        } catch (exception: TimeoutCancellationException) {
            Logger.w(tag, "Timeout appending message for session $sessionId")
        } catch (exception: Exception) {
            Logger.e(tag, "Failed to append message for session $sessionId", exception)
        }
    }

    /**
     * 原子追加用户输入和助手回复（一轮对话）。
     *
     * @param sessionId 会话 ID
     * @param userInput 用户输入
     * @param assistantResponse 助手回复
     */
    suspend fun appendConversation(
        sessionId: String,
        userInput: String,
        assistantResponse: String
    ) = withContext(dataStoreDispatcher) {
        val key = stringPreferencesKey("memory_$sessionId")
        try {
            withTimeout(5000) {
                dataStore.edit { preferences ->
                    val current = preferences[key]?.let { parseMessagesFromJson(it) } ?: emptyList()
                    val updated = current.toMutableList().apply {
                        add(UserMessage.from(userInput))
                        add(AiMessage.from(assistantResponse))
                    }
                    preferences[key] = encodeMessagesToJson(trimToMaxSize(updated))
                }
            }
        } catch (exception: TimeoutCancellationException) {
            Logger.w(tag, "Timeout appending conversation for session $sessionId")
        } catch (exception: Exception) {
            Logger.e(tag, "Failed to append conversation for session $sessionId", exception)
        }
    }

    /**
     * 清空指定 session 的对话历史
     */
    suspend fun clearHistory(sessionId: String) = withContext(dataStoreDispatcher) {
        return@withContext try {
            val key = stringPreferencesKey("memory_$sessionId")
            withTimeout(5000) {
                dataStore.edit { preferences ->
                    preferences.remove(key)
                }
            }
            Logger.i(tag, "Cleared history for session $sessionId")
        } catch (exception: TimeoutCancellationException) {
            Logger.w(tag, "Timeout clearing history for session $sessionId")
        } catch (exception: Exception) {
            Logger.e(tag, "Failed to clear history for session $sessionId", exception)
        }
    }

    /**
     * 清空所有 session 的对话历史
     */
    suspend fun clearAllHistory() = withContext(dataStoreDispatcher) {
        return@withContext try {
            withTimeout(5000) {
                dataStore.edit { preferences ->
                    preferences.asMap().keys.filter { it.name.startsWith("memory_") }
                        .forEach { key ->
                            preferences.remove(key)
                        }
                }
            }
            Logger.i(tag, "Cleared all conversation history")
        } catch (exception: TimeoutCancellationException) {
            Logger.w(tag, "Timeout clearing all history")
        } catch (exception: Exception) {
            Logger.e(tag, "Failed to clear all history", exception)
        }
    }

    /**
     * 构建带历史上下文的 prompt
     *
     * 保留最近 [maxHistoryRounds] 轮对话 + system prompt
     *
     * @param sessionId 会话 ID
     * @param systemPrompt 系统提示词
     * @param userInput 当前用户输入
     * @return 完整消息列表（system + history + user）
     */
    suspend fun buildContextMessages(
        sessionId: String,
        systemPrompt: String,
        userInput: String
    ): List<ChatMessage> = withContext(dataStoreDispatcher) {
        val history = loadHistory(sessionId)
        val trimmedHistory = trimToRounds(history, maxHistoryRounds)

        val messages = mutableListOf<ChatMessage>()
        messages.add(SystemMessage.from(systemPrompt))
        messages.addAll(trimmedHistory)
        messages.add(UserMessage.from(userInput))

        return@withContext messages
    }

    /**
     * 裁剪消息列表到最大容量
     */
    private fun trimToMaxSize(messages: List<ChatMessage>): List<ChatMessage> {
        return if (messages.size > maxMessagesPerSession) {
            messages.takeLast(maxMessagesPerSession)
        } else {
            messages
        }
    }

    /**
     * 按轮数裁剪历史（保留最近 N 轮）
     */
    private fun trimToRounds(messages: List<ChatMessage>, rounds: Int): List<ChatMessage> {
        val userAssistantPairs = mutableListOf<Pair<ChatMessage?, ChatMessage?>>()
        var currentUser: ChatMessage? = null

        messages.forEach { message ->
            when (message) {
                is UserMessage -> {
                    if (currentUser != null) {
                        userAssistantPairs.add(currentUser to null)
                    }
                    currentUser = message
                }
                is AiMessage -> {
                    userAssistantPairs.add(currentUser to message)
                    currentUser = null
                }
                is ToolExecutionResultMessage -> {
                    // tool results are part of the assistant turn, skip as a separate pair
                }
                else -> { /* ignore system messages in history */ }
            }
        }
        if (currentUser != null) {
            userAssistantPairs.add(currentUser to null)
        }

        val recentPairs = userAssistantPairs.takeLast(rounds)
        return recentPairs.flatMap { pair ->
            listOfNotNull(pair.first, pair.second)
        }
    }

    /**
     * 将消息列表编码为 JSON 字符串
     *
     * 对 UserMessage 安全提取文本内容：
     * - 纯文本消息 → 直接取 text
     * - 包含 ImageContent → 提取图片 URL 或标记为 "[图片]"
     * - 混合内容 → 拼接所有文本和图片标记
     */
    private fun encodeMessagesToJson(messages: List<ChatMessage>): String {
        val array = JSONArray()
        messages.forEach { message ->
            val (role, content) = when (message) {
                is SystemMessage -> "system" to message.text()
                is UserMessage -> "user" to safeExtractUserContent(message)
                is AiMessage -> "assistant" to message.text()
                is ToolExecutionResultMessage -> "tool" to message.text()
                else -> "unknown" to message.toString()
            }
            val obj = JSONObject().apply {
                put("role", role)
                put("content", content)
            }
            array.put(obj)
        }
        return array.toString()
    }

    /**
     * 安全提取 UserMessage 的文本内容。
     * 处理含 ImageContent 或其他非文本 Content 的消息。
     */
    private fun safeExtractUserContent(message: UserMessage): String {
        return if (message.hasSingleText()) {
            message.singleText()
        } else {
            message.contents().joinToString(" ") { content ->
                when (content) {
                    is TextContent -> content.text()
                    is ImageContent -> {
                        val image = content.image()
                        if (image.url() != null) {
                            "[图片: ${image.url()}]"
                        } else {
                            "[图片]"
                        }
                    }
                    else -> "[${content.type()}]"
                }
            }
        }
    }

    /**
     * 从 JSON 字符串解析消息列表
     */
    private fun parseMessagesFromJson(jsonStr: String): List<ChatMessage> {
        return try {
            val array = JSONArray(jsonStr)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                val roleName = obj.getString("role")
                val content = obj.getString("content")
                when (roleName) {
                    "system" -> SystemMessage.from(content)
                    "assistant" -> AiMessage.from(content)
                    "tool" -> ToolExecutionResultMessage.from(
                        com.mamba.tool.ToolExecutionRequest.builder()
                            .id("")
                            .name("")
                            .arguments("{}")
                            .build(),
                        content
                    )
                    else -> UserMessage.from(content)
                }
            }
        } catch (exception: Exception) {
            Logger.w(tag, "Failed to parse messages JSON", exception)
            emptyList()
        }
    }
}