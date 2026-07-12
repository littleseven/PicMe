package com.mamba.picme.server.admin

import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.HTML
import kotlinx.html.InputType
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.head
import kotlinx.html.html
import kotlinx.html.input
import kotlinx.html.meta
import kotlinx.html.p
import kotlinx.html.style
import kotlinx.html.table
import kotlinx.html.td
import kotlinx.html.textInput
import kotlinx.html.th
import kotlinx.html.title
import kotlinx.html.tr
import kotlinx.html.unsafe
import kotlinx.html.stream.createHTML

/**
 * 服务端渲染 HTML 页面（kotlinx.html）。无前端构建、无 CDN，内联 SVG 图表。
 * 每个 page 函数返回完整 HTML 字符串，由 AdminRoutes 用 respondText 输出。
 */
object AdminViews {

    fun loginPage(failed: Boolean = false): String = createHTML().html {
        adminHead("登录 · PicMe 管理后台")
        body {
            div("wrap") {
                h1 { +"PicMe 管理后台" }
                if (failed) p("err") { +"密码错误" }
                form(action = "/admin/login", method = FormMethod.post) {
                    p { textInput(name = "password", classes = "pw") { placeholder = "ADMIN_TOKEN" } }
                    p { input(type = InputType.submit, classes = "btn") { value = "登录" } }
                }
            }
        }
    }

    fun overviewPage(ov: OverviewRow, series: List<DayBucket>): String = createHTML().html {
        adminHead("概览 · PicMe 管理后台")
        body {
            navBar()
            h1 { +"概览（今日，UTC 自然日）" }
            div("cards") {
                statCard("总用户数", ov.totalUsers.toString())
                statCard("今日新增", ov.newUsersToday.toString())
                statCard("今日调用", ov.callsToday.toString())
                statCard("今日 Token", ov.tokensToday.toString())
                statCard("今日成本 ¥", fmt(ov.costToday))
                statCard("今日出口字节", ov.bytesToday.toString())
                statCard("今日 blocked", ov.blockedToday.toString())
            }
            h2 { +"近 ${series.size} 天 调用数 / 成本 ¥" }
            unsafe { raw(svgBars(series.map { it.calls.toDouble() }, series.map { it.day })) }
            unsafe { raw(svgBars(series.map { it.cost }, series.map { it.day })) }
        }
    }

    fun usersPage(rows: List<UserRow>): String = createHTML().html {
        adminHead("用户 · PicMe 管理后台")
        body {
            navBar()
            h1 { +"用户（${rows.size}）" }
            table {
                tr {
                    th { +"ID" }
                    th { +"邮箱" }
                    th { +"状态" }
                    th { +"注册时间" }
                    th { +"调用" }
                    th { +"Token" }
                    th { +"成本 ¥" }
                    th { +"最后活跃" }
                }
                rows.forEach { u ->
                    tr {
                        td { +u.id.toString() }
                        td { a("/admin/users/${u.id}") { +u.email } }
                        td { +u.status }
                        td { +fmtTs(u.createdAt) }
                        td { +u.calls.toString() }
                        td { +u.totalTokens.toString() }
                        td { +fmt(u.cost) }
                        td { +(u.lastActive?.let { fmtTs(it) } ?: "—") }
                    }
                }
            }
        }
    }

    fun userDetailPage(d: UserDetail, calls: List<CallRow>): String = createHTML().html {
        adminHead("用户详情 · PicMe 管理后台")
        body {
            navBar()
            h1 { +"${d.email}（#${d.id}）" }
            div("cards") {
                statCard("状态", d.status)
                statCard("注册时间", fmtTs(d.createdAt))
                statCard("成功调用", d.calls.toString())
                statCard("Token", d.totalTokens.toString())
                statCard("成本 ¥", fmt(d.cost))
                statCard("blocked", d.blocked.toString())
                statCard("出口字节", d.bytes.toString())
            }
            h2 { +"最近调用（${calls.size}）" }
            table {
                tr {
                    th { +"时间" }
                    th { +"模型" }
                    th { +"Provider" }
                    th { +"Prompt" }
                    th { +"Completion" }
                    th { +"Total" }
                    th { +"成本 ¥" }
                    th { +"字节" }
                    th { +"状态" }
                    th { +"ms" }
                }
                calls.forEach { c ->
                    tr {
                        td { +fmtTs(c.createdAt) }
                        td { +c.model }
                        td { +c.provider }
                        td { +(c.promptTokens?.toString() ?: "—") }
                        td { +(c.completionTokens?.toString() ?: "—") }
                        td { +(c.totalTokens?.toString() ?: "—") }
                        td { +fmt(c.costCny) }
                        td { +c.respBytes.toString() }
                        td { +c.status }
                        td { +(c.latencyMs?.toString() ?: "—") }
                    }
                }
            }
        }
    }

