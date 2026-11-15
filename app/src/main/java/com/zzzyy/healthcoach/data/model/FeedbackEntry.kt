package com.zzzyy.healthcoach.data.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 用户的主观反馈。
 *
 * 这是健康判断里不可替代的一半：手环能测到心率、血氧、睡眠，
 * 但「今天累不累」「哪里不舒服」「睡得好不好」只能自己说。
 */
data class FeedbackEntry(
    /** yyyy-MM-dd */
    val date: String,
    val timestamp: Long,
    /**
     * 来源。
     *
     *   MORNING —— 早上那次「睡醒打分」（每天只保留一条，重复提交会替换）
     *   MANUAL  —— 反馈页随手记的（可以一天多条，只追加不覆盖）
     */
    val source: String = SOURCE_MANUAL,
    /** 睡眠质量 1~5 */
    val sleepQuality: Int? = null,
    /** 精力 1~5 */
    val energy: Int? = null,
    /** 心情 1~5 */
    val mood: Int? = null,
    /** 肌肉酸痛 1~5 */
    val soreness: Int? = null,
    /** 主观症状标签 */
    val symptoms: List<String> = emptyList(),
    /** 自由备注 */
    val note: String = "",
) {
    /** 这条记录是否来自早上那次打卡。 */
    val isMorningCheckIn: Boolean get() = source == SOURCE_MORNING

    fun toJson(): JSONObject = JSONObject().apply {
        put("date", date)
        put("timestamp", timestamp)
        put("source", source)
        sleepQuality?.let { put("sleepQuality", it) }
        energy?.let { put("energy", it) }
        mood?.let { put("mood", it) }
        soreness?.let { put("soreness", it) }
        put("symptoms", JSONArray(symptoms))
        put("note", note)
    }

    companion object {
        const val SOURCE_MORNING = "morning"
        const val SOURCE_MANUAL = "manual"

        /**
         * 推断来源，兼容加入 source 字段之前的老数据。
         *
         * 老记录没有 source，但「带睡眠质量」这种形态几乎只可能来自早上那次打卡；
         * 全部当成手动记录的话，用户重复打卡时查不到原来那条，就会重复堆记录。
         */
        private fun inferSource(json: JSONObject): String {
            val raw = json.optString("source")
            if (raw == SOURCE_MORNING || raw == SOURCE_MANUAL) return raw
            val hasSleepQuality = json.optInt("sleepQuality", 0) > 0
            return if (hasSleepQuality) SOURCE_MORNING else SOURCE_MANUAL
        }

        fun fromJson(json: JSONObject): FeedbackEntry {
            val symptoms = mutableListOf<String>()
            json.optJSONArray("symptoms")?.let { array ->
                for (i in 0 until array.length()) symptoms.add(array.optString(i))
            }
            return FeedbackEntry(
                date = json.optString("date"),
                timestamp = json.optLong("timestamp"),
                source = inferSource(json),
                sleepQuality = json.optInt("sleepQuality").takeIf { it > 0 },
                energy = json.optInt("energy").takeIf { it > 0 },
                mood = json.optInt("mood").takeIf { it > 0 },
                soreness = json.optInt("soreness").takeIf { it > 0 },
                symptoms = symptoms,
                note = json.optString("note"),
            )
        }
    }
}

/** 常见症状标签，界面上直接提供快捷选项。 */
object SymptomTags {
    val ALL = listOf(
        "头晕", "头痛", "胸闷", "心悸",
        "乏力", "嗜睡", "失眠",
        "肌肉酸痛", "关节痛",
        "胃部不适", "食欲差",
        "畏寒", "发热",
        "视力模糊", "耳鸣",
    )
}
