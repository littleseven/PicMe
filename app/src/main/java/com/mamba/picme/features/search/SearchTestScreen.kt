@file:Suppress("MagicNumber", "TooManyFunctions")

package com.mamba.picme.features.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.util.Locale
import com.mamba.picme.R
import com.mamba.picme.domain.model.StructuredFilter
import com.mamba.picme.domain.search.DiagnosticMediaItem
import com.mamba.picme.domain.search.DiagnosticSemanticItem
import com.mamba.picme.domain.search.RecallDimension
import com.mamba.picme.domain.search.SearchDiagnosticsResult
import com.mamba.picme.domain.search.SearchMetrics

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchTestScreen(
    onNavigateBack: () -> Unit,
    viewModel: SearchTestViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val logLines by viewModel.logLines.collectAsState()
    val history by viewModel.history.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.search_test_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        SearchTestContent(
            modifier = Modifier.padding(padding),
            uiState = uiState,
            logLines = logLines,
            history = history,
            onSearch = { query, enableSemantic ->
                viewModel.search(query, enableSemantic)
            },
            onClearLogs = { viewModel.clearLogs() },
            onRemoveHistory = { viewModel.removeHistoryItem(it) }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchTestContent(
    modifier: Modifier = Modifier,
    uiState: SearchTestViewModel.UiState,
    logLines: List<String>,
    history: List<String>,
    onSearch: (String, Boolean) -> Unit,
    onClearLogs: () -> Unit,
    onRemoveHistory: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var enableSemanticSearch by remember { mutableStateOf(true) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        SearchInputArea(
            query = query,
            onQueryChange = { query = it },
            history = history,
            enableSemanticSearch = enableSemanticSearch,
            onEnableSemanticSearchChange = { enableSemanticSearch = it },
            onSearch = { onSearch(query, enableSemanticSearch) },
            onRemoveHistory = onRemoveHistory
        )

        HorizontalDivider()

        SearchStateContent(uiState = uiState)

        HorizontalDivider()

        LogWindow(
            logLines = logLines,
            onClear = onClearLogs
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchInputArea(
    query: String,
    onQueryChange: (String) -> Unit,
    history: List<String>,
    enableSemanticSearch: Boolean,
    onEnableSemanticSearchChange: (Boolean) -> Unit,
    onSearch: () -> Unit,
    onRemoveHistory: (String) -> Unit
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.search_test_query_hint)) },
        leadingIcon = { Icon(Icons.Default.Search, null) },
        trailingIcon = {
            if (query.isNotBlank()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Clear, stringResource(R.string.clear))
                }
            }
        },
        singleLine = true
    )

    if (history.isNotEmpty()) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            history.forEach { item ->
                FilterChip(
                    selected = false,
                    onClick = { onQueryChange(item) },
                    label = { Text(item, maxLines = 1) },
                    trailingIcon = {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = stringResource(R.string.remove),
                            modifier = Modifier
                                .size(16.dp)
                                .clickable { onRemoveHistory(item) }
                        )
                    }
                )
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.search_test_enable_semantic),
            style = MaterialTheme.typography.bodyMedium
        )
        Switch(
            checked = enableSemanticSearch,
            onCheckedChange = onEnableSemanticSearchChange
        )
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        IconButton(
            onClick = onSearch,
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = stringResource(R.string.search_test_start)
            )
        }
    }
}

@Composable
private fun SearchStateContent(uiState: SearchTestViewModel.UiState) {
    when (val state = uiState) {
        is SearchTestViewModel.UiState.Idle -> {
            InfoCard(message = stringResource(R.string.search_test_idle_hint))
        }
        is SearchTestViewModel.UiState.Loading -> {
            LoadingCard()
        }
        is SearchTestViewModel.UiState.Error -> {
            ErrorCard(message = state.message)
        }
        is SearchTestViewModel.UiState.Success -> {
            DiagnosticsResult(result = state.result)
        }
    }
}

@Composable
private fun DiagnosticsResult(result: SearchDiagnosticsResult) {
    ParsedFilterCard(filter = result.parsedFilter, needsLlm = result.needsLlm)

    MetricsCard(metrics = result.metrics, mergedCount = result.mergedResults.size)

    RecallBreakdownSection(breakdown = result.recallBreakdown)

    if (result.mergedResults.isNotEmpty()) {
        ResultsSection(title = stringResource(R.string.search_test_merged_results), results = result.mergedResults)
    }

    if (result.semanticResults.isNotEmpty()) {
        SemanticResultsSection(semanticResults = result.semanticResults)
    }
}

