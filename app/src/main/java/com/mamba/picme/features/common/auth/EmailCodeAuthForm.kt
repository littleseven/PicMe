package com.mamba.picme.features.common.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mamba.picme.R
import com.mamba.picme.data.remote.picme.PoLangAuthClient

/**
 * 共享的「邮箱 + 验证码」认证表单。设置页与聊天注册弹层共用。
 *
 * @param sendCode 发送验证码；回调在主线程触发，携带结果。
 * @param verifyCode 校验验证码；调用方在成功分支里自行持久化 token。
 */
@Composable
fun EmailCodeAuthForm(
    sendCode: (email: String, onResult: (Result<Unit>) -> Unit) -> Unit,
    verifyCode: (email: String, code: String, onResult: (Result<*>) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    onOpenDataPrivacy: () -> Unit = {},
) {
    val context = LocalContext.current
    var emailInput by remember { mutableStateOf("") }
    var codeInput by remember { mutableStateOf("") }
    var codeSent by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = emailInput,
            onValueChange = { emailInput = it.trim() },
            label = { Text(stringResource(R.string.auth_email_label)) },
            enabled = !loading,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        if (codeSent) {
            OutlinedTextField(
                value = codeInput,
                onValueChange = { codeInput = it.trim() },
                label = { Text(stringResource(R.string.auth_code_label)) },
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
                            errorMsg = context.getString(R.string.auth_email_empty)
                            return@Button
                        }
                        loading = true
                        errorMsg = null
                        sendCode(emailInput) { result ->
                            loading = false
                            result.onSuccess { codeSent = true }
                                .onFailure { e ->
                                    errorMsg = context.getString(R.string.auth_send_failed, e.message ?: "")
                                }
                        }
                    },
                    enabled = !loading && emailInput.isNotBlank(),
                ) {
                    Text(stringResource(R.string.auth_send_code))
                }
            } else {
                Button(
                    onClick = {
                        if (codeInput.isBlank()) {
                            errorMsg = context.getString(R.string.auth_code_empty)
                            return@Button
                        }
                        loading = true
                        errorMsg = null
                        verifyCode(emailInput, codeInput) { result ->
                            loading = false
                            result.onFailure { e ->
                                errorMsg = if (e is PoLangAuthClient.PoLangAuthException) {
                                    when (e.errorType) {
                                        "invalid_code" -> context.getString(R.string.auth_invalid_code)
                                        "code_expired" -> context.getString(R.string.auth_code_expired)
                                        else -> context.getString(R.string.auth_verify_failed, e.errorType)
                                    }
                                } else {
                                    context.getString(R.string.auth_verify_failed, e.message ?: "")
                                }
                            }
                        }
                    },
                    enabled = !loading && codeInput.isNotBlank(),
                ) {
                    Text(stringResource(R.string.auth_verify))
                }
                TextButton(
                    onClick = {
                        codeSent = false
                        codeInput = ""
                        errorMsg = null
                    },
                    enabled = !loading,
                ) {
                    Text(stringResource(R.string.auth_reenter_email))
                }
            }
        }

        TextButton(
            onClick = onOpenDataPrivacy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.data_privacy_entry))
        }
    }
}
