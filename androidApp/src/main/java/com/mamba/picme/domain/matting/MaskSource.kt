package com.mamba.picme.domain.matting

/** 抠图掩码来源。P1 仅 U2NETP；P2 接入 MODNET；P3 接入 MediaPipe Selfie Segmentation（A/B）。 */
enum class MaskSource { U2NETP, MODNET, SELFIE_SEGMENTATION, FUSION }
