package com.mamba.picme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mamba.picme.beauty.internal.facedetect.MnnLandmarkDetector
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Landmark 检测器对比测试
 * 使用同一张静态图片分别测试 ONNX 和 MNN 路径的 106 点输出
 */
@RunWith(AndroidJUnit4::class)
class LandmarkDetectorComparisonTest {

    companion object {
        private const val TAG = "LandmarkCompare"
    }

    private fun testMnnLandmarks(context: Context, bitmap: Bitmap, roi: RectF, forceGpu: Boolean): FloatArray? {
        val mode = if (forceGpu) "GPU" else "CPU"
        println("[MNN-$mode] Initializing MnnLandmarkDetector (forceGpu=$forceGpu)...")
        val detector = MnnLandmarkDetector(context, requireGpu = forceGpu)

        println("[MNN-$mode] Running detectLandmarks on ${bitmap.width}x${bitmap.height} bitmap...")
        val landmarks = detector.detectLandmarks(bitmap, 1, roi)

        if (landmarks != null) {
            println("[MNN-$mode] Detected ${landmarks.size / 2} points")
            // 输出前 5 个点
            for (i in 0 until minOf(5, landmarks.size / 2)) {
                val px = landmarks[i * 2] * bitmap.width
                val py = landmarks[i * 2 + 1] * bitmap.height
                println("[MNN-$mode] Point $i: ($px, $py)")
            }
        } else {
            println("[MNN-$mode] No landmarks detected")
        }

        detector.release()
        return landmarks
    }

    private fun loadTestBitmap(context: Context, assetPath: String): Bitmap? {
        return try {
            context.assets.open(assetPath).use { input ->
                BitmapFactory.decodeStream(input)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load test image from assets/$assetPath", e)
            null
        }
    }

    data class PointDiff(
        val index: Int,
        val dx: Float,
        val dy: Float,
        val distance: Float
    )

    private fun calculatePointDiffs(
        onnx: FloatArray,
        mnn: FloatArray,
        width: Int,
        height: Int
    ): List<PointDiff> {
        val diffs = mutableListOf<PointDiff>()
        val pointCount = minOf(onnx.size, mnn.size) / 2

        for (i in 0 until pointCount) {
            val onnxX = onnx[i * 2] * width
            val onnxY = onnx[i * 2 + 1] * height
            val mnnX = mnn[i * 2] * width
            val mnnY = mnn[i * 2 + 1] * height

            val dx = abs(onnxX - mnnX)
            val dy = abs(onnxY - mnnY)
            val dist = kotlin.math.sqrt(dx * dx + dy * dy)

            diffs.add(PointDiff(i, dx, dy, dist))
        }

        return diffs
    }

    private fun printDiffSummary(diffs: List<PointDiff>) {
        val sorted = diffs.sortedByDescending { it.distance }
        val maxDiff = sorted.first()
        val avgDiff = diffs.map { it.distance }.average()
        val medianDiff = diffs.map { it.distance }.sorted()[diffs.size / 2]

        println("  Max diff: point ${maxDiff.index}, dist=${String.format("%.2f", maxDiff.distance)}px " +
                "(dx=${String.format("%.2f", maxDiff.dx)}, dy=${String.format("%.2f", maxDiff.dy)})")
        println("  Avg diff: ${String.format("%.2f", avgDiff)}px")
        println("  Median diff: ${String.format("%.2f", medianDiff)}px")
        println("  Top 10 worst points:")
        for (i in 0 until minOf(10, sorted.size)) {
            val d = sorted[i]
            println("    #${d.index}: dist=${String.format("%.2f", d.distance)}px " +
                    "(dx=${String.format("%.2f", d.dx)}, dy=${String.format("%.2f", d.dy)})")
        }

        // 统计误差分布
        val within1px = diffs.count { it.distance < 1 }
        val within3px = diffs.count { it.distance < 3 }
        val within5px = diffs.count { it.distance < 5 }
        val within10px = diffs.count { it.distance < 10 }
        println("  Error distribution:")
        println("    <1px: $within1px/${diffs.size} (${String.format("%.1f", within1px * 100f / diffs.size)}%)")
        println("    <3px: $within3px/${diffs.size} (${String.format("%.1f", within3px * 100f / diffs.size)}%)")
        println("    <5px: $within5px/${diffs.size} (${String.format("%.1f", within5px * 100f / diffs.size)}%)")
        println("    <10px: $within10px/${diffs.size} (${String.format("%.1f", within10px * 100f / diffs.size)}%)")
    }
}
