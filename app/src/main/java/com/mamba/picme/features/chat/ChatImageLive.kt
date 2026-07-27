package com.mamba.picme.features.chat

import java.io.File

/**
 * 判定 chat 内一张结果图当前是否可展示（未过期）。
 *
 * - content://（已保存到相册）：恒为存活，免疫 LRU；
 * - file:// 或裸路径（私有缓存）：取决于文件是否仍存在；
 * - null/空：不存活。
 *
 * 判定基于 [File.exists]（stat），不依赖 Coil 内存缓存，确定性强。
 */
fun chatImageIsLive(uri: String?): Boolean {
    if (uri.isNullOrBlank()) return false
    if (uri.startsWith("content://")) return true
    val path = uri.removePrefix("file://")
    return File(path).exists()
}
