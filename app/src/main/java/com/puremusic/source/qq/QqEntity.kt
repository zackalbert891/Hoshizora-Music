package com.puremusic.source.qq

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.stream.JsonReader
import com.puremusic.model.MusicSourceType
import com.puremusic.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/** 歌手资料。 */
data class QqArtistDetail(
    val name: String = "",
    val singerMid: String = "",
    val coverUrl: String = "",
    /** 英文名 / 别名（QQ 的 Fother_name），可空。 */
    val alias: String = "",
    val songCount: Int = 0,
    val albumCount: Int = 0,
    val briefDesc: String = "",
)

/** 歌手专辑列表里的一张专辑卡。 */
data class QqAlbumCard(
    /** 数字 albumid（作路由 key）。 */
    val id: String = "",
    val name: String = "",
    val coverUrl: String = "",
    /** 发布日期（yyyy-MM-dd，可为空）。 */
    val publishTime: String = "",
    val size: Int = 0,
)

/** 专辑分页（QQ 用 begin 翻页，接口不吐 more，本层用「还有没有内容」判定）。 */
data class QqAlbumPage(
    val albums: List<QqAlbumCard> = emptyList(),
    val more: Boolean = false,
)

/** 专辑详情 + 全量歌曲。 */
data class QqAlbumFull(
    /** 数字 albumid。 */
    val id: String = "",
    val name: String = "",
    val coverUrl: String = "",
    val artistName: String = "",
    /** 主歌手 mid，供「跳歌手页」用。 */
    val artistMid: String = "",
    /** 发布日期（毫秒时间戳；取不到为 0）。 */
    val publishTime: Long = 0L,
    val description: String = "",
    val songs: List<Song> = emptyList(),
)

/**
 * QQ 音乐「歌手 / 专辑」数据源：歌手资料、歌手专辑列表、专辑详情与全量歌曲。
 *
 * ## 通道（2026-10-15 curl 实测）
 *
 * 全部走 `c.y.qq.com/v8/fcg-bin/...` 裸 GET + 官方 H5 Cookie 无关（匿名可用）：
 * - `fcg_v8_singer_detail_cp.fcg?singerMid=` → `getSingerInfo`(Fsinger_name/Fsinger_mid/Fother_name)
 *   + 顶层 `singerBrief` 简介 + `total_song`/`total_album` 统计。
 * - `fcg_v8_singer_album.fcg?singerMid=&order=time&begin=&num=` → `data.list[]`
 *   (albumID 数字 / albumMID / albumName / pubTime / latest_song.song_count)。
 * - `fcg_v8_album_detail_cp.fcg?albumid=<数字>` → `getAlbumInfo`(Falbum_name/Falbum_mid/Falbum_id/Fpublic_time)
 *   + `getAlbumDesc.Falbum_desc` + `singerInfo[]`(Fsinger_mid/Fsinger_name) + `getSongInfo[]`(全量曲)。
 * - `fcg_v8_album_info_cp.fcg?albummid=` → `data.id`(数字 id)，用于把 albummid 解析成数字 id。
 *
 * 坑：`fcg_v8_album_detail_cp.fcg` **只认数字 albumid**，传 albummid 直接 404 空响应；
 * 所以专辑页统一用数字 id，albummid 先用 `fcg_v8_album_info_cp` 换出数字 id 再查。
 *
 * 封面按 QQ 图片 URL 规律拼：歌手 T001 + singermid，专辑 T002 + albummid（[qqSingerCoverUrl] / [qqAlbumCoverUrl]）。
 */
