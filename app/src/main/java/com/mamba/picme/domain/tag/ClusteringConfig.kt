package com.mamba.picme.domain.tag

/**
 * 人脸聚类统一配置常量
 *
 * 所有人脸聚类相关的阈值、参数均定义在此处，
 * [FaceClusterEngine]、[TagGenerationScheduler]、[FaceClusteringWorker]
 * 等使用者统一引用此配置，确保参数一致性。
 *
 * ─── 参数说明 ─────────────────────────────────────────
 * COSINE_THRESHOLD : 流式匹配的余弦相似度下限（越大越严格）
 * DBSCAN_EPS       : DBSCAN 余弦距离上限（= 1 - 相似度，越小越严格）
 * CLUSTER_COHESION_MIN : 簇内平均相似度下限（低于此值则分裂）
 *
 * 当前值: 相似度 ≥ 0.72 / 距离 ≤ 0.28，适用于 MobileFaceNet 512 维 embedding
 * ──────────────────────────────────────────────────────
 */
object ClusteringConfig {

    /** 余弦相似度阈值：高于此值归入已有簇（越接近 1.0 越严格）
     *  0.65：在抑制误聚与召回低频人脸之间取平衡 */
    const val COSINE_THRESHOLD = 0.65f

    /** DBSCAN: 余弦距离阈值（= 1 - 相似度，越小越严格）
     *  0.35：相似度 ≥ 0.65 才成簇，抑制不同女明星误聚 */
    const val DBSCAN_EPS = 0.35f

    /** DBSCAN: 最小邻居数（≥2 形成核心点）
     *  降为 2：让照片较少的明星/低频人物也能成簇 */
    const val DBSCAN_MIN_PTS = 2

    /** 簇内部平均相似度下限（< 此值则递归分裂）
     *  0.35：对松散大簇进行二次分裂 */
    const val CLUSTER_COHESION_MIN = 0.35f

    /** 增量积累达到此数量后触发全量 DBSCAN 重聚 */
    const val RE_CLUSTER_THRESHOLD = 100
}
