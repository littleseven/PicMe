package com.mamba.picme.features.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mamba.picme.R
import com.mamba.picme.features.camera.components.BeautyPanel
import com.mamba.picme.features.editor.components.AdjustPanel
import com.mamba.picme.features.editor.components.CropPanel
import com.mamba.picme.features.editor.components.EditorBottomBar
import com.mamba.picme.features.editor.components.EditorTopBar
import com.mamba.picme.features.editor.components.FilterPanel
import com.mamba.picme.features.editor.components.MarkupPanel

@Composable
fun PhotoEditorScreen(
    sourceUri: String,
    recipeUri: String?,
    autoOptimize: Boolean = false,
    viewModel: PhotoEditorViewModel,
    onNavigateBack: () -> Unit,
    onEditSaved: (String) -> Unit
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.load(context, sourceUri, recipeUri, autoOptimize)
        viewModel.onSaveComplete = onEditSaved
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.onSaveComplete = null }
    }

    Scaffold(
        topBar = {
            EditorTopBar(
                title = stringResource(R.string.edit),
                canUndo = viewModel.canUndo,
                canRedo = viewModel.canRedo,
                isSaving = (state as? PhotoEditorViewModel.State.Ready)?.isSaving == true,
                onCancel = onNavigateBack,
                onUndo = viewModel::undo,
                onRedo = viewModel::redo,
                onCompare = { /* handled in preview */ },
                onAiOptimize = viewModel::aiOptimize,
                onDone = {
                    val ready = state as? PhotoEditorViewModel.State.Ready ?: return@EditorTopBar
                    viewModel.save(context, ready.recipe)
                }
            )
        },
        bottomBar = {
            val ready = state as? PhotoEditorViewModel.State.Ready ?: return@Scaffold
            Column(modifier = Modifier.navigationBarsPadding()) {
                PanelForTab(
                    tab = ready.selectedTab,
                    recipe = ready.recipe,
                    onRecipeChange = viewModel::updateRecipe
                )
                EditorBottomBar(
                    selectedTab = ready.selectedTab,
                    onTabSelected = viewModel::selectTab
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            when (val s = state) {
                is PhotoEditorViewModel.State.Loading -> {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
                is PhotoEditorViewModel.State.Error -> {
                    Text(
                        text = s.message,
                        color = Color.White,
                        modifier = Modifier.padding(16.dp)
                    )
                }
                is PhotoEditorViewModel.State.Ready -> {
                    var comparing by remember { mutableStateOf(false) }
                    var scale by remember { mutableFloatStateOf(1f) }
                    var offsetX by remember { mutableFloatStateOf(0f) }
                    var offsetY by remember { mutableFloatStateOf(0f) }

                    // 切换编辑 tab 或更换源图时重置缩放/平移，避免用户在不同工具间跳转时
                    // 仍保留上一状态的放大视图，导致预览图只显示局部而误以为被裁剪。
                    LaunchedEffect(sourceUri, s.selectedTab) {
                        scale = 1f
                        offsetX = 0f
                        offsetY = 0f
                    }

                    val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
                        scale = (scale * zoomChange).coerceIn(1f, 4f)
                        offsetX += panChange.x
                        offsetY += panChange.y
                    }
                    val displayBitmap = if (comparing) s.originalBitmap else s.previewBitmap
                    Image(
                        bitmap = displayBitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.cd_image_preview),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offsetX
                                translationY = offsetY
                            }
                            .transformable(state = transformableState)
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onDoubleTap = {
                                        scale = 1f
                                        offsetX = 0f
                                        offsetY = 0f
                                    },
                                    onPress = {
                                        comparing = true
                                        tryAwaitRelease()
                                        comparing = false
                                    }
                                )
                            }
                    )
                    if (s.isProcessing) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelForTab(
    tab: PhotoEditorViewModel.EditorTab,
    recipe: EditRecipe,
    onRecipeChange: (EditRecipe) -> Unit
) {
    when (tab) {
        PhotoEditorViewModel.EditorTab.CROP -> CropPanel(
            crop = recipe.crop,
            onChange = { onRecipeChange(recipe.copy(crop = it)) }
        )
        PhotoEditorViewModel.EditorTab.ADJUST -> AdjustPanel(
            adjustments = recipe.adjustments,
            onChange = { onRecipeChange(recipe.copy(adjustments = it)) }
        )
        PhotoEditorViewModel.EditorTab.BEAUTY -> {
            BeautyPanel(
                settings = recipe.beauty,
                onSettingsChanged = { onRecipeChange(recipe.copy(beauty = it)) },
                onDismiss = {}
            )
        }
        PhotoEditorViewModel.EditorTab.FILTER -> FilterPanel(
            colorFilter = recipe.colorFilter,
            styleFilter = recipe.styleFilter,
            onChange = { colorFilter, styleFilter ->
                onRecipeChange(recipe.copy(colorFilter = colorFilter, styleFilter = styleFilter))
            }
        )
        PhotoEditorViewModel.EditorTab.MARKUP -> MarkupPanel(
            actions = recipe.markup,
            onChange = { onRecipeChange(recipe.copy(markup = it)) }
        )
    }
}
