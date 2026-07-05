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

    /** 聚类策略切换：true 使用方案 B（密度自适应 MST/HDBSCAN），false 使用方案 A（DBSCAN） */
    const val USE_ADAPTIVE_CLUSTERING = true

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

    // ═══════════════════════════════════════════════════
    //  方案 B：密度自适应 k-NN 图连通分量聚类
    // ═══════════════════════════════════════════════════

    /** k-NN 邻居数 k。
     *  越小簇越紧凑（可能漏召），越大越连通（可能混组）。
     *  当前经验值 3：在明星测试集上得到 10 个高纯度簇，接近真实 11 人。 */
    const val KNN_K = 3

    /** k-NN 建边最小余弦相似度（= 1 - eps）。
     *  越大边越严格（纯度高、噪声多）；越小边越宽松（召回高、可能混组）。
     *  当前经验值 0.40：在 70 张明星测试图上 purity=1.0。 */
    const val KNN_MIN_SIMILARITY = 0.40f

    /** 方案 B 最小簇大小，小于此值的连通分量视为噪声。
     *  与 DBSCAN_MIN_PTS 保持一致的语义：≥2 张人脸才成人物簇。 */
    const val KNN_MIN_CLUSTER_SIZE = 2
}

