package com.mamba.picme.features.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mamba.picme.data.local.llmlog.JsRunLogDao
import com.mamba.picme.data.local.llmlog.JsRunLogEntity
import com.mamba.picme.data.local.llmlog.LlmCallLogDao
import com.mamba.picme.data.local.llmlog.LlmCallLogEntity
import com.mamba.picme.data.local.llmlog.ToolCallLogDao
import com.mamba.picme.data.local.llmlog.ToolCallLogEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * LLM 调用日志页 ViewModel：从独立库读取最近 [MAX] 条 LLM 调用记录、tool 执行指标与
 * JS 沙盒运行事件（Agent 终端运行感知层三层：推理/行动/端侧执行），
 * 支持删除单条 / 清空（清空同时作用于三张表）。
 */
class LlmCallLogViewModel(
    private val dao: LlmCallLogDao,
    private val toolDao: ToolCallLogDao,
    private val jsRunDao: JsRunLogDao
) : ViewModel() {

    private val _items = MutableStateFlow<List<LlmCallLogEntity>>(emptyList())
    val items: StateFlow<List<LlmCallLogEntity>> = _items.asStateFlow()

    private val _toolItems = MutableStateFlow<List<ToolCallLogEntity>>(emptyList())
    val toolItems: StateFlow<List<ToolCallLogEntity>> = _toolItems.asStateFlow()

    private val _jsRunItems = MutableStateFlow<List<JsRunLogEntity>>(emptyList())
    val jsRunItems: StateFlow<List<JsRunLogEntity>> = _jsRunItems.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _items.value = runCatching { dao.recent(MAX) }.getOrDefault(emptyList())
            _toolItems.value = runCatching { toolDao.recent(MAX) }.getOrDefault(emptyList())
            _jsRunItems.value = runCatching { jsRunDao.recent(MAX) }.getOrDefault(emptyList())
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            runCatching { dao.delete(id) }
            refresh()
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            runCatching { dao.clearAll() }
            runCatching { toolDao.clearAll() }
            runCatching { jsRunDao.clearAll() }
            refresh()
        }
    }

    /**
     * 装载同一 [traceId] 的三层记录（LLM/tool/JS），按时间升序合并，供详情页 turn pager。
     * 挂起函数：调用方在协程里 await（详情页 LaunchedEffect）。
     */
    suspend fun loadTurn(traceId: String): List<TurnRecordItem> {
        val llm = runCatching { dao.getByTraceId(traceId) }.getOrDefault(emptyList())
        val tool = runCatching { toolDao.getByTraceId(traceId) }.getOrDefault(emptyList())
        val js = runCatching { jsRunDao.getByTraceId(traceId) }.getOrDefault(emptyList())
        return mergeTurnRecords(llm, tool, js)
    }

    companion object {
        private const val MAX = 200
    }
}

class LlmCallLogViewModelFactory(
    private val dao: LlmCallLogDao,
    private val toolDao: ToolCallLogDao,
    private val jsRunDao: JsRunLogDao
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        LlmCallLogViewModel(dao, toolDao, jsRunDao) as T
}
