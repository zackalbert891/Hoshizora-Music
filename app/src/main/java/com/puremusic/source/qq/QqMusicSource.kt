package com.puremusic.source.qq

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.puremusic.data.AccountStore
import com.puremusic.data.SettingsStore
import com.puremusic.lyrics.LrcParser
import com.puremusic.model.Account
import com.puremusic.model.Lyrics
import com.puremusic.model.MusicSourceType
import com.puremusic.model.Playlist
import com.puremusic.model.Song
import com.puremusic.source.MusicSource
import com.puremusic.ui.login.QqLoginManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/** QQ 音乐搜索响应（JSONP 内层 JSON）。 */
data class QqSearchResponse(val code: Int = 0, val data: QqData? = null)
data class QqData(val song: QqSongContainer? = null)
data class QqSongContainer(val list: List<QqSong>? = null)
data class QqSong(
    val mid: String = "",
    val name: String = "",
    val singer: List<QqSinger>? = null,
    val album: QqAlbum? = null,
    val interval: Int = 0,
)
data class QqSinger(val name: String = "")
data class QqAlbum(val name: String = "", val mid: String? = null)

/** smartbox 搜索建议响应（client_search_cp 失效时的兜底）。 */
data class SmartboxResponse(val code: Int = 0, val data: SmartboxData? = null)
data class SmartboxData(val song: SmartboxSong? = null)
data class SmartboxSong(val itemlist: List<SmartboxItem>? = null)
data class SmartboxItem(
    val id: String = "",
    val mid: String = "",
    val name: String = "",
    val singer: String = "",
    val pic: String? = null,
)

/** 官方 App 搜索 CGI 响应：req_1.data.body.song.list（含 interval 时长，smartbox 没有）。 */
data class QqCgiSearchResponse(val code: Int = 0, val req_1: QqCgiSearchReq1? = null)
data class QqCgiSearchReq1(val code: Int = 0, val data: QqCgiSearchData? = null)
data class QqCgiSearchData(val body: QqCgiSearchBody? = null)
data class QqCgiSearchBody(val song: QqCgiSongList? = null)
data class QqCgiSongList(val list: List<QqCgiSong>? = null, val totalnum: Int = 0)
data class QqCgiSong(
    val mid: String = "",
    val name: String = "",
    val interval: Int = 0,
    val singer: List<QqSinger>? = null,
    val album: QqAlbum? = null,
    val file: QqFile? = null,
)

/** vkey 取流响应。 */
data class QqVkeyResponse(val code: Int = 0, val req_1: QqVkeyReq1? = null)
data class QqVkeyReq1(val code: Int = 0, val data: QqVkeyData? = null)
data class QqVkeyData(val sip: List<String>? = null, val midurlinfo: List<QqMidUrlInfo>? = null)
/**
 * vkey 单曲结果。
 *
 * 无会员时 [purl] 为空（result=101404），但服务端仍会给 30 秒试听直链 [opi30surl]；
 * 保留该字段才能在「所有源都无完整版」时至少出声，而不是直接播放失败。
 */
data class QqMidUrlInfo(
    val purl: String? = null,
    val opi30surl: String? = null,
    val wifiurl: String? = null,
)

/** 歌词响应。 */
data class QqLyricResponse(val lyric: String? = null, val trans: String? = null)

/** 歌单详情响应。 */
data class QqPlaylistDetailResp(val req_0: QqPlaylistDetailReq0? = null)
data class QqPlaylistDetailReq0(val code: Int = 0, val data: QqPlaylistDetailData? = null)
data class QqPlaylistDetailData(
    val dirinfo: QqDirinfo? = null,
    val songlist: List<QqSongDetail>? = null,
)
data class QqDirinfo(val title: String = "", val name: String = "")
data class QqSongDetail(
    val mid: String = "",
    val name: String = "",
    val singer: List<QqSingerDetail>? = null,
    val album: QqAlbumDetail? = null,
    val interval: Int = 0,
    val file: QqFile? = null,
)
data class QqFile(val media_mid: String = "")
data class QqSingerDetail(val name: String = "")
data class QqAlbumDetail(val name: String = "", val mid: String = "")

/**
 * QQ 音乐源：搜索、取流、歌词、歌单（我的歌单 + 歌单详情）。
 */
