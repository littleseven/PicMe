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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import com.mamba.picme.features.chat.js.QuickJsEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** 默认演示脚本（同步 handler，结果可立即可视化；可在输入框编辑）。 */
private const val DEFAULT_DEMO_SCRIPT = """(function () {
    console.log("demo start");
    var sum = bridge.call("math.add", [18, 24]);
    console.log("math.add =>", sum);
    var up = bridge.call("string.upper", "polang");
    console.log("string.upper =>", up);
    console.log("handlers =>", bridge.list());
    return { sum: sum, upper: up };
})();"""

/**
 * Debug 页「JS Bridge」区块：可编辑脚本 + 运行按钮 + 输出区。
 *
 * 可视化：console.log 与 eval 结果都展示在输出区（不再只进 logcat / Toast 一闪而过）。
 * JS 仅能通过 bridge 间接访问原生（QuickJS 沙箱：无 LiveConnect，仅 bridge 通道）。
 */
@Composable
fun JsBridgeDebugSection() {
    val context = LocalContext.current
    val app = context.applicationContext as PoLangApplication
    val uiScope = rememberCoroutineScope()
    var script by remember { mutableStateOf(DEFAULT_DEMO_SCRIPT) }
    val output = remember { mutableStateListOf<String>() }
    var running by remember { mutableStateOf(false) }

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
 */
private fun runJsBridgeDemo(
    scope: CoroutineScope,
    script: String,
    onOutput: (String) -> Unit,
    onDone: () -> Unit,
) {
    scope.launch(Dispatchers.IO) {
        val runtime = JsRuntime(
            engine = QuickJsEngine(onLog = { msg -> onOutput("console: $msg") }),
            scope = scope,
        )
        try {
            val result = runtime.eval(script)
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
 * JS Bridge 独立调试页：从 DebugScreen 拆分而来。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JsBridgeScreen(onNavigateBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.jsbridge_debug_section)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
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
