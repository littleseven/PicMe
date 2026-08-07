package com.mamba.picme.features.chat

import com.mamba.picme.agent.core.model.context.MediaAsset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** 选图面板模式：浏览最近照片 / 展示搜索结果。 */
enum class PickerMode { BROWSE, SEARCH }

/**
 * 选图面板状态持有者：管理搜索词、防抖后的搜索结果与搜索中状态。
 *
 * 为便于单测，将 [search] 与 [coroutineScope] 以构造参数注入；
 * 生产环境在 Composable 中用 rememberCoroutineScope() + 真实 searchEngine 传入。
 *
 * @param search 给定 query 返回匹配媒体；[searchAvailable] 为 false 时不会被调用。
 * @param searchAvailable 搜索引擎是否就绪（false 时一律走浏览态，不发起搜索）。
 * @param debounceMs 搜索防抖时长（默认 250ms；测试中可缩短或由虚拟时间驱动）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatPhotoPickerViewModel(
    private val search: suspend (String) -> List<MediaAsset>,
    private val searchAvailable: Boolean,
    coroutineScope: CoroutineScope,
    private val debounceMs: Long = 250L
) {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow<List<MediaAsset>>(emptyList())
    val results: StateFlow<List<MediaAsset>> = _results.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    init {
        coroutineScope.launch {
            _query
                .debounce(debounceMs)
                .distinctUntilChanged()
                .collect { q ->
                    if (q.isBlank() || !searchAvailable) {
                        _results.value = emptyList()
                        _isSearching.value = false
                    } else {
                        _isSearching.value = true
                        _results.value = search(q)
                        _isSearching.value = false
                    }
                }
        }
    }

    fun setQuery(q: String) {
        _query.value = q
    }
}
