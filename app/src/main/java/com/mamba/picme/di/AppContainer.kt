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
import com.mamba.picme.data.repository.MediaRepositoryImpl
import com.mamba.picme.data.repository.PhotoEditRecipeRepository
import com.mamba.picme.domain.repository.MediaRepository
import com.mamba.picme.domain.repository.UserSettingsRepository
import com.mamba.picme.domain.search.ExplicitFirstSearchPipeline
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
import com.mamba.picme.domain.usecase.FindDuplicateMediaUseCase
import com.mamba.picme.domain.usecase.GetGroupedMediaUseCase
import com.mamba.picme.domain.usecase.OcrProcessor
import com.mamba.picme.features.chat.ChatViewModel
import com.mamba.picme.features.chat.ChatViewModelDependencies
import com.mamba.picme.features.editor.PhotoEditorViewModelFactory
import com.mamba.picme.features.gallery.MediaViewModel
import androidx.lifecycle.ViewModel

data class MediaViewModelDependencies(
    val repository: MediaRepository,
    val getGroupedMediaUseCase: GetGroupedMediaUseCase,
    val findDuplicateMediaUseCase: FindDuplicateMediaUseCase,
    val ocrUseCase: OcrProcessor,
    val photoProcessor: PhotoProcessor,
    val faceDetector: FaceDetector
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
                faceDetector = dependencies.faceDetector
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
    /** TAG 生成扫描状态（只读，从 TagGenerationService 获取） */
    val tagGenerationIsScanning: kotlinx.coroutines.flow.StateFlow<Boolean>
    /** TAG 生成扫描进度（旧版兼容） */
    val tagGenerationProgress: kotlinx.coroutines.flow.StateFlow<com.mamba.picme.domain.tag.TagScanProgress?>
    /** TAG 生成最后消息 */
    val tagGenerationLastMessage: kotlinx.coroutines.flow.StateFlow<String?>
    /** TAG 生成会话级增强进度 */
    val tagGenerationSessionProgress: kotlinx.coroutines.flow.StateFlow<com.mamba.picme.domain.tag.scan.TagScanSessionProgress?>
    /** 跨维度查询构建器（LLM 意图 → Room 查询） */
    val queryBuilder: QueryBuilder
    /** 双级缩略图缓存（LRU 内存 + 磁盘） */
    val thumbnailCache: ThumbnailCache

    val photoEditRecipeRepository: PhotoEditRecipeRepository

    fun createMediaViewModelFactory(): ViewModelProvider.Factory
    fun createChatViewModelFactory(): ViewModelProvider.Factory
    fun createPhotoEditorViewModelFactory(): ViewModelProvider.Factory

    /** 创建 MediaStoreObserver（需要 ContentResolver，按需创建） */
    fun createMediaStoreObserver(onChange: (List<com.mamba.picme.data.indexing.MediaChangeEvent>) -> Unit): MediaStoreObserver
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
    private val controlledVocab: ControlledVocab by lazy {
        ControlledVocab.loadFromAssets(context)
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
            tagTranslator = TagTranslator(bilingualVocab, opusMtTranslator, controlledVocab)
        )
    }

    /** 媒体搜索引擎（自然语言图片搜索） */
    override val mediaSearchEngine: MediaSearchEngine by lazy {
        MediaSearchEngine(
            mediaDao = database.mediaDao(),
            tagDao = database.tagDao(),
            ocrWordDao = database.ocrWordDao(),
            locationDao = database.locationDao(),
            userSettingsRepository = userPreferencesRepository,
            tagTranslator = TagTranslator(bilingualVocab, opusMtTranslator, controlledVocab),
            semanticSearchEngine = semanticSearchEngine,
            explicitFirstPipeline = explicitFirstSearchPipeline
        )
    }

    /** 跨维度查询构建器 */
    override val queryBuilder: QueryBuilder by lazy {
        QueryBuilder(
            mediaDao = database.mediaDao(),
            tagDao = database.tagDao(),
            ocrWordDao = database.ocrWordDao(),
            locationDao = database.locationDao(),
            userSettingsRepository = userPreferencesRepository,
            tagTranslator = TagTranslator(BilingualVocab.loadFromAssets(context), opusMtTranslator)
        )
    }

    /** 双级缩略图缓存（LRU 内存 + 磁盘） */
    override val thumbnailCache: ThumbnailCache = thumbnailCacheParam

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

    /** TAG 生成扫描状态（从 TagGenerationService 获取） */
    override val tagGenerationIsScanning: kotlinx.coroutines.flow.StateFlow<Boolean>
        get() = com.mamba.picme.service.tag.TagGenerationService.isScanning

    /** TAG 生成扫描进度（旧版兼容） */
    override val tagGenerationProgress: kotlinx.coroutines.flow.StateFlow<com.mamba.picme.domain.tag.TagScanProgress?>
        get() = com.mamba.picme.service.tag.TagGenerationService.progress

    /** TAG 生成最后消息 */
    override val tagGenerationLastMessage: kotlinx.coroutines.flow.StateFlow<String?>
        get() = com.mamba.picme.service.tag.TagGenerationService.lastScanMessage

    /** TAG 生成会话级增强进度 */
    override val tagGenerationSessionProgress: kotlinx.coroutines.flow.StateFlow<com.mamba.picme.domain.tag.scan.TagScanSessionProgress?>
        get() = com.mamba.picme.service.tag.TagGenerationService.sessionProgress

    /**
     * 创建 MediaStoreObserver。
     * 每次调用创建新实例，生命周期由调用方管理。
     */
    override fun createMediaStoreObserver(
        onChange: (List<com.mamba.picme.data.indexing.MediaChangeEvent>) -> Unit
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

    override val userPreferencesRepository: UserSettingsRepository by lazy {
        UserPreferencesRepository(context)
    }

    override val faceDetector: FaceDetector by lazy {
        FaceDetectorFactory.create(context)
    }

    private val photoProcessor: PhotoProcessor by lazy {
        GlBeautyPreviewProviderFactory().createPhotoProcessor(context)
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

    private val mediaViewModelDependencies: MediaViewModelDependencies by lazy {
        MediaViewModelDependencies(
            repository = repository,
            getGroupedMediaUseCase = GetGroupedMediaUseCase(),
            findDuplicateMediaUseCase = FindDuplicateMediaUseCase(repository),
            ocrUseCase = ocrProcessor,
            photoProcessor = photoProcessor,
            faceDetector = faceDetector
        )
    }

    private val mediaViewModelFactory: ViewModelProvider.Factory by lazy {
        MediaViewModelFactory(mediaViewModelDependencies)
    }

    private val photoEditorViewModelFactory: ViewModelProvider.Factory by lazy {
        PhotoEditorViewModelFactory(
            photoProcessor = photoProcessor,
            faceDetector = faceDetector,
            recipeRepository = photoEditRecipeRepository,
            mediaRepository = repository
        )
    }

    private val chatViewModelDependencies: ChatViewModelDependencies by lazy {
        ChatViewModelDependencies(
            context = context,
            chatMessageDao = database.chatMessageDao(),
            chatSessionDao = database.chatSessionDao(),
            userSettingsRepository = userPreferencesRepository
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

    override fun createChatViewModelFactory(): ViewModelProvider.Factory {
        return chatViewModelFactory
    }
}
