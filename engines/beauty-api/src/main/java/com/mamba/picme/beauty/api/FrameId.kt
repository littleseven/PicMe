package com.mamba.picme.beauty.api

import java.util.concurrent.atomic.AtomicLong

/**
 * 全局帧标识符
 * - 单调递增，从 1 开始
 * - 与相机帧生命周期绑定
 */
@JvmInline
value class FrameId(val value: Long) : Comparable<FrameId> {
    companion object {
        val INVALID = FrameId(0L)
        private val counter = AtomicLong(0L)
        fun next(): FrameId = FrameId(counter.incrementAndGet())
    }

    override fun compareTo(other: FrameId): Int = value.compareTo(other.value)
}
