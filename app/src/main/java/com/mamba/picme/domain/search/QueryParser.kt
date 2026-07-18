package com.mamba.picme.domain.search

import com.mamba.picme.domain.model.AppLanguage
import com.mamba.picme.domain.model.StructuredFilter
import com.mamba.picme.domain.model.TimeRange
import java.util.Calendar

/**
 * 自然语言查询解析器（Layer 1：无需 LLM 的规则匹配）
 *
 * 支持：
 * - 时间词解析（去年、上个月、夏天、春节等）
 * - 关键词提取（用于标签/OCR/地名搜索）
 * - 地点词检测（"北京"、"三里屯" 等中国城市名 → locationKeywords）
 * - 判断是否需要 LLM 解析复杂混合查询
 */
object QueryParser {

    /** 当前年份偏移（用于测试注入） */
    var currentYear: Int = Calendar.getInstance().get(Calendar.YEAR)
    /** 当前月份偏移 */
    var currentMonth: Int = Calendar.getInstance().get(Calendar.MONTH) + 1

    /** 地点关键词（用于检测地点词） */
    private val LOCATION_KEYWORDS = SearchVocabulary.LOCATION

    /**
     * 解析查询，返回结构化过滤条件
     *
     * @param query 用户输入
     * @param lang 当前界面语言，影响停用词过滤
     * @return StructuredFilter 如果规则能完全解析；null 表示需要 LLM 协助
     */
    fun parse(query: String, lang: AppLanguage = AppLanguage.CHINESE): StructuredFilter? {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return null

        val timeRange = parseTimeRange(trimmed)
        val (rawContentKeywords, locationKeywords) = extractCategorizedKeywords(trimmed, lang)

        // 通用人物触发词（如“人脸”“自拍”）只用于触发人脸列查询，
        // 不应再作为内容关键词去标签/OCR 中匹配。
        val genericPersonTriggers = SearchVocabulary.PERSON_GENERIC_TRIGGERS
        val contentKeywords = rawContentKeywords.filter { it !in genericPersonTriggers }

        // 人物相关搜索（含具体人物词如“宝宝”）统一开启人脸列查询
        val isPeople = isPeopleSearch(trimmed)

        // 没有任何可规则解析的约束 → 需要 LLM
        val hasConstraint = timeRange != null || contentKeywords.isNotEmpty() ||
            locationKeywords.isNotEmpty() || isPeople
        if (!hasConstraint) {
            return null
        }

        return StructuredFilter(
            timeRange = timeRange,
            keywords = contentKeywords,
            locationKeywords = locationKeywords,
            hasFaces = if (isPeople) true else null,
            needsLlm = false
        )
    }

    /**
     * 判断查询需要 LLM 解析
     */
    fun needsLlm(query: String): Boolean {
        return parse(query) == null
    }

    // ── 时间词解析 ──────────────────────────────────────────

    /**
     * 解析相对年份+月份，如"去年3月""今年5月""前年8月"
     */
    private fun parseRelativeYearMonth(query: String): TimeRange? {
        val markers = listOf("去年" to -1, "今年" to 0, "前年" to -2)
        for ((marker, yearOffset) in markers) {
            val idx = query.indexOf(marker)
            if (idx < 0) continue
            val afterMarker = query.substring(idx + marker.length)
            val month = parseMonthAfterYear(afterMarker) ?: continue
            val year = currentYear + yearOffset
            return TimeRange(
                startMs = monthStartMs(year, month - 1),
                endMs = monthEndMs(year, month - 1)
            )
        }
        return null
    }

    /**
     * 解析绝对年份+月份，如"2024年3月"
     */
    private fun parseAbsoluteYearMonth(query: String): TimeRange? {
        val yearMatch = Regex("""(\d{4})年""").find(query)
        if (yearMatch != null) {
            val year = yearMatch.groupValues[1].toInt()
            val afterYear = query.substring(yearMatch.range.last + 1)
            val month = parseMonthAfterYear(afterYear) ?: return TimeRange(
                startMs = monthStartMs(year, 0),
                endMs = monthEndMs(year, 11)
            )
            return TimeRange(
                startMs = monthStartMs(year, month - 1),
                endMs = monthEndMs(year, month - 1)
            )
        }
        return null
    }

