package com.mamba.picme.core.identity

import android.content.Context
import android.provider.Settings
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.util.UUID

private val Context.deviceIdStore by preferencesDataStore(name = "device_id")

/**
 * 稳定的设备标识，用于未注册访客的服务端试用额度（X-Device-Id）。
 * 优先用 ANDROID_ID；缺失/已知异常值时回退到 DataStore 持久化的 UUID。
 */
class DeviceIdProvider(private val appContext: Context) {

    suspend fun get(): String {
        val androidId = runCatching {
            Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull().orEmpty()
        // 9774d56d682e549c 是 Android 2.2 的已知坏值
        if (androidId.isNotBlank() && androidId != "9774d56d682e549c") {
            return androidId
        }
        val key = stringPreferencesKey("uuid")
        val stored = appContext.deviceIdStore.data.first()[key]
        if (!stored.isNullOrBlank()) return stored
        val generated = "uuid-" + UUID.randomUUID().toString().replace("-", "")
        appContext.deviceIdStore.edit { it[key] = generated }
        return generated
    }
}
