package com.puremusic.source.migu

import com.google.gson.Gson
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
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/** 咪咕搜索响应（v1.0/content/search_all.do）：歌曲在 songResultData.result。 */
data class MiguSearchResponse(val code: String? = null, val songResultData: MiguSongResult? = null)
data class MiguSongResult(
    val totalCount: String? = null,
    val result: List<MiguSong>? = null,
)

data class MiguSong(
    val id: String = "",
    val contentId: String = "",
    val copyrightId: String = "",
    val resourceType: String = "2",
    val name: String = "",
    val singers: List<MiguName>? = null,
    val albums: List<MiguName>? = null,
    val imgItems: List<MiguImg>? = null,
    val img1: String? = null,
    val img2: String? = null,
    val img3: String? = null,
    val lyricUrl: String? = null,
    val rateFormats: List<MiguFormat>? = null,
    val newRateFormats: List<MiguFormat>? = null,
)
data class MiguName(val name: String = "")
data class MiguImg(val img: String? = null)
/** 音质档位：formatType=PQ/HQ/SQ/ZQ(无损)，resourceType 参与取流参数。 */
data class MiguFormat(
    val formatType: String = "",
    val resourceType: String = "",
    val size: String? = null,
    val fileType: String? = null,
)

/** 取流响应：data.url 为直链，data.song.duration 为时长（秒）。 */
data class MiguUrlResponse(val code: String? = null, val data: MiguUrlData? = null)
data class MiguUrlData(val url: String? = null, val song: MiguUrlSong? = null)
data class MiguUrlSong(val duration: String? = null)

/**
 * 咪咕音乐源（c.musicapp.migu.cn h5 通道），**免登录**。
 *
 * 这是补齐「周杰伦等独家版权」的关键源：网易云/酷我/QQ 都拿不到的曲目，咪咕常有版权。
 *
 * 协议（学自 CharlesPikachu/musicdl 的 MiguMusicClient，已在本机实测通过）：
 *  1. 搜索：GET https://c.musicapp.migu.cn/v1.0/content/search_all.do
 *     ?text=&pageNo=&pageSize=&isCopyright=1&sort=1&searchSwitch={"song":1,...}
 *     请求头必须带 ua=Android_migu / version / channel / Origin+Referer=h5.nf.migu.cn。
 *     歌曲列表在 songResultData.result（**不是** data.song.list——这点很多人踩坑）。
 *  2. 取流：GET https://c.musicapp.migu.cn/strategy/listen-url/h5/v2.4
 *     ?contentId=&copyrightId=&resourceType=&netType=01&toneFlag=&scene=&lowerQualityContentId=
 *     请求头额外带 birth=h5page 与 signature=1。
 *     **响应可能被加密**：以 AB CD 01 开头时，第 4 字节是 seed，
 *     明文[i] = (密文[4+i] + seed - KEY[i % KEY.size]) & 0xFF —— 见 [decrypt]。
 *  3. 歌词：搜索结果自带 lyricUrl（.lrc 直链）。
 *
 * 档位顺序：优先无损 SQ/ZQ，再降 PQ/HQ/LQ。
 */
