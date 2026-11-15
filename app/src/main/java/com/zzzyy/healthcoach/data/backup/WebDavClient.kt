package com.zzzyy.healthcoach.data.backup

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * WebDAV 备份配置。
 *
 * 为什么走 WebDAV 而不是自己写服务端：群晖、威联通、Nextcloud、坚果云都自带，
 * 你只要填地址和账号，不用在 NAS 上部署任何东西。
 */
data class WebDavConfig(
    /** 例如 https://nas.example.com:5006/health 或 https://dav.jianguoyun.com/dav */
    val baseUrl: String = "",
    val username: String = "",
    val password: String = "",
    /** 子目录，会自动创建 */
    val folder: String = "health-coach",
    /** 每天第一次打开时自动备份 */
    val autoBackup: Boolean = true,
) {
    val isConfigured: Boolean get() = baseUrl.isNotBlank()

    /** 目录的完整远端地址（去掉多余斜杠）。 */
    fun folderUrl(): String {
        val base = baseUrl.trim().trimEnd('/')
        val sub = folder.trim().trim('/')
        return if (sub.isEmpty()) base else "$base/$sub"
    }

    fun fileUrl(fileName: String): String = "${folderUrl()}/$fileName"
}

class WebDavConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("webdav_config", Context.MODE_PRIVATE)

    fun load(): WebDavConfig = WebDavConfig(
        baseUrl = prefs.getString(KEY_BASE, "") ?: "",
        username = prefs.getString(KEY_USER, "") ?: "",
        password = com.zzzyy.healthcoach.data.security.SecureStore.decrypt(prefs.getString(KEY_PASS, "")),
        folder = prefs.getString(KEY_FOLDER, "health-coach") ?: "health-coach",
        autoBackup = prefs.getBoolean(KEY_AUTO, true),
    )

    fun save(config: WebDavConfig) {
        prefs.edit()
            .putString(KEY_BASE, config.baseUrl.trim())
            .putString(KEY_USER, config.username)
            .putString(KEY_PASS, com.zzzyy.healthcoach.data.security.SecureStore.encrypt(config.password))
            .putString(KEY_FOLDER, config.folder.trim())
            .putBoolean(KEY_AUTO, config.autoBackup)
            .apply()
        // 只记录是否配置了密码，绝不打印密码本身
        android.util.Log.i(
            "HealthCoach.WebDav",
            "配置已保存：base=${config.baseUrl} folder=${config.folder} " +
                "user=${config.username.ifBlank { "匿名" }} 密码=${if (config.password.isBlank()) "未填" else "已填"} " +
                "自动备份=${config.autoBackup}",
        )
    }

    /** 把明文密码就地升级成密文。 */
    fun migrateToEncrypted() {
        val raw = prefs.getString(KEY_PASS, "") ?: ""
        if (raw.isNotEmpty() && !com.zzzyy.healthcoach.data.security.SecureStore.isEncrypted(raw)) {
            prefs.edit()
                .putString(KEY_PASS, com.zzzyy.healthcoach.data.security.SecureStore.encrypt(raw))
                .apply()
            android.util.Log.i("HealthCoach.Secure", "WebDAV 密码已升级为加密存储")
        }
    }

    private companion object {
        const val KEY_BASE = "base_url"
        const val KEY_USER = "username"
        const val KEY_PASS = "password"
        const val KEY_FOLDER = "folder"
        const val KEY_AUTO = "auto_backup"
    }
}

/**
 * 极简 WebDAV 客户端：只做「建目录 + PUT 上传」。
 *
 * 不做 PROPFIND 列目录，也不做删除 —— 备份只需要写，读可以等真有换机需求再加，
 * 少一个功能就少一处出错的地方。
 */
