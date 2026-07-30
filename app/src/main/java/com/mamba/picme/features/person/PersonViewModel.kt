package com.mamba.picme.features.person

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mamba.picme.data.local.AppDatabase
import com.mamba.picme.data.local.entity.PersonEntity
import com.mamba.picme.domain.person.PersonRepository
import com.mamba.picme.domain.person.RelationPredicate
import com.mamba.picme.domain.tag.FaceClusterEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「人物」页 ViewModel：全部人脸聚类列表 + 每个聚类的封面（coverMediaId 整图 uri + faceFocusY）。
 *
 * 封面用 [PersonCoverResolver] 纯映射（可单测）；编辑走 [PersonRepository.applyPersonEdit]。
 */
class PersonViewModel(
    private val personRepository: PersonRepository,
    private val db: AppDatabase,
    private val faceClusterEngine: FaceClusterEngine
) : ViewModel() {

    private val _persons = MutableStateFlow<List<PersonEntity>>(emptyList())
    val persons: StateFlow<List<PersonEntity>> = _persons.asStateFlow()

    private val _covers = MutableStateFlow<Map<Long, PersonCover>>(emptyMap())
    val covers: StateFlow<Map<Long, PersonCover>> = _covers.asStateFlow()

    /** 加载全部人物簇并解析封面（coverMediaId → uri + faceFocusY）。 */
    fun load() {
        viewModelScope.launch {
            val all = personRepository.getAllPersons()
            val ids = all.mapNotNull { person -> person.coverMediaId }.distinct()
            val resolved = withContext(Dispatchers.IO) {
                if (ids.isEmpty()) emptyMap()
                else {
                    val media = db.mediaDao().getMediaByIds(ids)
                    PersonCoverResolver.resolve(
                        all,
                        media.associate { entity -> entity.id to entity.uri },
                        media.associate { entity -> entity.id to entity.faceFocusY }
                    )
                }
            }
            _covers.value = resolved
            // 防御：coverMediaId 悬空（封面媒体已删）的聚类 coverUri 为 null，不展示，避免空白格。
            // 正常情况下 reconcileAndLoad 已先行清理，此处为兜底。
            _persons.value = PersonCoverResolver.filterCoverable(all, resolved)
        }
    }

    /** 进入人物页：先对齐 persons 表（清孤儿/修悬空封面/重算 faceCount），再加载。幂等。 */
    fun reconcileAndLoad() {
        viewModelScope.launch {
            personRepository.reconcilePersons()
            // 跨簇合并 pass：把同一人被拆出的小簇/单例并回最近邻（愈合 44/144 类拆组）。
            // 合并失败不阻断人物页加载。
            withContext(Dispatchers.IO) {
                runCatching { faceClusterEngine.mergeSmallClusters() }
                    .onFailure { Log.w("PersonViewModel", "mergeSmallClusters failed", it) }
            }
            load()
        }
    }

    /** 应用人物编辑（命名/关系/自我），完成后刷新列表。 */
    fun applyEdit(
        personId: Long,
        name: String,
        customLabel: String,
        isSelf: Boolean,
        relation: RelationPredicate?,
        onDone: () -> Unit = {}
    ) {
        viewModelScope.launch {
            personRepository.applyPersonEdit(personId, name, relation, customLabel, isSelf)
            load()
            onDone()
        }
    }

    companion object {
        /** ViewModelProvider.Factory：参照 MemoryFactsViewModel.factory 范式。 */
        fun factory(
            personRepository: PersonRepository,
            db: AppDatabase,
            faceClusterEngine: FaceClusterEngine
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(PersonViewModel::class.java)) {
                        @Suppress("UNCHECKED_CAST")
                        return PersonViewModel(personRepository, db, faceClusterEngine) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
    }
}
