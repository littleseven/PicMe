package com.mamba.picme.server.routes

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.mamba.picme.server.cos.CosService
import com.mamba.picme.server.db.Db
import com.mamba.picme.server.db.IosUdidRegistrations
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.html.FormMethod
import kotlinx.html.InputType
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.head
import kotlinx.html.html
import kotlinx.html.img
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.meta
import kotlinx.html.p
import kotlinx.html.stream.createHTML
import kotlinx.html.style
import kotlinx.html.title
import kotlinx.html.unsafe
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.EnumMap

private const val APP_VERSION = "1.0.10"

/** 深色主题内联 CSS，Android/iOS 下载页共用。 */
private const val DARK_THEME_CSS = """
    *{box-sizing:border-box;margin:0;padding:0}
    body{font-family:-apple-system,system-ui,"Segoe UI",Roboto,sans-serif;background:#0f172a;color:#e2e8f0;min-height:100vh;display:flex;align-items:center;justify-content:center}
    .container{text-align:center;padding:32px 24px;max-width:420px}
    .logo{font-size:28px;font-weight:700;margin-bottom:8px;background:linear-gradient(135deg,#38bdf8,#818cf8);-webkit-background-clip:text;-webkit-text-fill-color:transparent}
    .tagline{font-size:14px;color:#94a3b8;margin-bottom:32px}
    .qr-wrap{background:#fff;border-radius:16px;padding:16px;margin:0 auto 24px;width:288px;display:flex;align-items:center;justify-content:center}
    .qr-wrap img{width:256px;height:256px;display:block}
    .download-btn{display:inline-block;background:linear-gradient(135deg,#3b82f6,#6366f1);color:#fff;text-decoration:none;padding:14px 36px;border-radius:12px;font-size:16px;font-weight:600;transition:opacity .2s}
    .download-btn:hover{opacity:.9}
    .info{margin-top:20px;font-size:13px;color:#64748b}
    .scan-tip{font-size:13px;color:#94a3b8;margin-bottom:16px}
    .contact{margin-top:32px;padding-top:20px;border-top:1px solid #1e293b}
    .contact-title{font-size:14px;color:#94a3b8;margin-bottom:14px}
    .contact-grid{display:flex;gap:28px;justify-content:center;align-items:flex-start;flex-wrap:wrap}
    .contact-item{display:flex;flex-direction:column;align-items:center;gap:8px}
    .contact-qr{background:#fff;border-radius:12px;padding:8px;width:124px;height:124px;display:flex;align-items:center;justify-content:center}
    .contact-qr img{width:108px;height:108px;display:block}
    .contact-label{font-size:12px;color:#94a3b8}
    .udid-section{text-align:left;margin-top:28px;background:#1e293b;border-radius:12px;padding:20px}
    .udid-section-title{font-size:14px;color:#e2e8f0;font-weight:600;margin-bottom:8px}
    .udid-section-desc{font-size:13px;color:#94a3b8;margin-bottom:14px;line-height:1.6}
    .udid-form{display:flex;flex-direction:column;gap:10px}
    .udid-form label{font-size:12px;color:#94a3b8}
    .udid-form input[type=text]{padding:10px 12px;border:1px solid #334155;border-radius:8px;font-size:14px;background:#0f172a;color:#e2e8f0}
    .udid-form input[type=text]:focus{outline:none;border-color:#3b82f6}
    .udid-submit{padding:10px 20px;background:linear-gradient(135deg,#3b82f6,#6366f1);color:#fff;border:none;border-radius:8px;font-size:14px;font-weight:600;cursor:pointer}
    .udid-submit:hover{opacity:.9}
    .udid-tutorial{margin-top:20px;padding:16px;background:#0f172a;border-radius:8px;border:1px solid #1e293b}
    .udid-tutorial-title{font-size:12px;color:#64748b;font-weight:600;margin-bottom:8px}
    .udid-tutorial p{font-size:12px;color:#94a3b8;line-height:1.7}
    .udid-trouble{margin-top:16px;padding:12px 16px;background:#1a1a2e;border-radius:8px;font-size:12px;color:#64748b;line-height:1.6}
    .back-link{display:inline-block;margin-top:20px;color:#3b82f6;text-decoration:none;font-size:13px}
    .back-link:hover{text-decoration:underline}
"""

