@file:Suppress("TooManyFunctions") // 待重构：ChatScreen 拆分为多个子文件以降低文件级函数数

package com.mamba.picme.features.chat

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.annotation.StringRes
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.ShortText
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ImageNotSupported
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.KeyboardVoice
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import java.util.Locale
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mamba.picme.R
import com.mamba.picme.core.common.Logger
import androidx.compose.material.icons.rounded.Menu
import androidx.activity.compose.BackHandler
import androidx.core.net.toUri
import com.mamba.picme.features.common.topbar.AppTopBar
import com.mamba.picme.features.common.topbar.AppTopBarAction
import com.mamba.picme.features.common.topbar.AppTopBarNavBack
import com.mamba.picme.features.chat.ChatThreadSidebar
import com.mamba.picme.data.preferences.UserPreferencesRepository
import dev.jeziellago.compose.markdowntext.MarkdownText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import androidx.compose.ui.draw.shadow
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.agent.core.model.command.FeedbackAction
import com.mamba.picme.features.chat.capability.ChatGallerySummaryCapability
import com.mamba.picme.features.chat.capability.ChatRunScriptCapability
import com.mamba.picme.features.chat.capability.ChatMediaWriteCapability
import com.mamba.picme.agent.core.model.command.CommandRisk
import com.mamba.picme.features.chat.capability.ChatSearchCapability
import com.mamba.picme.features.chat.capability.ChatStartTagScanCapability
import com.mamba.picme.features.chat.components.ChatEmptyState
import com.mamba.picme.features.chat.components.ChatPhotoPickerSheet
import com.mamba.picme.features.chat.components.ChatRegistrationSheet
import com.mamba.picme.features.chat.components.MediaResultsCarousel
import androidx.core.net.toUri
import com.mamba.picme.features.gallery.MediaViewModel
import com.mamba.picme.features.gallery.components.MediaPager
import com.mamba.picme.service.tag.TagGenerationService
import com.mamba.picme.agent.core.platform.voice.AsrEngine
import androidx.compose.runtime.mutableIntStateOf
import com.mamba.picme.agent.core.platform.voice.SherpaOnnxAsrEngine
import com.mamba.picme.features.camera.voice.SystemAsrEngine
import com.mamba.picme.features.camera.voice.PushToTalkEngine
import com.mamba.picme.features.settings.SettingsViewModel
import java.io.File

private const val TAG = "ChatScreen"

/**
 * Chat 二级页 — AI 对话入口
 *
 * 从相册首页通过 plus 菜单进入。页面提供返回按钮回到相册。
 * 布局：
 * - 顶部栏：返回 + 菜单 + 清空 + 新建（设置入口仅在相册首页，不在每个页面重复）
 * - 消息列表：LazyColumn 展示对话历史
 * - 输入区：ModelSelector + 输入框 + 发送按钮
 * - 快捷入口：相机 / 模型中心
 */
