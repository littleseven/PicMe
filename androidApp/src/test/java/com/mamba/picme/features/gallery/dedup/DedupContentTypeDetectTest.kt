package com.mamba.picme.features.gallery.dedup

import com.mamba.picme.domain.dedup.DedupContentType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 内容类型识别纯函数单测（spec §10.2 / AC-7 退化原则）：
 * SCREENSHOT 路径 > DOCUMENT 文字密度/标签 > PORTRAIT 人脸信号 > GENERAL 兜底。
 */
class DedupContentTypeDetectTest {

    private fun detect(
        relativePath: String? = null,
        ocrText: String? = null,
        labels: String? = null,
        hasFace: Boolean = false,
        faceQualityScore: Float? = null,
    ) = detectContentType(
        relativePath = relativePath,
        ocrText = ocrText,
        labels = labels,
        hasFace = hasFace,
        faceQualityScore = faceQualityScore,
    )

    @Test
    fun `screenshots dir in relative path detects SCREENSHOT case-insensitively`() {
        assertEquals(DedupContentType.SCREENSHOT, detect(relativePath = "DCIM/Screenshots/"))
        assertEquals(DedupContentType.SCREENSHOT, detect(relativePath = "Pictures/screenshots/"))
        assertEquals(DedupContentType.SCREENSHOT, detect(relativePath = "Pictures/SCREENSHOTS/"))
        assertEquals(DedupContentType.GENERAL, detect(relativePath = "DCIM/Camera/"))
    }

    @Test
    fun `long ocr text over threshold detects DOCUMENT`() {
        val dense = "字".repeat(DOCUMENT_OCR_CHAR_THRESHOLD + 1)
        assertEquals(DedupContentType.DOCUMENT, detect(ocrText = dense))
        val sparse = "字".repeat(DOCUMENT_OCR_CHAR_THRESHOLD)
        assertEquals(DedupContentType.GENERAL, detect(ocrText = sparse))
    }

    @Test
    fun `document-like labels detect DOCUMENT`() {
        assertEquals(DedupContentType.DOCUMENT, detect(labels = "室内,document,纸张"))
        assertEquals(DedupContentType.DOCUMENT, detect(labels = "receipt photo"))
        assertEquals(DedupContentType.DOCUMENT, detect(labels = "证件照"))
        assertEquals(DedupContentType.GENERAL, detect(labels = "风景,山脉"))
    }

    @Test
    fun `face signals detect PORTRAIT`() {
        assertEquals(DedupContentType.PORTRAIT, detect(hasFace = true))
        assertEquals(DedupContentType.PORTRAIT, detect(faceQualityScore = 0.8f))
    }

    @Test
    fun `priority is SCREENSHOT over DOCUMENT over PORTRAIT`() {
        // 截图目录 + 长 OCR + 人脸信号同时命中：截图语义最强
        assertEquals(
            DedupContentType.SCREENSHOT,
            detect(
                relativePath = "DCIM/Screenshots/",
                ocrText = "x".repeat(DOCUMENT_OCR_CHAR_THRESHOLD + 1),
                hasFace = true,
            ),
        )
        // 文档保守性优先于人像美观
        assertEquals(
            DedupContentType.DOCUMENT,
            detect(labels = "receipt", hasFace = true, faceQualityScore = 0.9f),
        )
    }

    @Test
    fun `TAG-uncovered photos fall back to GENERAL`() {
        // 退化原则：信号全空（含 TAG 未覆盖存量照片）一律 GENERAL，行为与 v1.0 一致
        assertEquals(DedupContentType.GENERAL, detect())
        assertEquals(DedupContentType.GENERAL, detect(relativePath = null, ocrText = "", labels = ""))
    }
}
