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
    /** 0 = 有权限；101404 = 无权限（未开会员 / 版权限制）。 */
    val result: Int? = null,
)

/** VIP 权益实测结论：直接拿一首已知需权益的曲目试取流，比查会员状态接口更贴近「能不能播」。 */
data class QqVipProbeResult(
    val loggedIn: Boolean,
    val uin: String,
    val savedLoginType: Int?,
    val cookieKeys: String,
    val lines: List<String>,
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
        val comm = commFields(uin, cookie, authst)
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
        migrateGuidIfNeeded()
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
            // ct/cv 必须 24/0（与搜索的 11/11 相反）：真机实测取流用 11/11 时，
            // 无论登录态还是匿名、无论免费歌还是 VIP 歌，一律 result=101404；
            // 换成 24/0 后同一账号同一首歌立刻 result=0 拿到直链。
            val commFields = commFields(uin, cookie, authst, ct = 24, cv = 0)
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
            // 播放路径的取流结果必须可观测：purl 是否非空、result 是多少，
            // 直接对应「这首歌到底能不能播」。以前只有 Log.d，release 包会被优化掉。
            android.util.Log.e(
                "QqVipProbe",
                "播放取流 songmid=$songMid file=$filename uin=$uin ct=24/0 " +
                    "authst=${authst.isNotBlank()} :: ${text.take(600)}",
            )
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

    /**
     * 老账号迁移：早期版本登录 / 手动粘贴的 cookie 没有 guid，补一个稳定值并回写。
     *
     * 不补的话取流只能用 [SESSION_GUID]（进程级随机），进程一重启就变，
     * 服务端把这当成「登录态与设备不一致」，权益判定会不稳定。
     */
    private suspend fun migrateGuidIfNeeded() {
        val account = accountStore.accounts.value[type] ?: return
        val cookie = account.cookie
        if (cookie.isBlank() || Regex("""(?:^|;)\s*guid=""").containsMatchIn(cookie)) return
        accountStore.save(account.copy(cookie = "$cookie; guid=${QqLoginManager.newGuid()}"))
        android.util.Log.d("QqMusic", "该账号 cookie 缺 guid，已补写稳定值")
    }

    /**
     * 构造 CGI 的 comm 段。
     *
     * **tmeLoginType / tmeLoginMethod 必须沿用登录时的账号体系**，不能写死：
     * 手机号登录走原生账号（0），扫码登录走 QQ 互联 OAuth（2），二者是两套权益校验。
     * 以前这里写死 2，于是「原生账号登录后明明有会员，取流却被判成第三方授权」，
     * 表现为 vkey 恒 result=101404 —— 即所谓的「QQ 登录了但没有 VIP 权益」。
     *
     * **ct/cv 按接口区分，绝不能互相照搬**（真机实测，2026-10-14）：
     * - 搜索 / 歌单等 `music.*` 接口：必须 11/11，否则 meta.sum 恒 0、结果为空；
     * - 取流 `vkey.GetVkeyServer`：必须 **24/0**。用 11/11 会一律 result=101404，
     *   **连免费歌都取不到**，极易被误判成"账号没有会员权益"。
     */
    private fun commFields(
        uin: String,
        cookie: String,
        authst: String,
        ct: Int = 11,
        cv: Int = 11,
    ): String {
        val loginType = cookieField(cookie, "tmeLoginType").toIntOrNull()
            ?: QqLoginManager.LOGIN_TYPE_QQ
        val method = QqLoginManager.loginMethodOf(loginType)
        return buildString {
            append("\"uin\":\"$uin\",\"format\":\"json\",\"ct\":$ct,\"cv\":$cv")
            append(",\"tmeLoginType\":$loginType,\"tmeLoginMethod\":$method")
            if (authst.isNotBlank()) append(",\"authst\":\"$authst\"")
        }
    }

    /**
     * VIP 权益实测：对多种「登录类型 x 请求方法」组合逐一取流，看哪一组能拿到完整直链。
     *
     * 不用会员状态 CGI —— 那类接口要登录、字段名还会漂移；直接拿一首已知需要权益的
     * 曲目（周杰伦《晴天》，匿名实测 purl 为空、result=101404）试取流最贴近真实播放：
     * purl 非空即代表权益在该组合下生效。
     *
     * 输出每行形如 `type=0 method=3 F000 -> purl=true result=0`，
     * 用于定位「我的会员到底在哪一组参数下能生效」。
     */
    suspend fun probeVip(): QqVipProbeResult = withContext(Dispatchers.IO) {
        accountStore.awaitLoaded()
        // 先补齐 guid 再测：否则测的还是「临时 guid」的旧状态，结论不可信
        migrateGuidIfNeeded()
        val account = accountStore.accounts.value[type]
        val cookie = account?.cookie.orEmpty()
        if (cookie.isBlank()) {
            return@withContext QqVipProbeResult(false, "", null, "", listOf("未登录 QQ 音乐"))
        }
        val uin = cookieField(cookie, "uin").takeIf { it.isNotBlank() }
            ?: account?.userId?.takeIf { it.isNotBlank() } ?: "0"
        val authst = listOf("qqmusic_key", "qm_keyst", "musickey")
            .map { cookieField(cookie, it) }.firstOrNull { it.isNotBlank() }.orEmpty()
        val savedType = cookieField(cookie, "tmeLoginType").toIntOrNull()
        val keys = cookie.split(";").map { it.trim().substringBefore("=") }
            .filter { it.isNotBlank() }.joinToString(",")
        val guid = cookieField(cookie, "guid").takeIf { it.isNotBlank() } ?: SESSION_GUID

        val lines = mutableListOf<String>()
        val hasGuid = Regex("""(?:^|;)\s*guid=""").containsMatchIn(cookie)
        lines += "uin=$uin  authst=${authst.isNotBlank()}  guid=${if (hasGuid) "有" else "无"}"
        lines += "已存 tmeLoginType=${savedType ?: "无"}（无 = 旧版登录的账号）"
        lines += "cookie 键: $keys"
        // 先试账号自身记录的类型，再穷举另外两种，保证至少能找到一组可用参数
        val types = listOfNotNull(savedType, QqLoginManager.LOGIN_TYPE_NATIVE, QqLoginManager.LOGIN_TYPE_QQ)
            .distinct()
        var anyOk = false
        var firstRaw = ""
        for (loginType in types) {
            val methods = listOf(QqLoginManager.loginMethodOf(loginType), 3, 2).distinct()
            for (method in methods) {
                for (fn in listOf("F000$PROBE_MEDIA_MID.flac", "M800$PROBE_MEDIA_MID.mp3")) {
                    val (purl, result, raw) = rawVkey(
                        PROBE_SONG_MID, cookie, uin, guid, fn, loginType, method, authst,
                    )
                    val tag = fn.substring(0, 4)
                    lines += "type=$loginType method=$method $tag -> purl=${purl != null} result=$result"
                    if (purl != null) {
                        lines += "  直链: ${purl.take(70)}…"
                        anyOk = true
                    }
                    if (firstRaw.isBlank()) firstRaw = raw
                }
            }
        }
        // ct/cv 横比（本次排查的关键）：取流必须 24/0。
        // 用 11/11 时无论登录态/匿名、免费歌/VIP 歌，一律 result=101404 ——
        // 这个假象曾让人误判成「账号没有会员权益」。
        if (authst.isNotBlank()) {
            for (loginType in listOf(QqLoginManager.LOGIN_TYPE_NATIVE, QqLoginManager.LOGIN_TYPE_QQ)) {
                for (fn in listOf("F000$PROBE_MEDIA_MID.flac", "M800$PROBE_MEDIA_MID.mp3")) {
                    val (p, r, _) = rawVkey(
                        PROBE_SONG_MID, cookie, uin, guid, fn, loginType,
                        QqLoginManager.loginMethodOf(loginType), authst, 24, 0,
                    )
                    lines += "《晴天》type=$loginType ct=24/0 ${fn.substring(0, 4)} -> purl=${p != null} result=$r"
                    if (p != null) {
                        lines += "  直链: ${p.take(70)}…"
                        anyOk = true
                    }
                }
            }
        }
        // 诊断①：完整响应。result 只说明「这首歌有没有权益」，顶层 retcode 才说明
        // 「请求链路通不通」——101404 配 retcode=0 是权益问题，配非 0 是请求构造问题。
        // 长 JSON 必须折行：一行上千字符的无空格文本会把对话框布局拖坏。
        if (firstRaw.isNotBlank()) {
            lines += ""
            lines += "—— 首组完整响应（看 retcode）——"
            lines += firstRaw.replace('\n', ' ').take(1000).chunked(60)
        }
        // 诊断②：uin 取值。手机号登录只返回 musicid（开放平台 id），没有真实 QQ 号；
        // encryptUin 是加密的 QQ 号，若只有它做 uin 时取得到，说明 uin 一直传错了。
        val encUin = cookieField(cookie, "encryptUin")
        if (encUin.isNotBlank()) {
            val t = savedType ?: QqLoginManager.LOGIN_TYPE_NATIVE
            val (p, r, _) = rawVkey(
                PROBE_SONG_MID, cookie, encUin, guid, "M800$PROBE_MEDIA_MID.mp3",
                t, QqLoginManager.loginMethodOf(t), authst,
            )
            lines += "uin=encryptUin -> purl=${p != null} result=$r"
        }
        // 诊断③：免费歌对照。《晴天》是 VIP 曲目，101404 说明不了链路问题；
        // 换一首普通曲目分别用「匿名」和「登录态」取流：
        //   匿名必须用随机 10 位 uin —— uin=0 会被服务端直接拒，不能当对照基线；
        //   登录态用当前账号，能取到就说明链路对账号是通的，问题只在权益曲目。
        val free = runCatching { searchViaCgi("月亮之上", 1).firstOrNull() }.getOrNull()
        if (free != null) {
            val fm = free.extra[MEDIA_MID_KEY]?.takeIf { it.isNotBlank() } ?: free.id
            val anonUin = QqLoginManager.newGuid()
            val t = savedType ?: QqLoginManager.LOGIN_TYPE_NATIVE
            // ct/cv 横比：搜索必须 11/11，取流未必 —— 拿同一首歌、同一账号，
            // 只换客户端标识，看哪一组能给出 purl。
            for ((ct, cv) in listOf(11 to 11, 24 to 0, 12 to 0)) {
                val (fp, fr, fraw) = rawVkey(
                    free.id, cookie, uin, guid, "M500$fm.mp3",
                    t, QqLoginManager.loginMethodOf(t), authst, ct, cv,
                )
                lines += "免费歌 登录态 ct=$ct/cv=$cv -> purl=${fp != null} result=$fr"
                if (fraw.isNotBlank()) lines += fraw.replace('\n', ' ').take(300).chunked(60)
            }
            for ((ct, cv) in listOf(11 to 11, 24 to 0)) {
                val (fp, fr, fraw) = rawVkey(
                    free.id, "", anonUin, anonUin, "M500$fm.mp3", 2, 2, "", ct, cv,
                )
                lines += "免费歌 匿名   ct=$ct/cv=$cv -> purl=${fp != null} result=$fr"
                if (fraw.isNotBlank()) lines += fraw.replace('\n', ' ').take(300).chunked(60)
            }
        } else {
            lines += ""
            lines += "免费歌对照：搜索失败，无法判断链路"
        }
        // 诊断④：主动续期一次。完整响应能看出服务端到底发了哪些鉴权字段
        // （uin / qm_keyst / qqmusic_key）—— 这是取流权益的直接来源。
        val refreshed = account?.let { runCatching { qqLogin.refreshCredential(it) }.getOrNull() }
        lines += ""
        lines += if (refreshed != null && refreshed.status == "DONE") {
            "续期成功 musicid=${refreshed.musicid}（完整响应见 logcat QqVipProbe）"
        } else {
            "续期失败: ${refreshed?.message ?: "无响应"}"
        }
        if (!anyOk) {
            // 关键结论：所有参数组合都取不到，说明问题不在请求参数，而在登录通道本身
            lines += ""
            if (!hasGuid) lines += "· 该账号 cookie 缺 guid，重新登录后会补上并固定。"
            val oauth = cookie.contains("pt_oauth_token") || cookie.contains("pt_login_type")
            if (oauth) {
                lines += "· 当前是扫码（QQ 互联第三方授权）登录 —— 这类凭证服务端不发放会员权益，"
                lines += "  换参数也无效。请退出登录，改用「手机号登录」重新登录后再检测。"
            } else {
                lines += "· 凭证可能已过期或账号本身无该曲目的会员权益。"
            }
        }
        QqVipProbeResult(true, uin, savedType, keys, lines)
    }

    /**
     * 发一次 vkey 请求并返回（完整播放直链，result 码，**原始响应**）。
     *
     * 与 [requestVkeyUrl] 的区别：**不做 30 秒试听降级**，purl 为空就是没权益，
     * 这样探针才能如实区分「能播完整版」和「只有试听」。
     *
     * 第三项原始响应是给探针做深度诊断用的：`result` 只代表「这首歌有没有权益」，
     * 而顶层 `retcode` 才代表「请求链路本身通不通」——两者混在一起看极易误判。
     */
    private suspend fun rawVkey(
        songMid: String,
        cookie: String,
        uin: String,
        guid: String,
        filename: String,
        loginType: Int,
        loginMethod: Int,
        authst: String,
        // ct/cv 是客户端标识，**搜索和取流不是同一套**：搜索实测必须 11/11，
        // 而取流的开源实现（含 qqmusic-dl 等）普遍用 24/0。写成参数以便探针横比。
        ct: Int = 11,
        cv: Int = 11,
    ): Triple<String?, Int?, String> = withContext(Dispatchers.IO) {
        val comm = buildString {
            append("\"uin\":\"$uin\",\"format\":\"json\",\"ct\":$ct,\"cv\":$cv")
            append(",\"tmeLoginType\":$loginType,\"tmeLoginMethod\":$loginMethod")
            if (authst.isNotBlank()) append(",\"authst\":\"$authst\"")
        }
        val json = """{"req_1":{"module":"vkey.GetVkeyServer","method":"CgiGetVkey","param":{"filename":["$filename"],"guid":"$guid","songmid":["$songMid"],"songtype":[0],"uin":"$uin","loginflag":1,"platform":"20"}},"loginUin":"$uin","comm":{$comm}}"""
        val fullCookie = if (cookie.isNotBlank() && !cookie.contains("guid=")) "$cookie; guid=$guid" else cookie
        val builder = Request.Builder()
            .url("https://u.y.qq.com/cgi-bin/musicu.fcg")
            .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Referer", "https://y.qq.com/")
        if (fullCookie.isNotBlank()) builder.header("Cookie", fullCookie)
        val text = runCatching { client.newCall(builder.build()).execute().body?.string() }
            .getOrNull() ?: return@withContext Triple(null, null, "")
        // 诊断日志：完整响应是定位 101404 的唯一依据（result 只说权益，retcode 才说链路）
        android.util.Log.e("QqVipProbe", "vkey uin=$uin type=$loginType/$loginMethod fn=$filename :: $text")
        val resp = runCatching { gson.fromJson(text, QqVkeyResponse::class.java) }.getOrNull()
        val data = resp?.req_1?.data ?: return@withContext Triple(null, null, text)
        val info = data.midurlinfo?.firstOrNull() ?: return@withContext Triple(null, null, text)
        val sip = data.sip?.firstOrNull() ?: "http://aqqmusic.tc.qq.com/"
        val purl = info.purl?.takeIf { it.isNotBlank() }
            ?.let { (sip + it).replaceFirst("http://", "https://") }
        Triple(purl, info.result, text)
    }

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
        val comm = commFields(uin, cookie, authst)
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

        /**
         * 权益探针用的样本曲目：周杰伦《晴天》。
         * 匿名取流实测 purl 为空、result=101404（无权限），故能作为「有无 VIP」的判据。
         */
        const val PROBE_SONG_MID = "0039MnYb0qxYhV"
        const val PROBE_MEDIA_MID = "003Qui1q2u1Zho"

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