    /**
     * 从"年"后面的字符串解析月份，支持阿拉伯和中文数字，如"3月""12月""五月"
     */
    private fun parseMonthAfterYear(afterYear: String): Int? {
        val arabicMatch = Regex("""^(\d{1,2})月""").find(afterYear)
        if (arabicMatch != null) {
            return arabicMatch.groupValues[1].toIntOrNull()?.coerceIn(1, 12)
        }
        val chineseMatch = Regex("""^([一二三四五六七八九十]{1,3})月""").find(afterYear)
        return chineseMatch?.groupValues?.get(1)?.let(::chineseMonthToInt)
    }

    /**
     * 解析独立中文月份，如"五月""十一月"
     */
    private fun parseStandaloneChineseMonth(query: String): TimeRange? {
        val match = Regex("""^([一二三四五六七八九十]{1,3})月""").find(query)
        val month = match?.groupValues?.get(1)?.let(::chineseMonthToInt) ?: return null
        return TimeRange(
            startMs = monthStartMs(currentYear, month - 1),
            endMs = monthEndMs(currentYear, month - 1)
        )
    }

    /**
     * 中文月份转数字：一→1，十一→11，十二→12
     */
    private fun chineseMonthToInt(text: String): Int? {
        val map = mapOf(
            "一" to 1, "二" to 2, "三" to 3, "四" to 4, "五" to 5,
            "六" to 6, "七" to 7, "八" to 8, "九" to 9, "十" to 10,
            "十一" to 11, "十二" to 12
        )
        return map[text]
    }

    private fun monthStartMs(year: Int, month: Int): Long {
        return Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun monthEndMs(year: Int, month: Int): Long {
        return Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, 31)
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis
    }

    /**
     * 解析查询中的时间范围（公开给 QuerySegmenter 复用）
     */
    fun parseTimeRange(query: String): TimeRange? {
        // 1. 精确到月：去年3月 / 今年5月 / 前年8月 / 2024年3月
        val relativeYearMonth = parseRelativeYearMonth(query)
        if (relativeYearMonth != null) return relativeYearMonth

        val absoluteYearMonth = parseAbsoluteYearMonth(query)
        if (absoluteYearMonth != null) return absoluteYearMonth

        // 1.5 独立中文月份：五月 / 十一月
        val standaloneChineseMonth = parseStandaloneChineseMonth(query)
        if (standaloneChineseMonth != null) return standaloneChineseMonth

        // 2. 整年：去年 / 今年 / 前年 / 2024年
        val yearMatch = Regex("(\\d{4})年").find(query)
        if (yearMatch != null) {
            val year = yearMatch.groupValues[1].toInt()
            return TimeRange(
                startMs = monthStartMs(year, 0),
                endMs = monthEndMs(year, 11)
            )
        }
        if (query.contains("去年")) {
            return TimeRange(
                startMs = monthStartMs(currentYear - 1, 0),
                endMs = monthEndMs(currentYear - 1, 11)
            )
        }
        if (query.contains("今年")) {
            val startMonth = if (query.contains("夏天")) 5 else 0
            val endMonth = if (query.contains("夏天")) 7 else 11
            return TimeRange(
                startMs = monthStartMs(currentYear, startMonth),
                endMs = monthEndMs(currentYear, endMonth)
            )
        }
        if (query.contains("前年")) {
            return TimeRange(
                startMs = monthStartMs(currentYear - 2, 0),
                endMs = monthEndMs(currentYear - 2, 11)
            )
        }

        // 3. 季节
        if (query.contains("夏天")) {
            return TimeRange(
                startMs = monthStartMs(currentYear, 5),
                endMs = monthEndMs(currentYear, 7)
            )
        }
        val seasonMap = mapOf("春天" to 2, "秋天" to 8, "冬天" to 11)
        for ((season, startMonth) in seasonMap) {
            if (query.contains(season)) {
                return TimeRange(
                    startMs = monthStartMs(currentYear, startMonth),
                    endMs = monthEndMs(currentYear, (startMonth + 2) % 12)
                )
            }
        }

        // 4. 上个月
        if (query.contains("上个月")) {
            val cal = Calendar.getInstance()
            cal.set(Calendar.YEAR, currentYear)
            cal.set(Calendar.MONTH, currentMonth - 1)
            cal.set(Calendar.DAY_OF_MONTH, 1)
            cal.add(Calendar.MONTH, -1)
            val month = cal.get(Calendar.MONTH)
            val year = cal.get(Calendar.YEAR)
            return TimeRange(
                startMs = monthStartMs(year, month),
                endMs = monthEndMs(year, month)
            )
        }

        // 5. 本周 / 上周
        if (query.contains("本周") || query.contains("上周")) {
            val cal = Calendar.getInstance()
            if (query.contains("上周")) cal.add(Calendar.WEEK_OF_YEAR, -1)
            cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val start = cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, 6)
            cal.set(Calendar.HOUR_OF_DAY, 23)
            cal.set(Calendar.MINUTE, 59)
            cal.set(Calendar.SECOND, 59)
            val end = cal.timeInMillis
            return TimeRange(startMs = start, endMs = end)
        }

