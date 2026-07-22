package com.mamba.picme.domain.tag

import com.mamba.picme.data.local.entity.TagScanPass

/**
 * TAG 类别
 *
 * 用于精细控制某一类标签的重新生成或增量生成。
 */
enum class TagCategory {
    /** 人脸相关信息：count / selfie / groupPhoto / personIds */
    FACE,

    /** 场景 */
    SCENE,

    /** 活动 */
    ACTIVITY,

    /** 物体列表 */
    OBJECTS,

    /** 受控词表标签 */
    TAGS,

    /** 一句话摘要 */
    SUMMARY;

    companion object {
        /** 全部类别 */
        val ALL: Set<TagCategory> = entries.toSet()

        /**
         * 将类别集合映射为需要执行的 Pass 阶段
         */
        fun toPasses(categories: Set<TagCategory>): List<TagScanPass> {
            val passes = mutableListOf<TagScanPass>()
            if (categories.contains(FACE)) {
                passes += TagScanPass.FACE_DETECTION
                passes += TagScanPass.DBSCAN
            }
            if (categories.any { it in setOf(SCENE, ACTIVITY, OBJECTS, TAGS, SUMMARY) }) {
                passes += TagScanPass.QWEN_TAGGING
            }
            return passes.distinct()
        }
    }
}
