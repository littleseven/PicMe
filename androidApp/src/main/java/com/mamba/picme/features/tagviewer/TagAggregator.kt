package com.mamba.picme.features.tagviewer

/**
 * 将一批 [PhotoTagsItem] 的标签按字段聚合计数，结果按次数降序排列。
 *
 * 跳过 parsed==null 的项；忽略空白标签。
 */
object TagAggregator {

    fun aggregate(items: List<PhotoTagsItem>): TagAggregates {
        val scenes = mutableMapOf<String, Int>()
        val objects = mutableMapOf<String, Int>()
        val tags = mutableMapOf<String, Int>()

        for (item in items) {
            val parsed = item.parsed ?: continue
            accumulate(scenes, parsed.scene)
            for (label in parsed.objects) accumulate(objects, label)
            for (label in parsed.tags) accumulate(tags, label)
        }

        return TagAggregates(
            scenes = scenes.toSortedCounts(),
            objects = objects.toSortedCounts(),
            tags = tags.toSortedCounts()
        )
    }

    private fun accumulate(target: MutableMap<String, Int>, label: String) {
        if (label.isBlank()) return
        target.merge(label, 1, Int::plus)
    }

    private fun Map<String, Int>.toSortedCounts(): List<TagCount> =
        entries.map { entry -> TagCount(entry.key, entry.value) }
            .sortedByDescending { tag -> tag.count }
}
