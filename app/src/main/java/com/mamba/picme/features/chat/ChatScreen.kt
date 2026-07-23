package com.mamba.picme.features.chat

import android.app.Activity
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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.ShortText
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.KeyboardVoice
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Settings
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.activity.compose.BackHandler
import androidx.core.net.toUri
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
import com.mamba.picme.domain.agent.RegisterCapability
import com.mamba.picme.agent.core.model.command.FeedbackAction
import com.mamba.picme.features.chat.capability.ChatGallerySummaryCapability
import com.mamba.picme.features.chat.capability.ChatRunScriptCapability
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
 * - 顶部栏：返回 + 设置 + 清空
 * - 消息列表：LazyColumn 展示对话历史
 * - 输入区：ModelSelector + 输入框 + 发送按钮
 * - 快捷入口：相机 / 设置 / 模型中心
 */
@Suppress("LongMethod") // Top-level Compose screen: scaffold + list + input + sidebar
@Composable
fun ChatScreen(
    viewModel: ChatViewModel = viewModel(),
    settingsViewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToGallery: (String) -> Unit = {},
    mediaViewModel: MediaViewModel,
    onNavigateToPhotoEditor: (uri: String, autoOptimize: Boolean) -> Unit = { _, _ -> },
    onNavigateToIDPhoto: (uri: String) -> Unit = {}
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
    // 图片预览状态
    var previewImageUri by remember { mutableStateOf<Uri?>(null) }
    // 相册搜索结果预览状态
    var previewAssets by remember { mutableStateOf<List<MediaAsset>>(emptyList()) }
    var previewIndex by remember { mutableIntStateOf(0) }
    // 已点删除但等待媒体库刷新确认的图片 ID
    var pendingDeletedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }

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

    // 当媒体库刷新后发现 preview 中的某张图已被物理删除，同步清理 preview 列表和 chat 消息
    LaunchedEffect(allMedia) {
        if (allMedia.isEmpty()) return@LaunchedEffect
        val existingIds = allMedia.map { it.id }.toSet()

        // 确认 pending 删除已实际生效（物理文件 + Room 记录已清理）
        if (pendingDeletedIds.isNotEmpty()) {
            val confirmedRemoved = pendingDeletedIds.filter { it > 0L && it !in existingIds }.toSet()
            if (confirmedRemoved.isNotEmpty()) {
                confirmedRemoved.forEach { viewModel.removeMediaResultAsset(it) }
                pendingDeletedIds = pendingDeletedIds - confirmedRemoved
            }
        }

        // 兜底：其他途径导致 previewAssets 中的图片已不在媒体库时，也清理 preview
        if (previewAssets.isNotEmpty()) {
            val removedIds = previewAssets
                .map { it.id }
                .filter { it > 0L && it !in existingIds }
                .toSet()
            if (removedIds.isNotEmpty()) {
                previewAssets = previewAssets.filter { it.id in existingIds }
            }
        }
    }

    // 注册到 Compose CapabilityHost（CHAT 场景），让命令分发命中本能力，
    // 否则 findCapabilityForCommand 会回退到 registry 里的 GalleryCapability
    // （GALLERY 场景）→ CHAT 场景不匹配 → "正在为您切换到对应页面执行操作..."
    RegisterCapability(ChatSearchCapability.getInstance())
    RegisterCapability(ChatGallerySummaryCapability.getInstance())
    RegisterCapability(ChatRunScriptCapability.getInstance())
    RegisterCapability(ChatStartTagScanCapability.getInstance())

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

    BackHandler(enabled = isSidebarOpen) {
        isSidebarOpen = false
    }

    // 预览打开时拦截系统返回键：关闭预览并回到 chat 页（保留横滑卡片），
    // 而非直接 pop 到相册（Gallery 为 startDestination，栈底为 [Gallery, Chat]）。
    // 与 GalleryScreen 的预览 BackHandler 行为对齐。
    BackHandler(enabled = previewAssets.isNotEmpty() || previewImageUri != null) {
        when {
            previewAssets.isNotEmpty() -> previewAssets = emptyList()
            previewImageUri != null -> previewImageUri = null
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
            // 预览打开时隐藏 chat 顶栏，让 MediaPager 覆盖整屏（避免 chat 顶栏透出）
            if (previewAssets.isEmpty()) {
                ChatTopBar(
                    onNavigateBack = onNavigateBack,
                    onOpenSidebar = { isSidebarOpen = true },
                    onNavigateToSettings = onNavigateToSettings,
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
                            } else {
                                ChatMessageItem(
                                    message = message,
                                    onImageClick = { imageUri -> previewImageUri = imageUri }
                                )
                            }
                        }
                    }
                }

                // 输入区
                ChatInputArea(
                    currentModel = currentModel,
                    isProcessing = isProcessing,
                    onModelSwitch = { option ->
                        viewModel.switchModel(option)
                        if (option is ChatModelOption.Local) {
                            scope.launch {
                                settingsViewModel.checkChatModelsOnFeatureEnabled()
                            }
                        }
                    },
                    onSendMessage = { text ->
                        viewModel.sendMessage(text)
                    },
                    mediaViewModel = mediaViewModel,
                    viewModel = viewModel,
                    onNavigateToPhotoEditor = onNavigateToPhotoEditor,
                    onNavigateToIDPhoto = onNavigateToIDPhoto
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

            // 图片全屏预览
            ImagePreviewOverlay(
                imageUri = previewImageUri,
                onDismiss = { previewImageUri = null }
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
                    onReTag = {
                        context.startForegroundService(
                            TagGenerationService.intentScanPass3Full(context)
                        )
                    }
                )
            }
        }
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
    onNavigateToSettings: () -> Unit,
    onClearChat: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    modifier = Modifier.size(24.dp)
                )
            }
            IconButton(onClick = onOpenSidebar) {
                Icon(
                    imageVector = Icons.Rounded.Menu,
                    contentDescription = stringResource(R.string.cd_open_sidebar),
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = onClearChat, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Rounded.DeleteSweep,
                    contentDescription = stringResource(R.string.clear_chat),
                    modifier = Modifier.size(22.dp)
                )
            }
            IconButton(onClick = onNavigateToSettings, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Rounded.Settings,
                    contentDescription = stringResource(R.string.settings),
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatMessageItem(message: ChatMessageUi, onImageClick: (Uri) -> Unit = {}) {
    val isUser = message.type == ChatMessageType.USER_TEXT ||
        message.type == ChatMessageType.USER_IMAGE ||
        message.type == ChatMessageType.USER_IMAGE_TEXT
    val isImage = message.type == ChatMessageType.AGENT_IMAGE || message.type == ChatMessageType.USER_IMAGE
    val isImageText = message.type == ChatMessageType.USER_IMAGE_TEXT
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val copySuccess = stringResource(R.string.copy_success)

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Column(
            modifier = Modifier
                .clip(
                    if (isUser) {
                        RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp)
                    } else {
                        RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp)
                    }
                )
                .background(
                    if (isUser) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)
                    }
                )
                .padding(
                    horizontal = if (isImage) 6.dp else 16.dp,
                    vertical = if (isImage) 4.dp else 12.dp
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
                        contentScale = ContentScale.FillHeight,
                        modifier = Modifier
                            .height(160.dp)
                            .widthIn(max = 240.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                val iu = Uri.parse(message.imageUri)
                                val resolvedUri = if (iu.scheme != null) iu
                                    else java.io.File(message.imageUri).toUri()
                                onImageClick(resolvedUri)
                            }
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
                    // 显示图片（可点击进入全屏预览）
                    // 高度固定 200dp，宽度按原始比例自适应，不超 260dp
                    AsyncImage(
                        model = message.content,
                        contentDescription = stringResource(R.string.photo),
                        contentScale = ContentScale.FillHeight,
                        modifier = Modifier
                            .height(200.dp)
                            .widthIn(max = 260.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                val uri = Uri.parse(message.content)
                                val resolvedUri = if (uri.scheme != null) uri
                                    else java.io.File(message.content).toUri()
                                onImageClick(resolvedUri)
                            }
                    )
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
                    MarkdownText(
                        markdown = message.content,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )
                }
            }
            if (message.modelUsed != null && message.performance == null) {
                Text(
                    text = message.modelUsed,
                    color = if (isUser) Color.White.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatInputArea(
    currentModel: ChatModelOption,
    isProcessing: Boolean,
    onModelSwitch: (ChatModelOption) -> Unit,
    onSendMessage: (String) -> Unit,
    mediaViewModel: MediaViewModel,
    viewModel: ChatViewModel,
    onNavigateToPhotoEditor: (String, Boolean) -> Unit,
    onNavigateToIDPhoto: (String) -> Unit
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
    val savedInputMode by settingsRepository.chatInputModeFlow.collectAsState(initial = "voice")
    // 提到顶层稳定订阅（避免在 when(inputMode) 分支内 collectAsState 导致重组不稳定/漏订阅）
    val hasUserKey by viewModel.hasUserKey.collectAsState()
    val availableModels by viewModel.availableModels.collectAsState()
    val selectedModelId by viewModel.selectedModelId.collectAsState()
    val selectedModel = availableModels.find { m -> m.id == selectedModelId } ?: availableModels.firstOrNull()
    var inputMode by remember(savedInputMode) {
        mutableStateOf(
            if (savedInputMode == "text") ChatInputMode.TEXT else ChatInputMode.VOICE
        )
    }

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
                    currentModel = currentModel,
                    isProcessing = isProcessing,
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
                                    onSendMessage(text.trim())
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
                    onModelSwitch = onModelSwitch,
                    hasUserKey = hasUserKey,
                    availableModels = availableModels,
                    selectedModel = selectedModel,
                    onSwitchModel = viewModel::switchModel,
                    onSwitchToVoice = {
                        inputMode = ChatInputMode.VOICE
                        keyboardController?.hide()
                        scope.launch {
                            settingsRepository.updateChatInputMode("voice")
                        }
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
                        scope.launch {
                            settingsRepository.updateChatInputMode("text")
                        }
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

@Composable
private fun ChatTextInputMode(
    text: String,
    onTextChange: (String) -> Unit,
    currentModel: ChatModelOption,
    isProcessing: Boolean,
    onSend: () -> Unit,
    onModelMenuToggle: () -> Unit,
    onShowModelMenu: () -> Unit,
    onDismissModelMenu: () -> Unit,
    showModelMenu: Boolean,
    onModelSwitch: (ChatModelOption) -> Unit,
    hasUserKey: Boolean,
    availableModels: List<ChatViewModel.ChatRemoteModel>,
    selectedModel: ChatViewModel.ChatRemoteModel?,
    onSwitchModel: (String) -> Unit,
    onSwitchToVoice: () -> Unit,
    onShowPhotoPicker: () -> Unit,
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
            if (text.isEmpty()) {
                Text(
                    text = stringResource(R.string.chat_input_hint),
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                )
            }
            // 使用 BasicTextField 实现无边框输入
            androidx.compose.foundation.text.BasicTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.fillMaxWidth(),
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
private fun modelDotColor(model: ChatViewModel.ChatRemoteModel?): Color =
    if (model?.id == "official") Color(0xFF2196F3) else Color(0xFFFF9800)

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
    val decodeSpeed: Float
)

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
    val imageUri: String? = null
)

enum class ChatMessageType {
    USER_TEXT,
    AGENT_TEXT,
    USER_IMAGE,
    USER_IMAGE_TEXT,
    AGENT_IMAGE,
    COMMAND,
    PLAN_PREVIEW,
    MEDIA_RESULTS
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
 * 图片全屏预览浮层
 */
@Composable
private fun ImagePreviewOverlay(
    imageUri: Uri?,
    onDismiss: () -> Unit
) {
    AnimatedVisibility(
        visible = imageUri != null,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
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
            AsyncImage(
                model = imageUri,
                contentDescription = stringResource(R.string.cd_image_preview),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentScale = ContentScale.Fit
            )

            // 关闭按钮
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
        }
    }
}