@Suppress("LongMethod", "LongParameterList", "CyclomaticComplexMethod") // 待重构：Top-level Compose screen，scaffold+list+input+sidebar，后续按区域拆子组件
@Composable
fun ChatScreen(
    viewModel: ChatViewModel = viewModel(),
    settingsViewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToGallery: (String) -> Unit = {},
    mediaViewModel: MediaViewModel,
    onNavigateToPhotoEditor: (uri: String, autoOptimize: Boolean) -> Unit = { _, _ -> },
    onNavigateToIDPhoto: (uri: String) -> Unit = {},
    /** 上报是否允许外层主页面 Pager 横滑（预览打开时禁用） */
    onHorizontalSwipeEnabledChange: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val view = LocalView.current
    val messages by viewModel.displayMessages.collectAsState()
    val isProcessing by viewModel.isProcessing.collectAsState()
    val currentModel by viewModel.currentModel.collectAsState()
    val threads by viewModel.filteredThreads.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val currentSessionId by viewModel.currentSessionId.collectAsState()
    val isGuestMode by viewModel.isGuestMode.collectAsState()
    val showRegistration by viewModel.showRegistrationSheet.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    var isSidebarOpen by remember { mutableStateOf(false) }
    // 图片预览状态（横滑翻页集合）
    var imagePreview by remember { mutableStateOf<ChatImagePreviewState?>(null) }
    var previewChartSvg by remember { mutableStateOf<String?>(null) }
    // 相册搜索结果预览状态
    var previewAssets by remember { mutableStateOf<List<MediaAsset>>(emptyList()) }
    var previewIndex by remember { mutableIntStateOf(0) }
    // 已点删除但等待媒体库刷新确认的图片 ID
    var pendingDeletedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }

    // 上报外层 Pager 横滑使能：任一全屏预览打开时禁用，避免与内层预览滑动冲突
    LaunchedEffect(previewAssets, imagePreview, previewChartSvg) {
        onHorizontalSwipeEnabledChange(
            previewAssets.isEmpty() && imagePreview == null && previewChartSvg == null
        )
    }

    // 媒体库全量数据：用于感知删除完成并同步清理 preview/chat 消息
    val allMedia by mediaViewModel.allMedia.collectAsState()
    val deleteAuthRequest by mediaViewModel.deleteAuthRequest.collectAsState()

    // Android 10 (API 29) 恢复性删除权限请求 launcher
    val api29DeleteLauncher = if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                Logger.d(TAG, "User granted API 29 delete permission")
                mediaViewModel.executePendingDeletes()
            } else {
                Logger.w(TAG, "User denied API 29 delete permission")
                mediaViewModel.clearPendingRecoverable()
                mediaViewModel.clearPendingDeleteUris()
            }
        }
    } else {
        null
    }

    // Android 11+ 删除权限请求 launcher
    val deletePermissionLauncher = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                Logger.d(TAG, "User granted delete permission")
                mediaViewModel.executePendingDeletes()
            } else {
                Logger.w(TAG, "User denied delete permission")
                mediaViewModel.clearPendingDeleteUris()
            }
        }
    } else {
        null
    }

    LaunchedEffect(deleteAuthRequest) {
        deleteAuthRequest?.let { request ->
            when (request) {
                is MediaViewModel.DeleteAuthRequest.Api29 -> {
                    api29DeleteLauncher?.launch(
                        IntentSenderRequest.Builder(request.intentSender).build()
                    )
                }
                is MediaViewModel.DeleteAuthRequest.Api30 -> {
                    val intent = MediaStore.createDeleteRequest(
                        context.contentResolver,
                        request.uris
                    )
                    deletePermissionLauncher?.launch(
                        IntentSenderRequest.Builder(intent).build()
                    )
                }
            }
            mediaViewModel.consumeDeleteAuthRequest()
        }
    }

    // ChatViewModel 侧（JS capability.dispatch 删除）触发的系统授权请求，复用上面的 launcher
    val chatWriteDeleteAuthRequest by viewModel.deleteAuthRequest.collectAsState()
    LaunchedEffect(chatWriteDeleteAuthRequest) {
        chatWriteDeleteAuthRequest?.let { request ->
            when (request) {
                is MediaViewModel.DeleteAuthRequest.Api29 -> {
                    api29DeleteLauncher?.launch(
                        IntentSenderRequest.Builder(request.intentSender).build()
                    )
                }
                is MediaViewModel.DeleteAuthRequest.Api30 -> {
                    val intent = MediaStore.createDeleteRequest(
                        context.contentResolver,
                        request.uris
                    )
                    deletePermissionLauncher?.launch(
                        IntentSenderRequest.Builder(intent).build()
                    )
                }
            }
            viewModel.consumeDeleteAuthRequest()
        }
    }

    // 当媒体库刷新后发现 preview 中的某张图已被物理删除，同步清理 preview 列表和 chat 消息
    LaunchedEffect(allMedia) {
        if (allMedia.isEmpty()) return@LaunchedEffect
        val existingIds = allMedia.map { it.id }.toSet()

        // 确认 pending 删除已实际生效（物理文件 + Room 记录已清理）
        if (pendingDeletedIds.isNotEmpty()) {
            val confirmedRemoved = pendingDeletedIds.filter { it != 0L && it !in existingIds }.toSet()
            if (confirmedRemoved.isNotEmpty()) {
                confirmedRemoved.forEach { viewModel.removeMediaResultAsset(it) }
                pendingDeletedIds = pendingDeletedIds - confirmedRemoved
            }
        }

        // 兜底：其他途径导致 previewAssets 中的图片已不在媒体库时，也清理 preview
        if (previewAssets.isNotEmpty()) {
            val removedIds = previewAssets
                .map { it.id }
                .filter { it != 0L && it !in existingIds }
                .toSet()
            if (removedIds.isNotEmpty()) {
                previewAssets = previewAssets.filter { it.id in existingIds }
            }
        }
    }

    // chat 系 Capability 已在 PoLangApplication 注册到全局 CapabilityRegistry（唯一注册表），
    // 本页只负责 delegate 绑定/解绑（2026-07-29 单轨收敛，Compose CapabilityHost 已退役）

    // 绑定 ChatSearchCapability Delegate（chat 场景相册搜索执行器）
    DisposableEffect(Unit) {
        ChatSearchCapability.getInstance().bindDelegate(viewModel)
        onDispose { ChatSearchCapability.getInstance().unbindDelegate() }
    }

    // 绑定 ChatGallerySummaryCapability Delegate
    DisposableEffect(Unit) {
        ChatGallerySummaryCapability.getInstance().bindDelegate(viewModel)
        onDispose { ChatGallerySummaryCapability.getInstance().unbindDelegate() }
    }

    // 绑定 ChatRunScriptCapability Delegate（端侧 JS 执行）
    DisposableEffect(Unit) {
        ChatRunScriptCapability.getInstance().bindDelegate(viewModel)
        onDispose { ChatRunScriptCapability.getInstance().unbindDelegate() }
    }

    // 绑定 ChatStartTagScanCapability Delegate
    DisposableEffect(Unit) {
        ChatStartTagScanCapability.getInstance().bindDelegate(viewModel)
        onDispose { ChatStartTagScanCapability.getInstance().unbindDelegate() }
    }

    // 绑定 ChatMediaWriteCapability Delegate（JS 写通路：删除/收藏/选中）
    DisposableEffect(Unit) {
        ChatMediaWriteCapability.getInstance().bindDelegate(viewModel)
        onDispose { ChatMediaWriteCapability.getInstance().unbindDelegate() }
    }

    BackHandler(enabled = isSidebarOpen) {
        isSidebarOpen = false
    }

    // 预览打开时拦截系统返回键：关闭预览并回到 chat 页（保留横滑卡片），
    // 而非直接 pop 到相册（Gallery 为 startDestination，栈底为 [Gallery, Chat]）。
    // 与 GalleryScreen 的预览 BackHandler 行为对齐。
    BackHandler(enabled = previewAssets.isNotEmpty() || imagePreview != null || previewChartSvg != null) {
        when {
            previewAssets.isNotEmpty() -> previewAssets = emptyList()
            imagePreview != null -> imagePreview = null
            previewChartSvg != null -> previewChartSvg = null
        }
    }

    // AI 优化命令触发后导航到编辑器
    val pendingOptimizeUri by viewModel.pendingAiOptimizeNavigation.collectAsState()
    LaunchedEffect(pendingOptimizeUri) {
        pendingOptimizeUri?.let { uri ->
            onNavigateToPhotoEditor(uri, true)
            viewModel.consumeAiOptimizeNavigation()
        }
    }

    // 自动滚动到底部：列表条数变化或最后一条内容变化时触发（支持流式打字效果）
    LaunchedEffect(messages.size, messages.lastOrNull()?.content) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // 进入聊天页后，若已开启本地推理或语音功能，在蜂窝网络下检查 Tier 2 模型
    LaunchedEffect(Unit) {
        settingsViewModel.checkChatModelsOnCellular()
    }

    // 沉浸式模式：隐藏系统栏
    DisposableEffect(Unit) {
        val activity = context as? Activity
        val window = activity?.window
        val insetsController = window?.let { WindowCompat.getInsetsController(it, view) }
        insetsController?.hide(WindowInsetsCompat.Type.systemBars())
        insetsController?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        onDispose {
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            // 预览（照片轮播 / 图片 / 图表全屏）打开时隐藏 chat 顶栏，让覆盖层占满整屏
            if (previewAssets.isEmpty() && imagePreview == null && previewChartSvg == null) {
                ChatTopBar(
                    onNavigateBack = onNavigateBack,
                    onOpenSidebar = { isSidebarOpen = true },
                    onNewChat = { viewModel.newSession() },
                    onClearChat = { viewModel.clearChat() }
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
            ) {
                // 消息列表 / 空状态引导
                if (messages.isEmpty()) {
                    ChatEmptyState(
                        isGuestMode = isGuestMode,
                        onExampleClick = { text -> viewModel.sendMessage(text) },
                        onRegisterClick = { viewModel.openRegistrationSheet() },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(messages, key = { it.id }) { message ->
                            val mr = message.mediaResults
                            if (message.type == ChatMessageType.MEDIA_RESULTS && mr != null) {
                                MediaResultsCarousel(
                                    mediaResults = mr,
                                    onCardClick = { index ->
                                        previewAssets = mr.assets
                                        previewIndex = index
                                    },
                                    onViewAll = {
                                        onNavigateToGallery(mr.query)
                                    },
                                    onFeedback = { mediaId, action ->
                                        viewModel.onMediaFeedback(mediaId, mr.query, action)
                                    }
                                )
                            } else if (message.type == ChatMessageType.CHART && message.chartSvg != null) {
                                ChartSvgCard(svg = message.chartSvg, onClick = { previewChartSvg = message.chartSvg })
                            } else {
                                ChatMessageItem(
                                    message = message,
                                    onImageClick = { msg ->
                                        val pages = buildImagePreviewPages(messages)
                                        if (pages.isNotEmpty()) {
                                            val isEdit = msg.type == ChatMessageType.AGENT_IMAGE ||
                                                msg.type == ChatMessageType.AGENT_EDIT_RESULT
                                            if (isEdit) viewModel.touchEditImage(msg.imageUri)
                                            imagePreview = ChatImagePreviewState(
                                                pages = pages,
                                                initialIndex = indexOfPage(pages, msg.id)
                                            )
                                        }
                                    },
                                    onDiagConfirm = { jobId, mode -> viewModel.confirmDiagnosis(jobId, mode) },
                                    onDiagSubmit = { ds -> viewModel.submitDiagnosis(ds.description, ds.summary) }
                                )
                            }
                        }
                    }
                }

                // 输入区
                ChatInputArea(
                    isProcessing = isProcessing,
                    onSendMessage = { text ->
                        viewModel.sendMessage(text)
                    },
                    mediaViewModel = mediaViewModel,
                    viewModel = viewModel,
                    onNavigateToPhotoEditor = onNavigateToPhotoEditor,
                )
            }

            // 侧边栏
            ChatThreadSidebar(
                visible = isSidebarOpen,
                threads = threads,
                currentSessionId = currentSessionId,
                searchQuery = searchQuery,
                onSearchQueryChange = { viewModel.setSearchQuery(it) },
                onThreadSelected = { sessionId ->
                    viewModel.switchSession(sessionId)
                    isSidebarOpen = false
                },
                onNewChat = {
                    viewModel.newSession()
                    isSidebarOpen = false
                },
                onRename = { sessionId, newTitle ->
                    viewModel.renameSession(sessionId, newTitle)
                },
                onDelete = { sessionId ->
                    viewModel.deleteSession(sessionId)
                },
                onDismiss = { isSidebarOpen = false }
            )

            // 图片全屏预览（横滑翻页）
            ChatImagePreviewOverlay(
                state = imagePreview,
                onSave = { messageId, onDone ->
                    viewModel.saveEditResult(messageId) { ok ->
                        if (ok) {
                            imagePreview = imagePreview?.let { s ->
                                s.copy(
                                    pages = s.pages.map { p ->
                                        if (p.messageId == messageId) p.copy(isSaved = true) else p
                                    }
                                )
                            }
                        }
                        onDone(ok)
                    }
                },
                onPageChanged = { page ->
                    if (page.isEditableResult) viewModel.touchEditImage(page.rawUri)
                },
                onDismiss = { imagePreview = null }
            )

            // 图表全屏预览
            ChartPreviewOverlay(
                svg = previewChartSvg,
                onDismiss = { previewChartSvg = null }
            )

            // 注册引导弹层（访客试用用尽 / 用户主动注册）
            if (showRegistration) {
                ChatRegistrationSheet(
                    onDismiss = { viewModel.dismissRegistrationSheet() },
                    onUseOwnKey = {
                        onNavigateToSettings()
                        viewModel.dismissRegistrationSheet()
                    },
                    sendCode = viewModel::sendVerificationCode,
                    verifyCode = viewModel::verifyCode,
                )
            }

            // 相册搜索结果全屏预览（覆盖整屏；预览期间隐藏 chat 顶栏，避免顶栏透出）
            if (previewAssets.isNotEmpty()) {
                MediaPager(
                    assets = previewAssets,
                    initialIndex = previewIndex,
                    onClose = { previewAssets = emptyList() },
                    onDelete = { asset ->
                        previewAssets = previewAssets.filter { it.id != asset.id }
                        pendingDeletedIds = pendingDeletedIds + asset.id
                        mediaViewModel.deleteMediaByIds(listOf(asset.id))
                    },
                    onStartOcr = { uriString ->
                        mediaViewModel.recognizeTextFromCurrentImage(context, uriString.toUri())
                    },
                    onDismissOcr = { mediaViewModel.clearOcrResult() },
                    ocrState = mediaViewModel.ocrState,
                    onNavigateToEditor = { asset -> onNavigateToPhotoEditor(asset.uri, false) },
                    onAiOptimize = { asset -> onNavigateToPhotoEditor(asset.uri, true) },
                    onIdPhoto = { asset -> onNavigateToIDPhoto(asset.uri) },
                    voiceCoordinator = null,
                    onReTag = { _ ->
                        // chat 页 photo info 暂用全量扫描(无 container 直拿 worker);相册页走单张 processSingleSync
                        context.startForegroundService(TagGenerationService.intentScanPass3Full(context))
                        null
                    }
                )
            }
        }
    }

    // ── JS 写操作确认（capability.dispatch 触发，删除/收藏/选中）──────────────
    val pendingWriteConfirmation by viewModel.pendingWriteConfirmation.collectAsState()
    pendingWriteConfirmation?.let { req ->
        val operationText = when (req.method) {
            "delete_media" -> stringResource(R.string.chat_write_confirm_delete, req.targetCount)
            "favorite_media" -> stringResource(R.string.chat_write_confirm_favorite, req.targetCount)
            "select_media" -> stringResource(R.string.chat_write_confirm_select, req.targetCount)
            else -> req.method
        }
        AlertDialog(
            onDismissRequest = { viewModel.resolveWriteConfirmation(false) },
            title = {
                Text(text = stringResource(R.string.chat_write_confirm_title))
            },
            text = {
                Column {
                    Text(text = operationText)
                    // 条目缩略图预览：让用户核实 LLM 选出的删除/操作目标
                    if (req.previewUris.isNotEmpty()) {
                        Row(
                            modifier = Modifier.padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            req.previewUris.forEach { uri ->
                                AsyncImage(
                                    model = uri,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                )
                            }
                        }
                    }
                    Text(
                        text = stringResource(R.string.chat_write_confirm_ai_source),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (req.risk == CommandRisk.DESTRUCTIVE) {
                        Text(
                            text = stringResource(R.string.chat_write_confirm_risk_destructive),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.chat_write_confirm_risk_reversible),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.resolveWriteConfirmation(true) }) {
                    Text(text = stringResource(R.string.chat_write_confirm_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.resolveWriteConfirmation(false) }) {
                    Text(text = stringResource(R.string.cancel))
                }
            }
        )
    }

    // ── 聊天/语音/本地 LLM 模型下载提示 ────────────────────────────
    val showChatModelsPrompt by settingsViewModel.showChatModelsPrompt.collectAsState()
    val isChatBatchDownloading by settingsViewModel.isBatchDownloading.collectAsState()
    if (showChatModelsPrompt) {
        AlertDialog(
            onDismissRequest = { if (!isChatBatchDownloading) settingsViewModel.dismissChatModelsPrompt() },
            title = {
                Text(text = stringResource(R.string.chat_models_download_title))
            },
            text = {
                Text(text = stringResource(R.string.chat_models_download_message))
            },
            confirmButton = {
                Button(
                    onClick = { settingsViewModel.startChatModelsDownload() },
                    enabled = !isChatBatchDownloading
                ) {
                    Text(
                        text = if (isChatBatchDownloading) {
                            stringResource(R.string.chat_models_download_progress)
                        } else {
                            stringResource(R.string.chat_models_download_button)
                        }
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { settingsViewModel.dismissChatModelsPrompt() },
                    enabled = !isChatBatchDownloading
                ) {
                    Text(text = stringResource(R.string.chat_models_download_later))
                }
            }
        )
    }
}

@Composable
private fun ChatTopBar(
    onNavigateBack: () -> Unit,
    onOpenSidebar: () -> Unit,
    onNewChat: () -> Unit,
    onClearChat: () -> Unit
) {
    AppTopBar(
        title = {},
        modifier = Modifier.displayCutoutPadding(),
        navigationIcon = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppTopBarNavBack(onClick = onNavigateBack)
                AppTopBarAction(
                    icon = Icons.Rounded.Menu,
                    contentDescription = stringResource(R.string.cd_open_sidebar),
                    onClick = onOpenSidebar
                )
            }
        },
        actions = {
            AppTopBarAction(Icons.Rounded.AddComment, stringResource(R.string.new_chat), onNewChat)
            AppTopBarAction(Icons.Rounded.DeleteSweep, stringResource(R.string.clear_chat), onClearChat)
        }
    )
}

/** LRU 已清理的编辑结果图占位：灰框 + 图标 + 「图片已过期·不可见」。 */
@Composable
private fun ExpiredImagePlaceholder(height: androidx.compose.ui.unit.Dp = 180.dp) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Rounded.ImageNotSupported,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(36.dp)
            )
            Text(
                text = stringResource(R.string.chat_edit_image_expired),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

/** 流式渲染段类型：Markdown 段正常渲染，表格段按纯文本直出（防抖动）。 */
private enum class StreamSegmentType { MARKDOWN, TABLE }

private data class StreamSegment(val type: StreamSegmentType, val text: String)

/** GFM 表格分隔行，如 `|---|---|`、`| --- | ---: |`、`---|---`（可无前后导 `|`）。 */
private val TABLE_DELIMITER = Regex("""^\s*\|?(\s*:?-{2,}:?\s*\|)+(\s*:?-{2,}:?\s*)\|?\s*$""")

private val CODE_FENCE = Regex("""^\s*```""")

/**
 * 流式 Markdown 分段：把内容按「Markdown 段 / 表格段」切开。
 *
 * 表格段识别：分隔行（[TABLE_DELIMITER]）的前一行含 `|` 即视为表头，向后吞并
 * 所有含 `|` 的非空行； fenced code block 内的 `|` 行不算。一条回复可有多个表格，
 * 全部识别。流式期间表格段一律按纯文本渲染——Markwon 的表格位图逐 token 重建
 * 是抖动根源；流结束消息落库后走完整 Markdown，表格一次性定型。
 */
private fun segmentStreamingMarkdown(content: String): List<StreamSegment> {
    val lines = content.split("\n")
    val isTableLine = BooleanArray(lines.size)
    var inCodeFence = false
    for (i in lines.indices) {
        if (CODE_FENCE.containsMatchIn(lines[i])) inCodeFence = !inCodeFence
        if (!inCodeFence && i > 0 && TABLE_DELIMITER.matches(lines[i]) && lines[i - 1].contains("|")) {
            isTableLine[i - 1] = true
            isTableLine[i] = true
            var j = i + 1
            while (j < lines.size && lines[j].isNotBlank() && lines[j].contains("|")) {
                isTableLine[j] = true
                j++
            }
        }
    }
    val segments = mutableListOf<StreamSegment>()
    var start = 0
    for (i in 1..lines.size) {
        if (i == lines.size || isTableLine[i] != isTableLine[start]) {
            val type = if (isTableLine[start]) StreamSegmentType.TABLE else StreamSegmentType.MARKDOWN
            segments += StreamSegment(type, lines.subList(start, i).joinToString("\n"))
            start = i
        }
    }
    return segments
}

@Suppress("LongMethod", "CyclomaticComplexMethod") // 待重构：消息项多类型分支，抽分发器
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatMessageItem(
    message: ChatMessageUi,
    onImageClick: (ChatMessageUi) -> Unit = {},
    onDiagConfirm: (Int, String) -> Unit = { _, _ -> },
    onDiagSubmit: (DiagSubmitUi) -> Unit = {},
) {
    val isUser = message.type == ChatMessageType.USER_TEXT ||
        message.type == ChatMessageType.USER_IMAGE ||
        message.type == ChatMessageType.USER_IMAGE_TEXT
    val isImage = message.type == ChatMessageType.AGENT_IMAGE || message.type == ChatMessageType.USER_IMAGE
    val isImageText = message.type == ChatMessageType.USER_IMAGE_TEXT
    val isEditResult = message.type == ChatMessageType.AGENT_EDIT_RESULT
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val copySuccess = stringResource(R.string.copy_success)

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = if (isImage || isImageText) 240.dp else 360.dp)
                .clip(
                    if (isUser) {
                        RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp)
                    } else {
                        RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp)
                    }
                )
                .background(
                    if (isImage) {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    } else if (isUser) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)
                    }
                )
                .padding(
                    horizontal = if (isImage || isImageText) 6.dp else 16.dp,
                    vertical = if (isImage || isImageText) 6.dp else 12.dp
                )
                .pointerInput(Unit) {
                    detectTapGestures(
                        onLongPress = {
                            clipboardManager.setText(AnnotatedString(message.content))
                            Toast.makeText(context, copySuccess, Toast.LENGTH_SHORT).show()
                        }
                    )
                }
        ) {
            when {
                isImageText -> {
                    // 用户「图 + 文字意图」：图在上、文字在下
                    AsyncImage(
                        model = message.imageUri,
                        contentDescription = stringResource(R.string.photo),
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onImageClick(message) }
                    )
                    Text(
                        text = message.content,
                        color = Color.White,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                isImage -> {
                    // 显示图片（可点击进入全屏预览）；agent 生成的结果图过期则显示占位
                    val imgSrc = message.imageUri ?: message.content
                    if (message.type == ChatMessageType.AGENT_IMAGE && !chatImageIsLive(imgSrc)) {
                        ExpiredImagePlaceholder()
                    } else {
                        AsyncImage(
                            model = imgSrc,
                            contentDescription = stringResource(R.string.photo),
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onImageClick(message) }
                        )
                    }
                }
                isEditResult -> {
                    // 对话式编辑结果：图片 + 说明文字；结果图过期则显示占位
                    val imageUri = message.imageUri.orEmpty()
                    if (imageUri.isNotBlank()) {
                        if (!chatImageIsLive(imageUri)) {
                            ExpiredImagePlaceholder(height = 200.dp)
                        } else {
                            AsyncImage(
                                model = imageUri,
                                contentDescription = stringResource(R.string.photo),
                                contentScale = ContentScale.FillHeight,
                                modifier = Modifier
                                    .height(200.dp)
                                    .widthIn(max = 260.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onImageClick(message) }
                            )
                        }
                    }
                    if (message.content.isNotBlank()) {
                        Text(
                            text = message.content,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
                isUser -> {
                    Text(
                        text = message.content,
                        color = Color.White,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )
                }
                else -> {
                    if (message.isStreaming) {
                        if (message.isThinking) {
                            TypingIndicator()
                        } else {
                            // 流式防抖动：表格段（可多个）一律纯文本直出，流式期间零表格位图；
                            // Markdown 段照常渲染。消息落库后走下方完整 Markdown，表格一次性定型。
                            Row(verticalAlignment = Alignment.Bottom) {
                                Column(modifier = Modifier.weight(1f)) {
                                    segmentStreamingMarkdown(message.content).forEach { segment ->
                                        when (segment.type) {
                                            StreamSegmentType.TABLE -> Text(
                                                text = segment.text,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                fontSize = 13.sp,
                                                lineHeight = 18.sp,
                                                fontFamily = FontFamily.Monospace
                                            )
                                            StreamSegmentType.MARKDOWN -> MarkdownText(
                                                markdown = segment.text,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                fontSize = 14.sp,
                                                lineHeight = 20.sp
                                            )
                                        }
                                    }
                                }
                                if (message.showCursor) {
                                    BlinkCursor()
                                }
                            }
                        }
                    } else {
                        MarkdownText(
                            markdown = message.content,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 14.sp,
                            lineHeight = 20.sp
                        )
                    }
                }
            }
            // 诊断根因：内嵌确认按钮（pending 时显示 [推送]/[PR]）
            message.diagConfirm?.let { dc ->
                if (dc.pending) {
                    Spacer(Modifier.height(10.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(onClick = { onDiagConfirm(dc.jobId, "push") }) {
                            Text(stringResource(R.string.diag_sheet_push))
                        }
                        Button(onClick = { onDiagConfirm(dc.jobId, "pr") }) {
                            Text(stringResource(R.string.diag_sheet_pr))
                        }
                        Button(onClick = { onDiagConfirm(dc.jobId, "auto") }) {
                            Text(stringResource(R.string.diag_sheet_auto))
                        }
                    }
                }
            }
            // 诊断澄清对话：[DIAG_READY] 摘要气泡内嵌「提交诊断」按钮（§2：提交永远是用户手动动作）
            message.diagSubmit?.let { ds ->
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Button(onClick = { onDiagSubmit(ds) }) {
                        Text(stringResource(R.string.diag_submit_report))
                    }
                }
            }
            message.performance?.let { perf ->
                val metricTint = if (isUser) {
                    Color.White.copy(alpha = 0.55f)
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                }
                FlowRow(
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    PerformanceMetric(
                        icon = Icons.AutoMirrored.Rounded.ShortText,
                        value = "${perf.promptLen}",
                        tint = metricTint
                    )
                    PerformanceMetric(
                        icon = Icons.Rounded.ChatBubble,
                        value = "${perf.decodeLen}",
                        tint = metricTint
                    )
                    if (perf.prefillTimeMs > 0) {
                        PerformanceMetric(
                            icon = Icons.Rounded.Bolt,
                            value = "${perf.prefillTimeMs}ms",
                            tint = metricTint
                        )
                    }
                    PerformanceMetric(
                        icon = Icons.Rounded.Timer,
                        value = "${perf.decodeTimeMs}ms",
                        tint = metricTint
                    )
                    PerformanceMetric(
                        icon = Icons.Rounded.Speed,
                        value = "${String.format(Locale.ROOT, "%.1f", perf.decodeSpeed)}",
                        tint = metricTint
                    )
                    if (perf.usedSandbox) {
                        Box(
                            modifier = Modifier
                                .padding(start = 6.dp)
                                .size(14.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .border(1.5.dp, metricTint)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 单个性能指标：图标 + 数值，紧凑展示，避免一行文字过长。
 */
@Composable
private fun PerformanceMetric(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(10.dp)
        )
        Text(
            text = value,
            color = tint,
            fontSize = 9.sp
        )
    }
}

/**
 * 输入模式枚举
 */
private enum class ChatInputMode {
    TEXT,
    VOICE
}

@Suppress("LongMethod", "LongParameterList", "CyclomaticComplexMethod") // 待重构：输入区抽 state holder 降复杂度
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatInputArea(
    isProcessing: Boolean,
    onSendMessage: (String) -> Unit,
    mediaViewModel: MediaViewModel,
    viewModel: ChatViewModel,
    onNavigateToPhotoEditor: (String, Boolean) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var showModelMenu by remember { mutableStateOf(false) }
    var showPhotoPicker by remember { mutableStateOf(false) }
    var pendingImage by remember { mutableStateOf<Uri?>(null) }
    var selectedIntent by remember { mutableStateOf<ImageIntent?>(null) }
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState()
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val settingsRepository = remember { UserPreferencesRepository(context) }
    val hasUserKey by viewModel.hasUserKey.collectAsState()
    val availableModels by viewModel.availableModels.collectAsState()
    val selectedModelId by viewModel.selectedModelId.collectAsState()
    val selectedModel = availableModels.find { m -> m.id == selectedModelId } ?: availableModels.firstOrNull()
    // 独立 chat 页默认文本输入模式（不继承/回写公共 AI chat 的语音偏好）
    var inputMode by remember { mutableStateOf(ChatInputMode.TEXT) }
    // 诊断澄清对话模式（§2）：状态在 ViewModel（进入时自动新建独立会话）
    val diagMode by viewModel.diagMode.collectAsState()

    // 语音输入：按需加载本地 Sherpa-ONNX ASR 模型，未配置时回退到系统 ASR
    val localAsrModel by settingsRepository.localAsrModelFlow.collectAsState(initial = "")
    var asrEngine by remember(context) {
        mutableStateOf<AsrEngine>(SystemAsrEngine(context))
    }
    LaunchedEffect(context, localAsrModel) {
        val engine = withContext(Dispatchers.IO) {
            if (localAsrModel.isNotBlank()) {
                val modelDir = context.filesDir.resolve("llm_models/$localAsrModel")
                val isModelReady = modelDir.exists() && modelDir.isDirectory &&
                    modelDir.walkTopDown().any { it.name.endsWith(".onnx") } &&
                    File(modelDir, "tokens.txt").exists()

                if (isModelReady) {
                    val sherpa = SherpaOnnxAsrEngine(context, modelDir.absolutePath)
                    if (sherpa.isAvailable()) {
                        Logger.i(TAG, "Chat ASR using local model: $localAsrModel")
                        sherpa
                    } else {
                        Logger.w(TAG, "Local ASR init failed, falling back to system ASR")
                        SystemAsrEngine(context)
                    }
                } else {
                    Logger.w(TAG, "Local ASR model not ready: $localAsrModel")
                    SystemAsrEngine(context)
                }
            } else {
                Logger.d(TAG, "No local ASR model configured, using system ASR")
                SystemAsrEngine(context)
            }
        }
        val previousEngine = asrEngine
        asrEngine = engine
        if (previousEngine !== engine) {
            previousEngine.release()
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            asrEngine.release()
        }
    }

    // DeepSeek 风格：白色大圆角卡片统一包裹输入区域（带阴影增强视觉层次）
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = 4.dp,
                    shape = RoundedCornerShape(24.dp),
                    ambientColor = Color.Black.copy(alpha = 0.08f),
                    spotColor = Color.Black.copy(alpha = 0.12f)
                )
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            when (inputMode) {
                ChatInputMode.TEXT -> ChatTextInputMode(
                    text = text,
                    onTextChange = { text = it },
                    isProcessing = isProcessing,
                    diagMode = diagMode,
                    onToggleDiag = { if (diagMode) viewModel.exitDiagMode() else viewModel.enterDiagMode() },
                    onSend = {
                        if (!isProcessing) {
                            val img = pendingImage
                            when {
                                img != null && selectedIntent == ImageIntent.EDIT -> {
                                    onNavigateToPhotoEditor(img.toString(), false)
                                    pendingImage = null
                                    selectedIntent = null
                                }
                                img != null -> {
                                    viewModel.sendImageWithIntent(
                                        uri = img.toString(),
                                        intent = selectedIntent ?: ImageIntent.UNDERSTAND,
                                        text = text.trim().takeIf { it.isNotBlank() }
                                    )
                                    pendingImage = null
                                    selectedIntent = null
                                    text = ""
                                    keyboardController?.hide()
                                }
                                text.isNotBlank() -> {
                                    if (diagMode) viewModel.sendDiagMessage(text.trim()) else onSendMessage(text.trim())
                                    text = ""
                                    keyboardController?.hide()
                                }
                            }
                        }
                    },
                    onModelMenuToggle = { showModelMenu = !showModelMenu },
                    onShowModelMenu = { showModelMenu = true },
                    onDismissModelMenu = { showModelMenu = false },
                    showModelMenu = showModelMenu,
                    hasUserKey = hasUserKey,
                    availableModels = availableModels,
                    selectedModel = selectedModel,
                    onSwitchModel = viewModel::switchModel,
                    onSwitchToVoice = {
                        inputMode = ChatInputMode.VOICE
                        keyboardController?.hide()
                    },
                    onShowPhotoPicker = { showPhotoPicker = true },
                    pendingImage = pendingImage,
                    selectedIntent = selectedIntent,
                    onSelectIntent = { selectedIntent = it },
                    onRemovePendingImage = {
                        pendingImage = null
                        selectedIntent = null
                    }
                )

                ChatInputMode.VOICE -> ChatVoiceInputMode(
                    onSwitchToText = {
                        inputMode = ChatInputMode.TEXT
                        keyboardController?.show()
                    },
                    onVoiceResult = { result ->
                        if (result.isNotBlank() && !isProcessing) {
                            onSendMessage(result.trim())
                        }
                    },
                    asrEngine = asrEngine,
                    scope = scope
                )
            }
        }
    }

    // 内置相册选取底部弹窗（可搜索 + 复用 MediaGrid）
    if (showPhotoPicker) {
        ChatPhotoPickerSheet(
            sheetState = sheetState,
            mediaViewModel = mediaViewModel,
            onImageSelected = { uri ->
                viewModel.stageImage(uri)?.let { persisted ->
                    pendingImage = Uri.parse(persisted)
                    selectedIntent = null
                }
                showPhotoPicker = false
            },
            onDismiss = { showPhotoPicker = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImageIntentChip(
    @StringRes labelRes: Int,
    intent: ImageIntent,
    selected: ImageIntent?,
    onSelect: (ImageIntent) -> Unit
) {
    FilterChip(
        selected = selected == intent,
        onClick = { onSelect(intent) },
        label = { Text(stringResource(labelRes), fontSize = 12.sp) }
    )
}

@Suppress("LongMethod", "LongParameterList") // 待重构：文本输入模式，抽 state holder
@Composable
private fun ChatTextInputMode(
    text: String,
    onTextChange: (String) -> Unit,
    isProcessing: Boolean,
    onSend: () -> Unit,
    onModelMenuToggle: () -> Unit,
    onShowModelMenu: () -> Unit,
    onDismissModelMenu: () -> Unit,
    showModelMenu: Boolean,
    hasUserKey: Boolean,
    availableModels: List<ChatViewModel.ChatRemoteModel>,
    selectedModel: ChatViewModel.ChatRemoteModel?,
    onSwitchModel: (String) -> Unit,
    onSwitchToVoice: () -> Unit,
    onShowPhotoPicker: () -> Unit,
    diagMode: Boolean = false,
    onToggleDiag: () -> Unit = {},
    pendingImage: Uri? = null,
    selectedIntent: ImageIntent? = null,
    onSelectIntent: (ImageIntent) -> Unit = {},
    onRemovePendingImage: () -> Unit = {}
) {
    val hasContent = text.isNotBlank() || pendingImage != null

    // 输入框内容区域（外层已由 ChatInputArea 统一包裹白色卡片）
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (pendingImage != null) {
            Box(modifier = Modifier.fillMaxWidth()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(pendingImage).size(256).crossfade(true).build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(72.dp).clip(RoundedCornerShape(8.dp))
                )
                IconButton(
                    onClick = onRemovePendingImage,
                    modifier = Modifier.align(Alignment.TopEnd).size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.cd_remove_pending_image),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ImageIntentChip(R.string.chat_intent_understand, ImageIntent.UNDERSTAND, selectedIntent, onSelectIntent)
                ImageIntentChip(R.string.chat_intent_find_similar, ImageIntent.FIND_SIMILAR, selectedIntent, onSelectIntent)
                ImageIntentChip(R.string.chat_intent_edit, ImageIntent.EDIT, selectedIntent, onSelectIntent)
            }
        }
        // 第一行：输入框（无独立边框，融入卡片）
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.CenterStart
        ) {
            val inputHint = stringResource(R.string.chat_input_hint)
            if (text.isEmpty()) {
                Text(
                    text = inputHint,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                )
            }
            // 使用 BasicTextField 实现无边框输入
            androidx.compose.foundation.text.BasicTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = inputHint },
                maxLines = 5,
                textStyle = TextStyle(
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (hasContent && !isProcessing) onSend() })
            )
        }

        // 第二行：胶囊形功能按钮 + 圆形图标按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // 左侧：胶囊形按钮组
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 模型切换胶囊按钮：仅当用户配了自配 Key 时显示（可在「默认服务器/自配 Key」切换）；
                // 未配 Key 时 chat 只用默认远程，不显示模型标签（避免无意义的固定「远程」文字）。
                if (hasUserKey) {
                    Box {
                        ModelCapsuleButton(
                            selectedModel = selectedModel,
                            onClick = onShowModelMenu
                        )
                        DropdownMenu(
                            expanded = showModelMenu,
                            onDismissRequest = onDismissModelMenu,
                            modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                        ) {
                            availableModels.forEach { model ->
                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(8.dp)
                                                    .clip(CircleShape)
                                                    .background(modelDotColor(model))
                                            )
                                            Text(model.displayName)
                                        }
                                    },
                                    onClick = {
                                        onSwitchModel(model.id)
                                        onDismissModelMenu()
                                    }
                                )
                            }
                        }
                    }
                }

                // 图片选择胶囊按钮
                CapsuleButton(
                    icon = Icons.Rounded.PhotoLibrary,
                    label = "相册",
                    onClick = onShowPhotoPicker,
                    enabled = !isProcessing
                )

                // 远程诊断 toggle（二态）：激活后发送键触发诊断（chat 描述问题 → 云主机定位/修复）
                CapsuleButton(
                    icon = Icons.Rounded.Code,
                    label = stringResource(R.string.diag_icon_desc),
                    onClick = onToggleDiag,
                    enabled = !isProcessing,
                    isActive = diagMode
                )
            }

            // 右侧：圆形图标按钮（语音 + 发送）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 语音切换按钮
                CircularIconButton(
                    icon = Icons.Rounded.KeyboardVoice,
                    contentDescription = stringResource(R.string.cd_switch_to_voice),
                    onClick = onSwitchToVoice
                )

                // 发送按钮（有内容时高亮）
                if (hasContent && !isProcessing) {
                    CircularIconButton(
                        icon = Icons.AutoMirrored.Rounded.Send,
                        contentDescription = stringResource(R.string.chat_send),
                        onClick = onSend,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

/**
 * 胶囊形按钮 — DeepSeek 风格（圆角长条，带图标+文字）
 */
@Composable
private fun CapsuleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    isActive: Boolean = false
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (isActive) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                }
            )
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = label,
            fontSize = 12.sp,
            color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

