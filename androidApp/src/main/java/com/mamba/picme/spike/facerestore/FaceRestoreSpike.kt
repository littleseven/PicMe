package com.mamba.picme.spike.facerestore

import android.graphics.Bitmap
import android.util.Log
import com.mamba.picme.beauty.api.facedetect.FaceDetection

/**
 * Phase-0 人脸修复 spike 编排器。
 *
 * 流程：检测 → 逐张人脸：align512（保留 forwardMatrix）→ restore → pasteBack → 累积到同一输出 Bitmap。
 *
 * **检测与修复均以函数参数注入**，不硬接 FaceDetector / DI，保证可在设备无关的上下文中测试。
 * 正式接入时由上层注入真实 FaceDetector 和 CodeFormerRestorer。
 */
object FaceRestoreSpike {

    private const val TAG = "PoLang:SpikeFaceRestore"

    /**
     * @param original 原图 Bitmap
     * @param detect 人脸检测函数（注入），返回像素坐标的 [FaceDetection] 列表
     * @param restore 512² 人脸修复函数（注入）；返回 null 则该人脸保持原样
     * @return 所有可修复人脸贴回后的 Bitmap（尺寸同 [original]）；无人脸则原样返回
     */
    suspend fun run(
        original: Bitmap,
        detect: (Bitmap) -> List<FaceDetection>,
        restore: (Bitmap) -> Bitmap?
    ): Bitmap {
        val detections = detect(original)
        if (detections.isEmpty()) {
            Log.d(TAG, "no face detected, returning original")
            return original
        }
        Log.d(TAG, "detected ${detections.size} face(s)")

        // 累积输出：每贴回一张人脸后作为下一张的输入，保证多人脸正确叠加。
        var current = original.copy(Bitmap.Config.ARGB_8888, true)
        var pastedCount = 0

        for ((faceIndex, detection) in detections.withIndex()) {
            val landmarks = detection.landmarks5
            if (landmarks == null || landmarks.size < 10) {
                Log.d(TAG, "face[$faceIndex] missing landmarks, skipping")
                continue
            }

            val aligned = FaceAlign512.align(current, landmarks)
            if (aligned == null) {
                Log.d(TAG, "face[$faceIndex] align failed (degenerate), skipping")
                continue
            }

            val restored = restore(aligned.bitmap)
            if (restored == null) {
                Log.d(TAG, "face[$faceIndex] restore returned null, leaving untouched")
                continue
            }
            if (restored.width != FaceAlign512.SIZE || restored.height != FaceAlign512.SIZE) {
                Log.w(TAG, "face[$faceIndex] restore output ${restored.width}x${restored.height} != 512, skipping")
                continue
            }

            current = FacePasteBack.pasteBack(current, restored, aligned.forwardMatrix, detection.roi)
            pastedCount++
        }

        Log.d(TAG, "pasted back $pastedCount/${detections.size} face(s)")
        return current
    }
}