    fun trafficPage(series: List<DayBucket>): String = createHTML().html {
        adminHead("流量 · PicMe 管理后台")
        body {
            navBar()
            h1 { +"流量（近 ${series.size} 天，UTC）" }
            h2 { +"每日 Token 总量" }
            unsafe { raw(svgBars(series.map { it.totalTokens.toDouble() }, series.map { it.day })) }
            h2 { +"每日明细" }
            table {
                tr {
                    th { +"日期" }
                    th { +"调用" }
                    th { +"blocked" }
                    th { +"Prompt" }
                    th { +"Completion" }
                    th { +"Total Token" }
                    th { +"成本 ¥" }
                    th { +"出口字节" }
                }
                series.reversed().forEach { b ->
                    tr {
                        td { +b.day }
                        td { +b.calls.toString() }
                        td { +b.blocked.toString() }
                        td { +b.promptTokens.toString() }
                        td { +b.completionTokens.toString() }
                        td { +b.totalTokens.toString() }
                        td { +fmt(b.cost) }
                        td { +b.bytes.toString() }
                    }
                }
            }
        }
    }

    // ── 公共片段 ──

    private fun HTML.adminHead(title: String) {
        head {
            meta(charset = "utf-8")
            title { +title }
            style {
                unsafe {
                    raw(
                        """
                        body{font-family:-apple-system,system-ui,sans-serif;margin:0;background:#f5f6f8;color:#111}
                        .wrap{max-width:420px;margin:60px auto;padding:24px}
                        .cards{display:flex;flex-wrap:wrap;gap:12px;padding:16px}
                        .card{background:#fff;border:1px solid #e3e6eb;border-radius:10px;padding:14px 16px;min-width:130px}
                        .card-label{font-size:12px;color:#666}
                        .card-value{font-size:22px;font-weight:600;margin-top:4px}
                        nav{background:#111;color:#fff;padding:10px 16px;display:flex;gap:16px}
                        nav a{color:#fff;text-decoration:none}
                        table{border-collapse:collapse;width:100%;background:#fff;font-size:13px}
                        th,td{border:1px solid #e3e6eb;padding:6px 8px;text-align:left}
                        th{background:#eef1f5}
                        .err{color:#c00}
                        .pw{padding:8px;width:100%}
                        .btn{padding:8px 16px}
                        .chart{max-width:100%;height:auto}
                        """.trimIndent(),
                    )
                }
            }
        }
    }

    private fun FlowContent.navBar() {
        div("nav") {
            a("/admin") { +"概览" }
            a("/admin/users") { +"用户" }
            a("/admin/traffic") { +"流量" }
            a("/admin/logout") { +"退出" }
        }
    }

    private fun FlowContent.statCard(label: String, value: String) {
        div("card") {
            div("card-label") { +label }
            div("card-value") { +value }
        }
    }

    /** 简易 SVG 柱状图：values 与 labels 等长。 */
    private fun svgBars(values: List<Double>, labels: List<String>): String {
        if (values.isEmpty()) return "<p>无数据</p>"
        val maxV = values.max().coerceAtLeast(1.0)
        val w = 720
        val h = 140
        val barW = (w / values.size).coerceAtLeast(2)
        val sb = StringBuilder()
        sb.append("""<svg class="chart" viewBox="0 0 $w $h" xmlns="http://www.w3.org/2000/svg">""")
        values.forEachIndexed { i, v ->
            val barH = (v / maxV * (h - 24)).toInt().coerceAtLeast(1)
            val x = i * barW
            val y = h - barH - 16
            sb.append("""<rect x="$x" y="$y" width="${barW - 2}" height="$barH" rx="2" fill="#3b82f6"/>""")
            sb.append("""<text x="${x + barW / 2}" y="${h - 4}" font-size="9" text-anchor="middle">${labels[i].takeLast(5)}</text>""")
        }
        sb.append("</svg>")
        return sb.toString()
    }

    private fun fmt(d: Double): String = "%.2f".format(d)

    private fun fmtTs(ms: Long): String =
        java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneOffset.UTC).toString().take(19).replace("T", " ")
}
