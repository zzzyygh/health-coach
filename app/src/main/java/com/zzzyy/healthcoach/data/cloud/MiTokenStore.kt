package com.zzzyy.healthcoach.data.cloud

import android.content.Context
import android.content.SharedPreferences
import com.zzzyy.healthcoach.data.security.SecureStore

/** 小米账号凭证。所有字段都来自一次扫码登录。 */
data class MiToken(
    val userId: String = "",
    val cUserId: String = "",
    val serviceToken: String = "",
    val ssecurity: String = "",
    val passToken: String = "",
    val deviceId: String = "",
) {
    val isUsable: Boolean
        get() = cUserId.isNotBlank() && serviceToken.isNotBlank() && ssecurity.isNotBlank()
}

/**
 * 凭证持久化。
 *
 * 三个**真正的凭据**（serviceToken / ssecurity / passToken）用 [SecureStore] 加密后
 * 再写进 SharedPreferences —— 明文状态下 root 或备份都能直接读出来，
 * 而 passToken 是长期有效的，泄漏等于账号被别人接管。
 *
 * userId / cUserId / deviceId 不是凭据（只是标识），保持明文以便排查。
 */
class MiTokenStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("mi_token", Context.MODE_PRIVATE)

    fun load(): MiToken = MiToken(
        userId = prefs.getString(KEY_USER_ID, "") ?: "",
        cUserId = prefs.getString(KEY_C_USER_ID, "") ?: "",
        serviceToken = SecureStore.decrypt(prefs.getString(KEY_SERVICE_TOKEN, "")),
        ssecurity = SecureStore.decrypt(prefs.getString(KEY_SSECURITY, "")),
        passToken = SecureStore.decrypt(prefs.getString(KEY_PASS_TOKEN, "")),
        deviceId = prefs.getString(KEY_DEVICE_ID, "") ?: "",
    )

    fun save(token: MiToken) {
        prefs.edit()
            .putString(KEY_USER_ID, token.userId)
            .putString(KEY_C_USER_ID, token.cUserId)
            .putString(KEY_SERVICE_TOKEN, SecureStore.encrypt(token.serviceToken))
            .putString(KEY_SSECURITY, SecureStore.encrypt(token.ssecurity))
            .putString(KEY_PASS_TOKEN, SecureStore.encrypt(token.passToken))
            .putString(KEY_DEVICE_ID, token.deviceId)
            .apply()
    }

    /** 把明文的 serviceToken / ssecurity / passToken 就地升级成密文。 */
    fun migrateToEncrypted() {
        val pairs = listOf(
            KEY_SERVICE_TOKEN,
            KEY_SSECURITY,
            KEY_PASS_TOKEN,
        )
        val editor = prefs.edit()
        var changed = false
        pairs.forEach { key ->
            val raw = prefs.getString(key, "") ?: ""
            if (raw.isNotEmpty() && !SecureStore.isEncrypted(raw)) {
                editor.putString(key, SecureStore.encrypt(raw))
                changed = true
            }
        }
        if (changed) {
            editor.apply()
            android.util.Log.i("HealthCoach.Secure", "小米凭证已升级为加密存储")
        }
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    /**
     * 从一份 JSON 文本导入凭证（字段名与本类的 MiToken 一致）。
     *
     * 用途：电脑上已经登录过一次时，可以把 token.json 推到手机私有目录，
     * app 启动时自动导入，避免手工粘贴 367 字符的 passToken。
     */
    fun importFromJson(text: String): Boolean {
        return try {
            val json = org.json.JSONObject(text)
            val token = MiToken(
                userId = json.optString("user_id"),
                cUserId = json.optString("c_user_id"),
                serviceToken = json.optString("service_token"),
                ssecurity = json.optString("ssecurity"),
                passToken = json.optString("pass_token"),
                deviceId = json.optString("device_id"),
            )
            if (!token.isUsable) return false
            save(token)
            true
        } catch (e: Exception) {
            false
        }
    }

    private companion object {
        const val KEY_USER_ID = "user_id"
        const val KEY_C_USER_ID = "c_user_id"
        const val KEY_SERVICE_TOKEN = "service_token"
        const val KEY_SSECURITY = "ssecurity"
        const val KEY_PASS_TOKEN = "pass_token"
        const val KEY_DEVICE_ID = "device_id"
    }
}
