package com.mamba.picme.features.settings

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mamba.picme.BuildConfig
import com.mamba.picme.PoLangApplication
import com.mamba.picme.R
import com.mamba.picme.agent.core.model.config.AiAgentMode
import com.mamba.picme.agent.core.tool.accessibility.AccessibilityServiceHolder
import com.mamba.picme.core.common.Logger
import com.mamba.picme.core.designsystem.PoLangTheme
import com.mamba.picme.data.download.DownloadState
import com.mamba.picme.data.download.ModelConfig
import com.mamba.picme.domain.model.AppLanguage
import com.mamba.picme.domain.model.DetectionModelType
import com.mamba.picme.domain.model.DetectionStage
import com.mamba.picme.domain.model.FaceDetectIntervalProfile
import com.mamba.picme.domain.model.FaceDetectionEngineMode
import com.mamba.picme.domain.model.InferenceDevicePreference
import com.mamba.picme.domain.model.LogModule
import com.mamba.picme.domain.model.LogModuleConfig
import com.mamba.picme.domain.model.StageConfig
import com.mamba.picme.domain.model.ThemeMode
import com.mamba.picme.domain.tag.TaggerModelSelector
import com.mamba.picme.domain.model.VoiceCommandMode
import com.mamba.picme.features.common.chat.rememberAgentChatConfig
import com.mamba.picme.features.common.topbar.AppTopBar
import com.mamba.picme.features.common.topbar.AppTopBarNavBack
import com.mamba.picme.features.backuprestore.BackupRestoreActivity
import com.mamba.picme.features.settings.capability.SettingsCapability
import com.mamba.picme.service.chat.FloatingChatBubbleService
import com.mamba.picme.util.permission.BatteryOptimizationUtils
import com.mamba.picme.util.permission.MiuiPermissionUtils
import kotlinx.coroutines.delay

/**
 * 设置页分类，用于主菜单与二级页切换
 */
enum class SettingsCategory {
    MAIN,           // 设置主菜单
    ACCOUNT,        // 账号
    PERSONALIZATION,// 个性化
    AI_AGENT,       // AI 助手
    GALLERY,        // 相册功能
    CAMERA_BEAUTY,  // 相机与美颜
    SYSTEM,         // 系统与权限
    DEVELOPER       // 开发者选项
}

private const val TAG = "Settings"

