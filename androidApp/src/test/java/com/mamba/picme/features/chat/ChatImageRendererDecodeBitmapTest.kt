package com.mamba.picme.features.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归守卫：US-5「ChatImageRenderer 解码 I/O 优化」结构不变量。
 *
 * 背景：decodeBitmap 旧实现会 openInputStream 两次（探边界一次 + 实际解码一次），
 * 造成同一图片被读取两遍 I/O。US-5 改为单次 openInputStream → stream.readBytes()
 * 读入 byte[] → 用 BitmapFactory.decodeByteArray 复用同一 byte[] 做边界探测与实际解码；
 * 并新增 URI→Bitmap 的 LruCache（按字节计数），同一图片重复渲染时命中缓存跳过解码。
 *
 * 为什么用源码级断言而非 Robolectric 行为测试：
 * - 行为上要证明「流只开一次」需插桩 ContentResolver.openInputStream 调用次数，依赖 mockk
 *   且脆弱；而 Robolectric 的 ShadowBitmapFactory 并非真解码（返回 shadow 假图），无法
 *   证明 byte[] 复用是否生效。
 * - 本项目 JVM 单测对 Robolectric SDK36 / mockk 存在大量环境性预存失败（见
 *   memory/test-env-pitfalls），硬门槛为编译通过。
 * - 源码级断言精确、确定、零依赖地锁住「单流 + byte[] 复用」这一 I/O 不变量；一旦未来
 *   重构退回双流解码，本测试立刻失败。
 *
 * 这些断言与任务 T5 的验收命令（awk 提取 decodeBitmap 体 → grep -c openInputStream ≤ 1）
 * 完全同源，把人工 AC 固化为自动化回归。
 */
class ChatImageRendererDecodeBitmapTest {

    private val sourceFile: java.io.File by lazy {
        // Gradle JVM 测试的 user.dir 默认为模块目录（app/）。
        val moduleDir = java.io.File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            java.io.File(moduleDir, SRC_REL_PATH),
            java.io.File(moduleDir.parentFile, "app/$SRC_REL_PATH"),
            java.io.File(moduleDir.parentFile?.parentFile, "app/$SRC_REL_PATH")
        )
        candidates.first { it.exists() }
    }

    private fun decodeBitmapBody(): String {
        val full = sourceFile.readText()
        // 提取 `private fun decodeBitmap(` 到其方法体首个顶层闭合 `}`（与验收命令同一 awk 语义）。
        val startIdx = full.indexOf("private fun decodeBitmap")
        assertTrue("decodeBitmap 方法必须存在", startIdx >= 0)
        // 从方法体第一个 `{` 开始，按花括号配平找到方法结束位置。
        val braceStart = full.indexOf('{', startIdx)
        var depth = 0
        var endIdx = -1
        for (i in braceStart until full.length) {
            when (full[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        endIdx = i + 1
                        break
                    }
                }
            }
        }
        assertTrue("decodeBitmap 方法体必须完整闭合", endIdx > 0)
        return full.substring(startIdx, endIdx)
    }

    @Test
    fun `decodeBitmap opens the input stream at most once`() {
        val body = decodeBitmapBody()
        val openCount = body.split("openInputStream").size - 1
        assertTrue(
            "US-5: decodeBitmap 必须 openInputStream 最多一次，实际 $openCount 次。方法体:\n$body",
            openCount <= 1
        )
    }

    @Test
    fun `decodeBitmap reads bytes once and reuses the byte array`() {
        val body = decodeBitmapBody()
        // 单流读入：stream.readBytes() 把整流读入 byte[]（只应出现一次 readBytes）。
        val readBytesCount = body.split("readBytes()").size - 1
        assertTrue(
            "US-5: decodeBitmap 应通过 stream.readBytes() 一次性读入，实际出现 $readBytesCount 次",
            readBytesCount == 1
        )
        // 复用同一 byte[] 做边界探测与实际解码：decodeByteArray 应出现两次。
        val decodeByteArrayCount = body.split("decodeByteArray").size - 1
        assertTrue(
            "US-5: decodeBitmap 应复用 byte[] 调 decodeByteArray 两次（探边界 + 实际解码），" +
                "实际 $decodeByteArrayCount 次",
            decodeByteArrayCount == 2
        )
        // 确保旧的「直接从 stream 解码」双流写法未被重新引入。
        assertFalse(
            "US-5: decodeBitmap 不得再用 decodeStream（那是双流遗留写法）",
            body.contains("decodeStream")
        )
    }

    @Test
    fun `renderer holds a URI keyed LruCache sized by bitmap bytes`() {
        val full = sourceFile.readText()
        assertTrue(
            "US-5: 必须存在以 URI 为 key、Bitmap 为值的 LruCache",
            full.contains("LruCache<String, Bitmap>")
        )
        assertTrue(
            "US-5: LruCache 必须按位图字节计数（sizeOf 返回 byteCount）",
            full.contains("override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount")
        )
    }

    @Test
    fun `decodeBitmap consults the cache before opening any stream`() {
        val body = decodeBitmapBody()
        val getIdx = body.indexOf("bitmapCache.get")
        assertTrue("US-5: decodeBitmap 必须先查 bitmapCache.get", getIdx >= 0)
        val streamIdx = body.indexOf("openInputStream")
        assertTrue(
            "US-5: 缓存查询必须先于 openInputStream（命中时跳过解码）",
            streamIdx == -1 || getIdx < streamIdx
        )
    }

    @Test
    fun `decodeBitmap stores decoded bitmaps into the cache`() {
        val body = decodeBitmapBody()
        assertTrue(
            "US-5: 解码成功后必须 bitmapCache.put 写回缓存",
            body.contains("bitmapCache.put")
        )
    }
}

private const val SRC_REL_PATH = "src/main/java/com/mamba/picme/features/chat/ChatImageRenderer.kt"
