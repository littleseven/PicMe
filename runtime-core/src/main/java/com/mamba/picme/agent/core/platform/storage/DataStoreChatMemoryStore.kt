package com.mamba.picme.agent.core.platform.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.data.message.AiMessage
import com.mamba.data.message.ChatMessage
import com.mamba.data.message.SystemMessage
import com.mamba.data.message.ToolExecutionResultMessage
import com.mamba.data.message.UserMessage
import com.mamba.chat.ChatMemoryStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.chatMemoryDataStore: DataStore<Preferences> by preferencesDataStore(name = "chat_memory")

/**
 * LangChain4j ChatMemoryStore 的 DataStore 持久化实现
 *
 * 将对话历史以 JSON 格式持久化到 Android DataStore。
 * 充当 [com.mamba.chat.MessageWindowChatMemory] 的后端存储层。
 *
 * **线程模型**：使用 runBlocking 桥接同步 → 异步，内部由 DataStore 保证原子性。
 */
class DataStoreChatMemoryStore(private val context: Context) : ChatMemoryStore {

    private val tag = "DataStoreChatMemoryStore"
    private val dataStore = context.chatMemoryDataStore

    override fun getMessages(memoryId: Any): MutableList<ChatMessage> {
        val key = stringPreferencesKey("memory_$memoryId")
        return try {
            val jsonStr = runBlocking {
                dataStore.data.map { prefs -> prefs[key] ?: "[]" }.first()
            }
            if (jsonStr == "[]") {
                mutableListOf()
            } else {
                sanitizeMessages(parseMessages(jsonStr))
            }
        } catch (e: Exception) {
            Logger.w(tag, "Failed to load messages for memoryId=$memoryId", e)
            mutableListOf()
        }
    }

    override fun updateMessages(memoryId: Any, messages: MutableList<ChatMessage>) {
        val key = stringPreferencesKey("memory_$memoryId")
        try {
            val jsonStr = encodeMessages(messages)
            runBlocking {
                dataStore.edit { prefs -> prefs[key] = jsonStr }
            }
        } catch (e: Exception) {
            Logger.w(tag, "Failed to save messages for memoryId=$memoryId", e)
        }
    }

    override fun deleteMessages(memoryId: Any) {
        val key = stringPreferencesKey("memory_$memoryId")
        try {
            runBlocking {
                dataStore.edit { prefs -> prefs.remove(key) }
            }
        } catch (e: Exception) {
            Logger.w(tag, "Failed to delete messages for memoryId=$memoryId", e)
        }
    }

    // ── JSON 序列化 ─────────────────────────────────────────

    private fun encodeMessages(messages: List<ChatMessage>): String {
        val array = JSONArray()
        messages.forEach { message ->
            val obj = JSONObject()
            when (message) {
                is UserMessage -> {
                    obj.put("type", "user")
                    obj.put("content", message.singleText())
                }
                is AiMessage -> {
                    obj.put("type", "assistant")
                    obj.put("content", message.text() ?: "")
                    // 完整序列化 toolExecutionRequests，否则反序列化后丢失 toolCalls
                    // 会导致 OpenAI API 报错：tool message 没有对应的 tool_calls
                    if (message.hasToolExecutionRequests()) {
                        val toolCallsArray = JSONArray()
                        message.toolExecutionRequests().forEach { req ->
                            val reqObj = JSONObject()
                            reqObj.put("id", req.id())
                            reqObj.put("name", req.name())
                            reqObj.put("arguments", req.arguments())
                            toolCallsArray.put(reqObj)
                        }
                        obj.put("toolCalls", toolCallsArray)
                    }
                }
                is SystemMessage -> {
                    obj.put("type", "system")
                    obj.put("content", message.text())
                }
                is ToolExecutionResultMessage -> {
                    obj.put("type", "tool")
                    obj.put("content", message.text())
                    // 序列化 tool id 和 name，确保反序列化后 tool message 能正确配对
                    obj.put("toolId", message.id())
                    obj.put("toolName", message.toolName())
                }
            }
            array.put(obj)
        }
        return array.toString()
    }