/**
 * 模型切换胶囊按钮
 */
@Composable
private fun ModelCapsuleButton(
    selectedModel: ChatViewModel.ChatRemoteModel?,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(modelDotColor(selectedModel))
        )
        Text(
            text = selectedModel?.displayName ?: "官方LLM",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        Icon(
            imageVector = Icons.Rounded.KeyboardVoice,
            contentDescription = "切换",
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            modifier = Modifier.size(12.dp)
        )
    }
}

/** 模型圆点颜色（官方=蓝、自配=橙）。 */
private val OFFICIAL_MODEL_COLOR = Color(0xFF2196F3)
private val FALLBACK_MODEL_COLOR = Color(0xFFFF9800)

private fun modelDotColor(model: ChatViewModel.ChatRemoteModel?): Color =
    if (model?.id == "official") OFFICIAL_MODEL_COLOR else FALLBACK_MODEL_COLOR

/**
 * 圆形图标按钮
 */
@Composable
private fun CircularIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun ChatVoiceInputMode(
    onSwitchToText: () -> Unit,
    onVoiceResult: (String) -> Unit,
    asrEngine: AsrEngine,
    scope: CoroutineScope
) {
    val context = LocalContext.current
    var isListening by remember { mutableStateOf(false) }
    var isCancelRecord by remember { mutableStateOf(false) }
    var discardResult by remember { mutableStateOf(false) }

    val pushToTalkEngine = remember(asrEngine) {
        PushToTalkEngine(asrEngine, scope, context)
    }

    val voiceUnavailableText = stringResource(R.string.voice_unavailable)
    val permissionDeniedText = stringResource(R.string.record_audio_permission_denied)

    val startRecording = {
        if (!asrEngine.isAvailable()) {
            Toast.makeText(context, voiceUnavailableText, Toast.LENGTH_SHORT).show()
        } else {
            discardResult = false
            isListening = true
            isCancelRecord = false
            Logger.d(TAG, "Chat push-to-talk started")
            pushToTalkEngine.start { result ->
                isListening = false
                isCancelRecord = false
                Logger.d(TAG, "Chat ASR result: '$result', discard=$discardResult")
                if (result.isNotBlank() && !discardResult) {
                    onVoiceResult(result)
                }
                discardResult = false
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(context, permissionDeniedText, Toast.LENGTH_SHORT).show()
        }
    }

    // 页面退出或切换到键盘时强制停止录音
    DisposableEffect(Unit) {
        onDispose {
            pushToTalkEngine.stop()
        }
    }

    // 语音输入内容区域（外层已由 ChatInputArea 统一包裹白色卡片）
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        // 单行布局：左侧键盘切换 + 中间按住说话 + 右侧可扩展
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 左侧：键盘切换按钮
            CircularIconButton(
                icon = Icons.Rounded.Keyboard,
                contentDescription = stringResource(R.string.switch_to_keyboard),
                onClick = onSwitchToText
            )

            // 中间：按住说话按钮（占据剩余空间）
            val buttonBackground = when {
                isListening && !isCancelRecord -> MaterialTheme.colorScheme.primary
                isListening && isCancelRecord -> Color(0xFFE53935)
                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            }
            val buttonTextColor = if (isListening) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(buttonBackground)
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                event.changes.forEach { change ->
                                    when {
                                        // 手指按下：检查麦克风权限并开始录音
                                        change.pressed && !change.isConsumed && !isListening -> {
                                            val hasPermission = ContextCompat.checkSelfPermission(
                                                context,
                                                Manifest.permission.RECORD_AUDIO
                                            ) == PackageManager.PERMISSION_GRANTED

                                            if (hasPermission) {
                                                startRecording()
                                            } else {
                                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                            }
                                        }
                                        // 手指抬起：停止录音
                                        !change.pressed -> {
                                            if (isListening) {
                                                if (isCancelRecord) {
                                                    discardResult = true
                                                }
                                                pushToTalkEngine.stop()
                                                isListening = false
                                                isCancelRecord = false
                                            }
                                        }
                                    }

                                    // 手指移动：移出按钮区域视为取消
                                    if (isListening && change.pressed) {
                                        val bounds = this@pointerInput.size
                                        val x = change.position.x
                                        val y = change.position.y
                                        val newCancel = x < 0 || x > bounds.width || y < 0 || y > bounds.height
                                        if (newCancel != isCancelRecord) {
                                            isCancelRecord = newCancel
                                        }
                                    }
                                    change.consume()
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.KeyboardVoice,
                        contentDescription = stringResource(R.string.cd_switch_to_voice),
                        tint = buttonTextColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = when {
                            isListening && !isCancelRecord -> stringResource(R.string.release_to_stop)
                            isListening && isCancelRecord -> stringResource(R.string.release_to_cancel)
                            else -> stringResource(R.string.hold_to_speak)
                        },
                        color = buttonTextColor,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // 右侧：占位保持对称
            Box(modifier = Modifier.size(36.dp))
        }
    }
}