/** X（推特）官方 logo SVG，黑标，用于联系作者区块。 */
private const val X_LOGO_SVG =
    """<svg xmlns="http://www.w3.org/2000/svg" width="76" height="76" viewBox="0 0 24 24"><path fill="#000" d="M18.244 2.25h3.308l-7.227 8.26 8.502 11.24H16.17l-5.214-6.817L4.99 21.75H1.68l7.73-8.835L1.254 2.25H8.08l4.713 6.231zm-1.161 17.52h1.833L7.084 4.126H5.117z"/></svg>"""

fun Routing.downloadRoute(cosService: CosService) {
    get("/download") {
        val ua = call.request.headers["User-Agent"] ?: ""
        if (ua.contains("iPhone") || ua.contains("iPad") || ua.contains("iPod")) {
            call.respondRedirect("/download/ios")
            return@get
        }
        val apkInfo = cosService.getApkInfo()
        val html = createDownloadPage(
            downloadUrl = apkInfo.publicUrl,
            version = apkInfo.version.ifBlank { APP_VERSION },
            size = apkInfo.size.ifBlank { "—" },
            available = apkInfo.exists,
        )
        call.respondText(html, ContentType.Text.Html)
    }
}

private fun createDownloadPage(downloadUrl: String, version: String, size: String, available: Boolean): String =
    createHTML().html {
        head {
            meta(charset = "utf-8")
            meta(name = "viewport", content = "width=device-width, initial-scale=1")
            title { +"破浪相册 · 下载" }
            style {
                unsafe {
                    raw(DARK_THEME_CSS.trimIndent())
                }
            }
        }
        body {
            div("container") {
                div("logo") { +"PoLang 破浪相册" }
                div("tagline") { +"AI 驱动的智能相册助手" }
                if (!available) {
                    div("scan-tip") { +"暂无可用版本，请稍后再试" }
                } else {
                    div("scan-tip") { +"扫码下载 Android APK" }
                    div("qr-wrap") {
                        img(src = "data:image/svg+xml;base64,${generateQrCodeSvgBase64(downloadUrl, 256)}", alt = "QR Code")
                    }
                    p {
                        a(downloadUrl, classes = "download-btn") { +"下载 APK" }
                    }
                    div("info") {
                        +"版本 $version · $size · Android 10+"
                    }
                }
                div("contact") {
                    div("contact-title") { +"联系作者" }
                    div("contact-grid") {
                        div("contact-item") {
                            div("contact-qr") {
                                img(src = "data:image/jpeg;base64,${wechatQrBase64()}", alt = "微信二维码")
                            }
                            div("contact-label") { +"微信扫码" }
                        }
                        div("contact-item") {
                            a("https://x.com/shuaiguo007", target = "_blank", classes = "contact-qr") {
                                unsafe { raw(X_LOGO_SVG) }
                            }
                            div("contact-label") { +"@shuaiguo007" }
                        }
                    }
                }
            }
        }
    }

// ── iOS Ad-Hoc 自测分发（公开，无鉴权）──

/** manifest.plist 的公开 URL，itms-services 链接需要 URL-encode 这个地址。 */
private const val MANIFEST_URL = "https://api.polang.net/download/ios/manifest.plist"

/** IPA 的 COS https 直链，写入 manifest.plist。 */
private const val IPA_DIRECT_URL = "https://cos.polang.net/ios/polang.ipa"

/** bundle-identifier 固定值。 */
private const val IOS_BUNDLE_ID = "com.mamba.picme"

/** itms-services URL（安装按钮 / 二维码编码内容）。 */
private val itmsServicesUrl: String =
    "itms-services://?action=download-manifest&url=" + java.net.URLEncoder.encode(MANIFEST_URL, "UTF-8")

fun Routing.iosDownloadRoute(cosService: CosService) {
    get("/download/ios") {
        val ipaInfo = cosService.getIpaInfo()
        val html = createIosDownloadPage(
            available = ipaInfo.exists,
            version = ipaInfo.version,
            size = ipaInfo.size,
        )
        call.respondText(html, ContentType.Text.Html)
    }

    get("/download/ios/manifest.plist") {
        val ipaInfo = cosService.getIpaInfo()
        if (!ipaInfo.exists) {
            call.respondText("", ContentType.Text.Plain, HttpStatusCode.NotFound)
            return@get
        }
        val plist = generateManifestPlist(
            ipaUrl = IPA_DIRECT_URL,
            version = ipaInfo.version.ifBlank { "1.0" },
        )
        call.respondText(plist, ContentType.Application.Xml)
    }

    post("/download/ios/udid") {
        val params = call.receiveParameters()
        val rawUdid = (params["udid"] ?: "").trim()
        val nickname = (params["nickname"] ?: "").trim().take(128)
        if (!isValidUdid(rawUdid)) {
            val html = createUdidErrorPage()
            call.respondText(html, ContentType.Text.Html, HttpStatusCode.BadRequest)
            return@post
        }
        val cleanedUdid = rawUdid.replace("-", "").lowercase()
        transaction(Db.instance) {
            IosUdidRegistrations.insert {
                it[IosUdidRegistrations.udid] = cleanedUdid
                it[IosUdidRegistrations.nickname] = nickname.ifBlank { null }
                it[IosUdidRegistrations.createdAt] = System.currentTimeMillis()
                it[IosUdidRegistrations.status] = "pending"
            }
        }
        val html = createUdidSuccessPage()
        call.respondText(html, ContentType.Text.Html)
    }
}

