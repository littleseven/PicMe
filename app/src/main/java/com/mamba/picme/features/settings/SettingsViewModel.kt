package com.mamba.picme.features.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mamba.picme.agent.core.model.config.AiAgentInferencePreference
import com.mamba.picme.agent.core.model.config.AiAgentMode
import com.mamba.picme.agent.core.model.config.AiAgentPrivacyLevel
import com.mamba.picme.agent.core.runtime.cache.L1CacheSettings
import com.mamba.picme.beauty.internal.facedetect.mnn.MnnFaceDetector
import com.mamba.picme.beauty.internal.facedetect.ncnn.NcnnFaceDetector
import com.mamba.picme.core.common.Logger
import com.mamba.picme.data.download.DownloadState
import com.mamba.picme.data.download.DownloadStatus
import com.mamba.picme.data.download.LlmModelDownloadManager
import com.mamba.picme.data.download.ModelConfig
import com.mamba.picme.domain.model.AppLanguage
import com.mamba.picme.domain.model.DetectionModelType
import com.mamba.picme.domain.model.DetectionStage
import com.mamba.picme.domain.model.FaceDetectIntervalProfile
import com.mamba.picme.domain.model.FaceDetectionEngineMode
import com.mamba.picme.domain.model.InferenceDevicePreference
import com.mamba.picme.domain.model.InferenceEngineType
import com.mamba.picme.domain.model.LogModule
import com.mamba.picme.domain.model.LogModuleConfig
import com.mamba.picme.domain.model.ModelCategory
import com.mamba.picme.domain.model.StageConfig
import com.mamba.picme.domain.model.TagTranslations
import com.mamba.picme.domain.model.ThemeMode
import com.mamba.picme.domain.model.VoiceCommandMode
import com.mamba.picme.domain.repository.UserSettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val repository: UserSettingsRepository,
    private val modelDownloadManager: LlmModelDownloadManager,
    private val appContext: Context
) : ViewModel() {

    companion object {
        private const val TAG = "Settings"

// 必要模型（LLM + ASR + KWS），检测到缺少时提示一键下载
// 仅保留核心模型以节省用户首次进入时间
private val ESSENTIAL_MODEL_IDS = listOf(
    "qwen3_5_2b", // 下划线格式，与 ModelManager 注册表一致
    "sherpa-onnx-zipformer-zh-en", // ASR 模型
    "sherpa-onnx-kws-zipformer-wenetspeech" // KWS 唤醒词模型
)
    }

    val themeMode: StateFlow<ThemeMode> = repository.themeModeFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ThemeMode.SYSTEM
        )

    val appLanguage: StateFlow<AppLanguage> = repository.appLanguageFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AppLanguage.SYSTEM
        )



    val debugUiEnabled: StateFlow<Boolean> = repository.debugUiEnabledFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = true
        )

    val faceDetectionEngineMode: StateFlow<FaceDetectionEngineMode> = repository.faceDetectionEngineModeFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = FaceDetectionEngineMode.MEDIAPIPE
        )

    val faceDetectionLandmarkModeEnabled: StateFlow<Boolean> = repository.faceDetectionLandmarkModeFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = true
        )

    val adaptiveFaceDetectionIntervalEnabled: StateFlow<Boolean> = repository.adaptiveFaceDetectionIntervalEnabledFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = true
        )

    val faceDetectIntervalProfile: StateFlow<FaceDetectIntervalProfile> = repository.faceDetectIntervalProfileFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = FaceDetectIntervalProfile.BALANCED
        )

    val showCameraInfoInPreview: StateFlow<Boolean> = repository.showCameraInfoInPreviewFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    val showFaceDebugOverlay: StateFlow<Boolean> = repository.showFaceDebugOverlayFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    val showLogOverlay: StateFlow<Boolean> = repository.showLogOverlayFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    // Shader debug mode (not persisted, defaults to 0)
    private val _debugShaderMode = MutableStateFlow(0)
    val debugShaderMode: StateFlow<Int> = _debugShaderMode

    // ── 阶段独立配置（ROI / Landmark）────────────────────────
    val roiStageConfig: StateFlow<StageConfig> = repository.roiStageConfigFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = StageConfig.defaultRoi()
        )

    val landmarkStageConfig: StateFlow<StageConfig> = repository.landmarkStageConfigFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = StageConfig.defaultLandmark()
        )

    val aiAgentRemoteModelConfigs: StateFlow<String> = repository.aiAgentRemoteModelConfigsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ""
        )

    val aiAgentSelectedRemoteModel: StateFlow<String> = repository.aiAgentSelectedRemoteModelFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = "deepseek-v4-flash"
        )

    val aiAgentInferencePreference: StateFlow<AiAgentInferencePreference> = repository.aiAgentInferencePreferenceFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AiAgentInferencePreference.FORCE_LOCAL
        )

    val aiAgentL1CacheEnabled: StateFlow<Boolean> = repository.aiAgentL1CacheEnabledFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = true
        )

    val aiAgentMode: StateFlow<AiAgentMode> = repository.aiAgentModeFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AiAgentMode.LOCAL
        )

    val aiAgentPrivacyLevel: StateFlow<AiAgentPrivacyLevel> = repository.aiAgentPrivacyLevelFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AiAgentPrivacyLevel.STRICT
        )

    val aiAgentLocalModel: StateFlow<String> = repository.aiAgentLocalModelFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ""
        )

    val aiAgentLocalUseOpencl: StateFlow<Boolean> = repository.aiAgentLocalUseOpenclFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    val tagGenerationUseOpencl: StateFlow<Boolean> = repository.tagGenerationUseOpencl
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    val voiceCommandMode: StateFlow<VoiceCommandMode> = repository.voiceCommandModeFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = VoiceCommandMode.DISABLED
        )

    val localAsrModel: StateFlow<String> = repository.localAsrModelFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ""
        )

    val localKwsModel: StateFlow<String> = repository.localKwsModelFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ""
        )

    // ── 飞书远程控制 ───────────────────────────────────────
    val feishuAppId: StateFlow<String> = repository.feishuAppIdFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ""
        )

    val feishuAppSecret: StateFlow<String> = repository.feishuAppSecretFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ""
        )

    val logModuleConfig: StateFlow<LogModuleConfig> = repository.logModuleConfigFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = LogModuleConfig.default()
        )

    // 模型管理相关 Flow
    private val _allModels = MutableStateFlow<List<ModelConfig>>(emptyList())
    val allModels: StateFlow<List<ModelConfig>> = _allModels.asStateFlow()

    private val _downloadedModels = MutableStateFlow<List<ModelConfig>>(emptyList())
    val downloadedModels: StateFlow<List<ModelConfig>> = _downloadedModels.asStateFlow()

    private val _groupedModels = MutableStateFlow<Map<ModelCategory, List<ModelConfig>>>(emptyMap())
    val groupedModels: StateFlow<Map<ModelCategory, List<ModelConfig>>> = _groupedModels.asStateFlow()

    private val _tagTranslations = MutableStateFlow<TagTranslations>(emptyMap())
    val tagTranslations: StateFlow<TagTranslations> = _tagTranslations.asStateFlow()

    private val _categories = MutableStateFlow<List<ModelCategory>>(emptyList())
    val categories: StateFlow<List<ModelCategory>> = _categories.asStateFlow()

    private val _currentTab = MutableStateFlow(ModelCategory.ALL)
    val currentTab: StateFlow<ModelCategory> = _currentTab.asStateFlow()

    // ── 模型下载状态（共享给 UI 层实时监听）───────────────────
    val downloadStates: StateFlow<Map<String, DownloadState>> = modelDownloadManager.downloadStates

    // 模型 ID 到 DetectionModelType 的映射
    // Det10G 和 Det500M 都是 ROI 检测模型，共享 DET_500M_MNN/DET_500M_NCNN 类型
    // isModelDownloaded 需检查所有映射 ID 以兼容两种模型
    private val modelIdToDetectionType = mapOf(
        "face-det-retina10g-mnn" to DetectionModelType.DET_500M_MNN,
        "face-det-retina10g-ncnn" to DetectionModelType.DET_500M_NCNN,
        "face-det-retina500m-mnn" to DetectionModelType.DET_500M_MNN,
        "face-det-retina500m-ncnn" to DetectionModelType.DET_500M_NCNN,
        "face-landmark-2d106-mnn" to DetectionModelType.FACE_2D106_MNN,
        "face-landmark-2d106-ncnn" to DetectionModelType.FACE_2D106_NCNN
    )

    /**
     * 仅在状态从非 COMPLETED 过渡到 COMPLETED 时触发自动切换，
     * 避免下载状态频繁更新导致重复切换（表现为 UI 选项来回跳动）。
     */
    private val lastDownloadStatuses = mutableMapOf<String, DownloadStatus>()

    // ── 必要模型一键下载 ────────────────────────────
    private val _showEssentialModelsPrompt = MutableStateFlow(false)
    val showEssentialModelsPrompt: StateFlow<Boolean> = _showEssentialModelsPrompt.asStateFlow()

    private val _isBatchDownloading = MutableStateFlow(false)
    val isBatchDownloading: StateFlow<Boolean> = _isBatchDownloading.asStateFlow()

    /**
     * 检查必要模型是否缺失，若缺失则提示一键下载。
     * 由 CameraScreen 在进入相机 3 秒后调用，避免应用启动时立即打扰用户。
     */
    fun checkEssentialModels() {
        if (_isBatchDownloading.value || _showEssentialModelsPrompt.value) return
        viewModelScope.launch {
            val missingAny = ESSENTIAL_MODEL_IDS.any { id ->
                !modelDownloadManager.isModelDownloaded(id)
            }
            if (missingAny) {
                Logger.i(TAG, "Essential models missing, showing download prompt")
                _showEssentialModelsPrompt.value = true
            }
        }
    }

    /**
     * 一键下载：按顺序先下载所有 LLM 模型，再下载 ASR 模型。
     */
    fun startBatchDownload() {
        if (_isBatchDownloading.value) return
        _isBatchDownloading.value = true
        _showEssentialModelsPrompt.value = false
        viewModelScope.launch {
            try {
                // Step 1: 下载所有未下载的 LLM 模型（排除 ASR 和 KWS）
                val llmIds = ESSENTIAL_MODEL_IDS.filter {
                    it != "sherpa-onnx-zipformer-zh-en" && it != "sherpa-onnx-kws-zipformer-wenetspeech"
                }
                for (modelId in llmIds) {
                    if (!modelDownloadManager.isModelDownloaded(modelId)) {
                        val config = _allModels.value.find { it.id == modelId }
                        if (config != null) {
                            Logger.i(TAG, "Batch: downloading LLM model $modelId")
                            modelDownloadManager.enqueueDownload(modelId, config)
                            // 等待下载完成
                            modelDownloadManager.downloadStates.first { states ->
                                states[modelId]?.status == DownloadStatus.COMPLETED ||
                                    states[modelId]?.status == DownloadStatus.FAILED
                            }
                        }
                    }
                }

                // Step 2: 下载 ASR 模型
                val asrId = "sherpa-onnx-zipformer-zh-en"
                if (!modelDownloadManager.isModelDownloaded(asrId)) {
                    val config = _allModels.value.find { it.id == asrId }
                    if (config != null) {
                        Logger.i(TAG, "Batch: downloading ASR model $asrId")
                        modelDownloadManager.enqueueDownload(asrId, config)
                        modelDownloadManager.downloadStates.first { states ->
                            states[asrId]?.status == DownloadStatus.COMPLETED ||
                                states[asrId]?.status == DownloadStatus.FAILED
                        }
                    }
                }

                // Step 3: 下载 KWS 唤醒词模型
                val kwsId = "sherpa-onnx-kws-zipformer-wenetspeech"
                if (!modelDownloadManager.isModelDownloaded(kwsId)) {
                    val config = _allModels.value.find { it.id == kwsId }
                    if (config != null) {
                        Logger.i(TAG, "Batch: downloading KWS model $kwsId")
                        modelDownloadManager.enqueueDownload(kwsId, config)
                        modelDownloadManager.downloadStates.first { states ->
                            states[kwsId]?.status == DownloadStatus.COMPLETED ||
                                states[kwsId]?.status == DownloadStatus.FAILED
                        }
                    }
                }

                Logger.i(TAG, "Batch download complete")
            } catch (e: Exception) {
                Logger.e(TAG, "Batch download failed", e)
            } finally {
                _isBatchDownloading.value = false
            }
        }
    }

    /**
     * 关闭下载提示弹窗
     */
    fun dismissDownloadPrompt() {
        _showEssentialModelsPrompt.value = false
    }

    init {
        lastDownloadStatuses.putAll(downloadStates.value.mapValues { entry -> entry.value.status })
        loadModels()
        observeDownloadCompletion()
        syncL1CacheSetting()
    }

    private fun syncL1CacheSetting() {
        viewModelScope.launch {
            try {
                val enabled = repository.aiAgentL1CacheEnabledFlow.first()
                L1CacheSettings.setEnabled(enabled)
            } catch (e: Exception) {
                Logger.e("Settings", "Failed to sync L1 cache setting", e)
            }
        }
    }

    /**
     * 监听下载完成事件，自动切换对应阶段的模型
     */
    private fun observeDownloadCompletion() {
        viewModelScope.launch {
            downloadStates.collect { states ->
                lastDownloadStatuses.keys.retainAll(states.keys)

                states.forEach { (modelId, state) ->
                    val previousStatus = lastDownloadStatuses[modelId]
                    val justCompleted = state.status == DownloadStatus.COMPLETED &&
                        previousStatus != DownloadStatus.COMPLETED

                    if (justCompleted) {
                        // 刷新已下载模型列表，确保 UI 计数（如必须模型缺失数）同步更新
                        _downloadedModels.value = modelDownloadManager.getDownloadedModels()

                        val modelType = modelIdToDetectionType[modelId]
                        if (modelType != null) {
                            when {
                                modelType.isRoiModel() && roiStageConfig.value.modelType != modelType -> {
                                    Logger.i("Settings", "Auto-switching ROI model to $modelType after download")
                                    setRoiModelType(modelType)
                                }
                                modelType.isLandmarkModel() && landmarkStageConfig.value.modelType != modelType -> {
                                    Logger.i("Settings", "Auto-switching Landmark model to $modelType after download")
                                    setLandmarkModelType(modelType)
                                }
                            }
                        }
                    }

                    lastDownloadStatuses[modelId] = state.status
                }
            }
        }
    }

    /**
     * 检查模型是否已下载（MediaPipe 视为始终已下载）
     *
     * 一个 DetectionModelType 可能对应多个模型 ID（如 Det10G 和 Det500M 都映射到 DET_500M_MNN），
     * 只要有任意一个模型已下载即为可用。
     */
    fun isModelDownloaded(modelType: DetectionModelType): Boolean {
        if (modelType == DetectionModelType.MEDIAPIPE) return true
        return modelIdToDetectionType
            .filter { it.value == modelType }
            .keys
            .any { modelDownloadManager.isModelDownloaded(it) }
    }

    /**
     * 根据 stage 获取对应的模型 ID
     *
     * 同一 DetectionModelType 可能对应 Det10G 和 Det500M 两种模型，
     * 优先返回已下载的模型；若都未下载，默认返回 500M。
     */
    fun getModelId(modelType: DetectionModelType, stage: DetectionStage): String? {
        return when (stage) {
            DetectionStage.ROI -> when (modelType) {
                DetectionModelType.DET_500M_MNN -> {
                    if (modelDownloadManager.isModelDownloaded("face-det-retina500m-mnn")) {
                        "face-det-retina500m-mnn"
                    } else if (modelDownloadManager.isModelDownloaded("face-det-retina10g-mnn")) {
                        "face-det-retina10g-mnn"
                    } else {
                        "face-det-retina500m-mnn"
                    }
                }
                DetectionModelType.DET_500M_NCNN -> {
                    if (modelDownloadManager.isModelDownloaded("face-det-retina500m-ncnn")) {
                        "face-det-retina500m-ncnn"
                    } else if (modelDownloadManager.isModelDownloaded("face-det-retina10g-ncnn")) {
                        "face-det-retina10g-ncnn"
                    } else {
                        "face-det-retina500m-ncnn"
                    }
                }
                else -> null
            }
            DetectionStage.LANDMARK -> when (modelType) {
                DetectionModelType.FACE_2D106_MNN -> "face-landmark-2d106-mnn"
                DetectionModelType.FACE_2D106_NCNN -> "face-landmark-2d106-ncnn"
                else -> null
            }
        }
    }

    /**
     * 触发模型下载
     */
    fun downloadModel(modelId: String, modelConfig: ModelConfig) {
        modelDownloadManager.enqueueDownload(modelId, modelConfig)
    }

    /**
     * 一键下载所有未下载的必须模型，按顺序加入下载队列
     */
    fun downloadAllRequiredModels() {
        viewModelScope.launch {
            val requiredModels = _allModels.value.filter { it.isRequired }
            val downloadedIds = modelDownloadManager.getDownloadedModels().map { it.id }.toSet()
            val missingModels = requiredModels.filter { it.id !in downloadedIds }
            Logger.i("Settings", "Batch downloading ${missingModels.size} required models")
            missingModels.forEach { model ->
                modelDownloadManager.enqueueDownload(model.id, model)
            }
        }
    }

    fun resumeModelDownload(modelId: String, modelConfig: ModelConfig) {
        modelDownloadManager.enqueueResume(modelId, modelConfig)
    }

    fun pauseModelDownload(modelId: String) {
        modelDownloadManager.pauseDownload(modelId)
    }

    fun cancelModelDownload(modelId: String) {
        modelDownloadManager.cancelDownload(modelId)
    }

    suspend fun deleteDownloadedModel(modelId: String): Boolean {
        return modelDownloadManager.deleteModel(modelId)
    }

    private fun loadModels() {
        viewModelScope.launch {
            try {
                val marketData = modelDownloadManager.loadMarketData()
                _allModels.value = marketData.models
                _tagTranslations.value = marketData.tagTranslations

                val grouped = marketData.groupByCategory()
                _groupedModels.value = grouped
                _categories.value = grouped.keys.toList()

                // 默认选中第一个分类
                if (_currentTab.value == ModelCategory.ALL && grouped.isNotEmpty()) {
                    _currentTab.value = grouped.keys.first()
                }

                val downloaded = modelDownloadManager.getDownloadedModels()
                _downloadedModels.value = downloaded

                Logger.i("Settings", "Loaded ${marketData.models.size} models, " +
                    "categories: ${grouped.keys.map { it.tag }}")
            } catch (e: Exception) {
                Logger.e("Settings", "Failed to load models", e)
            }
        }
    }

    /**
     * 切换 Tab
     */
    fun switchTab(tab: ModelCategory) {
        _currentTab.value = tab
    }

    /**
     * 获取当前 Tab 对应的模型列表
     */
    fun getCurrentTabModels(): List<ModelConfig> {
        return _groupedModels.value[_currentTab.value] ?: emptyList()
    }

    /**
     * 获取所有模型分类标签（用于 TabRow）
     * 返回 Map<分类标签, 中文翻译>
     */
    fun getModelTypeLabels(): Map<ModelCategory, String> {
        val translations = _tagTranslations.value
        return _categories.value.associateWith { category ->
            translations[category.tag] ?: category.tag
        }
    }

    /**
     * 刷新模型列表（强制从网络获取）
     */
    fun refreshModels() {
        viewModelScope.launch {
            try {
                val marketData = modelDownloadManager.refreshMarketData()
                _allModels.value = marketData.models
                _tagTranslations.value = marketData.tagTranslations

                val grouped = marketData.groupByCategory()
                _groupedModels.value = grouped
                _categories.value = grouped.keys.toList()

                if (_currentTab.value !in grouped.keys && grouped.isNotEmpty()) {
                    _currentTab.value = grouped.keys.first()
                }

                val downloaded = modelDownloadManager.getDownloadedModels()
                _downloadedModels.value = downloaded

                Logger.i("Settings", "Refreshed ${marketData.models.size} models")
            } catch (e: Exception) {
                Logger.e("Settings", "Failed to refresh models", e)
            }
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            repository.updateThemeMode(mode)
        }
    }

    fun setAppLanguage(language: AppLanguage) {
        viewModelScope.launch {
            repository.updateAppLanguage(language)
        }
    }



    fun setDebugUiEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateDebugUiEnabled(enabled)
            // 关闭总开关时,同时关闭所有子选项
            if (!enabled) {
                repository.updateShowCameraInfoInPreview(false)
                repository.updateShowFaceDebugOverlay(false)
                repository.updateShowLogOverlay(false)
            }
        }
    }

    fun setFaceDetectionEngineMode(mode: FaceDetectionEngineMode) {
        viewModelScope.launch {
            Logger.d("UX", "Face detection engine mode changed: ${mode.name}")
            repository.updateFaceDetectionEngineMode(mode)

            if (mode != FaceDetectionEngineMode.CUSTOM) {
                val (roiConfig, landmarkConfig) = mode.toStageConfigs()
                repository.updateRoiStageConfig(roiConfig)
                repository.updateLandmarkStageConfig(landmarkConfig)
                Logger.d("UX", "Auto-updated StageConfig for $mode")
            }
        }
    }

    private fun FaceDetectionEngineMode.toStageConfigs(): Pair<StageConfig, StageConfig> = when (this) {
        FaceDetectionEngineMode.MEDIAPIPE -> Pair(
            StageConfig(DetectionStage.ROI, DetectionModelType.MEDIAPIPE, InferenceEngineType.TFLITE, InferenceDevicePreference.AUTO),
            StageConfig(DetectionStage.LANDMARK, DetectionModelType.MEDIAPIPE, InferenceEngineType.TFLITE, InferenceDevicePreference.AUTO)
        )
        FaceDetectionEngineMode.MNN -> Pair(
            StageConfig(DetectionStage.ROI, DetectionModelType.DET_500M_MNN, InferenceEngineType.MNN, InferenceDevicePreference.AUTO),
            StageConfig(DetectionStage.LANDMARK, DetectionModelType.FACE_2D106_MNN, InferenceEngineType.MNN, InferenceDevicePreference.AUTO)
        )
        FaceDetectionEngineMode.NCNN -> Pair(
            StageConfig(DetectionStage.ROI, DetectionModelType.DET_500M_NCNN, InferenceEngineType.NCNN, InferenceDevicePreference.AUTO),
            StageConfig(DetectionStage.LANDMARK, DetectionModelType.FACE_2D106_NCNN, InferenceEngineType.NCNN, InferenceDevicePreference.AUTO)
        )
        FaceDetectionEngineMode.CUSTOM -> Pair(
            StageConfig.defaultRoi(),
            StageConfig.defaultLandmark()
        )
    }

    fun setFaceDetectionLandmarkModeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateFaceDetectionLandmarkMode(enabled)
        }
    }

    fun setAdaptiveFaceDetectionIntervalEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateAdaptiveFaceDetectionIntervalEnabled(enabled)
        }
    }

    fun setFaceDetectIntervalProfile(profile: FaceDetectIntervalProfile) {
        viewModelScope.launch {
            repository.updateFaceDetectIntervalProfile(profile)
        }
    }

    fun setShowCameraInfoInPreview(show: Boolean) {
        viewModelScope.launch {
            repository.updateShowCameraInfoInPreview(show)
        }
    }

    fun setShowFaceDebugOverlay(show: Boolean) {
        viewModelScope.launch {
            repository.updateShowFaceDebugOverlay(show)
        }
    }

    fun setShowLogOverlay(show: Boolean) {
        viewModelScope.launch {
            repository.updateShowLogOverlay(show)
        }
    }

    fun setDebugShaderMode(mode: Int) {
        _debugShaderMode.value = mode
        // 通知 BeautyRenderer 更新 debug mode
        viewModelScope.launch {
            repository.updateDebugShaderMode(mode)
        }
    }

    // ── 阶段独立配置方法 ────────────────────────────────────
    fun setRoiModelType(modelType: DetectionModelType) {
        viewModelScope.launch {
            val current = roiStageConfig.value
            // 模型与引擎绑定，切换模型时自动同步引擎
            val updated = current.copy(
                modelType = modelType,
                engineType = modelType.toEngineType()
            )
            Logger.d("UX", "ROI model type changed: ${modelType.name}, engine auto-synced to ${modelType.toEngineType().name}")
            repository.updateRoiStageConfig(updated)
        }
    }

    fun setRoiDevicePreference(preference: InferenceDevicePreference) {
        viewModelScope.launch {
            val current = roiStageConfig.value
            val updated = current.copy(devicePreference = preference)
            Logger.d("UX", "ROI device preference changed: ${preference.name}")
            repository.updateRoiStageConfig(updated)
        }
    }

    fun setLandmarkModelType(modelType: DetectionModelType) {
        viewModelScope.launch {
            val current = landmarkStageConfig.value
            // 模型与引擎绑定，切换模型时自动同步引擎
            val updated = current.copy(
                modelType = modelType,
                engineType = modelType.toEngineType()
            )
            Logger.d("UX", "Landmark model type changed: ${modelType.name}, engine auto-synced to ${modelType.toEngineType().name}")
            repository.updateLandmarkStageConfig(updated)
        }
    }

    fun setLandmarkDevicePreference(preference: InferenceDevicePreference) {
        viewModelScope.launch {
            val current = landmarkStageConfig.value
            val updated = current.copy(devicePreference = preference)
            Logger.d("UX", "Landmark device preference changed: ${preference.name}")
            repository.updateLandmarkStageConfig(updated)
        }
    }

    fun setAiAgentMode(mode: AiAgentMode) {
        viewModelScope.launch {
            repository.updateAiAgentMode(mode)
        }
    }

    fun setAiAgentPrivacyLevel(level: AiAgentPrivacyLevel) {
        viewModelScope.launch {
            Logger.d("UX", "AI Agent privacy level changed: ${level.name}")
            repository.updateAiAgentPrivacyLevel(level)
        }
    }

    fun setAiAgentLocalModel(modelId: String) {
        viewModelScope.launch {
            repository.updateAiAgentLocalModel(modelId)
        }
    }

    fun setAiAgentLocalUseOpencl(enabled: Boolean) {
        viewModelScope.launch {
            Logger.d("UX", "AI Agent local OpenCL changed: $enabled")
            repository.updateAiAgentLocalUseOpencl(enabled)
        }
    }

    fun setTagGenerationUseOpencl(enabled: Boolean) {
        viewModelScope.launch {
            Logger.d("UX", "TAG generation OpenCL changed: $enabled")
            repository.updateTagGenerationUseOpencl(enabled)
        }
    }

    fun setAiAgentRemoteModelConfigs(configsJson: String) {
        viewModelScope.launch {
            repository.updateAiAgentRemoteModelConfigs(configsJson)
        }
    }

    fun setAiAgentSelectedRemoteModel(modelId: String) {
        viewModelScope.launch {
            repository.updateAiAgentSelectedRemoteModel(modelId)
        }
    }

    fun setAiAgentInferencePreference(preference: AiAgentInferencePreference) {
        viewModelScope.launch {
            Logger.d("UX", "AI Agent inference preference changed: ${preference.name}")
            repository.updateAiAgentInferencePreference(preference)
        }
    }

    fun setAiAgentL1CacheEnabled(enabled: Boolean) {
        viewModelScope.launch {
            Logger.d("UX", "AI Agent L1 cache enabled changed: $enabled")
            repository.updateAiAgentL1CacheEnabled(enabled)
            L1CacheSettings.setEnabled(enabled)
        }
    }

    fun setVoiceCommandMode(mode: VoiceCommandMode) {
        viewModelScope.launch {
            Logger.d("UX", "Voice command mode changed: ${mode.name}")
            repository.updateVoiceCommandMode(mode)
        }
    }

    fun setLocalAsrModel(modelId: String) {
        viewModelScope.launch {
            repository.updateLocalAsrModel(modelId)
        }
    }

    fun setLocalKwsModel(modelId: String) {
        viewModelScope.launch {
            repository.updateLocalKwsModel(modelId)
        }
    }

    fun resetCameraMemoryState() {
        viewModelScope.launch {
            repository.resetCameraMemoryState()
        }
    }

    fun setLogModuleConfig(config: LogModuleConfig) {
        // 同步更新内存中的 Logger 配置，使开关立即生效
        Logger.setModuleConfig(config)
        // 同步 C++ 层的人脸检测日志开关（静态全局开关，影响所有 native 实例）
        val faceDetectionEnabled = config.isEnabled(LogModule.FACE_DETECTION)
        MnnFaceDetector.setNativeLogEnabled(faceDetectionEnabled)
        NcnnFaceDetector.setNativeLogEnabled(faceDetectionEnabled)
        viewModelScope.launch {
            repository.updateLogModuleConfig(config)
        }
    }

    // ── 飞书远程控制 ────────────────────────────────────────
    fun setFeishuAppId(appId: String) {
        viewModelScope.launch {
            repository.updateFeishuAppId(appId)
        }
    }

    fun setFeishuAppSecret(appSecret: String) {
        viewModelScope.launch {
            repository.updateFeishuAppSecret(appSecret)
        }
    }
}

class SettingsViewModelFactory(
    private val repository: UserSettingsRepository,
    private val modelDownloadManager: LlmModelDownloadManager,
    private val appContext: Context
) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return SettingsViewModel(repository, modelDownloadManager, appContext) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