class WebDavClient(
    private val config: WebDavConfig,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build(),
) {

    private fun auth(): String = Credentials.basic(config.username, config.password)

    /**
     * 用 PROPFIND(depth=0) 判断某个路径是否真的存在。
     *
     * 必须靠这个来验证，不能靠 MKCOL 的返回码猜：
     * MKCOL 返回 405 既可能是「目录已存在」，也可能是「该位置不允许创建子集合」
     * （比如你把根目录当成了 baseUrl）。之前把 405 一律当成"已存在"，
     * 结果目录其实没建成，后面的 PUT 就报 405 Method Not Allowed。
     */
    private fun exists(url: String): Boolean = try {
        val request = Request.Builder()
            .url("$url/")
            .method("PROPFIND", "".toRequestBody("application/xml".toMediaType()))
            .header("Authorization", auth())
            .header("Depth", "0")
            .build()
        client.newCall(request).execute().use { response ->
            val ok = response.code in intArrayOf(200, 207)
            android.util.Log.d("HealthCoach.WebDav", "PROPFIND $url → ${response.code}（存在=$ok）")
            ok
        }
    } catch (e: Exception) {
        android.util.Log.w("HealthCoach.WebDav", "PROPFIND 异常：${e.message}")
        false
    }

    /** 从浅到深逐级创建目录（WebDAV 的 MKCOL 不会递归创建）。 */
    suspend fun ensureFolder(): Boolean = withContext(Dispatchers.IO) {
        val base = config.baseUrl.trim().trimEnd('/')
        val segments = config.folder.trim().trim('/').split('/').filter { it.isNotBlank() }
        var current = base
        for (segment in segments) {
            current = "$current/$segment"
            if (exists(current)) {
                android.util.Log.d("HealthCoach.WebDav", "目录已存在：$current")
                continue
            }
            val code = mkcol(current)
            android.util.Log.i("HealthCoach.WebDav", "MKCOL $current → $code")
            if (code !in intArrayOf(201, 200, 204)) {
                // 建不了就不要再往下走，把真实原因暴露出来
                android.util.Log.w(
                    "HealthCoach.WebDav",
                    "创建目录失败：$current（HTTP $code）。" +
                        "常见原因：baseUrl 里少了共享文件夹，例如群晖应为 https://NAS:5006/共享文件夹名",
                )
                return@withContext false
            }
        }
        // 最终确认一次，避免上面的 MKCOL 假成功
        exists(config.folderUrl())
    }

    private fun mkcol(url: String): Int = try {
        val request = Request.Builder()
            .url(url)
            .method("MKCOL", null)
            .header("Authorization", auth())
            .build()
        client.newCall(request).execute().use { it.code }
    } catch (e: Exception) {
        android.util.Log.w("HealthCoach.WebDav", "MKCOL 异常：${e.message}")
        -1
    }

    /** 上传一个文件，返回远端完整路径。 */
    suspend fun upload(fileName: String, content: String): String = withContext(Dispatchers.IO) {
        val url = config.fileUrl(fileName)
        val request = Request.Builder()
            .url(url)
            .put(content.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Authorization", auth())
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val hint = when (response.code) {
                    405 -> "（服务端不接受该位置的 PUT。多半是目录不存在或 baseUrl 少了一层共享文件夹）"
                    401, 403 -> "（认证失败或没有写权限）"
                    404 -> "（路径不存在）"
                    409 -> "（父目录不存在）"
                    507 -> "（NAS 空间不足）"
                    else -> ""
                }
                throw IOException(
                    "WebDAV 上传失败 HTTP ${response.code}$hint\n" +
                        "PUT $url\n${body.take(200)}"
                )
            }
            android.util.Log.i(
                "HealthCoach.WebDav",
                "上传成功：$url（${content.length} 字符）HTTP ${response.code}",
            )
            url
        }
    }

    /** 用一次 MKCOL 探活，确认地址和账号能用。 */
    suspend fun testConnection(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (!config.isConfigured) throw IOException("还没有填 WebDAV 地址")
            val base = config.baseUrl.trim().trimEnd('/')
            // 对根目录发一次 PROPFIND（depth=0）：能通就说明认证和地址都对
            val request = Request.Builder()
                .url(base)
                .method("PROPFIND", "".toRequestBody("application/xml".toMediaType()))
                .header("Authorization", auth())
                .header("Depth", "0")
                .build()
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    in 200..299 -> "连接正常（HTTP ${response.code}）"
                    401, 403 -> throw IOException("认证失败（HTTP ${response.code}），检查用户名或密码")
                    404 -> throw IOException("地址不存在（HTTP 404），检查路径")
                    else -> throw IOException("服务端返回 HTTP ${response.code}")
                }
            }
        }
    }
}
