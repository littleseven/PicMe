package com.mamba.picme.domain.agent.remote

import com.lark.oapi.core.utils.Jsons
import com.lark.oapi.event.EventDispatcher
import com.lark.oapi.service.im.v1.model.CreateImageReq
import com.lark.oapi.service.im.v1.model.CreateImageReqBody
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1
import com.lark.oapi.service.im.v1.model.ReplyMessageReq
import com.lark.oapi.service.im.v1.model.ReplyMessageReqBody
import com.lark.oapi.ws.Client as FeishuWsClient
import com.mamba.picme.core.common.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * 飞书通道处理器
 *
 * 基于飞书 OAPI SDK，通过 WebSocket 直连飞书平台。
 * 参考 ApkClaw 的实现模式，适配 PoLang 项目。
 *
 * **职责**：
 * - 建立与飞书平台的 WebSocket 长连接（出站连接，无需公网 IP）
 * - 接收 IM 消息（P2MessageReceiveV1 事件）
 * - 通过飞书 OAPI HTTP 客户端回复文本/图片消息
 *
 * **生命周期**：
 * ```
 * Application.onCreate()
 *     └── FeishuChannelHandler.init(appId, appSecret)
 *             ├── apiClient = OAPI Client.Builder(appId, appSecret).build()
 *             ├── wsClient = WS Client.Builder(appId, appSecret)
 *             │       .eventHandler(eventHandler)
 *             │       .build()
 *             └── wsClient.start()  // 建立长连接
 * ```
 */
