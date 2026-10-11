package com.puremusic.source.netease

import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.puremusic.data.AccountStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.StringReader
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * 网易云「发现」数据源：榜单 / 歌单广场 / 歌单详情 / 收藏歌单。
 *
 * ## 为什么这一层单独存在
 *
 * [NeteaseDirect] 解决的是「歌曲级」问题（搜索、取流、歌词、单个歌单的歌曲）；
 * 而 NCMusic / MusicFree 那类客户端还有整整一层「内容发现」：
 * 排行榜有几十张榜、歌单广场能按分类无限翻、歌单详情要显示播放量与收藏状态、
 * 还要能真的把歌单收进「我的收藏」并且在另一台设备上还在。这些都需要独立的接口，
 * 硬塞进 [NeteaseDirect] 会让那个类变成什么都管的上帝类。
 *
 * ## 接口与通道（2026-10-11 实测，别随便改）
 *
 * 这一层**全部走「裸 GET + 官方 PC Cookie」通道**，不走 eapi/weapi。实测对照：
 * - `/api/toplist/detail` 裸 GET → 63 张榜全量（91KB）✅
 * - `/api/playlist/list?cat=…` 裸 GET → 歌单广场分页 ✅（eapi 对应路径返回 404）
 * - `/api/playlist/highquality/tags` 裸 GET → 分类标签 ✅
 * - `/api/playlist/detail?id=…` 裸 GET → 详情 + 全量歌曲 + subscribed ✅
 * - `/api/v1/resource/comments/R_PL_0_<id>` 裸 GET → 歌单评论 ✅
 *
 * 之前在 [NeteaseDirect] 里用 eapi 打 `/api/recommend/resource`、`/api/personal/fm`
 * 拿到的是 `{"code":404,"message":"接口未发现"}`——不是参数错，是**路径根本不走 eapi**。
 * 写接口（收藏/取消）例外，必须 weapi + csrf_token + 安卓身份，见 [setSubscribed]。
 */
