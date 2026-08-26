package com.mamba.picme.features.gallery.dedup

import android.content.IntentSender
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mamba.picme.core.common.Logger
import com.mamba.picme.domain.dedup.DedupGroup
import com.mamba.picme.domain.dedup.DedupLevel
import com.mamba.picme.domain.dedup.DedupScanConfig
import com.mamba.picme.domain.dedup.DedupScanController
import com.mamba.picme.domain.dedup.DedupScanEvent
import com.mamba.picme.domain.dedup.DedupTrashManager
import com.mamba.picme.domain.dedup.KeepPolicy
import com.mamba.picme.domain.dedup.KeepPolicyEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * 去重 2.0 页面状态机（Agent First：sealed 枚举全部合法状态，无布尔组合）。
 *
 * Config → Scanning（渐进式 GroupFound）→ Results →（系统授权）→ Cleaned →（undo/done）→ Config。
 * 字节格式化在 UI 层，VM 只产出原始数值。
 */
sealed interface DedupUiState {
    data class Config(val config: DedupScanConfig = DedupScanConfig()) : DedupUiState

    data class Scanning(
        val phase: DedupLevel,
        val phaseIndex: Int,
        val phaseCount: Int,
        val scanned: Int,
        val total: Int,
        val paused: Boolean,
        val foundGroups: List<DedupGroup>,
    ) : DedupUiState

    data class Results(
        val groups: List<DedupGroup>,
        val selectedTab: DedupLevel,
        val policy: KeepPolicy,
    ) : DedupUiState

    data class Cleaned(
        val deletedCount: Int,
        val reclaimedBytes: Long,
        val trashedUris: List<String>,
    ) : DedupUiState
}

/** 待 UI 发起的系统授权请求（回收站删除/恢复同款：uris + IntentSender）。 */
data class PendingTrash(val uris: List<String>, val intentSender: IntentSender)

