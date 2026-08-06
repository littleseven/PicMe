package com.mamba.picme.features.debug.pexels

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException

/**
 * Pexels 图库状态编排。
 * 构造函数显式注入全部依赖（Agent First：显式优于隐式），scope 由调用方注入便于测试。
 */
class PexelsViewModel(
    private val api: PexelsApi,
    private val keyStore: PexelsKeyStore,
    private val imageSaver: PexelsImageSaver,
    private val scope: CoroutineScope
) {
    private val _uiState = MutableStateFlow<PexelsUiState>(PexelsUiState.Loading)
    val uiState = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<PexelsEvent>(extraBufferCapacity = 1)
    val events = _events.asSharedFlow()

    /** null = curated 精选；非 null = 搜索关键词 */
    private var currentQuery: String? = null

    init {
        if (keyStore.getKey() == null) {
            _uiState.value = PexelsUiState.NoKey()
        } else {
            loadCurated()
        }
    }

    fun saveKey(key: String) {
        if (key.isBlank()) return
        keyStore.saveKey(key)
        loadCurated()
    }

    fun clearKey() {
        keyStore.clear()
        _uiState.value = PexelsUiState.NoKey()
    }

    fun loadCurated() {
        currentQuery = null
        scope.launch { loadFirstPage(null) }
    }

    fun search(query: String) {
        currentQuery = query.trim().ifBlank { null }
        val q = currentQuery
        scope.launch { loadFirstPage(q) }
    }

    fun retry() {
        if (_uiState.value is PexelsUiState.Error) {
            val q = currentQuery
            scope.launch { loadFirstPage(q) }
        }
    }

    fun loadMore() {
        val ready = _uiState.value as? PexelsUiState.Ready ?: return
        if (ready.endReached || ready.loadingMore || ready.downloading) return
        val q = currentQuery
        scope.launch { loadPage(ready.page + 1, append = true, query = q) }
    }

    fun toggleSelect(photoId: Long) {
        val ready = _uiState.value as? PexelsUiState.Ready ?: return
        val selected = ready.selectedIds.toMutableSet()
        if (!selected.remove(photoId)) selected.add(photoId)
        _uiState.value = ready.copy(selectedIds = selected)
    }

    fun downloadSelected() {
        val ready = _uiState.value as? PexelsUiState.Ready ?: return
        if (ready.downloading || ready.selectedIds.isEmpty()) return
        val targets = ready.photos.filter { it.id in ready.selectedIds }
        scope.launch { downloadPhotos(targets) }
    }

    /** 批量下载当前列表前 count 张；已加载不足时自动翻页补足 */
    fun downloadBatch(count: Int) {
        val ready = _uiState.value as? PexelsUiState.Ready ?: return
        if (ready.downloading || ready.loadingMore) return
        val q = currentQuery
        scope.launch {
            var current = ready
            while (current.photos.size < count && !current.endReached) {
                loadPage(current.page + 1, append = true, query = q)
                current = _uiState.value as? PexelsUiState.Ready ?: return@launch
            }
            downloadPhotos(current.photos.take(count))
        }
    }

    internal suspend fun loadFirstPage(query: String?) {
        _uiState.value = PexelsUiState.Loading
        loadPage(page = 1, append = false, query = query)
    }

    internal suspend fun loadPage(page: Int, append: Boolean, query: String?) {
        val key = keyStore.getKey()
        if (key == null) {
            _uiState.value = PexelsUiState.NoKey()
            return
        }
        val previous = _uiState.value as? PexelsUiState.Ready
        if (append && previous != null) {
            _uiState.value = previous.copy(loadingMore = true)
        }
        try {
            val response = if (query == null) {
                api.curated(key, page)
            } else {
                api.search(key, query, page)
            }
            _uiState.value = PexelsUiState.Ready(
                photos = if (append) previous?.photos.orEmpty() + response.photos else response.photos,
                selectedIds = if (append) previous?.selectedIds.orEmpty() else emptySet(),
                page = page,
                endReached = response.nextPage == null || response.photos.isEmpty()
            )
        } catch (e: HttpException) {
            if (append && previous != null && e.code() != 401) {
                _uiState.value = previous.copy(loadingMore = false)
            } else {
                handleHttpError(e.code())
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (append && previous != null) {
                // 翻页失败保留已加载内容，仅复位 loadingMore，用户再次滚动即可重试
                _uiState.value = previous.copy(loadingMore = false)
            } else {
                _uiState.value = PexelsUiState.Error(PexelsErrorKind.NETWORK)
            }
        }
    }

    private fun handleHttpError(code: Int) {
        when (code) {
            401 -> {
                keyStore.clear()
                _uiState.value = PexelsUiState.NoKey(invalidPrevious = true)
            }

            429 -> _uiState.value = PexelsUiState.Error(PexelsErrorKind.RATE_LIMITED)
            else -> _uiState.value = PexelsUiState.Error(PexelsErrorKind.NETWORK)
        }
    }

    internal suspend fun downloadPhotos(targets: List<PexelsPhoto>) {
        val ready = _uiState.value as? PexelsUiState.Ready ?: return
        _uiState.value = ready.copy(downloading = true, downloadProgress = "0/${targets.size}")
        var success = 0
        targets.forEachIndexed { index, photo ->
            if (imageSaver.save(photo.id, photo.src.large2x)) success++
            val current = _uiState.value as? PexelsUiState.Ready ?: return
            _uiState.value = current.copy(downloadProgress = "${index + 1}/${targets.size}")
        }
        val current = _uiState.value as? PexelsUiState.Ready ?: return
        _uiState.value = current.copy(
            downloading = false,
            downloadProgress = "",
            selectedIds = emptySet()
        )
        _events.emit(PexelsEvent.DownloadCompleted(success, targets.size))
    }
}
