package com.mamba.picme.features.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mamba.picme.PicMeApplication
import com.mamba.picme.R
import com.mamba.picme.core.common.Logger
import com.mamba.picme.data.remote.picme.PicMeAuthClient
import com.mamba.picme.features.common.auth.EmailCodeAuthForm
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

    var quotaUsed by remember { mutableStateOf(0) }
    var quotaLimit by remember { mutableStateOf(1000) }

    if (serverToken.isNotBlank()) {
        QuotaDisplay(
            email = serverEmail,
            used = quotaUsed,
            limit = quotaLimit,
            token = serverToken,
            authClient = authClient,
            onRefresh = {
                scope.launch {
                    authClient.getQuota(serverToken)
                        .onSuccess {
                            quotaUsed = it.llmCallsUsed
                            quotaLimit = it.llmCallsLimit
                        }
                        .onFailure { Logger.w(TAG, "Quota refresh failed: ${it.message}") }
                }
            },
            onLogout = {
                scope.launch { repo.clearServerAuth() }
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
            text = stringResource(R.string.auth_register_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.auth_register_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
        EmailCodeAuthForm(
            sendCode = { email, onResult ->
                scope.launch { authClient.sendVerificationCode(email).also(onResult) }
            },
            verifyCode = { email, code, onResult ->
                scope.launch {
                    val result = authClient.verifyCode(email, code)
                    result.onSuccess { auth ->
                        repo.updateServerAuth(auth.token, email)
                        quotaUsed = auth.llmCallsUsed
                        quotaLimit = auth.llmCallsLimit
                    }
                    onResult(result)
                }
            },
        )
    }
}

@Composable
private fun QuotaDisplay(
    email: String,
    used: Int,
    limit: Int,
    token: String,
    authClient: PicMeAuthClient,
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
            text = stringResource(R.string.auth_account_title),
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
            text = stringResource(R.string.auth_quota_label, used, limit),
            style = MaterialTheme.typography.bodySmall,
            color = if (progress >= 0.9f) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onRefresh) {
                Text(stringResource(R.string.auth_refresh))
            }
            TextButton(onClick = onLogout) {
                Text(stringResource(R.string.auth_logout))
            }
        }

        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var showDeleteDialog by remember { mutableStateOf(false) }
        var deleting by remember { mutableStateOf(false) }

        TextButton(
            onClick = { showDeleteDialog = true },
            enabled = !deleting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.auth_delete_account),
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (showDeleteDialog) {
            AlertDialog(
                onDismissRequest = { if (!deleting) showDeleteDialog = false },
                title = { Text(stringResource(R.string.auth_delete_account_confirm_title)) },
                text = { Text(stringResource(R.string.auth_delete_account_confirm_body)) },
                confirmButton = {
                    TextButton(
                        enabled = !deleting,
                        onClick = {
                            deleting = true
                            scope.launch {
                                authClient.deleteAccount(token)
                                    .onSuccess {
                                        onLogout()
                                        showDeleteDialog = false
                                        Toast.makeText(
                                            context,
                                            R.string.auth_delete_account_success,
                                            Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                    .onFailure { e ->
                                        val code = (e as? PicMeAuthClient.PicMeAuthException)?.code
                                        if (code == 401 || code == 404) {
                                            onLogout()
                                            showDeleteDialog = false
                                        }
                                        Toast.makeText(
                                            context,
                                            R.string.auth_delete_account_failed,
                                            Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                deleting = false
                            }
                        },
                    ) {
                        Text(
                            stringResource(R.string.auth_delete_account_confirm),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
                dismissButton = {
                    TextButton(
                        enabled = !deleting,
                        onClick = { showDeleteDialog = false },
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }
    }
}
