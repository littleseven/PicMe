package com.mamba.picme.domain.dedup

import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

class DedupTrashManager(private val context: Context) {

    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun buildTrashIntent(uris: List<String>): IntentSender =
        MediaStore.createTrashRequest(
            context.contentResolver, uris.map { uri -> Uri.parse(uri) }, true
        ).intentSender

    fun buildRestoreIntent(uris: List<String>): IntentSender =
        MediaStore.createTrashRequest(
            context.contentResolver, uris.map { uri -> Uri.parse(uri) }, false
        ).intentSender

    /**
     * 授权后复查 uri 是否仍存在（未删净）。注意：部分 ROM（实测 HyperOS/Android 16）对已
     * trash 的媒体行做直接 item-URI 查询时仍返回该行（AOSP 默认查询会过滤 trash 行），
     * 因此必须读 [MediaStore.MediaColumns.IS_TRASHED] 区分「真没删」与「已进回收站」，
     * 否则会误判部分拒绝、整组保留不刷新。IS_TRASHED 为 API 29+ 列；本方法仅由 API 30+
     * 的回收站授权回流调用，列缺失时保守视为仍存在。
     */
    fun queryExisting(uris: List<String>): List<String> {
        val existing = mutableListOf<String>()
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.IS_TRASHED)
        for (uri in uris) {
            runCatching {
                context.contentResolver.query(Uri.parse(uri), projection, null, null, null)?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use
                    val trashedIndex = cursor.getColumnIndex(MediaStore.MediaColumns.IS_TRASHED)
                    val trashed = trashedIndex >= 0 && cursor.getInt(trashedIndex) != 0
                    if (!trashed) existing += uri
                }
            }
        }
        return existing
    }
}
