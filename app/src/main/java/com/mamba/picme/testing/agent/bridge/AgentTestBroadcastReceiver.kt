package com.mamba.picme.testing.agent.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mamba.picme.agent.core.inference.local.parser.LocalCommandParser
import com.mamba.picme.agent.core.runtime.capability.CapabilityRegistry
import com.mamba.picme.agent.core.runtime.state.SceneManager
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentScene
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.beauty.api.BeautySettings
import com.mamba.picme.beauty.api.FilterType
import com.mamba.picme.beauty.api.StyleFilter
import com.mamba.picme.core.common.Logger
import com.mamba.picme.domain.tag.FaceClusterEngine
import com.mamba.picme.testing.agent.cases.BeautyAgentTestCases
import com.mamba.picme.service.tag.TagGenerationService
import com.mamba.picme.testing.agent.cases.CameraAgentTestCases
import com.mamba.picme.PicMeApplication
import org.json.JSONArray
import com.mamba.picme.testing.agent.core.AgentTestCase
import com.mamba.picme.testing.agent.core.AgentTestResult
import com.mamba.picme.testing.agent.device.DeviceTestController
import com.mamba.picme.testing.agent.runner.AgentTestRunner
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Agent 测试广播接收器
 *
 * 接收来自 AI Agent 的测试命令，通过广播触发设备端测试执行。
 *
 * ## 命令格式
 *
 * ```bash
 * # 运行相机模块全部测试
 * adb shell am broadcast -a com.mamba.picme.AGENT_TEST --es suite "camera"
 *
 * # 运行美颜模块全部测试
 * adb shell am broadcast -a com.mamba.picme.AGENT_TEST --es suite "beauty"
 *
 * # 运行 P0 回归测试
 * adb shell am broadcast -a com.mamba.picme.AGENT_TEST --es suite "p0"
 *
 * # 运行单个测试用例
 * adb shell am broadcast -a com.mamba.picme.AGENT_TEST --es case "TC-CAMERA-01"
 *
 * # 获取测试状态
 * adb shell am broadcast -a com.mamba.picme.AGENT_TEST --es action "status"
 *
 * # 导出报告
 * adb shell am broadcast -a com.mamba.picme.AGENT_TEST --es action "export_report"
 * ```
 *
 * ## 响应格式
 *
 * 测试结果通过广播发送回 AI Agent：
 * ```json
 * {
 *   "type": "test_result",
 *   "caseId": "TC-CAMERA-01",
 *   "status": "passed",
 *   "durationMs": 5234,
 *   "screenshots": ["/sdcard/..."]
 * }
 * ```
 */
class AgentTestBroadcastReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AgentTestReceiver"
        const val ACTION_AGENT_TEST = "com.mamba.picme.AGENT_TEST"

        // 命令字段
        private const val EXTRA_SUITE = "suite"
        private const val EXTRA_CASE = "case"
        private const val EXTRA_ACTION = "action"

        // 单命令执行字段（V2: PC端直接驱动）
        private const val EXTRA_CMD = "cmd"
        private const val EXTRA_PARAM = "param"

        // JSON 命令字段（V2.1: 与 Agent 命令格式对齐）
        private const val EXTRA_JSON = "json"

        // 响应 Action
        const val ACTION_TEST_RESPONSE = "com.mamba.picme.AGENT_TEST_RESPONSE"
        const val EXTRA_RESPONSE_JSON = "response_json"

        // 单例 Runner
        private var runner: AgentTestRunner? = null
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        fun initialize(context: Context) {
            if (runner == null) {
                runner = AgentTestRunner(context.applicationContext)
            }
        }

        fun release() {
            runner?.release()
            runner = null
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        // 强制使用系统Log确保日志可见（调试用）
        android.util.Log.i(TAG, "=== onReceive called: action=${intent.action}, extras=${intent.extras?.keySet()} ===")

        if (intent.action != ACTION_AGENT_TEST) {
            Logger.w(TAG, "Unexpected action: ${intent.action}")
            return
        }

        initialize(context)
        val runner = runner ?: return
        val controller = DeviceTestController(context)

        when {
            intent.hasExtra(EXTRA_SUITE) -> handleSuiteCommand(intent, runner, context)
            intent.hasExtra(EXTRA_CASE) -> handleCaseCommand(intent, runner, controller, context)
            intent.hasExtra(EXTRA_JSON) -> handleJsonCommand(intent, context)
            intent.hasExtra(EXTRA_CMD) -> handleSingleCommand(intent, context)
            intent.hasExtra(EXTRA_ACTION) -> handleActionCommand(intent, runner, context)
            else -> {
                Logger.w(TAG, "No valid command found in intent")
                sendResponse(context, createErrorResponse("No valid command"))
            }
        }
    }

    private fun handleSuiteCommand(intent: Intent, runner: AgentTestRunner, context: Context) {
        val suite = intent.getStringExtra(EXTRA_SUITE) ?: return
        Logger.i(TAG, "Running suite: $suite")

        scope.launch {
            when (suite.lowercase()) {
                "camera" -> {
                    runner.runCameraTests { report ->
                        sendResponse(context, report.toJson().toString())
                    }
                }
                "beauty" -> {
                    runner.runBeautyTests { report ->
                        sendResponse(context, report.toJson().toString())
                    }
                }
                "p0", "regression" -> {
                    runner.runP0Regression { report ->
                        sendResponse(context, report.toJson().toString())
                    }
                }
                else -> {
                    sendResponse(context, createErrorResponse("Unknown suite: $suite"))
                }
            }
        }
    }

    private fun handleCaseCommand(
        intent: Intent,
        runner: AgentTestRunner,
        controller: DeviceTestController,
        context: Context
    ) {
        val caseId = intent.getStringExtra(EXTRA_CASE) ?: return
        Logger.i(TAG, "Running case: $caseId")

        scope.launch {
            val case = findCaseById(caseId, controller)
            if (case != null) {
                runner.runCase(case) { result ->
                    val json = when (result) {
                        is AgentTestResult.Success -> {
                            JSONObject().apply {
                                put("type", "test_result")
                                put("caseId", caseId)
                                put("status", "passed")
                                put("context", result.context.toString())
                            }.toString()
                        }
                        is AgentTestResult.Failure -> {
                            JSONObject().apply {
                                put("type", "test_result")
                                put("caseId", caseId)
                                put("status", "failed")
                                put("failedStep", result.failedStep)
                                put("reason", result.reason)
                                put("context", result.context.toString())
                            }.toString()
                        }
                    }
                    sendResponse(context, json)
                }
            } else {
                sendResponse(context, createErrorResponse("Case not found: $caseId"))
            }
        }
    }

    /**
     * V2.1: 处理 JSON 格式命令（与 Agent 命令格式完全对齐）
     *
     * PC 端不做任何解析，直接把 action JSON 透传过来。
     * 应用端复用 LocalCommandParser 解析为 AgentCommand，再分发执行。
     *
     * JSON 格式示例：
     * {"method":"switch_filter","params":{"filter":"leica_vibrant"}}
     */
    private fun handleJsonCommand(intent: Intent, context: Context) {
        val json = intent.getStringExtra(EXTRA_JSON) ?: return
        Logger.i(TAG, "V2.1 JSON command: $json")

        // TAG 扫描命令：直接启动 TagGenerationService，不经过 CapabilityRegistry
        val jsonObj = try {
            org.json.JSONObject(json)
        } catch (e: Exception) {
            null
        }
        val method = jsonObj?.optString("method", "")
        if (method != null && handleTagScanCommand(method, context, jsonObj.optJSONObject("params"))) {
            sendResponse(context, JSONObject().apply {
                put("type", "cmd_result")
                put("method", method)
                put("status", "success")
            }.toString())
            return
        }

        val registry = CapabilityRegistry.getInstance()
        val command = LocalCommandParser.parseLlmResponse(
            json,
            AgentContext(scene = AgentScene.CAMERA)
        )

        scope.launch {
            try {
                // 如果当前场景不是 CAMERA，先导航到相机页面
                // 例外：NavigateTo 和 GoBack 不需要自动导航（它们本身就是导航命令）
                val currentScene = SceneManager.getInstance().currentScene.value
                if (currentScene != SceneManager.Scene.CAMERA &&
                    command !is AgentCommand.NavigateTo &&
                    command !is AgentCommand.GoBack
                ) {
                    Logger.i(TAG, "Current scene is $currentScene, navigating to CAMERA first")
                    registry.dispatch(
                        AgentCommand.NavigateTo(destination = "camera"),
                        AgentContext(scene = AgentScene.CAMERA)
                    )
                    // 等待场景切换和 delegate 绑定
                    var waitCount = 0
                    while (SceneManager.getInstance().currentScene.value != SceneManager.Scene.CAMERA && waitCount < 20) {
                        kotlinx.coroutines.delay(100)
                        waitCount++
                    }
                    // 额外等待 delegate 绑定
                    kotlinx.coroutines.delay(300)
                    Logger.i(TAG, "Navigation wait done, scene=${SceneManager.getInstance().currentScene.value}")
                }

                val result = registry.dispatch(
                    command,
                    AgentContext(scene = AgentScene.CAMERA)
                )

                val response = when (val action = result.getOrNull()) {
                    is AgentAction.Success -> JSONObject().apply {
                        put("type", "cmd_result")
                        put("method", AgentCommand.getMethodName(command))
                        put("status", "success")
                    }.toString()
                    is AgentAction.Error -> JSONObject().apply {
                        put("type", "cmd_result")
                        put("method", AgentCommand.getMethodName(command))
                        put("status", "error")
                        put("message", action.message)
                    }.toString()
                    else -> JSONObject().apply {
                        put("type", "cmd_result")
                        put("method", AgentCommand.getMethodName(command))
                        put("status", "ok")
                    }.toString()
                }
                Logger.i(TAG, "JSON command executed: ${(result.getOrNull() as? AgentAction.Success) != null}")
                sendResponse(context, response)
            } catch (e: Exception) {
                Logger.e(TAG, "JSON command failed", e)
                sendResponse(context, createErrorResponse("Command failed: ${e.message}"))
            }
        }
    }

    /**
     * V2: 处理单个操作命令（由PC端adb直接驱动）
     *
     * 通过 CapabilityRegistry 直接分发命令，无需JSON测试用例包装。
     * 支持所有 AgentCommand 类型。
     *
     * 已废弃：保留用于兼容旧调用方式，新代码请使用 --es json 传递完整 JSON。
     */
    private fun handleSingleCommand(intent: Intent, context: Context) {
        val cmd = intent.getStringExtra(EXTRA_CMD) ?: return
        val param = intent.getStringExtra(EXTRA_PARAM)
        Logger.i(TAG, "V2 Single command: cmd=$cmd, param=$param (deprecated, use --es json)")

        // TAG 扫描命令：直接启动 TagGenerationService，不经过 CapabilityRegistry
        if (handleTagScanCommand(cmd, context)) {
            sendResponse(context, JSONObject().apply {
                put("type", "cmd_result")
                put("cmd", cmd)
                put("status", "success")
            }.toString())
            return
        }

        val registry = CapabilityRegistry.getInstance()
        val command = parseCommand(cmd, param)

        scope.launch {
            try {
                val result = registry.dispatch(
                    command,
                    AgentContext(scene = AgentScene.CAMERA)
                )

                val response = when (val action = result.getOrNull()) {
                    is AgentAction.Success -> JSONObject().apply {
                        put("type", "cmd_result")
                        put("cmd", cmd)
                        put("status", "success")
                    }.toString()
                    is AgentAction.Error -> JSONObject().apply {
                        put("type", "cmd_result")
                        put("cmd", cmd)
                        put("status", "error")
                        put("message", action.message)
                    }.toString()
                    else -> JSONObject().apply {
                        put("type", "cmd_result")
                        put("cmd", cmd)
                        put("status", "ok")
                    }.toString()
                }
                Logger.i(TAG, "Command $cmd executed: ${(result.getOrNull() as? AgentAction.Success) != null}")
                sendResponse(context, response)
            } catch (e: Exception) {
                Logger.e(TAG, "Command $cmd failed", e)
                sendResponse(context, createErrorResponse("Command failed: ${e.message}"))
            }
        }
    }

    /**
     * 将字符串命令解析为 AgentCommand
     */
    private fun parseCommand(cmd: String, param: String?): AgentCommand {
        return when (cmd.lowercase()) {
            "capture" -> AgentCommand.CapturePhoto()
            "flip_camera" -> AgentCommand.FlipCamera()
            "toggle_recording" -> AgentCommand.ToggleRecording()
            "navigate_to" -> AgentCommand.NavigateTo(
                destination = param ?: throw IllegalArgumentException("navigate_to requires destination")
            )
            "go_back" -> AgentCommand.GoBack()
            "adjust_beauty" -> {
                val params = parseParamMap(param)
                AgentCommand.AdjustBeauty(
                    settings = BeautySettings(
                        enabled = true,
                        smoothing = (params["smoothing"] ?: "0").toFloat(),
                        whitening = (params["whitening"] ?: "0").toFloat(),
                        slimFace = (params["slimFace"] ?: "0").toFloat(),
                        bigEyes = (params["bigEyes"] ?: "0").toFloat()
                    )
                )
            }
            "switch_filter" -> {
                val params = parseParamMap(param)
                AgentCommand.SwitchFilter(
                    filterType = parseFilterType(params["filter"] ?: param ?: "none")
                )
            }
            "switch_style" -> {
                val params = parseParamMap(param)
                AgentCommand.SwitchStyle(
                    styleFilter = parseStyleFilter(params["style"] ?: param ?: "none")
                )
            }
            "switch_scene" -> {
                val params = parseParamMap(param)
                AgentCommand.SwitchScene(
                    sceneName = params["scene"] ?: param ?: "none"
                )
            }
            "switch_ratio" -> {
                val params = parseParamMap(param)
                AgentCommand.SwitchRatio(
                    ratio = params["ratio"] ?: param ?: "4_3"
                )
            }
            "adjust_exposure" -> {
                val params = parseParamMap(param)
                AgentCommand.AdjustExposure(
                    exposure = params["exposure"]?.toIntOrNull() ?: param?.toIntOrNull() ?: 0
                )
            }
            "adjust_zoom" -> {
                val params = parseParamMap(param)
                AgentCommand.AdjustZoom(
                    zoomRatio = params["zoom"]?.toFloatOrNull() ?: param?.toFloatOrNull() ?: 1.0f
                )
            }
            "switch_mode" -> {
                val params = parseParamMap(param)
                AgentCommand.SwitchMode(
                    mode = parseMediaType(params["mode"] ?: param ?: "photo")
                )
            }
            "switch_face_engine" -> {
                val params = parseParamMap(param)
                AgentCommand.SwitchFaceEngine(
                    engine = params["engine"] ?: param ?: throw IllegalArgumentException("switch_face_engine requires engine")
                )
            }
            "toggle_setting" -> {
                val params = parseParamMap(param)
                AgentCommand.ToggleSetting(
                    settingKey = params["settingKey"]
                        ?: throw IllegalArgumentException("toggle_setting requires settingKey"),
                    enabled = (params["enabled"] ?: "true").toBooleanStrictOrNull() ?: true
                )
            }
            "change_theme" -> AgentCommand.ChangeTheme(
                theme = param ?: "light"
            )
            "change_language" -> AgentCommand.ChangeLanguage(
                language = param ?: "zh"
            )
            "download_model" -> AgentCommand.DownloadModel(
                modelId = param ?: throw IllegalArgumentException("download_model requires modelId")
            )
            "view_media" -> AgentCommand.ViewMedia(mediaId = param)
            "delete_media" -> {
                val ids = param?.split(",") ?: emptyList()
                AgentCommand.DeleteMedia(mediaIds = ids)
            }
            "share_media" -> {
                val ids = param?.split(",") ?: emptyList()
                AgentCommand.ShareMedia(mediaIds = ids)
            }
            "select_media" -> {
                val params = parseParamMap(param)
                AgentCommand.SelectMedia(
                    mediaId = params["mediaId"]
                        ?: throw IllegalArgumentException("select_media requires mediaId"),
                    selected = (params["selected"] ?: "true").toBooleanStrictOrNull() ?: true
                )
            }
            "search_media" -> AgentCommand.SearchMedia(
                query = param ?: throw IllegalArgumentException("search_media requires query")
            )
            "favorite_media" -> {
                val params = parseParamMap(param)
                AgentCommand.FavoriteMedia(
                    mediaId = params["mediaId"]
                        ?: throw IllegalArgumentException("favorite_media requires mediaId"),
                    favorite = (params["favorite"] ?: "true").toBooleanStrictOrNull() ?: true
                )
            }
            else -> throw IllegalArgumentException("Unknown command: $cmd")
        }
    }

    /**
     * 处理 TAG 扫描命令：直接启动 TagGenerationService
     *
     * 支持命令：
     * - scan_pass1_full / scan_pass1：全量 Pass 1 人脸检测
     * - scan_pass2_full / scan_pass2：全量 Pass 2 DBSCAN 聚类
     * - scan_pass3_full / scan_pass3：全量 Pass 3 Qwen 标签生成
     * - scan_pass4_full / scan_pass4：全量 MobileCLIP 语义编码
     * - scan_mlkit_full / scan_mlkit：全量 ML Kit 标签提取
     * - scan_all：全量 3-Pass 扫描
     * - cancel：取消扫描
     * - dump_face_embeddings [path]：导出所有 face embeddings 到 JSONL（默认 externalCacheDir/face_embeddings.jsonl）
     * - backup_tag_data [path]：备份 TAG 数据到 JSON（默认 externalFilesDir/backups/tag_data_backup.json）
     * - restore_tag_data [path] [--dry-run]：从 JSON 还原 TAG 数据
     *
     * @return true 如果 cmd 是 TAG 扫描命令并已处理
     */
    private fun handleTagScanCommand(
        cmd: String,
        context: Context,
        params: org.json.JSONObject? = null
    ): Boolean {
        when (cmd.lowercase()) {
            "dump_face_embeddings" -> {
                scope.launch {
                    try {
                        val outputPath = params?.optString("path")
                            ?: context.externalCacheDir?.let { File(it, "face_embeddings.jsonl").absolutePath }
                            ?: File(context.cacheDir, "face_embeddings.jsonl").absolutePath
                        val engine = FaceClusterEngine(context)
                        engine.dumpEmbeddingsForAnalysis(File(outputPath))
                        sendResponse(context, JSONObject().apply {
                            put("type", "cmd_result")
                            put("cmd", cmd)
                            put("status", "success")
                            put("path", outputPath)
                        }.toString())
                    } catch (e: Exception) {
                        Logger.e(TAG, "dump_face_embeddings failed", e)
                        sendResponse(context, createErrorResponse("dump_face_embeddings failed: ${e.message}"))
                    }
                }
                return true
            }
            "backup_tag_data" -> {
                scope.launch {
                    try {
                        val app = context.applicationContext as PicMeApplication
                        val customPath = params?.optString("path")
                        val customFile = customPath?.let { File(it) }
                        val result = app.container.backupTagDataUseCase(customFile)
                        val resultJson = JSONObject().apply {
                            put("type", "cmd_result")
                            put("cmd", cmd)
                            put("status", "success")
                            put("path", result.file.absolutePath)
                            put("tagCount", result.tagCount)
                            put("mediaCount", result.mediaCount)
                            put("crossRefCount", result.crossRefCount)
                            put("scanTaskCount", result.scanTaskCount)
                            put("personCount", result.personCount)
                            put("faceEmbeddingCount", result.faceEmbeddingCount)
                            put("ocrWordCount", result.ocrWordCount)
                            put("ocrWordOccurrenceCount", result.ocrWordOccurrenceCount)
                            put("locationCount", result.locationCount)
                            put("mediaLocationCount", result.mediaLocationCount)
                            put("preferenceCount", result.preferenceCount)
                        }.toString()
                        writeOperationResult(context, result.file, resultJson)
                        sendResponse(context, resultJson)
                    } catch (e: Exception) {
                        Logger.e(TAG, "backup_tag_data failed", e)
                        sendResponse(context, createErrorResponse("backup_tag_data failed: ${e.message}"))
                    }
                }
                return true
            }
            "restore_tag_data" -> {
                scope.launch {
                    try {
                        val app = context.applicationContext as PicMeApplication
                        val customPath = params?.optString("path")
                        val customFile = customPath?.let { File(it) }
                        val dryRun = params?.optBoolean("dryRun", false) ?: false
                        val result = app.container.restoreTagDataUseCase(customFile, dryRun)
                        val resultJson = JSONObject().apply {
                            put("type", "cmd_result")
                            put("cmd", cmd)
                            put("status", "success")
                            put("dryRun", dryRun)
                            put("matchedMediaCount", result.matchedMediaCount)
                            put("unmatchedMediaCount", result.unmatchedUris.size)
                            put("restoredTagCount", result.restoredTagCount)
                            put("restoredCrossRefCount", result.restoredCrossRefCount)
                            put("restoredScanTaskCount", result.restoredScanTaskCount)
                            put("restoredMetadataCount", result.restoredMetadataCount)
                            put("restoredPersonCount", result.restoredPersonCount)
                            put("restoredFaceEmbeddingCount", result.restoredFaceEmbeddingCount)
                            put("restoredOcrWordCount", result.restoredOcrWordCount)
                            put("restoredOcrWordOccurrenceCount", result.restoredOcrWordOccurrenceCount)
                            put("restoredLocationCount", result.restoredLocationCount)
                            put("restoredMediaLocationCount", result.restoredMediaLocationCount)
                            put("restoredPreferenceCount", result.restoredPreferenceCount)
                            put("unmatchedUris", JSONArray(result.unmatchedUris))
                        }.toString()
                        val backupFile = customFile ?: app.container.backupTagDataUseCase.defaultBackupFile()
                        writeOperationResult(context, backupFile, resultJson)
                        sendResponse(context, resultJson)
                    } catch (e: Exception) {
                        Logger.e(TAG, "restore_tag_data failed", e)
                        sendResponse(context, createErrorResponse("restore_tag_data failed: ${e.message}"))
                    }
                }
                return true
            }
        }

        val serviceIntent = when (cmd.lowercase()) {
            "scan_pass1_full", "scan_pass1" -> TagGenerationService.intentScanPass1Full(context)
            "scan_pass1_incremental" -> TagGenerationService.intentScanPass1(context)
            "scan_pass2_full", "scan_pass2" -> TagGenerationService.intentScanPass2Full(context)
            "scan_pass2_incremental" -> TagGenerationService.intentScanPass2(context)
            "scan_pass3_full", "scan_pass3" -> TagGenerationService.intentScanPass3Full(context)
            "scan_pass3_incremental" -> TagGenerationService.intentScanPass3(context)
            "scan_pass4_full", "scan_pass4" -> TagGenerationService.intentScanPass4Full(context)
            "scan_pass4_incremental" -> TagGenerationService.intentScanPass4(context)
            "scan_mlkit_full", "scan_mlkit" -> TagGenerationService.intentScanPassMlKitFull(context)
            "scan_mlkit_incremental" -> TagGenerationService.intentScanPassMlKit(context)
            "scan_all" -> TagGenerationService.intentScanAll(context)
            "scan_incremental" -> TagGenerationService.intentScanIncremental(context)
            "cancel" -> TagGenerationService.intentCancel(context)
            else -> return false
        }
        Logger.i(TAG, "Starting TagGenerationService: $cmd")
        context.startForegroundService(serviceIntent)
        return true
    }

    /**
     * 解析 key=value,key2=value2 格式的参数字符串
     */
    private fun parseParamMap(param: String?): Map<String, String> {
        if (param.isNullOrBlank()) return emptyMap()
        return param.split(",").associate {
            val parts = it.split("=", limit = 2)
            parts[0].trim() to (parts.getOrNull(1)?.trim() ?: "")
        }
    }

    private fun parseFilterType(filter: String) = FilterType.valueOf(
        filter.uppercase().replace("LEICA_CLASSIC", "LEICA_CLASSIC")
            .replace("LEICA_VIBRANT", "LEICA_VIBRANT")
            .replace("LEICA_BW", "LEICA_BW")
    )

    private fun parseStyleFilter(style: String) = StyleFilter.valueOf(
        style.uppercase()
    )

    private fun parseMediaType(mode: String) = when (mode.lowercase()) {
        "photo" -> MediaType.PHOTO
        "video" -> MediaType.VIDEO
        else -> MediaType.PHOTO
    }

    private fun handleActionCommand(intent: Intent, runner: AgentTestRunner, context: Context) {
        val action = intent.getStringExtra(EXTRA_ACTION) ?: return
        Logger.i(TAG, "Handling action: $action")

        when (action.lowercase()) {
            "status" -> {
                val state = runner.state.value
                val progress = runner.progress.value
                val response = JSONObject().apply {
                    put("type", "status")
                    put("state", state::class.simpleName)
                    put("currentIndex", progress.currentIndex)
                    put("totalCases", progress.totalCases)
                }.toString()
                sendResponse(context, response)
            }
            "export_report" -> {
                val path = runner.exportJsonReport()
                val mdReport = runner.generateMarkdownReport()
                val response = JSONObject().apply {
                    put("type", "report")
                    put("jsonPath", path)
                    put("markdown", mdReport)
                }.toString()
                sendResponse(context, response)
            }
            else -> {
                sendResponse(context, createErrorResponse("Unknown action: $action"))
            }
        }
    }

    private fun findCaseById(caseId: String, controller: DeviceTestController): AgentTestCase<*>? {
        val allCases = mutableListOf<AgentTestCase<*>>()
        allCases.addAll(CameraAgentTestCases.allCases(controller))
        allCases.addAll(BeautyAgentTestCases.allCases(controller))
        return allCases.find { it.id == caseId }
    }

    /**
     * 将操作结果写入备份文件同目录的 *.result.json，便于 PC 端脚本轮询检测完成状态。
     */
    private fun writeOperationResult(context: Context, backupFile: File, resultJson: String) {
        try {
            val resultFile = File(backupFile.parentFile, backupFile.name + ".result.json")
            resultFile.parentFile?.mkdirs()
            resultFile.writeText(resultJson)
            Logger.i(TAG, "Operation result written to ${resultFile.absolutePath}")
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to write operation result file", e)
        }
    }

    private fun sendResponse(context: Context, json: String) {
        try {
            val responseIntent = Intent(ACTION_TEST_RESPONSE).apply {
                putExtra(EXTRA_RESPONSE_JSON, json)
                `package` = context.packageName
            }
            context.sendBroadcast(responseIntent)
            Logger.i(TAG, "Response sent: ${json.take(200)}...")
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to send response", e)
        }
    }

    private fun createErrorResponse(message: String): String {
        return JSONObject().apply {
            put("type", "error")
            put("message", message)
        }.toString()
    }
}
