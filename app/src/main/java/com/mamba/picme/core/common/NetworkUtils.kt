package com.mamba.picme.core.common

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * 网络状态工具类
 */
object NetworkUtils {

    /**
     * 当前是否连接 WiFi 且可访问互联网
     */
    fun isWifiConnected(context: Context): Boolean {
        return hasTransport(context, NetworkCapabilities.TRANSPORT_WIFI)
    }

    /**
     * 当前是否连接移动蜂窝网络且可访问互联网
     */
    fun isCellularConnected(context: Context): Boolean {
        return hasTransport(context, NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    /**
     * 当前是否有可用的网络连接
     */
    fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun hasTransport(context: Context, transportType: Int): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(transportType) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
