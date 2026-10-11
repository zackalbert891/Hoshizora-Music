package com.puremusic.source.netease

import android.util.Base64
import com.google.gson.Gson
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 网易云 weapi / eapi 签名算法实现。
 *
 * weapi：`params` = Base64(AES(AES(json, nonce), secKey))，`encSecKey` = RSA(reverse(secKey))。
 * eapi：`params` = AES-128-ECB(`{url}-36cd479b6b5-{text}-36cd479b6b5-{md5}`, e82ckenh8dichen8) 的 hex 大写。
 * 均为网易云公开逆向的稳定实现，见 lx-music-desktop wy/crypto.js 与 NeteaseCloudMusicApi util/crypto.js。
 */
object NeteaseCrypto {

    private const val MODULUS =
        "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725" +
            "152b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e03" +
            "12ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10" +
            "b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7"
    private const val PUBLIC_EXPONENT = "010001"
    private const val NONCE = "0CoJUm6Qyw8W8jud"
    private const val IV = "0102030405060708"
    /** weapi 随机密钥字符集，与 NeteaseCloudMusicApi util/crypto.js `randomString(16, 'abcdefghijklmnop')` 一致。 */
    private const val SECRET_CHARS = "abcdefghijklmnop"
    /** eapi 预设密钥与分隔常量，与 lx-music wy/crypto.js、NeteaseCloudMusicApi util/crypto.js 一致。 */
    private const val EAPI_KEY = "e82ckenh8dichen8"
    private const val EAPI_DIGEST_PREFIX = "-36cd479b6b5-"
    private val gson = Gson()

    fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    /**
     * 构造 eapi 请求体，返回 { params }。
     *
     * 参考 lx-music-desktop wy/crypto.js 与 NeteaseCloudMusicApi util/crypto.js：
     * `nobody{url}use{text}md5forencrypt` 取 MD5，拼 `{url}-36cd479b6b5-{text}-36cd479b6b5-{digest}`，
     * 再用 AES-128-ECB（key=e82ckenh8dichen8）加密，输出 hex 大写。
     */
    fun eapi(url: String, body: Map<String, *>): Map<String, String> {
        val text = gson.toJson(body)
        val digest = md5("nobody${url}use${text}md5forencrypt")
        val data = "${url}${EAPI_DIGEST_PREFIX}${text}${EAPI_DIGEST_PREFIX}${digest}"
        return mapOf("params" to aesEcbEncrypt(data, EAPI_KEY))
    }

    private fun aesEcbEncrypt(text: String, key: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"))
        return cipher.doFinal(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02X".format(it.toInt() and 0xFF) }
    }

    /** 构造 weapi 请求体，返回 { params, encSecKey }。 */
    fun weapi(body: Map<String, *>): Map<String, String> {
        val text = gson.toJson(body)
        val secKey = buildString { repeat(16) { append(SECRET_CHARS.random()) } }
        val params = aesEncrypt(aesEncrypt(text, NONCE), secKey)
        val encSecKey = rsaEncrypt(secKey.reversed())
        return mapOf("params" to params, "encSecKey" to encSecKey)
    }

    private fun aesEncrypt(text: String, key: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(IV.toByteArray(Charsets.UTF_8)),
        )
        return Base64.encodeToString(
            cipher.doFinal(text.toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP,
        )
    }

    private fun rsaEncrypt(text: String): String {
        val key = KeyFactory.getInstance("RSA").generatePublic(
            RSAPublicKeySpec(BigInteger(MODULUS, 16), BigInteger(PUBLIC_EXPONENT, 16))
        )
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val data = text.toByteArray(Charsets.UTF_8)
        // 1024 位 RSA 需要把明文左补零到 128 字节。
        val padded = ByteArray(128 - data.size) + data
        val encrypted = cipher.doFinal(padded)
        return encrypted.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}