/**
 * 本地 LLM 性能指标（展示用）
 */
data class LlmPerformance(
    val promptLen: Long,
    val decodeLen: Long,
    val prefillTimeMs: Long,
    val decodeTimeMs: Long,
    val prefillSpeed: Float,
    val decodeSpeed: Float,
    val usedSandbox: Boolean = false
)

/** 全屏预览态：携带保存所需的 messageId / 类型 / 已保存标记。 */
/**
 * 聊天图片横滑预览状态：打开瞬间快照的翻页集合 + 初始页下标。
 */
data class ChatImagePreviewState(
    val pages: List<ImagePreviewPage>,
    val initialIndex: Int
)

/**
 * 图片预览横滑翻页集合中的一个页面。持有原始 uri 字符串（Uri 解析推迟到渲染时进行，
 * 使本模型为纯 Kotlin、可单测；touchEditImage 也接收原始字符串）。
 */
data class ImagePreviewPage(
    val messageId: String,
    val rawUri: String,
    val isEditableResult: Boolean, // AGENT_IMAGE / AGENT_EDIT_RESULT
    val isSaved: Boolean
)

/**
 * 从会话消息快照构建图片预览翻页集合：保留所有 [ChatMessageUi.imageUri] 非空的消息，
 * 保持原顺序。纯函数，便于单测（Uri 解析在渲染层 [resolvePreviewUri] 完成）。
 */
