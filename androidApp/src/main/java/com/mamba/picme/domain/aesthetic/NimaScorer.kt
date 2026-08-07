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
 * NIMA 美学评分（ONNX Runtime，参考 EdiffiqaScorer 范式）。
 *
 * 模型走模型中心 `nima-aesthetic-onnx`；未下载时 [initialize] 返回 false，调用方跳过。
 *
 * 权重来源：idealo `weights_mobilenet_aesthetic_0.07`（MobileNet V1 + Dropout + Dense(10, softmax)），
 * 经 parity 验证 HF `cromsc/nima-mobilenet-aesthetic` 与之逐位相同（见 docs/superpowers/specs/
 * 2026-08-02-nima-aesthetic-cover-design.md）。
 *
 * I/O（实测）：输入 `input_1` NCHW→实为 **NHWC** `1x224x224x3`（逐像素交错 RGB，归一化 (x-127.5)/127.5）；
 * 输出 `dense_1` `1x10` softmax 分布。分数 = Σ p_i·(i+1)（i=0..9）∈ [1,10]，越高越美。
 *
 * 与 [EdiffiqaScorer] 的差异：整图打分（不需人脸对齐）、NHWC 交错（非 NCHW 三 plane）、10-bin 期望分（非单标量）。
 */
class NimaScorer(private val context: Context) : AestheticScorer {
    companion object {
        private const val TAG = "PoLang:Aesthetic"
        val MODEL_ID: String = ModelPathConfig.MODEL_ID_NIMA
        private const val FILE_NAME = "nima_mobilenet_aesthetic.onnx"
        private const val SIZE = 224

        /** NHWC 交错（逐像素 RGB 连续）+ (x-127.5)/127.5。纯数组变换，便于 JVM 单测。 */
        internal fun preprocessPixels(px: IntArray): FloatArray {
            val out = FloatArray(3 * px.size)
            for (i in px.indices) {
                val base = i * 3
                out[base] = (((px[i] shr 16) and 0xFF) - 127.5f) / 127.5f      // R
                out[base + 1] = (((px[i] shr 8) and 0xFF) - 127.5f) / 127.5f   // G
                out[base + 2] = ((px[i] and 0xFF) - 127.5f) / 127.5f           // B
            }
            return out
        }

        /** softmax 10-bin 分布 → 期望分 Σ p_i·(i+1) ∈ [1,10]。 */
        internal fun expectedScore(distribution: List<Float>): Float {
            var s = 0f
            for (i in distribution.indices) {
                s += distribution[i] * (i + 1f)
            }
            return s
        }
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null

    override suspend fun initialize(): Boolean {
        session?.let { return true } // 已就绪则复用，避免重复建会话（NNAPI 编译昂贵）
        val modelDir = ModelPathConfig.getModelDir(context, MODEL_ID)
        val modelFile = File(modelDir, FILE_NAME)
        if (!modelFile.exists()) {
            Log.w(TAG, "NIMA model not present: ${modelFile.absolutePath}")
            return false
        }
        return try {
            val options = OrtSession.SessionOptions().apply {
                setInterOpNumThreads(2)
                setIntraOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                // NNAPI 加速（GPU/DSP），失败兜底 CPU；NIMA 输入 NHWC 与 NNAPI 原生布局一致。
                try {
                    addNnapi()
                    Log.i(TAG, "NIMA: using NNAPI")
                } catch (e: Exception) {
                    Log.w(TAG, "NIMA: NNAPI unavailable, CPU fallback", e)
                }
            }
            session = env.createSession(modelFile.absolutePath, options)
            Log.i(TAG, "NIMA session initialized")
            true
        } catch (e: Exception) {
            Log.e(TAG, "NIMA initialize failed", e)
            false
        }
    }

    /**
     * 给一张整图 [Bitmap] 打美学分；失败返回 null。内部 resize 到 224×224，无需人脸对齐。
     */
    override fun score(bitmap: Bitmap): Float? {
        val sess = session ?: run {
            Log.w(TAG, "NIMA session not initialized")
            return null
        }
        return try {
            val input = preprocess(bitmap)
            val shape = longArrayOf(1L, SIZE.toLong(), SIZE.toLong(), 3L)
            val inputName = sess.inputNames.first()
            val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape)
            try {
                sess.run(mapOf(inputName to tensor)).use { results ->
                    val out = ArrayList<Float>(10)
                    walkFloats(results.get(0).value, out)
                    if (out.isEmpty()) return null
                    expectedScore(out)
                }
            } finally {
                tensor.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "NIMA inference failed", e)
            null
        }
    }

    /** NHWC 交错（逐像素 RGB 连续）+ (x-127.5)/127.5。 */
    private fun preprocess(bitmap: Bitmap): FloatArray {
        val sized = if (bitmap.width == SIZE && bitmap.height == SIZE) bitmap
            else Bitmap.createScaledBitmap(bitmap, SIZE, SIZE, true)
        val px = IntArray(SIZE * SIZE)
        sized.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        if (sized !== bitmap) sized.recycle()
        return preprocessPixels(px)
    }

    private fun walkFloats(v: Any?, out: ArrayList<Float>) {
        when (v) {
            is FloatArray -> for (f in v) out.add(f)
            is Array<*> -> for (e in v) walkFloats(e, out)
            is Number -> out.add(v.toFloat())
        }
    }

    override fun release() {
        session?.close()
        session = null
        Log.i(TAG, "NIMA session released")
    }
}
