package com.mamba.picme.features.translation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mamba.picme.data.download.ModelPathConfig
import com.mamba.picme.domain.translation.SentencePieceTestViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SentencePieceTestScreen(
    onNavigateBack: () -> Unit,
    viewModel: SentencePieceTestViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val logLines by viewModel.logLines.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SentencePiece 测试") },
                navigationIcon = {
                    TextButton(onClick = onNavigateBack) {
                        Text("返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 状态指示器
            when (val s = state) {
                is SentencePieceTestViewModel.TestState.Idle -> {
                    val context = LocalContext.current
                    Button(
                        onClick = {
                            val modelDir = ModelPathConfig.getOpusMtModelDir(context).absolutePath
                            viewModel.startTestFromModelDir(modelDir)
                        }
                    ) {
                        Text("开始测试")
                    }
                    Text(
                        text = "点击开始测试 SentencePiece 编码解码功能\n" +
                                "从 ${ModelPathConfig.getOpusMtModelDir(LocalContext.current).absolutePath} 加载",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                is SentencePieceTestViewModel.TestState.CopyingModels -> {
                    CircularProgressIndicator()
                    Text("正在复制模型文件...", modifier = Modifier.padding(top = 8.dp))
                }
                is SentencePieceTestViewModel.TestState.LoadingModel -> {
                    CircularProgressIndicator()
                    Text("正在加载 ${s.name}...", modifier = Modifier.padding(top = 8.dp))
                }
                is SentencePieceTestViewModel.TestState.Testing -> {
                    CircularProgressIndicator()
                    Text(s.message, modifier = Modifier.padding(top = 8.dp))
                }
                is SentencePieceTestViewModel.TestState.Success -> {
                    Text(
                        text = "✅ 测试通过",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = s.result,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Button(
                        onClick = {
                            val modelDir = ModelPathConfig.getOpusMtModelDir(context).absolutePath
                            viewModel.startTestFromModelDir(modelDir)
                        },
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Text("重新测试")
                    }
                }
                is SentencePieceTestViewModel.TestState.Error -> {
                    Text(
                        text = "❌ 测试失败",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = s.message,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Button(
                        onClick = {
                            val modelDir = ModelPathConfig.getOpusMtModelDir(context).absolutePath
                            viewModel.startTestFromModelDir(modelDir)
                        },
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Text("重试")
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 日志输出区域
            Text(
                text = "日志输出",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.align(Alignment.Start)
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                items(logLines) { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        }
    }
}
