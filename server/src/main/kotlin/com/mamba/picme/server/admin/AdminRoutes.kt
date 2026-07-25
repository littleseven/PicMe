package com.mamba.picme.server.admin

import com.mamba.picme.server.auth.AccountService
import com.mamba.picme.server.auth.GuestService
import com.mamba.picme.server.config.SettingsService
import com.mamba.picme.server.cos.CosService
import com.mamba.picme.server.db.ApkUploads
import com.mamba.picme.server.db.Db
import com.mamba.picme.server.llm.ChannelBalanceService
import com.mamba.picme.server.llm.ChannelInput
import com.mamba.picme.server.llm.ChannelRegistry
import com.mamba.picme.server.llm.ChannelRepository
import com.mamba.picme.server.llm.parseModelMapLines
import io.ktor.http.ContentType
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.readAvailable
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * 管理后台路由：/admin 下全部页面。主 app-token 拦截器（Application.module）对 /admin 前缀放行，
 * 由各受保护页面顶部的 adminGuard 接管认证（ADMIN_TOKEN 为空 → 503 禁用）。
 */
fun Route.adminRoute(adminToken: String, cosService: CosService, balanceService: ChannelBalanceService) {
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
                    Cookie(
                        name = AdminAuth.COOKIE_NAME,
                        value = AdminAuth.expectedCookieValue(adminToken),
                        path = "/admin",
                        httpOnly = true,
                        secure = call.isHttps(),
                        // Ktor 3.0.3 的 Cookie 无 sameSite 字段（3.1.0 才加），用 extensions 追加原始属性
                        extensions = mapOf("SameSite" to "Lax"),
                    ),
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
                Cookie(
                    name = AdminAuth.COOKIE_NAME,
                    value = "",
                    path = "/admin",
                    expires = GMTDate(0),
                    httpOnly = true,
                    secure = call.isHttps(),
                    extensions = mapOf("SameSite" to "Lax"),
                ),
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
            val rows = AdminQueries.usersList()
            call.respondText(
                AdminViews.usersPage(rows, AdminQueries.devicesCount()),
                ContentType.Text.Html,
            )
        }

        get("/devices") {
            if (!call.adminGuard(adminToken)) return@get
            val rows = AdminQueries.devicesList()
            call.respondText(
                AdminViews.devicesPage(rows, AdminQueries.usersCount(), SettingsService.snapshot().guestLlmQuota),
                ContentType.Text.Html,
            )
        }

        // 供设备列表「复制」按钮调用:返回完整 device_id(cookie 鉴权;不进 HTML)。
        get("/devices/{id}/raw") {
            if (!call.adminGuard(adminToken)) return@get
            val id = call.parameters["id"]?.toIntOrNull()
            if (id == null) {
                call.respondText("bad request", contentType = ContentType.Text.Plain, status = HttpStatusCode.BadRequest)
                return@get
            }
            val deviceId = AdminQueries.deviceRawId(id)
            if (deviceId == null) {
                call.respondText("not found", contentType = ContentType.Text.Plain, status = HttpStatusCode.NotFound)
                return@get
            }
            val body = buildJsonObject { put("device_id", deviceId) }.toString()
            call.respondText(body, ContentType.Application.Json)
        }

        post("/devices/{id}/delete") {
            if (!call.adminGuard(adminToken)) return@post
            val id = call.parameters["id"]?.toIntOrNull()
            if (id != null) GuestService.deleteById(id)
            call.respondRedirect("/admin/devices")
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

        // 供用户列表「复制」按钮调用：返回完整 token（cookie 鉴权；不进列表 HTML）。
        get("/users/{id}/token") {
            if (!call.adminGuard(adminToken)) return@get
            val id = call.parameters["id"]?.toIntOrNull()
            val token = if (id != null) AccountService.rawToken(id) else null
            if (token == null) {
                call.respondText("not found", contentType = ContentType.Text.Plain, status = HttpStatusCode.NotFound)
                return@get
            }
            val body = buildJsonObject { put("token", token) }.toString()
            call.respondText(body, ContentType.Application.Json)
        }

        post("/users/{id}/revoke") {
            if (!call.adminGuard(adminToken)) return@post
            val id = call.parameters["id"]?.toIntOrNull()
            if (id != null) AccountService.setStatus(id, "revoked")
            call.respondRedirect("/admin/users")
        }

        post("/users/{id}/unrevoke") {
            if (!call.adminGuard(adminToken)) return@post
            val id = call.parameters["id"]?.toIntOrNull()
            if (id != null) AccountService.setStatus(id, "active")
            call.respondRedirect("/admin/users")
        }

        // 管理后台「删除账户及数据」：立即物理删除账号 + 调用日志（隐私合规「立即删除」）。
        post("/users/{id}/delete") {
            if (!call.adminGuard(adminToken)) return@post
            val id = call.parameters["id"]?.toIntOrNull()
            if (id != null) AccountService.purgeAccount(id)
            call.respondRedirect("/admin/users")
        }

        get("/traffic") {
            if (!call.adminGuard(adminToken)) return@get
            val now = System.currentTimeMillis()
            call.respondText(AdminViews.trafficPage(AdminQueries.dailySeries(30, now)), ContentType.Text.Html)
        }

        get("/channels") {
            if (!call.adminGuard(adminToken)) return@get
            val channels = ChannelRepository.list()
            call.respondText(AdminViews.channelsPage(channels), ContentType.Text.Html)
        }

        get("/channels/new") {
            if (!call.adminGuard(adminToken)) return@get
            call.respondText(AdminViews.channelFormPage(), ContentType.Text.Html)
        }

        get("/channels/{id}/edit") {
            if (!call.adminGuard(adminToken)) return@get
            val id = call.parameters["id"]?.toIntOrNull()
            val row = if (id != null) ChannelRepository.get(id) else null
            if (id == null || row == null) {
                call.respondText("not found", contentType = ContentType.Text.Plain, status = HttpStatusCode.NotFound)
                return@get
            }
            call.respondText(AdminViews.channelFormPage(row), ContentType.Text.Html)
        }

        // 供列表「复制」按钮调用：返回完整 token（cookie 鉴权；不进列表 HTML）。
        get("/channels/{id}/token") {
            if (!call.adminGuard(adminToken)) return@get
            val id = call.parameters["id"]?.toIntOrNull()
            val token = if (id != null) ChannelRepository.rawToken(id) else null
            if (token == null) {
                call.respondText("not found", contentType = ContentType.Text.Plain, status = HttpStatusCode.NotFound)
                return@get
            }
            val body = buildJsonObject { put("token", token) }.toString()
            call.respondText(body, ContentType.Application.Json)
        }

        post("/channels") {
            if (!call.adminGuard(adminToken)) return@post
            val input = call.parseChannelInput()
            if (input == null) {
                call.respondText(
                    AdminViews.channelsPage(ChannelRepository.list(), error = "表单参数错误：检查 model_map 格式（每行 请求名=上游名）"),
                    ContentType.Text.Html,
                    HttpStatusCode.BadRequest,
                )
                return@post
            }
            try {
                ChannelRepository.create(input)
            } catch (e: Exception) {
                call.respondText(
                    AdminViews.channelsPage(ChannelRepository.list(), error = "创建失败：名称可能重复"),
                    ContentType.Text.Html,
                    HttpStatusCode.BadRequest,
                )
                return@post
            }
            ChannelRegistry.reload()
            call.respondRedirect("/admin/channels")
        }

        post("/channels/{id}") {
            if (!call.adminGuard(adminToken)) return@post
            val id = call.parameters["id"]?.toIntOrNull()
            val input = call.parseChannelInput()
            if (id != null && input != null) {
                try {
                    ChannelRepository.update(id, input)
                } catch (e: Exception) {
                    // 唯一约束冲突等：忽略，回列表
                }
                ChannelRegistry.reload()
            }
            call.respondRedirect("/admin/channels")
        }

        post("/channels/{id}/activate") {
            if (!call.adminGuard(adminToken)) return@post
            val id = call.parameters["id"]?.toIntOrNull()
            if (id != null) {
                ChannelRepository.setActive(id)
                ChannelRegistry.reload()
            }
            call.respondRedirect("/admin/channels")
        }

        post("/channels/{id}/toggle") {
            if (!call.adminGuard(adminToken)) return@post
            val id = call.parameters["id"]?.toIntOrNull()
            if (id != null) {
                val current = ChannelRepository.get(id)
                if (current != null) ChannelRepository.setEnabled(id, !current.enabled)
                ChannelRegistry.reload()
            }
            call.respondRedirect("/admin/channels")
        }

        post("/channels/{id}/delete") {
            if (!call.adminGuard(adminToken)) return@post
            val id = call.parameters["id"]?.toIntOrNull()
            if (id != null) {
                ChannelRepository.delete(id)
                ChannelRegistry.reload()
            }
            call.respondRedirect("/admin/channels")
        }

        get("/apk") {
            if (!call.adminGuard(adminToken)) return@get
            val apkInfo = cosService.getApkInfo()
            val msg = call.request.queryParameters["msg"]
            val history = AdminQueries.apkUploadHistory(30)
            call.respondText(
                AdminViews.apkPage(
                    fileExists = apkInfo.exists,
                    fileSize = apkInfo.size,
                    lastModified = apkInfo.lastModified,
                    version = apkInfo.version,
                    cosUrl = apkInfo.publicUrl,
                    cosConfigured = cosService.configured,
                    message = msg,
                    history = history,
                ),
                ContentType.Text.Html,
            )
        }

        post("/apk/upload") {
            if (!call.adminGuard(adminToken)) return@post
            // APK 通常 50-150MB，放宽 multipart 限制到 200MB
            val multipart = call.receiveMultipart(200L * 1024 * 1024)
            var uploaded = false
            var errorMsg: String? = null
            var version = ""
            var fileName = ""
            var fileSize = 0L
            val tmpFile = java.io.File.createTempFile("apk-upload-", ".apk")
            try {
                multipart.forEachPart { part ->
                    when {
                        part is PartData.FormItem && part.name == "version" -> {
                            version = part.value.trim()
                        }
                        part is PartData.FileItem && part.name == "apkfile" -> {
                            fileName = part.originalFileName ?: ""
                            if (!fileName.endsWith(".apk", ignoreCase = true)) {
                                errorMsg = "文件格式错误：请上传 .apk 文件"
                            } else {
                                try {
                                    val channel = part.provider()
                                    tmpFile.outputStream().use { output ->
                                        val buffer = ByteArray(8192)
                                        while (true) {
                                            val read = channel.readAvailable(buffer)
                                            if (read <= 0) break
                                            output.write(buffer, 0, read)
                                        }
                                    }
                                    fileSize = tmpFile.length()
                                    uploaded = cosService.uploadApk(tmpFile.inputStream(), fileSize, version)
                                    if (!uploaded && errorMsg == null) {
                                        errorMsg = "COS 上传失败：检查 COS 配置或凭证"
                                    }
                                } catch (e: Exception) {
                                    errorMsg = "上传失败：${e.message}"
                                }
                            }
                        }
                    }
                    part.dispose()
                }
            } finally {
                tmpFile.delete()
            }
            val msg = when {
                uploaded -> "成功上传 v$version 到 COS"
                errorMsg != null -> errorMsg
                else -> "未收到文件"
            }
            // 写入上传历史记录
            transaction(Db.instance) {
                ApkUploads.insert {
                    it[ApkUploads.version] = version
                    it[ApkUploads.fileName] = fileName
                    it[ApkUploads.fileSize] = fileSize
                    it[ApkUploads.status] = if (uploaded) "success" else "failed"
                    it[ApkUploads.message] = if (uploaded) null else errorMsg
                    it[ApkUploads.createdAt] = System.currentTimeMillis()
                }
            }
            call.respondRedirect("/admin/apk?msg=${java.net.URLEncoder.encode(msg, "UTF-8")}")
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

/** 是否走 HTTPS：nginx 终止 TLS 时按 X-Forwarded-Proto 判断；本地 http dev 返回 false（cookie 不加 Secure）。 */
private fun ApplicationCall.isHttps(): Boolean =
    request.headers["X-Forwarded-Proto"]?.equals("https", ignoreCase = true) == true

/** 解析渠道表单为 ChannelInput；model_map 解析失败或校验不过返回 null。 */
private suspend fun ApplicationCall.parseChannelInput(): ChannelInput? {
    val params = receiveParameters()
    val modelMap = try {
        parseModelMapLines(params["model_map"] ?: "")
    } catch (e: IllegalArgumentException) {
        return null
    }
    val name = (params["name"] ?: "").trim()
    val baseUrl = (params["base_url"] ?: "").trim()
    if (name.isEmpty() || baseUrl.isEmpty()) return null
    if (name.length > 32) return null
    return ChannelInput(
        name = name,
        kind = (params["kind"] ?: "direct").trim(),
        baseUrl = baseUrl,
        authStyle = (params["auth_style"] ?: "bearer").trim(),
        apiToken = (params["api_token"] ?: "").trim(),
        modelMap = modelMap,
        enabled = (params["enabled"] ?: "0") == "1",
        defaultModel = (params["default_model"] ?: "").trim(),
    )
}
