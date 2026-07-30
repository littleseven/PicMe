@file:Suppress("OPT_IN_USAGE_ERROR")

package com.mamba.picme

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navOptions
import com.mamba.picme.core.designsystem.PoLangTheme
import com.mamba.picme.data.preferences.UserPreferencesRepository
import com.mamba.picme.domain.model.AppLanguage
import com.mamba.picme.features.camera.CameraScreen
import com.mamba.picme.features.chat.ChatScreen
import com.mamba.picme.features.chat.ChatViewModel
import com.mamba.picme.features.debug.DebugScreen
import com.mamba.picme.features.debug.JsBridgeScreen
import com.mamba.picme.features.debug.LlmCallLogScreen
import com.mamba.picme.features.editor.PhotoEditorScreen
import com.mamba.picme.features.editor.PhotoEditorViewModel
import com.mamba.picme.features.idphoto.IDPhotoScreen
import com.mamba.picme.features.idphoto.IDPhotoViewModel
import com.mamba.picme.features.gallery.GalleryScreen
import com.mamba.picme.features.search.SearchTestScreen
import com.mamba.picme.features.gallery.MediaViewModel
import com.mamba.picme.features.gallery.components.TagGenerationControlScreen
import com.mamba.picme.features.translation.SentencePieceTestScreen
import com.mamba.picme.features.tagviewer.TagViewerTestScreen
import com.mamba.picme.features.settings.DataPrivacyScreen
import com.mamba.picme.features.settings.MemoryFactsScreen
import com.mamba.picme.features.person.PersonScreen
import com.mamba.picme.features.person.PersonViewModel
import com.mamba.picme.features.settings.MemoryFactsViewModel
import com.mamba.picme.features.settings.CommunicationChannelScreen
import com.mamba.picme.features.settings.CommunicationChannelViewModel
import com.mamba.picme.domain.model.RemoteChannelType
import com.mamba.picme.features.settings.ModelCenterScreen
import com.mamba.picme.features.settings.SettingsCategory
import com.mamba.picme.features.settings.SettingsScreen
import com.mamba.picme.features.settings.SettingsViewModel
import com.mamba.picme.features.settings.SettingsViewModelFactory
import com.mamba.picme.features.debug.LogOverlay
import com.mamba.picme.navigation.Screen
import com.mamba.picme.core.common.Logger
import com.mamba.picme.agent.core.runtime.state.SceneManager
import com.mamba.picme.agent.core.facade.AgentOrchestrator
import com.mamba.picme.domain.agent.capability.NavigationCapability
import com.mamba.picme.domain.agent.capability.SystemCapability
import java.util.Locale
import androidx.camera.core.ExperimentalGetImage
import androidx.compose.ui.platform.LocalConfiguration

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private var currentLanguage: AppLanguage? = null

    override fun attachBaseContext(newBase: Context) {
        val repository = UserPreferencesRepository(newBase)
        val language = repository.getAppLanguageBlocking()

        val locale = getLocaleFromLanguage(language)
        val context = updateLocale(newBase, locale)
        super.attachBaseContext(context)
    }

    @ExperimentalGetImage
    @Suppress("OPT_IN_USAGE_ERROR")
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val app = application as PoLangApplication
            val context = LocalContext.current
            val chatViewModel: ChatViewModel = viewModel(
                factory = app.container.createChatViewModelFactory()
            )
            val mediaViewModel: MediaViewModel = viewModel(
                factory = app.container.createMediaViewModelFactory()
            )
            val settingsViewModel: SettingsViewModel = viewModel(
                factory = SettingsViewModelFactory(
                    app.container.userPreferencesRepository,
                    app.container.llmModelDownloadManager,
                    context
                )
            )

            val themeMode by settingsViewModel.themeMode.collectAsState()
            val appLanguage by settingsViewModel.appLanguage.collectAsState()

            LaunchedEffect(appLanguage) {
                if (currentLanguage != null && currentLanguage != appLanguage) {
                    recreate()
                }
                currentLanguage = appLanguage
            }

            // 进入应用且处于 WiFi 时，后台静默下载缺失的 Tier 1 + Tier 2 模型
            LaunchedEffect(Unit) {
                settingsViewModel.startSilentDownloadIfWifi()
            }

            CompositionLocalProvider(
                LocalConfiguration provides Configuration(context.resources.configuration).apply {
                    setLocale(getLocaleFromLanguage(appLanguage))
                }
            ) {
                PoLangTheme(themeMode = themeMode) {
                    val navController = rememberNavController()

                    // Navigation/System Capability：依赖 NavController/Context，在 Activity 期创建并
                    // 注册到全局 CapabilityRegistry（唯一注册表，2026-07-29 单轨收敛——Compose
                    // CapabilityHost 已退役，不再有第二注册容器）。
                    val navigationCapability = remember { NavigationCapability(navController) }
                    val systemCapability = remember { SystemCapability(applicationContext) }
                    val orchestrator = remember { AgentOrchestrator.getInstance(applicationContext) }
                    LaunchedEffect(navigationCapability, systemCapability) {
                        orchestrator.registerCapability(navigationCapability)
                        orchestrator.registerCapability(systemCapability)
                        Logger.i(TAG, "NavigationCapability and SystemCapability registered globally")
                    }

                    LaunchedEffect(navController) {
                        Logger.i(TAG, "NavigationCapability initialized with NavController")
                    }

                    run {
                        Scaffold(
                            modifier = Modifier.fillMaxSize(),
                            contentWindowInsets = WindowInsets(0, 0, 0, 0)
                        ) { innerPadding ->
                            NavHost(
                                navController = navController,
                                startDestination = Screen.Gallery.route,
                                modifier = Modifier.padding(innerPadding),
                                enterTransition = {
                                    fadeIn(tween(400)) + slideIntoContainer(
                                        AnimatedContentTransitionScope.SlideDirection.Start,
                                        tween(400)
                                    )
                                },
                                exitTransition = {
                                    fadeOut(tween(400)) + slideOutOfContainer(
                                        AnimatedContentTransitionScope.SlideDirection.Start,
                                        tween(400)
                                    )
                                },
                                popEnterTransition = {
                                    fadeIn(tween(400)) + slideIntoContainer(
                                        AnimatedContentTransitionScope.SlideDirection.End,
                                        tween(400)
                                    )
                                },
                                popExitTransition = {
                                    fadeOut(tween(400)) + slideOutOfContainer(
                                        AnimatedContentTransitionScope.SlideDirection.End,
                                        tween(400)
                                    )
                                }
                            ) {
                            composable(Screen.Chat.route) {
                                // 场景管理：进入 Chat 页面
                                DisposableEffect(Unit) {
                                    SceneManager.getInstance().transitionTo(SceneManager.Scene.CHAT)
                                    onDispose {
                                        SceneManager.getInstance().leaveScene(SceneManager.Scene.CHAT)
                                    }
                                }
                                ChatScreen(
                                    viewModel = chatViewModel,
                                    settingsViewModel = settingsViewModel,
                                    onNavigateBack = { navController.popBackStack() },
                                    onNavigateToSettings = { navController.navigate(Screen.Settings.route, navOptions { launchSingleTop = true }) },
                                    onNavigateToGallery = { query -> navController.navigate(Screen.Gallery.createRoute(query), navOptions { launchSingleTop = true }) },
                                    mediaViewModel = mediaViewModel,
                                    onNavigateToPhotoEditor = { uri, autoOptimize ->
                                        navController.navigate(
                                            Screen.PhotoEditor.createRoute(sourceUri = uri, autoOptimize = autoOptimize),
                                            navOptions { launchSingleTop = true }
                                        )
                                    },
                                    onNavigateToIDPhoto = { uri ->
                                        navController.navigate(
                                            Screen.IDPhoto.createRoute(sourceUri = uri),
                                            navOptions { launchSingleTop = true }
                                        )
                                    }
                                )
                            }
                            composable(Screen.Camera.route) {
                                // 场景管理：进入 Camera 页面
                                DisposableEffect(Unit) {
                                    SceneManager.getInstance().transitionTo(SceneManager.Scene.CAMERA)
                                    onDispose {
                                        SceneManager.getInstance().leaveScene(SceneManager.Scene.CAMERA)
                                    }
                                }
                                CameraScreen(
                                    onNavigateToGallery = { navController.navigate(Screen.Gallery.route, navOptions { launchSingleTop = true }) },
                                    onNavigateBack = { navController.popBackStack() },
                                    viewModel = mediaViewModel,
                                    settingsViewModel = settingsViewModel
                                )
                            }
                            composable(
                                route = Screen.Gallery.ROUTE_WITH_QUERY,
                                arguments = listOf(
                                    navArgument(Screen.Gallery.ARG_QUERY) {
                                        type = NavType.StringType
                                        defaultValue = ""
                                    }
                                )
                            ) { backStackEntry ->
                                // 场景管理：进入 Gallery 页面
                                DisposableEffect(Unit) {
                                    SceneManager.getInstance().transitionTo(SceneManager.Scene.GALLERY)
                                    onDispose {
                                        SceneManager.getInstance().leaveScene(SceneManager.Scene.GALLERY)
                                    }
                                }
                                val initialSearchQuery = backStackEntry.arguments
                                    ?.getString(Screen.Gallery.ARG_QUERY).orEmpty()
                                GalleryScreen(
                                    navController = navController,
                                    viewModel = mediaViewModel,
                                    settingsViewModel = settingsViewModel,
                                    initialSearchQuery = initialSearchQuery,
                                    onNavigateToChat = { navController.navigate(Screen.Chat.route, navOptions { launchSingleTop = true }) },
                                    onNavigateToCamera = { navController.navigate(Screen.Camera.route, navOptions { launchSingleTop = true }) },
                                    onNavigateToSettings = { navController.navigate(Screen.Settings.route, navOptions { launchSingleTop = true }) },
                                    onNavigateToModelCenter = { navController.navigate(Screen.ModelCenter.createRoute("llm"), navOptions { launchSingleTop = true }) },
                                    onNavigateToDebug = { navController.navigate(Screen.Debug.route, navOptions { launchSingleTop = true }) },
                                    onNavigateToTagControl = {
                                        navController.navigate(Screen.TagControl.route, navOptions { launchSingleTop = true })
                                    },
                                    onNavigateToPeople = {
                                        navController.navigate(Screen.People.route, navOptions { launchSingleTop = true })
                                    }
                                )
                            }
                            composable(
                                route = Screen.PhotoEditor.route,
                                arguments = listOf(
                                    navArgument("sourceUri") { type = NavType.StringType },
                                    navArgument("recipeUri") {
                                        type = NavType.StringType
                                        defaultValue = ""
                                        nullable = true
                                    },
                                    navArgument("autoOptimize") {
                                        type = NavType.BoolType
                                        defaultValue = false
                                    }
                                )
                            ) { backStackEntry ->
                                val encodedSource = backStackEntry.arguments?.getString("sourceUri") ?: ""
                                val encodedRecipe = backStackEntry.arguments?.getString("recipeUri").orEmpty()
                                val sourceUri = java.net.URLDecoder.decode(encodedSource, "UTF-8")
                                val recipeUri = encodedRecipe.takeIf { it.isNotBlank() }
                                    ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                                val autoOptimize = backStackEntry.arguments?.getBoolean("autoOptimize") ?: false

                                val factory = app.container.createPhotoEditorViewModelFactory()
                                val viewModel: PhotoEditorViewModel = viewModel(factory = factory)

                                PhotoEditorScreen(
                                    sourceUri = sourceUri,
                                    recipeUri = recipeUri,
                                    autoOptimize = autoOptimize,
                                    viewModel = viewModel,
                                    onNavigateBack = { navController.popBackStack() },
                                    onEditSaved = { outputUri ->
                                        navController.previousBackStackEntry
                                            ?.savedStateHandle
                                            ?.set("photo_editor_output_uri", outputUri)
                                        navController.popBackStack()
                                    }
                                )
                            }
                            composable(
                                route = Screen.IDPhoto.route,
                                arguments = listOf(
                                    navArgument("sourceUri") { type = NavType.StringType }
                                )
                            ) { backStackEntry ->
                                val encodedSource = backStackEntry.arguments?.getString("sourceUri") ?: ""
                                val sourceUri = java.net.URLDecoder.decode(encodedSource, "UTF-8")
                                val factory = app.container.createIDPhotoViewModelFactory()
                                val viewModel: IDPhotoViewModel = viewModel(factory = factory)
                                IDPhotoScreen(
                                    sourceUri = sourceUri,
                                    viewModel = viewModel,
                                    onNavigateBack = { navController.popBackStack() },
                                    onSaved = { navController.popBackStack() }
                                )
                            }
                            composable(Screen.TagControl.route) {
                                DisposableEffect(Unit) {
                                    SceneManager.getInstance().transitionTo(SceneManager.Scene.GALLERY)
                                    onDispose {
                                        SceneManager.getInstance().leaveScene(SceneManager.Scene.GALLERY)
                                    }
                                }
                                TagGenerationControlScreen(
                                    onNavigateBack = { navController.popBackStack() }
                                )
                            }
                            composable(Screen.TagViewer.route) {
                                TagViewerTestScreen(onNavigateBack = { navController.popBackStack() })
                            }
                            composable(Screen.Settings.route) {
                                // 场景管理：进入 Settings 主页面
                                DisposableEffect(Unit) {
                                    SceneManager.getInstance().transitionTo(SceneManager.Scene.SETTINGS)
                                    onDispose {
                                        SceneManager.getInstance().leaveScene(SceneManager.Scene.SETTINGS)
                                    }
                                }
                                SettingsScreen(
                                    viewModel = settingsViewModel,
                                    category = SettingsCategory.MAIN,
                                    onNavigateBack = { navController.popBackStack() },
                                    onNavigateToModelCenter = { categoryTag ->
                                        navController.navigate(Screen.ModelCenter.createRoute(categoryTag), navOptions { launchSingleTop = true })
                                    },
                                    onNavigateToTagControl = {
                                        navController.navigate(Screen.TagControl.route, navOptions { launchSingleTop = true })
                                    },
                                    onNavigateToTagViewer = {
                                        navController.navigate(Screen.TagViewer.route, navOptions { launchSingleTop = true })
                                    },
                                    onNavigateToDebug = {
                                        navController.navigate(Screen.Debug.route, navOptions { launchSingleTop = true })
                                    },
                                    onNavigateToJsBridge = {
                                        navController.navigate(Screen.JsBridge.route, navOptions { launchSingleTop = true })
                                    },
                                    onNavigateToSearchTest = {
                                        navController.navigate(Screen.SearchTest.route, navOptions { launchSingleTop = true })
                                    },
                                    onNavigateToLlmLog = {
                                        navController.navigate(Screen.LlmLog.route, navOptions { launchSingleTop = true })
                                    },
                                    onNavigateToCategory = { target ->
                                        navController.navigate(Screen.SettingsCategory.createRoute(target.name.lowercase()), navOptions { launchSingleTop = true })
                                    },
                                    onNavigateToDataPrivacy = {
                                        navController.navigate(Screen.DataPrivacy.route, navOptions { launchSingleTop = true })
                                    },
                                    onNavigateToMemoryFacts = {
                                        navController.navigate(Screen.MemoryFacts.route, navOptions { launchSingleTop = true })
                                    },
                                    onNavigateToCommunicationChannel = {
                                        navController.navigate(Screen.CommunicationChannel.route, navOptions { launchSingleTop = true })
                                    },
                                    onNavigateToPeople = {
                                        navController.navigate(Screen.People.route, navOptions { launchSingleTop = true })
                                    }
                                )
                            }
                            composable(
                                route = Screen.SettingsCategory.route,
                                arguments = listOf(
                                    navArgument("category") {
                                        type = NavType.StringType
                                        defaultValue = ""
                                    }
                                )
                            ) { backStackEntry ->
                                // 场景管理：进入 Settings 二级分类页
                                DisposableEffect(Unit) {
                                    SceneManager.getInstance().transitionTo(SceneManager.Scene.SETTINGS)
                                    onDispose {
                                        SceneManager.getInstance().leaveScene(SceneManager.Scene.SETTINGS)
                                    }
                                }
                                val categoryArg = backStackEntry.arguments?.getString("category") ?: ""
                                val category = try {
                                    SettingsCategory.valueOf(categoryArg.uppercase())
                                } catch (_: IllegalArgumentException) {
                                    SettingsCategory.MAIN
                                }
                                SettingsScreen(
                                    viewModel = settingsViewModel,
                                    category = category,
                                    onNavigateBack = { navController.popBackStack() },
                                    onNavigateToModelCenter = { categoryTag ->
                                        navController.navigate(Screen.ModelCenter.createRoute(categoryTag), navOptions { launchSingleTop = true })
                                    },
                                    onNavigateToTagControl = {
                                        navController.navigate(Screen.TagControl.route, navOptions { launchSingleTop = true })
                                    },
                                    onNavigateToTagViewer = {
                                        navController.navigate(Screen.TagViewer.route, navOptions { launchSingleTop = true })
                                    },
                                    onNavigateToDebug = {
                                        navController.navigate(Screen.Debug.route, navOptions { launchSingleTop = true })
                                    },
                                    onNavigateToJsBridge = {
                                        navController.navigate(Screen.JsBridge.route, navOptions { launchSingleTop = true })
                                    },
                                    onNavigateToSearchTest = {
                                        navController.navigate(Screen.SearchTest.route, navOptions { launchSingleTop = true })
                                    },
                                    onNavigateToLlmLog = {
                                        navController.navigate(Screen.LlmLog.route, navOptions { launchSingleTop = true })
                                    },
                                    onNavigateToCategory = { target ->
                                        navController.navigate(Screen.SettingsCategory.createRoute(target.name.lowercase()), navOptions { launchSingleTop = true })
                                    },
                                    onNavigateToDataPrivacy = {
                                        navController.navigate(Screen.DataPrivacy.route, navOptions { launchSingleTop = true })
                                    },
                                    onNavigateToMemoryFacts = {
                                        navController.navigate(Screen.MemoryFacts.route, navOptions { launchSingleTop = true })
                                    },
                                    onNavigateToCommunicationChannel = {
                                        navController.navigate(Screen.CommunicationChannel.route, navOptions { launchSingleTop = true })
                                    },
                                    onNavigateToPeople = {
                                        navController.navigate(Screen.People.route, navOptions { launchSingleTop = true })
                                    }
                                )
                            }
                            composable(
                                route = Screen.ModelCenter.route,
                                arguments = listOf(
                                    navArgument("categoryTag") {
                                        type = NavType.StringType
                                        defaultValue = ""
                                    }
                                )
                            ) { backStackEntry ->
                                // 场景管理：进入 Settings 子页面（复用 SETTINGS 场景）
                                DisposableEffect(Unit) {
                                    SceneManager.getInstance().transitionTo(SceneManager.Scene.SETTINGS)
                                    onDispose {
                                        SceneManager.getInstance().leaveScene(SceneManager.Scene.SETTINGS)
                                    }
                                }
                                val categoryTag = backStackEntry.arguments?.getString("categoryTag") ?: ""
                                ModelCenterScreen(
                                    viewModel = settingsViewModel,
                                    initialCategoryTag = categoryTag,
                                    onNavigateBack = { navController.popBackStack() }
                                )
                            }
                            composable(Screen.DataPrivacy.route) {
                                DataPrivacyScreen(onNavigateBack = { navController.popBackStack() })
                            }
                            composable(Screen.CommunicationChannel.route) {
                                val communicationChannelViewModel: CommunicationChannelViewModel = viewModel(
                                    factory = CommunicationChannelViewModel.factory(app.container.userPreferencesRepository)
                                )
                                val selectedChannel by communicationChannelViewModel.selectedChannel.collectAsState()
                                val feishuAppId by communicationChannelViewModel.feishuAppId.collectAsState()
                                val feishuAppSecret by communicationChannelViewModel.feishuAppSecret.collectAsState()
                                val telegramBotToken by communicationChannelViewModel.telegramBotToken.collectAsState()
                                val isConfigured = when (selectedChannel) {
                                    RemoteChannelType.FEISHU -> feishuAppId.isNotBlank() && feishuAppSecret.isNotBlank()
                                    RemoteChannelType.TELEGRAM -> telegramBotToken.isNotBlank()
                                    RemoteChannelType.NONE -> false
                                }
                                CommunicationChannelScreen(
                                    viewModel = communicationChannelViewModel,
                                    isConnected = app.remoteChannelManager.isConnected,
                                    isConfigured = isConfigured,
                                    onNavigateBack = { navController.popBackStack() }
                                )
                            }
                            composable(Screen.MemoryFacts.route) {
                                val memoryFactsViewModel: MemoryFactsViewModel = viewModel(
                                    factory = MemoryFactsViewModel.factory(
                                        app.container.memoryRepository,
                                        app.container.personRepository
                                    )
                                )
                                MemoryFactsScreen(
                                    viewModel = memoryFactsViewModel,
                                    onNavigateBack = { navController.popBackStack() }
                                )
                            }
                            composable(Screen.People.route) {
                                val personViewModel: PersonViewModel = viewModel(
                                    factory = PersonViewModel.factory(
                                        app.container.personRepository,
                                        app.container.database,
                                        app.container.faceClusterEngine
                                    )
                                )
                                PersonScreen(
                                    viewModel = personViewModel,
                                    onNavigateBack = { navController.popBackStack() }
                                )
                            }
                            composable(Screen.Debug.route) {
                                // 场景管理：进入 Debug 页面
                                DisposableEffect(Unit) {
                                    SceneManager.getInstance().transitionTo(SceneManager.Scene.DEBUG)
                                    onDispose {
                                        SceneManager.getInstance().leaveScene(SceneManager.Scene.DEBUG)
                                    }
                                }
                                DebugScreen(
                                    onNavigateBack = { navController.popBackStack() },
                                    mediaViewModel = mediaViewModel
                                )
                            }
                            if (BuildConfig.DEBUG) {
                                composable(Screen.JsBridge.route) {
                                    JsBridgeScreen(
                                        onNavigateBack = { navController.popBackStack() }
                                    )
                                }
                                composable(Screen.SearchTest.route) {
                                    SearchTestScreen(
                                        onNavigateBack = { navController.popBackStack() }
                                    )
                                }
                                composable(Screen.SentencePieceTest.route) {
                                    SentencePieceTestScreen(
                                        onNavigateBack = { navController.popBackStack() }
                                    )
                                }
                            }
                            // 诊断页全构建可用：release 下仅展示纯指标，不含消息内容
                            composable(Screen.LlmLog.route) {
                                LlmCallLogScreen(
                                    onNavigateBack = { navController.popBackStack() }
                                )
                            }
                            }
                        }
                    }

                    // 全局日志浮层：跨越页面生命周期
                    val showLogOverlay by settingsViewModel.showLogOverlay.collectAsState()
                    if (showLogOverlay) {
                        LogOverlay(onDismiss = { settingsViewModel.setShowLogOverlay(false) })
                    }
                }
            }
        }
    }

    private fun getLocaleFromLanguage(language: AppLanguage): Locale {
        return when (language) {
            AppLanguage.ENGLISH -> Locale.ENGLISH
            AppLanguage.CHINESE -> Locale.SIMPLIFIED_CHINESE
            AppLanguage.TRADITIONAL_CHINESE -> Locale.TRADITIONAL_CHINESE
            AppLanguage.SYSTEM -> Locale.getDefault()
        }
    }

    private fun updateLocale(context: Context, locale: Locale): Context {
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)

        // Update resources for old context
        context.resources.updateConfiguration(config, context.resources.displayMetrics)

        return context.createConfigurationContext(config)
    }
}