private fun createIosDownloadPage(available: Boolean, version: String, size: String): String =
    createHTML().html {
        head {
            meta(charset = "utf-8")
            meta(name = "viewport", content = "width=device-width, initial-scale=1")
            title { +"破浪相册 · iOS 自测版" }
            style {
                unsafe {
                    raw(DARK_THEME_CSS.trimIndent())
                }
            }
        }
        body {
            div("container") {
                div("logo") { +"PoLang 破浪相册" }
                div("tagline") { +"AI 驱动的智能相册助手" }
                if (!available) {
                    div("scan-tip") { +"暂无可用 iOS 版本，请稍后再试" }
                } else {
                    div("scan-tip") { +"扫码安装 iOS 自测版" }
                    div("qr-wrap") {
                        img(
                            src = "data:image/svg+xml;base64,${generateQrCodeSvgBase64(itmsServicesUrl, 256)}",
                            alt = "QR Code",
                        )
                    }
                    p {
                        a(href = itmsServicesUrl, classes = "download-btn") { +"安装" }
                    }
                    div("info") {
                        +"版本 ${version.ifBlank { "—" } } · ${size.ifBlank { "—" } } · iOS 自测版（Ad-Hoc）"
                    }
                }

                // UDID 登记区
                div("udid-section") {
                    div("udid-section-title") { +"登记 UDID" }
                    div("udid-section-desc") {
                        +"登记 UDID 后，开发者会加入测试描述文件并重新签名，届时刷新本页即可安装。"
                    }
                    form(action = "/download/ios/udid", method = FormMethod.post, classes = "udid-form") {
                        label { +"UDID（必填）" }
                        input(type = InputType.text, name = "udid") {
                            placeholder = "25 或 40 位十六进制字符"
                            required = true
                        }
                        label { +"备注（可选）" }
                        input(type = InputType.text, name = "nickname") {
                            placeholder = "你的名字/昵称"
                        }
                        input(type = InputType.submit, classes = "udid-submit") { value = "提交登记" }
                    }
                }

                // 获取 UDID 教程
                div("udid-tutorial") {
                    div("udid-tutorial-title") { +"如何获取 UDID？" }
                    p {
                        +"方式一（推荐）：用电脑 Finder/iTunes 连接 iPhone，点设备后点「序列号」一栏会切换显示 UDID，右键复制。"
                        +"方式二：访问 UDID 查询网页（如 get.udid.io）按引导获取。"
                        +"把获取到的 UDID 粘贴到上方表单即可。"
                    }
                }

                // 排错提示
                div("udid-trouble") {
                    +"点「安装」后提示「无法连接/未验证/无法安装」？说明你的设备 UDID 还没加入测试描述文件，请先在上方登记，开发者重新签名后即可。"
                }

                div("contact") {
                    div("contact-title") { +"联系作者" }
                    div("contact-grid") {
                        div("contact-item") {
                            div("contact-qr") {
                                img(src = "data:image/jpeg;base64,${wechatQrBase64()}", alt = "微信二维码")
                            }
                            div("contact-label") { +"微信扫码" }
                        }
                        div("contact-item") {
                            a("https://x.com/shuaiguo007", target = "_blank", classes = "contact-qr") {
                                unsafe { raw(X_LOGO_SVG) }
                            }
                            div("contact-label") { +"@shuaiguo007" }
                        }
                    }
                }
            }
        }
    }

