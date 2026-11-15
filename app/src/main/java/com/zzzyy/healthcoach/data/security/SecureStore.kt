package com.zzzyy.healthcoach.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 敏感字段的加密存储（API Key、WebDAV 密码、小米凭证）。
 *
 * ## 为什么需要
 *
 * `SharedPreferences` 存在 app 私有目录里，**默认权限只有本 app 可读**，
 * 所以普通使用下别人拿不到。但它是**明文**的：
 *   - 手机 root 后可以直接 cat 出来；
 *   - 云备份/本地备份可能把它一起打包走；
 *   - 设备丢失且已解锁时能被读。
 *
 * 这些凭据一旦泄漏，代价是「别人拿你的钱调模型」或「别人读你的健康数据」，
 * 所以值得多一层加密。
 *
 * ## 做法
 *
 * 用 **Android Keystore** 生成一枚 AES-256 密钥，密钥本身存在 TEE/StrongBox 里、
 * **永远不出设备、不可导出**；只把密文写进 SharedPreferences。
 * 即使有人拿到了 prefs 文件，没有那枚密钥也解不开。
 *
 * 格式：`enc:v1:` + Base64(IV + 密文)，AES/GCM/NoPadding（带认证，防篡改）。
 *
 * ## 兼容
 *
 * 读取时如果值不带 `enc:v1:` 前缀，说明是老版本的明文，
 * 直接返回原文（这样升级不会丢配置），下次写入时自动加密。
 */
object SecureStore {

    private const val TAG = "HealthCoach.Secure"
    private const val ALIAS = "health_coach_secret_v1"
    private const val PREFIX = "enc:v1:"
    private const val IV_SIZE = 12
    private const val TAG_BITS = 128

    /** 值是否已加密。 */
    fun isEncrypted(value: String?): Boolean = value?.startsWith(PREFIX) == true

    /** 加密；失败时退化成明文（宁可功能可用，也不要因为加密失败把配置弄丢）。 */
    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return plain
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, obtainKey())
            val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val combined = cipher.iv + encrypted
            PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "加密失败，退回明文存储：${e.javaClass.simpleName}")
            plain
        }
    }

    /** 解密；明文原样返回（兼容旧数据），解不开返回空串而不是崩溃。 */
    fun decrypt(stored: String?): String {
        if (stored.isNullOrEmpty()) return ""
        if (!stored.startsWith(PREFIX)) return stored
        return try {
            val combined = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            if (combined.size <= IV_SIZE) return ""
            val iv = combined.copyOfRange(0, IV_SIZE)
            val body = combined.copyOfRange(IV_SIZE, combined.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, obtainKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        } catch (e: Exception) {
            // 密钥被清（换机/重置）或密文损坏 → 只能当没有
            Log.e(TAG, "解密失败（可能密钥已失效）：${e.javaClass.simpleName}")
            ""
        }
    }

    private fun obtainKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
