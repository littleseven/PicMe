package com.mamba.picme.domain.agent.capability.optimize.gacha

import android.graphics.Bitmap
import com.mamba.picme.domain.agent.capability.optimize.preset.OptimizePreset

/**
 * 单张抽卡候选卡。
 *
 * @property index 卡组内序号（0 为 base preset 锚点）
 * @property direction 扰动方向标签（"base" / "clarity" / "warm" / ...），UI 展示与落库用
 * @property preset 候选参数
 */
data class OptimizeCandidate(
    val index: Int,
    val direction: String,
    val preset: OptimizePreset
)

/**
 * 评分后的候选卡。
 *
 * @property nimaScore NIMA 美学分（1~10），null = 未评分（护栏淘汰或推理失败）
 * @property rejected 是否被护栏/评分失败淘汰
 * @property rejectReason 淘汰原因（日志与落库用）
 * @property thumbnail 512px 渲染结果（「换一组」对比条展示用）
 */
data class ScoredCandidate(
    val candidate: OptimizeCandidate,
    val nimaScore: Float?,
    val rejected: Boolean,
    val rejectReason: String? = null,
    val thumbnail: Bitmap? = null
)

/** 抽卡结果 */
sealed interface GachaResult {

    /** 最优候选过退化守卫，可应用 */
    data class Selected(
        val best: ScoredCandidate,
        val all: List<ScoredCandidate>,
        val originalScore: Float?
    ) : GachaResult

    /** 全部候选未显著优于原图，保持原图 */
    data class KeepOriginal(
        val all: List<ScoredCandidate>,
        val originalScore: Float?
    ) : GachaResult

    /** 抽卡不可用（NIMA 未下载 / 解码失败 / 有效卡不足），调用方退回固定预设路径 */
    data object Unavailable : GachaResult
}
