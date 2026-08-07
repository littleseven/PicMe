package com.mamba.picme.domain.agent.capability

import com.mamba.picme.agent.core.capability.BaseCapability
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentErrorCode
import com.mamba.picme.agent.core.model.context.PageContext
import com.mamba.picme.core.common.Logger

/**
 * IM 远程控制 Capability（应用级）
 *
 * **职责**：
 * - 管理设备与飞书等 IM 平台的绑定/解绑
 * - 提供设备状态查询
 * - 管理远程操作审计日志
 * - 控制远程命令的自动确认模式
 *
 * **设计原则**：
 * - 应用级单例：在 Application.onCreate() 中注册，永不注销
 * - 远程控制的管理命令不通过 AgentCommand 分发（不污染 agent-core）
 * - 通过 [WebSocketClient] 和 [RemoteCommandDispatcher] 直接调用公开 API
 * - 不依赖任何页面存在，在任何场景下均可访问
 *
 * **与 IM 远程控制子组件的关系**：
 * - 本 Capability 负责设备状态管理（绑定/状态/审计）
 * - 实际命令收发由 [RemoteCommandDispatcher] + [WebSocketClient] 处理
 * - 通过 CapabilityRegistry 获取已有 Capability（Gallery/Editor）执行远程命令
 *
 * **生命周期**：
 * ```
 * Application.onCreate() ──► RemoteControlCapability.getInstance() 创建
 *     │
 *     ├── 注册到 CapabilityRegistry
 *     │
 * Application.onTerminate() ──► 随进程释放
 *     │
 *     └── onDestroy() 清理状态
 * ```
 */
class RemoteControlCapability : BaseCapability() {

    companion object {
        @Volatile
        private var instance: RemoteControlCapability? = null

        fun getInstance(): RemoteControlCapability {
            return instance ?: synchronized(this) {
                instance ?: RemoteControlCapability().also { instance = it }
            }
        }

        private const val TAG = "RemoteControlCapability"
    }

    private val tag = TAG

    /**
     * 当前设备绑定状态
     */
    @Volatile
    private var _deviceBound: Boolean = false
    val isDeviceBound: Boolean get() = _deviceBound

    /**
     * 绑定的设备名称（如 "Xiaomi 15 Ultra"）
     */
    @Volatile
    private var _deviceName: String = ""
    val deviceName: String get() = _deviceName

    /**
     * 绑定的用户 ID（飞书 open_id）
     */
    @Volatile
    private var _boundUserId: String = ""
    val boundUserId: String get() = _boundUserId

    /**
     * 当前设备 Token（用于 WebSocket 鉴权）
     */
    @Volatile
    private var _deviceToken: String = ""
    val deviceToken: String get() = _deviceToken

    /**
     * Relay Server URL
     */
    @Volatile
    private var _relayUrl: String = ""
    val relayUrl: String get() = _relayUrl

    /**
     * 是否启用自动确认模式
     * true: 所有远程命令自动执行（需用户主动开启）
     * false: 敏感操作需确认（默认）
     */
    @Volatile
    private var _autoConfirm: Boolean = false
    val autoConfirm: Boolean get() = _autoConfirm

    override val name: String = "remote_control"
    override val description: String = "IM 远程控制：管理设备绑定与远程命令执行状态"

    /**
     * RemoteControlCapability 不通过 AgentCommand 密封类分发命令。
     * 所有操作通过公开 API 由 [RemoteCommandDispatcher] 直接调用。
     * 此处始终返回 METHOD_NOT_FOUND。
     */
    override fun supportedCommands(): List<String> = emptyList()

    override suspend fun execute(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext?
    ): Result<AgentAction> {
        Logger.d(tag, "Remote control management commands are called via public API, not AgentCommand dispatch")
        return Result.success(
            AgentAction.Error(
                commandId = command.commandId,
                errorCode = AgentErrorCode.METHOD_NOT_FOUND,
                message = "远程控制管理操作通过公开 API 调用"
            )
        )
    }

    // ==================== 公开管理 API ====================

    /**
     * 更新设备绑定状态
     * 由 DeviceRegistrationFlow 在扫码绑定成功后调用
     */
    fun updateBinding(
        deviceToken: String,
        relayUrl: String,
        userId: String,
        deviceName: String
    ) {
        this._deviceToken = deviceToken
        this._relayUrl = relayUrl
        this._boundUserId = userId
        this._deviceName = deviceName
        this._deviceBound = true
        Logger.i(tag, "Device bound: userId=$userId, deviceName=$deviceName")
    }

    /**
     * 清除设备绑定状态
     * 断开 WebSocket 并清理本地绑定信息
     */
    fun clearBinding(): String {
        val previousUser = _boundUserId
        this._deviceToken = ""
        this._relayUrl = ""
        this._boundUserId = ""
        this._deviceName = ""
        this._deviceBound = false
        Logger.i(tag, "Device unbound from user: $previousUser")
        return previousUser
    }

    /**
     * 设置自动确认模式
     */
    fun setAutoConfirm(enabled: Boolean) {
        this._autoConfirm = enabled
        Logger.i(tag, "Auto confirm mode: $enabled")
    }

    /**
     * 构建设备状态描述
     */
    fun buildStatusString(): String {
        val sb = StringBuilder()
        sb.appendLine("📱 设备状态")
        sb.appendLine("━━━━━━━━━━━━━━━━")
        if (_deviceBound) {
            sb.appendLine("• 绑定设备: $_deviceName")
            sb.appendLine("• 绑定用户: $_boundUserId")
            sb.appendLine("• 连接状态: ${if (_deviceToken.isNotBlank()) "已连接" else "未连接"}")
            sb.appendLine("• 自动确认: ${if (_autoConfirm) "已开启" else "已关闭"}")
            sb.appendLine("• Relay Server: $_relayUrl")
        } else {
            sb.appendLine("• 状态: 未绑定")
            sb.appendLine("• 请通过飞书扫码绑定设备")
        }
        return sb.toString()
    }

    /**
     * 销毁时清理资源
     */
    fun onDestroy() {
        clearBinding()
        Logger.i(tag, "RemoteControlCapability resources cleaned up")
    }
}
