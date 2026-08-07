package com.mamba.picme.domain.matting

import com.mamba.picme.R

/** 证件照尺寸/颜色预设（@300dpi 国标）。 */
object IDPhotoSpecs {

    data class Size(val nameRes: Int, val widthPx: Int, val heightPx: Int, val labelCn: String)

    data class Color(val nameRes: Int, val argb: Int, val labelCn: String)

    /** 1寸 25×35mm、2寸 35×49mm、小1寸 22×32mm、小2寸 30×40mm（@300dpi）。 */
    val SIZES: List<Size> = listOf(
        Size(R.string.id_photo_size_1in, 295, 413, "1寸"),
        Size(R.string.id_photo_size_2in, 413, 579, "2寸"),
        Size(R.string.id_photo_size_small_1in, 260, 378, "小1寸"),
        Size(R.string.id_photo_size_small_2in, 354, 472, "小2寸")
    )

    /** 标准蓝 / 标准红 / 白。 */
    val COLORS: List<Color> = listOf(
        Color(R.string.id_photo_color_blue, 0xFF438EDB.toInt(), "标准蓝"),
        Color(R.string.id_photo_color_red, 0xFFD9001B.toInt(), "标准红"),
        Color(R.string.id_photo_color_white, 0xFFFFFFFF.toInt(), "白")
    )
}
