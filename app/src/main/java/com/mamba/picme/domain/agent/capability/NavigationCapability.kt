package com.mamba.picme.domain.agent.capability

import androidx.navigation.NavController
import androidx.navigation.navOptions
import com.mamba.picme.agent.core.capability.BaseCapability
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentErrorCode
import com.mamba.picme.agent.core.model.context.PageContext
import com.mamba.picme.agent.core.runtime.state.SceneManager
import com.mamba.picme.core.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 导航 Capability（Activity 级）
 *
 * **架构设计**：
 * - Activity 级生命周期：由 MainActivity 创建和持有，Activity 销毁时释放
 * - 构造函数注入 NavController，无需 bind/unbind 模式
 * - 状态内聚：直接持有 NavController 引用（Activity 级，同生命周期）
 *
 * **生命周期**：
 * ```
 * MainActivity.onCreate() ──► NavigationCapability(navController) 创建
 *     │
 *     ├── 注册到全局 CapabilityRegistry
 *     │
 * MainActivity.onDestroy() ──► NavigationCapability 随 Activity 释放
 * ```
 *
 * **注意**：导航回调涉及 Compose NavController 操作，必须在主线程执行。
 * execute() 内部会自动切换到 Main 线程。
 */
class NavigationCapability(
    private val navController: NavController,
    /**
     * 主页面（相机/相册等 HorizontalPager 内页面）切换器。
     * 主页面已不再是 NavController destination，必须由外层通过 Pager 切换。
     */
    private val mainPageSwitcher: ((Destination) -> Unit)? = null
) : BaseCapability() {

    private val tag = "NavigationCapability"

    override val name: String = "navigation"
    override val description: String = "页面导航：切换页面、返回上一页"

    override fun isAvailable(): Boolean {
        // NavigationCapability 必须检查 NavController 是否已绑定
        // 否则 CapabilityRegistry 会认为导航可用并直接执行命令，
        // 导致 IllegalStateException: 导航系统未初始化
        return try {
            navController.currentDestination != null
        } catch (e: Exception) {
            Logger.w(tag, "NavController not available: ${e.message}")
            false
        }
    }

    override fun activeScenes(): List<SceneManager.Scene> {
        // 导航能力在所有场景都可用
        return SceneManager.Scene.entries.toList()
    }

    override fun supportedCommands(): List<String> = listOf(
        "navigate_to",
        "go_back"
    )

    override fun getCommandDescription(command: String): String = when (command) {
        "navigate_to" -> "导航到指定页面，参数: destination (chat|camera|gallery|settings|debug|model_center)"
        "go_back" -> "返回上一页，无参数"
        else -> "未知命令"
    }

    override suspend fun execute(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext?
    ): Result<AgentAction> {
        Logger.d(tag, "Executing command: ${command::class.simpleName}, command=${command}")

        Logger.d(tag, "Checking matches: is NavigateTo=${command is AgentCommand.NavigateTo}, is GoBack=${command is AgentCommand.GoBack}")

        return when (command) {
            is AgentCommand.NavigateTo -> {
                Logger.d(tag, "Matched NavigateTo, destination='${command.destination}'")
                val destination = parseDestination(command.destination)
                Logger.d(tag, "parseDestination returned: $destination")
                if (destination != null) {
                    Logger.d(tag, "Before withContext(Dispatchers.Main)")
                    withContext(Dispatchers.Main) {
                        Logger.d(tag, "Inside withContext, before navigateTo")
                        navigateTo(navController, destination)
                        Logger.d(tag, "Inside withContext, after navigateTo")
                    }
                    Logger.d(tag, "After withContext, returning Success")
                    Result.success(AgentAction.Success(commandId = command.commandId, command = command))
                } else {
                    Logger.w(tag, "Unknown destination: ${command.destination}")
                    Result.success(
                        AgentAction.Error(
                            commandId = command.commandId,
                            errorCode = AgentErrorCode.INVALID_PARAMS,
                            message = "未知页面: ${command.destination}，可用页面: camera, gallery, settings, debug, model_center"
                        )
                    )
                }
            }

            is AgentCommand.GoBack -> {
                Logger.d(tag, "Matched GoBack")
                withContext(Dispatchers.Main) {
                    navController.popBackStack()
                }
                Result.success(AgentAction.Success(commandId = command.commandId, command = command))
            }

            else -> {
                Logger.w(tag, "Unsupported command: ${command::class.simpleName}")
                Result.success(
                    AgentAction.Error(
                        commandId = command.commandId,
                        errorCode = AgentErrorCode.METHOD_NOT_FOUND,
                        message = "导航 Capability 不支持此命令"
                    )
                )
            }
        }
    }

    /**
     * 执行页面导航（单实例约束）
     *
     * 核心策略：
     * - 目标页已在栈顶 → 忽略（不创建新实例）
     * - 目标页在 back stack 中 → popUpTo 该页，复用已有实例
     * - 目标页不在栈中 → 正常 navigate
     *
     * 这样保证相机页、相册页、设置页等核心页面全局最多只有 1 个实例，
     * 同时保留返回栈的合理层级关系。
     */
    private fun navigateTo(nav: NavController, destination: Destination) {
        // 主页面（相机/相册）已收敛进 HorizontalPager，优先走 Pager 切换
        if (destination == Destination.CAMERA || destination == Destination.GALLERY) {
            val switcher = mainPageSwitcher
            if (switcher != null) {
                Logger.d(tag, "Switching main page via pager: $destination")
                switcher(destination)
                return
            }
        }
        val route = when (destination) {
            Destination.CAMERA -> "camera"
            Destination.GALLERY -> "gallery"
            Destination.SETTINGS -> "settings"
            Destination.DEBUG -> "debug"
            Destination.MODEL_CENTER -> "model_center"
            Destination.LLM_MODEL_MANAGER -> "model_center"
            Destination.ASR_MODEL_MANAGER -> "model_center"
        }
        try {
            val currentRoute = nav.currentDestination?.route
            if (currentRoute == route) {
                Logger.d(tag, "Already on $route, skip navigation")
                return
            }
            val options = navOptions {
                launchSingleTop = true
            }
            Logger.d(tag, "Navigating to $route (current=$currentRoute)")
            nav.navigate(route, options)
            Logger.d(tag, "Navigation to $route dispatched successfully")
        } catch (e: Exception) {
            Logger.e(tag, "Navigation failed to $route", e)
        }
    }

    private fun parseDestination(destination: String): Destination? {
        return when (destination.lowercase()) {
            "camera", "相机", "拍照", "拍摄" -> Destination.CAMERA
            "gallery", "相册", "照片", "图库" -> Destination.GALLERY
            "settings", "设置", "配置" -> Destination.SETTINGS
            "debug", "调试" -> Destination.DEBUG
            "model_center", "模型中心", "模型管理" -> Destination.MODEL_CENTER
            "llm_model_manager", "llm模型管理", "大模型管理" -> Destination.LLM_MODEL_MANAGER
            "asr_model_manager", "asr模型管理", "语音模型管理" -> Destination.ASR_MODEL_MANAGER
            else -> null
        }
    }

    /**
     * 导航目标
     */
    enum class Destination {
        CAMERA,             // 相机页
        GALLERY,            // 相册页
        SETTINGS,           // 设置页
        DEBUG,              // 调试页
        MODEL_CENTER,       // 模型中心
        LLM_MODEL_MANAGER,  // 旧：LLM模型管理页（已废弃，路由到模型中心）
        ASR_MODEL_MANAGER   // 旧：ASR模型管理页（已废弃，路由到模型中心）
    }
}
