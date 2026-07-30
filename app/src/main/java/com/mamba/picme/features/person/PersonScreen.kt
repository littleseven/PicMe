package com.mamba.picme.features.person

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mamba.picme.PoLangApplication
import com.mamba.picme.R
import com.mamba.picme.data.local.entity.PersonEntity
import com.mamba.picme.data.model.MediaEntity
import com.mamba.picme.features.person.components.PersonCoverPickerSheet
import com.mamba.picme.features.person.components.PersonInfoSheet
import com.mamba.picme.features.person.components.PersonListItem
import kotlinx.coroutines.launch

/**
 * 「人物」页：双列网格展示全部人脸聚类。
 * 支持行内改名、展开式 Bottom Sheet 编辑关系/「我」标记、底部 Sheet 选择封面。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonScreen(
    viewModel: PersonViewModel,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { viewModel.reconcileAndLoad() }

    var scoring by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val persons by viewModel.persons.collectAsState()
    val covers by viewModel.covers.collectAsState()
    val relations by viewModel.relations.collectAsState()
    val editingPersonId by viewModel.editingPersonId.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    var infoTarget by remember { mutableStateOf<PersonEntity?>(null) }
    var coverTarget by remember { mutableStateOf<PersonEntity?>(null) }
    var photos by remember { mutableStateOf<List<MediaEntity>>(emptyList()) }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(errorMessage) {
        errorMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearError()
        }
    }

    LaunchedEffect(coverTarget) {
        val target = coverTarget
        photos = if (target != null) {
            viewModel.loadPhotosByPerson(target.personId)
        } else {
            emptyList()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.people_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    // 手动触发：跑一轮（300 张）eDifFIQA 打分 + 刷新封面，完成后 reload 看效果
                    IconButton(
                        onClick = {
                            if (scoring) return@IconButton
                            scope.launch {
                                scoring = true
                                try {
                                    val app = context.applicationContext as? PoLangApplication
                                    app?.container?.aestheticScoreWorker?.runOnce(300)
                                    viewModel.reconcileAndLoad()
                                } finally {
                                    scoring = false
                                }
                            }
                        },
                        enabled = !scoring
                    ) {
                        if (scoring) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                Icons.Rounded.AutoAwesome,
                                contentDescription = stringResource(R.string.people_rescore)
                            )
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(
                    items = persons,
                    key = { person -> person.personId }
                ) { person ->
                    PersonListItem(
                        person = person,
                        cover = covers[person.personId],
                        relation = relations[person.personId],
                        isEditingName = editingPersonId == person.personId,
                        onCoverClick = { coverTarget = person },
                        onNameClick = { viewModel.startEditing(person.personId) },
                        onNameSave = { name -> viewModel.updateName(person.personId, name) },
                        onNameCancel = { viewModel.stopEditing() },
                        onInfoClick = { infoTarget = person }
                    )
                }
            }
        }
    }

    infoTarget?.let { person ->
        PersonInfoSheet(
            relation = relations[person.personId],
            isSelf = person.isSelf,
            onSave = { relation, customLabel, isSelf ->
                viewModel.updatePersonInfo(person.personId, relation, customLabel, isSelf)
            },
            onDismiss = { infoTarget = null }
        )
    }

    coverTarget?.let { person ->
        if (photos.isNotEmpty()) {
            PersonCoverPickerSheet(
                photos = photos,
                onSelect = { photo ->
                    viewModel.updateCover(person.personId, photo.id)
                    coverTarget = null
                },
                onDismiss = { coverTarget = null }
            )
        }
    }
}
