package com.mamba.picme.features.tagviewer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.data.local.AppDatabase
import com.mamba.picme.data.local.dao.MediaFeedbackDao
import com.mamba.picme.data.local.entity.MediaFeedbackEntity
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
private const val TAG_VIEWER_QUERY = "tag_viewer"

class TagViewerViewModel(application: Application) : AndroidViewModel(application) {

    private val tag = "PoLang:TagViewer"
    private val dao = AppDatabase.getDatabase(application).mediaDao()
    private val feedbackDao: MediaFeedbackDao = AppDatabase.getDatabase(application).mediaFeedbackDao()

    private val _state = MutableStateFlow<TagViewerUiState>(TagViewerUiState.Loading)
    val state: StateFlow<TagViewerUiState> = _state.asStateFlow()

    private val query = MutableStateFlow("")
    private val showOnlyDisliked = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            dao.getAllMedia()
                .combine(query) { media, queryText -> media to queryText }
                .catch { error ->
                    Logger.e(tag, "Load media failed", error)
                    _state.value = TagViewerUiState.Error(error.message ?: "加载失败")
                }
                .collect { (media, queryText) ->
                    val feedback = feedbackDao.getFeedbackForMediaIds(
                        mediaIds = media.map { it.id.toString() },
                        queryText = TAG_VIEWER_QUERY
                    ).groupBy { it.mediaId }
                    val items = media.map { entity ->
                        entity.toItem(feedbackType = feedback[entity.id.toString()]?.firstOrNull()?.feedbackType)
                    }
                    val aggregates = TagAggregator.aggregate(items)
                    val filtered = filterItems(items, queryText)
                    _state.value = TagViewerUiState.Ready(
                        photos = items,
                        filteredPhotos = filtered,
                        aggregates = aggregates,
                        showOnlyDisliked = showOnlyDisliked.value
                    )
                }
        }
    }

    fun setQuery(text: String) {
        query.value = text
    }

    fun setShowOnlyDisliked(show: Boolean) {
        showOnlyDisliked.value = show
        val current = _state.value as? TagViewerUiState.Ready ?: return
        val filtered = filterItems(current.photos, query.value)
        _state.value = current.copy(
            filteredPhotos = filtered,
            showOnlyDisliked = show
        )
    }

    fun toggleDislike(mediaId: Long) {
        viewModelScope.launch {
            try {
                val mediaIdStr = mediaId.toString()
                val existing = feedbackDao.getFeedbackForMediaAndQuery(
                    mediaId = mediaIdStr,
                    queryText = TAG_VIEWER_QUERY
                ).firstOrNull { it.feedbackType == "dislike" }
                if (existing != null) {
                    feedbackDao.deleteFeedback(mediaIdStr, "dislike", TAG_VIEWER_QUERY)
                } else {
                    feedbackDao.insert(
                        MediaFeedbackEntity(
                            mediaId = mediaIdStr,
                            feedbackType = "dislike",
                            queryText = TAG_VIEWER_QUERY,
                            sessionId = "",
                            createdAt = System.currentTimeMillis()
                        )
                    )
                }
                // 触发一次重新加载以更新 UI 状态
                val current = _state.value as? TagViewerUiState.Ready ?: return@launch
                val mediaIds = current.photos.map { it.mediaId.toString() }
                val feedback = feedbackDao.getFeedbackForMediaIds(mediaIds, TAG_VIEWER_QUERY)
                    .groupBy { it.mediaId }
                val updatedItems = current.photos.map { item ->
                    item.copy(feedbackType = feedback[item.mediaId.toString()]?.firstOrNull()?.feedbackType)
                }
                _state.value = current.copy(
                    photos = updatedItems,
                    filteredPhotos = filterItems(updatedItems, query.value)
                )
            } catch (e: Exception) {
                Logger.e(tag, "Toggle dislike failed", e)
            }
        }
    }

    private fun filterItems(items: List<PhotoTagsItem>, queryText: String): List<PhotoTagsItem> {
        val onlyDisliked = showOnlyDisliked.value
        val keyword = queryText.trim().lowercase()
        return items.filter { item ->
            val matchesDislike = !onlyDisliked || item.isDisliked
            val matchesQuery = keyword.isBlank() || item.matches(keyword)
            matchesDislike && matchesQuery
        }
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

    private fun MediaEntity.toItem(feedbackType: String? = null): PhotoTagsItem = PhotoTagsItem(
        mediaId = id,
        uri = uri,
        fileName = fileName,
        parsed = TagJsonParser.parse(labels),
        rawJson = labels.orEmpty(),
        feedbackType = feedbackType
    )
}
