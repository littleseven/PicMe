package com.mamba.picme.server.issue

/**
 * 用户上报问题文本脱敏（PRIVACY 红线守门）。
 *
 * 脱敏规则与 App 侧 [DiagSanitizer] 对齐：
 * - 邮箱 → `<email>`
 * - App Token (`pl-...`) → `<token>`
 * - content URI / 绝对路径 → `<path>`
 * - GPS 坐标 → `<coord>`
 *
 * 脱敏在服务端入库前执行，确保数据库、GitHub issue、后台展示均不暴露隐私。
 */
object IssueSanitizer {
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