fun buildImagePreviewPages(messages: List<ChatMessageUi>): List<ImagePreviewPage> =
    messages.mapNotNull { msg ->
        val raw = msg.imageUri ?: return@mapNotNull null
        ImagePreviewPage(
            messageId = msg.id,
            rawUri = raw,
            isEditableResult = msg.type == ChatMessageType.AGENT_IMAGE ||
                msg.type == ChatMessageType.AGENT_EDIT_RESULT,
            isSaved = msg.imageSaved
        )
    }

/** 把 raw uri 字符串解析为最终 Uri（含 scheme 直接用，否则按 file:// 兜底）。 */
fun resolvePreviewUri(rawUri: String): Uri {
    val parsed = Uri.parse(rawUri)
    return if (parsed?.scheme != null) parsed else File(rawUri).toUri()
}

/** 返回 [messageId] 在 pages 中的下标；找不到返回 0（兜底定位到首页）。 */
fun indexOfPage(pages: List<ImagePreviewPage>, messageId: String): Int {
    val i = pages.indexOfFirst { it.messageId == messageId }
    return if (i >= 0) i else 0
}

/**
 * 聊天消息 UI 数据类
 */
data class ChatMessageUi(
    val id: String,
    val type: ChatMessageType,
    val content: String,
    val modelUsed: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val performance: LlmPerformance? = null,
    val mediaResults: MediaResultsUi? = null,
    /** 图文混排（USER_IMAGE_TEXT）时携带的图片 uri；其余类型为 null。 */
    val imageUri: String? = null,
    /** CHART 类型：端侧 JS 生成的 SVG 字符串，由 AndroidSVG 渲染成图。 */
    val chartSvg: String? = null,
    /** agent_image / agent_edit_result 是否已保存到相册（来自 metadata.saved）。 */
    val imageSaved: Boolean = false,
    /** 流式输出中的瞬态消息（不落 Room）；UI 据此对未闭合表格做防抖动处理。 */
    val isStreaming: Boolean = false,
    /** 流式打字光标是否可见（由节奏器驱动：吐字中 true，停顿超时/完成 false）。 */
    val showCursor: Boolean = false,
    /** 思考中（首 token 到达前）：UI 显示三点 typing indicator 而非内容+光标。 */
    val isThinking: Boolean = false,
    /** 诊断根因气泡的内嵌确认动作；非空且 pending=true 时渲染 [推送]/[PR] 按钮。 */
    val diagConfirm: DiagConfirmUi? = null,
    /** 诊断澄清对话 [DIAG_READY] 气泡的内嵌提交动作；非空时渲染「提交诊断」按钮。 */
    val diagSubmit: DiagSubmitUi? = null,
    /** claude-tunnel agent 气泡状态（文本流式 + 步骤列表 + 文件改动）；复用 diag 内嵌字段套路。 */
    val claudeAgent: ClaudeAgentState? = null,
    /** claude agent 气泡的交付动作；非空且 pending=true 时渲染「交付」按钮（§8）。 */
    val claudeDeliver: ClaudeDeliverUi? = null,
)

