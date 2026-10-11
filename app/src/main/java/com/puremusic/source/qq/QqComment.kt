package com.puremusic.source.qq

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.puremusic.data.AccountStore
import com.puremusic.model.Comment
import com.puremusic.model.CommentSource
import com.puremusic.model.MusicSourceType
import com.puremusic.model.RepliedComment
import com.puremusic.source.netease.CommentPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * QQ 音乐评论数据源（只读）。
 *
 * **为什么要单独一个源**：QQ 音乐的歌就该看 QQ 音乐的评论 —— 拿歌名去网易云搜同名曲再取评论，
 * 遇到翻唱 / 同名曲就会串到别人家的评论区。所以 QQ 源的歌走这里，网易云源的歌照旧走网易云。
 *
 * 链路（两跳，都是老 H5 接口，匿名可读，不依赖登录态）：
 * 1. `songmid` → 数字 `songid`：评论接口的 `topid` 只认数字 id，不认 songmid。
 *    三条通道依次兜底（见 [resolveSongId]），拿到即缓存，一首歌只解析一次。
 * 2. `fcg_global_comment_h5.fcg` 取评论：`comment.commentlist` 是最新评论（按 pagenum 翻页），
 *    `hot_comment.commentlist` 是精彩评论（只有第 0 页返回）。
 *
 * 返回值与网易云完全同构（都是 [CommentPage]），UI 层不需要区分平台。
 */
