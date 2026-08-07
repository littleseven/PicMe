package com.mamba.picme.domain.search

enum class SegmentType(
    val isExplicit: Boolean = false,
    val isContent: Boolean = false
) {
    /** 时间：去年3月、今年夏天、上周一等 */
    TIME(isExplicit = true),
    /** 地点：北京、室内、海边等 */
    LOCATION(isExplicit = true),
    /** 人物：小孩、我、宝宝、某个人名等 */
    PERSON(isExplicit = true),
    /** 物体：猫、车、食物等 */
    OBJECT(isContent = true),
    /** 场景：室内、户外、海滩、餐厅等 */
    SCENE(isContent = true),
    /** 活动：聚餐、运动会、婚礼等 */
    ACTIVITY(isContent = true),
    /** OCR 文字：发票、车牌、菜单等 */
    OCR(isContent = true),
    /** 未知/停用词：照片、的、了等 */
    UNKNOWN;
}
