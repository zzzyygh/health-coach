package com.zzzyy.healthcoach.data.advice

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 预置的模型服务商。
 *
 * 三家都走 **Anthropic 协议**（`POST {base}/messages`），所以客户端不用分叉。
 * DeepSeek 官方同时提供 OpenAI 与 Anthropic 两种端点
 * （`api.deepseek.com` 与 `api.deepseek.com/anthropic`），这里用后者。
 */
enum class ModelPreset(
    val id: String,
    val label: String,
    val baseUrl: String,
    val model: String,
    val hint: String,
) {
    MINIMAX(
        id = "minimax",
        label = "MiniMax",
        baseUrl = "https://agent.minimax.cn/mavis/api/v1/llm/v1",
        model = "MiniMax-M3.1-Flash",
        hint = "与本机 Mavis 同源，默认",
    ),
    DEEPSEEK(
        id = "deepseek",
        label = "DeepSeek",
        baseUrl = "https://api.deepseek.com/anthropic/v1",
        model = "deepseek-v4-flash",
        hint = "官方 Anthropic 端点；也可改成 deepseek-v4-pro 换更强模型",
    ),
    CUSTOM(
        id = "custom",
        label = "自定义",
        baseUrl = "",
        model = "",
        hint = "填任意 Anthropic 兼容端点",
    );

    companion object {
        fun ofId(id: String?): ModelPreset =
            entries.firstOrNull { it.id == id } ?: MINIMAX
    }
}

/** 模型接入配置（用户在设置页填）。 */
data class ModelConfig(
    val presetId: String = ModelPreset.MINIMAX.id,
    val baseUrl: String = ModelPreset.MINIMAX.baseUrl,
    val model: String = ModelPreset.MINIMAX.model,
    val apiKey: String = "",
) {
    val isConfigured: Boolean get() = apiKey.isNotBlank() && baseUrl.isNotBlank()
    val preset: ModelPreset get() = ModelPreset.ofId(presetId)

    companion object {
        const val DEFAULT_BASE_URL = "https://agent.minimax.cn/mavis/api/v1/llm/v1"
        const val DEFAULT_MODEL = "MiniMax-M3.1-Flash"
    }
}

/**
 * 保存模型配置。
 *
 * **按预设分开存**：切到 DeepSeek 再切回 MiniMax，各自的 Key 都还在，
 * 不会因为换了一家就把上一家的 Key 覆盖掉。
 */
class ModelConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("model_config", Context.MODE_PRIVATE)

    /** 当前生效的预设 id。 */
    fun activePresetId(): String =
        prefs.getString(KEY_ACTIVE, ModelPreset.MINIMAX.id) ?: ModelPreset.MINIMAX.id

    /** 读当前生效那套配置。 */
    fun load(): ModelConfig = load(activePresetId())

    fun load(presetId: String): ModelConfig {
        val preset = ModelPreset.ofId(presetId)
        val baseKey = "${preset.id}_$KEY_BASE"
        val modelKey = "${preset.id}_$KEY_MODEL"
        val keyKey = "${preset.id}_$KEY_API_KEY"
        // 自定义没有默认值，其余预设回落成自己的官方配置
        val defaultBase = preset.baseUrl.ifBlank { ModelConfig.DEFAULT_BASE_URL }
        val defaultModel = preset.model.ifBlank { ModelConfig.DEFAULT_MODEL }
        return ModelConfig(
            presetId = preset.id,
            baseUrl = prefs.getString(baseKey, defaultBase) ?: defaultBase,
            model = prefs.getString(modelKey, defaultModel) ?: defaultModel,
            // 加密存储；老版本的明文会被直接读出来，下次保存时自动升级成密文
            apiKey = com.zzzyy.healthcoach.data.security.SecureStore.decrypt(prefs.getString(keyKey, "")),
        )
    }

    fun save(config: ModelConfig) {
        val id = ModelPreset.ofId(config.presetId).id
        prefs.edit()
            .putString(KEY_ACTIVE, id)
            .putString("${id}_$KEY_BASE", config.baseUrl)
            .putString("${id}_$KEY_MODEL", config.model)
            .putString("${id}_$KEY_API_KEY", com.zzzyy.healthcoach.data.security.SecureStore.encrypt(config.apiKey))
            .apply()
        android.util.Log.i(
            "HealthCoach.Model",
            "模型配置已保存：预设=$id 模型=${config.model} base=${config.baseUrl} " +
                "key=${if (config.apiKey.isBlank()) "未填" else "已填(${config.apiKey.length}字符)"}",
        )
    }

    /**
     * 把历史上以明文保存的 Key 就地升级成密文。
     *
     * 加密只在写入时生效，老数据不会自己变 —— 不主动迁移的话，
     * 升级后 prefs 里依然是明文，等于没改。
     */
    fun migrateToEncrypted() {
        var changed = false
        val editor = prefs.edit()
        ModelPreset.entries.forEach { preset ->
            val key = "${preset.id}_$KEY_API_KEY"
            val raw = prefs.getString(key, "") ?: ""
            if (raw.isNotEmpty() && !com.zzzyy.healthcoach.data.security.SecureStore.isEncrypted(raw)) {
                editor.putString(key, com.zzzyy.healthcoach.data.security.SecureStore.encrypt(raw))
                changed = true
            }
        }
        if (changed) {
            editor.apply()
            android.util.Log.i("HealthCoach.Secure", "模型 Key 已升级为加密存储")
        }
    }

    /** 只切换生效预设，不改动各预设已存的配置。 */
    fun switchTo(presetId: String) {
        prefs.edit().putString(KEY_ACTIVE, ModelPreset.ofId(presetId).id).apply()
    }

    private companion object {
        const val KEY_ACTIVE = "active_preset"
        const val KEY_BASE = "base_url"
        const val KEY_MODEL = "model"
        const val KEY_API_KEY = "api_key"
    }
}

/** 一条健康建议。 */
data class Advice(
    val headline: String,
    val summary: String,
    val items: List<String>,
    /** 需要留意的信号 */
    val warnings: List<String> = emptyList(),
    /** 模型没有返回合法 JSON 时的原文 */
    val rawText: String? = null,
    /** 结构化解析是否失败（界面据此提示重试，而不是直接显示原始 JSON） */
    val parseFailed: Boolean = false,
)

/**
 * 调模型生成健康建议。
 *
 * 走 Anthropic Messages 协议（POST {base}/messages），
 * 与本机 Mavis 使用的 provider 一致。
 *
 * ⚠️ 隐私边界：只把**日聚合**数据和用户反馈发出去，
 * 逐条的心率原始记录、睡眠分段明细都留在手机本地。
 */
