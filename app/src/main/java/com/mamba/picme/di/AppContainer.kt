package com.mamba.picme.di

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import com.mamba.picme.agent.core.platform.voice.KeywordSpotterEngine
import com.mamba.picme.beauty.api.PhotoProcessor
import com.mamba.picme.beauty.render.GlBeautyPreviewProviderFactory
import com.mamba.picme.beauty.api.BeautyProcessor
import com.mamba.picme.core.image.GpuBeautyProcessor
import com.mamba.picme.core.image.ImageProcessor
import com.mamba.picme.core.image.ImageProcessorImpl
import com.mamba.picme.core.common.Logger
import com.mamba.picme.core.image.ThumbnailCache
import com.mamba.picme.data.local.AppDatabase
import com.mamba.picme.data.local.dao.PersonDao
import com.mamba.picme.beauty.api.facedetect.FaceDetector
import com.mamba.picme.beauty.api.facedetect.FaceDetectorFactory
import com.mamba.picme.data.local.MlKitOcrProcessor
import com.mamba.picme.agent.core.facade.AgentOrchestrator
import com.mamba.picme.data.indexing.FaceClusteringWorker
import com.mamba.picme.data.indexing.ImageTagIndexingWorker
import com.mamba.picme.data.indexing.IndexingTaskQueue
import com.mamba.picme.data.indexing.MediaIndexingWorker
import com.mamba.picme.data.indexing.MediaStoreObserver
import com.mamba.picme.data.preferences.UserPreferencesRepository
import com.mamba.picme.data.preferences.dataStore
import com.mamba.picme.data.repository.MediaFeedbackRepository
import com.mamba.picme.data.repository.MediaFeedbackRepositoryImpl
import com.mamba.picme.data.repository.MediaRepositoryImpl
import com.mamba.picme.data.repository.PhotoEditRecipeRepository
import com.mamba.picme.domain.repository.MediaRepository
import com.mamba.picme.domain.repository.UserSettingsRepository
import com.mamba.picme.domain.search.ExplicitFirstSearchPipeline
import com.mamba.picme.domain.search.MediaFeedbackUseCase
import com.mamba.picme.domain.search.MediaSearchEngine
import com.mamba.picme.domain.search.QueryBuilder
import com.mamba.picme.domain.search.SemanticSearchEngine
import com.mamba.picme.domain.tag.ControlledVocab
import com.mamba.picme.domain.tag.i18n.BilingualVocab
import com.mamba.picme.domain.tag.i18n.ChineseQueryTranslator
import com.mamba.picme.domain.tag.i18n.OpusMtTranslator
import com.mamba.picme.domain.tag.i18n.TagTranslator
import com.mamba.picme.data.download.LlmModelDownloadManager
import com.mamba.picme.data.download.ModelPathConfig
import com.mamba.picme.domain.backup.BackupTagDataUseCase
import com.mamba.picme.domain.backup.RestoreTagDataUseCase
import com.mamba.picme.domain.backup.TagDataBackupRepository
import com.mamba.picme.domain.agent.capability.optimize.analyzer.LocalSceneAnalyzer
import com.mamba.picme.domain.agent.capability.optimize.consent.CloudOptimizeConsentManager
import com.mamba.picme.domain.agent.capability.optimize.preset.AssetPresetRepository
import com.mamba.picme.domain.agent.capability.ImageEditCapability
import com.mamba.picme.domain.usecase.AiOptimizeUseCase
import com.mamba.picme.domain.usecase.ChatEditProcessor
import com.mamba.picme.domain.usecase.FindDuplicateMediaUseCase
import com.mamba.picme.domain.usecase.GetGallerySummaryUseCase
import com.mamba.picme.domain.usecase.GetGroupedMediaUseCase
import com.mamba.picme.domain.usecase.QueryGalleryMediaUseCase
import com.mamba.picme.domain.usecase.StartTagScanUseCase
import com.mamba.picme.domain.usecase.GenerateSummaryOnDemandUseCase
import com.mamba.picme.domain.usecase.OcrProcessor
import com.mamba.picme.features.chat.ChatEditStateHolder
import com.mamba.picme.features.chat.ChatViewModel
import com.mamba.picme.data.remote.picme.PoLangAuthClient
import com.mamba.picme.features.chat.ChatImageRenderer
import com.mamba.picme.features.chat.ChatViewModelDependencies
import com.mamba.picme.features.chat.capability.MemoryCapability
import com.mamba.picme.features.chat.capability.PersonRelationCapability
import com.mamba.picme.domain.matting.MattingEngine
import com.mamba.picme.domain.matting.MattingEngineImpl
import com.mamba.picme.domain.memory.MemoryRepository
import com.mamba.picme.domain.person.PersonQueryResolver
import com.mamba.picme.domain.person.PersonRepository
import com.mamba.picme.features.editor.PhotoEditorViewModelFactory
import com.mamba.picme.features.idphoto.IDPhotoViewModelFactory
import com.mamba.picme.features.gallery.MediaViewModel
import androidx.lifecycle.ViewModel
import com.mamba.picme.domain.tag.TagGenerationScheduler
import com.mamba.picme.domain.tag.TagScanProgress
import com.mamba.picme.domain.tag.scan.TagScanSessionProgress
import com.mamba.picme.data.indexing.MediaChangeEvent
import com.mamba.picme.service.tag.TagGenerationService

