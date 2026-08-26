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

    fun queryExisting(uris: List<String>): List<String> {
        val existing = mutableListOf<String>()
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        for (uri in uris) {
            runCatching {
                context.contentResolver.query(Uri.parse(uri), projection, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) existing += uri
                }
            }
        }
        return existing
    }
}
