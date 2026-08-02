package com.mamba.picme.data.download

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 校验 [RecommendedModelAutoDownloader.computeMissing]：推荐集合去除已下载与进行中，保持稳定顺序。
 */
class RecommendedModelAutoDownloaderTest {

    @Test
    fun missing_is_all_recommended_minus_downloaded_and_inprogress() {
        val downloaded = setOf("mediapipe-face-landmarker", "modnet-onnx")
        val inProgress = setOf("u2netp-onnx")
        val expected = ModelConfig.RECOMMENDED_MODEL_IDS
            .toList()
            .filter { id -> id !in downloaded && id !in inProgress }
        assertEquals(
            expected,
            RecommendedModelAutoDownloader.computeMissing(downloaded, inProgress)
        )
    }

    @Test
    fun nothing_missing_returns_empty() {
        val all = ModelConfig.RECOMMENDED_MODEL_IDS
        assertEquals(
            emptyList<String>(),
            RecommendedModelAutoDownloader.computeMissing(all, emptySet())
        )
    }
}