/** 诊断确认内嵌按钮状态。pending=true 显示按钮；false 则已处理（按钮消失）。 */
data class DiagConfirmUi(val jobId: Int, val pending: Boolean)

/** 诊断对话「提交诊断」按钮状态。description=诊断会话首条用户消息，summary=LLM 结构化摘要（可空，退化为现状）。 */
data class DiagSubmitUi(val description: String, val summary: String?)

enum class ChatMessageType {
    USER_TEXT,
    AGENT_TEXT,
    USER_IMAGE,
    USER_IMAGE_TEXT,
    AGENT_IMAGE,
    AGENT_EDIT_RESULT,
    COMMAND,
    PLAN_PREVIEW,
    MEDIA_RESULTS,
    CHART
}

/**
 * 相册搜索结果 carousel 的 UI 数据。
 * assets 已截到展示上限（20）；totalCount 为全量命中数。
 */
data class MediaResultsUi(
    val query: String,
    val assets: List<MediaAsset>,
    val totalCount: Int,
    val isRefinement: Boolean,
    val feedbackState: Map<String, FeedbackAction> = emptyMap()
)

/**
 * 模型选项
 */
sealed class ChatModelOption(val label: String, val indicatorColor: Color) {
    data object Local : ChatModelOption("本地", Color(0xFF4CAF50))
    data object Remote : ChatModelOption("远程", Color(0xFF2196F3))
}

