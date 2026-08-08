package com.mamba.picme.spike.facerestore

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
 * CodeFormer 人脸修复（ONNX Runtime）。
 *
 * Phase-0 spike（throwaway，pre-Phase-4）。需先下载 `codeformer.onnx`；当前无官方 ONNX，
 * 需从 `codeformer.pth` 自行导出。`-w` fidelity weight 已烘焙进导出权重，此处运行时不可配置。
 *
 * 模型走模型中心 `codeformer-onnx`；未下载时 [initialize] 返回 false，调用方跳过。
 *
 * I/O：输入 NCHW 1x3x512x512（对齐人脸，归一化 (x-127.5)/127.5，与 eDifFIQA 同预处理）；
 * 输入 tensor 名动态读取（CodeFormer ONNX 导出名不一，常见 `input` / `input_img`）。
 * 输出为 512x512 RGB 人脸（NCHW `[1,3,512,512]` 或 NHWC `[1,512,512,3]`，运行时按 shape 分支判别），
 * 反归一化 `pixel = value*127.5 + 127.5`（clamp 0..255）。
 */
class CodeFormerRestorer(private val context: Context) {
    companion object {
        private const val TAG = "PoLang:SpikeFaceRestore"
        val MODEL_ID: String = ModelPathConfig.MODEL_ID_CODEFORMER
        private const val FILE_NAME = "codeformer.onnx"
        private const val SIZE = 512
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null

    suspend fun initialize(): Boolean {
        session?.let { return true } // 已就绪则复用，避免重复建会话（NNAPI 编译昂贵）
        val modelDir = ModelPathConfig.getModelDir(context, MODEL_ID)
        val modelFile = File(modelDir, FILE_NAME)
        if (!modelFile.exists()) {
            Log.w(TAG, "CodeFormer model not present: ${modelFile.absolutePath}")
            return false
        }
        return try {
            val options = OrtSession.SessionOptions().apply {
                setInterOpNumThreads(2)
                setIntraOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                try {
                    addNnapi()
                    Log.i(TAG, "CodeFormer: using NNAPI")
                } catch (e: Exception) {
                    Log.w(TAG, "CodeFormer: NNAPI unavailable, CPU fallback", e)
                }
            }
            session = env.createSession(modelFile.absolutePath, options)
            Log.i(TAG, "CodeFormer session initialized")
            true
        } catch (e: Exception) {
            Log.e(TAG, "CodeFormer initialize failed", e)
            false
        }
    }

    /**
     * 对一张已对齐到 512x512 的人脸 [Bitmap] 做修复；失败或未就绪返回 null。
     * 对齐（5 点相似变换）由调用方完成。`-w` fidelity weight 已固化在权重中。
     */
    fun restore(alignedFace512: Bitmap): Bitmap? {
        val sess = session ?: run {
            Log.w(TAG, "CodeFormer session not initialized")
            return null
        }
        return try {
            val input = preprocess(alignedFace512)
            val shape = longArrayOf(1L, 3L, SIZE.toLong(), SIZE.toLong())
            val inputName = sess.inputNames.first()
            val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape)
            try {
                sess.run(mapOf(inputName to tensor)).use { results ->
                    val resultTensor = results.get(0) as OnnxTensor
                    val outShape = resultTensor.info.shape
                    val collected = ArrayList<Float>()
                    walkFloats(resultTensor.value, collected)
                    val floats = FloatArray(collected.size) { idx -> collected[idx] }
                    postprocess(floats, isChannelsFirst(outShape))
                }
            } finally {
                tensor.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "CodeFormer inference failed", e)
            null
        }
    }

    /** NCHW + (x-127.5)/127.5（MobileFaceNet 系标准人脸归一化，与 eDifFIQA 同）。 */
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

    /**
     * 将平面/交错 float 数据反归一化为 512x512 ARGB_8888 [Bitmap]。
     * [channelsFirst]=true → NCHW（R/G/B 三平面）；false → NHWC（逐像素交错）。
     */
    private fun postprocess(floats: FloatArray, channelsFirst: Boolean): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val px = IntArray(SIZE * SIZE)
        val plane = SIZE * SIZE
        for (i in 0 until plane) {
            val r: Float
            val g: Float
            val b: Float
            if (channelsFirst) {
                r = floats[i]
                g = floats[plane + i]
                b = floats[2 * plane + i]
            } else {
                r = floats[i * 3]
                g = floats[i * 3 + 1]
                b = floats[i * 3 + 2]
            }
            val ri = clamp255(r * 127.5f + 127.5f)
            val gi = clamp255(g * 127.5f + 127.5f)
            val bi = clamp255(b * 127.5f + 127.5f)
            px[i] = (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
        }
        bitmap.setPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        return bitmap
    }

    /** NCHW `[1,3,512,512]` → channels-first；NHWC `[1,512,512,3]` → channels-last。默认按 NCHW。 */
    private fun isChannelsFirst(shape: LongArray): Boolean {
        if (shape.size == 4 && shape[1] == 3L) return true
        if (shape.size == 4 && shape[3] == 3L) return false
        Log.w(TAG, "Unexpected output shape ${shape.toList()}, assuming NCHW")
        return true
    }

    private fun clamp255(v: Float): Int {
        val i = v.toInt()
        return if (i < 0) 0 else if (i > 255) 255 else i
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
        Log.i(TAG, "CodeFormer session released")
    }
}
