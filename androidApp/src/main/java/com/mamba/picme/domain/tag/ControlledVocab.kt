package com.mamba.picme.domain.tag

import android.content.Context
import android.util.Log
import com.mamba.picme.domain.model.AppLanguage
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
 * 新增英文候选（*_en），供 MobileCLIP 中英双语分类使用。
 *
 * 同义词映射（synonyms）支持多对一归一化：例如 "帅哥" → "男性"。
 */
data class ControlledVocab(
    val scene: List<String> = emptyList(),
    val sceneEn: List<String> = emptyList(),
    val activity: List<String> = emptyList(),
    val activityEn: List<String> = emptyList(),
    val objects: List<String> = emptyList(),
    val objectsEn: List<String> = emptyList(),
    val atmosphere: List<String> = emptyList(),
    val atmosphereEn: List<String> = emptyList(),
    val people: List<String> = emptyList(),
    val peopleEn: List<String> = emptyList(),
    val clothing: List<String> = emptyList(),
    val clothingEn: List<String> = emptyList(),
    val animal: List<String> = emptyList(),
    val animalEn: List<String> = emptyList(),
    val foodDrink: List<String> = emptyList(),
    val foodDrinkEn: List<String> = emptyList(),
    val architecture: List<String> = emptyList(),
    val architectureEn: List<String> = emptyList(),
    val nature: List<String> = emptyList(),
    val natureEn: List<String> = emptyList(),
    val transport: List<String> = emptyList(),
    val transportEn: List<String> = emptyList(),
    /** 风格/氛围标签，如「性感」 */
    val style: List<String> = emptyList(),
    val styleEn: List<String> = emptyList(),
    /** 同义词映射：非标准词 → 标准词（用于一语义覆盖多搜索词） */
    val synonyms: Map<String, String> = emptyMap(),
    /** 需要屏蔽的标签：MobileCLIP 候选中直接排除，Qwen 输出经规范化后过滤 */
    val blockedTags: List<String> = emptyList(),
    val blockedTagsEn: List<String> = emptyList()
) {
    /** 返回所有类别的标签并集（用于跨类别模糊匹配） */
    val allCategories: List<String> by lazy {
        scene + activity + objects + atmosphere + people +
            clothing + animal + foodDrink + architecture + nature + transport + style
    }

    /** 返回所有英文类别的标签并集（用于跨类别模糊匹配） */
    val allCategoriesEn: List<String> by lazy {
        sceneEn + activityEn + objectsEn + atmosphereEn + peopleEn +
            clothingEn + animalEn + foodDrinkEn + architectureEn + natureEn + transportEn + styleEn
    }

    /** MobileCLIP scene 字段候选：直接取 scene 类别 */
    val sceneCandidates: List<String>
        get() = scene

    /** MobileCLIP scene 字段英文候选 */
    val sceneCandidatesEn: List<String>
        get() = sceneEn

    /** MobileCLIP objects 字段候选：直接取 objects 类别 */
    val objectCandidates: List<String>
        get() = objects

    /** MobileCLIP objects 字段英文候选 */
    val objectCandidatesEn: List<String>
        get() = objectsEn

    /** MobileCLIP tags 字段候选：跨人物、服饰、动物、食物、建筑、自然、交通工具、氛围、风格等类别 */
    val tagCandidates: List<String>
        get() = people + clothing + animal + foodDrink + architecture + nature + transport + atmosphere + style

    /** MobileCLIP tags 字段英文候选 */
    val tagCandidatesEn: List<String>
        get() = peopleEn + clothingEn + animalEn + foodDrinkEn + architectureEn + natureEn + transportEn + atmosphereEn + styleEn

    /**
     * 语言感知的 MobileCLIP scene 候选。
     * 仅支持中文/英文；其他语言返回空列表，调用方应回退到 Qwen。
     */
    fun sceneCandidates(lang: AppLanguage): List<String> = when (lang) {
        AppLanguage.CHINESE -> scene
        AppLanguage.ENGLISH -> sceneEn
        else -> emptyList()
    }

    /**
     * 语言感知的 MobileCLIP objects 候选。
     * 仅支持中文/英文；其他语言返回空列表，调用方应回退到 Qwen。
     */
    fun objectCandidates(lang: AppLanguage): List<String> = when (lang) {
        AppLanguage.CHINESE -> objects
        AppLanguage.ENGLISH -> objectsEn
        else -> emptyList()
    }

    /**
     * 语言感知的 MobileCLIP tags 候选。
     * 仅支持中文/英文；其他语言返回空列表，调用方应回退到 Qwen。
     */
    fun tagCandidates(lang: AppLanguage): List<String> = when (lang) {
        AppLanguage.CHINESE -> tagCandidates
        AppLanguage.ENGLISH -> tagCandidatesEn
        else -> emptyList()
    }

    /**
     * 判断给定标签（按当前语言）是否被屏蔽
     */
    fun isBlocked(label: String, lang: AppLanguage): Boolean = when (lang) {
        AppLanguage.CHINESE -> label in blockedTags
        AppLanguage.ENGLISH -> label in blockedTagsEn
        else -> false
    }

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

        /**
         * 测试入口：直接从 JSON 字符串解析词表
         */
        internal fun parseJsonForTest(jsonString: String): ControlledVocab = parseJson(jsonString)

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
                sceneEn = parseArray(root, "scene_en"),
                activity = parseArray(root, "activity"),
                activityEn = parseArray(root, "activity_en"),
                objects = parseArray(root, "objects"),
                objectsEn = parseArray(root, "objects_en"),
                atmosphere = parseArray(root, "atmosphere"),
                atmosphereEn = parseArray(root, "atmosphere_en"),
                people = parseArray(root, "people"),
                peopleEn = parseArray(root, "people_en"),
                clothing = parseArray(root, "clothing"),
                clothingEn = parseArray(root, "clothing_en"),
                animal = parseArray(root, "animal"),
                animalEn = parseArray(root, "animal_en"),
                foodDrink = parseArray(root, "food_drink"),
                foodDrinkEn = parseArray(root, "food_drink_en"),
                architecture = parseArray(root, "architecture"),
                architectureEn = parseArray(root, "architecture_en"),
                nature = parseArray(root, "nature"),
                natureEn = parseArray(root, "nature_en"),
                transport = parseArray(root, "transport"),
                transportEn = parseArray(root, "transport_en"),
                style = parseArray(root, "style"),
                styleEn = parseArray(root, "style_en"),
                synonyms = synonymsMap,
                blockedTags = parseArray(root, "blocked_tags"),
                blockedTagsEn = parseArray(root, "blocked_tags_en")
            )
        }

        private fun parseArray(root: JSONObject, key: String): List<String> {
            return root.optJSONArray(key)?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList()
        }
    }
}
