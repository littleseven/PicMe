@file:Suppress("TooGenericExceptionCaught") // 通用兜底：catch(Exception) 防崩溃，已记录日志
package com.mamba.picme.domain.matting

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.util.Log
import java.nio.FloatBuffer

/** MODNet ONNX Runtime 推理后端。返回连续 Alpha FloatArray（长度 INPUT_SIZE^2，0..1），失败返回 null。 */
class ModNetOnnxBackend(
    private val resolver: MattingModelResolver
) {
    companion object {
        private const val TAG = "PoLang:Matting"
        private const val MODEL_ID = "modnet-onnx"
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null

    val isInitialized: Boolean
        get() = session != null

    suspend fun initialize(): Boolean {
        if (session != null) return true
        val modelFile = resolver.resolve(MODEL_ID) ?: run {
            Log.w(TAG, "modnet model not found via resolver")
            return false
        }
        return try {
            val options = OrtSession.SessionOptions().apply {
                setInterOpNumThreads(2)
                setIntraOpNumThreads(2)
            }
            session = env.createSession(modelFile.absolutePath, options)
            Log.i(TAG, "ModNetOnnxBackend initialized (${modelFile.name})")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init modnet session", e)
            release()
            false
        }
    }

    /** 推理；返回连续 Alpha（0..1），长度 INPUT_SIZE*INPUT_SIZE。秩无关读取。 */
    fun infer(bitmap: Bitmap): FloatArray? {
        val s = session ?: run {
            Log.w(TAG, "modnet session not initialized")
            return null
        }
        return try {
            val size = ModNetPreprocessor.INPUT_SIZE
            val nchw = ModNetPreprocessor.bitmapToNchw(bitmap, size)
            val shape = longArrayOf(1L, 3L, size.toLong(), size.toLong())
            val inputName = s.inputNames.first()
            val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(nchw), shape)
            try {
                s.run(mapOf(inputName to tensor)).use { results ->
                    val raw = flattenFloats(results.get(0).value)
                    // MODNet 输出已是 sigmoid Alpha；对越界值幂等保护
                    FloatArray(raw.size) { i ->
                        val v = raw[i]
                        if (v in 0f..1f) v else (1.0f / (1.0f + Math.exp((-v).toDouble()))).toFloat()
                    }
                }
            } finally {
                tensor.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "modnet inference failed", e)
            null
        }
    }

    private fun flattenFloats(value: Any): FloatArray {
        val out = ArrayList<Float>()
        walkFloats(value, out)
        return FloatArray(out.size) { i -> out[i] }
    }

    private fun walkFloats(v: Any?, out: ArrayList<Float>) {
        when (v) {
            is FloatArray -> for (f in v) out.add(f)
            is Array<*> -> for (e in v) walkFloats(e, out)
            is Number -> out.add(v.toFloat())
        }
    }

    fun release() {
        session?.close()
        session = null
        Log.i(TAG, "ModNetOnnxBackend session released (OrtEnvironment kept alive)")
    }
}
