package com.mamba.picme.core.agenttools

import org.json.JSONObject

/** 运行时状态快照来源（spec §3.1）。实现方负责从设置/仓库采集，纯接口便于 JVM 单测。 */
fun interface RuntimeStateProvider {
    fun snapshot(): JSONObject
}
