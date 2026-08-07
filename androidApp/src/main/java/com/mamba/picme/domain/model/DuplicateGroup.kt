package com.mamba.picme.domain.model

/**
 * 重复图片组领域模型
 */
data class DuplicateGroup(
    val id: String,
    val fileUris: List<String>,
    val isExactDuplicate: Boolean = true
) {
    /**
     * 获取需要保留的文件 URI（默认保留第一个）
     */
    fun getKeepUri(): String? = fileUris.firstOrNull()

    /**
     * 获取需要删除的文件 URI
     */
    fun getDeleteUris(): List<String> = fileUris.drop(1)
}
