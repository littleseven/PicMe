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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.mamba.picme.R
import kotlinx.coroutines.withTimeoutOrNull
import com.mamba.picme.features.camera.components.BeautyPanel
import com.mamba.picme.features.editor.components.AdjustPanel
import com.mamba.picme.features.editor.components.CheckerboardBackground
import com.mamba.picme.features.editor.components.CropPanel
import com.mamba.picme.features.editor.components.CropTransformOverlay
import com.mamba.picme.features.editor.components.EditorBottomBar
import com.mamba.picme.features.editor.components.EditorTopBar
import com.mamba.picme.features.editor.components.FilterPanel
import com.mamba.picme.features.editor.components.GachaCandidateBar
import com.mamba.picme.features.editor.components.MarkupDrawingOverlay
import com.mamba.picme.features.editor.components.MarkupPanel
import com.mamba.picme.features.editor.components.MarkupTextInputDialog
import com.mamba.picme.features.editor.components.MarkupToolState
import com.mamba.picme.features.editor.components.MARKUP_DEFAULT_TEXT_SIZE
import java.util.UUID

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
    val markupToolState = remember { MarkupToolState() }

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
                onRemoveBackground = viewModel::removeBackground,
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
                val run = ready.gachaRun
                if (run != null) {
                    // 抽卡对比模式：隐藏面板与底栏，只保留候选条，主预览区用于大图对比
                    GachaCandidateBar(
                        run = run,
                        onApply = viewModel::applyGachaCandidate,
                        onReroll = viewModel::rerollGacha,
                        onPreview = viewModel::previewGachaCandidate,
                        onDismiss = viewModel::dismissGacha
                    )
                } else {
                    PanelForTab(
                        tab = ready.selectedTab,
                        recipe = ready.recipe,
                        markupToolState = markupToolState,
                        onRecipeChange = viewModel::updateRecipe
                    )
                    EditorBottomBar(
                        selectedTab = ready.selectedTab,
                        onTabSelected = viewModel::selectTab
                    )
                }
            }
        }
    ) { padding ->
        val transparent = (state as? PhotoEditorViewModel.State.Ready)
            ?.recipe?.cutout?.bgMode == CutoutRecipe.BgMode.TRANSPARENT
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .clipToBounds()
                .background(if (transparent) Color.Transparent else Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (transparent) {
                CheckerboardBackground(Modifier.fillMaxSize())
            }
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
                    var viewSize by remember { mutableStateOf(IntSize.Zero) }
                    val viewConfiguration = LocalViewConfiguration.current
                    // 标记模式下预览区手势让位给绘制：禁用缩放/平移与长按对比，
                    // 且进入 MARKUP tab 时 LaunchedEffect 已将缩放重置为 1x，
                    // 覆盖层可按 Fit 矩形直接做视图→图片坐标换算
                    val markupMode = s.selectedTab == PhotoEditorViewModel.EditorTab.MARKUP &&
                        s.gachaRun == null

                    // 切换编辑 tab 或更换源图时重置缩放/平移，避免用户在不同工具间跳转时
                    // 仍保留上一状态的放大视图，导致预览图只显示局部而误以为被裁剪。
                    LaunchedEffect(sourceUri, s.selectedTab) {
                        scale = 1f
                        offsetX = 0f
                        offsetY = 0f
                    }

                    val bitmap = s.previewBitmap
                    val bitmapRatio = bitmap.width.toFloat() / bitmap.height

                    fun clampOffsets(nextScale: Float = scale) {
                        val viewW = viewSize.width.toFloat()
                        val viewH = viewSize.height.toFloat()
                        if (viewW <= 0f || viewH <= 0f) return
                        val viewRatio = viewW / viewH
                        val (fitW, fitH) = if (bitmapRatio > viewRatio) {
                            viewW to (viewW / bitmapRatio)
                        } else {
                            (viewH * bitmapRatio) to viewH
                        }
                        val scaledW = fitW * nextScale
                        val scaledH = fitH * nextScale
                        val maxX = maxOf(0f, (scaledW - viewW) / 2f)
                        val maxY = maxOf(0f, (scaledH - viewH) / 2f)
                        offsetX = offsetX.coerceIn(-maxX, maxX)
                        offsetY = offsetY.coerceIn(-maxY, maxY)
                    }

                    val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
                        val nextScale = (scale * zoomChange).coerceIn(1f, 4f)
                        scale = nextScale
                        offsetX += panChange.x
                        offsetY += panChange.y
                        clampOffsets(nextScale)
                    }
                    val displayBitmap = if (comparing) s.originalBitmap else bitmap
                    Image(
                        bitmap = displayBitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.cd_image_preview),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .onGloballyPositioned {
                                viewSize = it.size
                                clampOffsets()
                            }
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offsetX
                                translationY = offsetY
                            }
                            .transformable(state = transformableState, enabled = !markupMode)
                            .then(
                                if (markupMode) {
                                    Modifier
                                } else {
                                    Modifier.pointerInput(Unit) {
                                        detectTapGestures(
                                            onDoubleTap = {
                                                scale = 1f
                                                offsetX = 0f
                                                offsetY = 0f
                                            },
                                            onPress = {
                                                val releasedInTime = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                                    tryAwaitRelease()
                                                }
                                                if (releasedInTime == null) {
                                                    comparing = true
                                                    try {
                                                        tryAwaitRelease()
                                                    } finally {
                                                        comparing = false
                                                    }
                                                }
                                            }
                                        )
                                    }
                                }
                            )
                    )
                    // 标记绘制层：涂鸦/马赛克拖拽成笔、文字点按弹输入框
                    MarkupEditLayer(
                        markupMode = markupMode,
                        bitmapRatio = bitmapRatio,
                        markupToolState = markupToolState,
                        viewModel = viewModel
                    )
                    // 小米相册风格：CROP tab 下旋转/镜像悬浮在预览区底部左右角
                    if (s.selectedTab == PhotoEditorViewModel.EditorTab.CROP) {
                        CropTransformOverlay(
                            crop = s.recipe.crop,
                            onChange = { viewModel.updateRecipe(s.recipe.copy(crop = it)) }
                        )
                    }
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

