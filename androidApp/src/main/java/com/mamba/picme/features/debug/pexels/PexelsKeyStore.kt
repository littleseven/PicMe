package com.mamba.picme.features.debug.pexels

import android.content.Context

/**
 * Pexels API Key 本地存取。
 * 独立 SharedPreferences（debug-only），不侵入 UserPreferencesRepository 的 DataStore schema。
 * Key 仅存本地，不进日志、不进 git。
 */
class PexelsKeyStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getKey(): String? =
        prefs.getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() }

    fun saveKey(key: String) {
        prefs.edit().putString(KEY_API_KEY, key.trim()).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_API_KEY).apply()
    }

    private companion object {
        const val PREFS_NAME = "debug_pexels_prefs"
        const val KEY_API_KEY = "pexels_api_key"
    }
}
