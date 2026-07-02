package com.mamba.picme.features.editor

import android.graphics.Path
import android.graphics.PointF

sealed class MarkupAction {
    abstract val id: String

    data class Doodle(
        override val id: String,
        val path: Path,
        val color: Int,
        val strokeWidth: Float
    ) : MarkupAction()

    data class Mosaic(
        override val id: String,
        val path: Path,
        val strokeWidth: Float,
        val mode: MosaicMode = MosaicMode.PIXEL
    ) : MarkupAction()

    data class Text(
        override val id: String,
        val text: String,
        val position: PointF,
        val color: Int,
        val sizePx: Float
    ) : MarkupAction()
}

enum class MosaicMode { PIXEL, BLUR }