@Singleton
class NeteaseDiscover @Inject constructor(
    @Named("plain") private val client: OkHttpClient,
    private val accountStore: AccountStore,
) {

    private val gson = com.google.gson.Gson()

    // ═══════════════════════════════════════════════════════════════
    //  排行榜
    // ═══════════════════════════════════════════════════════════════

    /**
     * 全部榜单（一次返回 63 张，网易云在榜数十年没变过）。
     *
     * 榜单即歌单：[NeteaseToplist.id] 直接当歌单 id 传给 [playlistFull] 就能拿到榜单歌曲，
     * 所以榜单页与歌单详情页共用一套数据与一套 UI，不做第二套实现。
     */
    suspend fun toplists(cookie: String? = null): List<NeteaseToplist> = withContext(Dispatchers.IO) {
        val root = getJson(TOPLISTS_PATH, cookie) ?: return@withContext emptyList()
        val list = root.getAsJsonArray("list") ?: return@withContext emptyList()
        val out = ArrayList<NeteaseToplist>(list.size())
        list.forEach { el ->
            val o = el.asJsonObject
            val id = o.long("id")
            if (id <= 0L) return@forEach
            out += NeteaseToplist(
                id = id,
                name = o.str("name"),
                coverUrl = o.str("coverImgUrl"),
                updateFrequency = o.str("updateFrequency"),
                description = o.str("description"),
                trackCount = o.int("trackCount"),
                playCount = o.long("playCount"),
                group = groupOf(id, o.str("name")),
            )
        }
        Log.d(TAG, "toplists n=${out.size}")
        out
    }

    /**
     * 榜单分组。
     *
     * 网易云没有分组字段，靠 id 白名单划分（这是社区共识的划分方式）：
     * - 前四个是**官方榜**：飙升 / 新歌 / 原创 / 热歌
     * - `2801xxxxx` / `28xxxxxxx` 段是**全球榜**
     * - 其余是**特色榜**（电音 / 说唱 / 古典 / 潮流…）
     */
    private fun groupOf(id: Long, name: String): String {
        if (id in OFFICIAL_TOPLISTS.keys) return "官方榜"
        val s = id.toString()
        if (s.startsWith("28")) return "全球榜"
        return if (name.contains("榜")) "特色榜" else "其他"
    }

    // ═══════════════════════════════════════════════════════════════
    //  歌单广场
    // ═══════════════════════════════════════════════════════════════

    /**
     * 歌单分类标签（华语 / 流行 / 摇滚 / 轻音乐 …，约 40 个）。
     * 歌单广场顶部的筛选条直接吃这个，不需要在前端硬编码分类表。
     */
    suspend fun categories(): List<NeteaseCategory> = withContext(Dispatchers.IO) {
        val root = getJson(HIGHQUALITY_TAGS_PATH, null) ?: return@withContext emptyList()
        val arr = root.getAsJsonArray("tags") ?: return@withContext emptyList()
        arr.mapNotNull { el ->
            runCatching {
                val o = el.asJsonObject
                NeteaseCategory(id = o.long("id"), name = o.str("name"), hot = o.bool("hot"))
            }.getOrNull()
        }.filter { it.name.isNotBlank() }
    }

    /**
     * 歌单广场：一页歌单。
     *
     * @param category 分类名（如「华语」），空 = 全部。
     * @param offset 翻页偏移。返回 [more] 为 false 时说明已到底。
     */
    suspend fun squarePlaylists(
        category: String = "",
        limit: Int = 30,
        offset: Int = 0,
        cookie: String? = null,
    ): SquarePage = withContext(Dispatchers.IO) {
        val path = "$SQUARE_PATH?cat=${enc(category.ifBlank { "全部" })}" +
            "&limit=$limit&offset=$offset&order=hot"
        val root = getJson(path, cookie) ?: return@withContext SquarePage()
        val arr = root.getAsJsonArray("playlists")
        val list = arr?.mapNotNull { runCatching { it.asJsonObject.toCard() }.getOrNull() }
            .orEmpty()
        val total = root.int("total")
        SquarePage(
            playlists = list,
            // 服务端 total 偶尔为 0；此时用「本页是否装满」判断还有没有下一页
            more = root.bool("more") || (total <= 0 && list.size >= limit),
            total = total,
        )
    }

    /** 精品歌单：网易云官方挑选的高质量歌单，带 VIP 标识。 */
    suspend fun highQualityPlaylists(
        category: String = "",
        limit: Int = 20,
        before: Long = 0L,
        cookie: String? = null,
    ): SquarePage = withContext(Dispatchers.IO) {
        val sb = StringBuilder("$HIGHQUALITY_PATH?limit=$limit&order=desc")
        if (category.isNotBlank()) sb.append("&cat=${enc(category)}")
        if (before > 0L) sb.append("&before=$before")
        val root = getJson(sb.toString(), cookie) ?: return@withContext SquarePage()
        val arr = root.getAsJsonArray("playlists")
        val list = arr?.mapNotNull { runCatching { it.asJsonObject.toCard() }.getOrNull() }
            .orEmpty()
        // 精品歌单是游标翻页（before = 上一页最后一个歌单的更新时间戳）
        val cursor = arr?.lastOrNull()?.asJsonObject?.long("updateTime") ?: 0L
        SquarePage(playlists = list, more = root.bool("more") && list.isNotEmpty(), before = cursor)
    }

    // ═══════════════════════════════════════════════════════════════
    //  歌单详情
    // ═══════════════════════════════════════════════════════════════

    /**
     * 歌单详情 + 全量歌曲（榜单也走这里，榜单 id 就是歌单 id）。
     *
     * 传登录 Cookie 才能拿到 [NeteasePlaylistFull.subscribed]（当前账号是否已收藏），
     * 收藏按钮的状态就靠它 —— 匿名时它恒为 false，按钮显示成「收藏」而不是「已收藏」，
     * 点下去会与服务端状态冲突。
     */
    suspend fun playlistFull(
        playlistId: String,
        cookie: String? = null,
        limit: Int = 1000,
    ): NeteasePlaylistFull? = withContext(Dispatchers.IO) {
        val path = "$PLAYLIST_DETAIL_PATH?id=$playlistId&n=$limit&s=0"
        val root = getJson(path, cookie) ?: return@withContext null
        val r = root.getAsJsonObject("result") ?: return@withContext null
        val creator = r.getAsJsonObject("creator")
        NeteasePlaylistFull(
            id = r.long("id").takeIf { it > 0 } ?: playlistId.toLongOrNull() ?: 0L,
            name = r.str("name"),
            coverUrl = r.str("coverImgUrl"),
            description = r.str("description").replace(HEX_LINE_BREAK, "\n"),
            tags = r.getAsJsonArray("tags")?.mapNotNull {
                runCatching { it.asString }.getOrNull()
            }.orEmpty(),
            creatorName = creator?.str("nickname").orEmpty(),
            creatorAvatar = creator?.str("avatarUrl").orEmpty(),
            trackCount = r.int("trackCount"),
            playCount = r.long("playCount"),
            subscribedCount = r.long("subscribedCount"),
            subscribed = r.bool("subscribed"),
            createTime = r.long("createTime"),
            updateTime = r.long("updateTime"),
            songs = r.getAsJsonArray("tracks")
                ?.mapNotNull { runCatching { gson.fromJson(it, NeteaseSong::class.java) }.getOrNull() }
                .orEmpty(),
        )
    }

    /** 歌单歌曲翻页（v6 详情单次上限 1000，大歌单要继续往后翻）。 */
    suspend fun morePlaylistSongs(
        playlistId: String,
        offset: Int,
        limit: Int = 1000,
        cookie: String? = null,
    ): List<NeteaseSong> = withContext(Dispatchers.IO) {
        val root = getJson("$PLAYLIST_DETAIL_PATH?id=$playlistId&n=$limit&s=$offset", cookie)
            ?: return@withContext emptyList()
        root.getAsJsonObject("result")?.getAsJsonArray("tracks")
            ?.mapNotNull { runCatching { gson.fromJson(it, NeteaseSong::class.java) }.getOrNull() }
            .orEmpty()
    }

    // ═══════════════════════════════════════════════════════════════
    //  收藏歌单（写服务端）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 收藏 / 取消收藏歌单（**真写服务端**，换设备登录后依然在）。
     *
     * 端点：weapi `/weapi/playlist/{subscribe|unsubscribe}`，参数 `id` + `csrf_token`。
     * 与评论点赞同源的两个硬约束（都是 2026-10-11 真机踩出来的）：
     * 1. **必须带登录 Cookie**，匿名一律 301；
     * 2. **必须伪装安卓身份**（`os=android` + 安卓 UA），`os=pc` 会被判 250 设备风险。
     *
     * eapi 兜底通道签名相同，只是 URL 与 Cookie 身份不同。
     *
     * @return true 成功；false 失败（原因见日志）。
     */
    suspend fun setSubscribed(playlistId: String, subscribe: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            val cookie = accountStore.currentNeteaseCookie
            if (cookie.isNullOrBlank()) {
                Log.w(TAG, "收藏歌单失败：未登录网易云")
                return@withContext false
            }
            val action = if (subscribe) "subscribe" else "unsubscribe"
            val path = "/api/playlist/$action"
            val body = mapOf("id" to playlistId, "csrf_token" to csrfOf(cookie))
            val android = androidCookie(cookie)

            // weapi 官方客户端通道（music.163.com）
            val weapiText = postForm("https://music.163.com/weapi$path", NeteaseCrypto.weapi(body), android, ANDROID_UA)
            val code = codeOf(weapiText)
            Log.d(TAG, "playlist/$action weapi code=$code body=${weapiText.take(120)}")
            if (code == 200) return@withContext true

            // eapi 兜底：某些网络下 music.163.com 被风控，interface 域名仍然放行
            for (host in EAPI_HOSTS) {
                val text = postForm("$host/eapi$path", NeteaseCrypto.eapi(path, body), android, ANDROID_UA)
                val c = codeOf(text)
                Log.d(TAG, "playlist/$action eapi($host) code=$c body=${text.take(120)}")
                if (c == 200) return@withContext true
            }
            Log.w(TAG, "收藏歌单失败 id=$playlistId subscribe=$subscribe 全通道非 200")
            false
        }

    // ═══════════════════════════════════════════════════════════════
    //  HTTP
    // ═══════════════════════════════════════════════════════════════

    /** 裸 GET：`https://music.163.com` + `/api/xxx`（注意这一层**要带** /api 前缀，与 eapi 路径写法不同）。 */
    private fun getJson(path: String, cookie: String?): JsonObject? {
        val text = get("$HOST$path", requestCookie(cookie))
        if (text.isBlank()) {
            Log.w(TAG, "GET 空白 path=$path")
            return null
        }
        return runCatching {
            val reader = JsonReader(StringReader(text)).apply { isLenient = true }
            gson.fromJson<JsonObject>(reader, JsonObject::class.java)
        }.getOrNull()?.takeIf { it.has("code") && it.long("code") != 404L } ?: run {
            Log.w(TAG, "GET 404/解析失败 path=$path body=${text.take(150)}")
            null
        }
    }

    private fun get(url: String, cookie: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Referer", "https://music.163.com/")
            .header("Origin", "https://music.163.com")
            .header("Cookie", cookie)
            .get()
            .build()
        return runCatching {
            client.newCall(req).execute().use { if (it.isSuccessful) it.body?.string().orEmpty() else "" }
        }.getOrDefault("")
    }

    private fun postForm(url: String, form: Map<String, String>, cookie: String, ua: String): String {
        val body = form.entries.joinToString("&") { (k, v) ->
            "${enc(k)}=${enc(v)}"
        }
        val req = Request.Builder()
            .url(url)
            .post(body.toRequestBody(FORM))
            .header("User-Agent", ua)
            .header("Referer", "https://music.163.com/")
            .header("Origin", "https://music.163.com")
            .header("Cookie", cookie)
            .build()
        return runCatching {
            client.newCall(req).execute().use { if (it.isSuccessful) it.body?.string().orEmpty() else "" }
        }.getOrDefault("")
    }

    /** 有登录态用登录态（否则部分接口的 subscribed / 推荐结果不对），再补齐官方客户端必需字段。 */
    private fun requestCookie(cookie: String?): String {
        val base = cookie?.takeIf { it.isNotBlank() } ?: return ANON_COOKIE_PREFIX + randomHex(16)
        if (base.contains("os=") && base.contains("appver")) return base
        return "$base; os=pc; appver=$APPVER"
    }

    /** 写接口专用：把 cookie 改写成安卓客户端身份（os=android）。 */
    private fun androidCookie(base: String): String {
        val parts = LinkedHashMap<String, String>()
        base.split(";").forEach { seg ->
            val t = seg.trim()
            val i = t.indexOf('=')
            if (i > 0) parts[t.substring(0, i).trim()] = t.substring(i + 1).trim()
        }
        parts["os"] = "android"
        parts["appver"] = APPVER
        return parts.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun csrfOf(cookie: String): String =
        cookie.split(";").firstOrNull { it.trim().startsWith("__csrf=") }
            ?.substringAfter("__csrf=").orEmpty()

    private fun codeOf(text: String): Int {
        if (text.isBlank()) return 0
        return runCatching { JsonParser.parseString(text).asJsonObject.int("code") }.getOrDefault(0)
    }

    private fun enc(v: String): String = URLEncoder.encode(v, "UTF-8")

    private fun randomHex(n: Int): String {
        val bytes = ByteArray(n / 2)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun JsonObject.str(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    private fun JsonObject.long(key: String): Long =
        runCatching { get(key)?.asLong }.getOrNull() ?: 0L

    private fun JsonObject.int(key: String): Int =
        runCatching { get(key)?.asInt }.getOrNull() ?: 0

    private fun JsonObject.bool(key: String): Boolean =
        runCatching { get(key)?.asBoolean }.getOrNull() ?: false

    private fun JsonObject.toCard(): NeteasePlaylistCard? {
        val id = long("id")
        if (id <= 0L) return null
        return NeteasePlaylistCard(
            id = id,
            name = str("name"),
            coverUrl = str("coverImgUrl"),
            trackCount = int("trackCount"),
            playCount = long("playCount"),
            creatorName = getAsJsonObject("creator")?.str("nickname").orEmpty(),
            tags = getAsJsonArray("tags")?.mapNotNull { runCatching { it.asString }.getOrNull() }
                .orEmpty(),
            highQuality = bool("highQuality"),
        )
    }

    /** 歌单广场一页的结果。 */
    data class SquarePage(
        val playlists: List<NeteasePlaylistCard> = emptyList(),
        val more: Boolean = false,
        val total: Int = 0,
        /** 精品歌单的游标（下一页 before）。 */
        val before: Long = 0L,
    )

    companion object {
        private const val TAG = "NeteaseDiscover"
        private const val HOST = "https://music.163.com"

        /** 全部榜单（含官方榜 / 特色榜 / 全球榜）。 */
        const val TOPLISTS_PATH = "/api/toplist/detail"
        /** 歌单详情：带全量歌曲 + subscribed（是否已收藏）。 */
        const val PLAYLIST_DETAIL_PATH = "/api/playlist/detail"
        /** 歌单广场（分类 + 分页 + order）。 */
        const val SQUARE_PATH = "/api/playlist/list"
        /** 歌单分类标签。 */
        const val HIGHQUALITY_TAGS_PATH = "/api/playlist/highquality/tags"
        /** 精品歌单。 */
        const val HIGHQUALITY_PATH = "/api/top/playlist/highquality"

        /** 歌单评论的 threadId 前缀（歌曲是 R_SO_4_）。 */
        const val THREAD_PLAYLIST = "R_PL_0_"

        const val APPVER = "8.9.70"
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        /** 写接口（收藏）必须自称安卓客户端，否则一律 250「设备存在安全风险」。 */
        const val ANDROID_UA = "Mozilla/5.0 (Linux; Android 12; 25060RK16C) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 NeteaseMusic"
        const val ANON_COOKIE_PREFIX = "__remember_me=true; NMTID="
        val FORM = "application/x-www-form-urlencoded".toMediaType()
        val EAPI_HOSTS = listOf(
            "https://interface.music.163.com",
            "https://interface3.music.163.com",
        )

        /** 网易云歌单描述里的字面 `\n`（不是换行符），要换成真换行。 */
        val HEX_LINE_BREAK = Regex("\\\\n")

        /** 四个官方榜的 id → 名称。顺序即展示顺序。 */
        val OFFICIAL_TOPLISTS = linkedMapOf(
            19723756L to "飙升榜",
            3779629L to "新歌榜",
            2884035L to "原创榜",
            3778678L to "热歌榜",
        )
    }
}