@Singleton
class QqComment @Inject constructor(
    @Named("plain") private val client: OkHttpClient,
    private val accountStore: AccountStore,
) {

    private val gson = Gson()

    /** songmid → 数字 songid。翻页几十次也只解析一次，省掉每页一次额外往返。 */
    private val songIdCache = ConcurrentHashMap<String, String>()

    /**
     * 取一页评论。
     *
     * @param pageNum 从 0 开始；只有第 0 页会带回精彩评论。
     */
    suspend fun page(songMid: String, pageNum: Int = 0, pageSize: Int = 25): CommentPage =
        withContext(Dispatchers.IO) {
            val songId = resolveSongId(songMid)
            if (songId.isNullOrBlank()) {
                android.util.Log.d("QqComment", "songId 解析失败 songMid=$songMid")
                return@withContext CommentPage()
            }
            val cookie = accountStore.accounts.value[MusicSourceType.QQ]?.cookie.orEmpty()
            val url = "https://c.y.qq.com/base/fcgi-bin/fcg_global_comment_h5.fcg" +
                "?g_tk=5381&loginUin=0&hostUin=0&format=json&inCharset=utf8&outCharset=utf-8" +
                "&notice=0&platform=yqq.json&needNewCode=0" +
                "&cid=205360772&reqtype=2&biztype=1" +
                "&topid=$songId&cmd=8&needmusiccrit=0" +
                "&pagenum=$pageNum&pagesize=$pageSize&lasthotcommentid=&domain=qq.com" +
                "&ct=24&cv=101010"
            val builder = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Referer", "https://y.qq.com/")
            if (cookie.isNotBlank()) builder.header("Cookie", cookie)
            val text = runCatching {
                client.newCall(builder.build()).execute().use {
                    if (it.isSuccessful) it.body?.string().orEmpty() else ""
                }
            }.getOrDefault("")
            if (text.isBlank()) {
                android.util.Log.d("QqComment", "空响应 songMid=$songMid songId=$songId page=$pageNum")
                return@withContext CommentPage()
            }
            val root = runCatching { gson.fromJson(text, JsonObject::class.java) }.getOrNull()
            if (root == null) {
                android.util.Log.d("QqComment", "非 JSON songMid=$songMid resp=${text.take(160)}")
                return@withContext CommentPage()
            }

            val comment = root.obj("comment")
            val list = comment?.arr("commentlist")
                ?.mapNotNull { parseItem(it, hot = false) }.orEmpty()
            // 实测 commenttotal 恒为 0（热评那边才有真实总数），所以总数按"已拿到多少条"算，
            // 由 UI 层累加 —— 用户看到的是"已加载 71 / 共 71"，不会凭空冒出一个假总数。
            val total = maxOf(comment?.intOf("commenttotal") ?: 0, list.size)
            // 接口不给 hasmore，且"本页装满了"不能当作还有更多（实测第一页常常只给 24 条，
            // 后面 2、3 页照样有内容）。所以只要有内容就继续翻，靠"取到空页 / 全是重复"收尾。
            val hasMoreRaw = comment?.intOf("hasmore")
            val more = if (hasMoreRaw != null) hasMoreRaw > 0 else list.isNotEmpty()
            val hot = if (pageNum == 0) {
                root.obj("hot_comment")?.arr("commentlist")
                    ?.mapNotNull { parseItem(it, hot = true) }.orEmpty()
            } else {
                emptyList()
            }
            android.util.Log.d(
                "QqComment",
                "page songMid=$songMid songId=$songId page=$pageNum total=$total " +
                    "hot=${hot.size} n=${list.size} more=$more",
            )
            CommentPage(
                total = maxOf(total, list.size),
                hot = hot,
                comments = list,
                more = more,
            )
        }

    /**
     * songmid → 数字 songid。
     *
     * 三条通道依次尝试，任一成功即缓存。本机（代理出口 IP）访问 QQ 常态被风控，
     * 所以这里不赌"哪条一定行"，而是全都试一遍 + 打日志 —— 真机 logcat 一看就知道走通的是哪条。
     * A. `fcg_play_single_song.fcg`：最老最稳，`data[0].id`；
     * B. `musicu.fcg / music.trackInfo.UniformRuleCtrl`：官方 App 的曲库接口；
     * C. `fcg_music_express_mobile3.fcg`：取流同款老接口，`data.items[0].songid`。
     */
    suspend fun resolveSongId(songMid: String): String? {
        songIdCache[songMid]?.let { return it }
        val a = songIdViaSingleSong(songMid)
        val b = a ?: songIdViaTrackInfo(songMid)
        val id = b ?: songIdViaExpress(songMid)
        android.util.Log.d("QqComment", "resolveSongId $songMid -> $id (A=$a B=$b)")
        if (!id.isNullOrBlank()) songIdCache[songMid] = id
        return id
    }

    private suspend fun songIdViaSingleSong(songMid: String): String? = withContext(Dispatchers.IO) {
        val url = "https://c.y.qq.com/v8/fcg-bin/fcg_play_single_song.fcg" +
            "?songmid=$songMid&tpl=yqq_song_detail&format=json" +
            "&g_tk=5381&loginUin=0&hostUin=0&inCharset=utf8&outCharset=utf-8" +
            "&notice=0&platform=yqq&needNewCode=0"
        val text = get(url) ?: return@withContext null
        runCatching {
            gson.fromJson(text, JsonObject::class.java)
                .arr("data")?.firstOrNull()?.let { JsonElementParser.objOf(it) }
                ?.stringOf("id")
        }.getOrNull()?.takeIf { it.isNotBlank() && it != "0" }
    }

    private suspend fun songIdViaTrackInfo(songMid: String): String? = withContext(Dispatchers.IO) {
        val json = """{"comm":{"ct":"24","cv":"0","format":"json","inCharset":"utf-8",""" +
            """"outCharset":"utf-8","uin":"0"},"req_0":{"module":"music.trackInfo.""" +
            """UniformRuleCtrl","method":"CgiGetTrackInfo","param":{"ids":["$songMid"],"types":[0]}}}"""
        val text = runCatching {
            client.newCall(
                Request.Builder()
                    .url("https://u.y.qq.com/cgi-bin/musicu.fcg")
                    .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .header("Referer", "https://y.qq.com/")
                    .header("User-Agent", UA)
                    .build()
            ).execute().use { if (it.isSuccessful) it.body?.string().orEmpty() else "" }
        }.getOrDefault("")
        if (text.isBlank()) return@withContext null
        runCatching {
            gson.fromJson(text, JsonObject::class.java)
                .obj("req_0")?.obj("data")?.arr("tracks")
                ?.firstOrNull()?.let { JsonElementParser.objOf(it) }
                ?.stringOf("id")
        }.getOrNull()?.takeIf { it.isNotBlank() && it != "0" }
    }

    private suspend fun songIdViaExpress(songMid: String): String? = withContext(Dispatchers.IO) {
        val url = "https://c.y.qq.com/base/fcgi-bin/fcg_music_express_mobile3.fcg" +
            "?format=json&platform=yqq&cid=205361747&songmid=$songMid" +
            "&filename=C400$songMid.m4a&guid=1234567890"
        val text = get(url) ?: return@withContext null
        runCatching {
            gson.fromJson(text, JsonObject::class.java)
                .obj("data")?.arr("items")?.firstOrNull()?.let { JsonElementParser.objOf(it) }
                ?.stringOf("songid")
        }.getOrNull()?.takeIf { it.isNotBlank() && it != "0" }
    }

    private suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(
                Request.Builder().url(url).header("User-Agent", UA)
                    .header("Referer", "https://y.qq.com/").build()
            ).execute().use { if (it.isSuccessful) it.body?.string().orEmpty() else "" }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /**
     * 解析单条评论。
     *
     * 字段名是照着真机真实响应写的（2026-10），QQ 这个老 H5 接口跟网上流传的文档已经对不上：
     * - 正文在 `middlecommentcontent[]`，但它的内容字段叫 **`subcommentcontent`**，不叫 `str`；
     * - 根评论的 `middlecommentcontent` 是 **null**，正文反而落在 `rootcommentcontent` 里；
     * - 点赞数是 `praisenum`（不是 `praise_num`），`vipicon` 是个图片 URL（不是 0/1）。
     */
    private fun parseItem(el: JsonElement, hot: Boolean): Comment? {
        val o = runCatching { el.asJsonObject }.getOrNull() ?: return null
        val content = contentOf(o).trim()
        if (content.isBlank()) return null

        val cid = o.stringOf("commentid").orEmpty()
        val rootId = o.stringOf("rootcommentid").orEmpty()
        val rootContent = o.stringOf("rootcommentcontent").orEmpty().trim()
        val rootNick = o.stringOf("rootcommentnick").orEmpty().trim().trim('@')

        // 楼中楼引用：子评论里被回复者是 replyednick（"@xxx"），
        // 被回复的内容就是 rootcommentcontent；但根评论（rootcommentid == commentid）
        // 的 rootcommentcontent 其实是**它自己的正文**，不能当成引用。
        val replyedNick = o.arr("middlecommentcontent")?.firstOrNull()
            ?.let { JsonElementParser.objOf(it) }
            ?.stringOf("replyednick").orEmpty().trim().trim('@')
        val replied = when {
            replyedNick.isNotBlank() && rootContent.isNotBlank() ->
                listOf(RepliedComment(replyedNick, rootContent))
            rootId.isNotBlank() && rootId != cid && rootContent.isNotBlank() ->
                listOf(RepliedComment(rootNick.ifBlank { "该用户" }, rootContent))
            else -> emptyList()
        }

        val seconds = o.longOf("time", "createTime", "create_time") ?: 0L
        val ms = seconds * 1000L
        // vipicon 是图片地址（有值 = 会员），别按数字读
        val vip = o.stringOf("vipicon").orEmpty().isNotBlank() ||
            listOf("is_vip", "vip", "vip_type").any { (o.intOf(it) ?: 0) > 0 }

        return Comment(
            id = cid.ifBlank { "${o.stringOf("uin").orEmpty()}_${seconds}_${content.hashCode()}" },
            source = CommentSource.QQ,
            userName = o.stringOf("nick", "nickname").orEmpty().trim('@').ifBlank { "匿名用户" },
            avatarUrl = o.stringOf("avatarurl", "avatar_url", "avatarUrl").orEmpty()
                .replace("http://", "https://"),
            content = content.replace(EMOJI_PATTERN, "[表情]"),
            likeCount = o.intOf("praisenum", "praise_num", "praiseNum") ?: 0,
            liked = (o.intOf("ispraise", "is_praise") ?: 0) > 0,
            replyCount = o.intOf("cmt_num", "reply_num", "replyNum") ?: 0,
            timeText = formatTime(ms),
            createdMs = ms,
            ipLocation = o.stringOf("ip_location", "location", "ipLocation").orEmpty(),
            beReplied = replied,
            hot = hot,
            vipType = if (vip) 1 else 0,
        )
    }

    /** 正文：① `middlecommentcontent[].subcommentcontent` ② 根评论的 `rootcommentcontent` ③ 新版 `content`。 */
    private fun contentOf(o: JsonObject): String {
        val sub = o.arr("middlecommentcontent")
            ?.joinToString("") { seg ->
                JsonElementParser.objOf(seg)
                    ?.stringOf("subcommentcontent", "str", "content").orEmpty()
            }
            ?.trim()
        if (!sub.isNullOrBlank()) return sub
        val root = o.stringOf("rootcommentcontent").orEmpty().trim()
        if (root.isNotBlank()) return root
        return o.stringOf("content", "commentcontent").orEmpty().trim()
    }

    private fun formatTime(ms: Long): String {
        if (ms <= 0L) return ""
        val diff = System.currentTimeMillis() - ms
        return when {
            diff < 60_000L -> "刚刚"
            diff < 3_600_000L -> "${diff / 60_000L}分钟前"
            diff < 86_400_000L -> "${diff / 3_600_000L}小时前"
            diff < 172_800_000L -> "昨天 ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))}"
            diff < 31_536_000_000L -> SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
            else -> SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(ms))
        }
    }

    private companion object {
        /** QQ 的表情码形如 `[em]e400846[/em]`：不转义的话评论里会露出一串天书。 */
        val EMOJI_PATTERN = Regex("\\[em\\][^\\[]*\\[/em\\]")

        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}

