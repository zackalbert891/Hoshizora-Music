package com.puremusic.source.kuwo

import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import java.util.zip.GZIPInputStream

/**
 * 酷我歌词解密器：XOR 密钥 "yeelion" 逐字节循环异或 + 可选 GZIP 解压 + GB18030 解码。
 *
 * 酷我 newlyric.lrc 接口返回的歌词体经过 "yeelion" 循环异或加密（部分响应还叠加 gzip
 * 压缩），先按密钥还原字节，再尝试 gzip 解压，最后用 GB18030（兼容 GBK）转为文本。
 */
object KuwoLyricCipher {

    private val KEY = "yeelion".toByteArray(Charsets.US_ASCII)

    fun decrypt(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val xor = ByteArray(bytes.size)
        for (i in bytes.indices) {
            xor[i] = (bytes[i].toInt() xor KEY[i % KEY.size].toInt()).toByte()
        }
        // 尝试 gzip 解压（失败视为未压缩的 XOR 数据）
        val body = runCatching {
            GZIPInputStream(ByteArrayInputStream(xor)).use { it.readBytes() }
        }.getOrElse { xor }
        // GB18030 -> GBK -> UTF-8 逐级兜底
        return try {
            String(body, Charset.forName("GB18030"))
        } catch (e: Exception) {
            try {
                String(body, Charset.forName("GBK"))
            } catch (e2: Exception) {
                String(body, Charsets.UTF_8)
            }
        }
    }
}
