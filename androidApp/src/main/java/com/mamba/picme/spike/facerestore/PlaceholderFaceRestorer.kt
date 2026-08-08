package com.mamba.picme.spike.facerestore

import android.graphics.Bitmap

/**
 * Phase-0 spike 占位修复器：**恒等映射**（原样返回输入 512²）。
 *
 * 这不是真正的修复模型，仅用于让流水线在没有 CodeFormer 时跑通。
 * 用恒等映射的妙处：若 [FacePasteBack] 贴回无接缝，恒等修复后原图应 **无可见痕迹**，
 * 从而在不需要任何神经模型的前提下验证贴回的正确性。
 *
 * 正式接入时由 CodeFormerRestorer 替换（另一个 agent 负责）。
 */
object PlaceholderFaceRestorer {

    /** `(Bitmap) -> Bitmap?` 签名，与 orchestrator 的 restore 参数一致。原样返回输入。 */
    fun restore(input: Bitmap): Bitmap? = input
}