@Singleton
class MiguMusicSource @Inject constructor(
    @Named("plain") private val client: OkHttpClient,
) : MusicSource {

    private val gson = Gson()

    override val type = MusicSourceType.MIGU
    override val displayName = "咪咕音乐"
    override val supportsLogin = false

    override suspend fun search(keyword: String, page: Int, limit: Int): List<Song> =
        withContext(Dispatchers.IO) {
            val switch = "{\"song\":1,\"album\":0,\"singer\":0,\"tagSong\":1,\"mvSong\":0,\"bestShow\":1}"
            val url = "$BASE/v1.0/content/search_all.do?" + listOf(
                "text" to keyword,
                "pageNo" to (page + 1).toString(),
                "pageSize" to limit.coerceAtMost(30).toString(),
                "isCopyright" to "1",
                "sort" to "1",
                "searchSwitch" to switch,
            ).joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
            val body = httpGet(url, H5_HEADERS) ?: return@withContext emptyList()
            val resp = runCatching {
                gson.fromJson(body, MiguSearchResponse::class.java)
            }.getOrNull() ?: return@withContext emptyList()
            resp.songResultData?.result.orEmpty().mapNotNull { it.toSong() }
        }

    /** 取流：按音质档位从高到低尝试，响应可能加密故统一走 [decrypt]。 */
    override suspend fun getSongUrl(song: Song): String? = withContext(Dispatchers.IO) {
        val contentId = song.extra[AutoSwitchSource.SRC_ID_KEY] ?: song.id
        if (contentId.isBlank()) return@withContext null
        val copyrightId = song.extra[COPYRIGHT_KEY] ?: return@withContext null
        val formats = parseFormats(song)
        for (f in formats) {
            if (f.formatType == "Z3D") continue // 加密音频格式，播放器解不了
            val url = "$BASE/strategy/listen-url/h5/v2.4?" + listOf(
                "contentId" to contentId,
                "copyrightId" to copyrightId,
                "resourceType" to f.resourceType,
                "netType" to "01",
                "toneFlag" to f.formatType,
                "scene" to "",
                "lowerQualityContentId" to contentId,
            ).joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
            val bytes = httpGetBytes(url, H5_HEADERS + SIGN_HEADERS) ?: continue
            val data = runCatching {
                gson.fromJson(decrypt(bytes), MiguUrlResponse::class.java)?.data
            }.getOrNull() ?: continue
            val playUrl = data.url?.takeIf { it.startsWith("http") } ?: continue
            // 该直链会把 128k 的路径段写成 MP3_128_16_Stero，替换即可拿到 320k
            return@withContext playUrl.replace("MP3_128_16_Stero", "MP3_320_16_Stero")
        }
        null
    }

    override suspend fun getLyrics(song: Song): Lyrics = withContext(Dispatchers.IO) {
        val lrcUrl = song.extra[LRC_KEY]
        if (lrcUrl.isNullOrBlank()) return@withContext Lyrics()
        val text = httpGet(lrcUrl, H5_HEADERS) ?: return@withContext Lyrics()
        Lyrics(lines = runCatching { LrcParser.parse(text) }.getOrDefault(emptyList()))
    }

    override suspend fun getUserPlaylists(): List<Playlist> = emptyList()

    override suspend fun getPlaylistDetail(playlist: Playlist): List<Song> = emptyList()

    override suspend fun login(phone: String, password: String): Account =
        throw UnsupportedOperationException("咪咕音源免登录，无需登录")

    override suspend fun logout() {
        // 免登录源无需清理凭证
    }

    private fun MiguSong.toSong(): Song? {
        val cid = contentId.ifBlank { return null }
        val title = name.ifBlank { return null }
        val artist = singers.orEmpty().joinToString("/") { it.name }.trim('/')
        val cover = imgItems?.lastOrNull()?.img ?: img3 ?: img2 ?: img1 ?: ""
        return Song(
            source = MusicSourceType.MIGU,
            id = cid,
            title = title,
            artist = artist,
            album = albums.orEmpty().firstOrNull()?.name.orEmpty(),
            coverUrl = cover,
            durationMs = 0, // 咪咕搜索不返回时长，取流时由 data.song.duration 补
            extra = buildMap {
                put(COPYRIGHT_KEY, copyrightId)
                val fmts = (newRateFormats.orEmpty() + rateFormats.orEmpty())
                if (fmts.isNotEmpty()) {
                    put(FORMATS_KEY, fmts.joinToString(";") {
                        "${it.formatType},${it.resourceType}"
                    })
                }
                if (!lyricUrl.isNullOrBlank()) put(LRC_KEY, lyricUrl)
            },
        )
    }

    /** 档位顺序：无损优先（SQ/ZQ），再 PQ/HQ/LQ。 */
    private fun parseFormats(song: Song): List<MiguFormat> {
        val raw = song.extra[FORMATS_KEY] ?: return emptyList()
        val list = raw.split(";").mapNotNull { part ->
            val (t, r) = part.split(",").let { (it.getOrNull(0) ?: return@mapNotNull null) to (it.getOrNull(1) ?: "") }
            MiguFormat(formatType = t, resourceType = r)
        }
        val order = listOf("ZQ", "SQ", "ZQ24", "ZQ32", "HQ", "PQ", "LQ")
        return list.sortedBy { order.indexOf(it.formatType).let { i -> if (i < 0) 99 else i } }
    }

    /**
     * 咪咕响应解密：魔数 AB CD 01 开头时为加密体。
     * 第 4 字节（下标 3）为 seed，其后每字节 `(密文 + seed - KEY[i % KEY.size]) & 0xFF`。
     */
    private fun decrypt(raw: ByteArray): String {
        if (raw.size < 4 || raw[0] != MAGIC[0] || raw[1] != MAGIC[1] || raw[2] != MAGIC[2]) {
            return String(raw, Charsets.UTF_8)
        }
        val seed = raw[3].toInt() and 0xFF
        val out = ByteArray(raw.size - 4)
        for (i in out.indices) {
            out[i] = ((raw[i + 4].toInt() and 0xFF) + seed -
                (KEY[i % KEY.size].toInt() and 0xFF)).toByte()
        }
        return String(out, Charsets.UTF_8)
    }

    private fun httpGet(url: String, headers: Map<String, String>): String? =
        httpGetBytes(url, headers)?.let { String(it, Charsets.UTF_8) }

    private fun httpGetBytes(url: String, headers: Map<String, String>): ByteArray? = try {
        val builder = Request.Builder().url(url).header("User-Agent", UA)
        headers.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.bytes() else null
        }
    } catch (e: Exception) {
        null
    }

    companion object {
        private const val BASE = "https://c.musicapp.migu.cn"
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36"
        private val MAGIC = byteArrayOf(0xAB.toByte(), 0xCD.toByte(), 0x01.toByte())
        private val KEY = "Jk8qzuePiJ1qE3mDYhLQ3T73DtDoAhLP".toByteArray(Charsets.UTF_8)
        const val COPYRIGHT_KEY = "copyrightId"
        const val FORMATS_KEY = "formats"
        const val LRC_KEY = "lrcUrl"

        private val H5_HEADERS = mapOf(
            "Accept" to "application/json, text/plain, */*",
            "Origin" to "https://h5.nf.migu.cn",
            "Referer" to "https://h5.nf.migu.cn/",
            "ua" to "Android_migu",
            "version" to "6.8.8",
            "channel" to "014021I",
            "subchannel" to "014021I",
        )
        private val SIGN_HEADERS = mapOf(
            "Content-Type" to "application/json;charset=UTF-8",
            "birth" to "h5page",
            "signature" to "1",
        )
    }
}
