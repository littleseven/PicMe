package com.mamba.picme.domain.agent.capability.optimize.consent

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 云端 AI 优化授权管理器
 *
 * 管理用户是否允许将压缩图片上传到云端视觉模型进行智能推荐。
 */
class CloudOptimizeConsentManager(
    private val context: Context
) {

    companion object {
        private const val DATA_STORE_NAME = "ai_optimize_consent"
        private val CLOUD_OPTIMIZE_ALLOWED_KEY = booleanPreferencesKey("cloud_optimize_allowed")
    }

    private val Context.dataStore by preferencesDataStore(name = DATA_STORE_NAME)

    /**
     * 用户是否已授权云端 AI 优化
     */
    suspend fun isCloudOptimizeAllowed(): Boolean {
        return context.dataStore.data
            .map { preferences -> preferences[CLOUD_OPTIMIZE_ALLOWED_KEY] ?: false }
            .first()
    }

    /**
     * 设置云端 AI 优化授权状态
     */
    suspend fun setCloudOptimizeAllowed(allowed: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[CLOUD_OPTIMIZE_ALLOWED_KEY] = allowed
        }
    }
}
