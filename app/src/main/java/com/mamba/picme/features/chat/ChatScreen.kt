package com.mamba.picme.features.chat

import android.app.Activity
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
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
import android.provider.MediaStore
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
import com.mamba.picme.features.chat.capability.ChatSearchCapability
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
    onNavigateToPhotoEditor: (uri: String, autoOptimize: Boolean) -> Unit = { _, _ -> }
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

    // 注册到 Compose CapabilityHost（CHAT 场景），让命令分发命中本能力，
    // 否则 findCapabilityForCommand 会回退到 registry 里的 GalleryCapability
    // （GALLERY 场景）→ CHAT 场景不匹配 → "正在为您切换到对应页面执行操作..."
    RegisterCapability(ChatSearchCapability.getInstance())

    // 绑定 ChatSearchCapability Delegate（chat 场景相册搜索执行器）
    DisposableEffect(Unit) {
        ChatSearchCapability.getInstance().bindDelegate(viewModel)
        onDispose { ChatSearchCapability.getInstance().unbindDelegate() }
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
                    onImagePicked = { uri ->
                        viewModel.sendImageMessage(uri)
                    },
                    mediaViewModel = mediaViewModel
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
                    onUseLocal = {
                        viewModel.switchModel(ChatModelOption.Local)
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
                        mediaViewModel.deleteMediaByIds(listOf(asset.id))
                        previewAssets = previewAssets.filter { it.id != asset.id }
                    },
                    onStartOcr = { uriString ->
                        mediaViewModel.recognizeTextFromCurrentImage(context, uriString.toUri())
                    },
                    onDismissOcr = { mediaViewModel.clearOcrResult() },
                    ocrState = mediaViewModel.ocrState,
                    onNavigateToEditor = { asset -> onNavigateToPhotoEditor(asset.uri, false) },
                    onAiOptimize = { asset -> onNavigateToPhotoEditor(asset.uri, true) },
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
    val isUser = message.type == ChatMessageType.USER_TEXT || message.type == ChatMessageType.USER_IMAGE
    val isImage = message.type == ChatMessageType.AGENT_IMAGE || message.type == ChatMessageType.USER_IMAGE
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
            if (message.modelUsed != null) {
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
                    PerformanceMetric(
                        icon = Icons.Rounded.Bolt,
                        value = "${perf.prefillTimeMs}ms",
                        tint = metricTint
                    )
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
    onImagePicked: (Uri) -> Unit = {},
    mediaViewModel: MediaViewModel
) {
    var text by remember { mutableStateOf("") }
    var showModelMenu by remember { mutableStateOf(false) }
    var showPhotoPicker by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState()
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val settingsRepository = remember { UserPreferencesRepository(context) }
    val savedInputMode by settingsRepository.chatInputModeFlow.collectAsState(initial = "voice")
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
                        if (text.isNotBlank() && !isProcessing) {
                            onSendMessage(text.trim())
                            text = ""
                            keyboardController?.hide()
                        }
                    },
                    onModelMenuToggle = { showModelMenu = !showModelMenu },
                    onShowModelMenu = { showModelMenu = true },
                    onDismissModelMenu = { showModelMenu = false },
                    showModelMenu = showModelMenu,
                    onModelSwitch = onModelSwitch,
                    onSwitchToVoice = {
                        inputMode = ChatInputMode.VOICE
                        keyboardController?.hide()
                        scope.launch {
                            settingsRepository.updateChatInputMode("voice")
                        }
                    },
                    onShowPhotoPicker = { showPhotoPicker = true }
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
                onImagePicked(uri)
                showPhotoPicker = false
            },
            onDismiss = { showPhotoPicker = false }
        )
    }
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
    onSwitchToVoice: () -> Unit,
    onShowPhotoPicker: () -> Unit
) {
    val hasContent = text.isNotBlank()

    // 输入框内容区域（外层已由 ChatInputArea 统一包裹白色卡片）
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
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
                // 模型切换胶囊按钮
                Box {
                    ModelCapsuleButton(
                        currentModel = currentModel,
                        onClick = onShowModelMenu
                    )
                    DropdownMenu(
                        expanded = showModelMenu,
                        onDismissRequest = onDismissModelMenu,
                        modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                    ) {
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
                                            .background(ChatModelOption.Local.indicatorColor)
                                    )
                                    Text("本地模型 (Qwen3.5-2B)")
                                }
                            },
                            onClick = {
                                onModelSwitch(ChatModelOption.Local)
                                onDismissModelMenu()
                            }
                        )
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
                                            .background(ChatModelOption.Remote.indicatorColor)
                                    )
                                    Text("远程模型 (DeepSeek)")
                                }
                            },
                            onClick = {
                                onModelSwitch(ChatModelOption.Remote)
                                onDismissModelMenu()
                            }
                        )
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
    currentModel: ChatModelOption,
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
                .background(currentModel.indicatorColor)
        )
        Text(
            text = currentModel.label,
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
    val mediaResults: MediaResultsUi? = null
)

enum class ChatMessageType {
    USER_TEXT,
    AGENT_TEXT,
    USER_IMAGE,
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
