package com.mamba.picme.server.routes

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.mamba.picme.server.cos.CosService
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.div
import kotlinx.html.head
import kotlinx.html.html
import kotlinx.html.img
import kotlinx.html.meta
import kotlinx.html.p
import kotlinx.html.stream.createHTML
import kotlinx.html.style
import kotlinx.html.title
import kotlinx.html.unsafe
import java.util.EnumMap

private const val APP_VERSION = "1.0.10"

fun Routing.downloadRoute(cosService: CosService) {
    get("/download") {
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
                    raw(
                        """
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
                        """.trimIndent(),
                    )
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
            }
        }
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
