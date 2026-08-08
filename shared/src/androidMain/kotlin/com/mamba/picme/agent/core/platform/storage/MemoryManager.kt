package com.mamba.picme.agent.core.platform.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.agent.core.platform.thread.SharedDispatcherProvider
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private val Context.agentMemoryDataStore: DataStore<Preferences> by preferencesDataStore(name = "agent_memory")

/**
 * 对话记忆管理器（langchain4j → Koog 迁移 Phase 6 裁剪版）
 *
 * 会话记忆的读/写/裁剪已迁移至 Koog 记忆层（KoogMessageMemoryStore +
 * KoogSessionHistoryProvider，键前缀 `koog_memory_`），本类仅剩会话历史清理：
 * [AgentOrchestrator] 重置会话时调用 [clearHistory]。
 * 旧 `memory_*` 键的存量数据成为孤儿，不做迁移。
 *
 * **线程模型**：DataStore 读写由 [DispatcherProvider] 的专用单线程
 * （PoLang-DataStore-Thread）串行执行，与网络请求隔离。
 *
 * @param context Application Context
 */
class MemoryManager(private val context: Context) : ChatHistoryCleaner {

    private val tag = "MemoryManager"
    private val dataStore = context.agentMemoryDataStore

    private val dataStoreDispatcher = SharedDispatcherProvider.instance.dataStoreDispatcher

    /**
     * 清空指定 session 的对话历史
     */
    override suspend fun clearHistory(sessionId: String) = withContext(dataStoreDispatcher) {
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
}
