package com.mamba.picme.domain.matting

/** 抠图路由：人像（检测到人脸）走 MODNet 软边精修；其余走 u2netp 通用分割。 */
object MattingRouter {
    fun choose(hasFace: Boolean): MaskSource =
        if (hasFace) MaskSource.MODNET else MaskSource.U2NETP
}