@Suppress("LongMethod", "LongParameterList") // 待重构：SettingsScreen 抽 SettingsNav holder
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    category: SettingsCategory = SettingsCategory.MAIN,
    onNavigateBack: () -> Unit,
    onNavigateToModelCenter: (String) -> Unit = {},
    onNavigateToTagControl: () -> Unit = {},
    onNavigateToTagViewer: () -> Unit = {},
    onNavigateToDuplicateManager: () -> Unit = {},
    onNavigateToDebug: () -> Unit = {},
    onNavigateToJsBridge: () -> Unit = {},
    onNavigateToSearchTest: () -> Unit = {},
    onNavigateToLlmLog: () -> Unit = {},
    onNavigateToCategory: (SettingsCategory) -> Unit = {},
    onNavigateToDataPrivacy: () -> Unit = {},
    onNavigateToMemoryFacts: () -> Unit = {},
    onNavigateToCommunicationChannel: () -> Unit = {},
    onNavigateToPeople: () -> Unit = {}
) {
    val context = LocalContext.current

    val themeMode by viewModel.themeMode.collectAsState()
    val appLanguage by viewModel.appLanguage.collectAsState()
    val developerOptionsUnlocked by viewModel.developerOptionsUnlocked.collectAsState()
    val debugUiEnabled by viewModel.debugUiEnabled.collectAsState()
    val showCameraInfoInPreview by viewModel.showCameraInfoInPreview.collectAsState()
    val showFaceDebugOverlay by viewModel.showFaceDebugOverlay.collectAsState()
    val showLogOverlay by viewModel.showLogOverlay.collectAsState()
    val faceDetectionLandmarkModeEnabled by viewModel.faceDetectionLandmarkModeEnabled.collectAsState()
    val adaptiveFaceDetectionIntervalEnabled by viewModel.adaptiveFaceDetectionIntervalEnabled.collectAsState()
    val faceDetectIntervalProfile by viewModel.faceDetectIntervalProfile.collectAsState()
    val debugShaderMode by viewModel.debugShaderMode.collectAsState()
    val roiStageConfig by viewModel.roiStageConfig.collectAsState()
    val landmarkStageConfig by viewModel.landmarkStageConfig.collectAsState()
    val aiAgentMode by viewModel.aiAgentMode.collectAsState()
    val aiAgentRemoteModelConfigs by viewModel.aiAgentRemoteModelConfigs.collectAsState()
    val aiAgentSelectedRemoteModel by viewModel.aiAgentSelectedRemoteModel.collectAsState()
    val autoExecutePlans by viewModel.autoExecutePlansEnabled.collectAsState()
    val tagGenerationUseOpencl by viewModel.tagGenerationUseOpencl.collectAsState()
    val taggerModelKey by viewModel.taggerModelKey.collectAsState()
    val voiceCommandMode by viewModel.voiceCommandMode.collectAsState()
    val voiceEntryEnabled by viewModel.voiceEntryEnabled.collectAsState()
    val localAsrModel by viewModel.localAsrModel.collectAsState()
    val localKwsModel by viewModel.localKwsModel.collectAsState()
    val logModuleConfig by viewModel.logModuleConfig.collectAsState()
    val downloadStates by viewModel.downloadStates.collectAsState()
    val allModels by viewModel.allModels.collectAsState()

    val agentChatConfig = rememberAgentChatConfig(
        context = context,
        logTag = TAG,
        onCommand = { command ->
            Logger.i(TAG, "Voice command: ${command.javaClass.simpleName}")
        },
        onTranscript = { transcript ->
            Logger.d(TAG, "Voice transcript: $transcript")
        }
    )
    val voiceCoordinator = agentChatConfig.voiceCoordinator
    DisposableEffect(Unit) {
        onDispose {
            // 修复 P0-1：不应该完全释放 voiceCoordinator，因为它在多个 Chat 屏幕间共享
            // 而应该只进行"软释放"（停止监听但保留引擎）
            Logger.i(TAG, "Settings screen disposed - performing soft release of voice coordinator")
            voiceCoordinator.stopWakeWordListening()
            voiceCoordinator.stopPushToTalk()
            // 注意：不调用 voiceCoordinator.release() 以避免破坏 ASR 引擎状态
        }
    }

    DisposableEffect(Unit) {
        Logger.i(TAG, "Binding SettingsCapability delegate")
        val settingsCapability = SettingsCapability.getInstance()
        settingsCapability.bindDelegate(object : SettingsCapability.Delegate {
            override fun onChangeTheme(theme: ThemeMode) {
                viewModel.setThemeMode(theme)
            }
            override fun onChangeLanguage(language: AppLanguage) {
                viewModel.setAppLanguage(language)
            }
            override fun onDownloadModel(modelId: String) {
                onNavigateToModelCenter("")
            }
            override fun onSwitchFaceEngine(engine: FaceDetectionEngineMode) {
                viewModel.setFaceDetectionEngineMode(engine)
            }
            override fun onToggleSetting(key: String, enabled: Boolean) {
                when (key) {
                    "debug_ui" -> viewModel.setDebugUiEnabled(enabled)
                    "camera_info" -> viewModel.setShowCameraInfoInPreview(enabled)
                    "voice_command" -> viewModel.setVoiceCommandMode(
                        if (enabled) VoiceCommandMode.WAKE_WORD else VoiceCommandMode.DISABLED
                    )
                    "agent_mode" -> viewModel.setAiAgentMode(
                        if (enabled) AiAgentMode.REMOTE else AiAgentMode.OFF
                    )
                    "tag_generation_opencl" -> viewModel.setTagGenerationUseOpencl(enabled)
                    else -> Logger.w(TAG, "Unknown setting key: $key")
                }
            }
        })
        Logger.i(TAG, "SettingsCapability delegate bound")

        onDispose {
            Logger.i(TAG, "Unbinding SettingsCapability delegate")
            settingsCapability.unbindDelegate()
        }
    }


    Box(modifier = Modifier.fillMaxSize()) {
        SettingsContent(
            category = category,
            themeMode = themeMode,
            appLanguage = appLanguage,
            developerOptionsUnlocked = developerOptionsUnlocked,
            onUnlockDeveloperOptions = { viewModel.setDeveloperOptionsUnlocked(true) },
            debugUiEnabled = debugUiEnabled,
            showCameraInfoInPreview = showCameraInfoInPreview,
            showFaceDebugOverlay = showFaceDebugOverlay,
            showLogOverlay = showLogOverlay,
            faceDetectionLandmarkModeEnabled = faceDetectionLandmarkModeEnabled,
            adaptiveFaceDetectionIntervalEnabled = adaptiveFaceDetectionIntervalEnabled,
            faceDetectIntervalProfile = faceDetectIntervalProfile,
            debugShaderMode = debugShaderMode,
            roiStageConfig = roiStageConfig,
            landmarkStageConfig = landmarkStageConfig,
            onThemeModeSelected = { viewModel.setThemeMode(it) },
            onAppLanguageSelected = { viewModel.setAppLanguage(it) },
            onDebugUiEnabledChange = { viewModel.setDebugUiEnabled(it) },
            onShowCameraInfoInPreviewChange = { viewModel.setShowCameraInfoInPreview(it) },
            onShowFaceDebugOverlayChange = { viewModel.setShowFaceDebugOverlay(it) },
            onShowLogOverlayChange = { viewModel.setShowLogOverlay(it) },
            onFaceDetectionLandmarkModeEnabledChange = { viewModel.setFaceDetectionLandmarkModeEnabled(it) },
            onAdaptiveFaceDetectionIntervalEnabledChange = { viewModel.setAdaptiveFaceDetectionIntervalEnabled(it) },
            onFaceDetectIntervalProfileSelected = { viewModel.setFaceDetectIntervalProfile(it) },
            onDebugShaderModeSelected = { viewModel.setDebugShaderMode(it) },
            onRoiModelTypeSelected = { viewModel.setRoiModelType(it) },
            onRoiDevicePreferenceSelected = { viewModel.setRoiDevicePreference(it) },
            onLandmarkModelTypeSelected = { viewModel.setLandmarkModelType(it) },
            onLandmarkDevicePreferenceSelected = { viewModel.setLandmarkDevicePreference(it) },
            aiAgentMode = aiAgentMode,
            onAiAgentModeChange = { viewModel.setAiAgentMode(it) },
            aiAgentRemoteModelConfigs = aiAgentRemoteModelConfigs,
            onAiAgentRemoteModelConfigsChange = { viewModel.setAiAgentRemoteModelConfigs(it) },
            aiAgentSelectedRemoteModel = aiAgentSelectedRemoteModel,
            onAiAgentSelectedRemoteModelChange = { viewModel.setAiAgentSelectedRemoteModel(it) },
            autoExecutePlans = autoExecutePlans,
            onAutoExecutePlansChange = { viewModel.setAutoExecutePlansEnabled(it) },
            tagGenerationUseOpencl = tagGenerationUseOpencl,
            onTagGenerationUseOpenclChange = { viewModel.setTagGenerationUseOpencl(it) },
            taggerModelKey = taggerModelKey,
            onTaggerModelKeyChange = { viewModel.setTaggerModelKey(it) },
            voiceCommandMode = voiceCommandMode,
            onVoiceCommandModeChange = { viewModel.setVoiceCommandMode(it) },
            voiceEntryEnabled = voiceEntryEnabled,
            onVoiceEntryEnabledChange = { viewModel.setVoiceEntryEnabled(it) },
            localAsrModel = localAsrModel,
            onLocalAsrModelChange = { viewModel.setLocalAsrModel(it) },
            localKwsModel = localKwsModel,
            onLocalKwsModelChange = { viewModel.setLocalKwsModel(it) },
            onNavigateToModelCenter = onNavigateToModelCenter,
            onNavigateToCategory = onNavigateToCategory,
            isModelDownloaded = viewModel::isModelDownloaded,
            getModelId = viewModel::getModelId,
            downloadModel = viewModel::downloadModel,
            downloadStates = downloadStates,
            allModels = allModels,
            logModuleConfig = logModuleConfig,
            onLogModuleConfigChange = viewModel::setLogModuleConfig,
            onNavigateBack = onNavigateBack,
            onNavigateToTagControl = onNavigateToTagControl,
            onNavigateToTagViewer = onNavigateToTagViewer,
            onNavigateToDuplicateManager = onNavigateToDuplicateManager,
            onNavigateToDebug = onNavigateToDebug,
            onNavigateToJsBridge = onNavigateToJsBridge,
            onNavigateToSearchTest = onNavigateToSearchTest,
            onNavigateToLlmLog = onNavigateToLlmLog,
            onNavigateToDataPrivacy = onNavigateToDataPrivacy,
            onNavigateToMemoryFacts = onNavigateToMemoryFacts,
            onNavigateToCommunicationChannel = onNavigateToCommunicationChannel,
            onNavigateToPeople = onNavigateToPeople
        )
    }
}

