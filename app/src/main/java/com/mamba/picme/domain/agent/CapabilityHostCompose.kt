package com.mamba.picme.domain.agent

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import com.mamba.picme.agent.core.capability.Capability
import com.mamba.picme.agent.core.capability.CapabilityHost
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.agent.core.runtime.state.SceneManager

/**
 * Compose 实现的 Capability 宿主
 *
 * 管理当前作用域内所有 Capability 的注册和查询。
 * 支持层级查找：如果当前宿主找不到，会委托给父宿主。
 *
 * **设计原则**：
 * - 页面级 Capability 随页面创建和销毁，避免单例常驻内存
 * - 通过 CompositionLocal 在 Compose 树中传递，无需全局单例
 * - 层级查找支持 Capability 的继承和覆盖
 *
 * @param parent 父级 CapabilityHost，用于层级查找
 */
class ComposeCapabilityHost(
    private val parent: ComposeCapabilityHost? = null
) : CapabilityHost {
    private val tag = "CapabilityHost"
    // 使用同步包装，避免主线程注册/注销与后台线程查询之间的并发问题。
    // CapabilityRegistry 的工具调用链路会在后台线程读取此 Host，因此必须保证可见性。
    private val capabilities = java.util.Collections.synchronizedMap(
        LinkedHashMap<String, Capability>()
    )

    /**
     * 注册 Capability
     */
    fun register(capability: Capability) {
        synchronized(capabilities) {
            val existing = capabilities[capability.name]
            if (existing != null) {
                Logger.w(tag, "Capability '${capability.name}' already registered, replacing")
            }
            capabilities[capability.name] = capability
            Logger.i(tag, "Registered: ${capability.name} (total: ${capabilities.size})")
        }
    }

    /**
     * 注销 Capability
     */
    fun unregister(capability: Capability) {
        synchronized(capabilities) {
            val removed = capabilities.remove(capability.name)
            if (removed != null) {
                Logger.i(tag, "Unregistered: ${capability.name} (total: ${capabilities.size})")
            }
        }
    }

    /**
     * 按名称查找 Capability（支持层级查找）
     */
    fun find(name: String): Capability? {
        return synchronized(capabilities) { capabilities[name] } ?: parent?.find(name)
    }

    /**
     * 查找支持指定命令的 Capability（支持层级查找）
     */
    override fun findForCommand(commandName: String): Capability? {
        return synchronized(capabilities) {
            capabilities.values.find { it.supportedCommands().contains(commandName) }
        } ?: parent?.findForCommand(commandName)
    }

    /**
     * 获取指定场景下活跃的 Capability 列表
     */
    override fun findForScene(scene: SceneManager.Scene): List<Capability> {
        val local = synchronized(capabilities) {
            capabilities.values.filter {
                it.activeScenes().contains(scene) || it.activeScenes().isEmpty()
            }
        }
        val parentCapabilities = parent?.findForScene(scene) ?: emptyList()
        // 本地 Capability 优先（覆盖父级同名 Capability）
        val localNames = local.map { it.name }.toSet()
        return local + parentCapabilities.filter { it.name !in localNames }
    }

    /**
     * 获取所有 Capability（包含父级）
     */
    fun getAll(): List<Capability> {
        val local = synchronized(capabilities) { capabilities.values.toList() }
        val parentCapabilities = parent?.getAll() ?: emptyList()
        val localNames = local.map { it.name }.toSet()
        return local + parentCapabilities.filter { it.name !in localNames }
    }
}

/**
 * Compose CompositionLocal，用于在组件树中传递 CapabilityHost
 */
val LocalCapabilityHost = compositionLocalOf<ComposeCapabilityHost> {
    error("CapabilityHost not provided. Wrap your content with CapabilityHostProvider.")
}

/**
 * 全局 CapabilityHost 引用（用于非 Composable 上下文）
 *
 * 由 MainActivity 在创建根 CapabilityHost 时设置，
 * 供 CapabilityRegistry 等非 Composable 代码访问。
 */
object GlobalCapabilityHost {
    /** 空 stub：无宿主时让 CapabilityRegistry 回退到本地 registry，而非 crash。 */
    private val EMPTY_HOST = object : CapabilityHost {
        override fun findForScene(scene: SceneManager.Scene): List<Capability>? = null
        override fun findForCommand(commandName: String): Capability? = null
    }

    @Volatile
    private var host: ComposeCapabilityHost? = null

    fun set(host: ComposeCapabilityHost) {
        this.host = host
        CapabilityHost.set(host)
    }

    fun get(): ComposeCapabilityHost? = host

    /**
     * 清空全局宿主。仅当当前宿主就是 [expected] 时才清空：
     * Activity recreate 时新旧 composition 短暂共存，旧宿主的 onDispose 可能晚于
     * 新宿主的 set() 执行；无条件 clear 会把新宿主覆盖成空 stub，导致本进程内
     * Compose 注册的 Capability（chat_run_script 等）全部不可见。
     */
    fun clear(expected: ComposeCapabilityHost) {
        if (host === expected) {
            host = null
            CapabilityHost.set(EMPTY_HOST)
        }
    }
}

/**
 * 创建并记住 CapabilityHost
 *
 * @param parent 父级 CapabilityHost，默认为当前 CompositionLocal 中的值
 * @param capabilities 要注册到该宿主的能力列表
 * @return 创建的 CapabilityHost
 */
@Composable
fun rememberCapabilityHost(
    vararg capabilities: Capability,
    parent: ComposeCapabilityHost? = null
): ComposeCapabilityHost {
    val localHost = runCatching { LocalCapabilityHost.current }.getOrNull()
    val resolvedParent = parent ?: localHost
    return remember(*capabilities) {
        ComposeCapabilityHost(resolvedParent).apply {
            capabilities.forEach { register(it) }
        }
    }
}

/**
 * 在 Compose 中注册 Capability 的便捷函数
 *
 * 自动处理注册和注销生命周期。
 *
 * @param capability 要注册的 Capability
 * @param host 目标 CapabilityHost，默认为 LocalCapabilityHost.current
 */
@Composable
fun RegisterCapability(
    capability: Capability,
    host: ComposeCapabilityHost = LocalCapabilityHost.current
) {
    DisposableEffect(capability, host) {
        host.register(capability)
        onDispose {
            host.unregister(capability)
        }
    }
}