class AdviceClient(
    private val config: ModelConfig,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build(),
) {

    suspend fun generate(prompt: String): Advice = withContext(Dispatchers.IO) {
        if (!config.isConfigured) throw IOException("尚未配置模型 API Key")

        val body = JSONObject()
            .put("model", config.model)
            // 2000 太小：中文建议 + JSON 结构会被截断，导致解析失败、界面直接吐出原文
            .put("max_tokens", 4096)
            .put("system", SYSTEM_PROMPT)
            .put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", prompt)
                )
            )

        val request = Request.Builder()
            .url("${config.baseUrl.trimEnd('/')}/messages")
            .header("x-api-key", config.apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("模型接口返回 ${response.code}：${text.take(200)}")
            }
            val json = JSONObject(text)
            val content = json.optJSONArray("content") ?: JSONArray()
            val sb = StringBuilder()
            for (i in 0 until content.length()) {
                val part = content.optJSONObject(i) ?: continue
                if (part.optString("type") == "text") sb.append(part.optString("text"))
            }
            val reply = sb.toString().trim()
            if (reply.isBlank()) throw IOException("模型没有返回内容")

            val truncated = json.optString("stop_reason") == "max_tokens"
            android.util.Log.i(
                "HealthCoach.Advice",
                "模型返回 ${reply.length} 字，stop_reason=${json.optString("stop_reason")}" +
                    if (truncated) "（输出被截断）" else "",
            )
            parseAdvice(reply, truncated)
        }
    }

    /**
     * 解析模型返回的 JSON。
     *
     * 解析失败时**不能把原文直接当正文显示** —— 用户会看到一坨 {"headline":...}
     * 的原始 JSON，既没排版也没总结，等于功能没生效。
     * 这里改成返回「解析失败」的标记，界面据此提示重试并折叠原文。
     */
    private fun parseAdvice(reply: String, truncated: Boolean): Advice {
        val jsonText = reply.substringAfter("```json", "")
            .substringAfter("```", "")
            .trim()
            .ifEmpty { reply.trim() }
        // 去掉可能存在的说明性前缀，取第一段完整 JSON
        val start = jsonText.indexOf('{')
        val end = jsonText.lastIndexOf('}')
        if (start >= 0 && end > start) {
            try {
                val obj = JSONObject(jsonText.substring(start, end + 1))
                val items = mutableListOf<String>()
                obj.optJSONArray("suggestions")?.let { array ->
                    for (i in 0 until array.length()) items.add(array.optString(i))
                }
                val warnings = mutableListOf<String>()
                obj.optJSONArray("warnings")?.let { array ->
                    for (i in 0 until array.length()) warnings.add(array.optString(i))
                }
                return Advice(
                    headline = obj.optString("headline", "今日健康小结"),
                    summary = obj.optString("summary"),
                    items = items,
                    warnings = warnings,
                )
            } catch (e: Exception) {
                // 落到下面的纯文本兜底
            }
        }
        android.util.Log.w(
            "HealthCoach.Advice",
            "解析失败（截断=$truncated），原文长度 ${reply.length}",
        )
        return Advice(
            headline = if (truncated) "输出被截断" else "这次没能解析成结构化建议",
            summary = if (truncated) {
                "模型返回被长度上限截断了，JSON 不完整。可以点「重新生成」再试一次。"
            } else {
                "模型没有按要求的格式返回，可以点「重新生成」再试一次，或换个模型。"
            },
            items = emptyList(),
            warnings = emptyList(),
            rawText = reply,
            parseFailed = true,
        )
    }

    companion object {
        private val SYSTEM_PROMPT = """
你是个人健康助理。你会收到用户最近两周的**日聚合**健康数据（步数、心率、血氧、
睡眠时长与深睡分钟、站立小时）和用户自己填写的每日主观反馈。

要求：
1. 只根据给出的数据说话，不要编造没提到的指标或数值。
2. 指出数据里的**趋势**（连续多天上升/下降）比单日数值更重要。
3. 把主观反馈和客观数据对照着看：两者一致时说明状态稳定；矛盾时点出来。
4. 建议要具体可执行（几点睡、走多少步、什么时候测血压），不要空泛。
5. 涉及可能需要就医的信号（持续心率异常、血氧持续偏低、长期严重失眠等），
   要明确提醒用户考虑就医，但不下诊断结论。
6. 全部用中文，输出严格的 JSON，不要有多余文字。

输出格式：
{"headline":"不超过 20 字的结论","summary":"两三句话的整体判断",
 "suggestions":["建议1","建议2","建议3"],"warnings":["需要留意的信号，没有就空数组"]}

**长度硬性限制（超了会被截断，务必遵守）**：
- headline ≤ 20 字
- summary ≤ 120 字（两到三句，只说最重要的判断，不要罗列数据）
- suggestions 3~5 条，每条 ≤ 45 字，必须是能直接执行的动作
- warnings 0~3 条，每条 ≤ 45 字
- **全部内容加起来不超过 600 字**；宁可少说一条建议，也不要写长
- 不要在 JSON 前后添加任何解释、标题或 Markdown 代码块标记
""".trimIndent()
    }
}
