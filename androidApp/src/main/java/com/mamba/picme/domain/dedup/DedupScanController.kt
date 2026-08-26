package com.mamba.picme.domain.dedup

import kotlinx.coroutines.flow.Flow

/**
 * 扫描控制抽象（Agent First：显式依赖注入接缝）。
 *
 * 生产实现为 [DedupScanner]（Context + Room 缓存，难在 JVM 单测中构造），
 * ViewModel 依赖本接口以便测试注入 fake。成员签名与 [DedupScanner] 一一对应。
 */
interface DedupScanController {

    /** 暂停请求标志：true 时扫描循环在下一检查点挂起等待。 */
    var pauseRequested: Boolean

    /** 清除暂停标志，让挂起中的扫描循环继续。 */
    fun resume()

    /** 流式扫描：返回 cold flow，调用方负责调度（flowOn）。 */
    fun scan(items: List<DedupScanner.ScanItem>, config: DedupScanConfig): Flow<DedupScanEvent>
}
