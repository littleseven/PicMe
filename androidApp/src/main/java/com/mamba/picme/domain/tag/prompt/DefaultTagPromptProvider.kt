package com.mamba.picme.domain.tag.prompt

import com.mamba.picme.domain.model.AppLanguage

/**
 * 默认 Prompt 提供者
 *
 * 针对 SmolVLM-256M 等小模型的 prompt 工程：
 * - system prompt 极简，避免小模型被无关指令带偏
 * - user prompt 只输出一段合法 JSON，不要任何描述、解释、markdown
 * - JSON 放在回答最后，以便兜底解析
 */
class DefaultTagPromptProvider : TagPromptProvider {

    override fun systemPrompt(lang: AppLanguage): String = ""

    override fun systemPromptForActivityAndSummary(lang: AppLanguage): String = ""

    override fun userPromptForActivityAndSummary(
        lang: AppLanguage,
        faceCount: Int,
        isGroupPhoto: Boolean
    ): String {
        return if (lang == AppLanguage.ENGLISH) {
            "Output only a JSON object with activity and summary: {\"activity\":\"...\",\"summary\":\"...\"}"
        } else {
            "只输出一个 JSON 对象，包含 activity 和 summary：{\"activity\":\"...\",\"summary\":\"...\"}"
        }
    }

    override fun userPrompt(lang: AppLanguage, faceCount: Int, isGroupPhoto: Boolean): String {
        val faceHint = if (lang == AppLanguage.ENGLISH) {
            when {
                faceCount <= 0 -> "No face detected."
                isGroupPhoto -> "Group photo of $faceCount people."
                faceCount >= 2 -> "Photo of $faceCount people."
                else -> "Single-person photo."
            }
        } else {
            when {
                faceCount <= 0 -> "未检测到人脸。"
                isGroupPhoto -> "${faceCount}人合影。"
                faceCount >= 2 -> "${faceCount}人照片。"
                else -> "单人照片。"
            }
        }

        return if (lang == AppLanguage.ENGLISH) {
            buildString {
                appendLine("Describe this photo using only the JSON format below.")
                appendLine(faceHint)
                appendLine()
                appendLine("Output ONLY a single valid JSON object. No explanation. No markdown. No extra text before or after.")
                appendLine()
                appendLine(
                    "{\"scene\":\"indoor or outdoor place\",\"activity\":\"what people are doing\"," +
                        "\"objects\":[\"object1\",\"object2\",\"object3\"]," +
                        "\"tags\":[\"tag1\",\"tag2\",\"tag3\",\"tag4\",\"tag5\",\"tag6\",\"tag7\",\"tag8\"]," +
                        "\"summary\":\"one sentence description\"}"
                )
                appendLine()
                appendLine("Requirements:")
                appendLine("- scene: one word, e.g. park, street, restaurant, office, home, beach, mountain")
                appendLine("- activity: one phrase, e.g. eating, walking, selfie, traveling, working, party")
                appendLine("- objects: 2-5 visible objects")
                appendLine("- tags: exactly 8 English nouns. Include gender/age if person exists, main objects, and scene/atmosphere")
                appendLine("- summary: 15-30 words")
                appendLine("- The JSON must be the ONLY text in your response.")
            }
        } else {
            buildString {
                appendLine("只用下面的 JSON 格式描述这张照片。")
                appendLine(faceHint)
                appendLine()
                appendLine("只输出一个合法的 JSON 对象。不要解释。不要用 markdown。JSON 前后不要有任何文字。")
                appendLine()
                appendLine(
                    "{\"scene\":\"室内或室外地点\",\"activity\":\"人物活动\"," +
                        "\"objects\":[\"物体1\",\"物体2\",\"物体3\"]," +
                        "\"tags\":[\"标签1\",\"标签2\",\"标签3\",\"标签4\",\"标签5\",\"标签6\",\"标签7\",\"标签8\"]," +
                        "\"summary\":\"一句话描述\"}"
                )
                appendLine()
                appendLine("要求：")
                appendLine("- scene：一个词，例如公园、街道、餐厅、办公室、家中、海边、山区")
                appendLine("- activity：一个短语，例如吃饭、散步、自拍、旅行、工作、聚会")
                appendLine("- objects：2-5 个照片中可见物体")
                appendLine("- tags：正好 8 个中文名词。如有人物需包含性别/年龄，再补充主要物体和场景/氛围词")
                appendLine("- summary：15-30 字")
                appendLine("- 你的回答必须只有 JSON，不能有其他内容。")
            }
        }
    }
}
