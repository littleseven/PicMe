package com.mamba.picme.domain.tag.prompt

import com.mamba.picme.domain.model.AppLanguage

/**
 * 默认 Prompt 提供者
 *
 * 提供中英文两套 prompt，输出格式保持一致，仅语言不同。
 */
class DefaultTagPromptProvider : TagPromptProvider {

    override fun systemPrompt(lang: AppLanguage): String = if (lang == AppLanguage.ENGLISH) {
        ENGLISH_SYSTEM_PROMPT
    } else {
        CHINESE_SYSTEM_PROMPT
    }

    override fun systemPromptForActivityAndSummary(lang: AppLanguage): String = if (lang == AppLanguage.ENGLISH) {
        ENGLISH_SYSTEM_PROMPT_ACTIVITY_SUMMARY
    } else {
        CHINESE_SYSTEM_PROMPT_ACTIVITY_SUMMARY
    }

    override fun userPromptForActivityAndSummary(
        lang: AppLanguage,
        faceCount: Int,
        isGroupPhoto: Boolean
    ): String {
        if (faceCount <= 0) {
            return if (lang == AppLanguage.ENGLISH) {
                "Analyze the activity and write a one-sentence summary."
            } else {
                "请分析照片中的活动，并用一句话概括照片内容。"
            }
        }

        return if (lang == AppLanguage.ENGLISH) {
            buildString {
                append("The photo has $faceCount face(s), ")
                append(
                    when {
                        isGroupPhoto -> "it looks like a group photo."
                        faceCount >= 2 -> "it looks like a photo of two people."
                        else -> "it looks like a single-person photo."
                    }
                )
                append(" Analyze the activity and write a one-sentence summary.")
            }
        } else {
            buildString {
                append("照片中有${faceCount}张人脸，")
                append(
                    when {
                        isGroupPhoto -> "可能是合影。"
                        faceCount >= 2 -> "可能是双人照。"
                        else -> "可能是单人照。"
                    }
                )
                append("请分析照片中的活动，并用一句话概括照片内容。")
            }
        }
    }

    override fun userPrompt(lang: AppLanguage, faceCount: Int, isGroupPhoto: Boolean): String {
        if (faceCount <= 0) {
            return if (lang == AppLanguage.ENGLISH) {
                "Analyze the scene, activity, objects and generate tags."
            } else {
                "请分析场景、活动、物体并生成标签。"
            }
        }

        return if (lang == AppLanguage.ENGLISH) {
            buildString {
                append("The photo has $faceCount face(s), ")
                append(
                    when {
                        isGroupPhoto -> "it looks like a group photo."
                        faceCount >= 2 -> "it looks like a photo of two people."
                        else -> "it looks like a single-person photo."
                    }
                )
                append(" Analyze the scene, activity, objects and generate tags.")
            }
        } else {
            buildString {
                append("照片中有${faceCount}张人脸，")
                append(
                    when {
                        isGroupPhoto -> "可能是合影。"
                        faceCount >= 2 -> "可能是双人照。"
                        else -> "可能是单人照。"
                    }
                )
                append("请分析场景、活动、物体并生成标签。")
            }
        }
    }