    private fun parseMessages(jsonStr: String): MutableList<ChatMessage> {
        return try {
            val array = JSONArray(jsonStr)
            val messages = mutableListOf<ChatMessage>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val type = obj.optString("type", "")
                val content = obj.optString("content", "")
                when (type) {
                    "user" -> messages.add(UserMessage.from(content))
                    "assistant" -> {
                        val aiBuilder = AiMessage.builder().text(content)
                        // 反序列化 toolExecutionRequests
                        if (obj.has("toolCalls")) {
                            val toolCallsArray = obj.getJSONArray("toolCalls")
                            val requests = mutableListOf<com.mamba.tool.ToolExecutionRequest>()
                            for (j in 0 until toolCallsArray.length()) {
                                val reqObj = toolCallsArray.getJSONObject(j)
                                val req = com.mamba.tool.ToolExecutionRequest.builder()
                                    .id(reqObj.optString("id", ""))
                                    .name(reqObj.optString("name", ""))
                                    .arguments(reqObj.optString("arguments", "{}"))
                                    .build()
                                requests.add(req)
                            }
                            aiBuilder.toolExecutionRequests(requests)
                        }
                        messages.add(aiBuilder.build())
                    }
                    "system" -> messages.add(SystemMessage.from(content))
                    "tool" -> {
                        // 反序列化 tool result message，保留 id 和 name 用于正确配对
                        val toolId = obj.optString("toolId", "")
                        val toolName = obj.optString("toolName", "")
                        messages.add(ToolExecutionResultMessage.from(toolId, toolName, content))
                    }
                }
            }
            messages
        } catch (e: Exception) {
            Logger.w(tag, "Failed to parse messages JSON", e)
            mutableListOf()
        }
    }

    /**
     * 清理「悬空 tool_call」：assistant 消息含 toolExecutionRequests 但无对应 ToolExecutionResultMessage
     * （如工具执行崩溃、超时未产出结果），OpenAI 协议会拒绝（assistant tool_calls 后必须跟 tool message）。
     * 在加载层剔除这类悬空 tool_call，避免一次崩溃永久卡死整个会话。
     */
    private fun sanitizeMessages(messages: MutableList<ChatMessage>): MutableList<ChatMessage> {
        if (messages.isEmpty()) return messages
        val assistantToolCallIds = mutableSetOf<String>()
        messages.forEach { msg ->
            if (msg is AiMessage && msg.hasToolExecutionRequests()) {
                msg.toolExecutionRequests().forEach { assistantToolCallIds.add(it.id()) }
            }
        }
        val toolMessageIds = messages
            .filterIsInstance<ToolExecutionResultMessage>()
            .map { it.id() }
            .toSet()
        // 双向配对：assistant tool_call 必须有对应 tool message，且 tool message 必须有对应 assistant tool_call。
        // 任一方向缺失（崩溃未产出结果 / 窗口截断 / 历史污染）都剔除，避免 OpenAI 协议拒绝。
        val validIds = assistantToolCallIds.intersect(toolMessageIds)
        val sanitized = mutableListOf<ChatMessage>()
        for (msg in messages) {
            when {
                msg is AiMessage && msg.hasToolExecutionRequests() -> {
                    val requests = msg.toolExecutionRequests()
                    val valid = requests.filter { it.id() in validIds }
                    when {
                        // 全部 tool_call 都配对：原样保留
                        valid.size == requests.size -> sanitized.add(msg)
                        // 全部悬空：丢弃 toolCalls；若还有文本则保留文本消息
                        valid.isEmpty() -> {
                            val text = msg.text()
                            if (!text.isNullOrBlank()) sanitized.add(AiMessage.from(text))
                        }
                        // 部分悬空：重建 assistant，只保留配对的 toolCalls
                        else -> {
                            val builder = AiMessage.builder()
                            msg.text()?.let { builder.text(it) }
                            builder.toolExecutionRequests(valid)
                            sanitized.add(builder.build())
                        }
                    }
                }
                msg is ToolExecutionResultMessage -> {
                    // 丢弃悬空 tool message（无配对 assistant tool_call）
                    if (msg.id() in validIds) sanitized.add(msg)
                }
                else -> sanitized.add(msg)
            }
        }
        return sanitized
    }
}
