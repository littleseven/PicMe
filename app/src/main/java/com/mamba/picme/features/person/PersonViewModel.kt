package com.mamba.picme.features.person

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
import kotlinx.coroutines.Dispatchers
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
    internal val db: AppDatabase
) : ViewModel() {

    private val _persons = MutableStateFlow<List<PersonEntity>>(emptyList())
    val persons: StateFlow<List<PersonEntity>> = _persons.asStateFlow()

    private val _covers = MutableStateFlow<Map<Long, PersonCover>>(emptyMap())
    val covers: StateFlow<Map<Long, PersonCover>> = _covers.asStateFlow()

    private val _relations = MutableStateFlow<Map<Long, RelationDisplayItem?>>(emptyMap())
    val relations: StateFlow<Map<Long, RelationDisplayItem?>> = _relations.asStateFlow()

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
            _covers.value = resolved
            _relations.value = relationMap
            // 防御：coverMediaId 悬空（封面媒体已删）的聚类 coverUri 为 null，不展示，避免空白格。
            // 正常情况下 reconcileAndLoad 已先行清理，此处为兜底。
            _persons.value = PersonCoverResolver.filterCoverable(all, resolved)
        }
    }

    /** 进入人物页：先对齐 persons 表（清孤儿/修悬空封面/重算 faceCount），再加载。幂等。 */
    fun reconcileAndLoad() {
        viewModelScope.launch {
            personRepository.reconcilePersons()
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

    /** 读取某人物关联的全部媒体（供封面选择 Sheet）。 */
    suspend fun loadPhotosByPerson(personId: Long): List<MediaEntity> =
        withContext(Dispatchers.IO) {
            db.personDao().getMediaByPerson(personId)
        }

    fun clearError() {
        _errorMessage.value = null
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
        /** ViewModelProvider.Factory：参照 MemoryFactsViewModel.factory 范式。 */
        fun factory(personRepository: PersonRepository, db: AppDatabase): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(PersonViewModel::class.java)) {
                        @Suppress("UNCHECKED_CAST")
                        return PersonViewModel(personRepository, db) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
    }
}