    @Suppress("MaxLineLength")
    companion object {
        private val CHINESE_SYSTEM_PROMPT = buildString {
            appendLine("你是一个相册照片标签生成助手。只输出纯JSON，不要markdown代码块、不要解释、不要多余文字。")
            appendLine()
            appendLine("输出格式：")
            appendLine("{\"scene\":\"场景\",\"activity\":\"活动\",\"objects\":[\"物体1\",\"物体2\"],\"tags\":[\"标签1\",\"标签2\"],\"summary\":\"一句话概括\"}")
            appendLine()
            appendLine("要求：")
            appendLine("1. 全部使用中文，专有名词（如iPhone）除外")
            appendLine("2. scene：室内/户外/公园/街道/餐厅/海边等城市或自然环境")
            appendLine("3. activity：吃饭/旅行/运动/聚会/散步/自拍/工作/休息等")
            appendLine("4. objects：2-5个照片中最明显、具体可见的物体")
            appendLine("5. tags：8个常用中文名词，便于搜索。必须包含：")
            appendLine("   - 人物特征（如有）：性别（男/女）和年龄（小孩/成年人/老人/婴儿）")
            appendLine("   - 2-3个最突出的具体物体")
            appendLine("   - 1-2个场景/氛围词（白天/夜晚/室内/户外/晴天/雨天等）")
            appendLine("6. summary：30-40字的一句话概括，包含主要人物、场景、动作和氛围")
            appendLine()
            appendLine("示例：")
            appendLine("{\"scene\":\"公园\",\"activity\":\"散步\",\"objects\":[\"婴儿\",\"推车\",\"树\"],\"tags\":[\"女\",\"婴儿\",\"户外\",\"公园\",\"散步\",\"亲子\",\"白天\",\"推车\"],\"summary\":\"一位妈妈推着婴儿车在阳光明媚的公园小径上散步，周围绿树成荫，氛围轻松愉快\"}")
        }

        private val CHINESE_SYSTEM_PROMPT_ACTIVITY_SUMMARY = buildString {
            appendLine("你是一个相册照片描述助手。只输出纯JSON，不要markdown代码块、不要解释、不要多余文字。")
            appendLine()
            appendLine("输出格式：")
            appendLine("{\"activity\":\"活动\",\"summary\":\"一句话概括\"}")
            appendLine()
            appendLine("要求：")
            appendLine("1. 全部使用中文，专有名词（如iPhone）除外")
            appendLine("2. activity：吃饭/旅行/运动/聚会/散步/自拍/工作/休息等")
            appendLine("3. summary：30-40字的一句话概括，包含主要人物、场景、动作和氛围")
            appendLine()
            appendLine("示例：")
            appendLine("{\"activity\":\"散步\",\"summary\":\"一位妈妈推着婴儿车在阳光明媚的公园小径上散步，周围绿树成荫，氛围轻松愉快\"}")
        }

        private val ENGLISH_SYSTEM_PROMPT_ACTIVITY_SUMMARY = buildString {
            appendLine("You are a photo album description assistant. Output valid JSON only. No markdown, no explanation, no extra text.")
            appendLine()
            appendLine("Output format:")
            appendLine("{\"activity\":\"the activity\",\"summary\":\"a one-sentence summary\"}")
            appendLine()
            appendLine("Requirements:")
            appendLine("1. Use English only, except proper nouns like iPhone.")
            appendLine("2. activity: eating/traveling/sports/party/walking/selfie/working/resting/etc.")
            appendLine("3. summary: a 25-40 word sentence summarizing the photo, including main people, scene, action and atmosphere.")
            appendLine()
            appendLine("Example:")
            appendLine("{\"activity\":\"walking\",\"summary\":\"A mother pushing a stroller with her baby along a sunny park path lined with green trees, enjoying a relaxing afternoon walk\"}")
        }

        private val ENGLISH_SYSTEM_PROMPT = buildString {
            appendLine("You are a photo album tag generation assistant. Output valid JSON only. No markdown, no explanation, no extra text.")
            appendLine()
            appendLine("Output format:")
            appendLine("{\"scene\":\"the scene\",\"activity\":\"the activity\",\"objects\":[\"object1\",\"object2\"],\"tags\":[\"tag1\",\"tag2\"],\"summary\":\"a one-sentence summary\"}")
            appendLine()
            appendLine("Requirements:")
            appendLine("1. Use English only, except proper nouns like iPhone.")
            appendLine("2. scene: indoor/outdoor/park/street/restaurant/seaside/etc.")
            appendLine("3. activity: eating/traveling/sports/party/walking/selfie/working/resting/etc.")
            appendLine("4. objects: 2-5 most obvious, concrete visible objects in the photo.")
            appendLine("5. tags: 8 common English nouns for search. Must include:")
            appendLine("   - People traits (if any): gender (male/female) and age (baby/child/adult/elderly)")
            appendLine("   - 2-3 most prominent concrete objects")
            appendLine("   - 1-2 scene/atmosphere words (daytime/night/indoor/outdoor/sunny/rainy/etc.)")
            appendLine("6. summary: a 25-40 word sentence summarizing the photo, including main people, scene, action and atmosphere.")
            appendLine()
            appendLine("Example:")
            appendLine("{\"scene\":\"park\",\"activity\":\"walking\",\"objects\":[\"baby\",\"stroller\",\"tree\"],\"tags\":[\"female\",\"baby\",\"outdoor\",\"park\",\"walking\",\"family\",\"daytime\",\"stroller\"],\"summary\":\"A mother pushing a stroller with her baby along a sunny park path lined with green trees, enjoying a relaxing afternoon walk\"}")
        }
    }
}
