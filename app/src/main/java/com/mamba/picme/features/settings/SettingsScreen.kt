package com.mamba.picme.features.settings

import android.app.Activity
import android.content.Intent
import android.provider.Settings
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mamba.picme.BuildConfig
import com.mamba.picme.R
import com.mamba.picme.agent.core.model.config.AiAgentInferencePreference
import com.mamba.picme.agent.core.model.config.AiAgentMode
import com.mamba.picme.agent.core.tool.accessibility.AccessibilityServiceHolder
import com.mamba.picme.core.common.Logger
import com.mamba.picme.core.designsystem.PicMeTheme
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
import com.mamba.picme.domain.model.VoiceCommandMode
import com.mamba.picme.features.common.chat.rememberAgentChatConfig
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

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    category: SettingsCategory = SettingsCategory.MAIN,
    onNavigateBack: () -> Unit,
    onNavigateToModelCenter: (String) -> Unit = {},
    onNavigateToTagControl: () -> Unit = {},
    onNavigateToDebug: () -> Unit = {},
    onNavigateToSearchTest: () -> Unit = {},
    onNavigateToCategory: (SettingsCategory) -> Unit = {},
    onNavigateToDataPrivacy: () -> Unit = {}
) {
    val view = LocalView.current
    val context = LocalContext.current

    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window ?: return@DisposableEffect onDispose {}
        val insetsController = WindowCompat.getInsetsController(window, view)

        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        onDispose {
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    val themeMode by viewModel.themeMode.collectAsState()
    val appLanguage by viewModel.appLanguage.collectAsState()
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
    val aiAgentLocalModel by viewModel.aiAgentLocalModel.collectAsState()
    val aiAgentRemoteModelConfigs by viewModel.aiAgentRemoteModelConfigs.collectAsState()
    val aiAgentSelectedRemoteModel by viewModel.aiAgentSelectedRemoteModel.collectAsState()
    val aiAgentInferencePreference by viewModel.aiAgentInferencePreference.collectAsState()
    val aiAgentL1CacheEnabled by viewModel.aiAgentL1CacheEnabled.collectAsState()
    val aiAgentLocalUseOpencl by viewModel.aiAgentLocalUseOpencl.collectAsState()
    val tagGenerationUseOpencl by viewModel.tagGenerationUseOpencl.collectAsState()
    val voiceCommandMode by viewModel.voiceCommandMode.collectAsState()
    val localAsrModel by viewModel.localAsrModel.collectAsState()
    val localKwsModel by viewModel.localKwsModel.collectAsState()
    val logModuleConfig by viewModel.logModuleConfig.collectAsState()
    val downloadStates by viewModel.downloadStates.collectAsState()
    val allModels by viewModel.allModels.collectAsState()

    // 飞书远程控制
    val feishuAppId by viewModel.feishuAppId.collectAsState()
    val feishuAppSecret by viewModel.feishuAppSecret.collectAsState()

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
                        if (enabled) AiAgentMode.LOCAL else AiAgentMode.OFF
                    )
                    "agent_local_opencl" -> viewModel.setAiAgentLocalUseOpencl(enabled)
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
            aiAgentLocalModel = aiAgentLocalModel,
            onAiAgentLocalModelChange = { viewModel.setAiAgentLocalModel(it) },
            aiAgentRemoteModelConfigs = aiAgentRemoteModelConfigs,
            onAiAgentRemoteModelConfigsChange = { viewModel.setAiAgentRemoteModelConfigs(it) },
            aiAgentSelectedRemoteModel = aiAgentSelectedRemoteModel,
            onAiAgentSelectedRemoteModelChange = { viewModel.setAiAgentSelectedRemoteModel(it) },
            aiAgentInferencePreference = aiAgentInferencePreference,
            onAiAgentInferencePreferenceChange = { viewModel.setAiAgentInferencePreference(it) },
            aiAgentL1CacheEnabled = aiAgentL1CacheEnabled,
            onAiAgentL1CacheEnabledChange = { viewModel.setAiAgentL1CacheEnabled(it) },
            aiAgentLocalUseOpencl = aiAgentLocalUseOpencl,
            onAiAgentLocalUseOpenclChange = { viewModel.setAiAgentLocalUseOpencl(it) },
            tagGenerationUseOpencl = tagGenerationUseOpencl,
            onTagGenerationUseOpenclChange = { viewModel.setTagGenerationUseOpencl(it) },
            voiceCommandMode = voiceCommandMode,
            onVoiceCommandModeChange = { viewModel.setVoiceCommandMode(it) },
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
            feishuAppId = feishuAppId,
            feishuAppSecret = feishuAppSecret,
            onFeishuAppIdChange = viewModel::setFeishuAppId,
            onFeishuAppSecretChange = viewModel::setFeishuAppSecret,
            onNavigateBack = onNavigateBack,
            onNavigateToTagControl = onNavigateToTagControl,
            onNavigateToDebug = onNavigateToDebug,
            onNavigateToSearchTest = onNavigateToSearchTest,
            onNavigateToDataPrivacy = onNavigateToDataPrivacy
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsContent(
    category: SettingsCategory,
    themeMode: ThemeMode,
    appLanguage: AppLanguage,
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
    aiAgentLocalModel: String,
    onAiAgentLocalModelChange: (String) -> Unit,
    aiAgentRemoteModelConfigs: String,
    onAiAgentRemoteModelConfigsChange: (String) -> Unit,
    aiAgentSelectedRemoteModel: String,
    onAiAgentSelectedRemoteModelChange: (String) -> Unit,
    aiAgentInferencePreference: AiAgentInferencePreference,
    onAiAgentInferencePreferenceChange: (AiAgentInferencePreference) -> Unit,
    aiAgentL1CacheEnabled: Boolean,
    onAiAgentL1CacheEnabledChange: (Boolean) -> Unit,
    aiAgentLocalUseOpencl: Boolean,
    onAiAgentLocalUseOpenclChange: (Boolean) -> Unit,
    tagGenerationUseOpencl: Boolean,
    onTagGenerationUseOpenclChange: (Boolean) -> Unit,
    voiceCommandMode: VoiceCommandMode,
    onVoiceCommandModeChange: (VoiceCommandMode) -> Unit,
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
    feishuAppId: String,
    feishuAppSecret: String,
    onFeishuAppIdChange: (String) -> Unit,
    onFeishuAppSecretChange: (String) -> Unit,
    onNavigateBack: () -> Unit,
    onNavigateToTagControl: () -> Unit = {},
    onNavigateToDebug: () -> Unit = {},
    onNavigateToSearchTest: () -> Unit = {},
    onNavigateToDataPrivacy: () -> Unit = {}
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
            TopAppBar(
                title = { Text(stringResource(titleRes)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
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
                    onNavigateToCategory = onNavigateToCategory,
                    onNavigateToModelCenter = { onNavigateToModelCenter("") }
                )
                return@Column
            }

            // ── 0. 账号 ────────────────────────────────────────────
            if (category == SettingsCategory.ACCOUNT) {
                SettingsSection(
                    title = stringResource(R.string.account),
                    description = stringResource(R.string.account_desc)
                ) {
                    ServerAuthSection(onNavigateToDataPrivacy = onNavigateToDataPrivacy)
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                    SettingsClickableRow(
                        title = stringResource(R.string.data_privacy_entry),
                        leadingIcon = Icons.Rounded.Person,
                        onClick = onNavigateToDataPrivacy,
                    )
                }
            }

            // ── 1. 个性化（主题与语言已迁移至设置页主菜单顶部）───

            // ── 2. AI 助手 ────────────────────────────────────────
            if (category == SettingsCategory.AI_AGENT) {
                SettingsSection(
                    title = stringResource(R.string.ai_agent),
                    description = stringResource(R.string.ai_agent_desc)
                ) {
                    // 模型中心作为 AI 助手卡片第一项
                    SettingsClickableRow(
                        title = stringResource(R.string.model_center),
                        subtitle = stringResource(R.string.model_center_desc),
                        leadingIcon = Icons.Rounded.SmartToy,
                        onClick = { onNavigateToModelCenter("") }
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )

                    var autoExecutePlans by remember { mutableStateOf(true) }
                    DebugOptionRow(
                        title = stringResource(R.string.ai_agent_auto_execute_plans),
                        checked = autoExecutePlans,
                        onCheckedChange = { autoExecutePlans = it }
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

                    // 推理偏好选择（仅 LOCAL 模式下显示）
                    if (aiAgentMode == AiAgentMode.LOCAL) {
                        InferencePreferenceSelection(
                            currentPreference = aiAgentInferencePreference,
                            onPreferenceSelected = onAiAgentInferencePreferenceChange
                        )
                        OpenClBackendSelection(
                            useOpencl = aiAgentLocalUseOpencl,
                            onToggle = onAiAgentLocalUseOpenclChange
                        )
                    }

                    when (aiAgentMode) {
                        AiAgentMode.OFF -> {
                            Text(
                                text = stringResource(R.string.ai_agent_mode_off),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.Gray,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                        AiAgentMode.LOCAL -> {
                            AiAgentLocalModelSection(
                                currentLocalModel = aiAgentLocalModel,
                                onLocalModelSelected = onAiAgentLocalModelChange,
                                onNavigateToModelManager = onNavigateToModelCenter
                            )
                        }
                        AiAgentMode.REMOTE, AiAgentMode.FEISHU -> {
                            AiAgentRemoteModelsSection(
                                configsJson = aiAgentRemoteModelConfigs,
                                onConfigsChange = onAiAgentRemoteModelConfigsChange,
                                selectedModelId = aiAgentSelectedRemoteModel,
                                onSelectedModelChange = onAiAgentSelectedRemoteModelChange
                            )
                        }
                    }

                    DebugOptionRow(
                        title = stringResource(R.string.ai_agent_l1_cache),
                        checked = aiAgentL1CacheEnabled,
                        onCheckedChange = onAiAgentL1CacheEnabledChange
                    )
                    Text(
                        text = stringResource(R.string.ai_agent_l1_cache_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                    )
                }

                SettingsSection(
                    title = stringResource(R.string.communication_channel),
                    description = stringResource(R.string.communication_channel_desc)
                ) {
                    Text(
                        text = stringResource(R.string.feishu_channel_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                    SettingsTextInputRow(
                        title = "App ID",
                        value = feishuAppId,
                        onValueChange = onFeishuAppIdChange,
                        placeholder = "飞书应用的 App ID"
                    )
                    SettingsTextInputRow(
                        title = "App Secret",
                        value = feishuAppSecret,
                        onValueChange = onFeishuAppSecretChange,
                        placeholder = "飞书应用的 App Secret",
                        isPassword = true
                    )
                }

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
                }

                if (BuildConfig.DEBUG) {
                    SettingsSection(
                        title = stringResource(R.string.gallery_debug_features),
                        description = stringResource(R.string.gallery_debug_features_desc)
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

                        Spacer(modifier = Modifier.height(8.dp))

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
            }

            // ── 4. 相机与美颜 ─────────────────────────────────────
            if (category == SettingsCategory.CAMERA_BEAUTY) {
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

                SettingsSection(
                    title = stringResource(R.string.face_detection_advanced),
                    description = stringResource(R.string.settings_face_detection_advanced_desc)
                ) {
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

                // ── AI 远程控制无障碍服务（仅 debug 构建） ───────────────
                if (BuildConfig.DEBUG) {
                    var isAccessibilityEnabled by remember {
                        mutableStateOf(AccessibilityServiceHolder.isActive())
                    }

                    LaunchedEffect(Unit) {
                        while (true) {
                            isAccessibilityEnabled = AccessibilityServiceHolder.isActive()
                            delay(1000)
                        }
                    }

                    SettingsSection(
                        title = stringResource(R.string.settings_accessibility_service_title),
                        description = stringResource(R.string.settings_accessibility_service_summary)
                    ) {
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

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = stringResource(R.string.log_management),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp)
                    )
                    LogModuleConfigSection(
                        config = logModuleConfig,
                        onConfigChange = onLogModuleConfigChange
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsMainMenu(
    themeMode: ThemeMode,
    onThemeModeSelected: (ThemeMode) -> Unit,
    appLanguage: AppLanguage,
    onAppLanguageSelected: (AppLanguage) -> Unit,
    onNavigateToCategory: (SettingsCategory) -> Unit,
    onNavigateToModelCenter: () -> Unit
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
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

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SettingsCategoryCard(
                title = stringResource(R.string.account),
                description = stringResource(R.string.account_desc),
                icon = Icons.Rounded.Person,
                modifier = Modifier.weight(1f),
                onClick = { onNavigateToCategory(SettingsCategory.ACCOUNT) }
            )
            SettingsCategoryCard(
                title = stringResource(R.string.ai_assistant),
                description = stringResource(R.string.ai_assistant_desc),
                icon = Icons.Rounded.SmartToy,
                modifier = Modifier.weight(1f),
                onClick = { onNavigateToCategory(SettingsCategory.AI_AGENT) }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SettingsCategoryCard(
                title = stringResource(R.string.gallery_features),
                description = stringResource(R.string.gallery_features_desc),
                icon = Icons.Rounded.PhotoLibrary,
                modifier = Modifier.weight(1f),
                onClick = { onNavigateToCategory(SettingsCategory.GALLERY) }
            )
            SettingsCategoryCard(
                title = stringResource(R.string.camera_and_beauty),
                description = stringResource(R.string.camera_and_beauty_desc),
                icon = Icons.Rounded.CameraAlt,
                modifier = Modifier.weight(1f),
                onClick = { onNavigateToCategory(SettingsCategory.CAMERA_BEAUTY) }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SettingsCategoryCard(
                title = stringResource(R.string.developer_options),
                description = stringResource(R.string.developer_options_desc),
                icon = Icons.Rounded.Terminal,
                modifier = Modifier.weight(1f),
                onClick = { onNavigateToCategory(SettingsCategory.DEVELOPER) }
            )
            SettingsCategoryCard(
                title = stringResource(R.string.model_center),
                description = stringResource(R.string.model_center_desc),
                icon = Icons.Rounded.CloudDownload,
                modifier = Modifier.weight(1f),
                onClick = onNavigateToModelCenter
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SettingsCategoryCard(
                title = stringResource(R.string.backup_and_restore),
                description = stringResource(R.string.backup_and_restore_desc),
                icon = Icons.Rounded.Storage,
                modifier = Modifier.weight(1f),
                onClick = { context.startActivity(BackupRestoreActivity.intent(context)) }
            )
            Spacer(modifier = Modifier.weight(1f))
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
private fun InferencePreferenceSelection(
    currentPreference: AiAgentInferencePreference,
    onPreferenceSelected: (AiAgentInferencePreference) -> Unit
) {
    val options = listOf(
        AiAgentInferencePreference.AUTO to stringResource(R.string.ai_agent_inference_auto),
        AiAgentInferencePreference.FORCE_LOCAL to stringResource(R.string.ai_agent_inference_force_local),
        AiAgentInferencePreference.FORCE_REMOTE to stringResource(R.string.ai_agent_inference_force_remote)
    )

    Text(
        text = stringResource(R.string.ai_agent_inference_preference),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 0.dp)
    )

    CompactOptionChips(
        options = options,
        currentValue = currentPreference,
        maxLines = 1,
        onSelected = onPreferenceSelected
    )

    Text(
        text = when (currentPreference) {
            AiAgentInferencePreference.AUTO -> stringResource(R.string.ai_agent_inference_auto_desc)
            AiAgentInferencePreference.FORCE_LOCAL -> stringResource(R.string.ai_agent_inference_force_local_desc)
            AiAgentInferencePreference.FORCE_REMOTE -> stringResource(R.string.ai_agent_inference_force_remote_desc)
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
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
    PicMeTheme {
        SettingsContent(
            category = SettingsCategory.MAIN,
            themeMode = ThemeMode.SYSTEM,
            appLanguage = AppLanguage.ENGLISH,
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
            aiAgentMode = AiAgentMode.LOCAL,
            onAiAgentModeChange = {},
            aiAgentLocalModel = "",
            onAiAgentLocalModelChange = {},
            aiAgentRemoteModelConfigs = "",
            onAiAgentRemoteModelConfigsChange = {},
            aiAgentSelectedRemoteModel = "deepseek-v4-flash",
            onAiAgentSelectedRemoteModelChange = {},
            aiAgentInferencePreference = AiAgentInferencePreference.FORCE_LOCAL,
            onAiAgentInferencePreferenceChange = {},
            aiAgentL1CacheEnabled = true,
            onAiAgentL1CacheEnabledChange = {},
            aiAgentLocalUseOpencl = false,
            onAiAgentLocalUseOpenclChange = {},
            tagGenerationUseOpencl = false,
            onTagGenerationUseOpenclChange = {},
            voiceCommandMode = VoiceCommandMode.DISABLED,
            onVoiceCommandModeChange = {},
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
            feishuAppId = "",
            feishuAppSecret = "",
            onFeishuAppIdChange = {},
            onFeishuAppSecretChange = {},
            onNavigateBack = {},
            onNavigateToDebug = {},
            onNavigateToSearchTest = {}
        )
    }
}
