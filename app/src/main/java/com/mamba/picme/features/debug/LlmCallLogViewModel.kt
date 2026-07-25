package com.mamba.picme.features.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mamba.picme.data.local.llmlog.LlmCallLogDao
import com.mamba.picme.data.local.llmlog.LlmCallLogEntity
import com.mamba.picme.data.local.llmlog.ToolCallLogDao
import com.mamba.picme.data.local.llmlog.ToolCallLogEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * LLM 调用日志页 ViewModel：从独立库读取最近 [MAX] 条 LLM 调用记录与 tool 执行指标，
 * 支持删除单条 / 清空（清空同时作用于两张表）。
 */
class LlmCallLogViewModel(
    private val dao: LlmCallLogDao,
    private val toolDao: ToolCallLogDao
) : ViewModel() {

    private val _items = MutableStateFlow<List<LlmCallLogEntity>>(emptyList())
    val items: StateFlow<List<LlmCallLogEntity>> = _items.asStateFlow()

    private val _toolItems = MutableStateFlow<List<ToolCallLogEntity>>(emptyList())
    val toolItems: StateFlow<List<ToolCallLogEntity>> = _toolItems.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _items.value = runCatching { dao.recent(MAX) }.getOrDefault(emptyList())
            _toolItems.value = runCatching { toolDao.recent(MAX) }.getOrDefault(emptyList())
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
            refresh()
        }
    }

    companion object {
        private const val MAX = 200
    }
}

class LlmCallLogViewModelFactory(
    private val dao: LlmCallLogDao,
    private val toolDao: ToolCallLogDao
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = LlmCallLogViewModel(dao, toolDao) as T
}
