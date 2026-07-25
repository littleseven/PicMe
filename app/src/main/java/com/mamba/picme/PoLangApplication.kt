package com.mamba.picme

import android.app.Activity
import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.mamba.picme.BuildConfig
import com.mamba.picme.agent.core.inference.remote.tool.PoLangToolService
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.core.common.Logger
import com.mamba.picme.core.image.CoilConfig
import com.mamba.picme.core.image.ThumbnailCache
import com.mamba.picme.data.local.AppDatabase
import com.mamba.picme.data.local.llmlog.RoomLlmCallRecorder
import com.mamba.picme.data.local.llmlog.RoomToolCallRecorder
import com.mamba.picme.data.download.DownloadStatus
import com.mamba.picme.data.local.ChatMessageEntity
import com.mamba.picme.data.local.ChatSessionEntity
import com.mamba.picme.di.AppContainer
import com.mamba.picme.di.AppContainerImpl
import com.mamba.picme.domain.model.ProviderConfigs
import com.mamba.picme.agent.core.remote.config.RemoteModelConfig
import com.mamba.picme.agent.core.remote.config.RemoteModelConfigs
import com.mamba.picme.agent.core.remote.config.RemoteModelFactory
import com.mamba.picme.core.identity.DeviceIdProvider
import com.mamba.picme.agent.core.model.config.AiAgentMode
import com.mamba.picme.agent.core.model.config.AiAgentPrivacyLevel
import com.mamba.picme.agent.core.model.config.AiAgentInferencePreference
import com.mamba.picme.agent.core.facade.AgentOrchestrator
import com.mamba.picme.agent.core.runtime.capability.CommandExecutor
import com.mamba.picme.agent.core.platform.logging.Logger as AgentCoreLogger
import com.mamba.picme.mnn.MnnResourceManager
import com.mamba.picme.domain.agent.capability.optimize.AiOptimizeCapability
import com.mamba.picme.features.chat.capability.ChatSearchCapability
import com.mamba.picme.features.gallery.capability.GalleryCapability
// 其他页面级 Capability 由各 Screen 自行创建
import com.mamba.picme.domain.agent.remote.FeishuChannelHandler
import com.mamba.picme.domain.agent.remote.FeishuPhotoTracker
import com.mamba.picme.domain.agent.remote.RemoteCommandDispatcher
import com.mamba.picme.domain.repository.MediaRepository
import com.mamba.picme.beauty.internal.facedetect.adapter.FaceLandmarkAdapterRegistry
import com.mamba.picme.beauty.log.BeautyLogProxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class PoLangApplication : Application(), ImageLoaderFactory {

    companion object {
        private const val TAG = "Application"
    }

    val applicationScope = CoroutineScope(SupervisorJob())

    lateinit var container: AppContainer
        private set

    /** 双级缩略图缓存：在 ImageLoader 创建前初始化，供 Coil Fetcher 和 Container 共用 */
    lateinit var thumbnailCache: ThumbnailCache
        private set

    val repository: MediaRepository
        get() = container.repository

    val feishuChannelHandler: FeishuChannelHandler by lazy { FeishuChannelHandler(applicationScope) }

    val remoteCommandDispatcher: RemoteCommandDispatcher by lazy {
        val database = AppDatabase.getDatabase(this)
        RemoteCommandDispatcher(
            feishuChannelHandler,
            this,
            database.chatMessageDao(),
            database.chatSessionDao()
        )
    }

    /**
     * 当前活跃的 Activity（用于测试截屏等场景）
     *
     * 通过 ActivityLifecycleCallbacks 自动跟踪，无需手动设置。
     */
    @Volatile
    var currentActivity: Activity? = null
        private set

    /**
     * 当前飞书消息处理 Job，用于新消息到达时取消旧任务
     * 防止多个 LLM 推理同时运行吃满 CPU 导致 ANR
     */
    @Volatile
    private var feishuDispatchJob: Job? = null

    override fun onCreate() {
        super.onCreate()

        // 显式指定 SLF4J Provider，绕过 SPI 扫描机制。
        // 必须在任何 SLF4J Logger 首次使用前设置，否则不生效。
        System.setProperty("slf4j.provider", "com.mamba.android.slf4j.AndroidSLF4JServiceProvider")

        // 预加载 Native 库（agent-core 模块依赖这些库，但 agent-core 不直接依赖 beauty-engine
        // 的 aar，因此需要在 Application 中统一加载，确保类加载器命名空间可见）
        loadNativeLibraries()

        thumbnailCache = ThumbnailCache(this)
        container = AppContainerImpl(this, thumbnailCache)

        // 注入媒体搜索引擎到 GalleryCapability（自然语言图片搜索）
        GalleryCapability.getInstance().searchEngine = container.mediaSearchEngine
        // 注入跨维度查询构建器（LLM 意图 → 多维度 Room 查询）
        GalleryCapability.getInstance().queryBuilder = container.queryBuilder

        // 初始化人脸关键点适配器注册表
        FaceLandmarkAdapterRegistry.initDefaults()

        // 绑定 Beauty Engine 日志代理，使 beauty-engine 模块的日志受 Logger 模块开关控制
        BeautyLogProxy.bindLogger(Logger)

        // 绑定 Agent Core 日志代理，使 agent-core 模块的日志受 Logger 模块开关控制
        AgentCoreLogger.setDelegate(object : AgentCoreLogger {
            override fun d(tag: String, message: String) = Logger.d(tag, message)
            override fun i(tag: String, message: String) = Logger.i(tag, message)
            override fun w(tag: String, message: String) = Logger.w(tag, message)
            override fun w(tag: String, message: String, throwable: Throwable) = Logger.w(tag, message, throwable)
            override fun e(tag: String, message: String, throwable: Throwable?) = Logger.e(tag, message, throwable)
            override fun isLogEnabled(tag: String): Boolean = Logger.isLogEnabled(tag)
        })

        // 从 DataStore 加载日志模块配置并同步到 Logger
        applicationScope.launch {
            val config = container.userPreferencesRepository.logModuleConfigFlow.first()
            Logger.setModuleConfig(config)
        }

        // 注册应用级 Capability（只注册一次，永不注销）
        initializeCapabilities()

        // 预配置 AgentOrchestrator 默认远程推理配置
        // gatewayToken 异步从 DataStore 加载（syncRemoteModelConfigToOrchestrator）
        AgentOrchestrator.getInstance(this).configure(
            mode = AiAgentMode.REMOTE,
            modelId = "qwen3_5_2b",
            privacyLevel = AiAgentPrivacyLevel.STRICT,
            remoteConfig = RemoteModelConfig.PICME_SERVER_DEFAULT
        )
        Logger.i(TAG, "Orchestrator pre-configured with fallback remote config")

        // 安装 LLM 调用 / tool 执行指标记录器（全构建注入）。
        // release 构建仅落纯指标（model/latency/tokens/success/errorMessage 等），
        // 绝不记录消息内容（隐私红线）；DEBUG 构建额外记录 request/response 全文。
        // runtime-core 的 RemoteModelFactory 创建远程模型时会自动挂上 CapturingChatModelListener，
        // CommandExecutor 汇聚全部 tool 执行并上报指标，均落库到独立 DB（polang_llm_log）。
        RemoteModelFactory.captureContent = BuildConfig.DEBUG
        RemoteModelFactory.recorder = RoomLlmCallRecorder(this)
        CommandExecutor.recorder = RoomToolCallRecorder(this)
        Logger.i(TAG, "LLM/tool call metrics recorder installed (captureContent=${BuildConfig.DEBUG})")

        // 注册 Activity 生命周期回调，跟踪当前活跃 Activity
        registerActivityLifecycleCallbacks(ActivityTracker())

        // 初始化飞书远程控制通道
        applicationScope.launch {
            try {
                val appId = container.userPreferencesRepository.feishuAppIdFlow.first()
                val appSecret = container.userPreferencesRepository.feishuAppSecretFlow.first()
                if (appId.isNotBlank() && appSecret.isNotBlank()) {
                    feishuChannelHandler.init(appId, appSecret)
                    // 绑定消息处理回调：飞书消息 → RemoteCommandDispatcher
                    // 前一个推理任务未完成时自动取消，防止多个 LLM 线程吃满 CPU
                    feishuChannelHandler.onMessageReceived = { text, messageId ->
                        feishuDispatchJob?.cancel()
                        feishuDispatchJob = applicationScope.launch {
                            remoteCommandDispatcher.dispatch(text, messageId)
                        }
                    }
                }
            } catch (e: Exception) {
                Logger.e(TAG, "飞书通道初始化失败", e)
            }
        }

        // 监听飞书配置变化，自动重连
        applicationScope.launch {
            try {
                combine(
                    container.userPreferencesRepository.feishuAppIdFlow,
                    container.userPreferencesRepository.feishuAppSecretFlow
                ) { appId, appSecret -> Pair(appId, appSecret) }
                    .drop(1) // 跳过初始值，避免重复 init
                    .collect { (appId, appSecret) ->
                        if (appId.isNotBlank() && appSecret.isNotBlank()) {
                            feishuChannelHandler.reinit(appId, appSecret)
                        } else {
                            feishuChannelHandler.disconnect()
                        }
                    }
            } catch (e: Exception) {
                Logger.e(TAG, "飞书配置监听失败", e)
            }
        }

        // 注册网络状态变化监听：网络恢复时自动重连飞书
        registerFeishuNetworkMonitor()

        // 同步 AI Agent 模式到 AgentOrchestrator，确保飞书远程控制
        // 的路由遵循用户在设置中选定的推理模式（LOCAL/REMOTE/OFF）
        syncAgentModeToOrchestrator()

        // 同步远程模型配置到 AgentOrchestrator，确保用户修改 API Token 后即时生效
        syncRemoteModelConfigToOrchestrator()

        // 监听媒体库变化：飞书远程拍照完成后自动发送照片到飞书
        observeFeishuPhotoCapture()

        // 预下载必须模型资源（已从 APK assets 迁移到 ModelScope 以减小包体积）
        prefetchEssentialModels()
    }

    /**
     * 后台静默预下载已从 APK 移除、迁移到 ModelScope 的必需模型。
     * 失败时保留 assets fallback，不影响功能。
     */
    private fun prefetchEssentialModels() {
        applicationScope.launch {
            try {
                val modelId = "mediapipe-face-landmarker"
                val modelFile = File(filesDir, "llm_models/$modelId/face_landmarker.task")
                if (modelFile.exists() && modelFile.length() > 0) {
                    Logger.i(TAG, "Essential model already exists: $modelId")
                    return@launch
                }
                Logger.i(TAG, "Prefetching essential model from ModelScope: $modelId")
                container.llmModelDownloadManager.downloadModel(modelId)
                    .catch { e -> Logger.w(TAG, "Failed to prefetch essential model: $modelId", e) }
                    .collect { progress ->
                        if (progress.status == DownloadStatus.COMPLETED) {
                            Logger.i(TAG, "Essential model downloaded: $modelId")
                        }
                    }
            } catch (e: Exception) {
                Logger.w(TAG, "Prefetch essential models failed", e)
            }
        }
    }

    /**
     * 注册网络状态监听，网络恢复时自动重连飞书通道
     */
    private fun registerFeishuNetworkMonitor() {
        try {
            val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (connectivityManager == null) {
                Logger.w(TAG, "无法获取 ConnectivityManager")
                return
            }

            val networkCallback = object : ConnectivityManager.NetworkCallback() {
                private var wasUnavailable = true

                override fun onAvailable(network: Network) {
                    if (wasUnavailable) {
                        wasUnavailable = false
                        Logger.i(TAG, "网络恢复可用，触发飞书重连")
                        // 延迟 2 秒等待网络稳定后再重连
                        applicationScope.launch {
                            delay(2000)
                            feishuChannelHandler.reconnectIfNeeded()
                        }
                    }
                }

                override fun onLost(network: Network) {
                    wasUnavailable = true
                    Logger.w(TAG, "网络连接丢失")
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities
                ) {
                    val hasInternet = capabilities.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_INTERNET
                    )
                    if (hasInternet && wasUnavailable) {
                        wasUnavailable = false
                        Logger.i(TAG, "网络能力恢复，触发飞书重连")
                        applicationScope.launch {
                            delay(2000)
                            feishuChannelHandler.reconnectIfNeeded()
                        }
                    }
                }
            }

            connectivityManager.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                networkCallback
            )
            Logger.i(TAG, "网络状态监听已注册")
        } catch (e: Exception) {
            Logger.e(TAG, "注册网络状态监听失败", e)
        }
    }

    /**
     * 同步 AI Agent 模式到 AgentOrchestrator
     *
     * **为什么需要这个方法**：
     * - [AgentOrchestrator] 是单例，其内部 [AgentConfigurator.agentMode] 默认值为 [AiAgentMode.LOCAL]
     * - [RemoteCommandDispatcher] 直接使用此单例，但从不调用 [AgentOrchestrator.configure]
     * - 如果用户在设置中切换了推理模式，而飞书路径没有收到通知，
     *   飞书消息会继续走旧的模式（或默认 LOCAL），与用户期望不符
     * - 此方法在启动时读取用户的设置，并在设置变化时自动同步
     */
    private fun syncAgentModeToOrchestrator() {
        applicationScope.launch {
            try {
                val repository = container.userPreferencesRepository
                combine(
                    repository.aiAgentModeFlow,
                    repository.aiAgentLocalModelFlow,
                    repository.aiAgentPrivacyLevelFlow,
                    repository.aiAgentInferencePreferenceFlow
                ) { mode, localModel, privacyLevel, inferencePreference ->
                    SyncConfig(mode, localModel, privacyLevel, inferencePreference)
                }.collect { (mode, localModel, privacyLevel, inferencePreference) ->
                    val orchestrator = AgentOrchestrator.getInstance(this@PoLangApplication)
                    val effectiveModel = localModel.takeIf { it.isNotBlank() } ?: "qwen3_5_2b"
                    // 只同步 mode 相关参数，remoteConfig 由 syncRemoteModelConfigToOrchestrator 独立管理
                    // 避免两个 flow 竞态时 gatewayToken 被空值覆盖
                    orchestrator.configure(
                        mode = mode,
                        modelId = effectiveModel,
                        privacyLevel = privacyLevel,
                        remoteConfig = null,
                        inferencePreference = inferencePreference
                    )
                    Logger.i(TAG, "Agent orchestrator synced: mode=$mode, model=$effectiveModel, inferencePreference=$inferencePreference")
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Agent mode sync failed", e)
            }
        }
    }

    /**
     * 同步远程模型配置到 AgentOrchestrator
     *
     * 当用户在设置中添加/修改/删除远程模型配置时，
     * 自动解析并同步到 AgentOrchestrator，确保远程推理使用最新配置。
     *
     * **注意**：DataStore 中存储的是新版 ProviderConfigs 格式（{"provider":"DEEPSEEK","modelId":"...","apiKey":"..."}），
     * 需先解析为 ProviderConfigs，再转换为 RemoteModelConfig 供推理引擎使用。
     */
    private val deviceIdProvider = DeviceIdProvider(this)

    private fun syncRemoteModelConfigToOrchestrator() {
        applicationScope.launch {
            try {
                val repository = container.userPreferencesRepository
                combine(
                    repository.aiAgentRemoteModelConfigsFlow,
                    repository.aiAgentSelectedRemoteModelFlow,
                    repository.serverAuthTokenFlow
                ) { configsJson, selectedModelId, serverToken ->
                    Triple(configsJson, selectedModelId, serverToken)
                }.collect { (configsJson, selectedModelId, serverToken) ->
                    val orchestrator = AgentOrchestrator.getInstance(this@PoLangApplication)
                    // deviceId 独立注入 AgentConfigurator，不受后续 remoteConfig 覆盖影响（访客试用 X-Device-Id）
                    orchestrator.setDeviceId(deviceIdProvider.get())

                    val providerConfigs = ProviderConfigs.fromJson(configsJson)
                    val selectedProviderConfig = providerConfigs.configs
                        .find { it.modelId == selectedModelId && it.isConfigured }
                        ?: providerConfigs.configs.firstOrNull { it.isConfigured }

                    if (selectedProviderConfig != null && selectedProviderConfig.isConfigured) {
                        // BYOK 模式：用户配置了自己的 API Key，直连 provider
                        val remoteConfig = selectedProviderConfig.toRemoteModelConfig()
                        orchestrator.configure(
                            mode = orchestrator.getAgentMode(),
                            modelId = orchestrator.getCurrentModelId(),
                            privacyLevel = AiAgentPrivacyLevel.STRICT,
                            remoteConfig = remoteConfig
                        )
                        orchestrator.clearFeishuAgent()
                        Logger.i(TAG, "Remote model config synced: model=${remoteConfig.modelId}, provider=${remoteConfig.providerId}")
                    } else {
                        // 服务端代理模式：使用邮箱注册的 token 认证
                        val remoteConfig = RemoteModelConfig.PICME_SERVER_DEFAULT.copy(
                            gatewayToken = serverToken,
                            deviceId = deviceIdProvider.get(),
                        )
                        orchestrator.configure(
                            mode = orchestrator.getAgentMode(),
                            modelId = orchestrator.getCurrentModelId(),
                            privacyLevel = AiAgentPrivacyLevel.STRICT,
                            remoteConfig = remoteConfig
                        )
                        orchestrator.clearFeishuAgent()
                        Logger.i(TAG, "Server proxy config synced: token=${if (serverToken.isNotBlank()) "set" else "empty"}")
                    }
                }
            } catch (e: Exception) {
                Logger.e(TAG, "Remote model config sync failed", e)
            }
        }
    }

    /**
     * 监听媒体库变化，飞书远程拍照完成后自动发送照片到飞书
     *
     * 当 [FeishuPhotoTracker] 标记了 pending capture 且照片保存到媒体库后，
     * 此监听器会检测到 source="feishu_remote" 的新照片，执行以下操作：
     * 1. 将照片写入飞书聊天记录（agent_image 类型）
     * 2. 通过飞书通道发送图片文件到飞书
     */
    private fun observeFeishuPhotoCapture() {
        applicationScope.launch {
            try {
                val chatMessageDao = AppDatabase.getDatabase(this@PoLangApplication).chatMessageDao()
                val chatSessionDao = AppDatabase.getDatabase(this@PoLangApplication).chatSessionDao()
                val feishuSessionId = "feishu"

                repository.allMedia.collect { mediaList ->
                    Logger.d(TAG, "allMedia emit: size=${mediaList.size}")

                    // 查找来源为飞书远程控制的新照片
                    val feishuPhotos = mediaList.filter { it.source == "feishu_remote" && it.type == MediaType.PHOTO }
                    if (feishuPhotos.isEmpty()) {
                        Logger.d(TAG, "没有检测到 feishu_remote 来源的照片")
                        return@collect
                    }
                    Logger.d(TAG, "检测到 ${feishuPhotos.size} 张 feishu_remote 照片: ${feishuPhotos.map { it.fileName }}")

                    // 获取待回复的飞书消息 ID
                    val pendingMessageId = FeishuPhotoTracker.consumePendingMessageId()
                    if (pendingMessageId == null) {
                        Logger.d(TAG, "没有 pending messageId，跳过发送（可能已处理或非飞书触发）")
                        return@collect
                    }

                    val latestPhoto = feishuPhotos.maxByOrNull { it.captureDate } ?: return@collect
                    Logger.i(TAG, "检测到飞书远程拍照结果: uri=${latestPhoto.uri}, messageId=$pendingMessageId")

                    // 1. 写入飞书聊天记录（agent_image 类型）
                    try {
                        // 确保飞书会话存在
                        val existingSession = chatSessionDao.getSession(feishuSessionId)
                        if (existingSession == null) {
                            chatSessionDao.insertSession(
                                ChatSessionEntity(
                                    sessionId = feishuSessionId,
                                    title = "飞书远程控制"
                                )
                            )
                        }
                        chatMessageDao.insertMessage(
                            ChatMessageEntity(
                                id = UUID.randomUUID().toString(),
                                sessionId = feishuSessionId,
                                type = "agent_image",
                                content = latestPhoto.uri,
                                modelUsed = "feishu_remote"
                            )
                        )
                        chatSessionDao.touchSession(feishuSessionId)
                        Logger.i(TAG, "飞书拍照结果已写入聊天记录")
                    } catch (e: Exception) {
                        Logger.e(TAG, "写入飞书聊天记录失败", e)
                    }

                    // 2. 发送图片到飞书（压缩到 2K 尺寸，降低文件大小）
                    try {
                        val uri = android.net.Uri.parse(latestPhoto.uri)
                        val compressedBytes = compressImageForFeishu(uri, 2048, 85)
                        if (compressedBytes != null) {
                            feishuChannelHandler.sendImage(compressedBytes, pendingMessageId)
                            Logger.i(TAG, "飞书拍照结果已发送到飞书: messageId=$pendingMessageId, size=${compressedBytes.size / 1024}KB")
                            // 发送完成通知
                            feishuChannelHandler.sendMessage("✅ 照片已发送，请查收", pendingMessageId)
                        } else {
                            Logger.w(TAG, "图片压缩失败，尝试发送原图: ${latestPhoto.uri}")
                            // 兜底：发送原图
                            val parcelFileDescriptor = contentResolver.openFileDescriptor(uri, "r")
                            if (parcelFileDescriptor != null) {
                                val fileDescriptor = parcelFileDescriptor.fileDescriptor
                                val inputStream = java.io.FileInputStream(fileDescriptor)
                                val imageBytes = inputStream.use { it.readBytes() }
                                parcelFileDescriptor.close()
                                feishuChannelHandler.sendImage(imageBytes, pendingMessageId)
                                Logger.i(TAG, "飞书拍照结果（原图）已发送: messageId=$pendingMessageId")
                                // 发送完成通知
                                feishuChannelHandler.sendMessage("✅ 照片已发送，请查收", pendingMessageId)
                            }
                        }
                    } catch (e: Exception) {
                        Logger.e(TAG, "发送照片到飞书失败", e)
                    }
                }
            } catch (e: Exception) {
                Logger.e(TAG, "飞书拍照监听启动失败", e)
            }
        }
    }

    /**
     * Activity 生命周期跟踪器
     *
     * 用于测试框架获取当前前台 Activity 进行截屏等操作，
     * 同时联动 MnnResourceManager 实现应用级前后台状态感知。
     */
    private inner class ActivityTracker : ActivityLifecycleCallbacks {
        private var activityCount = 0

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {
            if (activityCount == 0) {
                Logger.i(TAG, "App 回到前台")
                MnnResourceManager.getInstance(this@PoLangApplication).onAppForeground()
                // App 回到前台时检查飞书连接，断开则自动重连
                applicationScope.launch {
                    delay(1000) // 等待系统稳定
                    feishuChannelHandler.reconnectIfNeeded()
                }
            }
            activityCount++
        }
        override fun onActivityResumed(activity: Activity) {
            currentActivity = activity
            PoLangToolService.currentActivity = activity
            Logger.d(TAG, "Activity resumed: ${activity.javaClass.simpleName}")
        }
        override fun onActivityPaused(activity: Activity) {
            if (currentActivity == activity) {
                currentActivity = null
            }
        }
        override fun onActivityStopped(activity: Activity) {
            activityCount--
            if (activityCount == 0) {
                MnnResourceManager.getInstance(this@PoLangApplication).onAppBackground()
            }
        }
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {
            // Activity 销毁时清理引用
            // NavigationCapability 现在由 MainActivity 持有，随 Activity 自动释放
            if (currentActivity == activity) {
                currentActivity = null
            }
        }
    }

    /**
     * 初始化应用级 Capability
     *
     * 页面级 Capability（Camera/Gallery/Settings）随 Screen 创建和销毁，
     * 但 GalleryCapability 需要支持后台飞书指令直接搜索，因此同时注册为全局 Capability。
     * 它的可用性仍由 delegate 绑定状态决定，只有相册页面激活时才能真正执行命令。
     */
    private fun initializeCapabilities() {
        Logger.i(TAG, "Capability lifecycle: page-scoped + global GalleryCapability for Feishu search")
        Logger.i(TAG, "- NavigationCapability: Activity-scoped (MainActivity)")
        Logger.i(TAG, "- CameraCapability: Page-scoped (CameraScreen)")
        Logger.i(TAG, "- GalleryCapability: Page-scoped (GalleryScreen) + global registry")
        Logger.i(TAG, "- SettingsCapability: Page-scoped (SettingsScreen)")
        Logger.i(TAG, "- AiOptimizeCapability: Application-scoped")

        val orchestrator = AgentOrchestrator.getInstance(this)
        orchestrator.registerCapability(GalleryCapability.getInstance())
        orchestrator.registerCapability(ChatSearchCapability.getInstance())
        Logger.i(TAG, "- ChatSearchCapability: CHAT-scoped gallery search")
        orchestrator.registerCapability(
            AiOptimizeCapability(
                context = this,
                optimizeUseCase = container.aiOptimizeUseCase
            )
        )
    }

    override fun newImageLoader(): ImageLoader {
        return CoilConfig.createImageLoader(this, thumbnailCache)
    }

    /**
     * 压缩图片用于飞书发送
     * 将图片缩放到指定最大边长，并以 JPEG 格式压缩
     *
     * @param uri 图片 URI
     * @param maxDimension 最大边长（像素）
     * @param quality JPEG 压缩质量（0-100）
     * @return 压缩后的图片字节数组，失败返回 null
     */
    private fun compressImageForFeishu(uri: android.net.Uri, maxDimension: Int, quality: Int): ByteArray? {
        return try {
            // 1. 解码图片尺寸
            val options = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, options)
            }

            val (width, height) = options.outWidth to options.outHeight
            if (width <= 0 || height <= 0) {
                Logger.w(TAG, "无法获取图片尺寸: $uri")
                return null
            }

            // 2. 计算采样率
            val scaleFactor = if (width > height) {
                width.toFloat() / maxDimension
            } else {
                height.toFloat() / maxDimension
            }

            val decodeOptions = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = if (scaleFactor > 1) {
                    kotlin.math.max(1, scaleFactor.toInt())
                } else {
                    1
                }
            }

            // 3. 解码图片
            val bitmap = contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, decodeOptions)
            } ?: return null

            // 4. 精确缩放到目标尺寸
            val scaledBitmap = if (bitmap.width > maxDimension || bitmap.height > maxDimension) {
                val ratio = kotlin.math.min(
                    maxDimension.toFloat() / bitmap.width,
                    maxDimension.toFloat() / bitmap.height
                )
                val newWidth = (bitmap.width * ratio).toInt()
                val newHeight = (bitmap.height * ratio).toInt()
                android.graphics.Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true).also {
                    if (it != bitmap) bitmap.recycle()
                }
            } else {
                bitmap
            }

            // 5. 压缩为 JPEG
            val outputStream = java.io.ByteArrayOutputStream()
            val compressed = scaledBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, outputStream)
            val bytes = outputStream.toByteArray()
            outputStream.close()

            if (!scaledBitmap.isRecycled) {
                scaledBitmap.recycle()
            }

            if (compressed) {
                Logger.i(TAG, "图片压缩成功: ${width}x${height} -> ${bytes.size / 1024}KB")
                bytes
            } else {
                Logger.w(TAG, "Bitmap.compress 返回 false")
                null
            }
        } catch (e: Exception) {
            Logger.e(TAG, "图片压缩失败", e)
            null
        }
    }

    /**
     * 预加载 Native 共享库
     *
     * sherpa-onnx-jni.so 由 SherpaOnnxAsrEngine / KeywordSpotterEngine 通过
     * System.loadLibrary 加载（Sherpa 语音栈完全基于 ONNX Runtime）。
     * libMNN.so 等 MNN 运行时由 libagent_native（LLM JNI）自动链接加载。
     * 在 Application 中提前加载可确保所有依赖 so 在类加载器命名空间中可见。
     */
    private fun loadNativeLibraries() {
        // 用绝对路径预加载 APK 里的 ICD Loader，让 MNN dlopen 时直接命中跳过搜索
        try {
            val apkLibDir = applicationInfo.nativeLibraryDir
            System.load("$apkLibDir/libOpenCL.so")
            Logger.d(TAG, "OpenCL ICD Loader preloaded")
        } catch (e: UnsatisfiedLinkError) {
            Logger.d(TAG, "OpenCL ICD Loader preload skipped: ${e.message}")
        }
        try {
            System.loadLibrary("sherpa-onnx-jni")
            Logger.d(TAG, "Native library loaded: sherpa-onnx-jni")
        } catch (e: UnsatisfiedLinkError) {
            Logger.e(TAG, "Failed to load sherpa-onnx-jni", e)
        }
        // 预加载 SentencePiece tokenizer（OPUS-MT 编码解码依赖）
        try {
            System.loadLibrary("sentencepiece_android")
            Logger.d(TAG, "Native library loaded: sentencepiece_android")
        } catch (e: UnsatisfiedLinkError) {
            Logger.e(TAG, "Failed to load sentencepiece_android", e)
        }
    }

    /**
     * syncAgentModeToOrchestrator 内部使用的同步参数容器
     */
    private data class SyncConfig(
        val mode: AiAgentMode,
        val localModel: String,
        val privacyLevel: AiAgentPrivacyLevel,
        val inferencePreference: AiAgentInferencePreference
    )
}
