package com.mamba.picme.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mamba.picme.PicMeApplication
import com.mamba.picme.core.common.Logger
import com.mamba.picme.data.remote.picme.PicMeAuthClient
import kotlinx.coroutines.launch

private const val TAG = "ServerAuth"

@Composable
internal fun ServerAuthSection() {
    val context = LocalContext.current
    val app = context.applicationContext as PicMeApplication
    val repo = app.container.userPreferencesRepository
    val scope = rememberCoroutineScope()

    val serverToken by repo.serverAuthTokenFlow.collectAsState(initial = "")
    val serverEmail by repo.serverAuthEmailFlow.collectAsState(initial = "")

    val authClient = remember { PicMeAuthClient() }

    var emailInput by remember { mutableStateOf("") }
    var codeInput by remember { mutableStateOf("") }
    var codeSent by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var quotaUsed by remember { mutableStateOf(0) }
    var quotaLimit by remember { mutableStateOf(100) }

    if (serverToken.isNotBlank()) {
        QuotaDisplay(
            email = serverEmail,
            used = quotaUsed,
            limit = quotaLimit,
            onRefresh = {
                scope.launch {
                    val result = authClient.getQuota(serverToken)
                    result.onSuccess {
                        quotaUsed = it.llmCallsUsed
                        quotaLimit = it.llmCallsLimit
                    }.onFailure { Logger.w(TAG, "Quota refresh failed: ${it.message}") }
                }
            },
            onLogout = {
                scope.launch {
                    repo.clearServerAuth()
                    emailInput = ""
                    codeInput = ""
                    codeSent = false
                    errorMsg = null
                }
            },
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "服务端邮箱注册",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "注册邮箱获取 100 次免费 LLM 调用额度，或配置自己的 API Key 直连。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )

        OutlinedTextField(
            value = emailInput,
            onValueChange = { emailInput = it.trim() },
            label = { Text("邮箱") },
            enabled = !loading,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        if (codeSent) {
            OutlinedTextField(
                value = codeInput,
                onValueChange = { codeInput = it.trim() },
                label = { Text("验证码") },
                enabled = !loading,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        errorMsg?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.height(20.dp),
                    strokeWidth = 2.dp,
                )
            }
            if (!codeSent) {
                Button(
                    onClick = {
                        if (emailInput.isBlank()) {
                            errorMsg = "请输入邮箱"
                            return@Button
                        }
                        loading = true
                        errorMsg = null
                        scope.launch {
                            val result = authClient.sendVerificationCode(emailInput)
                            loading = false
                            result.onSuccess {
                                codeSent = true
                            }.onFailure {
                                errorMsg = "验证码发送失败：${it.message}"
                            }
                        }
                    },
                    enabled = !loading && emailInput.isNotBlank(),
                ) {
                    Text("发送验证码")
                }
            } else {
                Button(
                    onClick = {
                        if (codeInput.isBlank()) {
                            errorMsg = "请输入验证码"
                            return@Button
                        }
                        loading = true
                        errorMsg = null
                        scope.launch {
                            val result = authClient.verifyCode(emailInput, codeInput)
                            loading = false
                            result.onSuccess { auth ->
                                repo.updateServerAuth(auth.token, emailInput)
                                quotaUsed = auth.llmCallsUsed
                                quotaLimit = auth.llmCallsLimit
                                Logger.i(TAG, "Auth success: email=$emailInput")
                            }.onFailure { e ->
                                errorMsg = if (e is PicMeAuthClient.PicMeAuthException) {
                                    when (e.errorType) {
                                        "invalid_code" -> "验证码错误"
                                        "code_expired" -> "验证码已过期，请重新发送"
                                        else -> "验证失败：${e.errorType}"
                                    }
                                } else {
                                    "验证失败：${e.message}"
                                }
                            }
                        }
                    },
                    enabled = !loading && codeInput.isNotBlank(),
                ) {
                    Text("验证")
                }
                TextButton(
                    onClick = { codeSent = false; codeInput = ""; errorMsg = null },
                    enabled = !loading,
                ) {
                    Text("重新输入邮箱")
                }
            }
        }
    }
}

@Composable
private fun QuotaDisplay(
    email: String,
    used: Int,
    limit: Int,
    onRefresh: () -> Unit,
    onLogout: () -> Unit,
) {
    val progress = if (limit > 0) used.toFloat() / limit else 0f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "服务端账户",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = email,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            text = "LLM 额度：$used / $limit 次",
            style = MaterialTheme.typography.bodySmall,
            color = if (progress >= 0.9f) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onRefresh) {
                Text("刷新")
            }
            TextButton(onClick = onLogout) {
                Text("退出登录")
            }
        }
    }
}
