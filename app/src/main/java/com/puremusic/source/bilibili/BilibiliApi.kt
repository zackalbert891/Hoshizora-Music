package com.puremusic.source.bilibili

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.puremusic.model.Comment
import com.puremusic.model.CommentSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/** B站视频搜索结果（用于评论聚合与音频取流）。 */
data class BiliVideo(
    val aid: Long,
    val bvid: String,
    val cid: Long,
    val title: String,
    val author: String,
    val coverUrl: String,
    val durationSec: Int,
)

/**
 * B站开放接口客户端：搜索视频、读评论、取音频流。
 *
 * 关键点（2024+ 实测）：
 * - 必须先向 `/x/frontend/finger/spi` 换取 `buvid3`，否则搜索接口直接返回空 body（-412 风控）；
 * - 所有请求需带 `Referer: https://www.bilibili.com/`；
 * - 评论 `/x/v2/reply?type=1&oid={aid}&sort=2` 为热门排序，匿名即可读；
 * - 音频流走 `/x/player/playurl?fnval=16` 的 DASH `audio[].baseUrl`，播放时必须带 Referer。
 */
@Singleton
class BilibiliApi @Inject constructor(
    @Named("plain") private val client: OkHttpClient,
) {

    private val gson = Gson()

    /** 设备指纹 cookie（buvid3/b_nut），首次调用时惰性获取并缓存。 */
    @Volatile
    private var buvidCookie: String = ""

    /** 惰性获取 buvid3；失败时退回本地随机 UUID（部分接口仍可用）。 */
    private suspend fun ensureBuvid(): String {
        if (buvidCookie.isNotBlank()) return buvidCookie
        val text = get("https://api.bilibili.com/x/frontend/finger/spi", withBuvid = false)
        val data = runCatching {
            gson.fromJson(text, JsonObject::class.java)?.getAsJsonObject("data")
        }.getOrNull()
        val b3 = data?.get("b_3")?.asString
        val b4 = data?.get("b_4")?.asString
        buvidCookie = if (!b3.isNullOrBlank()) {
            "buvid3=${URLEncoder.encode(b3, "UTF-8")}%3A${URLEncoder.encode(b4.orEmpty(), "UTF-8")}; " +
                "b_nut=${System.currentTimeMillis() / 1000}"
        } else {
            "buvid3=${UUID.randomUUID().toString().uppercase()}-infoc; b_nut=${System.currentTimeMillis() / 1000}"
        }
        return buvidCookie
    }

    private suspend fun cookieHeader(): String = ensureBuvid()

    /** 搜索视频（音频/翻唱/现场等稀缺资源的主要来源）。 */
    suspend fun searchVideos(keyword: String, page: Int = 1, limit: Int = 10): List<BiliVideo> =
        withContext(Dispatchers.IO) {
            val kw = URLEncoder.encode(keyword, "UTF-8")
            val url = "https://api.bilibili.com/x/web-interface/search/type" +
                "?search_type=video&keyword=$kw&page=$page&order=totalrank"
            val text = get(url, withBuvid = true)
            val arr = runCatching {
                gson.fromJson(text, JsonObject::class.java)
                    ?.getAsJsonObject("data")
                    ?.getAsJsonArray("result")
            }.getOrNull() ?: return@withContext emptyList()
            arr.take(limit).mapNotNull { el ->
                val o = runCatching { el.asJsonObject }.getOrNull() ?: return@mapNotNull null
                val aid = runCatching { o.get("aid").asLong }.getOrNull() ?: return@mapNotNull null
                val bvid = o.get("bvid")?.asString ?: return@mapNotNull null
                BiliVideo(
                    aid = aid,
                    bvid = bvid,
                    cid = 0L, // 取流时再按 bvid 拉取
                    title = o.get("title")?.asString.orEmpty()
                        .replace("<em class=\"keyword\">", "").replace("</em>", ""),
                    author = o.get("author")?.asString.orEmpty(),
                    coverUrl = o.get("pic")?.asString.orEmpty().let {
                        if (it.startsWith("//")) "https:$it" else it
                    },
                    durationSec = runCatching { durationToSec(o.get("duration")?.asString) }.getOrDefault(0),
                )
            }
        }

    /** 解析视频分 P 的 cid。 */
    suspend fun firstCid(bvid: String): Long = withContext(Dispatchers.IO) {
        val text = get("https://api.bilibili.com/x/player/pagelist?bvid=$bvid", withBuvid = true)
        runCatching {
            gson.fromJson(text, JsonObject::class.java)
                ?.getAsJsonArray("data")
                ?.firstOrNull()?.asJsonObject
                ?.get("cid")?.asLong
        }.getOrNull() ?: 0L
    }

    /** 音频直链（DASH 音轨）；播放时务必带 Referer，否则 CDN 返回 403。 */
    suspend fun audioUrl(bvid: String, cid: Long): String? = withContext(Dispatchers.IO) {
        if (cid <= 0L) return@withContext null
        val url = "https://api.bilibili.com/x/player/playurl" +
            "?bvid=$bvid&cid=$cid&fnval=16&fnver=0&fourk=1"
        val text = get(url, withBuvid = true, referer = "https://www.bilibili.com/")
        runCatching {
            gson.fromJson(text, JsonObject::class.java)
                ?.getAsJsonObject("data")
                ?.getAsJsonObject("dash")
                ?.getAsJsonArray("audio")
                ?.firstOrNull()?.asJsonObject
                ?.get("baseUrl")?.asString
        }.getOrNull()
    }

    /** 热门评论（sort=2 为热度排序）。 */
    suspend fun hotComments(aid: Long, limit: Int = 20, title: String = "", bvid: String = ""): List<Comment> =
        replies(aid, sort = 2, limit = limit, title = title, bvid = bvid)

    /** 最新评论（sort=0 为时间排序）。 */
    suspend fun latestComments(aid: Long, limit: Int = 20, title: String = "", bvid: String = ""): List<Comment> =
        replies(aid, sort = 0, limit = limit, title = title, bvid = bvid)

    private suspend fun replies(
        aid: Long,
        sort: Int,
        limit: Int,
        title: String,
        bvid: String,
    ): List<Comment> = withContext(Dispatchers.IO) {
        val url = "https://api.bilibili.com/x/v2/reply?type=1&oid=$aid&sort=$sort&ps=$limit&pn=1"
        val text = get(url, withBuvid = true)
        val arr = runCatching {
            gson.fromJson(text, JsonObject::class.java)
                ?.getAsJsonObject("data")
                ?.getAsJsonArray("replies")
        }.getOrNull() ?: return@withContext emptyList()
        arr.mapNotNull { el ->
            val o = runCatching { el.asJsonObject }.getOrNull() ?: return@mapNotNull null
            val member = o.getAsJsonObject("member")
            val content = o.getAsJsonObject("content")?.get("message")?.asString.orEmpty()
            if (content.isBlank()) return@mapNotNull null
            Comment(
                id = o.get("rpid")?.asString ?: content.hashCode().toString(),
                source = CommentSource.BILIBILI,
                userName = member?.get("uname")?.asString.orEmpty(),
                avatarUrl = member?.get("avatar")?.asString.orEmpty().let {
                    if (it.startsWith("//")) "https:$it" else it
                },
                content = content,
                likeCount = runCatching { o.get("like")?.asInt }.getOrDefault(0) ?: 0,
                replyCount = runCatching { o.get("rcount")?.asInt }.getOrDefault(0) ?: 0,
                timeText = formatTime(runCatching { o.get("ctime")?.asLong }.getOrDefault(0L) ?: 0L),
                sourceTitle = title,
                sourceUrl = if (bvid.isBlank()) "" else "https://www.bilibili.com/video/$bvid",
            )
        }
    }

    private suspend fun get(url: String, withBuvid: Boolean, referer: String = "https://www.bilibili.com/"): String {
        val cookie = if (withBuvid) cookieHeader() else ""
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Referer", referer)
            .apply { if (cookie.isNotBlank()) header("Cookie", cookie) }
            .get()
            .build()
        return runCatching {
            client.newCall(request).execute().use { if (it.isSuccessful) it.body?.string().orEmpty() else "" }
        }.getOrDefault("")
    }

    private fun durationToSec(raw: String?): Int {
        if (raw.isNullOrBlank()) return 0
        val parts = raw.split(":")
        return when (parts.size) {
            2 -> parts[0].toIntOrNull()?.times(60)?.plus(parts[1].toIntOrNull() ?: 0) ?: 0
            3 -> (parts[0].toIntOrNull() ?: 0) * 3600 +
                (parts[1].toIntOrNull() ?: 0) * 60 +
                (parts[2].toIntOrNull() ?: 0)
            else -> raw.toIntOrNull() ?: 0
        }
    }

    private fun formatTime(sec: Long): String {
        if (sec <= 0L) return ""
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(sec * 1000))
        }.getOrDefault("")
    }

    private companion object {
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