/**
 * 标记编辑层：MARKUP tab 下叠加绘制覆盖层，文字工具点按后弹输入框。
 * 抽出以控制 [PhotoEditorScreen] 的圈复杂度（detekt 上限 20）。
 */
@Composable
private fun MarkupEditLayer(
    markupMode: Boolean,
    bitmapRatio: Float,
    markupToolState: MarkupToolState,
    viewModel: PhotoEditorViewModel
) {
    var pendingTextPosition by remember { mutableStateOf<NormPoint?>(null) }
    if (markupMode) {
        MarkupDrawingOverlay(
            toolState = markupToolState,
            bitmapRatio = bitmapRatio,
            onCommit = viewModel::addMarkupAction,
            onTextTap = { pendingTextPosition = it }
        )
    }
    pendingTextPosition?.let { pos ->
        MarkupTextInputDialog(
            onConfirm = { text ->
                viewModel.addMarkupAction(
                    MarkupAction.Text(
                        id = UUID.randomUUID().toString(),
                        text = text,
                        position = pos,
                        color = markupToolState.color,
                        size = MARKUP_DEFAULT_TEXT_SIZE
                    )
                )
                pendingTextPosition = null
            },
            onDismiss = { pendingTextPosition = null }
        )
    }
}

@Composable
private fun PanelForTab(
    tab: PhotoEditorViewModel.EditorTab,
    recipe: EditRecipe,
    markupToolState: MarkupToolState,
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
            toolState = markupToolState,
            actions = recipe.markup,
            onChange = { onRecipeChange(recipe.copy(markup = it)) }
        )
    }
}
