package com.mamba.picme.features.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mamba.picme.PicMeApplication
import com.mamba.picme.R
import com.mamba.picme.core.common.Logger
import com.mamba.picme.data.remote.picme.PicMeAuthClient
import com.mamba.picme.features.common.auth.EmailCodeAuthForm
import kotlinx.coroutines.launch

private const val TAG = "ServerAuth"

@Composable
internal fun ServerAuthSection(onNavigateToDataPrivacy: () -> Unit = {}) {
    val context = LocalContext.current
    val app = context.applicationContext as PicMeApplication
    val repo = app.container.userPreferencesRepository
    val scope = rememberCoroutineScope()

    val serverToken by repo.serverAuthTokenFlow.collectAsState(initial = "")
    val serverEmail by repo.serverAuthEmailFlow.collectAsState(initial = "")

    val authClient = remember { PicMeAuthClient() }

    var quotaUsed by remember { mutableStateOf(0) }
    var quotaLimit by remember { mutableStateOf(0) }

    // 已登录：进入即拉取真实额度，避免显示假占位
    LaunchedEffect(serverToken) {
        if (serverToken.isNotBlank()) {
            authClient.getQuota(serverToken)
                .onSuccess {
                    quotaUsed = it.llmCallsUsed
                    quotaLimit = it.llmCallsLimit
                }
                .onFailure { Logger.w(TAG, "Initial quota load failed: ${it.message}") }
        }
    }

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

    // 未登录：引导注册
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.auth_register_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.auth_register_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            onOpenDataPrivacy = onNavigateToDataPrivacy,
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
    val progress = if (limit > 0) (used.toFloat() / limit).coerceIn(0f, 1f) else 0f
    val nearLimit = progress >= 0.9f
    val remaining = (limit - used).coerceAtLeast(0)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // ── 账户信息 ────────────────────────────────────────────
        AccountHeader(email = email)

        // ── 额度卡片 ────────────────────────────────────────────
        QuotaCard(
            used = used,
            limit = limit,
            progress = progress,
            nearLimit = nearLimit,
            remaining = remaining,
        )

        // ── 主操作：刷新 + 登出 ─────────────────────────────────
        AuthActionButtons(
            onRefresh = onRefresh,
            onLogout = onLogout,
        )

        // ── 危险操作区 ──────────────────────────────────────────
        DangerZone(
            token = token,
            authClient = authClient,
            onLogout = onLogout,
        )
    }
}

/**
 * 删除账号确认对话框。
 */
@Composable
private fun DeleteAccountDialog(
    token: String,
    authClient: PicMeAuthClient,
    onDeleted: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var deleting by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!deleting) onDismiss() },
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
                                onDeleted()
                                Toast.makeText(
                                    context,
                                    R.string.auth_delete_account_success,
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                            .onFailure { e ->
                                val code = (e as? PicMeAuthClient.PicMeAuthException)?.code
                                if (code == 401 || code == 404) {
                                    onDeleted()
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
                onClick = onDismiss,
            ) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/**
 * 账户信息头部。
 */
@Composable
private fun AccountHeader(email: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AccountAvatar()
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = email,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.auth_account_title),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 主操作按钮：刷新 + 登出。
 */
@Composable
private fun AuthActionButtons(
    onRefresh: () -> Unit,
    onLogout: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FilledTonalButton(
            onClick = onRefresh,
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.auth_refresh))
        }
        OutlinedButton(
            onClick = onLogout,
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.auth_logout))
        }
    }
}

/**
 * 危险操作区：清除访客数据 + 删除账号。
 */
@Composable
private fun DangerZone(
    token: String,
    authClient: PicMeAuthClient,
    onLogout: () -> Unit,
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

        ClearGuestDataButton()

        TextButton(
            onClick = { showDeleteDialog = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.auth_delete_account),
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (showDeleteDialog) {
            DeleteAccountDialog(
                token = token,
                authClient = authClient,
                onDeleted = {
                    showDeleteDialog = false
                    onLogout()
                },
                onDismiss = { showDeleteDialog = false },
            )
        }
    }
}

/**
 * 清除访客数据按钮。
 */
@Composable
private fun ClearGuestDataButton() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var clearing by remember { mutableStateOf(false) }
    val authClient = remember { PicMeAuthClient() }

    TextButton(
        onClick = {
            clearing = true
            scope.launch {
                val deviceId = com.mamba.picme.core.identity.DeviceIdProvider(context).get()
                authClient.clearGuestData(deviceId)
                    .onSuccess {
                        Toast.makeText(
                            context,
                            R.string.data_privacy_clear_guest_success,
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    .onFailure {
                        Toast.makeText(
                            context,
                            R.string.data_privacy_clear_guest_failed,
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                clearing = false
            }
        },
        enabled = !clearing,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Rounded.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.data_privacy_clear_guest),
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/**
 * 额度信息卡片。
 */
@Composable
private fun QuotaCard(
    used: Int,
    limit: Int,
    progress: Float,
    nearLimit: Boolean,
    remaining: Int,
) {
    val indicatorColor = if (nearLimit) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.auth_quota_title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "$used / $limit",
                    style = MaterialTheme.typography.labelLarge,
                    color = indicatorColor,
                )
            }

            QuotaProgressBar(
                progress = progress,
                color = indicatorColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )

            Text(
                text = stringResource(R.string.auth_quota_remaining, remaining),
                style = MaterialTheme.typography.bodySmall,
                color = if (nearLimit) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

/**
 * 自定义额度进度条，无两端圆点，圆角 track。
 */
@Composable
private fun QuotaProgressBar(
    progress: Float,
    color: androidx.compose.ui.graphics.Color,
    trackColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(trackColor)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(color)
        )
    }
}

/**
 * 账户头像占位。
 */
@Composable
private fun AccountAvatar() {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.size(56.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Rounded.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}
