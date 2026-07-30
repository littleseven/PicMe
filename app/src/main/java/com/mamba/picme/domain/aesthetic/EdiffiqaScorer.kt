package com.mamba.picme.domain.aesthetic

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.mamba.picme.data.download.ModelPathConfig
import java.io.File
import java.nio.FloatBuffer

/**
 * eDifFIQA 人脸画质评分（ONNX Runtime，参考 ModNetOnnxBackend 范式）。
 *
 * 模型走模型中心 `ediffiqa-face-quality-onnx`；未下载时 [initialize] 返回 false，调用方跳过。
 *
 * I/O（实测）：输入 `input` NCHW 1x3x112x112（对齐人脸，归一化 (x-127.5)/127.5）；
 * 输出 `output` 1x1 标量质量分（~0~1，越高越好）。
 */
class EdiffiqaScorer(private val context: Context) {
    companion object {
        private const val TAG = "PoLang:Aesthetic"
        val MODEL_ID: String = ModelPathConfig.MODEL_ID_EDIFFIQA
        private const val FILE_NAME = "ediffiqa_tiny.onnx"
        private const val SIZE = 112
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null

    suspend fun initialize(): Boolean {
        val modelDir = ModelPathConfig.getModelDir(context, MODEL_ID)
        val modelFile = File(modelDir, FILE_NAME)
        if (!modelFile.exists()) {
            Log.w(TAG, "eDifFIQA model not present: ${modelFile.absolutePath}")
            return false
        }
        return try {
            val options = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            }
            session = env.createSession(modelFile.absolutePath, options)
            Log.i(TAG, "eDifFIQA session initialized")
            true
        } catch (e: Exception) {
            Log.e(TAG, "eDifFIQA initialize failed", e)
            false
        }
    }

    /**
     * 给一张已对齐到 112x112 的人脸 [Bitmap] 打质量分；失败返回 null。
     * 对齐（5 点相似变换）由调用方经 [FaceAligner] 完成。
     */
    fun score(alignedFace: Bitmap): Float? {
        val sess = session ?: run {
            Log.w(TAG, "eDifFIQA session not initialized")
            return null
        }
        return try {
            val input = preprocess(alignedFace)
            val shape = longArrayOf(1L, 3L, SIZE.toLong(), SIZE.toLong())
            val inputName = sess.inputNames.first()
            val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape)
            try {
                sess.run(mapOf(inputName to tensor)).use { results ->
                    val out = ArrayList<Float>()
                    walkFloats(results.get(0).value, out)
                    out.firstOrNull()
                }
            } finally {
                tensor.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "eDifFIQA inference failed", e)
            null
        }
    }

    /** NCHW + (x-127.5)/127.5（MobileFaceNet 系标准人脸归一化）。 */
    private fun preprocess(bitmap: Bitmap): FloatArray {
        val sized = if (bitmap.width == SIZE && bitmap.height == SIZE) bitmap
            else Bitmap.createScaledBitmap(bitmap, SIZE, SIZE, true)
        val out = FloatArray(3 * SIZE * SIZE)
        val px = IntArray(SIZE * SIZE)
        sized.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        val plane = SIZE * SIZE
        for (i in px.indices) {
            out[i] = (((px[i] shr 16) and 0xFF) - 127.5f) / 127.5f           // R
            out[plane + i] = (((px[i] shr 8) and 0xFF) - 127.5f) / 127.5f    // G
            out[out.size - plane + i] = ((px[i] and 0xFF) - 127.5f) / 127.5f // B
        }
        if (sized !== bitmap) sized.recycle()
        return out
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
        Log.i(TAG, "eDifFIQA session released")
    }
}
