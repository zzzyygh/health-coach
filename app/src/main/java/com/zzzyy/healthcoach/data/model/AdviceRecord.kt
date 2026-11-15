package com.zzzyy.healthcoach.data.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 一次生成过的健康建议（存下来备查）。
 *
 * 为什么值得存：建议本身会随数据和模型变化，几周后回头看
 * 「当时模型说了什么、我有没有照做」比只看今天这一条有用。
 * 而且换了模型后可以对比同一批数据下不同模型的判断差异。
 */
data class AdviceRecord(
    /** 生成日期 yyyy-MM-dd */
    val date: String,
    val timestamp: Long,
    /** 生成时用的模型，便于对比（如 deepseek-v4-flash / MiniMax-M3.1-Flash） */
    val model: String,
    val headline: String,
    val summary: String,
    val suggestions: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    /** 解析失败时保留原文，方便事后排查 */
    val rawText: String? = null,
    val parseFailed: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("date", date)
        put("timestamp", timestamp)
        put("model", model)
        put("headline", headline)
        put("summary", summary)
        put("suggestions", JSONArray(suggestions))
        put("warnings", JSONArray(warnings))
        rawText?.let { put("rawText", it) }
        put("parseFailed", parseFailed)
    }

    companion object {
        fun fromJson(json: JSONObject): AdviceRecord = AdviceRecord(
            date = json.optString("date"),
            timestamp = json.optLong("timestamp"),
            model = json.optString("model"),
            headline = json.optString("headline"),
            summary = json.optString("summary"),
            suggestions = json.optJSONArray("suggestions").toStringList(),
            warnings = json.optJSONArray("warnings").toStringList(),
            rawText = json.optString("rawText").takeIf { it.isNotBlank() },
            parseFailed = json.optBoolean("parseFailed", false),
        )

        private fun JSONArray?.toStringList(): List<String> {
            if (this == null) return emptyList()
            return buildList {
                for (i in 0 until length()) add(optString(i))
            }
        }

        fun listToJson(list: List<AdviceRecord>): JSONArray = JSONArray().apply {
            list.forEach { put(it.toJson()) }
        }

        fun listFromJson(array: JSONArray?): List<AdviceRecord> {
            if (array == null) return emptyList()
            return buildList {
                for (i in 0 until array.length()) {
                    array.optJSONObject(i)?.let { add(fromJson(it)) }
                }
            }
        }
    }
}