@Suppress("LongMethod", "LongParameterList", "CyclomaticComplexMethod") // 待重构：SettingsContent 按分类拆子屏
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsContent(
    category: SettingsCategory,
    themeMode: ThemeMode,
    appLanguage: AppLanguage,
    developerOptionsUnlocked: Boolean,
    onUnlockDeveloperOptions: () -> Unit,
    debugUiEnabled: Boolean,
    showCameraInfoInPreview: Boolean,
    showFaceDebugOverlay: Boolean,
    showLogOverlay: Boolean,
    faceDetectionLandmarkModeEnabled: Boolean,
    adaptiveFaceDetectionIntervalEnabled: Boolean,
    faceDetectIntervalProfile: FaceDetectIntervalProfile,
    debugShaderMode: Int,
    roiStageConfig: StageConfig,
    landmarkStageConfig: StageConfig,
    aiAgentMode: AiAgentMode,
    onAiAgentModeChange: (AiAgentMode) -> Unit,
    aiAgentRemoteModelConfigs: String,
    onAiAgentRemoteModelConfigsChange: (String) -> Unit,
    aiAgentSelectedRemoteModel: String,
    onAiAgentSelectedRemoteModelChange: (String) -> Unit,
    autoExecutePlans: Boolean,
    onAutoExecutePlansChange: (Boolean) -> Unit,
    tagGenerationUseOpencl: Boolean,
    onTagGenerationUseOpenclChange: (Boolean) -> Unit,
    taggerModelKey: String,
    onTaggerModelKeyChange: (String) -> Unit,
    voiceCommandMode: VoiceCommandMode,
    onVoiceCommandModeChange: (VoiceCommandMode) -> Unit,
    voiceEntryEnabled: Boolean,
    onVoiceEntryEnabledChange: (Boolean) -> Unit,
    localAsrModel: String,
    onLocalAsrModelChange: (String) -> Unit,
    localKwsModel: String,
    onLocalKwsModelChange: (String) -> Unit,
    onThemeModeSelected: (ThemeMode) -> Unit,
    onAppLanguageSelected: (AppLanguage) -> Unit,
    onDebugUiEnabledChange: (Boolean) -> Unit,
    onShowCameraInfoInPreviewChange: (Boolean) -> Unit,
    onShowFaceDebugOverlayChange: (Boolean) -> Unit,
    onShowLogOverlayChange: (Boolean) -> Unit,
    onFaceDetectionLandmarkModeEnabledChange: (Boolean) -> Unit,
    onAdaptiveFaceDetectionIntervalEnabledChange: (Boolean) -> Unit,
    onFaceDetectIntervalProfileSelected: (FaceDetectIntervalProfile) -> Unit,
    onDebugShaderModeSelected: (Int) -> Unit,
    onRoiModelTypeSelected: (DetectionModelType) -> Unit,
    onRoiDevicePreferenceSelected: (InferenceDevicePreference) -> Unit,
    onLandmarkModelTypeSelected: (DetectionModelType) -> Unit,
    onLandmarkDevicePreferenceSelected: (InferenceDevicePreference) -> Unit,
    onNavigateToModelCenter: (String) -> Unit,
    onNavigateToCategory: (SettingsCategory) -> Unit = {},
    isModelDownloaded: (DetectionModelType) -> Boolean,
    getModelId: (DetectionModelType, DetectionStage) -> String?,
    downloadModel: (String, ModelConfig) -> Unit,
    downloadStates: Map<String, DownloadState>,
    allModels: List<ModelConfig>,
    logModuleConfig: LogModuleConfig,
    onLogModuleConfigChange: (LogModuleConfig) -> Unit,
    onNavigateBack: () -> Unit,
    onNavigateToTagControl: () -> Unit = {},
    onNavigateToTagViewer: () -> Unit = {},
    onNavigateToDuplicateManager: () -> Unit = {},
    onNavigateToDebug: () -> Unit = {},
    onNavigateToJsBridge: () -> Unit = {},
    onNavigateToSearchTest: () -> Unit = {},
    onNavigateToLlmLog: () -> Unit = {},
    onNavigateToDataPrivacy: () -> Unit = {},
    onNavigateToMemoryFacts: () -> Unit = {},
    onNavigateToCommunicationChannel: () -> Unit = {},
    onNavigateToPeople: () -> Unit = {}
) {
    val titleRes = when (category) {
        SettingsCategory.MAIN -> R.string.settings
        SettingsCategory.ACCOUNT -> R.string.account
        SettingsCategory.PERSONALIZATION -> R.string.personalization
        SettingsCategory.AI_AGENT -> R.string.ai_assistant
        SettingsCategory.GALLERY -> R.string.gallery_features
        SettingsCategory.CAMERA_BEAUTY -> R.string.camera_and_beauty
        SettingsCategory.SYSTEM -> R.string.system_and_permissions
        SettingsCategory.DEVELOPER -> R.string.developer_options
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = { Text(stringResource(titleRes)) },
                navigationIcon = { AppTopBarNavBack(onClick = onNavigateBack) }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            if (category == SettingsCategory.MAIN) {
                SettingsMainMenu(
                    themeMode = themeMode,
                    onThemeModeSelected = onThemeModeSelected,
                    appLanguage = appLanguage,
                    onAppLanguageSelected = onAppLanguageSelected,
                    developerOptionsUnlocked = developerOptionsUnlocked,
                    onUnlockDeveloperOptions = onUnlockDeveloperOptions,
                    onNavigateToCategory = onNavigateToCategory,
                    onNavigateToModelCenter = { onNavigateToModelCenter("") },
                    onNavigateToDataPrivacy = onNavigateToDataPrivacy,
                    onNavigateToCommunicationChannel = onNavigateToCommunicationChannel,
                    onNavigateToMemoryFacts = onNavigateToMemoryFacts,
                    onNavigateToPeople = onNavigateToPeople
                )
                return@Column
            }

            // ── 0. 账号 ────────────────────────────────────────────
            if (category == SettingsCategory.ACCOUNT) {
                SettingsSection(
                    title = stringResource(R.string.account),
                    description = stringResource(R.string.account_desc)
                ) {
                    ServerAuthSection()
                }
            }

            // ── 1. 个性化（主题与语言已迁移至设置页主菜单顶部）───

            // ── 2. AI 设置 ────────────────────────────────────────
            if (category == SettingsCategory.AI_AGENT) {
                // 2.1 默认链路：单选决定 chat 实际路由 + 全局行为开关
                SettingsSection(
                    title = stringResource(R.string.ai_agent),
                    description = stringResource(R.string.ai_agent_desc)
                ) {
                    DebugOptionRow(
                        title = stringResource(R.string.ai_agent_auto_execute_plans),
                        checked = autoExecutePlans,
                        onCheckedChange = onAutoExecutePlansChange
                    )
                    Text(
                        text = stringResource(R.string.ai_agent_auto_execute_plans_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                    )

                    AiAgentModeSelection(
                        currentMode = aiAgentMode,
                        onModeSelected = onAiAgentModeChange
                    )
                }

                // 2.2 远程模型原始配置（API key/baseUrl/protocol）已收口至「开发者选项」

                // 2.4 语音控制（独立第三区）
                SettingsSection(
                    title = stringResource(R.string.voice_control),
                    description = stringResource(R.string.voice_control_desc)
                ) {
                    VoiceCommandModeSelection(
                        currentMode = voiceCommandMode,
                        onModeSelected = onVoiceCommandModeChange
                    )

                    if (voiceCommandMode != VoiceCommandMode.DISABLED) {
                        LocalAsrModelSelection(
                            currentModel = localAsrModel,
                            onModelSelected = onLocalAsrModelChange,
                            onNavigateToModelCenter = onNavigateToModelCenter
                        )

                        LocalKwsModelSelection(
                            currentModel = localKwsModel,
                            onModelSelected = onLocalKwsModelChange,
                            onNavigateToModelCenter = onNavigateToModelCenter
                        )
                    }
                }
            }

            // ── 3. 相册功能 ───────────────────────────────────────
            if (category == SettingsCategory.GALLERY) {
                SettingsSection(
                    title = stringResource(R.string.gallery_features),
                    description = stringResource(R.string.gallery_features_desc)
                ) {
                    SettingsClickableRow(
                        title = stringResource(R.string.tag_control_title),
                        subtitle = stringResource(R.string.tag_control_subtitle),
                        leadingIcon = Icons.AutoMirrored.Rounded.Label,
                        onClick = onNavigateToTagControl
                    )
                    SettingsClickableRow(
                        title = stringResource(R.string.tag_viewer_title),
                        subtitle = stringResource(R.string.tag_viewer_open_entry),
                        leadingIcon = Icons.Rounded.Search,
                        onClick = onNavigateToTagViewer
                    )
                    SettingsClickableRow(
                        title = stringResource(R.string.manage_duplicates),
                        subtitle = stringResource(R.string.duplicate_manager_desc),
                        leadingIcon = Icons.Rounded.PhotoLibrary,
                        onClick = onNavigateToDuplicateManager
                    )
                    // 打标模型选择（Florence-2/Qwen3-VL）已收口至「开发者选项」
                }

                // TAG 生成 GPU 加速是真实性能配置（非调试项），作为相册常规配置项展示
                SettingsSection(
                    title = stringResource(R.string.gallery_advanced)
                ) {
                    OpenClBackendSelection(
                        useOpencl = tagGenerationUseOpencl,
                        onToggle = onTagGenerationUseOpenclChange,
                        title = stringResource(R.string.tag_gen_use_opencl_title)
                    )
                    Text(
                        text = stringResource(R.string.tag_gen_use_opencl_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                    )
                }
            }

            // ── 4. 相机与美颜 ─────────────────────────────────────
            if (category == SettingsCategory.CAMERA_BEAUTY) {
                SettingsSection(
                    title = stringResource(R.string.voice_control),
                    description = stringResource(R.string.voice_entry_section_desc)
                ) {
                    DebugOptionRow(
                        title = stringResource(R.string.voice_entry_enabled),
                        checked = voiceEntryEnabled,
                        onCheckedChange = onVoiceEntryEnabledChange
                    )
                }

                // 人脸检测阶段配置（ROI/Landmark 模型与推理设备）+ 自适应间隔已收口至「开发者选项」
            }

            // ── 5. 系统与权限 ─────────────────────────────────────
            if (category == SettingsCategory.SYSTEM) {
                val context = LocalContext.current
                var isFloatingChatRunning by remember {
                    mutableStateOf(FloatingChatBubbleService.isRunning(context))
                }
                var hasOverlayPermission by remember {
                    mutableStateOf(FloatingChatBubbleService.canDrawOverlays(context))
                }
                val overlayPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) {
                    hasOverlayPermission = FloatingChatBubbleService.canDrawOverlays(context)
                    if (hasOverlayPermission && !isFloatingChatRunning) {
                        FloatingChatBubbleService.start(context)
                        isFloatingChatRunning = true
                    }
                }

                LaunchedEffect(Unit) {
                    while (true) {
                        isFloatingChatRunning = FloatingChatBubbleService.isRunning(context)
                        hasOverlayPermission = FloatingChatBubbleService.canDrawOverlays(context)
                        delay(1000)
                    }
                }

                SettingsSection(
                    title = stringResource(R.string.floating_chat_title),
                    description = stringResource(R.string.floating_chat_summary)
                ) {
                    SettingsClickableRow(
                        title = stringResource(R.string.floating_chat_title),
                        subtitle = stringResource(R.string.floating_chat_summary),
                        valueText = stringResource(
                            if (isFloatingChatRunning) R.string.floating_chat_enabled else R.string.floating_chat_disabled
                        ),
                        onClick = {
                            when {
                                !hasOverlayPermission -> {
                                    overlayPermissionLauncher.launch(
                                        FloatingChatBubbleService.openOverlayPermissionSettingsIntent(context)
                                    )
                                }
                                isFloatingChatRunning -> {
                                    FloatingChatBubbleService.stop(context)
                                    isFloatingChatRunning = false
                                }
                                else -> {
                                    FloatingChatBubbleService.start(context)
                                    isFloatingChatRunning = true
                                }
                            }
                        }
                    )
                }

                var isIgnoringBatteryOptimizations by remember {
                    mutableStateOf(BatteryOptimizationUtils.isIgnoringBatteryOptimizations(context))
                }
                val isMiui = remember { MiuiPermissionUtils.isMiui() }

                LaunchedEffect(Unit) {
                    while (true) {
                        isIgnoringBatteryOptimizations =
                            BatteryOptimizationUtils.isIgnoringBatteryOptimizations(context)
                        delay(1000)
                    }
                }

                SettingsSection(
                    title = stringResource(R.string.settings_background_permission_title),
                    description = stringResource(R.string.settings_background_permission_summary)
                ) {
                    SettingsClickableRow(
                        title = stringResource(R.string.settings_battery_optimization_title),
                        subtitle = stringResource(R.string.settings_battery_optimization_summary),
                        valueText = stringResource(
                            if (isIgnoringBatteryOptimizations) {
                                R.string.settings_battery_optimization_enabled
                            } else {
                                R.string.settings_battery_optimization_disabled
                            }
                        ),
                        onClick = {
                            if (!isIgnoringBatteryOptimizations) {
                                BatteryOptimizationUtils.requestIgnoreBatteryOptimizations(context)
                            }
                        }
                    )

                    if (isMiui) {
                        SettingsClickableRow(
                            title = stringResource(R.string.settings_miui_auto_start_title),
                            subtitle = stringResource(R.string.settings_miui_auto_start_summary),
                            valueText = stringResource(R.string.settings_miui_action_open),
                            onClick = { MiuiPermissionUtils.openMiuiAutoStart(context) }
                        )

                        SettingsClickableRow(
                            title = stringResource(R.string.settings_miui_permission_editor_title),
                            subtitle = stringResource(R.string.settings_miui_permission_editor_summary),
                            valueText = stringResource(R.string.settings_miui_action_open),
                            onClick = { MiuiPermissionUtils.openMiuiPermissionEditor(context) }
                        )
                    }
                }
            }

            // ── 6. 开发者选项 ─────────────────────────────────────
            if (category == SettingsCategory.DEVELOPER) {
                // ── 6.1 调试浮层：相机预览上的可视化叠加层 ──────────────
                SettingsSection(
                    title = stringResource(R.string.debug_tools),
                    description = stringResource(R.string.settings_debug_tools_desc)
                ) {
                    DebugOptionRow(
                        title = stringResource(R.string.debug),
                        checked = debugUiEnabled,
                        onCheckedChange = onDebugUiEnabledChange
                    )
                    if (debugUiEnabled) {
                        DebugOptionRow(
                            title = stringResource(R.string.show_camera_info),
                            checked = showCameraInfoInPreview,
                            onCheckedChange = onShowCameraInfoInPreviewChange
                        )
                        DebugOptionRow(
                            title = stringResource(R.string.show_face_debug),
                            checked = showFaceDebugOverlay,
                            onCheckedChange = onShowFaceDebugOverlayChange
                        )
                        DebugOptionRow(
                            title = stringResource(R.string.show_log_overlay),
                            checked = showLogOverlay,
                            onCheckedChange = onShowLogOverlayChange
                        )
                        ShaderDebugModeSelection(
                            currentMode = debugShaderMode,
                            onModeSelected = onDebugShaderModeSelected
                        )
                    }
                }

                // ── 6.2 人脸检测引擎（收口）：阶段配置 + 关键点 + 自适应间隔 ──
                SettingsSection(
                    title = stringResource(R.string.face_detection_advanced),
                    description = stringResource(R.string.settings_face_detection_advanced_desc)
                ) {
                    StageConfigSection(
                        stage = DetectionStage.ROI,
                        config = roiStageConfig,
                        onModelTypeSelected = onRoiModelTypeSelected,
                        onDevicePreferenceSelected = onRoiDevicePreferenceSelected,
                        onNavigateToModelManager = onNavigateToModelCenter,
                        isModelDownloaded = isModelDownloaded,
                        getModelId = getModelId,
                        downloadModel = downloadModel,
                        downloadStates = downloadStates,
                        allModels = allModels
                    )

                    StageConfigSection(
                        stage = DetectionStage.LANDMARK,
                        config = landmarkStageConfig,
                        onModelTypeSelected = onLandmarkModelTypeSelected,
                        onDevicePreferenceSelected = onLandmarkDevicePreferenceSelected,
                        onNavigateToModelManager = onNavigateToModelCenter,
                        isModelDownloaded = isModelDownloaded,
                        getModelId = getModelId,
                        downloadModel = downloadModel,
                        downloadStates = downloadStates,
                        allModels = allModels
                    )

                    DebugOptionRow(
                        title = stringResource(R.string.face_landmark_mode),
                        checked = faceDetectionLandmarkModeEnabled,
                        onCheckedChange = onFaceDetectionLandmarkModeEnabledChange
                    )
                    DebugOptionRow(
                        title = stringResource(R.string.adaptive_face_detect_interval),
                        checked = adaptiveFaceDetectionIntervalEnabled,
                        onCheckedChange = onAdaptiveFaceDetectionIntervalEnabledChange
                    )
                    if (adaptiveFaceDetectionIntervalEnabled) {
                        FaceDetectProfileSelection(
                            currentProfile = faceDetectIntervalProfile,
                            onProfileSelected = onFaceDetectIntervalProfileSelected
                        )
                    }
                }

                // ── 6.3 AI 推理链路·高级（收口）：远程模型原始配置 ────────
                SettingsSection(
                    title = stringResource(R.string.ai_settings_remote_section)
                ) {
                    AiAgentRemoteModelsSection(
                        configsJson = aiAgentRemoteModelConfigs,
                        onConfigsChange = onAiAgentRemoteModelConfigsChange,
                        selectedModelId = aiAgentSelectedRemoteModel,
                        onSelectedModelChange = onAiAgentSelectedRemoteModelChange
                    )
                }

                // ── 6.4 相册打标·高级（收口）：打标模型选择 ─────────────
                SettingsSection(
                    title = stringResource(R.string.gallery_advanced)
                ) {
                    val taggerAutoLabel = stringResource(R.string.tag_model_auto)
                    SettingsClickableRow(
                        title = stringResource(R.string.tag_model_selector_title),
                        subtitle = when (taggerModelKey) {
                            "florence2_base" -> "Florence-2-Base"
                            "qwen3_vl_2b" -> "Qwen3-VL-2B"
                            else -> taggerAutoLabel
                        },
                        leadingIcon = Icons.AutoMirrored.Rounded.Label,
                        onClick = {
                            // 三态循环：自动(→Florence-2 首选) → Florence-2 → Qwen3-VL-2B(备选) → 自动
                            val next = when (taggerModelKey) {
                                TaggerModelSelector.AUTO -> "florence2_base"
                                "florence2_base" -> "qwen3_vl_2b"
                                else -> TaggerModelSelector.AUTO
                            }
                            onTaggerModelKeyChange(next)
                        }
                    )
                }

                // ── 6.5 诊断（全构建可见；release 仅展示纯指标） ──────────
                SettingsSection(
                    title = stringResource(R.string.diagnostics_entries)
                ) {
                    SettingsClickableRow(
                        title = stringResource(R.string.llm_call_log),
                        subtitle = stringResource(R.string.llm_call_log_desc),
                        valueText = stringResource(R.string.enter),
                        onClick = onNavigateToLlmLog
                    )
                }

                // ── 6.6 测试工具与服务（仅 debug 构建） ────────────────
                if (BuildConfig.DEBUG) {
                    val context = LocalContext.current

                    SettingsSection(
                        title = stringResource(R.string.developer_debug_entries)
                    ) {
                        SettingsClickableRow(
                            title = stringResource(R.string.debug_image_download),
                            subtitle = stringResource(R.string.debug_image_download_desc),
                            valueText = stringResource(R.string.enter),
                            onClick = onNavigateToDebug
                        )
                        SettingsClickableRow(
                            title = stringResource(R.string.search_test_entry_title),
                            subtitle = stringResource(R.string.search_test_entry_subtitle),
                            valueText = stringResource(R.string.enter),
                            onClick = onNavigateToSearchTest
                        )
                        SettingsClickableRow(
                            title = stringResource(R.string.jsbridge_entry_title),
                            subtitle = stringResource(R.string.jsbridge_entry_subtitle),
                            valueText = stringResource(R.string.enter),
                            onClick = onNavigateToJsBridge
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // AI 远程控制无障碍服务
                        var isAccessibilityEnabled by remember {
                            mutableStateOf(AccessibilityServiceHolder.isActive())
                        }

                        LaunchedEffect(Unit) {
                            while (true) {
                                isAccessibilityEnabled = AccessibilityServiceHolder.isActive()
                                delay(1000)
                            }
                        }

                        SettingsClickableRow(
                            title = stringResource(R.string.settings_accessibility_service_title),
                            subtitle = stringResource(R.string.settings_accessibility_service_summary),
                            valueText = stringResource(
                                if (isAccessibilityEnabled) R.string.settings_accessibility_service_enabled else R.string.settings_accessibility_service_disabled
                            ),
                            leadingIcon = Icons.Rounded.Accessibility,
                            onClick = {
                                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            }
                        )
                    }
                }

                // ── 6.7 日志配置：按模块控制日志输出 ──────────────────
                SettingsSection(
                    title = stringResource(R.string.log_management),
                    description = stringResource(R.string.log_management_desc)
                ) {
                    LogModuleConfigSection(
                        config = logModuleConfig,
                        onConfigChange = onLogModuleConfigChange
                    )
                }
            }
        }
    }
}

@Suppress("LongParameterList") // 待重构：SettingsScreen 抽 SettingsNav holder
@Composable
private fun SettingsMainMenu(
    themeMode: ThemeMode,
    onThemeModeSelected: (ThemeMode) -> Unit,
    appLanguage: AppLanguage,
    onAppLanguageSelected: (AppLanguage) -> Unit,
    developerOptionsUnlocked: Boolean,
    onUnlockDeveloperOptions: () -> Unit,
    onNavigateToCategory: (SettingsCategory) -> Unit,
    onNavigateToModelCenter: () -> Unit,
    onNavigateToDataPrivacy: () -> Unit,
    onNavigateToCommunicationChannel: () -> Unit,
    onNavigateToMemoryFacts: () -> Unit,
    onNavigateToPeople: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ── 账号（置顶全宽卡，反映登录态）──
        SettingsAccountHeroCard(onClick = { onNavigateToCategory(SettingsCategory.ACCOUNT) })

        // ── 主题 ──
        Card(
            modifier = Modifier
                .fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.theme_mode),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                ThemeSelection(
                    currentMode = themeMode,
                    onModeSelected = onThemeModeSelected
                )
            }
        }

        // ── 语言 ──
        Card(
            modifier = Modifier
                .fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.language),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                LanguageSelection(
                    currentLanguage = appLanguage,
                    onLanguageSelected = onAppLanguageSelected
                )
            }
        }

        // ── 功能分类网格 ──
        SettingsCategoryGrid(
            onNavigateToCategory = onNavigateToCategory,
            onNavigateToModelCenter = onNavigateToModelCenter,
            onNavigateToDataPrivacy = onNavigateToDataPrivacy,
            onNavigateToCommunicationChannel = onNavigateToCommunicationChannel,
            onNavigateToMemoryFacts = onNavigateToMemoryFacts,
            onNavigateToPeople = onNavigateToPeople,
            developerOptionsUnlocked = developerOptionsUnlocked,
        )

        // ── 版本页脚（连点 7 次解锁开发者选项）──
        SettingsVersionFooter(onUnlock = onUnlockDeveloperOptions)
    }
}

