package com.mamba.picme.domain.tag.i18n

import android.content.Context
import android.util.Log
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/**
 * 本地双语 TAG 词表
 *
 * 从 assets/tag_translations.json 加载：
 * - zh_to_en：中文标准词 → 英文标准词
 * - en_to_zh：英文标准词 → 中文标准词
 * - en_synonyms：英文口语/同义词 → 英文标准词
 *
 * 该词表用于：
 * 1. 展示时把中文 TAG 翻译成用户界面语言
 * 2. 搜索时把用户输入的英文查询扩展为可能的中文 canonical 词
 *
 * 注意：这是一个 starter 词表，未命中的 TAG 会原样显示/搜索。
 */
class BilingualVocab(
    val zhToEn: Map<String, String>,
    val enToZh: Map<String, String>,
    val enSynonyms: Map<String, String>
) {

    companion object {
        private const val TAG = "BilingualVocab"
        private const val ASSET_NAME = "tag_translations.json"

        /**
         * 从 assets 加载双语词表。
         * 如果 asset 不存在或解析失败，返回空词表（系统仍可用，只是无翻译）。
         */
        fun loadFromAssets(context: Context): BilingualVocab {
            return try {
                val json = context.assets.open(ASSET_NAME)
                    .bufferedReader()
                    .use { it.readText() }
                parseJson(json)
            } catch (e: IOException) {
                Log.w(TAG, "Failed to load bilingual vocab from assets", e)
                empty()
            } catch (e: JSONException) {
                Log.w(TAG, "Failed to parse bilingual vocab", e)
                empty()
            }
        }

        fun empty(): BilingualVocab = BilingualVocab(emptyMap(), emptyMap(), emptyMap())

        private fun parseJson(jsonString: String): BilingualVocab {
            val root = JSONObject(jsonString)
            return BilingualVocab(
                zhToEn = parseStringMap(root, "zh_to_en"),
                enToZh = parseStringMap(root, "en_to_zh"),
                enSynonyms = parseStringMap(root, "en_synonyms")
            )
        }

        private fun parseStringMap(root: JSONObject, key: String): Map<String, String> {
            val map = mutableMapOf<String, String>()
            root.optJSONObject(key)?.let { obj ->
                obj.keys().forEach { k ->
                    map[k] = obj.getString(k)
                }
            }
            return map
        }
    }
}
