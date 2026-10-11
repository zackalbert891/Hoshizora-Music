package com.puremusic.source.kuwo

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.puremusic.lyrics.LrcParser
import com.puremusic.model.Account
import com.puremusic.model.LyricLine
import com.puremusic.model.Lyrics
import com.puremusic.model.MusicSourceType
import com.puremusic.model.Playlist
import com.puremusic.model.Song
import com.puremusic.source.MusicSource
import com.puremusic.source.autoswitch.AutoSwitchSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * 新版接口响应：https://www.kuwo.cn/api/www/search/searchMusicBykeyWord
 * 旧版接口（search.kuwo.cn/r.s）条目位于顶层 abslist，字段名不同，故 [KuwoSongItem] 同时兼容两套字段。
 */
data class KuwoSearchResponse(val code: Int = 0, val msg: String? = null, val data: KuwoSearchData? = null)
data class KuwoSearchData(val total: String? = null, val list: List<KuwoSongItem>? = null)

/** 同时兼容新版（rid / name / artist / album / pic / duration）与旧版（MUSICRID / SONGNAME / ARTIST / ALBUM / DURATION）。 */
data class KuwoSongItem(
    val rid: Long = 0,
    val name: String = "",
    val artist: String = "",
    val album: String? = null,
    val pic: String? = null,
    val duration: Int = 0,
    val MUSICRID: String? = null,
    val SONGNAME: String? = null,
    val ARTIST: String? = null,
    val ALBUM: String? = null,
    val DURATION: String? = null,
)

/** 取流响应：type=convert_url3&response=json 时直链位于 data.url。 */
data class KuwoUrlResponse(val code: Int = 0, val data: KuwoUrlData? = null)
data class KuwoUrlData(val url: String? = null)

/** 歌词响应：data.lrclist[].time（秒，字符串）/ lineLyric。 */
data class KuwoLrcResponse(val code: Int = 0, val data: KuwoLrcData? = null)
data class KuwoLrcData(val lrclist: List<KuwoLrcLine>? = null)
data class KuwoLrcLine(val time: String = "", val lineLyric: String = "")

/**
 * 酷我音乐源（免登录兜底源，不支持登录）。
 *
 * 采用的接口（均为酷我公开的免登录接口，无需账号）：
 *  1. 搜索（主）：GET https://www.kuwo.cn/api/www/search/searchMusicBykeyWord?key=&pn=&rn=&httpsStatus=1
 *     —— 需同时携带 Cookie: kw_token=<值> 与请求头 csrf: <同一值>，二者必须一致；
 *     kw_token 由 GET https://www.kuwo.cn/ 的 Set-Cookie 动态下发，本类缓存 30 分钟后自动刷新，
 *     若搜索命中 CSRF 校验失败则强制刷新 token 重试一次。
 *  2. 搜索（兜底）：GET https://search.kuwo.cn/r.s?all=&ft=music&itemset=web_2013&client=kt&pn=&rn=&rformat=json&encoding=utf8
 *     —— 返回 Python 单引号风格 JSON，条目在顶层 abslist，字段为 MUSICRID/SONGNAME/ARTIST/ALBUM/DURATION。
 *  3. 取流：GET https://antiserver.kuwo.cn/anti.s?type=convert_url3&rid=&format=mp3&response=json（失败退回 type=convert_url&response=url）
 *     —— 返回带 expires/sign 的短时效直链，故本类不做缓存，每次播放前即时解析。
 *  4. 歌词：GET https://m.kuwo.cn/newh5/singles/songinfoandlrc?musicId=（失败退回 www.kuwo.cn 同路径），解析 data.lrclist。
 *
 * 为何未实现 mobi.kuwo.cn 取流：该接口的 q 参数需经 kwDES（密钥 "ylzsxkwm"）加密后再 Base64，
 * 纯 URL 拼接无法构造；为避免引入未经验证的加密实现，这里统一走 antiserver 明文直链。
 *
 * 与 ExternalMusicSource(JSON 插件) 机制的关系：插件机制只能做静态 URL 占位符拼接 + 静态 header，
 * 无法携带动态 kw_token≡csrf，也无法解析 rid / lrclist，故按工程内 MusicSource 接口约定新增本实现类。
 */
