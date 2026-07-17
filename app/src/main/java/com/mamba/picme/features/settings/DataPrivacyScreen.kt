package com.mamba.picme.features.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mamba.picme.R
import com.mamba.picme.core.identity.DeviceIdProvider
import com.mamba.picme.data.remote.picme.PicMeAuthClient
import kotlinx.coroutines.launch

private const val PRIVACY_POLICY_URL = "https://polang.net/privacy-policy/"

/**
 * 「数据与隐私」说明页：声明账号数据、设备标识、保留期、删除方式、本地/远程处理、联系方式，
 * 并提供「清除访客数据」入口。Google Play 数据安全合规要求 app 内可访问的数据说明与删除能力。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataPrivacyScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val authClient = remember { PicMeAuthClient() }
    var clearing by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.data_privacy_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PrivacySection(R.string.data_privacy_account_title, R.string.data_privacy_account_body)
            PrivacySection(R.string.data_privacy_device_title, R.string.data_privacy_device_body)
            PrivacySection(R.string.data_privacy_retention_title, R.string.data_privacy_retention_body)
            PrivacySection(R.string.data_privacy_delete_title, R.string.data_privacy_delete_body)
            PrivacySection(R.string.data_privacy_local_title, R.string.data_privacy_local_body)
            PrivacySection(R.string.data_privacy_remote_title, R.string.data_privacy_remote_body)
            PrivacySection(
                titleRes = R.string.data_privacy_contact_title,
                bodyRes = R.string.data_privacy_contact_body,
                email = "budao.gs@gmail.com",
            )

            Button(
                onClick = {
                    clearing = true
                    scope.launch {
                        val deviceId = DeviceIdProvider(context).get()
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
                Text(stringResource(R.string.data_privacy_clear_guest))
            }

            TextButton(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(intent) }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.data_privacy_view_full_policy))
            }
        }
    }
}

@Composable
private fun PrivacySection(titleRes: Int, bodyRes: Int, email: String? = null) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = if (email != null) stringResource(bodyRes, email) else stringResource(bodyRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