data class MediaViewModelDependencies(
    val repository: MediaRepository,
    val getGroupedMediaUseCase: GetGroupedMediaUseCase,
    val findDuplicateMediaUseCase: FindDuplicateMediaUseCase,
    val ocrUseCase: OcrProcessor,
    val photoProcessor: PhotoProcessor,
    val faceDetector: FaceDetector,
    val generateSummaryOnDemandUseCase: GenerateSummaryOnDemandUseCase
)

class MediaViewModelFactory(
    private val dependencies: MediaViewModelDependencies
) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MediaViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MediaViewModel(
                repository = dependencies.repository,
                getGroupedMediaUseCase = dependencies.getGroupedMediaUseCase,
                findDuplicateMediaUseCase = dependencies.findDuplicateMediaUseCase,
                ocrUseCase = dependencies.ocrUseCase,
                photoProcessor = dependencies.photoProcessor,
                faceDetector = dependencies.faceDetector,
                generateSummaryOnDemandUseCase = dependencies.generateSummaryOnDemandUseCase
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

class ChatViewModelFactory(
    private val dependencies: ChatViewModelDependencies
) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ChatViewModel(dependencies) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

interface AppContainer {
    val repository: MediaRepository
    val userPreferencesRepository: UserSettingsRepository
    val imageProcessor: ImageProcessor
    val faceDetector: FaceDetector
    val llmModelDownloadManager: LlmModelDownloadManager
    val kwsEngine: KeywordSpotterEngine?
    val mediaSearchEngine: MediaSearchEngine
    val mediaIndexingWorker: MediaIndexingWorker
    val faceClusteringWorker: FaceClusteringWorker
    /** AI 图片标签索引器（本地 Vision LLM → 标签） */
    val imageTagIndexingWorker: ImageTagIndexingWorker
    /** TAG 生成调度器(单张 retag 走 Pass3 pipeline,与集中扫描同源) */
    val tagGenerationScheduler: TagGenerationScheduler
    /** TAG 生成扫描状态（只读，从 TagGenerationService 获取） */
    val tagGenerationIsScanning: kotlinx.coroutines.flow.StateFlow<Boolean>
    /** TAG 生成扫描进度（旧版兼容） */
    val tagGenerationProgress: kotlinx.coroutines.flow.StateFlow<TagScanProgress?>
    /** TAG 生成最后消息 */
    val tagGenerationLastMessage: kotlinx.coroutines.flow.StateFlow<String?>
    /** TAG 生成会话级增强进度 */
    val tagGenerationSessionProgress: kotlinx.coroutines.flow.StateFlow<TagScanSessionProgress?>
    /** 跨维度查询构建器（LLM 意图 → Room 查询） */
    val queryBuilder: QueryBuilder
    /** 双级缩略图缓存（LRU 内存 + 磁盘） */
    val thumbnailCache: ThumbnailCache

    val photoEditRecipeRepository: PhotoEditRecipeRepository
    val aiOptimizeUseCase: AiOptimizeUseCase

    /** 对话式图片编辑 Capability（全局注册） */
    val imageEditCapability: ImageEditCapability

    /** TAG 数据库备份用例 */
    val backupTagDataUseCase: BackupTagDataUseCase
    /** TAG 数据库还原用例 */
    val restoreTagDataUseCase: RestoreTagDataUseCase

    /** 相册摘要查询（chat JS handler / Debug 演示共用） */
    val getGallerySummaryUseCase: GetGallerySummaryUseCase
    /** 相册结构化查询（chat JS handler / Debug 演示共用） */
    val queryGalleryMediaUseCase: QueryGalleryMediaUseCase
    /** 人物/人脸聚类 DAO（chat JS handler face.cluster / Debug 演示共用） */
    val personDao: PersonDao
    /** 人物领域仓库（命名 / "我"标记 / 人物关系图谱收口，命名对话框与聊天工具共用） */
    val personRepository: PersonRepository
    /** 通用事实记忆仓库（"帮我记住…"事实的收口，聊天工具 / JS 通路 / 设置页共用） */
    val memoryRepository: MemoryRepository

    /** 人物关系声明 Capability（CHAT 场景，聊天 remember/forget_person_relation 落点） */
    val personRelationCapability: PersonRelationCapability
    /** 事实记忆 Capability（CHAT 场景，聊天工具直调与 JS dispatch 共用落点） */
    val memoryCapability: MemoryCapability
    /** 受控词表（chat JS handler tag.audit 词表外标签比对 / Debug 演示共用） */
    val controlledVocab: ControlledVocab

    fun createMediaViewModelFactory(): ViewModelProvider.Factory
    fun createChatViewModelFactory(): ViewModelProvider.Factory
    fun createPhotoEditorViewModelFactory(): ViewModelProvider.Factory

    fun createIDPhotoViewModelFactory(): ViewModelProvider.Factory

    /** 创建 MediaStoreObserver（需要 ContentResolver，按需创建） */
    fun createMediaStoreObserver(onChange: (List<MediaChangeEvent>) -> Unit): MediaStoreObserver
}

class AppContainerImpl(
    private val context: Context,
    private val thumbnailCacheParam: ThumbnailCache
) : AppContainer {

    private val database by lazy { AppDatabase.getDatabase(context) }

    /** 双语词表（全局共享，避免重复加载） */
    private val bilingualVocab: BilingualVocab by lazy {
        BilingualVocab.loadFromAssets(context)
    }

    /** 受控词表（标签规范化 + 搜索同义词扩展） */
    override val controlledVocab: ControlledVocab by lazy {
        ControlledVocab.loadFromAssets(context)
    }

    override val personDao: PersonDao
        get() = database.personDao()

    override val personRepository: PersonRepository by lazy {
        PersonRepository(
            personDao = database.personDao(),
            relationDao = database.personRelationDao()
        )
    }

    override val memoryRepository: MemoryRepository by lazy {
        MemoryRepository(memoryFactDao = database.memoryFactDao())
    }

    override val personRelationCapability: PersonRelationCapability by lazy {
        PersonRelationCapability(personRepository = personRepository)
    }

    override val memoryCapability: MemoryCapability by lazy {
        MemoryCapability(memoryRepository = memoryRepository)
    }

    /** OPUS-MT 翻译引擎（SentencePiece + ONNX Runtime） */
    private val opusMtTranslator: OpusMtTranslator by lazy {
        OpusMtTranslator(context)
    }

    /** 中文查询翻译器（注入 OPUS-MT 翻译引擎 + 受控词表） */
    private val chineseQueryTranslator: ChineseQueryTranslator by lazy {
        ChineseQueryTranslator(
            context = context,
            vocab = bilingualVocab,
            translator = opusMtTranslator,
            controlledVocab = controlledVocab
        )
    }

    /** 语义搜索引擎（MobileCLIP 跨模态检索，注入翻译器） */
    private val semanticSearchEngine: SemanticSearchEngine by lazy {
        SemanticSearchEngine(
            context = context,
            mediaDao = database.mediaDao(),
            queryTranslator = chineseQueryTranslator
        )
    }

    /** 显式约束优先搜索管道（注入翻译器支持跨语言扩展） */
    private val explicitFirstSearchPipeline: ExplicitFirstSearchPipeline by lazy {
        ExplicitFirstSearchPipeline(
            mediaDao = database.mediaDao(),
            personDao = database.personDao(),
            tagTranslator = TagTranslator(bilingualVocab, opusMtTranslator, controlledVocab)
        )
    }

    /** 人物查询解析器（人名/亲属称谓/“我” → personId，MediaSearchEngine 共现查询依赖） */
    private val personQueryResolver: PersonQueryResolver by lazy {
        PersonQueryResolver(personRepository)
    }

    /** 媒体搜索引擎（自然语言图片搜索） */
    override val mediaSearchEngine: MediaSearchEngine by lazy {
        MediaSearchEngine(
            mediaDao = database.mediaDao(),
            tagDao = database.tagDao(),
            ocrWordDao = database.ocrWordDao(),
            locationDao = database.locationDao(),
            personDao = database.personDao(),
            userSettingsRepository = userPreferencesRepository,
            tagTranslator = TagTranslator(bilingualVocab, opusMtTranslator, controlledVocab),
            semanticSearchEngine = semanticSearchEngine,
            explicitFirstPipeline = explicitFirstSearchPipeline,
            mediaFeedbackUseCase = mediaFeedbackUseCase,
            personQueryResolver = personQueryResolver
        )
    }

    /** 跨维度查询构建器 */
    override val queryBuilder: QueryBuilder by lazy {
        QueryBuilder(
            mediaDao = database.mediaDao(),
            tagDao = database.tagDao(),
            ocrWordDao = database.ocrWordDao(),
            locationDao = database.locationDao(),
            personDao = database.personDao(),
            userSettingsRepository = userPreferencesRepository,
            tagTranslator = TagTranslator(BilingualVocab.loadFromAssets(context), opusMtTranslator)
        )
    }

    /** 双级缩略图缓存（LRU 内存 + 磁盘） */
    override val thumbnailCache: ThumbnailCache = thumbnailCacheParam

    /** 图片反馈 Repository */
    private val mediaFeedbackRepository: MediaFeedbackRepository by lazy {
        MediaFeedbackRepositoryImpl(database.mediaFeedbackDao())
    }

    /** 图片反馈 UseCase */
    private val mediaFeedbackUseCase: MediaFeedbackUseCase by lazy {
        MediaFeedbackUseCase(mediaFeedbackRepository)
    }

    /** 媒体元数据索引器（ML Kit 标签+OCR+EXIF） */
    override val mediaIndexingWorker: MediaIndexingWorker by lazy {
        MediaIndexingWorker(context)
    }

    /** 人脸聚类器（面部几何特征 + DBSCAN） */
    override val faceClusteringWorker: FaceClusteringWorker by lazy {
        FaceClusteringWorker(context) {
            repository.refreshMediaLibrary()
        }
    }

    /** AI 图片标签索引器（本地 Vision LLM → 中文标签） */
    override val imageTagIndexingWorker: ImageTagIndexingWorker by lazy {
        val llmEngine = AgentOrchestrator.getInstance(context).getLlmEngine()
        ImageTagIndexingWorker(context, llmEngine)
    }

    override val tagGenerationScheduler: TagGenerationScheduler by lazy {
        TagGenerationScheduler(context)
    }

    /** TAG 生成扫描状态（从 TagGenerationService 获取） */
    override val tagGenerationIsScanning: kotlinx.coroutines.flow.StateFlow<Boolean>
        get() = TagGenerationService.isScanning

    /** TAG 生成扫描进度（旧版兼容） */
    override val tagGenerationProgress: kotlinx.coroutines.flow.StateFlow<TagScanProgress?>
        get() = TagGenerationService.progress

    /** TAG 生成最后消息 */
    override val tagGenerationLastMessage: kotlinx.coroutines.flow.StateFlow<String?>
        get() = TagGenerationService.lastScanMessage

    /** TAG 生成会话级增强进度 */
    override val tagGenerationSessionProgress: kotlinx.coroutines.flow.StateFlow<TagScanSessionProgress?>
        get() = TagGenerationService.sessionProgress

    /**
     * 创建 MediaStoreObserver。
     * 每次调用创建新实例，生命周期由调用方管理。
     */
    override fun createMediaStoreObserver(
        onChange: (List<MediaChangeEvent>) -> Unit
    ): MediaStoreObserver {
        return MediaStoreObserver(
            contentResolver = context.contentResolver,
            onChange = onChange
        )
    }

    /**
     * 静态 Bitmap 美颜处理器（拍照后 CPU 路径）。
     * ⚠️ 仅用于拍照后的静态图像后处理，与实时预览无关。
     * 实时预览美颜由 beauty-engine 模块的 BeautyPreviewEngine（GPU 路径）负责。
     */
    private val beautyProcessor: BeautyProcessor by lazy {
        GpuBeautyProcessor(context)
    }

    override val repository: MediaRepository by lazy {
        MediaRepositoryImpl(database.mediaDao(), context)
    }

    override val photoEditRecipeRepository: PhotoEditRecipeRepository by lazy {
        PhotoEditRecipeRepository(database.photoEditRecipeDao())
    }

    override val aiOptimizeUseCase: AiOptimizeUseCase by lazy {
        AiOptimizeUseCase(
            sceneAnalyzer = LocalSceneAnalyzer(context, faceDetector),
            presetRepository = AssetPresetRepository(context),
            consentManager = CloudOptimizeConsentManager(context),
            smartEngine = null
        )
    }

    private val tagDataBackupRepository: TagDataBackupRepository by lazy {
        TagDataBackupRepository(
            database = database,
            mediaDao = database.mediaDao(),
            tagDao = database.tagDao(),
            tagScanTaskDao = database.tagScanTaskDao(),
            personDao = database.personDao(),
            ocrWordDao = database.ocrWordDao(),
            locationDao = database.locationDao(),
            mediaFeedbackDao = database.mediaFeedbackDao(),
            dataStore = context.dataStore
        )
    }

    override val backupTagDataUseCase: BackupTagDataUseCase by lazy {
        BackupTagDataUseCase(context, tagDataBackupRepository)
    }

    override val restoreTagDataUseCase: RestoreTagDataUseCase by lazy {
        RestoreTagDataUseCase(context, tagDataBackupRepository, backupTagDataUseCase)
    }

    override val userPreferencesRepository: UserSettingsRepository by lazy {
        UserPreferencesRepository(context)
    }

    override val faceDetector: FaceDetector by lazy {
        FaceDetectorFactory.create(context)
    }

    private val photoProcessor: PhotoProcessor by lazy {
        photoProcessorFactory(context)
    }

    private fun photoProcessorFactory(ctx: Context): PhotoProcessor {
        return GlBeautyPreviewProviderFactory().createPhotoProcessor(ctx)
    }

    private val chatEditStateHolder: ChatEditStateHolder by lazy {
        ChatEditStateHolder()
    }

    private val chatEditProcessor: ChatEditProcessor by lazy {
        ChatEditProcessor(
            photoProcessor = photoProcessor,
            faceDetector = faceDetector,
            mediaRepository = repository,
            userSettingsRepository = userPreferencesRepository
        )
    }

    override val imageEditCapability: ImageEditCapability by lazy {
        ImageEditCapability(
            context = context,
            chatEditProcessor = chatEditProcessor,
            stateHolder = chatEditStateHolder
        )
    }

    override val imageProcessor: ImageProcessor by lazy {
        ImageProcessorImpl(beautyProcessor, photoProcessor, faceDetector)
    }

    override val llmModelDownloadManager: LlmModelDownloadManager by lazy {
        LlmModelDownloadManager(context)
    }

    /**
     * KWS 唤醒词引擎（sherpa-onnx 版）
     *
     * 用于低功耗的 always-on 唤醒词检测。
     * 【重要】不允许自动降级，如果 KWS 初始化失败就直接抛异常！
     *
     * 【模型路径管理】
     * 使用 ModelPathConfig 统一管理模型路径，避免硬编码。
     * 支持灵活扩展新模型，只需在 ModelPathConfig 中添加配置。
     */
    override val kwsEngine: KeywordSpotterEngine? by lazy {
        val kwsModelDir = ModelPathConfig.getKwsModelDir(context)
        Logger.i("AppContainer", "【KWS 初始化】Starting KWS engine initialization...")
        Logger.i("AppContainer", "  Model path: ${kwsModelDir.absolutePath}")

        val missingFiles = ModelPathConfig.getMissingFiles(kwsModelDir, ModelPathConfig.KWS_MODEL_FILES)
        if (missingFiles.isNotEmpty()) {
            val errorMsg = buildString {
                append("❌ KWS 模型文件缺失，跳过 native 初始化以避免崩溃\n")
                append("  Model dir: ${kwsModelDir.absolutePath}\n")
                append("  Missing files: ${missingFiles.joinToString(", ")}\n")
                append("  Expected ${ModelPathConfig.KWS_MODEL_FILES.size} files, " +
                    "found ${ModelPathConfig.KWS_MODEL_FILES.size - missingFiles.size}\n")
                append("【安全降级】返回 null，KWS 唤醒词功能不可用")
            }
            Logger.w("AppContainer", errorMsg)
            return@lazy null
        }

        val emptyFiles = ModelPathConfig.KWS_MODEL_FILES.filter { fileName ->
            val file = java.io.File(kwsModelDir, fileName)
            file.exists() && file.length() == 0L
        }
        if (emptyFiles.isNotEmpty()) {
            val errorMsg = buildString {
                append("❌ KWS 模型文件为空（可能损坏），跳过 native 初始化以避免崩溃\n")
                append("  Model dir: ${kwsModelDir.absolutePath}\n")
                append("  Empty files: ${emptyFiles.joinToString(", ")}\n")
                append("【安全降级】返回 null，KWS 唤醒词功能不可用")
            }
            Logger.w("AppContainer", errorMsg)
            return@lazy null
        }

        Logger.i("AppContainer", "✓ KWS model files validated, creating KeywordSpotterEngine...")
        val kwsEngine = KeywordSpotterEngine(kwsModelDir.absolutePath)

        if (!kwsEngine.isAvailable()) {
            val errorMsg = buildString {
                append("❌ KWS 引擎初始化失败 - native 构造返回不可用\n")
                append("  Model dir: ${kwsModelDir.absolutePath}\n")
                append("【安全降级】返回 null，KWS 唤醒词功能不可用")
            }
            Logger.w("AppContainer", errorMsg)
            return@lazy null
        }

        Logger.i("AppContainer", "✓ KWS engine initialized successfully")
        Logger.i("AppContainer", "  Keywords: ${kwsEngine.getKeywords().joinToString(", ")}")

        kwsEngine
    }

    private val ocrProcessor: OcrProcessor by lazy {
        MlKitOcrProcessor()
    }

    override val getGallerySummaryUseCase: GetGallerySummaryUseCase by lazy {
        GetGallerySummaryUseCase(db = database)
    }

    override val queryGalleryMediaUseCase: QueryGalleryMediaUseCase by lazy {
        QueryGalleryMediaUseCase(db = database)
    }

    private val startTagScanUseCase: StartTagScanUseCase by lazy {
        StartTagScanUseCase(context = context)
    }

    val generateSummaryOnDemandUseCase: GenerateSummaryOnDemandUseCase by lazy {
        // 注入容器内单例 scheduler，使 on-demand 路径走与 Pass3/「重新打标」同源的统一规格管道，
        // 而非只写 summary 自然语言桩。两者均 by lazy，首次访问时才解析，无循环依赖。
        GenerateSummaryOnDemandUseCase(
            context = context,
            tagGenerationScheduler = tagGenerationScheduler
        )
    }

    private val mediaViewModelDependencies: MediaViewModelDependencies by lazy {
        MediaViewModelDependencies(
            repository = repository,
            getGroupedMediaUseCase = GetGroupedMediaUseCase(),
            findDuplicateMediaUseCase = FindDuplicateMediaUseCase(repository),
            ocrUseCase = ocrProcessor,
            photoProcessor = photoProcessor,
            faceDetector = faceDetector,
            generateSummaryOnDemandUseCase = generateSummaryOnDemandUseCase
        )
    }

    private val mediaViewModelFactory: ViewModelProvider.Factory by lazy {
        MediaViewModelFactory(mediaViewModelDependencies)
    }

    private val photoEditorViewModelFactory: ViewModelProvider.Factory by lazy {
        PhotoEditorViewModelFactory(
            appContext = context,
            photoProcessorFactory = ::photoProcessorFactory,
            faceDetector = faceDetector,
            recipeRepository = photoEditRecipeRepository,
            mediaRepository = repository,
            userSettingsRepository = userPreferencesRepository,
            aiOptimizeUseCase = aiOptimizeUseCase,
            downloadManager = llmModelDownloadManager
        )
    }

    private val idPhotoViewModelFactory: ViewModelProvider.Factory by lazy {
        IDPhotoViewModelFactory(
            appContext = context,
            downloadManager = llmModelDownloadManager,
            mediaRepository = repository
        )
    }

    private val mattingEngine: MattingEngine by lazy {
        MattingEngineImpl(context, llmModelDownloadManager)
    }

    private val chatImageRenderer: ChatImageRenderer by lazy {
        ChatImageRenderer(context, photoProcessor, mattingEngine, aiOptimizeUseCase)
    }

    private val chatViewModelDependencies: ChatViewModelDependencies by lazy {
        ChatViewModelDependencies(
            context = context,
            chatMessageDao = database.chatMessageDao(),
            chatSessionDao = database.chatSessionDao(),
            userSettingsRepository = userPreferencesRepository,
            mediaSearchEngine = mediaSearchEngine,
            mediaFeedbackRepository = mediaFeedbackRepository,
            mediaRepository = repository,
            picMeAuthClient = PoLangAuthClient(),
            getGallerySummaryUseCase = getGallerySummaryUseCase,
            queryGalleryMediaUseCase = queryGalleryMediaUseCase,
            startTagScanUseCase = startTagScanUseCase,
            personDao = database.personDao(),
            controlledVocab = controlledVocab,
            chatEditStateHolder = chatEditStateHolder,
            chatEditProcessor = chatEditProcessor,
            chatImageRenderer = chatImageRenderer
        )
    }

    private val chatViewModelFactory: ViewModelProvider.Factory by lazy {
        ChatViewModelFactory(chatViewModelDependencies)
    }

    override fun createMediaViewModelFactory(): ViewModelProvider.Factory {
        return mediaViewModelFactory
    }

    override fun createPhotoEditorViewModelFactory(): ViewModelProvider.Factory {
        return photoEditorViewModelFactory
    }

    override fun createIDPhotoViewModelFactory(): ViewModelProvider.Factory {
        return idPhotoViewModelFactory
    }

    override fun createChatViewModelFactory(): ViewModelProvider.Factory {
        return chatViewModelFactory
    }
}
