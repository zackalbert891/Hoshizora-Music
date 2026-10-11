package com.puremusic.source.kugou

import com.google.gson.Gson
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

/** 酷狗搜索响应（song_search_v2）：条目在 data.lists。 */
data class KugouSearchResponse(val status: Int = 0, val data: KugouSearchData? = null)
data class KugouSearchData(val total: Int = 0, val lists: List<KugouSong>? = null)

/** 各音质各自的 hash：SQ=flac 无损、HQ=320k、FileHash=128k。 */
data class KugouSong(
    val FileHash: String = "",
    val HQFileHash: String = "",
    val SQFileHash: String = "",
    val SongName: String = "",
    val SingerName: String = "",
    val AlbumName: String = "",
    /** 秒 */
    val Duration: Int = 0,
    val Image: String? = null,
)

/** 取流响应：url / backup_url / mp3Url，字段可能是数组。 */
data class KugouUrlResponse(
    val status: Int = 0,
    val url: Any? = null,
    val backup_url: Any? = null,
    val mp3Url: Any? = null,
)

/**
 * 酷狗音乐源（免登录）。
 *
 * 协议（学自 CharlesPikachu/musicdl 的 KugouMusicClient，已在本机实测通过）：
 *  1. 搜索：GET https://songsearch.kugou.com/song_search_v2
 *     ?keyword=&page=&pagesize=&userid=-1&platform=WebFilter&filter=2&iscorrection=1&privilege_filter=0
 *     **不需要 dfid / mid 等设备指纹**（早期 www.kugou.com 的新版接口才需要，故改用本端点）。
 *     条目在 data.lists，字段：FileHash / HQFileHash / SQFileHash / SongName / SingerName /
 *     AlbumName / Duration(秒) / Image（含 {size} 占位符，需替换）。
 *  2. 取流：GET https://trackercdnbj.kugou.com/i/v2/?cmd=23&pid=1&behavior=play&hash=&key=
 *     其中 key = MD5(hash + "kgcloudv2")（另有 appv2 下载端 cmd=21&appid=1001，实测易超时）。
 *     响应 status=1 且 url 非空即成功；status=2 表示该曲目需付费/无权限，应换档位或换源。
 *
 * 音质策略：SQ(flac) → HQ(320k) → 标准(128k) 依次尝试，能拿最高档就用最高档。
 */
@Singleton
class KugouMusicSource @Inject constructor(
    @Named("plain") private val client: OkHttpClient,
) : MusicSource {

    private val gson = Gson()

    override val type = MusicSourceType.KUGOU
    override val displayName = "酷狗音乐"
    override val supportsLogin = false

    override suspend fun search(keyword: String, page: Int, limit: Int): List<Song> =
        withContext(Dispatchers.IO) {
            val url = "$SEARCH_BASE/song_search_v2?" + listOf(
                "keyword" to keyword,
                "page" to (page + 1).toString(),
                "pagesize" to limit.coerceAtMost(30).toString(),
                "userid" to "-1",
                "clientver" to "",
                "platform" to "WebFilter",
                "filter" to "2",
                "iscorrection" to "1",
                "privilege_filter" to "0",
            ).joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
            val body = httpGet(url, mapOf("Referer" to "https://www.kugou.com/"))
                ?: return@withContext emptyList()
            val resp = runCatching {
                gson.fromJson(body, KugouSearchResponse::class.java)
            }.getOrNull() ?: return@withContext emptyList()
            resp.data?.lists.orEmpty().mapNotNull { it.toSong() }
        }

    /** 取流：按 SQ → HQ → 标准档位依次尝试（extra 里带了各档 hash）。 */
    override suspend fun getSongUrl(song: Song): String? = withContext(Dispatchers.IO) {
        // 换源场景用真实平台 hash；否则优先高音质 hash
        val candidates = buildList {
            song.extra[AutoSwitchSource.SRC_ID_KEY]?.takeIf { it.isNotBlank() }?.let { add(it) }
            song.extra[SQ_KEY]?.takeIf { it.isNotBlank() }?.let { add(it) }
            song.extra[HQ_KEY]?.takeIf { it.isNotBlank() }?.let { add(it) }
            song.id.takeIf { it.isNotBlank() }?.let { add(it) }
        }.distinct()
        for (hash in candidates) {
            requestUrl(hash)?.let { return@withContext it }
        }
        null
    }

    /** 歌词：酷狗歌词需 krcs 两步（search → download → base64 解码），暂返回空。 */
    override suspend fun getLyrics(song: Song): Lyrics = Lyrics()

    override suspend fun getUserPlaylists(): List<Playlist> = emptyList()

    override suspend fun getPlaylistDetail(playlist: Playlist): List<Song> = emptyList()

    override suspend fun login(phone: String, password: String): Account =
        throw UnsupportedOperationException("酷狗音源免登录，无需登录")

    override suspend fun logout() {
        // 免登录源无需清理凭证
    }

    private fun KugouSong.toSong(): Song? {
        val hash = FileHash.ifBlank { return null }
        val title = SongName.ifBlank { return null }
        return Song(
            source = MusicSourceType.KUGOU,
            id = hash,
            title = title,
            // 标题常写成「歌名 - 歌手」或含 <em> 高亮标签，这里只做高亮标签清理
            artist = SingerName.replace("<em>", "").replace("</em>", ""),
            album = AlbumName,
            coverUrl = (Image ?: "").replace("{size}", "400"),
            durationMs = Duration * 1000L,
            extra = buildMap {
                if (SQFileHash.isNotBlank()) put(SQ_KEY, SQFileHash)
                if (HQFileHash.isNotBlank()) put(HQ_KEY, HQFileHash)
            },
        )
    }

    /** key = MD5(hash + "kgcloudv2")，取 webv2 播放通道（cmd=23）。 */
    private fun requestUrl(hash: String): String? {
        val key = md5(hash + KEY_SALT)
        val url = "$TRACKER_BASE/i/v2?" + listOf(
            "cmd" to "23",
            "pid" to "1",
            "behavior" to "play",
            "hash" to hash,
            "key" to key,
        ).joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val body = httpGet(url, mapOf("Referer" to "https://www.kugou.com/")) ?: return null
        val resp = runCatching {
            gson.fromJson(body, KugouUrlResponse::class.java)
        }.getOrNull() ?: return null
        if (resp.status != 1) return null
        return firstUrl(resp.url) ?: firstUrl(resp.backup_url) ?: firstUrl(resp.mp3Url)
    }

    /** 字段名可能是字符串或字符串数组，统一取第一个。 */
    private fun firstUrl(value: Any?): String? = when (value) {
        is String -> value.takeIf { it.startsWith("http") }
        is List<*> -> (value.firstOrNull() as? String)?.takeIf { it.startsWith("http") }
        else -> null
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
        private const val SEARCH_BASE = "https://songsearch.kugou.com"
        private const val TRACKER_BASE = "https://trackercdnbj.kugou.com"
        private const val KEY_SALT = "kgcloudv2"
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36"
        const val SQ_KEY = "sqHash"
        const val HQ_KEY = "hqHash"
    }
}
