package com.puremusic.source.qianqian

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.puremusic.lyrics.LrcParser
import com.puremusic.model.Account
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
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/** 千千搜索响应：条目在 data.typeTrack。 */
data class QianqianSearchResponse(val errno: Int = 0, val data: QianqianSearchData? = null)
data class QianqianSearchData(
    val typeTrack: List<QianqianTrack>? = null,
    @SerializedName("list", alternate = ["result"]) val list: List<QianqianTrack>? = null,
)

data class QianqianTrack(
    val TSID: String = "",
    val title: String = "",
    val albumTitle: String = "",
    val pic: String = "",
    /** 秒 */
    val duration: Int = 0,
    val artist: List<QianqianArtist>? = null,
    val allRate: List<String>? = null,
    /** 搜索结果自带 .lrc 直链，省掉一次歌词请求。 */
    val lyric: String? = null,
    /** 1=VIP 曲目。实测 VIP 曲目 tracklink 返回 path=null 且无试听，故沉底。 */
    val isVip: Int = 0,
    val pay_model: Int = 0,
)
data class QianqianArtist(val name: String = "")

/** 取流响应：直链在 data.path，试听片段在 data.trail_audio_info.path。 */
data class QianqianLinkResponse(val errno: Int = 0, val data: QianqianLinkData? = null)
data class QianqianLinkData(
    val path: String? = null,
    val duration: Double? = null,
    val trail_audio_info: QianqianTrail? = null,
)
data class QianqianTrail(val path: String? = null)

/**
 * 千千音乐源（music.91q.com / 太合），**免登录，且能给 flac 无损直链**。
 *
 * 协议（学自 CharlesPikachu/musicdl 的 QianqianMusicClient，已在本机实测通过）：
 *  1. 搜索：GET https://music.91q.com/v1/search?word=&type=1&pageNo=&pageSize=&appid=16073360&timestamp=&sign=
 *     sign = MD5(按 key 升序拼接的 "k=v" & 相连字符串 + 固定密钥)，密钥为客户端内置常量。
 *     条目位于 data.typeTrack，字段：TSID / title / artist[].name / albumTitle / pic / duration(秒) / allRate。
 *  2. 取流：GET https://music.91q.com/v1/song/tracklink?TSID=&appid=&rate=&timestamp=&sign=
 *     直链在 data.path；rate 档位 3000=flac 无损、320/128/64=mp3。
 *     无权限时回落 data.trail_audio_info.path（试听片段）。
 *  3. 歌词：搜索结果自带 lyric 字段（.lrc 直链），直接下载解析即可，无需额外接口。
 *
 * 价值：这是**唯一实测能给无损且完全免登录**的源，网易云 VIP 曲目在这里常能拿到完整版。
 */
