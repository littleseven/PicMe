package com.mamba.picme.features.person

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.mamba.picme.R
import com.mamba.picme.core.image.faceAwareVerticalAlignment
import com.mamba.picme.data.local.entity.PersonEntity
import com.mamba.picme.domain.person.RelationPredicate
import com.mamba.picme.features.common.PersonRenameDialog

/**
 * 「人物」页：全部人脸聚类 = 封面网格（coverMediaId 整图 + faceAwareVerticalAlignment 人脸感知纵向对齐）。
 * 点封面 → [PersonRenameDialog]（改名/标关系/标"我"）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonScreen(
    viewModel: PersonViewModel,
    onNavigateBack: () -> Unit
) {
    LaunchedEffect(Unit) { viewModel.load() }

    val persons by viewModel.persons.collectAsState()
    val covers by viewModel.covers.collectAsState()
    var editing by remember { mutableStateOf<PersonEntity?>(null) }

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
                }
            )
        }
    ) { innerPadding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 96.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(4.dp)
        ) {
            items(items = persons, key = { person -> person.personId }) { person ->
                PersonCoverCell(
                    person = person,
                    cover = covers[person.personId],
                    onClick = { editing = person }
                )
            }
        }
    }

    val target = editing
    if (target != null) {
        // v1：关系/自定义称呼不预填（留空），仅回填"这是我"标记
        PersonRenameDialog(
            initialName = target.name.orEmpty(),
            initialRelation = null,
            initialCustomLabel = "",
            initialIsSelf = target.isSelf,
            onConfirm = { name, relation, customLabel, isSelf ->
                viewModel.applyEdit(target.personId, name, customLabel, isSelf, relation)
            },
            onDismiss = { editing = null }
        )
    }
}

@Composable
private fun PersonCoverCell(
    person: PersonEntity,
    cover: PersonCover?,
    onClick: () -> Unit
) {
    val name = person.name ?: stringResource(R.string.people_default_name, person.personId)
    val count = stringResource(R.string.people_photos_count, person.faceCount)
    Box(
        modifier = Modifier
            .padding(4.dp)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        val uri = cover?.coverUri
        if (uri != null) {
            AsyncImage(
                model = uri,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                alignment = faceAwareVerticalAlignment(cover?.faceFocusY),
                modifier = Modifier.fillMaxSize()
            )
        }
        // 底部半透明名条：名字 · 张数
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(4.dp)
        ) {
            Text(
                text = "$name · $count",
                color = Color.White,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