@Singleton
class KuwoMusicSource @Inject constructor(
    @Named("plain") private val client: OkHttpClient,
) : MusicSource {

    private val gson = Gson()

    override val type = MusicSourceType.KUWO
    override val displayName = "酷我音乐"
    override val supportsLogin = false

    @Volatile
    private var cachedToken: String? = null

    @Volatile
    private var tokenTime = 0L

    /** 搜索：优先走新版 api/www（需 kw_token），失败退回旧版接口并做主流版本筛选。 */
    override suspend fun search(keyword: String, page: Int, limit: Int): List<Song> =
        withContext(Dispatchers.IO) {
            val kw = URLEncoder.encode(keyword, "UTF-8")
            // 1) 新版接口：kw_token 与 csrf 必须一致（酷我官网现已不再向脚本客户端下发 kw_token，
            //    失败时自动回落旧版；token 获取与缓存逻辑保留，官网恢复下发后即可自动生效）
            val primary = runCatching { searchByWebApi(kw, page, limit) }.getOrDefault(emptyList())
            if (primary.isNotEmpty()) return@withContext primary
            // 2) 旧版接口：扩大召回量（limit*5），过滤翻唱/DJ/现场等非主流版本后按相关性截取
            runCatching { searchByLegacyApi(kw, page, limit * 5) }
                .getOrDefault(emptyList())
                .filterMainstream(keyword)
                .take(limit)
        }

    /** 新版接口：kw_token 与 csrf 必须一致；命中 CSRF 错误时刷新 token 重试一次。 */
    private fun searchByWebApi(kw: String, page: Int, limit: Int): List<Song> {
        repeat(2) { attempt ->
            val token = kwToken(force = attempt > 0) ?: return emptyList()
            val url = "$WEB_API/search/searchMusicBykeyWord?key=$kw&pn=${page + 1}&rn=$limit&httpsStatus=1"
            val body = httpGet(
                url,
                mapOf(
                    "csrf" to token,
                    "Cookie" to "kw_token=$token",
                    "Referer" to "https://www.kuwo.cn/",
                ),
            ) ?: return@repeat
            val songs = runCatching {
                gson.fromJson(body, KuwoSearchResponse::class.java)?.data?.list.orEmpty()
            }.getOrDefault(emptyList()).mapNotNull { it.toSong() }
            if (songs.isNotEmpty()) return songs
            if (!body.contains("CSRF", ignoreCase = true)) return emptyList()
        }
        return emptyList()
    }

    /** 旧版接口：响应为单引号风格，条目位于顶层 abslist。 */
    private fun searchByLegacyApi(kw: String, page: Int, limit: Int): List<Song> {
        val url = "https://search.kuwo.cn/r.s?all=$kw&ft=music&itemset=web_2013&client=kt" +
            "&pn=$page&rn=$limit&rformat=json&encoding=utf8"
        val body = httpGet(url, mapOf("Referer" to "https://www.kuwo.cn/")) ?: return emptyList()
        val normalized = body.replace('\'', '"')
        val items = runCatching {
            JsonParser.parseString(normalized).asJsonObject
                .getAsJsonArray("abslist")
                .mapNotNull { gson.fromJson(it, KuwoSongItem::class.java) }
        }.getOrNull()
        return items.orEmpty().mapNotNull { it.toSong() }
    }

    /**
     * 「主流原版」偏好：把翻唱/DJ/现场/Demo 等非主流版本**沉底**，其余保持接口顺序。
     *
     * 用 sortedBy（稳定排序）而不是按分数重排：接口返回顺序本身就是酷我的相关度排序，
     * 客户端再按自制分数重排会把噪声顶到前面（与网易云源踩过的坑一致）。
     */
    private fun List<Song>.filterMainstream(keyword: String): List<Song> {
        if (isEmpty()) return this
        val kwWords = keyword.trim().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        val firstWord = kwWords.firstOrNull().orEmpty()
        return sortedBy { song ->
            val title = song.title.lowercase()
            val artist = song.artist.lowercase()
            // 命中关键词的排前面，非主流版本沉底，其余 0（保持原顺序）
            val onTarget = title.contains(firstWord) || artist.contains(firstWord)
            val nonMain = NON_MAINSTREAM.any { title.contains(it) || artist.contains(it) }
            if (nonMain) 2 else if (onTarget) 0 else 1
        }
    }

    /** 取流：convert_url3(JSON) → convert_url(明文)。返回的是短时效直链，每次播放前重新解析。 */
    override suspend fun getSongUrl(song: Song): String? = withContext(Dispatchers.IO) {
        // 跨源换源后的歌曲真实平台 id 在 extra["srcId"]（原 id 已保留给上层 UI）
        val rid = (song.extra[com.puremusic.source.autoswitch.AutoSwitchSource.SRC_ID_KEY] ?: song.id)
            .removePrefix(MUSIC_PREFIX)
            .ifBlank { return@withContext null }
        val fromJson = runCatching {
            val body = httpGet("$ANTI_SERVER?type=convert_url3&rid=$rid&format=mp3&response=json")
                ?: return@runCatching null
            gson.fromJson(body, KuwoUrlResponse::class.java)?.data?.url?.takeIf { it.isNotBlank() }
        }.getOrNull()
        if (!fromJson.isNullOrBlank()) return@withContext fromJson
        runCatching {
            httpGet("$ANTI_SERVER?type=convert_url&rid=$rid&format=mp3&response=url")
                ?.trim()
                ?.takeIf { it.startsWith("http") }
        }.getOrNull()
    }

    /** 歌词：songinfoandlrc 的 lrclist → LyricLine（time 为秒字符串），双域名降级。 */
    override suspend fun getLyrics(song: Song): Lyrics = withContext(Dispatchers.IO) {
        val rid = song.id.removePrefix(MUSIC_PREFIX).ifBlank { return@withContext Lyrics() }
        for (host in listOf("https://m.kuwo.cn", "https://www.kuwo.cn")) {
            val body = httpGet(
                "$host/newh5/singles/songinfoandlrc?musicId=$rid",
                mapOf("Referer" to "https://www.kuwo.cn/"),
            ) ?: continue
            val lines = runCatching {
                gson.fromJson(body, KuwoLrcResponse::class.java)?.data?.lrclist.orEmpty()
                    .mapNotNull { line ->
                        val text = line.lineLyric.trim()
                        val seconds = line.time.toDoubleOrNull() ?: return@mapNotNull null
                        if (text.isEmpty()) null else LyricLine((seconds * 1000).toLong(), text)
                    }
            }.getOrDefault(emptyList())
            if (lines.isNotEmpty()) return@withContext Lyrics(lines = lines.sortedBy { it.timeMs })
        }
        Lyrics()
    }

    /** 免登录源无「我的歌单」。 */
    override suspend fun getUserPlaylists(): List<Playlist> = emptyList()

    /** 免登录源无歌单详情。 */
    override suspend fun getPlaylistDetail(playlist: Playlist): List<Song> = emptyList()

    override suspend fun login(phone: String, password: String): Account =
        throw UnsupportedOperationException("酷我音源免登录，无需登录")

    override suspend fun logout() {
        // 免登录源无需清理凭证
    }

    /** 读取并缓存 www.kuwo.cn 下发的最新 kw_token；force=true 时强制刷新。 */
    private fun kwToken(force: Boolean = false): String? {
        if (!force) {
            val cached = cachedToken
            if (cached != null && System.currentTimeMillis() - tokenTime < TOKEN_TTL_MS) return cached
        }
        val token = runCatching {
            client.newCall(
                Request.Builder().url("https://www.kuwo.cn/").header("User-Agent", UA).build()
            ).execute().use { resp ->
                resp.headers("Set-Cookie").firstNotNullOfOrNull {
                    Regex("kw_token=([^;]+)").find(it)?.groupValues?.get(1)
                }
            }
        }.getOrNull()
        if (!token.isNullOrBlank()) {
            cachedToken = token
            tokenTime = System.currentTimeMillis()
        } else if (force) {
            cachedToken = null
        }
        return token?.takeIf { it.isNotBlank() } ?: cachedToken
    }

    private fun httpGet(url: String, headers: Map<String, String> = emptyMap()): String? {
        return try {
            val builder = Request.Builder().url(url).header("User-Agent", UA)
            headers.forEach { (k, v) -> builder.header(k, v) }
            client.newCall(builder.build()).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 读取二进制响应（用于 newlyric.lrc 密文）。 */
    private fun httpGetBytes(url: String, headers: Map<String, String> = emptyMap()): ByteArray? {
        return try {
            val builder = Request.Builder().url(url).header("User-Agent", UA)
            headers.forEach { (k, v) -> builder.header(k, v) }
            client.newCall(builder.build()).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.bytes() else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun KuwoSongItem.toSong(): Song? {
        val id = (MUSICRID?.takeIf { it.isNotBlank() } ?: rid.toString())
            .removePrefix(MUSIC_PREFIX)
            .takeIf { it.isNotBlank() && it != "0" } ?: return null
        val songTitle = (SONGNAME ?: name).replace("&nbsp;", " ").trim()
        if (songTitle.isBlank()) return null
        val rawArtist = (ARTIST ?: artist).replace("&nbsp;", " ").replace("&amp;", "&").trim()
        val durationSec = DURATION?.toLongOrNull() ?: duration.toLong()
        return Song(
            source = MusicSourceType.KUWO,
            id = id,
            title = songTitle,
            artist = rawArtist.replace("&", " / ").ifBlank { "未知歌手" },
            album = ALBUM ?: album.orEmpty(),
            coverUrl = pic.orEmpty(),
            durationMs = durationSec * 1000,
        )
    }

    private companion object {
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        const val MUSIC_PREFIX = "MUSIC_"
        const val WEB_API = "https://www.kuwo.cn/api/www"
        const val ANTI_SERVER = "https://antiserver.kuwo.cn/anti.s"
        const val TOKEN_TTL_MS = 30 * 60 * 1000L

        /** 非主流版本特征词：命中即降权，避免翻唱/DJ/现场等占据结果。 */
        val NON_MAINSTREAM = listOf(
            "cover", "翻唱", "伴奏", "纯音乐", "dj ", "live", "现场", "演唱会",
            "demo", "片段", "remix", "remaster", "extended", "instrumental",
            "钢琴版", "吉他版", "弹唱", "女声", "男声",
        )
    }
}
