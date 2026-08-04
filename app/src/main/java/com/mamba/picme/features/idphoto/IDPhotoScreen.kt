package com.mamba.picme.features.idphoto

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
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
                    // 底图仅在加载/换底色时重建；手势只改 graphicsLayer 变换参数，保证跟手
                    val base by produceState<android.graphics.Bitmap?>(
                        initialValue = null,
                        s.selectedColorIndex
                    ) {
                        value = viewModel.previewBase()
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
                            val bmp = base
                            val cropRect = viewModel.currentCropRect()
                            if (bmp != null && cropRect != null) {
                                val sizeSpec = IDPhotoSpecs.SIZES[s.selectedSizeIndex]
                                val frameW = 220.dp
                                val frameH = frameW * sizeSpec.heightPx / sizeSpec.widthPx
                                Box(
                                    modifier = Modifier
                                        .size(frameW, frameH)
                                        .background(Color.White, RoundedCornerShape(4.dp))
                                        .clip(RoundedCornerShape(4.dp))
                                        .pointerInput(Unit) {
                                            detectTransformGestures { _, pan, gestureZoom, _ ->
                                                viewModel.transformBy(
                                                    dxFraction = pan.x / size.width,
                                                    dyFraction = pan.y / size.height,
                                                    zoomChange = gestureZoom
                                                )
                                            }
                                        }
                                ) {
                                    // 直接把 cropRect 区域拉伸绘制到 frame。曾用 graphicsLayer 对整张 bmp 做
                                    // scale+translation，但「子 layout(requiredSize=bmp 尺寸) 超出父 Box 后再经
                                    // graphicsLayer 缩放/平移」的绘制在 frame 边缘不精确，会在底部/右侧露出白边。
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
                                    }
                                }
                            } else {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Text(
                            text = stringResource(R.string.id_photo_drag_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.6f)
                        )
                        ColorSwatchRow(IDPhotoSpecs.COLORS, s.selectedColorIndex, viewModel::selectColor)
                        SizeChipRow(IDPhotoSpecs.SIZES, s.selectedSizeIndex, viewModel::selectSize)
                    }
                }
            }
        }
    }
}