/**
 * 账号置顶全宽卡：已登录显示邮箱与「服务端账户」，未登录显示「账号」+ 注册引导。
 */
@Composable
private fun SettingsAccountHeroCard(onClick: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as PoLangApplication
    val repo = app.container.userPreferencesRepository
    val serverToken by repo.serverAuthTokenFlow.collectAsState(initial = "")
    val serverEmail by repo.serverAuthEmailFlow.collectAsState(initial = "")
    val loggedIn = serverToken.isNotBlank()

    val authClient = app.container.picMeAuthClient
    var quotaUsed by remember { mutableStateOf(0) }
    var quotaLimit by remember { mutableStateOf(0) }
    var quotaLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(serverToken) {
        if (loggedIn) {
            authClient.getQuota(serverToken)
                .onSuccess {
                    quotaUsed = it.llmCallsUsed
                    quotaLimit = it.llmCallsLimit
                    quotaLoaded = true
                }
                .onFailure { Logger.w(TAG, "Hero card quota load failed: ${it.message}") }
        } else {
            quotaLoaded = false
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = if (loggedIn) serverEmail else stringResource(R.string.account),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = when {
                        !loggedIn -> stringResource(R.string.account_desc)
                        quotaLoaded -> stringResource(
                            R.string.auth_quota_summary,
                            quotaUsed,
                            quotaLimit
                        )
                        else -> stringResource(R.string.auth_account_title)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private data class CategoryGridItem(
    val titleRes: Int,
    val descriptionRes: Int,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

/**
 * 设置页功能分类网格（2 列卡片）。
 */
@Composable
private fun SettingsCategoryGrid(
    onNavigateToCategory: (SettingsCategory) -> Unit,
    onNavigateToModelCenter: () -> Unit,
    onNavigateToDataPrivacy: () -> Unit,
    onNavigateToCommunicationChannel: () -> Unit,
    onNavigateToMemoryFacts: () -> Unit,
    onNavigateToPeople: () -> Unit,
    developerOptionsUnlocked: Boolean,
) {
    val context = LocalContext.current
    val baseItems = listOf(
        CategoryGridItem(R.string.ai_assistant, R.string.ai_assistant_desc, Icons.Rounded.SmartToy) {
            onNavigateToCategory(SettingsCategory.AI_AGENT)
        },
        CategoryGridItem(R.string.settings_ai_memory, R.string.settings_ai_memory_desc, Icons.Rounded.Psychology, onNavigateToMemoryFacts),
        CategoryGridItem(R.string.people_entry, R.string.people_entry_desc, Icons.Rounded.AccountCircle, onNavigateToPeople),
        CategoryGridItem(R.string.communication_channel, R.string.communication_channel_desc, Icons.Rounded.Forum) {
            onNavigateToCommunicationChannel()
        },
        CategoryGridItem(R.string.gallery_features, R.string.gallery_features_desc, Icons.Rounded.PhotoLibrary) {
            onNavigateToCategory(SettingsCategory.GALLERY)
        },
        CategoryGridItem(R.string.camera_and_beauty, R.string.camera_and_beauty_desc, Icons.Rounded.CameraAlt) {
            onNavigateToCategory(SettingsCategory.CAMERA_BEAUTY)
        },
        CategoryGridItem(R.string.model_center, R.string.model_center_desc, Icons.Rounded.CloudDownload, onNavigateToModelCenter),
        CategoryGridItem(R.string.backup_and_restore, R.string.backup_and_restore_desc, Icons.Rounded.Storage) {
            context.startActivity(BackupRestoreActivity.intent(context))
        },
        CategoryGridItem(R.string.data_privacy_entry, R.string.data_privacy_desc, Icons.Rounded.PrivacyTip, onNavigateToDataPrivacy),
    )
    val devItem = CategoryGridItem(R.string.developer_options, R.string.developer_options_desc, Icons.Rounded.Terminal) {
        onNavigateToCategory(SettingsCategory.DEVELOPER)
    }
    val items = if (developerOptionsUnlocked) baseItems + devItem else baseItems

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.chunked(2).forEach { pair ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                pair.forEach { item ->
                    SettingsCategoryCard(
                        title = stringResource(item.titleRes),
                        description = stringResource(item.descriptionRes),
                        icon = item.icon,
                        modifier = Modifier.weight(1f),
                        onClick = item.onClick
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsCategoryCard(
    title: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                minLines = 2,
                maxLines = 2
            )
        }
    }
}

/**
 * 设置主页底部版本页脚：连点 [DeveloperOptionsUnlockCounter.REQUIRED_TAPS] 次解锁开发者选项。
 * 解锁前对普通用户仅显示版本号（良性信息），不构成开发项泄漏。
 */
@Composable
private fun SettingsVersionFooter(onUnlock: () -> Unit) {
    val context = LocalContext.current
    val counter = remember { DeveloperOptionsUnlockCounter() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(
                R.string.app_version_footer,
                stringResource(R.string.app_name),
                BuildConfig.VERSION_NAME
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.clickable {
                when (val result = counter.tap()) {
                    is UnlockTapResult.Countdown -> Toast.makeText(
                        context,
                        context.getString(R.string.dev_options_unlock_countdown, result.remaining),
                        Toast.LENGTH_SHORT
                    ).show()
                    UnlockTapResult.Unlocked -> {
                        onUnlock()
                        Toast.makeText(
                            context,
                            context.getString(R.string.dev_options_unlocked_toast),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        )
    }
}

@Composable
private fun LogModuleConfigSection(
    config: LogModuleConfig,
    onConfigChange: (LogModuleConfig) -> Unit
) {
    CompactMultiSelectChips(
        options = LogModule.entries.map { it to it.displayName },
        isSelected = { module -> config.isEnabled(module) },
        maxLines = 3,
        onToggle = { module ->
            onConfigChange(config.toggle(module, !config.isEnabled(module)))
        }
    )
}

@Composable
private fun OpenClBackendSelection(
    useOpencl: Boolean,
    onToggle: (Boolean) -> Unit,
    title: String = stringResource(R.string.ai_agent_local_backend)
) {
    val options = listOf(
        false to stringResource(R.string.ai_agent_local_backend_cpu),
        true to stringResource(R.string.ai_agent_local_backend_opencl)
    )

    Text(
        text = title,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 0.dp)
    )

    CompactOptionChips(
        options = options,
        currentValue = useOpencl,
        maxLines = 1,
        onSelected = onToggle
    )

    Text(
        text = stringResource(R.string.ai_agent_local_backend_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
    )
}

@Composable
private fun ThemeSelection(
    currentMode: ThemeMode,
    onModeSelected: (ThemeMode) -> Unit
) {
    val options = listOf(
        ThemeMode.SYSTEM to stringResource(R.string.system_default),
        ThemeMode.LIGHT to stringResource(R.string.light),
        ThemeMode.DARK to stringResource(R.string.dark)
    )
    CompactOptionChips(
        options = options,
        currentValue = currentMode,
        maxLines = 1,
        onSelected = onModeSelected
    )
}

@Composable
private fun LanguageSelection(
    currentLanguage: AppLanguage,
    onLanguageSelected: (AppLanguage) -> Unit
) {
    val options = listOf(
        AppLanguage.ENGLISH to "English",
        AppLanguage.CHINESE to "中文",
        AppLanguage.TRADITIONAL_CHINESE to "繁體中文"
    )
    CompactOptionChips(
        options = options,
        currentValue = currentLanguage,
        maxLines = 2,
        onSelected = onLanguageSelected
    )
}

@Composable
private fun FaceDetectProfileSelection(
    currentProfile: FaceDetectIntervalProfile,
    onProfileSelected: (FaceDetectIntervalProfile) -> Unit
) {
    val options = listOf(
        FaceDetectIntervalProfile.CONSERVATIVE to stringResource(R.string.face_detect_profile_conservative),
        FaceDetectIntervalProfile.BALANCED to stringResource(R.string.face_detect_profile_balanced),
        FaceDetectIntervalProfile.AGGRESSIVE to stringResource(R.string.face_detect_profile_aggressive)
    )

    Text(
        text = stringResource(R.string.face_detect_profile_title),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp, top = 2.dp, bottom = 0.dp)
    )

    CompactOptionChips(
        options = options,
        currentValue = currentProfile,
        maxLines = 1,
        onSelected = onProfileSelected
    )
}

@Composable
private fun ShaderDebugModeSelection(
    currentMode: Int,
    onModeSelected: (Int) -> Unit
) {
    val options = listOf(
        0 to "Normal",
        1 to "Skin Mask",
        2 to "Warp Offset",
        3 to "BigEye Radius",
        4 to "ThinFace Radius",
        5 to "All Warp"
    )

    Text(
        text = stringResource(R.string.shader_debug_mode),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 0.dp)
    )

    CompactOptionChips(
        options = options,
        currentValue = currentMode,
        maxLines = 2,
        onSelected = onModeSelected
    )
}

@Preview(showBackground = true)
@Composable
fun SettingsScreenPreview() {
    PoLangTheme {
        SettingsContent(
            category = SettingsCategory.MAIN,
            themeMode = ThemeMode.SYSTEM,
            appLanguage = AppLanguage.ENGLISH,
            developerOptionsUnlocked = false,
            onUnlockDeveloperOptions = {},
            debugUiEnabled = true,
            showCameraInfoInPreview = false,
            showFaceDebugOverlay = false,
            showLogOverlay = false,
            faceDetectionLandmarkModeEnabled = true,
            adaptiveFaceDetectionIntervalEnabled = true,
            faceDetectIntervalProfile = FaceDetectIntervalProfile.BALANCED,
            debugShaderMode = 0,
            roiStageConfig = StageConfig.defaultRoi(),
            landmarkStageConfig = StageConfig.defaultLandmark(),
            onThemeModeSelected = {},
            onAppLanguageSelected = {},
            onDebugUiEnabledChange = {},
            onShowCameraInfoInPreviewChange = {},
            onShowFaceDebugOverlayChange = {},
            onShowLogOverlayChange = {},
            onFaceDetectionLandmarkModeEnabledChange = {},
            onAdaptiveFaceDetectionIntervalEnabledChange = {},
            onFaceDetectIntervalProfileSelected = {},
            onDebugShaderModeSelected = {},
            onRoiModelTypeSelected = {},
            onRoiDevicePreferenceSelected = {},
            onLandmarkModelTypeSelected = {},
            onLandmarkDevicePreferenceSelected = {},
            aiAgentMode = AiAgentMode.REMOTE,
            onAiAgentModeChange = {},
            aiAgentRemoteModelConfigs = "",
            onAiAgentRemoteModelConfigsChange = {},
            aiAgentSelectedRemoteModel = "deepseek-v4-flash",
            onAiAgentSelectedRemoteModelChange = {},
            autoExecutePlans = true,
            onAutoExecutePlansChange = {},
            tagGenerationUseOpencl = false,
            onTagGenerationUseOpenclChange = {},
            taggerModelKey = TaggerModelSelector.AUTO,
            onTaggerModelKeyChange = {},
            voiceCommandMode = VoiceCommandMode.DISABLED,
            onVoiceCommandModeChange = {},
            voiceEntryEnabled = false,
            onVoiceEntryEnabledChange = {},
            localAsrModel = "",
            onLocalAsrModelChange = {},
            localKwsModel = "",
            onLocalKwsModelChange = {},
            onNavigateToModelCenter = {},
            isModelDownloaded = { true },
            getModelId = { _, _ -> null },
            downloadModel = { _, _ -> },
            downloadStates = emptyMap(),
            allModels = emptyList(),
            logModuleConfig = LogModuleConfig.default(),
            onLogModuleConfigChange = {},
            onNavigateBack = {},
            onNavigateToDebug = {},
            onNavigateToJsBridge = {},
            onNavigateToSearchTest = {}
        )
    }
}
