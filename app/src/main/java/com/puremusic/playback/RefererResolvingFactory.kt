package com.puremusic.playback

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource

private const val UA =
    "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0 Mobile Safari/537.36"

/**
 * 按直链 host 注入正确的 Referer / UA 的 DataSource 工厂。
 *
 * 背景：本应用是多平台聚合播放器，不同平台 CDN 对 Referer 的要求不同——
 * - B站（`*.bilivideo.com` / `*.akamaized.net`）：必须 `https://www.bilibili.com/`，否则 403；
 * - 网易云（`*.music.126.net`）：带 `https://music.163.com/` 更稳；
 * - QQ（`*.qqmusic.qq.com` 等）：需 `https://y.qq.com/`。
 * 全局单一 Referer 会让跨源播放直接失败，故在 DataSpec 解析阶段动态改写请求头。
 */
class RefererResolvingFactory : DataSource.Factory {

    private val upstream = DefaultHttpDataSource.Factory()
        .setUserAgent(UA)
        .setDefaultRequestProperties(mapOf("Referer" to "https://y.qq.com/"))
        .setConnectTimeoutMs(10_000)
        .setReadTimeoutMs(15_000)

    private val resolver = ResolvingDataSource.Resolver { dataSpec ->
        val host = dataSpec.uri.host.orEmpty()
        val referer = refererFor(host)
        val headers = LinkedHashMap(dataSpec.httpRequestHeaders).apply {
            put("Referer", referer)
            put("User-Agent", UA)
        }
        dataSpec.buildUpon().setHttpRequestHeaders(headers).build()
    }

    private val delegate = ResolvingDataSource.Factory(upstream, resolver)

    override fun createDataSource(): DataSource = delegate.createDataSource()

    private fun refererFor(host: String): String = when {
        host.contains("bilivideo") || host.contains("bilibili") ||
            host.contains("akamaized") || host.contains("hdslb") ->
            "https://www.bilibili.com/"
        host.contains("music.126.net") || host.endsWith("163.com") ->
            "https://music.163.com/"
        else -> "https://y.qq.com/"
    }
}

