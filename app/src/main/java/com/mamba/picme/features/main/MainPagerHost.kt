package com.mamba.picme.features.main

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.navOptions
import com.mamba.picme.agent.core.runtime.state.SceneManager
import com.mamba.picme.features.camera.CameraScreen
import com.mamba.picme.features.chat.ChatScreen
import com.mamba.picme.features.chat.ChatViewModel
import com.mamba.picme.features.gallery.GalleryScreen
import com.mamba.picme.features.gallery.MediaViewModel
import com.mamba.picme.features.person.PersonScreen
import com.mamba.picme.features.person.PersonViewModel
import com.mamba.picme.features.settings.SettingsViewModel
import com.mamba.picme.navigation.Screen

/** 主页面 Pager 页索引（线性，无循环回绕） */
const val MAIN_PAGE_CAMERA = 0
const val MAIN_PAGE_GALLERY = 1
const val MAIN_PAGE_CHAT = 2
const val MAIN_PAGE_PEOPLE = 3
const val MAIN_PAGE_COUNT = 4

/**
 * 主页面容器：以 HorizontalPager 承载 相机/相册/聊天/人物 4 页。
 *
 * - 拖动跟手：横滑实时跟随手指，松手物理吸附；替代原 MainPageSwipeWrapper 的离散跳转
 * - 页面常驻：beyondViewportPageCount = 3，4 页全部常驻组合，相册滚动/搜索状态滑走不丢
 * - 相机门控：仅当前页为相机页时绑定相机/加载语音与本地模型（isActivePage）
 * - 横滑使能：相册（详情/多选）与聊天（全屏预览）通过回调上报，局部禁用外层滑动
 */
@Suppress("LongParameterList")
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainPagerHost(
    pagerState: PagerState,
    chatViewModel: ChatViewModel,
    mediaViewModel: MediaViewModel,
    settingsViewModel: SettingsViewModel,
    personViewModel: PersonViewModel,
    navController: NavHostController,
    onSwitchPage: (Int) -> Unit,
    gallerySearchRequest: Pair<String, Long>?,
    onGallerySearchRequestConsumed: () -> Unit,
    onRequestGallerySearch: (query: String, personId: Long) -> Unit
) {
    var gallerySwipeEnabled by remember { mutableStateOf(true) }
    var chatSwipeEnabled by remember { mutableStateOf(true) }

    // 场景管理：跟随 Pager 稳定页切换（人物页无独立场景，沿用进入前的场景）
    LaunchedEffect(pagerState.settledPage) {
        val scene = when (pagerState.settledPage) {
            MAIN_PAGE_CAMERA -> SceneManager.Scene.CAMERA
            MAIN_PAGE_GALLERY -> SceneManager.Scene.GALLERY
            MAIN_PAGE_CHAT -> SceneManager.Scene.CHAT
            else -> null
        }
        scene?.let { SceneManager.getInstance().transitionTo(it) }
    }

    // 返回键：非相册页回到相册页（对齐原 popUpTo(Gallery) 语义）。
    // 各 page 内部 BackHandler（预览/多选/顶栏返回）均以 isActivePage 守卫，
    // 仅激活页消费返回键，避免 HorizontalPager 全 page 组合下的跨页 LIFO 抢占。
    BackHandler(enabled = pagerState.currentPage != MAIN_PAGE_GALLERY) {
        onSwitchPage(MAIN_PAGE_GALLERY)
    }

    val userScrollEnabled = when (pagerState.currentPage) {
        MAIN_PAGE_GALLERY -> gallerySwipeEnabled
        MAIN_PAGE_CHAT -> chatSwipeEnabled
        else -> true
    }

    HorizontalPager(
        state = pagerState,
        beyondViewportPageCount = MAIN_PAGE_COUNT - 1,
        userScrollEnabled = userScrollEnabled,
        modifier = Modifier.fillMaxSize()
    ) { page ->
        when (page) {
            MAIN_PAGE_CAMERA -> CameraScreen(
                onNavigateToGallery = { onSwitchPage(MAIN_PAGE_GALLERY) },
                onNavigateBack = { onSwitchPage(MAIN_PAGE_GALLERY) },
                viewModel = mediaViewModel,
                settingsViewModel = settingsViewModel,
                isActivePage = pagerState.currentPage == MAIN_PAGE_CAMERA
            )

            MAIN_PAGE_GALLERY -> GalleryScreen(
                navController = navController,
                viewModel = mediaViewModel,
                settingsViewModel = settingsViewModel,
                onNavigateToChat = { onSwitchPage(MAIN_PAGE_CHAT) },
                onNavigateToCamera = { onSwitchPage(MAIN_PAGE_CAMERA) },
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route, navOptions { launchSingleTop = true })
                },
                onNavigateToModelCenter = {
                    navController.navigate(Screen.ModelCenter.createRoute("llm"), navOptions { launchSingleTop = true })
                },
                onNavigateToDebug = {
                    navController.navigate(Screen.Debug.route, navOptions { launchSingleTop = true })
                },
                onNavigateToTagControl = {
                    navController.navigate(Screen.TagControl.route, navOptions { launchSingleTop = true })
                },
                onNavigateToPeople = { onSwitchPage(MAIN_PAGE_PEOPLE) },
                searchRequest = gallerySearchRequest,
                onSearchRequestConsumed = onGallerySearchRequestConsumed,
                onHorizontalSwipeEnabledChange = { gallerySwipeEnabled = it },
                isActivePage = pagerState.currentPage == MAIN_PAGE_GALLERY
            )

            MAIN_PAGE_CHAT -> ChatScreen(
                viewModel = chatViewModel,
                settingsViewModel = settingsViewModel,
                onNavigateBack = { onSwitchPage(MAIN_PAGE_GALLERY) },
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route, navOptions { launchSingleTop = true })
                },
                onNavigateToGallery = { query -> onRequestGallerySearch(query, 0L) },
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
                },
                onHorizontalSwipeEnabledChange = { chatSwipeEnabled = it },
                isActivePage = pagerState.currentPage == MAIN_PAGE_CHAT
            )

            MAIN_PAGE_PEOPLE -> PersonScreen(
                viewModel = personViewModel,
                onNavigateBack = { onSwitchPage(MAIN_PAGE_GALLERY) },
                onNavigateToGallery = { personId -> onRequestGallerySearch("", personId) },
                isActivePage = pagerState.currentPage == MAIN_PAGE_PEOPLE
            )
        }
    }
}
