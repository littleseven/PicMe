package com.mamba.picme.features.camera.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.mamba.picme.agent.core.platform.voice.AsrEngine
import com.mamba.picme.core.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Android 系统 SpeechRecognizer ASR 引擎实现
 *
 * 依赖系统语音识别服务，需要网络连接（部分设备支持离线）。
 */
class SystemAsrEngine(private val context: Context) : AsrEngine {

    private val tag = "SystemAsrEngine"

    override fun isAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context)
    }

    override suspend fun transcribe(audioData: ByteArray): Result<String> {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                }

                speechRecognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}

                    override fun onError(error: Int) {
                        Logger.w(tag, "Speech recognition error: $error")
                        speechRecognizer.destroy()
                        if (continuation.isActive) {
                            continuation.resume(
                                Result.failure(
                                    RuntimeException("Speech recognition error: $error")
                                )
                            )
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION
                        )
                        val text = matches?.firstOrNull()?.trim() ?: ""
                        Logger.d(tag, "Recognition result: $text")
                        speechRecognizer.destroy()
                        if (continuation.isActive) {
                            continuation.resume(Result.success(text))
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {}
                })

                continuation.invokeOnCancellation {
                    speechRecognizer.stopListening()
                    speechRecognizer.destroy()
                }

                @Suppress("TooGenericExceptionCaught")
                try {
                    speechRecognizer.startListening(intent)
                } catch (runtimeException: RuntimeException) {
                    Logger.e(tag, "Failed to start listening", runtimeException)
                    speechRecognizer.destroy()
                    if (continuation.isActive) {
                        continuation.resume(Result.failure(runtimeException))
                    }
                }
            }
        }
    }
}
