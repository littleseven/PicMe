package com.mamba.picme.data.download

import com.mamba.picme.core.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * 大文件分块并行下载器：把单个大文件切成多段，并发 Range 请求下载，按偏移写入同一文件。
 *
 * 用于加速模型中心大文件（如 1.4GB llm.mnn.weight）下载——单连接受 CDN 单流限速，
 * 多段并发可成倍提升吞吐。ModelScope 支持 Range（断点续传已验证）。
 *
 * 各 chunk 以独立 [RandomAccessFile] seek 到非重叠偏移并发写，无需拼接、不双倍占盘。
 */
class ParallelFileDownloader(private val client: OkHttpClient) {

    /**
     * 并发下载 [url] 到 [destFile]（总长 [totalSize]）。
     *
     * @param onBytes 每写入一段字节时回调增量（会被多线程并发调用，调用方需自行保证聚合线程安全）
     * @param isCancelled 调用方取消检测（取消时抛 IOException）
     * @throws IOException 区段 HTTP 非 206/200、响应体空、或最终大小不符
     */
    suspend fun download(
        url: String,
        destFile: File,
        totalSize: Long,
        onBytes: (Long) -> Unit,
        isCancelled: () -> Boolean,
        chunkCount: Int = DEFAULT_CHUNK_COUNT,
        minChunkSize: Long = DEFAULT_MIN_CHUNK_SIZE
    ) {
        val ranges = computeChunkRanges(totalSize, chunkCount, minChunkSize)
        if (ranges.isEmpty()) throw IOException("Invalid totalSize=$totalSize for $url")

        // 预分配文件长度（截断），各 chunk 按偏移并发写入非重叠区段
        withContext(Dispatchers.IO) {
            RandomAccessFile(destFile, "rw").use { it.setLength(totalSize) }
        }
        Logger.i(TAG, "Parallel download: $url -> ${destFile.name}, total=$totalSize, chunks=${ranges.size}")

        coroutineScope {
            for ((index, range) in ranges.withIndex()) {
                launch(Dispatchers.IO) {
                    val req = Request.Builder()
                        .url(url)
                        .header("User-Agent", "PoLang-Android/1.0")
                        .header("Range", "bytes=${range.first}-${range.last}")
                        .build()
                    val call = client.newCall(req)
                    call.execute().use { response ->
                        if (response.code != 206 && response.code != 200) {
                            throw IOException("Chunk $index HTTP ${response.code} for ${destFile.name} (Range 不支持?)")
                        }
                        val body = response.body ?: throw IOException("Empty body, chunk $index ${destFile.name}")
                        body.byteStream().use { input ->
                            RandomAccessFile(destFile, "rw").use { raf ->
                                raf.seek(range.first)
                                val buffer = ByteArray(BUFFER_SIZE)
                                while (true) {
                                    if (isCancelled()) throw IOException("Download cancelled")
                                    val n = input.read(buffer)
                                    if (n == -1) break
                                    raf.write(buffer, 0, n)
                                    onBytes(n.toLong())
                                }
                            }
                        }
                    }
                }
            }
        }

        // 最终大小校验
        val actualLen = destFile.length()
        if (actualLen != totalSize) {
            throw IOException("Size mismatch after parallel download: expected=$totalSize, actual=$actualLen, file=${destFile.name}")
        }
    }

    companion object {
        private const val TAG = "Download"

        /** 单 chunk 读写缓冲 */
        private const val BUFFER_SIZE = 262144 // 256KB

        /** 默认并发区段数（连接数） */
        const val DEFAULT_CHUNK_COUNT = 4

        /** 默认单段最小字节（不足则减少区段数） */
        const val DEFAULT_MIN_CHUNK_SIZE = 8L * 1024 * 1024 // 8MB

        /**
         * 把 [totalSize] 字节切成至多 [chunkCount] 个并发区段，每段不小于 [minChunkSize]。
         *
         * 返回闭区间 LongRange 列表，恰好连续覆盖 [0, totalSize-1]，无重叠无空隙。
         * - totalSize <= 0 → 空
         * - chunkCount <= 1 或 totalSize <= minChunkSize → 单区段 [0, totalSize-1]
         * - 余数均匀分给前若干段，保证各区段长度差 ≤ 1
         */
        fun computeChunkRanges(totalSize: Long, chunkCount: Int, minChunkSize: Long): List<LongRange> {
            if (totalSize <= 0) return emptyList()
            val last = totalSize - 1
            if (chunkCount <= 1 || totalSize <= minChunkSize) return listOf(0L..last)
            val effective = minOf(chunkCount.toLong(), totalSize / minChunkSize).toInt().coerceAtLeast(1)
            if (effective <= 1) return listOf(0L..last)
            val base = totalSize / effective
            val remainder = totalSize % effective
            val ranges = mutableListOf<LongRange>()
            var start = 0L
            for (i in 0 until effective) {
                val len = base + if (i < remainder) 1 else 0
                ranges.add(start..(start + len - 1))
                start += len
            }
            return ranges
        }
    }
}