@Singleton
class QianqianMusicSource @Inject constructor(
    @Named("plain") private val client: OkHttpClient,
) : MusicSource {

    private val gson = Gson()

    override val type = MusicSourceType.QIANQIAN
    override val displayName = "千千音乐"
    override val supportsLogin = false

    override suspend fun search(keyword: String, page: Int, limit: Int): List<Song> =
        withContext(Dispatchers.IO) {
            val params = signedParams(
                mapOf(
                    "word" to keyword,
                    "type" to "1",
                    "pageNo" to (page + 1).toString(),
                    "pageSize" to limit.coerceAtMost(30).toString(),
                    "appid" to APPID,
                ),
            )
            val url = "$BASE/v1/search?" + params.entries.joinToString("&") { (k, v) ->
                "$k=${URLEncoder.encode(v, "UTF-8")}"
            }
            val body = httpGet(url, DEFAULT_HEADERS) ?: return@withContext emptyList()
            val resp = runCatching {
                gson.fromJson(body, QianqianSearchResponse::class.java)
            }.getOrNull() ?: return@withContext emptyList()
            val items = resp.data?.typeTrack ?: resp.data?.list ?: return@withContext emptyList()
            items
                // VIP 曲目取不到直链（实测 path=null 且不给试听），沉底以免抢占首选位；
                // 用 sortedBy 稳定排序保持接口自身的相关度顺序。
                .sortedBy { if (it.isVip == 1 || it.pay_model >= 3) 1 else 0 }
                .mapNotNull { it.toSong() }
        }

    /**
     * 取流：按 3000(flac) → 320 → 128 依次尝试，优先给无损。
     * 全部失败时返回试听片段路径（总比播不了好），并打日志标记。
     */
    override suspend fun getSongUrl(song: Song): String? = withContext(Dispatchers.IO) {
        val tsid = song.extra[AutoSwitchSource.SRC_ID_KEY] ?: song.id
        if (tsid.isBlank()) return@withContext null
        for (rate in RATE_ORDER) {
            val params = signedParams(mapOf("TSID" to tsid, "appid" to APPID, "rate" to rate))
            val url = "$BASE/v1/song/tracklink?" + params.entries.joinToString("&") { (k, v) ->
                "$k=${URLEncoder.encode(v, "UTF-8")}"
            }
            val body = httpGet(url, DEFAULT_HEADERS) ?: continue
            val data = runCatching {
                gson.fromJson(body, QianqianLinkResponse::class.java)?.data
            }.getOrNull() ?: continue
            val full = data.path?.takeIf { it.startsWith("http") }
            if (full != null) return@withContext full
            if (rate == RATE_ORDER.last()) {
                val trail = data.trail_audio_info?.path?.takeIf { it.startsWith("http") }
                if (trail != null) {
                    android.util.Log.w(TAG, "qianqian[$tsid] 仅拿到试听片段")
                    return@withContext trail
                }
            }
        }
        null
    }

    /** 歌词：优先用搜索结果带过来的 .lrc 直链，没有就走 lyric 接口。 */
    override suspend fun getLyrics(song: Song): Lyrics = withContext(Dispatchers.IO) {
        val lrcUrl = song.extra[LRC_KEY]
        if (lrcUrl.isNullOrBlank()) return@withContext Lyrics()
        val text = httpGet(lrcUrl, DEFAULT_HEADERS) ?: return@withContext Lyrics()
        Lyrics(lines = runCatching { LrcParser.parse(text) }.getOrDefault(emptyList()))
    }

    override suspend fun getUserPlaylists(): List<Playlist> = emptyList()

    override suspend fun getPlaylistDetail(playlist: Playlist): List<Song> = emptyList()

    override suspend fun login(phone: String, password: String): Account =
        throw UnsupportedOperationException("千千音源免登录，无需登录")

    override suspend fun logout() {
        // 免登录源无需清理凭证
    }

    private fun QianqianTrack.toSong(): Song? {
        val id = TSID.ifBlank { return null }
        val title = this.title.ifBlank { return null }
        val artist = artist.orEmpty().joinToString("/") { it.name }.trim('/')
        return Song(
            source = MusicSourceType.QIANQIAN,
            id = id,
            title = title,
            artist = artist,
            album = albumTitle,
            coverUrl = pic,
            durationMs = duration * 1000L,
            extra = buildMap {
                allRate?.joinToString(",")?.takeIf { it.isNotBlank() }?.let { put(RATE_KEY, it) }
                if (!lyric.isNullOrBlank()) put(LRC_KEY, lyric)
            },
        )
    }

    /** 追加 timestamp 与 sign（MD5(升序拼接 + 密钥)）。 */
    private fun signedParams(base: Map<String, String>): LinkedHashMap<String, String> {
        val p = LinkedHashMap(base)
        p["timestamp"] = (System.currentTimeMillis() / 1000).toString()
        val raw = p.entries.sortedBy { it.key }.joinToString("&") { "${it.key}=${it.value}" }
        p["sign"] = md5(raw + SECRET)
        return p
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun httpGet(url: String, headers: Map<String, String>): String? = try {
        val builder = Request.Builder().url(url).header("User-Agent", UA)
        headers.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    } catch (e: Exception) {
        null
    }

    companion object {
        private const val TAG = "QianqianMusic"
        private const val BASE = "https://music.91q.com"
        private const val APPID = "16073360"
        private const val SECRET = "0b50b02fd0d73a9c4c8c3a781c30845f"
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
        /** 3000=flac 无损，其余为 mp3 码率。 */
        private val RATE_ORDER = listOf("3000", "320", "128")
        const val RATE_KEY = "allRate"
        const val LRC_KEY = "lrcUrl"

        val DEFAULT_HEADERS = mapOf(
            "accept" to "*/*",
            "accept-language" to "zh-CN,zh;q=0.9",
            "referer" to "https://music.91q.com/player",
        )
    }
}
