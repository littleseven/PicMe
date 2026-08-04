@file:Suppress("TooGenericExceptionCaught") // 通用兜底：catch(Exception) 防崩溃，已记录日志
package com.mamba.picme.features.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mamba.picme.PoLangApplication
import com.mamba.picme.R
import com.mamba.picme.agent.core.js.JsRuntime
import com.mamba.picme.di.AppContainer
import com.mamba.picme.features.common.topbar.AppTopBar
import com.mamba.picme.features.chat.js.QuickJsEngine
import com.mamba.picme.features.chat.js.registerGalleryHandlers
import com.mamba.picme.service.tag.TagGenerationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** 默认演示脚本（同步 handler，结果可立即可视化；可在输入框编辑）。
 *  按「async 函数体」语义执行（evalAsync）：顶层 return/await 合法，无需自包 IIFE。 */
private const val DEFAULT_DEMO_SCRIPT = """console.log("demo start");
var sum = bridge.call("math.add", [18, 24]);
console.log("math.add =>", sum);
var up = bridge.call("string.upper", "polang");
console.log("string.upper =>", up);
console.log("handlers =>", bridge.list());
return { sum: sum, upper: up };"""

/**
 * Debug 页「JS Bridge」区块：可编辑脚本 + 运行按钮 + 输出区。
 *
 * 可视化：console.log 与 eval 结果都展示在输出区（不再只进 logcat / Toast 一闪而过）。
 * JS 仅能通过 bridge 间接访问原生（QuickJS 沙箱：无 LiveConnect，仅 bridge 通道）。
 */
@Suppress("LongMethod") // 待重构：抽 demo 项子组件
@Composable
fun JsBridgeDebugSection() {
    val context = LocalContext.current
    val app = context.applicationContext as PoLangApplication
    val uiScope = rememberCoroutineScope()
    var script by remember { mutableStateOf(DEFAULT_DEMO_SCRIPT) }
    val output = remember { mutableStateListOf<String>() }
    var running by remember { mutableStateOf(false) }

    // 预置脚本快捷加载
    Text("预置脚本", style = MaterialTheme.typography.titleSmall)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PresetScriptButton("Bridge 演示", app.applicationScope, context) { loaded ->
            script = loaded
        }
        PresetScriptButton("相册盘点", app.applicationScope, context, "js/gallery_inventory_demo.js") { loaded ->
            script = loaded
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PresetScriptButton("时间线分析", app.applicationScope, context, "js/gallery_timeline_analysis.js") { loaded ->
            script = loaded
        }
        PresetScriptButton("健康度报告", app.applicationScope, context, "js/gallery_health_report.js") { loaded ->
            script = loaded
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PresetScriptButton("智能清理", app.applicationScope, context, "js/gallery_smart_cleanup.js") { loaded ->
            script = loaded
        }
        PresetScriptButton("交叉分析", app.applicationScope, context, "js/gallery_cross_analysis.js") { loaded ->
            script = loaded
        }
    }

    Spacer(Modifier.height(8.dp))

    OutlinedTextField(
        value = script,
        onValueChange = { script = it },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp),
        label = { Text(stringResource(R.string.jsbridge_debug_script_hint)) },
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        maxLines = 10,
        enabled = !running,
    )

    Spacer(Modifier.height(8.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = {
                output.clear()
                output += "▶ ${context.getString(R.string.jsbridge_debug_running)}"
                running = true
                runJsBridgeDemo(
                    scope = app.applicationScope,
                    container = app.container,
                    script = script,
                    onOutput = { line -> uiScope.launch { output += line } },
                    onDone = { uiScope.launch { running = false } },
                )
            },
            enabled = !running,
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.Default.Code, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.jsbridge_debug_run_demo))
        }
        OutlinedButton(
            onClick = { output.clear() },
            enabled = !running && output.isNotEmpty(),
        ) {
            Text(stringResource(R.string.jsbridge_debug_clear))
        }
    }

    Spacer(Modifier.height(8.dp))

    Text(
        stringResource(R.string.jsbridge_debug_output),
        style = MaterialTheme.typography.titleSmall,
    )
    Surface(
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .padding(8.dp)
                .heightIn(min = 80.dp, max = 260.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            if (output.isEmpty()) {
                Text(
                    "—",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            } else {
                output.forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

/**
 * 在端侧 QuickJS 沙箱执行 [script]：console.log 与 eval 结果通过 [onOutput] 回传（供 UI 展示）。
 *
 * 通过 [registerGalleryHandlers] 注册与 chat 链路一致的 gallery/media 只读 handler
 * （取自 [container]），assets/js 下的相册演示脚本因此可直接运行。
 */
private fun runJsBridgeDemo(
    scope: CoroutineScope,
    container: AppContainer,
    script: String,
    onOutput: (String) -> Unit,
    onDone: () -> Unit,
) {
    scope.launch(Dispatchers.IO) {
        val runtime = JsRuntime(
            engine = QuickJsEngine(onLog = { msg -> onOutput("console: $msg") }),
            scope = scope,
            source = "debug_page",
        )
        try {
            registerGalleryHandlers(
                runtime,
                container.getGallerySummaryUseCase,
                container.queryGalleryMediaUseCase,
                container.personDao,
                container.controlledVocab,
                scanProgressProvider = { TagGenerationService.sessionProgress.value },
            )
            val result = runtime.evalAsync(script, QuickJsEngine.DEFAULT_EVAL_TIMEOUT_MS)
            onOutput("✓ result: ${result.toJson()}")
        } catch (e: Throwable) {
            onOutput("✗ ${e.message ?: "unknown error"}")
        } finally {
            runtime.close()
            onDone()
        }
    }
}

/**
 * 预置脚本快捷按钮：点击后从 assets 加载脚本内容，回调 [onLoaded]。
 *
 * @param assetPath assets 内相对路径（如 `js/gallery_timeline_analysis.js`）；
 *                  null 时加载内置 [DEFAULT_DEMO_SCRIPT]。
 */
@Composable
private fun PresetScriptButton(
    label: String,
    scope: CoroutineScope,
    context: android.content.Context,
    assetPath: String? = null,
    onLoaded: (String) -> Unit,
) {
    OutlinedButton(
        onClick = {
            if (assetPath == null) {
                onLoaded(DEFAULT_DEMO_SCRIPT)
                return@OutlinedButton
            }
            scope.launch(Dispatchers.IO) {
                val loaded = runCatching {
                    context.assets.open(assetPath).bufferedReader().use { it.readText() }
                }.getOrDefault("// 加载失败: $assetPath")
                onLoaded(loaded)
            }
        },
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * JS Bridge 独立调试页：从 DebugScreen 拆分而来。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JsBridgeScreen(onNavigateBack: () -> Unit) {
    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.jsbridge_debug_section),
                onBack = onNavigateBack
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            JsBridgeDebugSection()
        }
    }
}
