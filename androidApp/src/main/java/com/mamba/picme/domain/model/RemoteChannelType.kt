package com.mamba.picme.domain.model

/**
 * 远程控制通道选择（单通道模型：同一时刻仅一个通道连接）。
 *
 * 存储于 DataStore（存 [name]），读取时经 [fromStored] 安全解析。
 * 默认 [FEISHU]：保 debug 开机自动连（凭据齐全时）；release 凭据为空则不连接。
 */
enum class RemoteChannelType {
    FEISHU,
    TELEGRAM,
    NONE;

    companion object {
        /** DataStore 存储值的安全解析；非法/空值回退默认 [FEISHU]。大小写不敏感。 */
        fun fromStored(name: String?): RemoteChannelType =
            name?.let { runCatching { valueOf(it.uppercase()) }.getOrNull() } ?: FEISHU
    }
}