@Singleton
class QqMusicSource @Inject constructor(
    @Named("plain") private val client: OkHttpClient,
    private val accountStore: AccountStore,
    private val settingsStore: SettingsStore,
    private val qqLogin: QqLoginManager,
) : MusicSource {

    private val gson = Gson()

    /** 自动续期失败后的冷却时长：冷却期内不再发起续期请求，避免无效高频重试。 */
    private val refreshCooldownMs = 10 * 60 * 1000L

    /** 最近一次自动续期尝试的时间戳（失败后记录，用于冷却判定）。 */
    @Volatile
    private var lastRefreshAttemptAt = 0L

    override val type = MusicSourceType.QQ
    override val displayName = "QQ音乐"
    override val supportsLogin = true

    /**
     * 搜索：`client_search_cp` 为主，`smartbox_new` 兜底。
     *
     * 2026-10 实测：`c.y.qq.com/soso/fcgi-bin/client_search_cp` 已对匿名请求返回 500（接口下线/限流），
     * 而 `splcloud/smartbox_new.fcg`（搜索建议）仍返回带 songmid 的歌曲条目，故作为兜底通道，
     * 保证 QQ 音源在匿名状态下仍能参与聚合搜索与跨源换源。
     */
    override suspend fun search(keyword: String, page: Int, limit: Int): List<Song> =
        withContext(Dispatchers.IO) {
            if (page > 0) return@withContext emptyList() // 兜底接口不支持翻页
            // 主通道：官方 App 搜索 CGI（结果最全，且**带 interval 时长**；
            // smartbox 只是搜索建议，既漏结果也不返回时长 → 时长全 0 会让跨源匹配失效）
            val fromCgi = searchViaCgi(keyword, limit)
            if (fromCgi.isNotEmpty()) return@withContext fromCgi
            // 次选：smartbox 搜索建议
            val fromSmart = searchViaSmartbox(keyword, limit)
            if (fromSmart.isNotEmpty()) return@withContext fromSmart
            // 兜底：client_search_cp（个别登录态 / 网络下仍可能可用）
            val kw = URLEncoder.encode(keyword, "UTF-8")
            val url = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp" +
                "?w=$kw&format=json&p=1&n=$limit&new_json=1"
            val body = runCatching {
                client.newCall(
                    Request.Builder().url(url).header("User-Agent", UA).build()
                ).execute().body?.string()
            }.getOrNull()
            body?.let {
                runCatching { gson.fromJson(stripJsonp(it), QqSearchResponse::class.java) }.getOrNull()
            }?.data?.song?.list.orEmpty().map { it.toSong() }
        }

    /**
     * 主搜索：官方 App 用的搜索 CGI（music.search.SearchCgiService / DoSearchForQQMusicDesktop）。
     *
     * 相比 smartbox 的两大优势：① 结果完整（smartbox 只是前缀建议，中文歌名常漏）；
     * ② **带 interval 时长** —— 时长是跨源同曲判定的硬指标，缺了它 QQ 歌曲永远匹配不上别的源。
     *
     * 走 POST u.y.qq.com/cgi-bin/musicu.fcg，comm 需带登录态（uin + authst），与取流同源。
     */
    private suspend fun searchViaCgi(keyword: String, limit: Int): List<Song> {
        accountStore.awaitLoaded()
        val account = accountStore.accounts.value[type]
        val cookie = account?.cookie.orEmpty()
        val authst = listOf("qqmusic_key", "qm_keyst", "musickey")
            .map { cookieField(cookie, it) }.firstOrNull { it.isNotBlank() }.orEmpty()
        val uin = cookieField(cookie, "uin").takeIf { it.isNotBlank() }
            ?: account?.userId?.takeIf { it.isNotBlank() } ?: "0"
        // ct/cv 必须是 11/11（PC 客户端标识）：实测 ct=24&cv=0 时后端虽返回 code=0，
        // 但 meta.sum 恒为 0、song.list 为空 —— 表现为「QQ 搜索永远 0 条」。
        val comm = buildString {
            append("\"uin\":\"$uin\",\"format\":\"json\",\"ct\":11,\"cv\":11,\"tmeLoginType\":2")
            if (authst.isNotBlank()) append(",\"authst\":\"$authst\"")
        }
        // param 必须保持「裸参数」：实测额外带 grp / nqc_flag / remoteplace 会让 meta.sum 归零
        val json = """{"req_1":{"module":"music.search.SearchCgiService","method":"DoSearchForQQMusicDesktop","param":{"query":"$keyword","page_num":1,"num_per_page":$limit,"search_type":0}},"comm":{$comm}}"""
        val builder = Request.Builder()
            .url("https://u.y.qq.com/cgi-bin/musicu.fcg")
            .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Referer", "https://y.qq.com/")
            .header("User-Agent", UA)
        if (cookie.isNotBlank()) builder.header("Cookie", cookie)
        val text = runCatching { client.newCall(builder.build()).execute().body?.string() }
            .getOrNull()
        if (text.isNullOrBlank()) return emptyList()
        val resp = runCatching { gson.fromJson(text, QqCgiSearchResponse::class.java) }.getOrNull()
        val list = resp?.req_1?.data?.body?.song?.list.orEmpty()
        val rawFirst = runCatching {
            JsonParser.parseString(text).asJsonObject
                .getAsJsonObject("req_1").getAsJsonObject("data")
                .getAsJsonObject("body").getAsJsonObject("song")
                .getAsJsonArray("list").get(0).toString()
        }.getOrDefault("")
        android.util.Log.d("QqMusic", "searchCgi[$keyword] n=${list.size} first=$rawFirst")
        return list.map { it.toSong() }
    }

    private fun QqCgiSong.toSong(): Song = Song(
        source = MusicSourceType.QQ,
        id = mid,
        title = name,
        artist = singer.orEmpty().joinToString(" / ") { it.name }.ifBlank { "未知歌手" },
        album = album?.name.orEmpty(),
        coverUrl = "",
        durationMs = interval * 1000L,
        extra = buildMap {
            file?.media_mid?.takeIf { it.isNotBlank() }?.let { put(MEDIA_MID_KEY, it) }
            album?.mid?.takeIf { !it.isNullOrBlank() }?.let { put("albumMid", it) }
        },
    )

    /** 兜底/主搜索：smartbox 搜索建议接口（返回 song / album / singer 三类，取 song 段）。 */
    private suspend fun searchViaSmartbox(keyword: String, limit: Int): List<Song> =
        withContext(Dispatchers.IO) {
            val kw = URLEncoder.encode(keyword, "UTF-8")
            val url = "https://c.y.qq.com/splcloud/fcgi-bin/smartbox_new.fcg" +
                "?is_xml=0&format=jsonp&key=$kw&g_tk=5381&loginUin=0&hostUin=0" +
                "&format=json&inCharset=utf8&outCharset=utf-8"
            val body = runCatching {
                client.newCall(
                    Request.Builder().url(url).header("User-Agent", UA)
                        .header("Referer", "https://y.qq.com/").build()
                ).execute().body?.string()
            }.getOrNull() ?: return@withContext emptyList()
            val resp = runCatching {
                gson.fromJson(stripJsonp(body), SmartboxResponse::class.java)
            }.getOrNull()
            resp?.data?.song?.itemlist.orEmpty().take(limit).map { it.toSong() }
        }

    override suspend fun getSongUrl(song: Song): String? = getQqMusicUrl(song)

    private suspend fun getQqMusicUrl(song: Song): String? = withContext(Dispatchers.IO) {
        // 先确保登录态已从磁盘加载：否则冷启动后立刻点歌会按匿名取流，版权歌一律失败
        accountStore.awaitLoaded()
        // 跨源换源后的歌曲真实平台 id 在 extra["srcId"]（原 id 已保留给上层 UI）
        val songMid = song.extra[com.puremusic.source.autoswitch.AutoSwitchSource.SRC_ID_KEY] ?: song.id
        val mediaMid = song.extra[MEDIA_MID_KEY]?.takeIf { it.isNotBlank() }
        // filename 必须拼 **media_mid**（file.media_mid），拼 songmid 会被判文件不存在（result=101404）。
        // 搜索 CGI 返回的条目带 media_mid，故优先用它；缺失时再退化成 songmid 试一次。
        if (mediaMid != null) {
            requestWithFilenames(songMid, mediaMid)?.let { return@withContext it }
        }
        if (mediaMid != songMid) {
            requestWithFilenames(songMid, songMid)?.let { return@withContext it }
        }
        null
    }

    /**
     * 用给定 mid 走完整取流流程（登录态 → 续期 → 匿名降级）。
     *
     * @param songMid 请求参数 songmid（歌曲 id）
     * @param fileKey filename 前缀用的 id（media_mid 优先）
     */
    private suspend fun requestWithFilenames(songMid: String, fileKey: String): String? {
        val account = accountStore.accounts.value[type]
        val cookie = account?.cookie.orEmpty()
        // vkey 需要的 uin 是「真实 QQ 号」，QR 登录时写入 cookie 的 uin 字段；
        // 若 cookie 无 uin（如手机号登录），回退到 musicid（QQ 音乐开放平台 uin）。
        val uin = cookieField(cookie, "uin").takeIf { it.isNotBlank() }
            ?: account?.userId?.takeIf { it.isNotBlank() } ?: "0"
        // 无损 FLAC 排首位：非绿钻账号 QQ 不会返回该格式的直链，会自动往后降级到 320/128/m4a，
        // 所以不需要（也不能）再用一个设置开关去控制——UI 上那个「SVIP 无损音质」已删。
        val filenames = listOf(
            "F000$fileKey.flac",
            "M800$fileKey.mp3",
            "M500$fileKey.mp3",
            "C400$fileKey.m4a",
        )
        // guid 必须与取流 cookie 里的一致；cookie 无 guid 时用本次会话稳定 guid（避免 104009 invalidq）
        val guid = cookieField(cookie, "guid").takeIf { it.isNotBlank() } ?: SESSION_GUID
        // 第一次请求
        val (url, expired) = requestVkeyUrl(songMid, fileKey, cookie, uin, guid, filenames)
        if (url != null) {
            // 播放直链短时效，无需记录完整 URL（避免日志堆积与泄露）
            return url
        }
        // 凭证过期（104400 / 104401）：用 refresh_key 自动续期后重试一次
        if (!expired) return null
        // 续期失败冷却：最近一次续期失败后 10 分钟内不再重复发起，直接尝试匿名降级
        val sinceLast = System.currentTimeMillis() - lastRefreshAttemptAt
        if (lastRefreshAttemptAt > 0 && sinceLast < refreshCooldownMs) {
            android.util.Log.d("QqMusic", "凭证续期处于冷却期，跳过自动续期")
            return tryAnonymousUrl(songMid, fileKey)
        }
        val refreshedCookie = refreshQqCredential() ?: return tryAnonymousUrl(songMid, fileKey)
        val retryUin = cookieField(refreshedCookie, "uin").takeIf { it.isNotBlank() }
            ?: accountStore.accounts.value[type]?.userId?.takeIf { it.isNotBlank() } ?: uin
        val retryGuid = cookieField(refreshedCookie, "guid").takeIf { it.isNotBlank() } ?: guid
        val (retryUrl, _) = requestVkeyUrl(
            songMid, fileKey, refreshedCookie, retryUin, retryGuid, filenames,
        )
        if (retryUrl != null) {
            return retryUrl
        }
        // 续期成功仍取流失败：最后一次尝试匿名公开接口（登录失效降级匿名）
        return tryAnonymousUrl(songMid, fileKey)
    }

    /**
     * 登录态失效 / 取流失败时的匿名降级（参考 MintMusic：uin=0 + 随机 guid + 无 Cookie）。
     * 匿名接口通常只能拿到 128k / m4a，但可保证"登录过期仍能继续播放"。
     */
    private suspend fun tryAnonymousUrl(songMid: String, fileKey: String): String? {
        val (url, _) = requestVkeyUrl(
            songMid, fileKey, "", "0", SESSION_GUID, listOf("M500$fileKey.mp3", "C400$fileKey.m4a"),
        )
        if (url != null) {
            android.util.Log.w("QqMusic", "QQ 登录态不可用，已降级匿名取流继续播放")
        }
        return url
    }

    /**
     * 依次用候选 filename 请求 vkey，返回（播放地址，是否遇到凭证过期错误）。
     *
     * @param songMid 请求参数 songmid（歌曲 id）
     * @param fileKey filename 前缀（media_mid）；仅用于日志与调试定位
     */
    private suspend fun requestVkeyUrl(
        songMid: String,
        fileKey: String,
        cookie: String,
        uin: String,
        guid: String,
        filenames: List<String>,
    ): Pair<String?, Boolean> = withContext(Dispatchers.IO) {
        var expired = false
        // 新版 QQ 音乐登录态用 qqmusic_key / qm_keyst（旧版为 musickey）；CGI 的 comm.authst 需显式携带授权票据，
        // 否则服务端认不出登录态，版权歌一律取流失败（retcode 104003）。
        val authst = listOf("qqmusic_key", "qm_keyst", "musickey")
            .map { cookieField(cookie, it) }.firstOrNull { it.isNotBlank() }.orEmpty()
        for (filename in filenames) {
            // ct/cv 与搜索同理：11/11 才能被后端当作合法 PC 客户端，否则 purl 恒空（result=104003）
            val commFields = buildString {
                append("\"uin\":\"$uin\",\"format\":\"json\",\"ct\":11,\"cv\":11,\"tmeLoginType\":2")
                if (authst.isNotBlank()) append(",\"authst\":\"$authst\"")
            }
            val json = """{"req_1":{"module":"vkey.GetVkeyServer","method":"CgiGetVkey","param":{"filename":["$filename"],"guid":"$guid","songmid":["$songMid"],"songtype":[0],"uin":"$uin","loginflag":1,"platform":"20"}},"loginUin":"$uin","comm":{$commFields}}"""
            // guid 必须随 cookie 一起下发，否则服务端返回 104009 invalidq 拒绝取流
            val fullCookie = if (cookie.isNotBlank() && !cookie.contains("guid=")) {
                "$cookie; guid=$guid"
            } else cookie
            val builder = Request.Builder()
                .url("https://u.y.qq.com/cgi-bin/musicu.fcg")
                .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .header("Referer", "https://y.qq.com/")
            if (fullCookie.isNotBlank()) builder.header("Cookie", fullCookie)
            val text = runCatching { client.newCall(builder.build()).execute().body?.string() }
                .getOrNull() ?: continue
            android.util.Log.d(
                "QqMusic",
                "vkey songmid=$songMid fileKey=$fileKey uin=$uin guid=$guid file=$filename authst=${authst.isNotBlank()} " +
                    "ck=${fullCookie.isNotBlank()} :: ${text.take(1400)}",
            )
            if (hasCredentialExpiredCode(text)) expired = true
            val resp = runCatching { gson.fromJson(text, QqVkeyResponse::class.java) }
                .getOrNull() ?: continue
            val data = resp.req_1?.data ?: continue
            val info = data.midurlinfo?.firstOrNull() ?: continue
            val sip = data.sip?.firstOrNull() ?: "http://aqqmusic.tc.qq.com/"
            val purl = info.purl?.takeIf { it.isNotBlank() }
            if (purl != null) {
                // 用 HTTPS 播放（QQ 的 CDN 同时支持 http/https）
                return@withContext (sip + purl).replaceFirst("http://", "https://") to expired
            }
            // 无权限（result=101404，多为未开绿钻）：退回 30 秒试听直链
            val trial = info.opi30surl?.takeIf { it.isNotBlank() }
                ?: info.wifiurl?.takeIf { it.isNotBlank() }
            if (trial != null) {
                android.util.Log.w("QqMusic", "QQ 无完整权限，改用 30s 试听: $filename")
                return@withContext (sip + trial).replaceFirst("http://", "https://") to expired
            }
        }
        null to expired
    }

    /** guid 必须与 Cookie 里的 guid 一致，否则 VIP 会被服务端识别为异常。 */
    private fun guidOf(cookie: String): String =
        cookieField(cookie, "guid").takeIf { it.isNotBlank() } ?: SESSION_GUID

    /** 从 cookie 串中读取单个键值（与 QqLoginManager.cookieField 同义）。 */
    private fun cookieField(cookie: String, name: String): String =
        Regex("(?:^|;)\\s*$name=([^;]*)").find(cookie)?.groupValues?.get(1).orEmpty()

    /**
     * 用已持久化的 refresh_key / refresh_token 自动续期一次，并把新凭证回写 AccountStore。
     *
     * 协议依据：luren-dc/QQMusicApi 的 refresh_credential（服务 music.login.LoginServer、
     * 方法 Login），QQ（loginType=2）携带 openid / access_token / refresh_token / musickey /
     * refresh_key，返回结构与登录一致（musickey / refresh_key / refresh_token / keyExpiresIn）。
     * 具体请求构造见 QqLoginManager.refreshCredential。
     *
     * @return 续期后的完整 cookie；失败返回 null（由调用方按原错误返回，必要时提示重新登录）。
     */
    private suspend fun refreshQqCredential(): String? {
        val account = accountStore.accounts.value[type] ?: return null
        val result = runCatching { qqLogin.refreshCredential(account) }.getOrNull()
        if (result == null || result.status != "DONE" || result.musickey.isBlank()) {
            // 续期失败：记录时间戳进入冷却期，避免每次取流失败都重复发起续期请求
            lastRefreshAttemptAt = System.currentTimeMillis()
            android.util.Log.w("QqMusic", "凭证自动续期失败: ${result?.message}")
            return null
        }
        // 续期成功：重置冷却标记
        lastRefreshAttemptAt = 0L
        val updated = account.copy(
            userId = result.musicid.ifBlank { account.userId },
            cookie = result.cookie,
            refreshKey = result.refreshKey.ifBlank { account.refreshKey.orEmpty() },
            refreshToken = result.refreshToken.ifBlank { account.refreshToken.orEmpty() },
            credentialExpiresAt = result.expiresAt.takeIf { it > 0 } ?: account.credentialExpiresAt,
        )
        accountStore.save(updated)
        android.util.Log.d("QqMusic", "凭证已自动续期，musicid=${updated.userId}")
        return updated.cookie
    }

    /** 判断 CGI 响应里是否含凭证过期错误码（1000 / 104400 / 104401）。 */
    private fun hasCredentialExpiredCode(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val root = runCatching { JsonParser.parseString(text) }.getOrNull() ?: return false
        return runCatching { scanExpiredCode(root) }.getOrDefault(false)
    }

    /** 递归扫描响应中的 code / subcode 字段，命中过期错误码即返回 true。 */
    private fun scanExpiredCode(element: JsonElement): Boolean {
        when {
            element.isJsonObject -> {
                for ((key, value) in element.asJsonObject.entrySet()) {
                    val hit = (key.equals("code", true) || key.equals("subcode", true)) &&
                        codeOf(value)?.let { it in CREDENTIAL_EXPIRED_CODES } == true
                    if (hit || scanExpiredCode(value)) return true
                }
            }
            element.isJsonArray -> {
                for (item in element.asJsonArray) if (scanExpiredCode(item)) return true
            }
        }
        return false
    }

    /** code 字段可能是数字或字符串，统一取整数。 */
    private fun codeOf(element: JsonElement): Int? {
        if (!element.isJsonPrimitive) return null
        val p = element.asJsonPrimitive
        return when {
            p.isNumber -> runCatching { p.asInt }.getOrNull()
            p.isString -> p.asString.toIntOrNull()
            else -> null
        }
    }

    override suspend fun getLyrics(song: Song): Lyrics = withContext(Dispatchers.IO) {
        // 跨源换源后的歌曲真实平台 id 在 extra["srcId"]
        val mid = song.extra[com.puremusic.source.autoswitch.AutoSwitchSource.SRC_ID_KEY] ?: song.id
        val account = accountStore.accounts.value[type]
        val musickey = account?.cookie
            ?.let { Regex("musickey=([^;]+)").find(it)?.groupValues?.get(1) }
            .orEmpty()
        val gtk = if (musickey.isNotBlank()) hash33(musickey, 5381) else 5381
        val url = "https://i.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg" +
            "?songmid=$mid&g_tk=$gtk&format=json&inCharset=utf8&outCharset=utf-8&nobase64=1"
        val text = runCatching {
            client.newCall(
                Request.Builder().url(url).header("Referer", "https://y.qq.com/").build()
            ).execute().body?.string()
        }.getOrNull() ?: return@withContext Lyrics()
        val resp = runCatching { gson.fromJson(text, QqLyricResponse::class.java) }
            .getOrNull() ?: return@withContext Lyrics()
        Lyrics(
            lines = resp.lyric?.let { LrcParser.parse(it) } ?: emptyList(),
            translation = resp.trans?.let { LrcParser.parse(it) } ?: emptyList(),
        )
    }

    /**
     * 我的云端歌单：music.musicasset.PlaylistBaseRead / GetPlaylistByUin。
     *
     * 字段用「多候选键」手动解析而不是 Gson data class —— QQ 各版本 CGI 返回的键名
     * 大小写与命名不一致（tid/dirid、dirName/title、songNum/songnum），写死一个键名
     * 极易解析出 0 条，表现为「登录了却看不到歌单」。
     */
    override suspend fun getUserPlaylists(): List<Playlist> = withContext(Dispatchers.IO) {
        accountStore.awaitLoaded()
        val account = accountStore.accounts.value[type] ?: return@withContext emptyList()
        val cookie = account.cookie
        val authst = listOf("qqmusic_key", "qm_keyst", "musickey")
            .map { cookieField(cookie, it) }.firstOrNull { it.isNotBlank() }.orEmpty()
        val uin = cookieField(cookie, "uin").takeIf { it.isNotBlank() }
            ?: account.userId.takeIf { it.isNotBlank() } ?: "0"
        val comm = buildString {
            append("\"uin\":\"$uin\",\"format\":\"json\",\"ct\":11,\"cv\":11,\"tmeLoginType\":2")
            if (authst.isNotBlank()) append(",\"authst\":\"$authst\"")
        }
        val json = """{"req_0":{"module":"music.musicasset.PlaylistBaseRead","method":"GetPlaylistByUin","param":{"uin":"$uin","sin":0,"ein":100,"inCharset":"utf-8","outCharset":"utf-8"}},"comm":{$comm}}"""
        val resp = cgiPost(json, cookie)
        val parsed = parsePlaylists(resp)
        android.util.Log.d(
            "QqMusic",
            "getUserPlaylists uin=$uin authst=${authst.isNotBlank()} n=${parsed.size} :: ${resp?.take(400)}",
        )
        parsed.ifEmpty { parsePlaylists(cgiPost(fallbackPlaylistJson(uin, comm), cookie)) }
    }

    /** 兜底通道：srfDissInfo 的个人歌单列表（部分账号只在老接口有数据）。 */
    private fun fallbackPlaylistJson(uin: String, comm: String): String =
        """{"req_0":{"module":"music.srfDissInfo.DissInfo","method":"CgiGetUserDiss","param":{"uin":"$uin","sin":0,"ein":100}},"comm":{$comm}}"""

    private fun parsePlaylists(text: String?): List<Playlist> {
        if (text.isNullOrBlank()) return emptyList()
        val root = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
            ?: return emptyList()
        val data = root.getAsJsonObject("req_0")?.getAsJsonObject("data") ?: return emptyList()
        val arr = data.getAsJsonArray("v_playlist")
            ?: data.getAsJsonArray("disslist")
            ?: data.getAsJsonArray("list")
            ?: return emptyList()
        return arr.mapNotNull { e ->
            val o = runCatching { e.asJsonObject }.getOrNull() ?: return@mapNotNull null
            val id = firstLong(o, "tid", "dirid", "dissid", "id") ?: return@mapNotNull null
            val name = firstString(o, "dirName", "title", "name", "dissname") ?: "歌单"
            val count = firstInt(o, "songNum", "songnum", "song_num", "total_song_num")
            val pic = firstString(o, "picUrl", "picurl", "pic_url", "pic", "dir_pic_url").orEmpty()
            Playlist(
                id = id.toString(),
                name = name,
                coverUrl = pic,
                source = type,
                trackCount = count,
            )
        }
    }

    private fun firstString(o: com.google.gson.JsonObject, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { k ->
            o.get(k)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
        }

    private fun firstLong(o: com.google.gson.JsonObject, vararg keys: String): Long? =
        keys.firstNotNullOfOrNull { k ->
            val p = o.get(k)?.takeIf { it.isJsonPrimitive } ?: return@firstNotNullOfOrNull null
            if (p.asJsonPrimitive.isNumber) p.asLong else p.asString.toLongOrNull()?.takeIf { it > 0 }
        }

    private fun firstInt(o: com.google.gson.JsonObject, vararg keys: String): Int =
        keys.firstNotNullOfOrNull { k ->
            val p = o.get(k)?.takeIf { it.isJsonPrimitive } ?: return@firstNotNullOfOrNull null
            if (p.asJsonPrimitive.isNumber) p.asInt else p.asString.toIntOrNull()
        } ?: 0

    override suspend fun getPlaylistDetail(playlist: Playlist): List<Song> = withContext(Dispatchers.IO) {
        val account = accountStore.accounts.value[type]
        val cookie = account?.cookie.orEmpty()
        val encryptUin = account?.cookie
            ?.let { Regex("encryptUin=([^;]+)").find(it)?.groupValues?.get(1) }
            .orEmpty()

        // 普通歌单
        val json = """{"req_0":{"module":"music.srfDissInfo.DissInfo","method":"CgiGetDiss","param":{"disstid":${playlist.id}}},"comm":{"tmeLoginType":2,"format":"json","ct":23,"cv":0}}"""
        val resp = cgiPost(json, cookie) ?: return@withContext emptyList()
        val parsed = runCatching {
            gson.fromJson(resp, QqPlaylistDetailResp::class.java)
        }.getOrNull() ?: return@withContext emptyList()
        val songs = parsed.req_0?.data?.songlist.orEmpty().map { it.toSong() }
        if (songs.isNotEmpty()) return@withContext songs

        // 空的话，尝试「我喜欢」特殊歌单（dirid=201 + 加密 uin）
        if (encryptUin.isNotBlank()) {
            val favJson = """{"req_0":{"module":"music.srfDissInfo.DissInfo","method":"CgiGetDiss","param":{"disstid":0,"dirid":201,"song_begin":0,"song_num":300,"enc_host_uin":"$encryptUin"}},"comm":{"tmeLoginType":2,"format":"json","ct":23,"cv":0}}"""
            val favResp = cgiPost(favJson, cookie) ?: return@withContext songs
            val favParsed = runCatching {
                gson.fromJson(favResp, QqPlaylistDetailResp::class.java)
            }.getOrNull() ?: return@withContext songs
            return@withContext favParsed.req_0?.data?.songlist.orEmpty().map { it.toSong() }
        }
        songs
    }

    override suspend fun login(phone: String, password: String): Account =
        throw UnsupportedOperationException("请在设置中扫码登录")

    override suspend fun logout() {
        accountStore.remove(type)
    }

    /**
     * 带凭证自愈的 CGI 请求：响应含凭证过期错误码（1000 / 104401）且当前带 cookie 时，
     * 用 refresh_key / refresh_token 自动续期一次并重试一次。
     */
    private suspend fun cgiPost(json: String, cookie: String): String? {
        val first = cgiPostRaw(json, cookie)
        if (cookie.isBlank() || !hasCredentialExpiredCode(first)) return first
        val refreshed = refreshQqCredential() ?: return first
        return cgiPostRaw(json, refreshed) ?: first
    }

    private suspend fun cgiPostRaw(json: String, cookie: String): String? = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url("https://u.y.qq.com/cgi-bin/musicu.fcg")
            .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Referer", "https://y.qq.com/")
        if (cookie.isNotBlank()) builder.header("Cookie", cookie)
        runCatching { client.newCall(builder.build()).execute().body?.string() }.getOrNull()
    }

    private fun QqSong.toSong(): Song = Song(
        source = MusicSourceType.QQ,
        id = mid,
        title = name,
        artist = singer?.joinToString(" / ") { it.name }.orEmpty().ifBlank { "未知歌手" },
        album = album?.name ?: "",
        coverUrl = album?.mid
            ?.let { "https://y.gtimg.cn/music/photo_new/T002R300x300M000$it.jpg" }
            ?: "",
        durationMs = interval * 1000L,
    )

    /** smartbox 结果：mid 为 songmid（取流用），封面直接用返回的 pic 地址。 */
    private fun SmartboxItem.toSong(): Song = Song(
        source = MusicSourceType.QQ,
        id = mid.takeIf { it.isNotBlank() } ?: id,
        title = name,
        artist = singer.ifBlank { "未知歌手" },
        album = "",
        coverUrl = pic.orEmpty().replace("http://", "https://"),
        durationMs = 0L,
    )

    /**
     * 歌单详情：id 统一用 songmid（与搜索结果一致，取流 filename 需要），
     * media_mid 另存 extra，取流失败时作为第二候选。
     */
    private fun QqSongDetail.toSong(): Song = Song(
        source = MusicSourceType.QQ,
        id = mid,
        title = name,
        artist = singer?.joinToString(" / ") { it.name }.orEmpty().ifBlank { "未知歌手" },
        album = album?.name ?: "",
        coverUrl = album?.mid.takeIf { !it.isNullOrBlank() }
            ?.let { "https://y.gtimg.cn/music/photo_new/T002R300x300M000$it.jpg" }
            ?: "",
        durationMs = interval * 1000L,
        extra = file?.media_mid?.takeIf { it.isNotBlank() }
            ?.let { mapOf(MEDIA_MID_KEY to it) } ?: emptyMap(),
    )

    private fun stripJsonp(body: String): String {
        val start = body.indexOf('(')
        val end = body.lastIndexOf(')')
        return if (start in 0 until end) body.substring(start + 1, end) else body
    }

    private companion object {
        /** 歌单详情返回的 media_mid（取流第二候选），见 QqSongDetail.toSong。 */
        const val MEDIA_MID_KEY = "mediaMid"

        /** 本次进程稳定的 guid（QQ vkey 需要，避免每次随机触发 104009 invalidq）。 */
        val SESSION_GUID = (1000000000L..9999999999L).random().toString()

        const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

        /** 凭证过期错误码（QQ 音乐：104400 / 104401 → LoginAuthExpired）。
         *  注意：1000 是 vkey 信封通用码（即使取流失败也常为 1000），不能据此判定登录过期；
         *  104009 是 IP / 滑块验证闸门（invalidq），续期无效，均不在此列。 */
        /**
         * 触发「凭证续期后重试」的错误码。
         *
         * 104003 实测含义：服务端认到了请求但拒绝下发 purl（`midurlinfo[].result=104003`），
         * 绝大多数情况是 musickey / qqmusic_key 已过期（本例创建于两个月前）。
         * 必须纳入续期触发集合，否则登录态过期后 QQ 源永久静默失败。
         */
        val CREDENTIAL_EXPIRED_CODES = setOf(104400, 104401, 104003)

        fun hash33(s: String, h: Int = 0): Int {
            var result = h
            for (c in s) result = (result shl 5) + result + c.code
            return 0x7FFFFFFF and result
        }
    }
}