class DedupViewModel(
    private val mediaSource: DedupMediaSource,
    private val scanner: DedupScanController,
    private val trashManager: DedupTrashManager,
    /** API < 30 无回收站授权接口，由 UI 层注入旧删除流回调兜底。 */
    private val legacyDeleter: ((List<String>) -> Unit)? = null,
    /** 测试注入作用域（避开 Dispatchers.Main）；生产为 null → viewModelScope。 */
    coroutineScope: CoroutineScope? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val scope: CoroutineScope = coroutineScope ?: viewModelScope

    private val _uiState = MutableStateFlow<DedupUiState>(DedupUiState.Config())
    val uiState: StateFlow<DedupUiState> = _uiState.asStateFlow()

    private val _pendingTrash = MutableStateFlow<PendingTrash?>(null)
    val pendingTrash: StateFlow<PendingTrash?> = _pendingTrash.asStateFlow()

    private val _pendingRestore = MutableStateFlow<PendingTrash?>(null)
    val pendingRestore: StateFlow<PendingTrash?> = _pendingRestore.asStateFlow()

    private var scanJob: Job? = null

    // ---------- 扫描生命周期 ----------

    fun startScan(config: DedupScanConfig) {
        if (_uiState.value is DedupUiState.Scanning) return // 防重入
        val phases = config.levels.sorted()
        _uiState.value = DedupUiState.Scanning(
            phase = phases.firstOrNull() ?: DedupLevel.EXACT,
            phaseIndex = 1,
            phaseCount = phases.size.coerceAtLeast(1),
            scanned = 0,
            total = 0,
            paused = false,
            foundGroups = emptyList(),
        )
        scanJob = scope.launch {
            val items = mediaSource.photoScanItems()
            scanner.scan(items, config)
                .flowOn(ioDispatcher)
                .collect { event -> onScanEvent(event) }
        }
    }

    fun pauseScan() {
        if (_uiState.value !is DedupUiState.Scanning) return
        scanner.pauseRequested = true
        updateScanning { state -> state.copy(paused = true) }
    }

    fun resumeScan() {
        scanner.resume()
        updateScanning { state -> state.copy(paused = false) }
    }

    fun cancelScan() {
        scanner.resume() // 防卡死：暂停态取消时扫描循环需退出 awaitIfPaused
        scanJob?.cancel()
        scanJob = null
        _uiState.value = DedupUiState.Config()
    }

    private fun onScanEvent(event: DedupScanEvent) {
        when (event) {
            is DedupScanEvent.Progress -> updateScanning { state ->
                state.copy(phase = event.phase, scanned = event.scanned, total = event.total)
            }
            is DedupScanEvent.GroupFound -> updateScanning { state ->
                state.copy(foundGroups = listOf(event.group) + state.foundGroups) // 最新发现排前
            }
            is DedupScanEvent.PhaseChanged -> updateScanning { state ->
                state.copy(phase = event.phase, phaseIndex = event.phaseIndex, phaseCount = event.phaseCount)
            }
            is DedupScanEvent.Done -> {
                val groups = event.groups
                val firstTab = DedupLevel.entries.firstOrNull { level ->
                    groups.any { group -> group.level == level }
                } ?: DedupLevel.EXACT
                _uiState.value = DedupUiState.Results(
                    groups = groups,
                    selectedTab = firstTab,
                    policy = KeepPolicy.BEST_QUALITY,
                )
            }
            DedupScanEvent.Cancelled -> _uiState.value = DedupUiState.Config()
        }
    }

    private inline fun updateScanning(transform: (DedupUiState.Scanning) -> DedupUiState.Scanning) {
        (_uiState.value as? DedupUiState.Scanning)?.let { state -> _uiState.value = transform(state) }
    }

    // ---------- 结果操作 ----------

    fun setKeep(groupId: String, uri: String) {
        when (val state = _uiState.value) {
            is DedupUiState.Results -> _uiState.value =
                state.copy(groups = state.groups.map { group -> withKeep(group, groupId, uri) })
            is DedupUiState.Scanning -> _uiState.value =
                state.copy(foundGroups = state.foundGroups.map { group -> withKeep(group, groupId, uri) })
            else -> Unit
        }
    }

    private fun withKeep(group: DedupGroup, groupId: String, uri: String): DedupGroup {
        if (group.id != groupId || group.members.none { member -> member.uri == uri }) return group
        return group.copy(keepUri = uri, userOverride = true)
    }

    fun applyPolicy(policy: KeepPolicy) {
        val state = _uiState.value as? DedupUiState.Results ?: return
        _uiState.value = state.copy(
            policy = policy,
            groups = state.groups.map { group -> resortGroup(group, policy) },
        )
    }

    /** 仅 EXACT/VISUAL 组清 userOverride 并按当前 policy 重算；SCENE 组保留用户手动选择。 */
    fun smartSelectAll() {
        val state = _uiState.value as? DedupUiState.Results ?: return
        _uiState.value = state.copy(
            groups = state.groups.map { group ->
                if (group.level == DedupLevel.SCENE) {
                    group
                } else {
                    resortGroup(group.copy(userOverride = false), state.policy)
                }
            },
        )
    }

    private fun resortGroup(group: DedupGroup, policy: KeepPolicy): DedupGroup {
        if (group.userOverride) return group
        val sorted = KeepPolicyEngine.recommend(policy, group.members)
        return group.copy(members = sorted, keepUri = sorted.first().uri)
    }

    fun selectTab(level: DedupLevel) {
        val state = _uiState.value as? DedupUiState.Results ?: return
        _uiState.value = state.copy(selectedTab = level)
    }

    fun getGroup(id: String): DedupGroup? = when (val state = _uiState.value) {
        is DedupUiState.Results -> state.groups.firstOrNull { group -> group.id == id }
        is DedupUiState.Scanning -> state.foundGroups.firstOrNull { group -> group.id == id }
        else -> null
    }

    // ---------- 删除 / 撤销 ----------

    /**
     * 聚合 Results 全部组的 deleteUris。API 30+ 发回收站授权（[pendingTrash]，UI 启动
     * IntentSender 后回调 [onTrashResult]）；API < 30 走 [legacyDeleter] 旧删除流，
     * 状态保持 Results 由旧流自行管理。
     */
    fun deleteSelected() {
        val state = _uiState.value as? DedupUiState.Results ?: return
        val uris = state.groups.flatMap { group -> group.deleteUris }.distinct()
        if (uris.isEmpty()) return
        Logger.d(TAG, "deleteSelected: ${uris.size} uris")
        if (trashManager.isSupported) {
            _pendingTrash.value = PendingTrash(uris, trashManager.buildTrashIntent(uris))
        } else {
            legacyDeleter?.invoke(uris)
        }
    }

    /**
     * 回收站授权结果。拒绝 → 整批留在 Results 不动；确认 → 复查仍存在的 uri（部分拒绝
     * V1 简化：有残留则整批不动），全部消失才统计进 Cleaned。
     */
    fun onTrashResult(ok: Boolean) {
        val pending = _pendingTrash.value ?: return
        _pendingTrash.value = null
        if (!ok) return
        val state = _uiState.value as? DedupUiState.Results ?: return
        scope.launch(ioDispatcher) {
            val remaining = trashManager.queryExisting(pending.uris)
            if (remaining.isNotEmpty()) {
                Logger.d(TAG, "trash partially confirmed, ${remaining.size} uris remain, keep Results intact")
                return@launch
            }
            val deleted = pending.uris.toSet()
            val bytes = state.groups
                .flatMap { group -> group.members }
                .filter { member -> member.uri in deleted }
                .sumOf { member -> member.sizeBytes }
            _uiState.value = DedupUiState.Cleaned(
                deletedCount = deleted.size,
                reclaimedBytes = bytes,
                trashedUris = pending.uris,
            )
        }
    }

    /** Cleaned 状态下发起恢复授权（[pendingRestore]，UI 回调 [onRestoreResult]）。 */
    fun undoTrash() {
        val state = _uiState.value as? DedupUiState.Cleaned ?: return
        if (state.trashedUris.isEmpty() || !trashManager.isSupported) return
        _pendingRestore.value = PendingTrash(state.trashedUris, trashManager.buildRestoreIntent(state.trashedUris))
    }

    fun onRestoreResult(ok: Boolean) {
        if (_pendingRestore.value == null) return
        _pendingRestore.value = null
        if (ok) _uiState.value = DedupUiState.Config()
    }

    /** Cleaned → Config（「完成」按钮）。 */
    fun resetToConfig() {
        if (_uiState.value is DedupUiState.Cleaned) _uiState.value = DedupUiState.Config()
    }

    override fun onCleared() {
        scanner.resume()
        super.onCleared()
    }

    private companion object {
        const val TAG = "Dedup"
    }
}
