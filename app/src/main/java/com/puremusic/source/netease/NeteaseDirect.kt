package com.puremusic.source.netease

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
 * 网易云官方直连客户端（免登录）：搜索走 eapi、取流与歌词 weapi 优先 + eapi 兜底，不依赖自建后端。
 *
 * 接口路径与参数参考 lx-music-desktop `src/renderer/utils/musicSdk/wy/`（musicSearch.js /
 * musicInfo.js / crypto.js）与 NeteaseCloudMusicApi（module/song_url.js），密钥见 [NeteaseCrypto]。
 *
 * 2026-09 实测：匿名请求 weapi 端点（player/url、lyric）返回空 body，eapi 端点正常返回可用直链/歌词，
 * 因此实现为 weapi 优先、eapi 兜底的双通道策略；搜索聚合接口 /api/search/song/list/page 翻页有效，
 * 单页固定 20 条，需多页拉取以扩大原版召回池。
 */
@Singleton
class NeteaseDirect @Inject constructor(
    @Named("plain") private val client: OkHttpClient,
) {

    private val gson = Gson()

    /**
     * 聚合搜索池：并发拉取 3 页（offset 0/40/80，每页 20 条）合并，供上层过滤排序后取原版。
     * 网易云匿名聚合搜索把 AI/翻唱排前，原版常在深页，故多页召回。
     */
    suspend fun searchPool(keyword: String): List<NeteaseSong> = coroutineScope {
        listOf(0, 40, 80).map { offset ->
            async(Dispatchers.IO) { searchPage(keyword, offset) }
        }.awaitAll().flatten()
    }

    /** 单页 eapi 搜索：POST /eapi/search/song/list/page，解析新版 resources 聚合结构。 */
    private suspend fun searchPage(keyword: String, offset: Int): List<NeteaseSong> =
        withContext(Dispatchers.IO) {
            val form = NeteaseCrypto.eapi(
                SEARCH_PATH,
                mapOf(
                    "keyword" to keyword,
                    "needCorrect" to true,
                    "channel" to "netease",
                    "offset" to offset,
                    "scene" to "normal",
                    "total" to true,
                    "limit" to SEARCH_PAGE_LIMIT,
                ),
            )
            val text = postForm(SEARCH_URL, form) ?: return@withContext emptyList()
            val resp = runCatching { gson.fromJson(text, SearchPageResponse::class.java) }.getOrNull()
            if (resp?.code != 200) return@withContext emptyList()
            resp.data?.resources.orEmpty()
                .filter { it.resourceType == "song" && it.baseInfo?.simpleSongData != null }
                .map { it.baseInfo!!.simpleSongData!! }
        }

    /**
     * 取流：br 999000 → 320000 → 128000；每个音质先 weapi，空 url 则 eapi 兜底，返回首个可用直链。
     *
     * @param cookie 登录态 Cookie。传入时 VIP 账号才能拿到付费曲目的完整直链与无损音质；
     *               匿名请求即使携带 br=999000 也只会返回 null 或试听片段。
     *               注意 br 必须逐档降级：付费/版权受限曲目在高码率档位会直接返回空 url。
     */
    /**
     * 取流：新版 v1 接口（level + encodeType）优先，旧版 br 通道兜底。
     *
     * 2026-10 实测结论（务必照此实现，否则"登录了也播不了"）：
     * 1. 旧路径 `/api/song/enhance/player/url` 只认 `br` 参数，传 level/encodeType 会返回
     *    **400 参数错误**；
     * 2. 新路径 `/api/song/enhance/player/url/v1` 只认 `level` + `encodeType`；
     * 3. 服务端**偶发把同一份 JSON 连着返回两遍**，普通解析会直接抛异常（表现为"接口坏了"），
     *    必须用 lenient 解析只取首段；
     * 4. 无权限不再是空 url，而是 `data[0].code`：-110 无版权/需会员、-105 需购买、
     *    -460/-462 风控；试听片段用 `freeTrialInfo` 标注。
     *
     * @return 可用的直链；优先返回完整版，只有试听片段时才返回片段（并打日志）。
     */
    suspend fun songUrl(id: String, cookie: String? = null): String? = withContext(Dispatchers.IO) {
        val enriched = enrichCookie(cookie)
        val candidates = mutableListOf<SongUrlItem>()

        for ((level, encodeType) in LEVEL_ORDER) {
            fetchV1(id, level, encodeType, enriched)?.let { candidates += it }
            // 拿到完整直链就收手，别把 4 个档位全打一遍（每次都是一次网络往返）
            val got = candidates.firstOrNull { it.url.isNullOrBlank().not() && it.freeTrialInfo == null }
            if (got != null) {
                Log.d(TAG, "songUrl[$id] hit level=$level br=${got.br} type=${got.type}")
                return@withContext got.url
            }
        }
        // v1 全线失败（老接口在某些网络下更稳）
        for (br in BR_ORDER) {
            fetchLegacy(id, br, enriched)?.let { candidates += it }
            val got = candidates.firstOrNull { it.url.isNullOrBlank().not() && it.freeTrialInfo == null }
            if (got != null) {
                Log.d(TAG, "songUrl[$id] hit legacy br=$br -> ${got.br}")
                return@withContext got.url
            }
        }

        val trial = candidates.firstOrNull { it.url.isNullOrBlank().not() }
        if (trial != null) {
            Log.w(TAG, "songUrl[$id] 只有试听片段 ${trial.freeTrialInfo}")
            return@withContext trial.url
        }
        val reason = candidates.firstOrNull()?.takeIf { it.code != 200 }
        Log.w(TAG, "songUrl[$id] 无可用直链 code=${reason?.code} msg=${reason?.message}")
        null
    }

    /** 新版取流：POST /eapi/song/enhance/player/url/v1，参数 level + encodeType。 */
    private suspend fun fetchV1(
        id: String,
        level: String,
        encodeType: String,
        cookie: String?,
    ): SongUrlItem? {
        val text = postForm(
            PLAYER_EAPI_V1_URL,
            NeteaseCrypto.eapi(
                PLAYER_V1_PATH,
                mapOf("ids" to "[$id]", "level" to level, "encodeType" to encodeType),
            ),
            cookie,
        )
        return resolveItem(text)
    }

    /** 旧版取流：POST /eapi/song/enhance/player/url，参数 br。 */
    private suspend fun fetchLegacy(id: String, br: Int, cookie: String?): SongUrlItem? {
        val text = postForm(
            PLAYER_EAPI_URL,
            NeteaseCrypto.eapi(PLAYER_PATH, mapOf("ids" to "[$id]", "br" to br)),
            cookie,
        )
        return resolveItem(text)
    }

    /**
     * 解析取流响应。
     *
     * 注意：网易云**偶发把同一份 JSON 返回两遍**，Gson 严格解析会因尾部多余内容抛
     * JsonSyntaxException，直接表现为"所有歌都播不了"。这里用 lenient 的 JsonReader
     * 只读第一个对象，多余内容忽略。
     */
    private fun resolveItem(text: String?): SongUrlItem? {
        if (text.isNullOrBlank()) return null
        val resp = runCatching {
            val reader = JsonReader(StringReader(text)).apply { isLenient = true }
            gson.fromJson<SongUrlResponse>(reader, SongUrlResponse::class.java)
        }.getOrNull() ?: return null
        if (resp?.code != 200) return null
        return resp.data?.firstOrNull()
    }

    /**
     * 补齐官方客户端必备 Cookie 字段：`os=pc` / `appver` / `NMTID` / `__remember_me`。
     * 缺失这些字段时服务端会把请求当异常客户端处理，VIP 曲目只返回试听片段或空 url。
     */
    private fun enrichCookie(cookie: String?): String? {
        if (cookie.isNullOrBlank()) return null
        if (cookie.contains("os=") && cookie.contains("appver")) return cookie
        val nmtid = ByteArray(8).apply { java.security.SecureRandom().nextBytes(this) }
            .joinToString("") { "%02x".format(it) }
        val parts = LinkedHashMap<String, String>().apply {
            put("os", "pc")
            put("appver", "8.9.70")
            put("NMTID", nmtid)
            put("__remember_me", "true")
        }
        cookie.split(";").forEach { seg ->
            val t = seg.trim()
            val idx = t.indexOf('=')
            if (idx > 0) parts[t.substring(0, idx).trim()] = t.substring(idx + 1).trim()
        }
        return parts.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /** 歌词（含翻译）：weapi 优先，空/失败则 eapi 兜底；登录态传 cookie 可拿到逐字歌词。 */
    suspend fun lyric(id: String, cookie: String? = null): LyricResponse? = withContext(Dispatchers.IO) {
        val enriched = enrichCookie(cookie)
        // eapi 优先（weapi 端点在第三方客户端常被风控返回空 body）
        val eapiForm = NeteaseCrypto.eapi(
            LYRIC_PATH,
            mapOf("id" to id, "lv" to -1, "kv" to -1, "tv" to -1),
        )
        val eapiResp = postForm(LYRIC_EAPI_URL, eapiForm, enriched)?.let {
            runCatching { gson.fromJson(it, LyricResponse::class.java) }.getOrNull()
        }
        if (eapiResp?.code == 200 && !eapiResp.lrc?.lyric.isNullOrBlank()) return@withContext eapiResp

        val weapiForm = NeteaseCrypto.weapi(mapOf("id" to id, "lv" to -1, "kv" to -1, "tv" to -1))
        val weapiResp = postForm(LYRIC_URL, weapiForm, enriched)?.let {
            runCatching { gson.fromJson(it, LyricResponse::class.java) }.getOrNull()
        }
        if (weapiResp?.code == 200 && !weapiResp.lrc?.lyric.isNullOrBlank()) return@withContext weapiResp
        eapiResp ?: weapiResp
    }

    /**
     * 我的云端歌单：eapi 优先、weapi 兜底，**必须带登录 Cookie**（含 MUSIC_U）。
     *
     * 之前这里走 Retrofit 打自建后端（NeteaseCloudMusicApi），手机装 APK 后根本连不上电脑上的
     * 服务 —— 表现为「点网易云永远 0 个歌单」。改为官方直连后与手机网络无关，只要有登录态即可。
     */
    suspend fun userPlaylists(uid: String, cookie: String?): List<NeteasePlaylistSummary> =
        withContext(Dispatchers.IO) {
            if (cookie.isNullOrBlank() || uid.isBlank()) return@withContext emptyList()
            val enriched = enrichCookie(cookie)
            val body = mapOf(
                "uid" to uid,
                "limit" to PLAYLIST_LIMIT,
                "offset" to 0,
                "includeVideo" to true,
            )
            val fromEapi = postForm(
                USER_PLAYLIST_EAPI_URL,
                NeteaseCrypto.eapi(USER_PLAYLIST_PATH, body),
                enriched,
            )
            parseUserPlaylists(fromEapi)?.takeIf { it.isNotEmpty() }?.let {
                Log.d(TAG, "userPlaylists eapi ok n=${it.size}")
                return@withContext it
            }
            val fromWeapi = postForm(
                USER_PLAYLIST_WEAPI_URL,
                NeteaseCrypto.weapi(body),
                enriched,
            )
            val parsed = parseUserPlaylists(fromWeapi) ?: emptyList()
            Log.d(TAG, "userPlaylists weapi n=${parsed.size}")
            parsed
        }

    private fun parseUserPlaylists(text: String?): List<NeteasePlaylistSummary>? {
        if (text.isNullOrBlank()) return null
        return runCatching {
            val reader = JsonReader(StringReader(text)).apply { isLenient = true }
            gson.fromJson<UserPlaylistResponse>(reader, UserPlaylistResponse::class.java)
        }.getOrNull()?.takeIf { it.code == 200 }?.playlist
    }

    /**
     * 歌单详情（全部歌曲）：/api/v6/playlist/detail。
     *
     * v6 一次可返回整张歌单（n 给到 1000），比旧 /api/playlist/detail 只能拿前 10 首更实用。
     */
    suspend fun playlistSongs(playlistId: String, cookie: String?): List<NeteaseSong> =
        withContext(Dispatchers.IO) {
            val enriched = enrichCookie(cookie)
            val body = mapOf("id" to playlistId, "n" to PLAYLIST_LIMIT, "s" to 8)
            val fromEapi = postForm(
                PLAYLIST_DETAIL_EAPI_URL,
                NeteaseCrypto.eapi(PLAYLIST_DETAIL_PATH, body),
                enriched,
            )
            parsePlaylistDetail(fromEapi)?.tracks?.takeIf { it.isNotEmpty() }?.let {
                Log.d(TAG, "playlistSongs eapi ok n=${it.size}")
                return@withContext it
            }
            val fromWeapi = postForm(
                PLAYLIST_DETAIL_WEAPI_URL,
                NeteaseCrypto.weapi(body),
                enriched,
            )
            val tracks = parsePlaylistDetail(fromWeapi)?.tracks.orEmpty()
            Log.d(TAG, "playlistSongs weapi n=${tracks.size}")
            tracks
        }

    private fun parsePlaylistDetail(text: String?): NeteasePlaylistDetail? {
        if (text.isNullOrBlank()) return null
        return runCatching {
            val reader = JsonReader(StringReader(text)).apply { isLenient = true }
            gson.fromJson<PlaylistDetailResponse>(reader, PlaylistDetailResponse::class.java)
        }.getOrNull()?.takeIf { it.code == 200 }?.playlist
    }

    /** 用登录 Cookie 反查 uid（账号入库时没存 uid 时的兜底）。 */
    suspend fun currentUid(cookie: String): String? = withContext(Dispatchers.IO) {
        val enriched = enrichCookie(cookie) ?: return@withContext null
        val body = mapOf("csrf_token" to "")
        val text = postForm(
            ACCOUNT_WEAPI_URL,
            NeteaseCrypto.weapi(body),
            enriched,
        ) ?: return@withContext null
        runCatching {
            JsonParser.parseString(text).asJsonObject
                .getAsJsonObject("profile")?.get("userId")?.asString
        }.getOrNull()
    }

    private fun resolveUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val resp = runCatching { gson.fromJson(text, SongUrlResponse::class.java) }.getOrNull()
            ?: return null
        if (resp.code != 200) return null
        return resp.data?.firstOrNull()?.url?.takeIf { it.isNotBlank() }
    }

    /** 表单 POST：eapi/weapi 均返回 application/x-www-form-urlencoded 的 params（及 encSecKey）。 */
    private fun postForm(url: String, form: Map<String, String>, cookie: String? = null): String? {
        return try {
            val body = form.entries.joinToString("&") { (k, v) ->
                "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
            }
            val request = Request.Builder()
                .url(url)
                .post(body.toRequestBody(FORM))
                .apply {
                    // 登录态必须带 Cookie，否则服务端按匿名处理，付费曲目只给试听片段。
                    if (!cookie.isNullOrBlank()) header("Cookie", cookie)
                }
                .header("User-Agent", UA)
                .header("Origin", "https://music.163.com")
                .header("Referer", "https://music.163.com/")
                .build()
            client.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private companion object {
        const val SEARCH_PATH = "/api/search/song/list/page"
        const val SEARCH_URL = "https://interface.music.163.com/eapi/search/song/list/page"
        const val SEARCH_PAGE_LIMIT = 50

        const val PLAYER_PATH = "/api/song/enhance/player/url"
        const val PLAYER_URL = "https://interface.music.163.com/weapi/song/enhance/player/url"
        const val PLAYER_EAPI_URL = "https://interface3.music.163.com/eapi/song/enhance/player/url"

        /** 新版取流端点：只接受 level + encodeType（传 br 会 400）。 */
        const val PLAYER_V1_PATH = "/api/song/enhance/player/url/v1"
        const val PLAYER_EAPI_V1_URL = "https://interface.music.163.com/eapi/song/enhance/player/url/v1"

        /** 我的歌单列表。 */
        const val USER_PLAYLIST_PATH = "/api/user/playlist"
        const val USER_PLAYLIST_EAPI_URL = "https://interface.music.163.com/eapi/user/playlist"
        const val USER_PLAYLIST_WEAPI_URL = "https://interface.music.163.com/weapi/user/playlist"

        /** 歌单详情（v6 一次拿全量歌曲）。 */
        const val PLAYLIST_DETAIL_PATH = "/api/v6/playlist/detail"
        const val PLAYLIST_DETAIL_EAPI_URL = "https://interface.music.163.com/eapi/v6/playlist/detail"
        const val PLAYLIST_DETAIL_WEAPI_URL = "https://interface.music.163.com/weapi/v6/playlist/detail"

        /** 账号信息（反查 uid）。 */
        const val ACCOUNT_WEAPI_URL = "https://interface.music.163.com/weapi/w/nuser/account/get"

        const val PLAYLIST_LIMIT = 1000

        const val LYRIC_PATH = "/api/song/lyric"
        const val LYRIC_URL = "https://interface.music.163.com/weapi/song/lyric"
        const val LYRIC_EAPI_URL = "https://interface3.music.163.com/eapi/song/lyric"

        val FORM = "application/x-www-form-urlencoded".toMediaType()
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        /** 旧版音质码兜底：优先高码率（999000=无损），空 url 时依次降级。 */
        val BR_ORDER = listOf(999000, 320000, 128000)

        /**
         * 音质档位（高 → 低）。服务端会按账号权限自动降级：
         * 非会员请求 lossless 也只会拿到 320k mp3，会员才会给 flac/999k。
         */
        val LEVEL_ORDER = listOf(
            "lossless" to "flac",
            "exhigh" to "aac",
            "higher" to "aac",
            "standard" to "aac",
        )
        const val TAG = "NeteaseDirect"
    }
}
