package com.zzzyy.healthcoach.data.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 一次「干预」记录 —— 我今天做了什么、吃了什么。
 *
 * 为什么要单独建模：手环只能测结果（睡眠、心率、恢复），
 * 测不到原因。补充剂、咖啡、酒精、运动这些**主动行为**才是可调的变量。
 * 把它们记下来，才可能回答「补了 D3 之后，睡眠和精力有没有变化」。
 *
 * 观察这类关系需要**足够长的窗口**：维生素 D 的血药浓度变化以周计，
 * 所以界面上要提示「至少连续几周才看得出趋势」，避免几天没效果就放弃。
 */
data class InterventionEntry(
    /** yyyy-MM-dd */
    val date: String,
    /** 名称，如「维生素 D3」 */
    val name: String,
    /** 剂量数值，如 4000；可为空（比如「晒太阳 20 分钟」没有剂量） */
    val dose: Double? = null,
    /** 单位，如 IU / mg / 分钟 / 杯 */
    val unit: String = "",
    /** 备注，如「随餐服用」 */
    val note: String = "",
    val timestamp: Long = 0L,
) {
    /** 展示用：`维生素 D3 4000 IU` */
    val display: String
        get() = buildString {
            append(name)
            val d = dose
            if (d != null && d > 0) {
                append(' ')
                append(if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString())
                if (unit.isNotBlank()) append(' ').append(unit)
            } else if (unit.isNotBlank()) {
                append(' ').append(unit)
            }
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("date", date)
        put("name", name)
        dose?.let { put("dose", it) }
        put("unit", unit)
        put("note", note)
        put("timestamp", timestamp)
    }

    companion object {
        fun fromJson(json: JSONObject) = InterventionEntry(
            date = json.optString("date"),
            name = json.optString("name"),
            dose = json.optDouble("dose", 0.0).takeIf { it > 0 },
            unit = json.optString("unit"),
            note = json.optString("note"),
            timestamp = json.optLong("timestamp"),
        )

        fun listToJson(list: List<InterventionEntry>): JSONArray = JSONArray().apply {
            list.forEach { put(it.toJson()) }
        }

        fun listFromJson(array: JSONArray?): List<InterventionEntry> {
            if (array == null) return emptyList()
            return buildList {
                for (i in 0 until array.length()) {
                    array.optJSONObject(i)?.let { add(fromJson(it)) }
                }
            }
        }
    }
}

/**
 * 常见干预的快捷预设。
 *
 * 只放最常见的几类，其余用「自定义」。关键是 a 点就填好名称和单位，
 * 不必每次都手打「维生素 D3」和「IU」。
 */
object InterventionPresets {

    data class Preset(
        val name: String,
        val defaultDose: Double?,
        val unit: String,
        /** 观察这类干预通常需要多久才看得出趋势 */
        val observeWeeks: Int,
    )

    val ALL = listOf(
        Preset("维生素 D3", 4000.0, "IU", 8),
        Preset("鱼油 / Omega-3", 1000.0, "mg", 8),
        Preset("镁", 300.0, "mg", 4),
        Preset("锌", 15.0, "mg", 6),
        Preset("咖啡", 1.0, "杯", 2),
        Preset("酒精", 0.0, "杯", 2),
        Preset("晒太阳", 20.0, "分钟", 4),
        Preset("运动", 30.0, "分钟", 4),
        Preset("午睡", 20.0, "分钟", 3),
    )

    /** 有剂量单位的预设，用于自动填单位。 */
    fun unitOf(name: String): String =
        ALL.firstOrNull { it.name == name }?.unit ?: ""

    fun presetOf(name: String): Preset? = ALL.firstOrNull { it.name == name }
}
