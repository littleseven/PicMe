package com.mamba.picme.features.gallery.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mamba.picme.R
import com.mamba.picme.util.permission.BackgroundScanGuard

/**
 * 后台扫描保活相关 UI(常驻提示条 + 启动引导弹窗)。
 *
 * 设计要点:HyperOS 等 ROM 退后台会冻结进程,扫描暂停。引导用户开启
 * 「电池白名单 + 通知 + MIUI 自启动」是根治手段,详见 [BackgroundScanGuard]。
 */

/**
 * 常驻提示条:扫描控制页顶部,检测到保活缺失项时柔和提示。
 * 点击跳转第一项缺失的修复页。
 */
@Composable
fun BackgroundScanGuardBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val issues by produceState<List<BackgroundScanGuard.Issue>>(initialValue = emptyList(), context) {
        value = runCatching { BackgroundScanGuard.diagnose(context.applicationContext) }
            .getOrDefault(emptyList())
            // MIUI/HyperOS 自启动无读取 API（恒报），不在常驻 Banner 显示——
            // 配齐「电池白名单 + 通知」后 Banner 即消失；自启动仅在启动扫描弹窗引导一次
            .filter { it.type != BackgroundScanGuard.IssueType.MIUI_AUTOSTART }
    }
    if (issues.isEmpty()) return
    val bannerText = stringResource(R.string.bg_scan_guard_banner_text)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { issues.first().openFix(context) },
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3CD))
    ) {
        Text(
            text = bannerText,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF856404)
        )
    }
}

/**
 * 启动扫描前的保活引导弹窗。
 *
 * @param issues 缺失项(非空时调用方才渲染本弹窗)
 * @param onGoSettings 用户点「去设置」(调用方调第一项 openFix)
 * @param onContinue 用户点「仍然扫描」(调用方执行被暂挂的启动动作)
 * @param onDontRemind 用户点「不再提醒」(调用方持久化后不再弹)
 */
@Composable
fun BackgroundScanGuardDialog(
    issues: List<BackgroundScanGuard.Issue>,
    onGoSettings: () -> Unit,
    onContinue: () -> Unit,
    onDontRemind: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onContinue,
        title = { Text(stringResource(R.string.bg_scan_guard_dialog_title)) },
        text = {
            Column {
                Text(stringResource(R.string.bg_scan_guard_dialog_message))
                Spacer(Modifier.height(8.dp))
                // 用 for 循环(非 forEach)以便在 @Composable 作用域内调用 stringResource
                for (issue in issues) {
                    Text(
                        text = "• " + stringResource(issue.titleRes),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onContinue) {
                Text(stringResource(R.string.bg_scan_guard_action_continue))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDontRemind) {
                    Text(stringResource(R.string.bg_scan_guard_action_dont_remind))
                }
                TextButton(onClick = onGoSettings) {
                    Text(stringResource(R.string.bg_scan_guard_action_settings))
                }
            }
        }
    )
}
