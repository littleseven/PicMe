package com.mamba.picme.domain.matting

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

class AssetMattingModelResolverTest {

    @Test
    fun `resolve copies asset to filesDir and caches on second call`() {
        val tmpRoot = File.createTempFile("matting_test", null).apply { delete(); mkdirs() }
        val fakeBytes = byteArrayOf(1, 2, 3, 4)
        val provider = AssetBytesProvider { path ->
            if (path == "matting/u2netp.onnx") fakeBytes else null
        }
        val first = AssetMattingModelResolver.resolveBlocking(
            tmpRoot, provider, "u2netp-onnx", "matting/u2netp.onnx", "u2netp.onnx"
        )
        assertNotNull(first)
        assertArrayEquals(fakeBytes, first!!.readBytes())

        // 第二次用会抛错的 provider 证明走的是缓存（不再读 assets）
        val errorProvider = AssetBytesProvider { path -> error("cache miss: $path") }
        val cached = AssetMattingModelResolver.resolveBlocking(
            tmpRoot, errorProvider, "u2netp-onnx", "matting/u2netp.onnx", "u2netp.onnx"
        )
        assertNotNull(cached)
        assertArrayEquals(fakeBytes, cached!!.readBytes())

        tmpRoot.deleteRecursively()
    }

    @Test
    fun `resolve returns null when asset missing`() {
        val tmpRoot = File.createTempFile("matting_test2", null).apply { delete(); mkdirs() }
        val provider = AssetBytesProvider { null }
        val result = AssetMattingModelResolver.resolveBlocking(
            tmpRoot, provider, "u2netp-onnx", "matting/u2netp.onnx", "u2netp.onnx"
        )
        assert(result == null)
        tmpRoot.deleteRecursively()
    }
}