        // 6. 昨天 / 今天 / 前天
        val relativeDayMap = mapOf("前天" to -2, "昨天" to -1, "今天" to 0)
        for ((word, offset) in relativeDayMap) {
            if (query.contains(word)) {
                val cal = Calendar.getInstance()
                cal.add(Calendar.DAY_OF_YEAR, offset)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                val start = cal.timeInMillis
                cal.set(Calendar.HOUR_OF_DAY, 23)
                cal.set(Calendar.MINUTE, 59)
                cal.set(Calendar.SECOND, 59)
                return TimeRange(startMs = start, endMs = cal.timeInMillis)
            }
        }

        return null
    }

    // ── 关键词提取（分类为内容词和地点词） ──────────────────

    /**
     * 从查询中提取关键词，区分内容词和地点词
     */
    fun extractCategorizedKeywords(
        query: String,
        lang: AppLanguage = AppLanguage.CHINESE
    ): Pair<List<String>, List<String>> {
        val allKeywords = extractKeywords(query, lang)
        val locationWords = mutableListOf<String>()
        val contentWords = mutableListOf<String>()

        for (kw in allKeywords) {
            if (kw in LOCATION_KEYWORDS) {
                locationWords.add(kw)
            } else {
                contentWords.add(kw)
            }
        }
        return contentWords to locationWords
    }

    /**
     * 从查询中提取有实体意义的关键词（移除时间词、停用词）
     */
    fun extractKeywords(query: String, lang: AppLanguage = AppLanguage.CHINESE): List<String> {
        var text = removeTimeWords(query)

        // 根据当前语言移除停用词
        val stopWords = when (lang) {
            AppLanguage.ENGLISH -> ENGLISH_STOP_WORDS
            else -> CHINESE_STOP_WORDS
        }
        for (word in stopWords) {
            text = text.replace(word, " ", ignoreCase = true)
        }

        return text
            .split(Regex("[\\s，,。.!！？?]+"))
            .map { kw -> kw.trim() }
            .filter { it.isNotEmpty() && it.length >= 1 }
            .distinct()
    }

    /**
     * 判断是否是"人物"相关搜索（含儿童/婴儿等同义概念）
     */
    fun isPeopleSearch(query: String): Boolean {
        val peopleKeywords = listOf(
            "人", "人物", "人脸", "合照", "合影", "people", "person",
            "face", "portrait", "selfie", "自拍", "头像",
            "小孩", "儿童", "婴儿", "宝宝", "孩子",
            "child", "children", "kid", "kids", "baby", "infant", "toddler"
        )
        return peopleKeywords.any { query.contains(it, ignoreCase = true) }
    }

    private fun removeTimeWords(query: String): String {
        val timeWords = listOf(
            "去年", "今年", "上个月", "本周", "上周", "今天", "昨天", "前天",
            "春天", "夏天", "秋天", "冬天"
        )
        var text = query
        for (word in timeWords) {
            text = text.replace(word, "")
        }
        return text.trim()
    }

    private val CHINESE_STOP_WORDS = listOf(
        "的", "了", "在", "是", "我", "有", "和", "就", "不", "都", "一", "把",
        "一个", "上面", "下面", "可以", "这个", "那个", "拍", "照片", "图片",
        "找", "搜索", "显示", "查看", "包含", "给我", "帮我"
    )

    private val ENGLISH_STOP_WORDS = listOf(
        "the", "a", "an", "is", "are", "was", "were", "be", "been", "being",
        "i", "you", "he", "she", "it", "we", "they", "my", "your", "his", "her",
        "its", "our", "their", "me", "him", "them", "us",
        "of", "in", "on", "at", "to", "for", "with", "about", "from", "by",
        "and", "or", "but", "so", "if", "because", "as",
        "show", "find", "search", "look", "see", "view", "display", "give", "help",
        "photo", "photos", "picture", "pictures", "image", "images"
    )
}
