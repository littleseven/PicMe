package com.mamba.picme.server.admin

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.util.date.GMTDate

/**
 * 管理后台路由：/admin 下全部页面。主 app-token 拦截器（Application.module）对 /admin 前缀放行，
 * 由各受保护页面顶部的 adminGuard 接管认证（ADMIN_TOKEN 为空 → 503 禁用）。
 */
fun Route.adminRoute(adminToken: String) {
    route("/admin") {
        get("/login") {
            if (adminToken.isBlank()) {
                call.respondText("admin disabled", contentType = ContentType.Text.Plain, status = HttpStatusCode.ServiceUnavailable)
            } else {
                call.respondText(AdminViews.loginPage(), ContentType.Text.Html)
            }
        }

        post("/login") {
            val params = call.receiveParameters()
            val password = params["password"] ?: ""
            if (adminToken.isNotBlank() && password == adminToken) {
                call.response.cookies.append(
                    AdminAuth.COOKIE_NAME,
                    AdminAuth.expectedCookieValue(adminToken),
                    path = "/admin",
                    httpOnly = true,
                )
                call.respondRedirect("/admin")
            } else {
                call.respondText(
                    AdminViews.loginPage(failed = true),
                    ContentType.Text.Html,
                    HttpStatusCode.Unauthorized,
                )
            }
        }

        get("/logout") {
            call.response.cookies.append(
                AdminAuth.COOKIE_NAME,
                "",
                path = "/admin",
                expires = GMTDate(0),
            )
            call.respondRedirect("/admin/login")
        }

        get {
            if (!call.adminGuard(adminToken)) return@get
            val now = System.currentTimeMillis()
            val ov = AdminQueries.overview(now)
            val series = AdminQueries.dailySeries(14, now)
            call.respondText(AdminViews.overviewPage(ov, series), ContentType.Text.Html)
        }

        get("/users") {
            if (!call.adminGuard(adminToken)) return@get
            call.respondText(AdminViews.usersPage(AdminQueries.usersList()), ContentType.Text.Html)
        }

        get("/users/{id}") {
            if (!call.adminGuard(adminToken)) return@get
            val id = call.parameters["id"]?.toIntOrNull()
            if (id == null) {
                call.respondText("bad request", contentType = ContentType.Text.Plain, status = HttpStatusCode.BadRequest)
                return@get
            }
            val detail = AdminQueries.userDetail(id)
            if (detail == null) {
                call.respondText("not found", contentType = ContentType.Text.Plain, status = HttpStatusCode.NotFound)
                return@get
            }
            val calls = AdminQueries.recentCalls(id, 50)
            call.respondText(AdminViews.userDetailPage(detail, calls), ContentType.Text.Html)
        }

        get("/traffic") {
            if (!call.adminGuard(adminToken)) return@get
            val now = System.currentTimeMillis()
            call.respondText(AdminViews.trafficPage(AdminQueries.dailySeries(30, now)), ContentType.Text.Html)
        }
    }
}

/** 受保护页面统一鉴权：空 token → 503；cookie 无效 → 跳登录。返回 false 表示已响应、调用方应 return。 */
private suspend fun ApplicationCall.adminGuard(adminToken: String): Boolean {
    if (adminToken.isBlank()) {
        respondText("admin disabled", contentType = ContentType.Text.Plain, status = HttpStatusCode.ServiceUnavailable)
        return false
    }
    val cookie = request.cookies[AdminAuth.COOKIE_NAME]
    if (!AdminAuth.isValid(cookie, adminToken)) {
        respondRedirect("/admin/login")
        return false
    }
    return true
}