private fun createUdidSuccessPage(): String =
    createHTML().html {
        head {
            meta(charset = "utf-8")
            meta(name = "viewport", content = "width=device-width, initial-scale=1")
            title { +"UDID 登记成功 · 破浪相册" }
            style {
                unsafe {
                    raw(DARK_THEME_CSS.trimIndent())
                }
            }
        }
        body {
            div("container") {
                div("logo") { +"登记成功" }
                div("tagline") { +"感谢你的配合" }
                div("scan-tip") {
                    +"UDID 已记录，开发者会尽快加入测试描述文件并重新签名。"
                    +"签名完成后回到本页扫码安装即可。"
                }
                p {
                    a(href = "/download/ios", classes = "download-btn") { +"返回 iOS 下载页" }
                }
                div("contact") {
                    div("contact-title") { +"联系作者" }
                    div("contact-grid") {
                        div("contact-item") {
                            div("contact-qr") {
                                img(src = "data:image/jpeg;base64,${wechatQrBase64()}", alt = "微信二维码")
                            }
                            div("contact-label") { +"微信扫码" }
                        }
                        div("contact-item") {
                            a("https://x.com/shuaiguo007", target = "_blank", classes = "contact-qr") {
                                unsafe { raw(X_LOGO_SVG) }
                            }
                            div("contact-label") { +"@shuaiguo007" }
                        }
                    }
                }
            }
        }
    }

private fun createUdidErrorPage(): String =
    createHTML().html {
        head {
            meta(charset = "utf-8")
            meta(name = "viewport", content = "width=device-width, initial-scale=1")
            title { +"登记失败 · 破浪相册" }
            style {
                unsafe {
                    raw(DARK_THEME_CSS.trimIndent())
                }
            }
        }
        body {
            div("container") {
                div("logo") { +"UDID 格式不正确" }
                div("tagline") { +"请检查后重新提交" }
                div("scan-tip") {
                    +"UDID 应为 25 或 40 位十六进制字符（0-9, a-f）。"
                    +"请确认复制完整，或尝试用其他方式获取。"
                }
                p {
                    a(href = "/download/ios", classes = "download-btn") { +"返回重试" }
                }
            }
        }
    }

/** OTA manifest plist：IPA 直链 + bundle 信息，供 itms-services 协议消费。 */
private fun generateManifestPlist(ipaUrl: String, version: String): String =
    """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict><key>items</key><array><dict><key>assets</key><array><dict><key>kind</key><string>software-package</string><key>url</key><string>$ipaUrl</string></dict></array><key>metadata</key><dict><key>bundle-identifier</key><string>$IOS_BUNDLE_ID</string><key>bundle-version</key><string>$version</string><key>kind</key><string>software</string><key>title</key><string>PoLang</string></dict></dict></array></dict></plist>"""

/** UDID 基本校验：去掉连字符后 25 或 40 位十六进制。 */
private fun isValidUdid(raw: String): Boolean {
    val cleaned = raw.replace("-", "").lowercase()
    return cleaned.length in setOf(25, 40) && cleaned.all { ch -> ch in '0'..'9' || ch in 'a'..'f' }
}

private fun generateQrCodeSvgBase64(text: String, size: Int): String {
    val hints = EnumMap<com.google.zxing.EncodeHintType, Any>(com.google.zxing.EncodeHintType::class.java)
    hints[com.google.zxing.EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.M
    hints[com.google.zxing.EncodeHintType.MARGIN] = 1

    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
    val width = matrix.width
    val height = matrix.height
    val moduleSize = size.toFloat() / width

    val svgBuilder = StringBuilder()
    svgBuilder.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"$size\" height=\"$size\" viewBox=\"0 0 $size $size\">")
    svgBuilder.append("<rect width=\"$size\" height=\"$size\" fill=\"#ffffff\"/>")
    for (y in 0 until height) {
        for (x in 0 until width) {
            if (matrix.get(x, y)) {
                val px = (x * moduleSize).toInt()
                val py = (y * moduleSize).toInt()
                val pw = ((x + 1) * moduleSize).toInt() - px
                val ph = ((y + 1) * moduleSize).toInt() - py
                svgBuilder.append("<rect x=\"$px\" y=\"$py\" width=\"$pw\" height=\"$ph\" fill=\"#0f172a\"/>")
            }
        }
    }
    svgBuilder.append("</svg>")

    return java.util.Base64.getEncoder().encodeToString(svgBuilder.toString().toByteArray())
}

private fun wechatQrBase64(): String = runCatching {
    Thread.currentThread().contextClassLoader
        .getResourceAsStream("static/wechat-qr.jpg")?.use { it.readBytes() }
}.getOrNull()?.let { java.util.Base64.getEncoder().encodeToString(it) } ?: ""
