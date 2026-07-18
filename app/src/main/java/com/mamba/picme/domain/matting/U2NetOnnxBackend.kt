package com.mamba.picme.domain.matting

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.nio.FloatBuffer

/** u2netp ONNX Runtime 推理后端。返回概率图 FloatArray（长度 INPUT_SIZE^2，0..1），失败返回 null。 */
class U2NetOnnxBackend(
    context: Context,
    private val resolver: MattingModelResolver
) {
    companion object {
        private const val TAG = "PoLang:Matting"
        private const val MODEL_ID = "u2netp-onnx"
    }

    private val appContext = context.applicationContext
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null

    val isInitialized: Boolean
        get() = session != null

    suspend fun initialize(): Boolean {
        if (session != null) return true
        val modelFile = resolver.resolve(MODEL_ID) ?: run {
            Log.w(TAG, "u2netp model not found via resolver")
            return false
        }
        return try {
            val options = OrtSession.SessionOptions().apply {
                setInterOpNumThreads(2)
                setIntraOpNumThreads(2)
            }
            session = env.createSession(modelFile.absolutePath, options)
            Log.i(TAG, "U2NetOnnxBackend initialized (${modelFile.name})")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init u2netp session", e)
            release()
            false
        }
    }

    /** 推理；返回概率图（0..1），长度 INPUT_SIZE*INPUT_SIZE。输出用 floatBuffer 读取，与导出秩无关。 */
    fun infer(bitmap: Bitmap): FloatArray? {
        val s = session ?: run {
            Log.w(TAG, "session not initialized")
            return null
        }
        return try {
            val size = U2NetPreprocessor.INPUT_SIZE
            val nchw = U2NetPreprocessor.bitmapToNchw(bitmap, size)
            val shape = longArrayOf(1L, 3L, size.toLong(), size.toLong())
            val inputName = s.inputNames.first()
            val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(nchw), shape)
            try {
                s.run(mapOf(inputName to tensor)).use { results ->
                    val raw = flattenFloats(results.get(0).value)
                    // sigmoid（部分导出已含 sigmoid；对已饱和值幂等安全）
                    FloatArray(raw.size) { i ->
                        val v = raw[i]
                        if (v in 0f..1f) v else (1.0f / (1.0f + Math.exp((-v).toDouble()))).toFloat()
                    }
                }
            } finally {
                tensor.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "u2netp inference failed", e)
            null
        }
    }

    /** 递归展开 OnnxTensor.value（可能为任意秩的嵌套 Float 数组）为一维 FloatArray，与导出秩无关。 */
    private fun flattenFloats(value: Any): FloatArray {
        val out = ArrayList<Float>()
        walkFloats(value, out)
        return FloatArray(out.size) { out[it] }
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
        Log.i(TAG, "U2NetOnnxBackend session released (OrtEnvironment kept alive)")
    }
}
