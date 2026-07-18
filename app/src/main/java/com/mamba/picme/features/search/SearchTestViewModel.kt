package com.mamba.picme.features.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mamba.picme.PoLangApplication
import com.mamba.picme.domain.search.MediaSearchEngine
import com.mamba.picme.domain.search.SearchDiagnosticsResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 搜索召回测试页 ViewModel。
 *
 * 负责驱动 [MediaSearchEngine.searchWithDiagnostics]，暴露 UI 状态、过程日志与搜索历史。
 */
class SearchTestViewModel(application: Application) : AndroidViewModel(application) {

    sealed interface UiState {
        data object Idle : UiState
        data object Loading : UiState
        data class Success(val result: SearchDiagnosticsResult) : UiState
        data class Error(val message: String) : UiState
    }

    private val searchEngine: MediaSearchEngine by lazy {
        (application as PoLangApplication).container.mediaSearchEngine
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _logLines = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = _logLines.asStateFlow()

    private val _history = MutableStateFlow<List<String>>(emptyList())
    val history: StateFlow<List<String>> = _history.asStateFlow()

    /**
     * 执行诊断搜索。
     *
     * @param query 用户输入关键词
     * @param enableSemanticSearch 是否启用 MobileCLIP 语义召回
     */
    fun search(query: String, enableSemanticSearch: Boolean = true) {
        if (query.isBlank()) {
            _uiState.value = UiState.Error("Query cannot be empty")
            return
        }

        viewModelScope.launch(Dispatchers.Default) {
            _uiState.value = UiState.Loading
            addLog("Start search: \"$query\" (semantic=$enableSemanticSearch)")
            val startTime = System.currentTimeMillis()
            @Suppress("TooGenericExceptionCaught")
            try {
                val result = searchEngine.searchWithDiagnostics(
                    query = query,
                    enableSemanticSearch = enableSemanticSearch
                )
                val totalTime = System.currentTimeMillis() - startTime
                addLog(
                    "Search complete: total=${totalTime}ms, " +
                        "sql=${result.sqlResults.size}, " +
                        "semantic=${result.semanticResults.size}, " +
                        "merged=${result.mergedResults.size}"
                )
                addHistory(query)
                _uiState.value = UiState.Success(result)
            } catch (e: Exception) {
                addLog("Search failed: ${e.message}")
                _uiState.value = UiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * 清空日志。
     */
    fun clearLogs() {
        _logLines.value = emptyList()
    }

    /**
     * 从搜索历史中移除指定条目。
     */
    fun removeHistoryItem(query: String) {
        _history.value = _history.value.filter { it != query }
    }

    private fun addHistory(query: String) {
        _history.value = (listOf(query) + _history.value.filter { it != query }).take(MAX_HISTORY_SIZE)
    }

    private fun addLog(line: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        _logLines.value = (listOf("[$timestamp] $line") + _logLines.value).take(MAX_LOG_LINES)
    }

    companion object {
        private const val MAX_LOG_LINES = 200
        private const val MAX_HISTORY_SIZE = 10
    }
}
