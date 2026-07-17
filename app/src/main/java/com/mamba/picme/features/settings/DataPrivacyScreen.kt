package com.mamba.picme.features.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mamba.picme.R

private const val PRIVACY_POLICY_URL = "https://polang.net/privacy-policy/"

/**
 * 「数据与隐私」说明页：声明账号数据收集与用途、保留期、删除方式、本地/远程处理、联系方式。
 * Google Play 数据安全合规要求 app 内可访问的数据说明。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataPrivacyScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
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
            PrivacySection(R.string.data_privacy_retention_title, R.string.data_privacy_retention_body)
            PrivacySection(R.string.data_privacy_delete_title, R.string.data_privacy_delete_body)
            PrivacySection(R.string.data_privacy_local_title, R.string.data_privacy_local_body)
            PrivacySection(R.string.data_privacy_remote_title, R.string.data_privacy_remote_body)
            PrivacySection(
                titleRes = R.string.data_privacy_contact_title,
                bodyRes = R.string.data_privacy_contact_body,
                email = "budao.gs@gmail.com",
            )
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