@Singleton
class QqEntity @Inject constructor(
    @Named("plain") private val client: OkHttpClient,
) {
    private val gson = Gson()

    /** 歌手资料 + 统计 + 简介。 */
    suspend fun artist(singerMid: String): QqArtistDetail? = withContext(Dispatchers.IO) {
        if (singerMid.isBlank()) {
            Log.e(TAG, "artist: singerMid 为空，路由可能没带上 extra")
            return@withContext null
        }
        // ⚠️ 参数名必须**全小写 singermid**：写成驼峰 singerMid 服务端返
        // {"code":404,"message":"no such singerid"}（2026-10-15 真机实测）。
        val root = getJson("$SINGER_DETAIL?singermid=$singerMid&$BASE") ?: run {
            Log.e(TAG, "artist: 接口失败 singerMid=$singerMid")
            return@withContext null
        }
        val info = root.obj("getSingerInfo") ?: run {
            Log.e(TAG, "artist: 响应无 getSingerInfo body=${root.toString().take(200)}")
            return@withContext null
        }
        val mid = info.str("Fsinger_mid").ifBlank { singerMid }
        Log.e(TAG, "artist ok singerMid=$mid name=${info.str("Fsinger_name")}")
        QqArtistDetail(
            name = info.str("Fsinger_name").ifBlank { mid },
            singerMid = mid,
            coverUrl = qqSingerCoverUrl(mid),
            alias = info.str("Fother_name"),
            songCount = root.int("total_song"),
            albumCount = root.int("total_album"),
            briefDesc = root.str("singerBrief").trim(),
        )
    }

    /** 歌手专辑列表（按 begin 分页）。 */
    suspend fun artistAlbums(singerMid: String, begin: Int = 0, num: Int = 30): QqAlbumPage =
        withContext(Dispatchers.IO) {
            if (singerMid.isBlank()) return@withContext QqAlbumPage()
            val root = getJson("$SINGER_ALBUM?singermid=$singerMid&order=time&begin=$begin&num=$num&$BASE")
                ?: return@withContext QqAlbumPage()
            val data = root.obj("data") ?: return@withContext QqAlbumPage()
            val list = data.arr("list")
                ?.mapNotNull { runCatching { it.asJsonObject }.getOrNull()?.let { toAlbumCard(it) } }
                .orEmpty()
            QqAlbumPage(albums = list, more = list.isNotEmpty())
        }

    /**
     * 专辑详情 + 全量歌曲。
     *
     * @param idOrMid 数字 albumid（直接用）或 albummid（先解析成数字 id 再查）。
     */
    suspend fun album(idOrMid: String): QqAlbumFull? = withContext(Dispatchers.IO) {
        if (idOrMid.isBlank()) return@withContext null
        val numericId = if (idOrMid.all { it.isDigit() }) idOrMid else resolveAlbumId(idOrMid)
        if (numericId.isNullOrBlank()) {
            Log.w(TAG, "album 解析不到数字 id idOrMid=$idOrMid")
            return@withContext null
        }
        val root = getJson("$ALBUM_DETAIL?albumid=$numericId&$BASE") ?: return@withContext null
        val data = root.obj("data") ?: return@withContext null
        val ai = data.obj("getAlbumInfo") ?: JsonObject()
        val albumMid = ai.str("Falbum_mid")
        val singer = data.arr("singerInfo")?.firstOrNull()
            ?.takeIf { it.isJsonObject }?.asJsonObject
        val songs = data.arr("getSongInfo")
            ?.mapNotNull { runCatching { it.asJsonObject }.getOrNull()?.let { s -> toSong(s, albumMid) } }
            .orEmpty()
        QqAlbumFull(
            id = numericId,
            name = ai.str("Falbum_name"),
            coverUrl = qqAlbumCoverUrl(albumMid),
            artistName = singer?.str("Fsinger_name").orEmpty(),
            artistMid = singer?.str("Fsinger_mid").orEmpty(),
            publishTime = parseDateMs(ai.str("Fpublic_time")),
            description = data.obj("getAlbumDesc")?.str("Falbum_desc").orEmpty().trim(),
            songs = songs,
        )
    }

    /** albummid → 数字 albumid（info_cp 的 data.id）。 */
    private suspend fun resolveAlbumId(albumMid: String): String? {
        val root = getJson("$ALBUM_INFO?albummid=$albumMid&$BASE") ?: return null
        return root.obj("data")?.str("id")?.takeIf { it.isNotBlank() }
    }

    private fun toAlbumCard(o: JsonObject): QqAlbumCard? {
        val id = o.str("albumID")
        val mid = o.str("albumMID")
        if (id.isBlank() && mid.isBlank()) return null
        return QqAlbumCard(
            id = id.ifBlank { mid },
            name = o.str("albumName"),
            coverUrl = qqAlbumCoverUrl(mid),
            publishTime = o.str("pubTime"),
            size = o.obj("latest_song")?.int("song_count") ?: 0,
        )
    }

    /** 专辑曲目 → [Song]：补齐 extra（media_mid / albumMid / 数字 albumid / singerMid），点歌换源与跳实体页都靠它。 */
    private fun toSong(o: JsonObject, fallbackAlbumMid: String): Song {
        val mid = o.str("songmid").ifBlank { o.str("mid") }
        val albumMid = o.str("albummid").ifBlank { fallbackAlbumMid }
        val singers = o.arr("singer")
            ?.mapNotNull { runCatching { it.asJsonObject }.getOrNull() }.orEmpty()
        val singerMid = singers.firstOrNull()?.str("mid").orEmpty()
        val mediaMid = o.str("strMediaMid")
        return Song(
            source = MusicSourceType.QQ,
            id = mid,
            title = o.str("songname").ifBlank { o.str("name") },
            artist = singers.joinToString(" / ") { it.str("name") }.ifBlank { "未知歌手" },
            album = o.str("albumname").ifBlank { o.str("albumName") },
            coverUrl = qqAlbumCoverUrl(albumMid),
            durationMs = o.int("interval") * 1000L,
            extra = buildMap {
                mediaMid.takeIf { it.isNotBlank() }?.let { put(MEDIA_MID_KEY_QQ, it) }
                albumMid.takeIf { it.isNotBlank() }?.let { put(EXTRA_QQ_ALBUM_MID, it) }
                o.str("albumid").takeIf { it.isNotBlank() }?.let { put(EXTRA_QQ_ALBUM_ID, it) }
                singerMid.takeIf { it.isNotBlank() }?.let { put(EXTRA_QQ_SINGER_MID, it) }
            },
        )
    }

    private fun parseDateMs(date: String): Long {
        if (date.isBlank()) return 0L
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(date.take(10))?.time
        }.getOrNull() ?: 0L
    }

    // ── HTTP ──────────────────────────────────────────────────────────

    private fun getJson(url: String): JsonObject? {
        val text = get(url)
        if (text.isBlank()) {
            Log.w(TAG, "空白 url=${url.take(90)}")
            return null
        }
        val parsed = runCatching {
            val reader = JsonReader(StringReader(text)).apply { isLenient = true }
            gson.fromJson<JsonObject>(reader, JsonObject::class.java)
        }.getOrNull()
        if (parsed == null) {
            Log.e(TAG, "JSON 解析失败 url=${url.take(90)} body=${text.take(200)}")
            return null
        }
        val code = parsed.longOf("code")
        if (code == 404L) {
            Log.e(TAG, "接口 404 url=${url.take(90)} body=${text.take(200)}")
            return null
        }
        return parsed
    }

    private fun get(url: String): String = runCatching {
        client.newCall(
            Request.Builder().url(url)
                .header("User-Agent", UA)
                .header("Referer", "https://y.qq.com/")
                .get()
                .build(),
        ).execute().use { resp ->
            if (resp.isSuccessful) {
                resp.body?.string().orEmpty()
            } else {
                Log.e(TAG, "HTTP ${resp.code} url=${url.take(90)} body=${resp.body?.string()?.take(150)}")
                ""
            }
        }
    }.onFailure { Log.e(TAG, "HTTP 异常 url=${url.take(90)} err=$it") }
        .getOrDefault("")

    private companion object {
        const val TAG = "QqEntity"
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        const val BASE = "g_tk=5381&loginUin=0&hostUin=0&format=json&inCharset=utf8&" +
            "outCharset=utf-8&notice=0&platform=yqq&needNewCode=0"
        const val SINGER_DETAIL = "https://c.y.qq.com/v8/fcg-bin/fcg_v8_singer_detail_cp.fcg"
        const val SINGER_ALBUM = "https://c.y.qq.com/v8/fcg-bin/fcg_v8_singer_album.fcg"
        const val ALBUM_DETAIL = "https://c.y.qq.com/v8/fcg-bin/fcg_v8_album_detail_cp.fcg"
        const val ALBUM_INFO = "https://c.y.qq.com/v8/fcg-bin/fcg_v8_album_info_cp.fcg"
        /** 与 QqMusicSource 里取流读的键保持一致（"mediaMid"）。 */
        const val MEDIA_MID_KEY_QQ = "mediaMid"
    }
}

/* ---------- 容错取值：字段缺失 / 类型不符一律给空，由上层兜底 ---------- */

private fun JsonObject.obj(key: String): JsonObject? =
    get(key)?.takeIf { it.isJsonObject }?.asJsonObject

private fun JsonObject.arr(key: String): JsonArray? =
    get(key)?.takeIf { it.isJsonArray }?.asJsonArray

private fun JsonObject.str(key: String): String =
    get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asString }.getOrNull() }.orEmpty()

private fun JsonObject.int(key: String): Int =
    runCatching { get(key)?.asInt }.getOrNull() ?: 0

private fun JsonObject.longOf(key: String): Long? =
    runCatching { get(key)?.asLong }.getOrNull()
