package com.mamba.picme.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 校验 [ParallelFileDownloader.computeChunkRanges]：把文件总长切成并发下载区段，
 * 恰好连续覆盖 [0, totalSize-1]，无重叠无空隙。
 */
class ParallelFileDownloaderTest {

    @Test
    fun empty_for_zero_or_negative_size() {
        assertTrue(ParallelFileDownloader.computeChunkRanges(0L, 4, 8).isEmpty())
        assertTrue(ParallelFileDownloader.computeChunkRanges(-1L, 4, 8).isEmpty())
    }

    @Test
    fun single_range_when_below_min_chunk_or_one_chunk() {
        assertEquals(listOf(0L..49L), ParallelFileDownloader.computeChunkRanges(50L, 4, 100))
        assertEquals(listOf(0L..99L), ParallelFileDownloader.computeChunkRanges(100L, 1, 10))
    }

    @Test
    fun four_even_chunks_cover_whole_range() {
        val ranges = ParallelFileDownloader.computeChunkRanges(100L, 4, 10)
        assertEquals(4, ranges.size)
        assertEquals(0L..24L, ranges[0])
        assertEquals(25L..49L, ranges[1])
        assertEquals(50L..74L, ranges[2])
        assertEquals(75L..99L, ranges[3])
    }

    @Test
    fun fewer_chunks_when_total_below_chunkcount_times_minchunk() {
        // 100 / minChunk(30) = 3 → 即便要 8 段，也只切 3 段（每段 ≥30）
        val ranges = ParallelFileDownloader.computeChunkRanges(100L, 8, 30)
        assertEquals(3, ranges.size)
        // 每段不小于 minChunkSize
        for (r in ranges) {
            assertTrue("段长 ${r.last - r.first + 1} 应 ≥ 30", r.last - r.first + 1 >= 30)
        }
    }

    @Test
    fun ranges_contiguous_and_complete_large() {
        val total = 1_000_000L
        val ranges = ParallelFileDownloader.computeChunkRanges(total, 6, 10_000)
        assertEquals(0L, ranges.first().first)
        assertEquals(total - 1, ranges.last().last)
        var prevEnd = -1L
        for (r in ranges) {
            assertEquals(prevEnd + 1, r.first)
            prevEnd = r.last
        }
        assertEquals(total - 1, prevEnd)
    }
}
