import XCTest
@testable import PoLang

/// 校验 `ParallelFileDownloader.computeChunkRanges`：把文件总长切成并发下载区段，
/// 恰好连续覆盖 [0, totalSize-1]，无重叠无空隙。
/// （对齐 Android `ParallelFileDownloaderTest` 的用例集）
final class ParallelChunkRangesTest: XCTestCase {

    /// T1：totalSize <= 0 → 空
    func testEmptyForZeroOrNegativeSize() {
        XCTAssertTrue(ParallelFileDownloader.computeChunkRanges(totalSize: 0, chunkCount: 4, minChunkSize: 8).isEmpty)
        XCTAssertTrue(ParallelFileDownloader.computeChunkRanges(totalSize: -1, chunkCount: 4, minChunkSize: 8).isEmpty)
    }

    /// T2：低于 minChunkSize 或单段 → 单区段
    func testSingleRangeWhenBelowMinChunkOrOneChunk() {
        XCTAssertEqual([ChunkRange(start: 0, end: 49)],
                       ParallelFileDownloader.computeChunkRanges(totalSize: 50, chunkCount: 4, minChunkSize: 100))
        XCTAssertEqual([ChunkRange(start: 0, end: 99)],
                       ParallelFileDownloader.computeChunkRanges(totalSize: 100, chunkCount: 1, minChunkSize: 10))
    }

    /// T3：4 段均分，恰好覆盖整段
    func testFourEvenChunksCoverWholeRange() {
        let ranges = ParallelFileDownloader.computeChunkRanges(totalSize: 100, chunkCount: 4, minChunkSize: 10)
        XCTAssertEqual(4, ranges.count)
        XCTAssertEqual(ChunkRange(start: 0, end: 24), ranges[0])
        XCTAssertEqual(ChunkRange(start: 25, end: 49), ranges[1])
        XCTAssertEqual(ChunkRange(start: 50, end: 74), ranges[2])
        XCTAssertEqual(ChunkRange(start: 75, end: 99), ranges[3])
    }

    /// T4：totalSize < chunkCount × minChunkSize → 减少段数，每段 ≥ minChunkSize
    func testFewerChunksWhenTotalBelowChunkCountTimesMinChunk() {
        // 100 / minChunk(30) = 3 → 即便要 8 段，也只切 3 段（每段 ≥30）
        let ranges = ParallelFileDownloader.computeChunkRanges(totalSize: 100, chunkCount: 8, minChunkSize: 30)
        XCTAssertEqual(3, ranges.count)
        for r in ranges {
            XCTAssertGreaterThanOrEqual(r.length, 30, "段长 \(r.length) 应 ≥ 30")
        }
    }

    /// T5：大区段连续且完整覆盖
    func testRangesContiguousAndCompleteLarge() {
        let total: Int64 = 1_000_000
        let ranges = ParallelFileDownloader.computeChunkRanges(totalSize: total, chunkCount: 6, minChunkSize: 10_000)
        XCTAssertEqual(0, ranges.first?.start)
        XCTAssertEqual(total - 1, ranges.last?.end)
        var prevEnd: Int64 = -1
        for r in ranges {
            XCTAssertEqual(prevEnd + 1, r.start)
            prevEnd = r.end
        }
        XCTAssertEqual(total - 1, prevEnd)
    }

    /// T6：1.4GB 大文件按默认参数（4 段 / 8MB）切分——覆盖完整、每段 ≥ 8MB
    func testDefaultParamsOnLargeFile() {
        let total: Int64 = 1_475_225_454  // qwen3_vl_2b llm.mnn.weight 实际大小
        let ranges = ParallelFileDownloader.computeChunkRanges(totalSize: total)
        XCTAssertEqual(4, ranges.count)
        XCTAssertEqual(0, ranges.first?.start)
        XCTAssertEqual(total - 1, ranges.last?.end)
        XCTAssertEqual(total, ranges.reduce(Int64(0)) { $0 + $1.length })
    }

    /// T7：threshold 边界——恰好 32MB 也切分（调用方以 > threshold 判定，这里验证函数本身）
    func testThresholdBoundary() {
        let ranges = ParallelFileDownloader.computeChunkRanges(totalSize: ParallelFileDownloader.parallelThreshold)
        XCTAssertEqual(4, ranges.count)
    }

    /// T8：单字节文件 → 单区段
    func testSingleByteFile() {
        XCTAssertEqual([ChunkRange(start: 0, end: 0)],
                       ParallelFileDownloader.computeChunkRanges(totalSize: 1))
    }
}

/// ChunkRange 测试别名（struct 是 internal，测试内可见）
private typealias ChunkRange = ParallelFileDownloader.ChunkRange
