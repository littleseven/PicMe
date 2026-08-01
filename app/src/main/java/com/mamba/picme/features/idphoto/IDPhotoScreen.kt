package com.mamba.picme.features.idphoto

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mamba.picme.R
import com.mamba.picme.domain.matting.IDPhotoSpecs
import com.mamba.picme.features.common.topbar.AppTopBar
import com.mamba.picme.features.common.topbar.AppTopBarAction
import com.mamba.picme.features.idphoto.components.ColorSwatchRow
import com.mamba.picme.features.idphoto.components.SizeChipRow

@Suppress("LongMethod") // 待重构：抽 IDPhoto 控制面板子组件
@Composable
fun IDPhotoScreen(
    sourceUri: String,
    viewModel: IDPhotoViewModel,
    onNavigateBack: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()

    LaunchedEffect(sourceUri) {
        viewModel.load(context, sourceUri)
        viewModel.onSaveComplete = { onSaved() }
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.id_photo_title),
                onBack = onNavigateBack,
                actions = {
                    val ready = state as? IDPhotoViewModel.State.Ready
                    AppTopBarAction(
                        icon = Icons.Rounded.Check,
                        contentDescription = stringResource(R.string.done),
                        onClick = { viewModel.save(context) },
                        enabled = ready != null && !ready.isSaving
                    )
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFF101010)),
            contentAlignment = Alignment.Center
        ) {
            when (val s = state) {
                is IDPhotoViewModel.State.Loading -> CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                is IDPhotoViewModel.State.Error -> Text(s.message, color = Color.White, modifier = Modifier.padding(16.dp))
                is IDPhotoViewModel.State.Ready -> {
                    val preview by produceState<android.graphics.Bitmap?>(
                        initialValue = null,
                        s.selectedColorIndex,
                        s.selectedSizeIndex
                    ) {
                        value = viewModel.composePreview()
                    }
                    Column(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            val bmp = preview
                            if (bmp != null) {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = stringResource(R.string.id_photo_title),
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .size(width = 220.dp, height = 300.dp)
                                        .background(Color.White, RoundedCornerShape(4.dp))
                                )
                            } else {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        ColorSwatchRow(IDPhotoSpecs.COLORS, s.selectedColorIndex, viewModel::selectColor)
                        SizeChipRow(IDPhotoSpecs.SIZES, s.selectedSizeIndex, viewModel::selectSize)
                    }
                }
            }
        }
    }
}
