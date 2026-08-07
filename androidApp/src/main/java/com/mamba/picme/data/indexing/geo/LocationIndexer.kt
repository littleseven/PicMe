package com.mamba.picme.data.indexing.geo

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.room.withTransaction
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
 *
 * **EXIF GPS 读取**:用 [ExifInterface] 标准库。**前提**:AndroidManifest 必须声明
 * `ACCESS_MEDIA_LOCATION`,否则 Android 10+ 系统对 app 读取 MediaStore 图片时 redact
 * EXIF GPS(数据区清零),任何库都读不到坐标。
 *
 * **批量事务写入**:一批 BATCH 条 EXIF 读取攒齐后,在一个 `withTransaction` 内统一写
 * [com.mamba.picme.data.local.MediaDao.updateLocation]。Room 失效追踪只在事务提交时
 * 通知一次,相册 `groupedMedia` Flow(基于全表 SELECT)每批只重算 1 次,而非逐条触发
 * 的 BATCH 次——避免 10000 张图引发 10000 次全表重查重分组(UI 卡顿 + 发热)。层级表
 * (location_index)写入不触发相册 Flow,保持逐条。
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

            // 阶段 1:逐张读 EXIF + 逆地理(纯读文件 + 计算,不写库),攒批量结果。
            // 读操作互相独立,但保持串行避免并发 IO 发热(项目发热敏感)。
            val locationUpdates = ArrayList<LocationUpdate>(batch.size)
            val indexUpdates = ArrayList<Pair<Long, ResolvedLocation?>>(batch.size)
            for (entity in batch) {
                if (!coroutineContext.isActive) break
                try {
                    val resolved = readExifLocation(entity.uri)
                    if (resolved != null) {
                        val name = resolved.toDisplayString() ?: ""
                        locationUpdates.add(
                            LocationUpdate(entity.id, resolved.latitude, resolved.longitude, name, resolved.city)
                        )
                        indexUpdates.add(entity.id to resolved)
                    } else {
                        locationUpdates.add(LocationUpdate(entity.id, null, null, NO_GPS, null))
                        indexUpdates.add(entity.id to null)
                    }
                } catch (e: Exception) {
                    Logger.w(TAG, "location index fail media ${entity.id}: ${e.message}")
                    locationUpdates.add(LocationUpdate(entity.id, null, null, NO_GPS, null))
                    indexUpdates.add(entity.id to null)
                }
                processed++
            }

            // 阶段 2:一个事务批量写 media_assets(每批只触发 1 次 Room 失效 → 相册 Flow 只重算 1 次)。
            if (locationUpdates.isNotEmpty()) {
                db.withTransaction {
                    locationUpdates.forEach { update ->
                        dao.updateLocation(
                            update.mediaId,
                            update.latitude,
                            update.longitude,
                            update.locationName,
                            update.city
                        )
                    }
                }
                // 层级表(location_index)不触发相册 groupedMedia Flow,逐条更新即可。
                indexUpdates.forEach { (mediaId, resolved) ->
                    updater.updateIndex(mediaId, resolved)
                }
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

/** 一条位置批量更新(LocationIndexer 内部攒批用)。 */
private data class LocationUpdate(
    val mediaId: Long,
    val latitude: Double?,
    val longitude: Double?,
    val locationName: String,
    val city: String?
)