class FeishuChannelHandler(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : RemoteChannel {
    private var apiClient: com.lark.oapi.Client? = null
    private var wsClient: FeishuWsClient? = null
    private var appId: String = ""
    private var appSecret: String = ""

    /**
     * 连接状态标志
     * 用于 UI 展示和 reconnectIfNeeded 决策
     */
    @Volatile
    override var isConnected: Boolean = false
        private set

    /**
     * 最近一次成功收发消息的时间戳（毫秒）
     * 用于检测连接是否在合理时间内有过活动
     */
    @Volatile
    private var lastActivityTimeMs: Long = 0L

    /**
     * 连接状态回调（供 UI/日志使用）
     */
    override var onConnectionStateChanged: ((connected: Boolean) -> Unit)? = null

    /**
     * 消息接收回调（供 RemoteCommandDispatcher 使用）
     */
    override var onMessageReceived: ((text: String, replyToken: String) -> Unit)? = null


    private val eventHandler: EventDispatcher by lazy {
        EventDispatcher.newBuilder("", "")
            .onP2MessageReceiveV1(object : com.lark.oapi.service.im.ImService.P2MessageReceiveV1Handler() {
                override fun handle(event: P2MessageReceiveV1) {
                    handleMessageEvent(event)
                }
            })
            .build()
    }

    /**
     * 初始化飞书通道
     * 可在运行中多次调用以更新配置（先 disconnect 再 init）
     */
    fun init(appId: String, appSecret: String) {
        if (appId.isEmpty() || appSecret.isEmpty()) {
            Logger.w(TAG, "飞书 AppId/AppSecret 未配置，通道不可用")
            return
        }

        this.appId = appId
        this.appSecret = appSecret

        apiClient = com.lark.oapi.Client.newBuilder(appId, appSecret).build()
        wsClient = FeishuWsClient.Builder(appId, appSecret)
            .eventHandler(eventHandler)
            .build()

        scope.launch {
            try {
                wsClient?.start()
                isConnected = true
                lastActivityTimeMs = System.currentTimeMillis()
                onConnectionStateChanged?.invoke(true)
                Logger.i(TAG, "飞书 WebSocket 客户端已启动")
            } catch (e: Exception) {
                isConnected = false
                onConnectionStateChanged?.invoke(false)
                Logger.e(TAG, "飞书 WebSocket 启动失败", e)
            }
        }
    }

    /**
     * 断开飞书连接
     * 通过反射调用 SDK 内部方法，避免依赖 SDK 内部 API
     */
    fun disconnect() {
        val oldWsClient = wsClient ?: return
        wsClient = null
        apiClient = null
        isConnected = false
        onConnectionStateChanged?.invoke(false)

        try {
            // 禁用自动重连
            val autoReconnectField = oldWsClient.javaClass.getDeclaredField("autoReconnect")
            autoReconnectField.isAccessible = true
            autoReconnectField.set(oldWsClient, false)
        } catch (e: Exception) {
            Logger.w(TAG, "禁用自动重连失败", e)
        }

        try {
            val disconnectMethod = oldWsClient.javaClass.getDeclaredMethod("disconnect")
            disconnectMethod.isAccessible = true
            disconnectMethod.invoke(oldWsClient)
        } catch (e: Exception) {
            Logger.w(TAG, "调用 disconnect 失败", e)
        }

        try {
            val executorField = oldWsClient.javaClass.getDeclaredField("executor")
            executorField.isAccessible = true
            val executor = executorField.get(oldWsClient) as? java.util.concurrent.ExecutorService
            executor?.shutdownNow()
        } catch (e: Exception) {
            Logger.w(TAG, "关闭线程池失败", e)
        }

        Logger.i(TAG, "飞书 WebSocket 已断开")
    }

    /**
     * 重新初始化（从存储读取新配置后调用）
     */
    fun reinit(newAppId: String, newAppSecret: String) {
        disconnect()
        init(newAppId, newAppSecret)
    }

    /**
     * 检查连接状态，必要时重建连接。
     *
     * 参考 ApkClaw ChannelManager.reconnectIfNeeded()。
     * 在网络变化恢复或 App 从后台切回前台时调用。
     */
    fun reconnectIfNeeded() {
        if (appId.isEmpty() || appSecret.isEmpty()) {
            Logger.w(TAG, "reconnectIfNeeded 跳过：AppId/AppSecret 未配置")
            return
        }

        if (isConnected && apiClient != null) {
            Logger.d(TAG, "reconnectIfNeeded 跳过：连接正常")
            return
        }

        Logger.i(TAG, "reconnectIfNeeded：连接丢失，准备重建")
        // 调用前清理旧连接
        val oldWsClient = wsClient
        if (oldWsClient != null) {
            wsClient = null
            apiClient = null
            isConnected = false
            try {
                val autoReconnectField = oldWsClient.javaClass.getDeclaredField("autoReconnect")
                autoReconnectField.isAccessible = true
                autoReconnectField.set(oldWsClient, false)
            } catch (e: Exception) {
                Logger.w(TAG, "禁用自动重连失败", e)
            }
            try {
                val disconnectMethod = oldWsClient.javaClass.getDeclaredMethod("disconnect")
                disconnectMethod.isAccessible = true
                disconnectMethod.invoke(oldWsClient)
            } catch (e: Exception) {
                Logger.w(TAG, "调用 disconnect 失败", e)
            }
        }
        init(appId, appSecret)
    }

    // ==================== 消息发送 ====================

    /**
     * 回复文本消息
     * @param content 文本内容
     * @param messageId 要回复的消息 ID
     */
    override fun sendMessage(text: String, replyToken: String) {
        val client = apiClient
        if (client == null) {
            Logger.w(TAG, "发送消息失败：客户端未初始化")
            return
        }

        scope.launch {
            try {
                val isMarkdown = containsMarkdown(text)
                val msgType = if (isMarkdown) "post" else "text"
                val jsonContent = if (isMarkdown) buildPostJson(text) else buildTextJson(text)

                val resp = client.im().message().reply(
                    ReplyMessageReq.newBuilder()
                        .messageId(replyToken)
                        .replyMessageReqBody(
                            ReplyMessageReqBody.newBuilder()
                                .msgType(msgType)
                                .content(jsonContent)
                                .build()
                        )
                        .build()
                )
                if (resp.code == 0) {
                    lastActivityTimeMs = System.currentTimeMillis()
                }
                Logger.i(TAG, "飞书回复消息: code=${resp.code}, type=$msgType")
            } catch (e: Exception) {
                Logger.e(TAG, "飞书回复失败", e)
            }
        }
    }

    /**
     * 发送图片消息
     * @param imageBytes 图片字节数组
     * @param messageId 要回复的消息 ID
     */
    override fun sendImage(bytes: ByteArray, replyToken: String) {
        scope.launch {
            try {
                val imageKey = uploadImage(bytes)
                if (imageKey != null) {
                    replyImage(imageKey, replyToken)
                } else {
                    Logger.e(TAG, "图片上传失败，imageKey 为空")
                }
            } catch (e: Exception) {
                Logger.e(TAG, "发送图片失败", e)
            }
        }
    }

    /**
     * 发送文件消息（图片自动识别为 image 类型）
     */
    fun sendFile(file: File, messageId: String) {
        val client = apiClient
        if (client == null) {
            Logger.w(TAG, "发送文件失败：客户端未初始化")
            return
        }

        scope.launch {
            try {
                val isImage = file.name.let {
                    it.endsWith(".png", true) || it.endsWith(".jpg", true)
                            || it.endsWith(".jpeg", true) || it.endsWith(".gif", true)
                            || it.endsWith(".bmp", true)
                }

                if (isImage) {
                    val uploadResp = client.im().image().create(
                        CreateImageReq.newBuilder()
                            .createImageReqBody(
                                CreateImageReqBody.newBuilder()
                                    .imageType("message")
                                    .image(file)
                                    .build()
                            )
                            .build()
                    )
                    if (uploadResp.success()) {
                        val content = JSONObject().put("image_key", uploadResp.data.imageKey).toString()
                        client.im().message().reply(
                            ReplyMessageReq.newBuilder()
                                .messageId(messageId)
                                .replyMessageReqBody(
                                    ReplyMessageReqBody.newBuilder()
                                        .msgType("image")
                                        .content(content)
                                        .build()
                                )
                                .build()
                        )
                        Logger.i(TAG, "图片发送成功: ${file.name}")
                    } else {
                        Logger.e(TAG, "图片上传失败: code=${uploadResp.code}, msg=${uploadResp.msg}")
                    }
                } else {
                    val uploadResp = client.im().file().create(
                        com.lark.oapi.service.im.v1.model.CreateFileReq.newBuilder()
                            .createFileReqBody(
                                com.lark.oapi.service.im.v1.model.CreateFileReqBody.newBuilder()
                                    .fileType("stream")
                                    .fileName(file.name)
                                    .file(file)
                                    .build()
                            )
                            .build()
                    )
                    if (uploadResp.success()) {
                        val content = JSONObject().put("file_key", uploadResp.data.fileKey).toString()
                        client.im().message().reply(
                            ReplyMessageReq.newBuilder()
                                .messageId(messageId)
                                .replyMessageReqBody(
                                    ReplyMessageReqBody.newBuilder()
                                        .msgType("file")
                                        .content(content)
                                        .build()
                                )
                                .build()
                        )
                        Logger.i(TAG, "文件发送成功: ${file.name}")
                    } else {
                        Logger.e(TAG, "文件上传失败: code=${uploadResp.code}, msg=${uploadResp.msg}")
                    }
                }
            } catch (e: Exception) {
                Logger.e(TAG, "发送文件失败", e)
            }
        }
    }

    // ==================== 内部方法 ====================

    private fun handleMessageEvent(event: P2MessageReceiveV1) {
        try {
            Logger.i(TAG, "收到飞书消息: ${Jsons.DEFAULT.toJson(event.event)}")

            val messageId = event.event.message.messageId
            val messageType = event.event.message.messageType
            val createTime = event.event.message.createTime

            // 忽略超过 5 分钟的旧消息
            val fiveMinutesInMillis = 5 * 60 * 1000
            val currentTime = System.currentTimeMillis()
            if (createTime != null && (currentTime - createTime.toLong() > fiveMinutesInMillis)) {
                Logger.i(TAG, "忽略超过5分钟的消息: messageId=$messageId")
                return
            }

            if ("text" == messageType) {
                val rawContent = event.event.message.content
                val text = try {
                    JSONObject(rawContent).optString("text", "")
                } catch (e: Exception) {
                    rawContent
                }
                lastActivityTimeMs = System.currentTimeMillis()
                onMessageReceived?.invoke(text, messageId)
            }
        } catch (e: Exception) {
            Logger.e(TAG, "处理飞书消息异常", e)
        }
    }

    private fun uploadImage(imageBytes: ByteArray): String? {
        val client = apiClient ?: return null
        val tempFile = File.createTempFile("feishu_img_", ".png").apply {
            writeBytes(imageBytes)
            deleteOnExit()
        }
        return try {
            val resp = client.im().image().create(
                CreateImageReq.newBuilder()
                    .createImageReqBody(
                        CreateImageReqBody.newBuilder()
                            .imageType("message")
                            .image(tempFile)
                            .build()
                    )
                    .build()
            )
            if (resp.success()) {
                Logger.i(TAG, "图片上传成功: imageKey=${resp.data.imageKey}")
                resp.data.imageKey
            } else {
                Logger.e(TAG, "图片上传失败: code=${resp.code}, msg=${resp.msg}")
                null
            }
        } catch (e: Exception) {
            Logger.e(TAG, "图片上传异常", e)
            null
        } finally {
            tempFile.delete()
        }
    }

    private fun replyImage(imageKey: String, messageId: String) {
        val client = apiClient ?: return
        scope.launch {
            try {
                val content = JSONObject().put("image_key", imageKey).toString()
                val resp = client.im().message().reply(
                    ReplyMessageReq.newBuilder()
                        .messageId(messageId)
                        .replyMessageReqBody(
                            ReplyMessageReqBody.newBuilder()
                                .msgType("image")
                                .content(content)
                                .build()
                        )
                        .build()
                )
                Logger.i(TAG, "图片回复: code=${resp.code}")
            } catch (e: Exception) {
                Logger.e(TAG, "图片回复失败", e)
            }
        }
    }

    private val markdownPatterns = listOf(
        Regex("""\*\*.+?\*\*"""),
        Regex("""^#{1,6}\s""", RegexOption.MULTILINE),
        Regex("""```"""),
        Regex("""\[.+?]\(.+?\)"""),
        Regex("""^\|.+\|.+\|""", RegexOption.MULTILINE),
        Regex("""~~.+?~~"""),
        Regex("""^>\s""", RegexOption.MULTILINE),
        Regex("""^- \[[ x]]""", RegexOption.MULTILINE),
    )

    private fun containsMarkdown(text: String): Boolean =
        markdownPatterns.any { it.containsMatchIn(text) }

    private fun buildPostJson(content: String): String {
        val postContent = org.json.JSONArray().apply {
            put(org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("tag", "md")
                    put("text", content)
                })
            })
        }
        return JSONObject().apply {
            put("zh_cn", JSONObject().apply {
                put("content", postContent)
            })
        }.toString()
    }

    private fun buildTextJson(content: String): String =
        JSONObject().put("text", content).toString()

    override val channelId: String = "feishu"

    companion object {
        private const val TAG = "FeishuHandler"
    }
}
