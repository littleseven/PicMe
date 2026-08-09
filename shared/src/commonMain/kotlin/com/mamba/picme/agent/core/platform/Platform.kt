package com.mamba.picme.agent.core.platform

/**
 * 当前运行平台的标识符（小写），供网关 `X-Platform` header 注入使用。
 *
 * 值：`"android"` / `"ios"` / `"jvm"`。由各平台 source set 的 actual 提供，
 * 照 [thread.DispatcherProvider] 的 expect/actual 模式。
 */
expect val currentPlatform: String
