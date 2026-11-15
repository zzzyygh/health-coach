package com.zzzyy.healthcoach.data.backup

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 数据备份：把 App 私有目录里的几个 JSON 打包上传到 WebDAV。
 *
 * 设计取舍：
 *   - **只传日聚合**，不传 522MB 的数据库副本和逐条心率明细；
 *   - 每天一个文件（`backup-yyyy-MM-dd.json`），当天重复备份就覆盖，
 *     不会因为一天点几次而堆出一堆文件；
 *   - 全量快照而不是增量：数据总共才一百多 KB，增量省不下什么，
 *     却要引入版本比对和合并冲突，不值。
 */
class HealthBackup(private val context: Context) {

    data class Result(
        val ok: Boolean,
        val message: String,
        val remotePath: String? = null,
        val bytes: Int = 0,
    )

    private val healthDir = File(context.filesDir, "health")

    /** 打包成一份完整的备份 JSON。 */
    fun buildPayload(): String {
        val payload = JSONObject().apply {
            put("app", "health-coach")
            put("payloadVersion", 1)
            put("exportedAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.CHINA).format(Date()))
            put("dataVersion", readDataVersion())
            put("days", readArray("days.json"))
            put("feedback", readArray("feedback.json"))
            put("interventions", readArray("interventions.json"))
            put("advices", readArray("advices.json"))
        }
        return payload.toString()
    }

    /**
     * days.json 是 `{version, days}` 结构，其余是裸数组。
     * 这里统一取出数组，让备份格式扁平（恢复时不需要再判断两种结构）。
     */
    private fun readArray(name: String): JSONArray {
        val file = File(healthDir, name)
        if (!file.exists()) return JSONArray()
        return try {
            val text = file.readText().trim()
            when {
                text.startsWith("[") -> JSONArray(text)
                else -> JSONObject(text).optJSONArray("days") ?: JSONArray()
            }
        } catch (e: Exception) {
            android.util.Log.e("HealthCoach.Backup", "读取 $name 失败：${e.message}", e)
            JSONArray()
        }
    }

    private fun readDataVersion(): Int = try {
        val file = File(healthDir, "days.json")
        if (!file.exists()) 0 else {
            val text = file.readText().trim()
            if (text.startsWith("[")) 1 else JSONObject(text).optInt("version", 0)
        }
    } catch (e: Exception) {
        0
    }

    /** 执行一次备份。 */
    suspend fun backupTo(config: WebDavConfig): Result {
        if (!config.isConfigured) {
            return Result(false, "还没有配置 WebDAV 地址")
        }
        val payload = buildPayload()
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
        val fileName = "backup-$day.json"
        return try {
            val client = WebDavClient(config)
            if (!client.ensureFolder()) {
                return Result(false, "无法创建远端目录，检查地址与权限")
            }
            val remote = client.upload(fileName, payload)
            // 本地也留一份，方便确认「上次备份的内容是什么」
            File(healthDir, "last_backup.json").writeText(payload)
            Result(true, "已备份到 $remote", remote, payload.length)
        } catch (e: Exception) {
            android.util.Log.e("HealthCoach.Backup", "备份失败：${e.message}", e)
            Result(false, e.message ?: e.javaClass.simpleName)
        }
    }
}
