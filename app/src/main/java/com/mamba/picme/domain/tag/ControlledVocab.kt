package com.mamba.picme.domain.tag

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.IOException

/**
 * 受控词表 —— 从 assets/controlled_vocab.json 加载的标签规范化词库
 *
 * 用于后处理阶段将 Qwen 自由文本输出映射到标准标签。
 * 词表是软约束：未匹配的词保留原值到 [QwenTagsNormalized.nonStandard]。
 *
 * 新增类别：clothing（服装配饰）、animal（动物）、food_drink（食物饮品）、
 * architecture（建筑）、nature（自然元素）、transport（交通工具）。
 *
 * 同义词映射（synonyms）支持多对一归一化：例如 "帅哥" → "男性"。
 */
data class ControlledVocab(
    val scene: List<String> = emptyList(),
    val activity: List<String> = emptyList(),
    val objects: List<String> = emptyList(),
    val atmosphere: List<String> = emptyList(),
    val people: List<String> = emptyList(),
    val clothing: List<String> = emptyList(),
    val animal: List<String> = emptyList(),
    val foodDrink: List<String> = emptyList(),
    val architecture: List<String> = emptyList(),
    val nature: List<String> = emptyList(),
    val transport: List<String> = emptyList(),
    /** 同义词映射：非标准词 → 标准词（用于一语义覆盖多搜索词） */
    val synonyms: Map<String, String> = emptyMap()
) {
    /** 返回所有类别的标签并集（用于跨类别模糊匹配） */
    val allCategories: List<String> by lazy {
        scene + activity + objects + atmosphere + people +
            clothing + animal + foodDrink + architecture + nature + transport
    }

    /** MobileCLIP scene 字段候选：直接取 scene 类别 */
    val sceneCandidates: List<String>
        get() = scene

    /** MobileCLIP objects 字段候选：直接取 objects 类别 */
    val objectCandidates: List<String>
        get() = objects

    /** MobileCLIP tags 字段候选：跨人物、服饰、动物、食物、建筑、自然、交通工具、氛围等类别 */
    val tagCandidates: List<String>
        get() = people + clothing + animal + foodDrink + architecture + nature + transport + atmosphere

    /**
     * 反向同义词映射：标准词 → 所有同义词列表
     * 用于搜索扩展：搜索"美女"时也能匹配标签"女性"
     */
    val reverseSynonyms: Map<String, List<String>> by lazy {
        val result = mutableMapOf<String, MutableList<String>>()
        for ((synonym, canonical) in synonyms) {
            if (synonym != canonical) {
                result.getOrPut(canonical) { mutableListOf() }.add(synonym)
            }
        }
        result
    }

    companion object {
        private const val TAG = "ControlledVocab"

        /**
         * 从 assets 目录加载受控词表
         */
        fun loadFromAssets(context: Context): ControlledVocab {
            return try {
                val jsonString = context.assets.open("controlled_vocab.json")
                    .bufferedReader()
                    .use { it.readText() }
                parseJson(jsonString)
            } catch (e: IOException) {
                Log.e(TAG, "Failed to load vocab from assets", e)
                ControlledVocab()
            }
        }

        private fun parseJson(jsonString: String): ControlledVocab {
            val root = JSONObject(jsonString)

            // 解析同义词映射
            val synonymsMap = mutableMapOf<String, String>()
            root.optJSONObject("synonyms")?.let { synObj ->
                synObj.keys().forEach { key ->
                    synonymsMap[key] = synObj.getString(key)
                }
            }

            return ControlledVocab(
                scene = parseArray(root, "scene"),
                activity = parseArray(root, "activity"),
                objects = parseArray(root, "objects"),
                atmosphere = parseArray(root, "atmosphere"),
                people = parseArray(root, "people"),
                clothing = parseArray(root, "clothing"),
                animal = parseArray(root, "animal"),
                foodDrink = parseArray(root, "food_drink"),
                architecture = parseArray(root, "architecture"),
                nature = parseArray(root, "nature"),
                transport = parseArray(root, "transport"),
                synonyms = synonymsMap
            )
        }

        private fun parseArray(root: JSONObject, key: String): List<String> {
            return root.optJSONArray(key)?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList()
        }
    }
}