/**
 * 图表全屏预览（in-content 整屏覆盖层；顶栏由调用方在打开时隐藏，整屏留给图）。
 * - 图按 contain-fit 等比缩放，整图可见、清晰。
 * - 双指缩放（1x~5x）+ 单指拖动平移；缩放回到 ~1x 自动回正。
 * - 点空白 / 返回键 / 左上角关闭键 均可关闭。
 * - 宽图强制横屏，关闭时恢复系统默认方向（MainActivity 已声明 configChanges，旋转不重建）。
 */
@Composable
private fun ChartPreviewOverlay(
    svg: String?,
    onDismiss: () -> Unit
) {
    if (svg == null) return
    val context = LocalContext.current
    val landscape = chartIsLandscape(svg)
    DisposableEffect(Unit) {
        context.findActivity()?.requestedOrientation =
            if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        onDispose {
            context.findActivity()?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    var scale by remember(svg) { mutableStateOf(1f) }
    var offset by remember(svg) { mutableStateOf(Offset.Zero) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        ChartSvgImage(
            svg = svg,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(svg) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val nextScale = (scale * zoom).coerceIn(1f, 5f)
                        scale = nextScale
                        // 缩放回到 ~1x 时复位偏移，避免图被拖飞
                        offset = if (nextScale <= 1.01f) Offset.Zero else offset + pan
                    }
                }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
        )
        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .offset(x = (-80).dp)
                .padding(8.dp)
                .size(48.dp)
                .background(Color.Black.copy(alpha = 0.4f), CircleShape)
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "关闭",
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

/** 解析 SVG 的 width/height，判断是否为宽图（width > height）。 */
private fun chartIsLandscape(svg: String?): Boolean {
    if (svg == null) return false
    val w = Regex("""width="(\d+)"""").find(svg)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    val h = Regex("""height="(\d+)"""").find(svg)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    return w > 0 && h > 0 && w > h
}

/** 从 Context 链中取出 Activity（LocalContext 可能是 ContextWrapper）。 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * 图片全屏预览浮层：对编辑/优化结果额外提供「保存到相册」按钮。
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ChatImagePreviewOverlay(
    state: ChatImagePreviewState?,
    onSave: (messageId: String, onDone: (Boolean) -> Unit) -> Unit,
    onPageChanged: (ImagePreviewPage) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val expiredToast = stringResource(R.string.chat_edit_save_expired_failed)
    AnimatedVisibility(
        visible = state != null,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        if (state == null) return@AnimatedVisibility
        val pages = state.pages
        val pagerState = rememberPagerState(
            initialPage = state.initialIndex.coerceIn(0, pages.lastIndex.coerceAtLeast(0)),
            pageCount = { pages.size }
        )

        // 切页时：若是编辑/生成图则续期（LRU 回收）
        LaunchedEffect(pagerState.currentPage, pages.size) {
            pages.getOrNull(pagerState.currentPage)?.let(onPageChanged)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.Center
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { pageIndex ->
                val page = pages[pageIndex]
                var scale by remember(page.messageId) { mutableStateOf(1f) }
                var offset by remember(page.messageId) { mutableStateOf(Offset.Zero) }
                AsyncImage(
                    model = resolvePreviewUri(page.rawUri),
                    contentDescription = stringResource(R.string.cd_image_preview),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .pointerInput(page.messageId) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val nextScale = (scale * zoom).coerceIn(1f, 5f)
                                scale = nextScale
                                offset = if (nextScale <= 1.01f) Offset.Zero else offset + pan
                            }
                        }
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                    contentScale = ContentScale.Fit
                )
            }

            // 关闭按钮（右上）
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(16.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f))
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.close),
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }

            // 页码指示器（关闭键左侧）
            Text(
                text = "${pagerState.currentPage + 1} / ${pages.size}",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(end = 72.dp, top = 22.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.4f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )

            // 保存按钮（仅当前页为编辑/优化结果，底部居中）
            val currentPage = pages.getOrNull(pagerState.currentPage)
            if (currentPage?.isEditableResult == true) {
                val isSaved = currentPage.isSaved
                Button(
                    onClick = {
                        if (!isSaved) {
                            onSave(currentPage.messageId) { ok ->
                                if (!ok) Toast.makeText(context, expiredToast, Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    enabled = !isSaved,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(24.dp)
                ) {
                    Text(
                        text = stringResource(
                            if (isSaved) R.string.chat_edit_saved_to_gallery else R.string.chat_edit_save_to_gallery
                        )
                    )
                }
            }
        }
    }
}
