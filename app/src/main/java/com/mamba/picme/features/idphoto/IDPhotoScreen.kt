package com.mamba.picme.features.idphoto

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.mamba.picme.R
import com.mamba.picme.domain.matting.IDPhotoComposer
import com.mamba.picme.domain.matting.IDPhotoSpecs
import com.mamba.picme.domain.matting.StrokeMode
import com.mamba.picme.features.common.topbar.AppTopBar
import com.mamba.picme.features.common.topbar.AppTopBarAction
import com.mamba.picme.features.idphoto.components.ColorSwatchRow
import com.mamba.picme.features.idphoto.components.EdgePanel
import com.mamba.picme.features.idphoto.components.IdPhotoTabRow
import com.mamba.picme.features.idphoto.components.RepairPanel
import com.mamba.picme.features.idphoto.components.RepairPanelCallbacks
import com.mamba.picme.features.idphoto.components.RepairPanelState
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
                    // 底图在加载/换底色/参数或描边变化时重建；手势只改 cropRect 绘制窗口，保证跟手
                    val base by produceState<android.graphics.Bitmap?>(
                        initialValue = null,
                        s.selectedColorIndex, s.edgeParams, s.strokeVersion
                    ) {
                        value = viewModel.previewBase()
                    }
                    // 修补 tab 的本地交互态（画框坐标系，仅用于实时覆盖层与笔刷光标）
                    var brushMode by remember { mutableStateOf(StrokeMode.ERASE) }
                    var brushSize by remember { mutableFloatStateOf(32f) }
                    var softEdge by remember { mutableStateOf(false) }
                    val overlayPoints = remember { mutableStateListOf<Offset>() }
                    var cursor by remember { mutableStateOf<Offset?>(null) }

                    // 重建期间（base 被重置为 null）保留上一帧底图，避免 spinner 闪断与覆盖层消失
                    var lastBase by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
                    LaunchedEffect(base) {
                        if (base != null) {
                            lastBase = base
                            overlayPoints.clear() // 新底图就绪后无缝交接覆盖层（无覆盖层时 no-op）
                        }
                    }
                    val shownBase = base ?: lastBase

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
                            val bmp = shownBase
                            val cropRect = viewModel.currentCropRect()
                            if (bmp != null && cropRect != null) {
                                val sizeSpec = IDPhotoSpecs.SIZES[s.selectedSizeIndex]
                                val frameW = 220.dp
                                val frameH = frameW * sizeSpec.heightPx / sizeSpec.widthPx
                                val repairing = s.activeTab == IdPhotoTab.REPAIR
                                Box(
                                    modifier = Modifier
                                        .size(frameW, frameH)
                                        .background(Color.White, RoundedCornerShape(4.dp))
                                        .clip(RoundedCornerShape(4.dp))
                                        .then(
                                            if (repairing) {
                                                // 不变量：REPAIR tab 不会改变 cropRect（涂抹期间禁缩放），故描边中途不会因 key 变化重启手势块
                                                Modifier.pointerInput(cropRect) {
                                                    try {
                                                        detectDragGestures(
                                                            onDragStart = { start ->
                                                                overlayPoints.clear()
                                                                cursor = start
                                                                val radiusSrc = IDPhotoComposer.frameRadiusToSource(
                                                                    brushSize / 2f, size.width.toFloat(), cropRect
                                                                )
                                                                viewModel.beginStroke(
                                                                    brushMode, radiusSrc,
                                                                    if (softEdge) 0.5f else 0f
                                                                )
                                                                overlayPoints.add(start)
                                                                viewModel.appendStrokePoint(
                                                                    IDPhotoComposer.frameToSource(
                                                                        px = start.x, py = start.y,
                                                                        frameW = size.width.toFloat(),
                                                                        frameH = size.height.toFloat(),
                                                                        crop = cropRect
                                                                    )
                                                                )
                                                            },
                                                            onDrag = { change, _ ->
                                                                change.consume()
                                                                cursor = change.position
                                                                overlayPoints.add(change.position)
                                                                viewModel.appendStrokePoint(
                                                                    IDPhotoComposer.frameToSource(
                                                                        px = change.position.x,
                                                                        py = change.position.y,
                                                                        frameW = size.width.toFloat(),
                                                                        frameH = size.height.toFloat(),
                                                                        crop = cropRect
                                                                    )
                                                                )
                                                            },
                                                            onDragEnd = {
                                                                cursor = null
                                                                viewModel.endStroke()
                                                            },
                                                            onDragCancel = {
                                                                cursor = null
                                                                overlayPoints.clear()
                                                                viewModel.endStroke()
                                                            }
                                                        )
                                                    } finally {
                                                        // 协程被取消（切 tab / pointerInput 重启）时 detectDragGestures 不走 onDragCancel，兜底收尾
                                                        if (cursor != null) {
                                                            cursor = null
                                                            overlayPoints.clear()
                                                            viewModel.endStroke()
                                                        }
                                                    }
                                                }
                                            } else {
                                                Modifier.pointerInput(Unit) {
                                                    detectTransformGestures { _, pan, gestureZoom, _ ->
                                                        viewModel.transformBy(
                                                            dxFraction = pan.x / size.width,
                                                            dyFraction = pan.y / size.height,
                                                            zoomChange = gestureZoom
                                                        )
                                                    }
                                                }
                                            }
                                        )
                                ) {
                                    val imageBmp = remember(bmp) { bmp.asImageBitmap() }
                                    Canvas(modifier = Modifier.fillMaxSize()) {
                                        drawImage(
                                            image = imageBmp,
                                            srcOffset = IntOffset(cropRect.left, cropRect.top),
                                            srcSize = IntSize(
                                                cropRect.right - cropRect.left,
                                                cropRect.bottom - cropRect.top
                                            ),
                                            dstSize = IntSize(size.width.toInt(), size.height.toInt())
                                        )
                                        // 进行中的描边覆盖层：恢复=白、擦除=黑，40% 透明
                                        if (overlayPoints.isNotEmpty()) {
                                            val overlayColor =
                                                (if (brushMode == StrokeMode.RESTORE) Color.White else Color.Black)
                                                    .copy(alpha = 0.4f)
                                            for (i in 1 until overlayPoints.size) {
                                                drawLine(
                                                    color = overlayColor,
                                                    start = overlayPoints[i - 1],
                                                    end = overlayPoints[i],
                                                    strokeWidth = brushSize,
                                                    cap = StrokeCap.Round
                                                )
                                            }
                                        }
                                        // 笔刷圈光标
                                        cursor?.let { pos ->
                                            drawCircle(
                                                color = Color.White.copy(alpha = 0.8f),
                                                radius = brushSize / 2f,
                                                center = pos,
                                                style = Stroke(width = 2f)
                                            )
                                        }
                                    }
                                }
                            } else {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Text(
                            text = stringResource(
                                if (s.activeTab == IdPhotoTab.REPAIR) R.string.id_photo_repair_hint
                                else R.string.id_photo_drag_hint
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.6f)
                        )
                        IdPhotoTabRow(selected = s.activeTab, onSelect = viewModel::selectTab)
                        when (s.activeTab) {
                            IdPhotoTab.BG_COLOR ->
                                ColorSwatchRow(IDPhotoSpecs.COLORS, s.selectedColorIndex, viewModel::selectColor)
                            IdPhotoTab.SIZE ->
                                SizeChipRow(IDPhotoSpecs.SIZES, s.selectedSizeIndex, viewModel::selectSize)
                            IdPhotoTab.EDGE ->
                                EdgePanel(
                                    params = s.edgeParams,
                                    onParamsChange = viewModel::setEdgeParams,
                                    onReset = viewModel::resetEdgeParams
                                )
                            IdPhotoTab.REPAIR ->
                                RepairPanel(
                                    state = RepairPanelState(
                                        mode = brushMode,
                                        brushSizePx = brushSize,
                                        softEdge = softEdge,
                                        canUndo = s.canUndoStroke,
                                        canRedo = s.canRedoStroke,
                                        hasStrokes = s.canUndoStroke || s.canRedoStroke
                                    ),
                                    callbacks = RepairPanelCallbacks(
                                        onModeChange = { brushMode = it },
                                        onBrushSizeChange = { brushSize = it },
                                        onSoftEdgeChange = { softEdge = it },
                                        onUndo = viewModel::undoStroke,
                                        onRedo = viewModel::redoStroke,
                                        onClear = viewModel::clearStrokes
                                    )
                                )
                        }
                    }
                }
            }
        }
    }
}