/* ---------- 容错取值：字段缺失 / 类型不符一律给 null，由上层兜底，绝不抛异常 ---------- */

private object JsonElementParser {
    fun objOf(el: JsonElement?): JsonObject? =
        if (el != null && el.isJsonObject) el.asJsonObject else null
}

private fun JsonObject.obj(key: String): JsonObject? = JsonElementParser.objOf(get(key))

private fun JsonObject.arr(key: String): JsonArray? {
    val el = get(key) ?: return null
    return if (el.isJsonArray) el.asJsonArray else null
}

private fun JsonObject.stringOf(vararg keys: String): String? {
    for (k in keys) {
        val el = get(k) ?: continue
        if (el.isJsonPrimitive) {
            val v = runCatching { el.asString }.getOrNull()
            if (v != null) return v
        }
    }
    return null
}

private fun JsonElement.stringOf(): String? =
    if (isJsonPrimitive) runCatching { asString }.getOrNull() else null

private fun JsonObject.intOf(vararg keys: String): Int? {
    for (k in keys) {
        val el = get(k) ?: continue
        if (el.isJsonPrimitive) {
            val v = runCatching { el.asInt }.getOrNull()
            if (v != null) return v
        }
    }
    return null
}

private fun JsonObject.longOf(vararg keys: String): Long? {
    for (k in keys) {
        val el = get(k) ?: continue
        if (el.isJsonPrimitive) {
            val v = runCatching { el.asLong }.getOrNull()
            if (v != null) return v
        }
    }
    return null
}
