package com.mamba.picme.core.diag

/**
 * 诊断包脱敏（ADR-008 红线守门）：把日志里的邮箱、App Token、文件/media 路径、
 * content uri、GPS 坐标替换为占位符。诊断包是纯文本，绝不含图片/视频字节。
 *
 * 注：人名是任意自由文本、无法可靠识别；日志一般不含人名（人名存于本地 DB），
 * 故 MVP 不做人名 redaction，留待二期按需处理。
 */
object DiagSanitizer {
    private val email = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val token = Regex("pl-[0-9a-fA-F]{16,}")
    private val contentUri = Regex("content://[\\w./-]+")
    private val absPath = Regex("/(?:storage|sdcard|data|mnt|var|tmp|Users|home)(?:/[^\\s\"]*)?")
    private val coord = Regex("-?\\d{1,3}\\.\\d{4,},\\s*-?\\d{1,3}\\.\\d{4,}")

    fun sanitize(text: String): String = text
        .replace(email, "<email>")
        .replace(token, "<token>")
        .replace(contentUri, "<path>")
        .replace(absPath, "<path>")
        .replace(coord, "<coord>")
}
