package com.mamba.picme.mnn

import android.util.Log

/**
 * :mnn-core 内部日志封装。
 *
 * 保持 :engines:mnn-core 不依赖 :shared 的 Logger，避免反向依赖。
 */
internal object MnnLogger {
    @JvmStatic
    fun d(tag: String, message: String) {
        Log.d(tag, message)
    }

    @JvmStatic
    fun i(tag: String, message: String) {
        Log.i(tag, message)
    }

    @JvmStatic
    fun w(tag: String, message: String) {
        Log.w(tag, message)
    }

    @JvmStatic
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(tag, message, throwable)
        } else {
            Log.e(tag, message)
        }
    }
}
