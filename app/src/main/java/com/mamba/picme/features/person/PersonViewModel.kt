package com.mamba.picme.features.person

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mamba.picme.data.local.AppDatabase
import com.mamba.picme.data.local.entity.PersonEntity
import com.mamba.picme.domain.person.PersonRepository
import com.mamba.picme.domain.person.RelationPredicate
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
    private val db: AppDatabase
) : ViewModel() {

    private val _persons = MutableStateFlow<List<PersonEntity>>(emptyList())
    val persons: StateFlow<List<PersonEntity>> = _persons.asStateFlow()

    private val _covers = MutableStateFlow<Map<Long, PersonCover>>(emptyMap())
    val covers: StateFlow<Map<Long, PersonCover>> = _covers.asStateFlow()

    /** 加载全部人物簇并解析封面（coverMediaId → uri + faceFocusY）。 */
    fun load() {
        viewModelScope.launch {
            val all = personRepository.getAllPersons()
            _persons.value = all
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
