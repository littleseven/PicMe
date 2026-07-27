package com.mamba.picme.data.indexing.geo

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.mamba.picme.core.common.Logger
import com.mamba.picme.data.indexing.LocationIndexUpdater
import com.mamba.picme.data.local.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * 轻量位置索引 pass:对 `locationName IS NULL` 的媒体读 EXIF GPS + 离线逆地理,
 * 写回 latitude/longitude/city/locationName + 层级表;无 GPS 写哨兵(`""`)防重复读。
 *
 * 取代已废弃、无触发点的 [com.mamba.picme.data.indexing.MediaIndexingWorker] 中的
 * **位置**职责(仅位置,不含 OCR)。由 Application 启动时后台触发,增量幂等——
 * 仅处理 `locationName` 为空的,首启跑完后后续启动 no-op,新照片下次启动补上。
 *
 * 全程端侧零网络(离线质心库);不跑 OCR,故轻量、不发热。
 */
class LocationIndexer(
    private val context: Context,
    private val db: AppDatabase = AppDatabase.getDatabase(context),
    private val offlineGeocoder: OfflineGeocoder = OfflineGeocoder.fromAssets(context)
) {
    companion object {
        private const val TAG = "PoLang:LocIndexer"
        private const val BATCH = 100
    }

    /** 跑一遍位置 pass,直到没有待处理媒体。 */
    suspend fun runPass() = withContext(Dispatchers.IO) {
        val dao = db.mediaDao()
        val updater = LocationIndexUpdater(db.locationDao())
        var processed = 0
        while (coroutineContext.isActive) {
            val batch = dao.getMediaNeedingLocationScan(BATCH)
            if (batch.isEmpty()) break
            for (entity in batch) {
                if (!coroutineContext.isActive) break
                try {
                    val resolved = readExifLocation(entity.uri)
                    if (resolved != null) {
                        val name = resolved.toDisplayString() ?: ""
                        dao.updateLocation(entity.id, resolved.latitude, resolved.longitude, name, resolved.city)
                        updater.updateIndex(entity.id, resolved)
                    } else {
                        dao.updateLocation(entity.id, null, null, NO_GPS, null)
                    }
                } catch (e: Exception) {
                    Logger.w(TAG, "location index fail media ${entity.id}: ${e.message}")
                    dao.updateLocation(entity.id, null, null, NO_GPS, null)
                }
                processed++
            }
            Logger.d(TAG, "location pass batch done, cumulative=$processed")
            // 节流:每批之间让出,避免长时持续 IO 导致发热(项目发热敏感)
            delay(50)
        }
        if (processed > 0) Logger.i(TAG, "location pass done: $processed media")
    }

    /** 读 EXIF GPS;有坐标用离线逆地理,无 GPS/读失败返回 null。 */
    private fun readExifLocation(uriStr: String): ResolvedLocation? {
        val uri = Uri.parse(uriStr)
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val latLong = ExifInterface(stream).latLong
                val lat = latLong?.getOrNull(0)
                val lon = latLong?.getOrNull(1)
                if (lat != null && lon != null) offlineGeocoder.lookup(lat, lon) else null
            }
        } catch (e: Exception) {
            Logger.w(TAG, "EXIF read fail ${uriStr.takeLast(40)}: ${e.message}")
            null
        }
    }
}

private const val NO_GPS = ""
