package com.mamba.picme.domain.usecase

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 校验 [GenerateSummaryOnDemandUseCase] 的「统一规格」判定逻辑。
 *
 * 背景：on-demand 路径曾只写 `{"summary":"…"}` 自然语言桩（遗留自已回退的 ML Kit 方案），
 * 导致新增/生成图片的 labels 不符合统一规格（face/scene/activity/objects/tags/summary）。
 * 判定函数必须能把这种 summary-only 桩识别为「未完成」，触发完整管道重打标。
 */
class GenerateSummaryOnDemandUseCaseTest {

    @Test
    fun `isFullyTagged returns false for null labels`() {
        assertFalse(GenerateSummaryOnDemandUseCase.isFullyTagged(null))
    }

    @Test
    fun `isFullyTagged returns false for blank labels`() {
        assertFalse(GenerateSummaryOnDemandUseCase.isFullyTagged(""))
    }

    @Test
    fun `isFullyTagged returns false for summary-only stub without tags array`() {
        // 旧 on-demand 路径写的半成品：只有自然语言 summary，不符合统一规格 → 必须重打标。
        assertFalse(
            GenerateSummaryOnDemandUseCase.isFullyTagged("""{"summary":"一张户外的猫咪照片"}""")
        )
    }

    @Test
    fun `isFullyTagged returns false when tags array present but summary blank`() {
        // 有统一 tags 结构但 summary 缺失 → 仍需重跑管道补全 summary。
        assertFalse(
            GenerateSummaryOnDemandUseCase.isFullyTagged("""{"tags":["猫","户外"],"summary":""}""")
        )
    }

    @Test
    fun `isFullyTagged returns true for unified spec with tags and summary`() {
        // 统一规格：face/scene/activity/objects/tags/summary 完整写出。
        val unified = """
            {"face":{"count":1,"selfie":false,"groupPhoto":false,"personIds":[]},
            "scene":"户外","activity":"拍照","objects":["猫"],
            "tags":["猫","户外","草地"],"summary":"一只猫在草地上"}
        """.trimIndent()
        assertTrue(GenerateSummaryOnDemandUseCase.isFullyTagged(unified))
    }

    @Test
    fun `isFullyTagged returns false for invalid JSON`() {
        assertFalse(GenerateSummaryOnDemandUseCase.isFullyTagged("not a json"))
    }
}
