package com.zzzyy.healthcoach.data.cloud

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kotlin 加密层与 Python 参考实现的交叉验证。
 *
 * 期望值全部由 `.work/gen-crypto-vectors.py` 用 Python 的 `crypto.py` 算出。
 * 任何一边改动都会让这里变红 —— 这是防止「移植时手抖改了一个字节」的护栏。
 */
class MiCryptoTest {

    private fun vectors(): JSONObject {
        val stream = javaClass.classLoader?.getResourceAsStream("mi_crypto_vectors.json")
        assertNotNull("缺少测试向量 mi_crypto_vectors.json", stream)
        return JSONObject(stream!!.readBytes().toString(Charsets.UTF_8))
    }

    @Test
    fun `signedNonce 与 Python 实现一致`() {
        val v = vectors()
        val signed = MiCrypto.signedNonce(v.getString("ssecurity"), v.getString("nonce"))
        assertEquals(v.getString("signedNonce"), signed)
    }

    @Test
    fun `RC4 已知答案与 Python 实现一致`() {
        val v = vectors()
        val key = MiCrypto.b64decode(v.getString("rc4Key"))
        val kat = v.getJSONObject("rc4KAT")

        fun check(name: String, plain: ByteArray) {
            val expected = MiCrypto.b64decode(kat.getString(name))
            val actual = Rc4Cipher(key).apply(plain)
            assertArrayEquals("RC4 KAT 不一致：$name", expected, actual)
        }

        check("empty", ByteArray(0))
        check("zeros", ByteArray(64))
        check("ff", ByteArray(64) { 0xFF.toByte() })
        check("ascii", "the quick brown fox jumps over the lazy dog".toByteArray())
    }

    @Test
    fun `查询参数加密与 Python 实现一致`() {
        val v = vectors()
        val enc = v.getJSONObject("encrypt")
        val params = enc.getJSONObject("params")
        val flat = params.keys().asSequence().associateWith { params.getString(it) }

        val result = MiCrypto.encryptQueryParams(
            method = enc.getString("method"),
            path = enc.getString("path"),
            params = flat,
            nonce = v.getString("nonce"),
            ssecurity = v.getString("ssecurity"),
        )

        val expected = enc.getJSONObject("result")
        assertEquals(
            "加密后的参数集合不一致",
            expected.keys().asSequence().toSet(),
            result.keys.toSet(),
        )
        expected.keys().forEach { key ->
            assertEquals("参数 $key 加密结果不一致", expected.getString(key), result[key])
        }
    }

    @Test
    fun `响应解密与 Python 实现一致`() {
        val v = vectors()
        val dec = v.getJSONObject("decrypt")
        val plain = MiCrypto.decryptResponsePayload(
            dec.getString("body"),
            v.getString("nonce"),
            v.getString("ssecurity"),
        )
        assertEquals(dec.getString("expected"), plain)
    }

    @Test
    fun `nonce 结构为 12 字节且末 4 字节是大端分钟数`() {
        val nonce = MiCrypto.generateNonce(0)
        val raw = MiCrypto.b64decode(nonce)
        assertEquals(12, raw.size)

        val expectedMinutes = ((System.currentTimeMillis()) / 60_000L).toInt()
        val actualMinutes = ((raw[8].toInt() and 0xFF) shl 24) or
            ((raw[9].toInt() and 0xFF) shl 16) or
            ((raw[10].toInt() and 0xFF) shl 8) or
            (raw[11].toInt() and 0xFF)
        // 允许 1 分钟的抖动，避免跨分钟边界时误判
        assertTrue(
            "分钟数不一致：期望≈$expectedMinutes 实际=$actualMinutes",
            kotlin.math.abs(expectedMinutes - actualMinutes) <= 1,
        )
    }

    @Test
    fun `rc4_hash__ 是明文签名再经 RC4 加密的结果`() {
        val v = vectors()
        val enc = v.getJSONObject("encrypt")
        val params = enc.getJSONObject("params")
        val flat = params.keys().asSequence().associateWith { params.getString(it) }
        val expected = enc.getJSONObject("result")

        val signed = MiCrypto.signedNonce(v.getString("ssecurity"), v.getString("nonce"))

        // Python 的语义：signature_input（含 rc4_hash__ 本身）里的每个值都被 RC4 加密，
        // 且按 key 排序后依次用**同一个** cipher 流加密（data 先，rc4_hash__ 后）。
        val plainSignature = MiCrypto.buildSignature("GET", enc.getString("path"), flat, signed)

        val cipher = Rc4Cipher(MiCrypto.b64decode(signed))
        cipher.apply(flat.getValue("data").toByteArray(Charsets.UTF_8))
        val encryptedRc4Hash = MiCrypto.b64encode(cipher.apply(plainSignature.toByteArray(Charsets.UTF_8)))

        assertEquals(expected.getString("rc4_hash__"), encryptedRc4Hash)
    }
}
