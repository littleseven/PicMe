package com.mamba.picme.features.debug

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mamba.picme.R
import com.mamba.picme.data.local.llmlog.LlmCallLogEntity
import com.mamba.picme.data.local.llmlog.LlmLogDatabase
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LlmCallLogScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val dao = remember { LlmLogDatabase.getDatabase(context).llmCallLogDao() }
    val vm: LlmCallLogViewModel = viewModel(factory = LlmCallLogViewModelFactory(dao))
    val items by vm.items.collectAsState()

    var selected by remember { mutableStateOf<LlmCallLogEntity?>(null) }
    var showClearDialog by remember { mutableStateOf(false) }

    val selectedItem = selected
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (selectedItem != null) stringResource(R.string.llm_call_log_detail) else stringResource(R.string.llm_call_log))
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selectedItem != null) {
                            selected = null
                        } else {
                            onNavigateBack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null)
                    }
                    if (selectedItem == null) {
                        IconButton(onClick = { showClearDialog = true }) {
                            Icon(Icons.Rounded.DeleteSweep, contentDescription = null)
                        }
                    }
                }
            )
        }
    ) { padding ->
        when (val item = selectedItem) {
            null -> {
                if (items.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.llm_call_log_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(padding),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(items, key = { it.id }) { row ->
                            LlmCallLogRow(row = row) { selected = it }
                        }
                    }
                }
            }
            else -> LlmCallLogDetail(
                item = item,
                modifier = Modifier.fillMaxSize().padding(padding)
            )
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.llm_call_log_clear)) },
            text = { Text(stringResource(R.string.llm_call_log_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearAll()
                    showClearDialog = false
                }) { Text(stringResource(R.string.llm_call_log_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

@Composable
private fun LlmCallLogRow(row: LlmCallLogEntity, onClick: (LlmCallLogEntity) -> Unit) {
    val timeFormat = remember { SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault()) }
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick(row) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (row.success) Icons.Rounded.Check else Icons.Rounded.Close,
                    contentDescription = null,
                    tint = if (row.success) Color(0xFF2E7D32) else Color(0xFFC62828),
                    modifier = Modifier.height(18.dp)
                )
                Text(
                    text = timeFormat.format(Date(row.createdAt)),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 6.dp)
                )
                Spacer(Modifier.weight(1f))
                SourceChip(source = row.source)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = row.model ?: "—",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = buildString {
                    row.latencyMs?.let { append("${it}ms") }
                    append("  ·  ")
                    if (row.promptTokens != null || row.completionTokens != null) {
                        append("in=${row.promptTokens ?: 0} out=${row.completionTokens ?: 0}")
                    } else {
                        append(row.errorMessage?.take(40) ?: "")
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SourceChip(source: String) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = source,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun LlmCallLogDetail(item: LlmCallLogEntity, modifier: Modifier) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val timeFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()) }
    val successLabel = stringResource(R.string.llm_call_log_success)
    val failedLabel = stringResource(R.string.llm_call_log_failed)

    fun copy(text: String) {
        clipboard.setText(AnnotatedString(text))
        Toast.makeText(context, R.string.llm_call_log_copied, Toast.LENGTH_SHORT).show()
    }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "${timeFormat.format(Date(item.createdAt))}  ·  ${item.model ?: "—"}  ·  ${item.source}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = if (item.success) {
                "$successLabel  ·  ${item.latencyMs ?: 0}ms" +
                    "  ·  in=${item.promptTokens ?: 0} out=${item.completionTokens ?: 0}"
            } else {
                "$failedLabel  ·  ${item.latencyMs ?: 0}ms"
            },
            style = MaterialTheme.typography.labelMedium,
            color = if (item.success) Color(0xFF2E7D32) else Color(0xFFC62828)
        )

        HorizontalDivider()

        JsonSection(
            title = stringResource(R.string.llm_call_log_request),
            content = prettyJson(item.requestJson),
            onCopy = { copy(item.requestJson) }
        )
        item.responseJson?.let {
            JsonSection(
                title = stringResource(R.string.llm_call_log_response),
                content = prettyJson(it),
                onCopy = { copy(it) }
            )
        }
        item.errorMessage?.let {
            JsonSection(
                title = stringResource(R.string.llm_call_log_error),
                content = it,
                onCopy = { copy(it) }
            )
        }
    }
}

@Composable
private fun JsonSection(title: String, content: String, onCopy: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = onCopy) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = null)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Text(
                text = content,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

private fun prettyJson(raw: String): String =
    runCatching { JSONObject(raw).toString(2) }.getOrElse { raw }
