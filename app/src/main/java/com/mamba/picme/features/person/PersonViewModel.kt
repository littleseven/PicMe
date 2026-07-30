package com.mamba.picme.features.person

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mamba.picme.data.local.AppDatabase
import com.mamba.picme.data.local.entity.PersonEntity
import com.mamba.picme.data.local.entity.PersonRelationEntity
import com.mamba.picme.data.model.MediaEntity
import com.mamba.picme.domain.person.PersonRepository
import com.mamba.picme.domain.person.RelationDisplayItem
import com.mamba.picme.domain.person.RelationPredicate
import com.mamba.picme.domain.tag.FaceClusterEngine
import com.mamba.picme.features.gallery.capability.GalleryCapability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「人物」页 ViewModel：全部人脸聚类列表 + 每个聚类的封面 + 指向"我"的关系。
 *
 * 封面用 [PersonCoverResolver] 纯映射（可单测）；编辑走 [PersonRepository] 收口。
 */
class PersonViewModel(
    private val personRepository: PersonRepository,
    private val db: AppDatabase,
    private val faceClusterEngine: FaceClusterEngine
) : ViewModel() {

    private val _persons = MutableStateFlow<List<PersonEntity>>(emptyList())
    val persons: StateFlow<List<PersonEntity>> = _persons.asStateFlow()

    private val _showAll = MutableStateFlow(false)
    val showAll: StateFlow<Boolean> = _showAll.asStateFlow()

    private val _totalPersonCount = MutableStateFlow(0)
    val totalPersonCount: StateFlow<Int> = _totalPersonCount.asStateFlow()

    private val _covers = MutableStateFlow<Map<Long, PersonCover>>(emptyMap())
    val covers: StateFlow<Map<Long, PersonCover>> = _covers.asStateFlow()

    private val _relations = MutableStateFlow<Map<Long, RelationDisplayItem?>>(emptyMap())
    val relations: StateFlow<Map<Long, RelationDisplayItem?>> = _relations.asStateFlow()

    private val _photoCounts = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val photoCounts: StateFlow<Map<Long, Int>> = _photoCounts.asStateFlow()

    private val _editingPersonId = MutableStateFlow<Long?>(null)
    val editingPersonId: StateFlow<Long?> = _editingPersonId.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /** 加载全部人物簇并解析封面与关系。 */
    fun load() {
        viewModelScope.launch {
            val all = personRepository.getAllPersons()
            val ids = all.mapNotNull { person -> person.coverMediaId }.distinct()
            val resolved = withContext(Dispatchers.IO) {
                if (ids.isEmpty()) {
                    emptyMap()
                } else {
                    val media = db.mediaDao().getMediaByIds(ids)
                    PersonCoverResolver.resolve(
                        all,
                        media.associate { entity -> entity.id to entity.uri },
                        media.associate { entity -> entity.id to entity.faceFocusY }
                    )
                }
            }
            val relationMap = withContext(Dispatchers.IO) {
                all.associate { person ->
                    val relation = personRepository.getRelationToSelf(person.personId)
                    person.personId to relationToDisplay(person, relation)
                }
            }
            val photoCountMap = withContext(Dispatchers.IO) {
                val distinctCounts = db.personDao().getDistinctMediaCounts().associate { it.personId to it.count }
                val searchEngine = GalleryCapability.getInstance().searchEngine
                if (searchEngine == null) {
                    distinctCounts
                } else {
                    val namedPersons = all.filter { !it.name.isNullOrBlank() }
                    val searchCounts = coroutineScope {
                        namedPersons.map { person ->
                            async {
                                val count = searchEngine.search(person.name!!).media.size
                                person.personId to count
                            }
                        }.awaitAll().toMap()
                    }
                    distinctCounts.toMutableMap().apply {
                        searchCounts.forEach { (id, count) ->
                            if (count > 0) this[id] = count
                        }
                    }
                }
            }
            _covers.value = resolved
            _relations.value = relationMap
            _photoCounts.value = photoCountMap
            _totalPersonCount.value = all.size

            // 默认隐藏「未命名且只有 1 张人脸」的单人碎片，减少主界面噪音；
            // 用户可一键切换显示全部。
            val coverable = PersonCoverResolver.filterCoverable(all, resolved)
            _persons.value = if (_showAll.value) {
                coverable.sortedForDisplay(relationMap, photoCountMap)
            } else {
                coverable.filter { person ->
                    !person.name.isNullOrBlank() || (photoCountMap[person.personId] ?: person.faceCount) >= 2
                }.sortedForDisplay(relationMap, photoCountMap)
            }
        }
    }

    /** 切换「显示全部 / 隐藏单张未命名单人分组」。切换后自动重新加载。 */
    fun toggleShowAll() {
        _showAll.value = !_showAll.value
        load()
    }

    /** 进入人物页：先对齐 persons 表（清孤儿/修悬空封面/重算 faceCount），再加载。幂等。 */
    fun reconcileAndLoad() {
        viewModelScope.launch {
            personRepository.reconcilePersons()
            // 聚类维护：拆分（两个不同的人被并成一组）+ 合并（同一人被拆成多组），进人物页即时愈合。
            // 失败不阻断人物页加载。
            withContext(Dispatchers.IO) {
                runCatching { faceClusterEngine.runClusterMaintenance() }
                    .onFailure { Log.w("PersonViewModel", "runClusterMaintenance failed", it) }
            }
            load()
        }
    }

    fun startEditing(personId: Long) {
        _editingPersonId.value = personId
    }

    fun stopEditing() {
        _editingPersonId.value = null
    }

    /** 行内改名保存。 */
    fun updateName(personId: Long, name: String) {
        viewModelScope.launch {
            try {
                val trimmed = name.trim()
                if (trimmed.isNotBlank()) {
                    personRepository.renamePerson(personId, trimmed)
                }
                stopEditing()
                load()
            } catch (e: Exception) {
                _errorMessage.value = e.message
            }
        }
    }

    /** 更新封面。 */
    fun updateCover(personId: Long, mediaId: Long) {
        viewModelScope.launch {
            try {
                personRepository.updateCover(personId, mediaId)
                load()
            } catch (e: Exception) {
                _errorMessage.value = e.message
            }
        }
    }

    /** 更新人物信息（关系/自定义称呼/"我"标记）。 */
    fun updatePersonInfo(
        personId: Long,
        relation: RelationPredicate?,
        customLabel: String,
        isSelf: Boolean
    ) {
        viewModelScope.launch {
            try {
                val person = _persons.value.find { it.personId == personId } ?: return@launch
                val name = person.name ?: ""
                personRepository.applyPersonEdit(personId, name, relation, customLabel, isSelf)
                load()
            } catch (e: Exception) {
                _errorMessage.value = e.message
            }
        }
    }

    /** 读取某人物关联的全部媒体（供封面选择 Sheet）。单人照优先，避免合影作封面。 */
    suspend fun loadPhotosByPerson(personId: Long): List<MediaEntity> =
        withContext(Dispatchers.IO) {
            // 防御性去重：同一人可能在同一张照片里有多个人脸 embedding，
            // 即使 DAO 已用 DISTINCT，UI 层再保一次险，避免 LazyVerticalGrid 重复 key 崩溃。
            db.personDao().getMediaByPersonOrderedForCover(personId).distinctBy { it.id }
        }

    fun clearError() {
        _errorMessage.value = null
    }

    /**
     * 人物页排序：
     * 1. 亲密级：我 > 恋人/配偶 > 偶像 > 亲属 > 其他社会关系 > 无关系
     * 2. 同亲密级下：已命名者优先；已命名者按照片数（faceCount）倒序，未命名者按最近更新（新照片）倒序
     * 3. 最后以 updatedAt 作兜底去稳定
     */
    private fun List<PersonEntity>.sortedForDisplay(
        relations: Map<Long, RelationDisplayItem?>,
        photoCounts: Map<Long, Int>
    ): List<PersonEntity> {
        return sortedWith(
            compareByDescending<PersonEntity> { person -> intimacyPriority(person, relations) }
                .thenByDescending { person -> !person.name.isNullOrBlank() }
                .thenByDescending { person ->
                    if (!person.name.isNullOrBlank()) {
                        (photoCounts[person.personId] ?: person.faceCount).toLong()
                    } else {
                        person.updatedAt
                    }
                }
                .thenByDescending { it.updatedAt }
        )
    }

    private fun intimacyPriority(
        person: PersonEntity,
        relations: Map<Long, RelationDisplayItem?>
    ): Int = when {
        person.isSelf -> 5
        relations[person.personId]?.predicate in ROMANTIC_PREDICATES -> 4
        relations[person.personId]?.predicate == RelationPredicate.IDOL -> 3
        relations[person.personId]?.predicate in FAMILY_PREDICATES -> 2
        relations[person.personId]?.predicate != null -> 1
        else -> 0
    }

    private fun relationToDisplay(
        person: PersonEntity,
        relation: PersonRelationEntity?
    ): RelationDisplayItem? {
        if (relation == null) return null
        val predicate = RelationPredicate.fromStored(relation.predicate) ?: return null
        return RelationDisplayItem(
            relationId = relation.relationId,
            subjectPersonId = person.personId,
            subjectName = person.name ?: "#${person.personId}",
            predicate = predicate,
            customLabel = relation.customLabel?.trim()?.ifEmpty { null }
        )
    }

    companion object {
        private val ROMANTIC_PREDICATES = setOf(
            RelationPredicate.PARTNER,
            RelationPredicate.SPOUSE
        )

        private val FAMILY_PREDICATES = setOf(
            RelationPredicate.CHILD,
            RelationPredicate.SON,
            RelationPredicate.DAUGHTER,
            RelationPredicate.PARENT,
            RelationPredicate.FATHER,
            RelationPredicate.MOTHER,
            RelationPredicate.SIBLING,
            RelationPredicate.ELDER_BROTHER,
            RelationPredicate.ELDER_SISTER,
            RelationPredicate.YOUNGER_BROTHER,
            RelationPredicate.YOUNGER_SISTER,
            RelationPredicate.GRANDPARENT,
            RelationPredicate.GRANDFATHER,
            RelationPredicate.GRANDMOTHER,
            RelationPredicate.GRANDCHILD,
            RelationPredicate.OTHER_FAMILY
        )

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