@Composable
private fun ParsedFilterCard(filter: StructuredFilter?, needsLlm: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.search_test_parsed_filter),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (filter == null) {
                Text(
                    text = stringResource(R.string.search_test_need_llm),
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                FilterRow(
                    label = stringResource(R.string.search_test_keywords),
                    value = filter.keywords.joinToString(", ")
                )
                FilterRow(
                    label = stringResource(R.string.search_test_ocr_keywords),
                    value = filter.ocrKeywords.joinToString(", ")
                )
                FilterRow(
                    label = stringResource(R.string.search_test_location_keywords),
                    value = filter.locationKeywords.joinToString(", ")
                )
                FilterRow(
                    label = stringResource(R.string.search_test_time_range),
                    value = filter.timeRange?.let {
                        "${formatTimestamp(it.startMs)} ~ ${formatTimestamp(it.endMs)}"
                    } ?: stringResource(R.string.search_test_none)
                )
                FilterRow(
                    label = stringResource(R.string.search_test_has_faces),
                    value = filter.hasFaces?.let {
                        if (it) stringResource(R.string.yes) else stringResource(R.string.no)
                    } ?: stringResource(R.string.search_test_unlimited)
                )
                FilterRow(
                    label = stringResource(R.string.search_test_need_llm),
                    value = if (needsLlm) stringResource(R.string.yes) else stringResource(R.string.no)
                )
            }
        }
    }
}

@Composable
private fun FilterRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value.ifBlank { stringResource(R.string.search_test_none) },
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun MetricsCard(metrics: SearchMetrics, mergedCount: Int) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.search_test_metrics),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            MetricRow(label = stringResource(R.string.search_test_total_time), value = "${metrics.totalTimeMs}ms")
            MetricRow(label = stringResource(R.string.search_test_parse_time), value = "${metrics.parseTimeMs}ms")
            MetricRow(label = stringResource(R.string.search_test_sql_time), value = "${metrics.sqlRecallTimeMs}ms")
            MetricRow(label = stringResource(R.string.search_test_semantic_time), value = "${metrics.semanticRecallTimeMs}ms")
            MetricRow(label = stringResource(R.string.search_test_merge_time), value = "${metrics.mergeTimeMs}ms")
            MetricRow(
                label = stringResource(R.string.search_test_semantic_ready),
                value = if (metrics.semanticEngineReady) stringResource(R.string.yes) else stringResource(R.string.no)
            )
            MetricRow(label = stringResource(R.string.search_test_final_count), value = mergedCount.toString())
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun RecallBreakdownSection(breakdown: List<RecallDimension>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.search_test_recall_breakdown),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            breakdown.forEach { dimension ->
                RecallRow(dimension = dimension)
            }
        }
    }
}

@Composable
private fun RecallRow(dimension: RecallDimension) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = dimension.name,
            style = MaterialTheme.typography.bodySmall
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (dimension.count >= 0) dimension.count.toString() else "-",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = "${dimension.timeMs}ms",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResultsSection(title: String, results: List<DiagnosticMediaItem>) {
    Text(
        text = "$title (${results.size})",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold
    )
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        maxItemsInEachRow = 3
    ) {
        results.take(30).forEach { item ->
            ResultItem(item = item)
        }
    }
    if (results.size > 30) {
        Text(
            text = stringResource(R.string.search_test_more_results, results.size - 30),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SemanticResultsSection(semanticResults: List<DiagnosticSemanticItem>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.search_test_semantic_results, semanticResults.size),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            semanticResults.take(10).forEach { item ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = item.media.fileName,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = String.format(Locale.getDefault(), "%.3f", item.score),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultItem(item: DiagnosticMediaItem) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .width(96.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp)
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(item.media.uri)
                .crossfade(true)
                .build(),
            contentDescription = item.media.fileName,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(4.dp))
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = item.media.fileName,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1
        )
        Text(
            text = String.format(Locale.getDefault(), "%.3f", item.score),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = item.matchDimensions.take(3).joinToString(", "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

@Composable
private fun LogWindow(logLines: List<String>, onClear: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.search_test_logs),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = onClear) {
                Icon(
                    imageVector = Icons.Default.Clear,
                    contentDescription = stringResource(R.string.clear)
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .background(Color.Black.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                .padding(8.dp)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(logLines) { line ->
                    Text(
                        text = line,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = when {
                            line.contains("failed", ignoreCase = true) || line.contains("error", ignoreCase = true) ->
                                MaterialTheme.colorScheme.error
                            line.contains("complete", ignoreCase = true) -> Color(0xFF2E7D32)
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LoadingCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            Text(text = stringResource(R.string.search_test_searching))
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

private fun formatTimestamp(ms: Long): String {
    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(ms))
}
