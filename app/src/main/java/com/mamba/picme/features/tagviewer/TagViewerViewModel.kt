package com.mamba.picme.features.tagviewer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.data.local.AppDatabase
import com.mamba.picme.data.model.MediaEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 标签查看测试页 ViewModel。
 *
 * 订阅 [AppDatabase] 的 [com.mamba.picme.data.local.MediaDao.getAllMedia]，对每条
 * [MediaEntity.labels] 解析为 [ParsedTags]，组装列表与聚合，纯只读、不触发任何推理。
 */
class TagViewerViewModel(application: Application) : AndroidViewModel(application) {

    private val tag = "PoLang:TagViewer"
    private val dao = AppDatabase.getDatabase(application).mediaDao()

    private val _state = MutableStateFlow<TagViewerUiState>(TagViewerUiState.Loading)
    val state: StateFlow<TagViewerUiState> = _state.asStateFlow()

    private val query = MutableStateFlow("")

    init {
        viewModelScope.launch {
            dao.getAllMedia()
                .combine(query) { media, queryText -> media to queryText }
                .catch { error ->
                    Logger.e(tag, "Load media failed", error)
                    _state.value = TagViewerUiState.Error(error.message ?: "加载失败")
                }
                .collect { (media, queryText) ->
                    val items = media.map { entity -> entity.toItem() }
                    val aggregates = TagAggregator.aggregate(items)
                    val filtered = filterItems(items, queryText)
                    _state.value = TagViewerUiState.Ready(
                        photos = items,
                        filteredPhotos = filtered,
                        aggregates = aggregates
                    )
                }
        }
    }

    fun setQuery(text: String) {
        query.value = text
    }

    private fun filterItems(items: List<PhotoTagsItem>, queryText: String): List<PhotoTagsItem> {
        if (queryText.isBlank()) return items
        val keyword = queryText.trim().lowercase()
        return items.filter { item -> item.matches(keyword) }
    }

    private fun PhotoTagsItem.matches(keyword: String): Boolean {
        if (fileName.lowercase().contains(keyword)) return true
        val parsed = this.parsed ?: return false
        if (parsed.scene.lowercase().contains(keyword)) return true
        if (parsed.activity.lowercase().contains(keyword)) return true
        if (parsed.tags.any { label -> label.lowercase().contains(keyword) }) return true
        if (parsed.objects.any { label -> label.lowercase().contains(keyword) }) return true
        return false
    }

    private fun MediaEntity.toItem(): PhotoTagsItem = PhotoTagsItem(
        mediaId = id,
        uri = uri,
        fileName = fileName,
        parsed = TagJsonParser.parse(labels),
        rawJson = labels.orEmpty()
    )
}
