package com.mamba.picme.domain.translation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mamba.picme.core.common.Logger
import com.mamba.picme.sentencepiece.SentencePieceProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * SentencePiece 测试 ViewModel，用于验证 OPUS-MT tokenizer 的编码解码功能。
 *
 * 测试流程：
 * 1. 从应用私有目录加载 source.spm / target.spm
 * 2. 加载模型并执行 encode → decode 往返测试
 * 3. 输出词表大小和 token 示例
 */
class SentencePieceTestViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "SentencePieceTest"
    }

    sealed interface TestState {
        data object Idle : TestState
        data object CopyingModels : TestState
        data class LoadingModel(val name: String) : TestState
        data class Testing(val message: String) : TestState
        data class Success(val result: String) : TestState
        data class Error(val message: String) : TestState
    }

    private val _state = MutableStateFlow<TestState>(TestState.Idle)
    val state: StateFlow<TestState> = _state

    private val _logLines = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = _logLines

    private var sourceTokenizer: SentencePieceProcessor? = null
    private var targetTokenizer: SentencePieceProcessor? = null

    /**
     * 从指定路径加载 SentencePiece 模型并执行测试
     *
     * @param sourceModelPath source.spm 绝对路径
     * @param targetModelPath target.spm 绝对路径
     */
    fun startTest(sourceModelPath: String, targetModelPath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = TestState.CopyingModels
            try {
                // 1. 验证模型文件存在
                val sourceFile = File(sourceModelPath)
                val targetFile = File(targetModelPath)

                if (!sourceFile.exists()) {
                    throw IllegalStateException("Source model not found: $sourceModelPath")
                }
                if (!targetFile.exists()) {
                    throw IllegalStateException("Target model not found: $targetModelPath")
                }

                log("模型文件已确认: source=${sourceFile.absolutePath} (${sourceFile.length()} bytes), target=${targetFile.absolutePath} (${targetFile.length()} bytes)")

                // 2. 加载 source tokenizer
                _state.value = TestState.LoadingModel("source.spm")
                val src = SentencePieceProcessor().apply { loadModel(sourceFile.absolutePath) }
                sourceTokenizer = src
                log("Source tokenizer 加载成功, vocabSize=${src.vocabSize()}")

                // 3. 加载 target tokenizer
                _state.value = TestState.LoadingModel("target.spm")
                val tgt = SentencePieceProcessor().apply { loadModel(targetFile.absolutePath) }
                targetTokenizer = tgt
                log("Target tokenizer 加载成功, vocabSize=${tgt.vocabSize()}")

                // 4. 编码测试（中文 → token IDs）
                _state.value = TestState.Testing("编码测试: 中文 → token IDs")
                val testText = "你好世界"
                val encodedIds = src.encode(testText)
                log("编码 '$testText' -> IDs: ${encodedIds.joinToString(", ")}")

                // 5. 解码测试（token IDs → 文本）
                _state.value = TestState.Testing("解码测试: token IDs → 文本")
                val decodedText = src.decode(encodedIds)
                log("解码 IDs -> '$decodedText'")

                // 6. 往返一致性检查
                _state.value = TestState.Testing("往返一致性检查")
                val roundTripOk = decodedText == testText
                log("往返一致性: ${if (roundTripOk) "✅ PASS" else "⚠️ DIFF (decoded='$decodedText', expected='$testText')"}")

                // 7. encodeAsPieces 测试
                _state.value = TestState.Testing("Pieces 测试")
                val pieces = src.encodeAsPieces(testText)
                log("Pieces: ${pieces.joinToString(" | ")}")

                // 8. idToPiece / pieceToId 测试
                _state.value = TestState.Testing("ID ↔ Piece 映射测试")
                if (encodedIds.isNotEmpty()) {
                    val firstId = encodedIds[0]
                    val piece = src.idToPiece(firstId)
                    log("idToPiece($firstId) = '$piece'")
                    val backId = src.pieceToId(piece)
                    log("pieceToId('$piece') = $backId (match=${backId == firstId})")
                }

                // 9. 目标语言 tokenizer 测试（英文）
                _state.value = TestState.Testing("Target tokenizer 测试")
                val enText = "Hello world"
                val enIds = tgt.encode(enText)
                log("Target encode '$enText' -> IDs: ${enIds.joinToString(", ")}")
                val enDecoded = tgt.decode(enIds)
                log("Target decode -> '$enDecoded'")

                val summary = buildString {
                    appendLine("=== SentencePiece 测试报告 ===")
                    appendLine("Source vocabSize: ${src.vocabSize()}")
                    appendLine("Target vocabSize: ${tgt.vocabSize()}")
                    appendLine("编码/解码往返: ${if (roundTripOk) "PASS" else "DIFF"}")
                    appendLine("中文 '$testText' -> IDs: ${encodedIds.joinToString(", ")}")
                    appendLine("Pieces: ${pieces.joinToString(" | ")}")
                    appendLine("英文 '$enText' -> IDs: ${enIds.joinToString(", ")}")
                }
                _state.value = TestState.Success(summary)

            } catch (e: Exception) {
                Logger.e(TAG, "SentencePiece 测试失败", e)
                _state.value = TestState.Error("${e.javaClass.simpleName}: ${e.message}")
                log("ERROR: ${e.message}")
            }
        }
    }

    /**
     * 从应用私有目录加载模型（用于测试从 llm_models/opus-mt-zh-en 加载）
     */
    fun startTestFromModelDir(modelDir: String) {
        startTest(
            sourceModelPath = "$modelDir/source.spm",
            targetModelPath = "$modelDir/target.spm"
        )
    }

    private fun log(message: String) {
        Logger.d(TAG, message)
        _logLines.value = _logLines.value + message
    }

    override fun onCleared() {
        sourceTokenizer?.close()
        targetTokenizer?.close()
        super.onCleared()
    }
}
