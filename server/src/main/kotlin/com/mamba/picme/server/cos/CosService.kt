package com.mamba.picme.server.cos

import com.mamba.picme.server.config.AppConfig
import com.qcloud.cos.COSClient
import com.qcloud.cos.ClientConfig
import com.qcloud.cos.auth.BasicCOSCredentials
import com.qcloud.cos.http.HttpProtocol
import com.qcloud.cos.model.CannedAccessControlList
import com.qcloud.cos.model.ObjectMetadata
import com.qcloud.cos.model.PutObjectRequest
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date

private val logger = LoggerFactory.getLogger("CosService")

data class CosApkInfo(
    val exists: Boolean,
    val size: String,
    val lastModified: String,
    val version: String,
    val publicUrl: String,
)

class CosService(config: AppConfig) {
    private val bucket = config.cosBucket
    private val region = config.cosRegion
    private val cosKey = "apk/polang-release.apk"

    private val client: COSClient? = run {
        if (config.cosSecretId.isBlank() || config.cosSecretKey.isBlank() || bucket.isBlank()) {
            logger.warn("COS not configured: secretId/key/bucket empty, APK features disabled")
            null
        } else {
            val cred = BasicCOSCredentials(config.cosSecretId, config.cosSecretKey)
            val clientConfig = ClientConfig(com.qcloud.cos.region.Region(region)).apply {
                httpProtocol = HttpProtocol.https
            }
            COSClient(cred, clientConfig)
        }
    }

    val configured: Boolean get() = client != null

    val publicUrl: String
        get() = "https://cos.polang.net/$cosKey"

    fun uploadApk(inputStream: InputStream, contentLength: Long, version: String): Boolean {
        val c = client ?: run {
            logger.error("COS not configured, cannot upload")
            return false
        }
        return try {
            val metadata = ObjectMetadata().apply {
                this.contentLength = contentLength
                contentType = "application/vnd.android.package-archive"
                addUserMetadata("version", version)
            }
            val request = PutObjectRequest(bucket, cosKey, inputStream, metadata)
            c.putObject(request)
            c.setObjectAcl(bucket, cosKey, CannedAccessControlList.PublicRead)
            logger.info("APK uploaded to COS: bucket=$bucket, key=$cosKey, version=$version, size=$contentLength")
            true
        } catch (e: Exception) {
            logger.error("Failed to upload APK to COS", e)
            false
        }
    }

    fun getApkInfo(): CosApkInfo {
        val url = publicUrl
        val c = client ?: return CosApkInfo(false, "", "", "", url)
        return try {
            val metadata = c.getObjectMetadata(bucket, cosKey)
            CosApkInfo(
                exists = true,
                size = formatFileSize(metadata.contentLength),
                lastModified = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date(metadata.lastModified.time)),
                version = metadata.userMetadata["version"] ?: "",
                publicUrl = url,
            )
        } catch (e: Exception) {
            logger.warn("Failed to query COS APK metadata: ${e.message}")
            CosApkInfo(false, "", "", "", url)
        }
    }

    private fun formatFileSize(bytes: Long): String {
        val mb = bytes.toDouble() / (1024 * 1024)
        return if (mb >= 1) "${String.format("%.1f", mb)} MB" else "${bytes / 1024} KB"
    }
}
