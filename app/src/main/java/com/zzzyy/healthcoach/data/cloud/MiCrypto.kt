package com.zzzyy.healthcoach.data.cloud

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * 小米云端接口的加解密层。
 *
 * 移植自 `.work/mifit-ref-1793863039/src/mi_fitness_sync/activity/crypto.py`，
 * 与 Python 参考实现逐行对照，保证请求签名和响应解密的结果完全一致。
 *
 * 关键公式：
 *   nonce        = base64(8 字节随机 ‖ 4 字节大端分钟数)
 *   signedNonce  = base64(SHA256(b64decode(ssecurity) ‖ b64decode(nonce)))
 *   RC4 key      = b64decode(signedNonce)，初始化后丢弃前 1024 字节
 *   signature    = base64(SHA1("METHOD&/path&k=v&…&signedNonce"))
 */
object MiCrypto {

    private val secureRandom = SecureRandom()

    fun b64encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    fun b64decode(text: String): ByteArray = Base64.getDecoder().decode(text)

    /** 与 Python 的 `generate_nonce(time_diff_ms)` 等价。 */
    fun generateNonce(timeDiffMs: Long = 0L): String {
        val random = ByteArray(8)
        secureRandom.nextBytes(random)
        val minutes = ((System.currentTimeMillis() + timeDiffMs) / 60_000L).toInt()
        val payload = ByteArray(12)
        System.arraycopy(random, 0, payload, 0, 8)
        payload[8] = (minutes ushr 24).toByte()
        payload[9] = (minutes ushr 16).toByte()
        payload[10] = (minutes ushr 8).toByte()
        payload[11] = minutes.toByte()
        return b64encode(payload)
    }

    /** `base64(SHA256(b64decode(ssecurity) ‖ b64decode(nonce)))`。 */
    fun signedNonce(ssecurity: String, nonce: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(b64decode(ssecurity))
        digest.update(b64decode(nonce))
        return b64encode(digest.digest())
    }

    /** `base64(SHA1(parts.join("&")))`。 */
    fun sha1Signature(parts: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-1")
        // 注意：joinToString 的第一个参数是 prefix，separator 要用具名参数指定
        return b64encode(digest.digest(parts.joinToString(separator = "&").toByteArray(Charsets.UTF_8)))
    }

    /** 与 Python `build_signature` 等价：METHOD、路径、按 key 排序的 k=v、signedNonce。 */
    fun buildSignature(method: String, path: String, params: Map<String, String>, signedNonce: String): String {
        val uriPath = path.substringBefore('?')
        val parts = mutableListOf(method.uppercase(), uriPath)
        params.keys.sorted().forEach { key -> parts += "$key=${params[key]}" }
        parts += signedNonce
        return sha1Signature(parts)
    }

    /**
     * 把查询参数加密成可直接发出的 query map。
     * 与 Python `encrypt_query_params` 等价。
     */
    fun encryptQueryParams(
        method: String,
        path: String,
        params: Map<String, String>,
        nonce: String,
        ssecurity: String,
        signaturePath: String? = null,
    ): Map<String, String> {
        val signed = signedNonce(ssecurity, nonce)

        val signatureInput = params.filterValues { it.isNotEmpty() }.toMutableMap()
        signatureInput["rc4_hash__"] = buildSignature(method, signaturePath ?: path, signatureInput, signed)

        val cipher = Rc4Cipher(b64decode(signed))
        // 注意：加密的是 signature_input（含 rc4_hash__ 本身），不是原始 params
        val encrypted = signatureInput.keys.sorted().associateWith { key ->
            b64encode(cipher.apply(signatureInput.getValue(key).toByteArray(Charsets.UTF_8)))
        }.toMutableMap()

        encrypted["signature"] = buildSignature(method, signaturePath ?: path, encrypted, signed)
        encrypted["_nonce"] = nonce
        return encrypted
    }

    /** 解密响应体：base64(RC4(JSON))。 */
    fun decryptResponsePayload(body: String, nonce: String, ssecurity: String): String {
        val key = b64decode(signedNonce(ssecurity, nonce))
        return Rc4Cipher(key).apply(b64decode(body)).toString(Charsets.UTF_8)
    }
}

/** 字节级 RC4，初始化后丢弃前 1024 字节（与米家/小米运动健康一致）。 */
class Rc4Cipher(key: ByteArray) {

    private val state = IntArray(256)
    private var i = 0
    private var j = 0

    init {
        require(key.size == 32) { "RC4 key must be 32 bytes." }
        for (index in 0 until 256) state[index] = index
        var j2 = 0
        for (index in 0 until 256) {
            j2 = (j2 + state[index] + (key[index % key.size].toInt() and 0xFF)) and 0xFF
            val tmp = state[index]
            state[index] = state[j2]
            state[j2] = tmp
        }
        // 丢弃前 1024 字节
        apply(ByteArray(1024))
    }

    fun apply(data: ByteArray): ByteArray {
        val output = ByteArray(data.size)
        for (index in data.indices) {
            i = (i + 1) and 0xFF
            val stateI = state[i]
            j = (j + stateI) and 0xFF
            val tmp = state[i]
            state[i] = state[j]
            state[j] = tmp
            val k = (state[i] + stateI) and 0xFF
            output[index] = (data[index].toInt() xor state[k]).toByte()
        }
        return output
    }
}
