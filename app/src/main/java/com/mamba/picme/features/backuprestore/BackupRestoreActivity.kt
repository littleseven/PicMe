package com.mamba.picme.features.backuprestore

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mamba.picme.PoLangApplication
import com.mamba.picme.R
import com.mamba.picme.core.common.Logger
import com.mamba.picme.core.designsystem.PoLangTheme
import com.mamba.picme.domain.model.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Release 包可用的 TAG 数据备份/恢复入口。
 *
 * 通过 Android Storage Access Framework (SAF) 让用户选择 JSON 备份文件，
 * 调用 [com.mamba.picme.domain.backup.BackupTagDataUseCase] /
 * [com.mamba.picme.domain.backup.RestoreTagDataUseCase] 完成导出/导入，
 * 无需 adb 或 debug 签名即可在 release 包上恢复数据。
 */
class BackupRestoreActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PoLangTheme(themeMode = ThemeMode.SYSTEM) {
                BackupRestoreScreen(onBack = ::finish)
            }
        }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, BackupRestoreActivity::class.java)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackupRestoreScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as PoLangApplication
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var isBusy by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            isBusy = true
            statusText = context.getString(R.string.backup_exporting)
            try {
                withContext(Dispatchers.IO) {
                    val tempFile = File(context.externalCacheDir, "picme_backup_export_tmp.json")
                    val result = app.container.backupTagDataUseCase(tempFile)
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        result.file.inputStream().use { input ->
                            input.copyTo(output)
                        }
                    } ?: throw IOException("Cannot open SAF output stream")
                    result.file.delete()
                }
                snackbarHostState.showSnackbar(context.getString(R.string.backup_export_success))
            } catch (e: Exception) {
                Logger.e(TAG, "Export failed", e)
                snackbarHostState.showSnackbar(
                    context.getString(R.string.backup_export_failed, e.message ?: "unknown")
                )
            } finally {
                isBusy = false
                statusText = null
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            isBusy = true
            statusText = context.getString(R.string.backup_importing)
            try {
                val result = withContext(Dispatchers.IO) {
                    val tempFile = File(context.externalCacheDir, "picme_backup_import_tmp.json")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    } ?: throw IOException("Cannot open SAF input stream")
                    app.container.restoreTagDataUseCase(tempFile, dryRun = false).also {
                        tempFile.delete()
                    }
                }
                val summary = context.getString(
                    R.string.backup_result_summary,
                    result.matchedMediaCount,
                    result.restoredTagCount,
                    result.restoredScanTaskCount,
                    result.restoredPersonCount,
                    result.restoredFaceEmbeddingCount,
                    result.restoredPreferenceCount
                )
                snackbarHostState.showSnackbar(
                    context.getString(R.string.backup_import_success, result.matchedMediaCount)
                )
                statusText = summary
            } catch (e: Exception) {
                Logger.e(TAG, "Import failed", e)
                snackbarHostState.showSnackbar(
                    context.getString(R.string.backup_import_failed, e.message ?: "unknown")
                )
                statusText = null
            } finally {
                isBusy = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.backup_and_restore)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.backup_and_restore_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Button(
                onClick = {
                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                        .format(Date())
                    exportLauncher.launch("picme_tag_backup_$timestamp.json")
                },
                enabled = !isBusy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Rounded.CloudUpload,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(stringResource(R.string.backup_export))
            }

            Button(
                onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                enabled = !isBusy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Rounded.CloudDownload,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(stringResource(R.string.backup_import))
            }

            if (isBusy) {
                Spacer(modifier = Modifier.height(16.dp))
                CircularProgressIndicator()
            }

            statusText?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private const val TAG = "BackupRestoreActivity"
