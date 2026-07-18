package com.mamba.picme.features.debug

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mamba.picme.core.common.LogEntry
import com.mamba.picme.core.common.LogLevel
import com.mamba.picme.core.common.Logger
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogOverlay(
    onDismiss: () -> Unit
) {
    val logs by Logger.logs.collectAsState()
    var filterText by remember { mutableStateOf("") }

    val filteredLogs = remember(logs, filterText) {
        if (filterText.isBlank()) {
            logs
        } else {
            logs.filter { entry ->
                entry.message.contains(filterText, ignoreCase = true) ||
                        entry.tag.contains(filterText, ignoreCase = true)
            }
        }
    }

    val density = LocalDensity.current
    val configuration = LocalConfiguration.current

    val overlayWidthPx = with(density) { (configuration.screenWidthDp * 0.9f).dp.toPx() }
    val overlayHeightPx = with(density) { (configuration.screenHeightDp * 0.7f).dp.toPx() }
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }

    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .fillMaxHeight(0.7f)
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) },
            shape = RoundedCornerShape(16.dp),
            color = Color.Black.copy(alpha = 0.85f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // 拖拽把手
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 8.dp)
                        .fillMaxWidth()
                        .height(24.dp)
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                offsetX += dragAmount.x
                                offsetY += dragAmount.y

                                val maxX = screenWidthPx / 2f + overlayWidthPx * 0.3f
                                val maxY = screenHeightPx / 2f + overlayHeightPx * 0.3f
                                offsetX = offsetX.coerceIn(-maxX, maxX)
                                offsetY = offsetY.coerceIn(-maxY, maxY)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 36.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color.White.copy(alpha = 0.3f))
                    )
                }

                LogOverlayHeader(onClear = { Logger.clear() }, onDismiss = onDismiss)

                OutlinedTextField(
                    value = filterText,
                    onValueChange = { filterText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    placeholder = { Text("Grep logs (Tag or Content)...", fontSize = 12.sp) },
                    leadingIcon = {
                        Icon(Icons.Rounded.Search, null, modifier = Modifier.size(18.dp))
                    },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = MaterialTheme.colorScheme.primary
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(filteredLogs) { entry ->
                        LogItem(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogOverlayHeader(onClear: () -> Unit, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "PoLang System Logs",
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
        Row {
            IconButton(onClick = onClear) {
                Icon(Icons.Rounded.DeleteSweep, null, tint = Color.White)
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Rounded.Close, null, tint = Color.White)
            }
        }
    }
}

@Composable
private fun LogItem(entry: LogEntry) {
    val color = when (entry.level) {
        LogLevel.VERBOSE -> Color(0xFF9E9E9E)
        LogLevel.DEBUG -> Color.Gray
        LogLevel.INFO -> Color.White
        LogLevel.WARN -> Color(0xFFFFA000)
        LogLevel.ERROR -> Color.Red
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(4.dp))
            .padding(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = entry.timestamp,
                fontSize = 9.sp,
                color = Color.Gray,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = entry.tag,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.secondary,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            text = entry.message,
            fontSize = 11.sp,
            color = color,
            lineHeight = 14.sp
        )
    }
}
