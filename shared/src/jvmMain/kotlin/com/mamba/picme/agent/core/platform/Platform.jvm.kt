package com.mamba.picme.agent.core.platform

/** JVM 端 [currentPlatform] 实现（供单测/桌面，服务端不调 KoogChatAgent，值不进生产）。 */
actual val currentPlatform: String = "jvm"
