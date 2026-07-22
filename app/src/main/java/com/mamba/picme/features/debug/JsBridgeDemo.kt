package com.mamba.picme.features.debug

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mamba.picme.PoLangApplication
import com.mamba.picme.R
import com.mamba.picme.agent.core.js.JsRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val JS_TAG = "PoLang:Js"
private const val DEMO_ASSET = "js/picme_bridge_demo.js"

/**
 * Debug 页「JS Bridge (MVP)」区块：渲染标题 + 按钮，点击后加载包内 JS 演示脚本并用
 * [JsRuntime] 执行。context/scope 取自 [LocalContext]，无需向宿主页面注入额外回调。
 */
@Composable
fun JsBridgeDebugSection() {
    val context = LocalContext.current
    val app = context.applicationContext as PoLangApplication

    Text(
        stringResource(R.string.jsbridge_debug_section),
        style = MaterialTheme.typography.titleSmall
    )

    Button(
        onClick = { runJsBridgeDemo(context, app.applicationScope) },
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.Default.Code, null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.jsbridge_debug_run_demo))
    }
}

/**
 * 加载包内 [DEMO_ASSET] 并用 [JsRuntime] 执行。
 * JS 仅能通过 bridge 间接访问原生（ClassShutter deny-all 沙箱）；结果/失败以 Toast + 日志反馈。
 */
private fun runJsBridgeDemo(context: Context, scope: CoroutineScope) {
    scope.launch(Dispatchers.IO) {
        val runtime = JsRuntime(scope = scope, onLog = { msg -> Log.i(JS_TAG, msg) })
        try {
            val script = context.assets.open(DEMO_ASSET)
                .bufferedReader()
                .use { reader -> reader.readText() }
            val result = runtime.eval(script)
            Log.i(JS_TAG, "demo result: ${result.toJson()}")
            showToast(
                context,
                context.getString(R.string.jsbridge_debug_done, result.toJson()),
            )
        } catch (e: Throwable) {
            Log.e(JS_TAG, "demo failed", e)
            showToast(
                context,
                context.getString(R.string.jsbridge_debug_failed, e.message ?: "unknown"),
            )
        } finally {
            runtime.close()
        }
    }
}

private suspend fun showToast(context: Context, message: String) {
    withContext(Dispatchers.Main) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
}
