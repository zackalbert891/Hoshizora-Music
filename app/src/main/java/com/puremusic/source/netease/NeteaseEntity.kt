package com.puremusic.source.netease

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.stream.JsonReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import com.puremusic.model.Song
import java.io.StringReader
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * 网易云「歌手 / 专辑」数据源：歌手资料、歌手专辑列表、专辑详情与全量歌曲。
 *
 * ## 通道（2026-10-12 curl 实测，踩过的坑记在这里）
 *
 * 这一层和 [NeteaseDiscover] 一样**只走裸 GET**（`https://music.163.com` + `/api/xxx`），
 * 不走 eapi/weapi。对照实测：
 * - `/api/v1/artist/<id>` 裸 GET → 200，资料全（picUrl / briefDesc / albumSize / musicSize）✅
 * - `/api/artist/albums/<id>?limit&offset` 裸 GET → 200，专辑列表在 `hotAlbums` ✅
 * - `/api/v1/album/<id>` 裸 GET → 200，`album` 元信息 + `songs` 全量曲 ✅
 *
 * 同一批里**已经下线 / 从来不认裸 GET 的**（不要再试了）：
 * - `/api/artist/songs`、`/api/artist/top/songs`、`/api/v1/artist/<id>/topSong` 全 404，
 *   所以「歌手热门歌曲」**拿不到**；歌手页改为「资料 + 专辑列表」，点专辑进专辑页看曲目。
 * - `/api/simi/song`（相似推荐）、`/api/search/hot`、`/api/search/default`、
 *   `/api/playlist/recommend` 同样 404/400 —— 这些功能别再加回来。
 *
 * 歌手热门曲目的替代方案：歌手资料里的 `musicSize` + 头几张专辑的曲目已足够用户用。
 */
@Singleton
class NeteaseEntity @Inject constructor(
    @Named("plain") private val client: OkHttpClient,
) {

    private val gson = Gson()

    /** 歌手资料（头像 / 简介 / 专辑数 / 歌曲数）。 */
    suspend fun artist(id: String): NeteaseArtistDetail? = withContext(Dispatchers.IO) {
        if (id.isBlank() || id == "0") return@withContext null
        val root = getJson("$ARTIST_PATH/$id") ?: return@withContext null
        val a = root.getAsJsonObject("artist") ?: return@withContext null
        NeteaseArtistDetail(
            id = a.long("id").takeIf { it > 0L } ?: id.toLongOrNull() ?: 0L,
            name = a.str("name"),
            avatarUrl = a.str("picUrl").ifBlank { a.str("img1v1Url") },
            alias = a.str("trans"),
            briefDesc = a.str("briefDesc").replace(NeteaseDiscover.HEX_LINE_BREAK, "\n").trim(),
            albumCount = a.int("albumSize"),
            songCount = a.int("musicSize"),
        )
    }

    /** 歌手的专辑列表（分页）。返回的 [ArtistAlbumPage.more] 为 false 表示到底了。 */
    suspend fun artistAlbums(
        id: String,
        limit: Int = 30,
        offset: Int = 0,
        cookie: String? = null,
    ): ArtistAlbumPage = withContext(Dispatchers.IO) {
        val root = getJson("$ARTIST_ALBUMS_PATH/$id?limit=$limit&offset=$offset", cookie)
            ?: return@withContext ArtistAlbumPage()
        val list = root.getAsJsonArray("hotAlbums")
            ?.mapNotNull { runCatching { it.asJsonObject.toAlbumCard() }.getOrNull() }
            .orEmpty()
        ArtistAlbumPage(albums = list, more = root.bool("more") && list.isNotEmpty())
    }

    /** 专辑详情 + 全量歌曲。 */
    suspend fun album(id: String, cookie: String? = null): NeteaseAlbumFull? = withContext(Dispatchers.IO) {
        if (id.isBlank() || id == "0") return@withContext null
        val root = getJson("$ALBUM_PATH/$id", cookie) ?: return@withContext null
        val a = root.getAsJsonObject("album") ?: return@withContext null
        val artist = a.getAsJsonObject("artist")
        NeteaseAlbumFull(
            id = a.long("id").takeIf { it > 0L } ?: id.toLongOrNull() ?: 0L,
            name = a.str("name"),
            coverUrl = a.str("picUrl"),
            artistName = artist?.str("name").orEmpty(),
            artistId = artist?.long("id") ?: 0L,
            description = a.str("description").replace(NeteaseDiscover.HEX_LINE_BREAK, "\n").trim(),
            publishTime = a.long("publishTime"),
            songs = root.getAsJsonArray("songs")
                ?.mapNotNull { runCatching { gson.fromJson(it, NeteaseSong::class.java) }.getOrNull() }
                .orEmpty(),
        )
    }

    /**
     * 听歌排行（歌曲播放次数榜）。
     *
     * 端点：裸 GET `/api/v1/play/record?uid=<uid>&type=<0|1>`，
     * `type=1` 本周、`type=0` 所有时间。**必须带 type**，缺了服务端返回
     * `{"code":-2,"msg":"无权限访问"}` —— 之前「听歌记录一直是空的」就是漏了这个参数。
     * 也必须带登录 Cookie（匿名一律 -2）。
     *
     * 只给「次数」，**服务端不返回时间戳**，所以做不了「最近播放」；
     * 最近播放只能靠本地播放历史（见 `HistoryDao`）。收听时长同样没有官方接口，
     * 只能客户端自己累加 —— 第三方 NCMusic 项目也是这么做的。
     */
    suspend fun playRecord(
        uid: String,
        weekly: Boolean = false,
        limit: Int = 100,
        cookie: String? = null,
    ): List<NeteasePlayRecord> = withContext(Dispatchers.IO) {
        if (uid.isBlank() || uid == "0") return@withContext emptyList()
        val type = if (weekly) 1 else 0
        val root = getJson("$PLAY_RECORD_PATH?uid=$uid&type=$type&limit=$limit", cookie)
            ?: return@withContext emptyList()
        val arr = root.getAsJsonArray("record")
            ?: root.getAsJsonArray("allData")
            ?: return@withContext emptyList()
        arr.mapNotNull { runCatching { it.asJsonObject.toPlayRecord() }.getOrNull() }
            .filter { it.songId > 0L }
    }

    // ═══════════════════════════════════════════════════════════════
    //  HTTP
    // ═══════════════════════════════════════════════════════════════

    private fun getJson(path: String, cookie: String? = null): JsonObject? {
        val text = get("$HOST$path", requestCookie(cookie))
        if (text.isBlank()) {
            Log.w(TAG, "GET 空白 path=$path")
            return null
        }
        return runCatching {
            val reader = JsonReader(StringReader(text)).apply { isLenient = true }
            gson.fromJson<JsonObject>(reader, JsonObject::class.java)
        }.getOrNull()?.takeIf { !it.has("code") || it.long("code") != 404L } ?: run {
            Log.w(TAG, "GET 404/解析失败 path=$path body=${text.take(150)}")
            null
        }
    }

    private fun get(url: String, cookie: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", NeteaseDiscover.UA)
            .header("Referer", "https://music.163.com/")
            .header("Origin", "https://music.163.com")
            .header("Cookie", cookie)
            .get()
            .build()
        return runCatching {
            client.newCall(req).execute().use { if (it.isSuccessful) it.body?.string().orEmpty() else "" }
        }.getOrDefault("")
    }

    private fun requestCookie(cookie: String?): String {
        val base = cookie?.takeIf { it.isNotBlank() }
            ?: return NeteaseDiscover.ANON_COOKIE_PREFIX + java.security.SecureRandom().let {
                val b = ByteArray(8); it.nextBytes(b); b.joinToString("") { x -> "%02x".format(x) }
            }
        if (base.contains("os=") && base.contains("appver")) return base
        return "$base; os=pc; appver=${NeteaseDiscover.APPVER}"
    }

    private fun enc(v: String): String = URLEncoder.encode(v, "UTF-8")

    private fun JsonObject.str(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    private fun JsonObject.long(key: String): Long =
        runCatching { get(key)?.asLong }.getOrNull() ?: 0L

    private fun JsonObject.int(key: String): Int =
        runCatching { get(key)?.asInt }.getOrNull() ?: 0

    private fun JsonObject.bool(key: String): Boolean =
        runCatching { get(key)?.asBoolean }.getOrNull() ?: false

    private fun JsonObject.toAlbumCard(): NeteaseAlbumCard? {
        val id = long("id")
        if (id <= 0L) return null
        return NeteaseAlbumCard(
            id = id,
            name = str("name"),
            coverUrl = str("picUrl"),
            publishTime = long("publishTime"),
            size = int("size"),
        )
    }

    /** `record[]` 元素：songId / playCount + 歌曲快照（ar / al）。 */
    private fun JsonObject.toPlayRecord(): NeteasePlayRecord? {
        val song = runCatching { gson.fromJson(this, NeteaseSong::class.java) }.getOrNull()
            ?: return null
        val artistName = (song.ar ?: song.artists)
            ?.joinToString(" / ") { it.name }.orEmpty().ifBlank { "未知歌手" }
        val albumObj = song.al ?: song.album
        return NeteasePlayRecord(
            songId = song.id,
            playCount = long("playCount"),
            song = Song(
                source = com.puremusic.model.MusicSourceType.NETEASE,
                id = song.id.toString(),
                title = song.name,
                artist = artistName,
                album = albumObj?.name ?: "",
                coverUrl = albumObj?.picUrl ?: "",
                durationMs = if (song.dt > 0L) song.dt else song.duration,
                extra = buildMap {
                    (song.ar ?: song.artists)?.firstOrNull()?.id?.takeIf { it > 0L }
                        ?.let { put(EXTRA_ARTIST_ID, it.toString()) }
                    albumObj?.id?.takeIf { it > 0L }?.let { put(EXTRA_ALBUM_ID, it.toString()) }
                },
            ),
        )
    }

    companion object {
        private const val TAG = "NeteaseEntity"
        private const val HOST = "https://music.163.com"
        private const val ARTIST_PATH = "/api/v1/artist"
        private const val ARTIST_ALBUMS_PATH = "/api/artist/albums"
        private const val ALBUM_PATH = "/api/v1/album"
        /** 听歌排行：必须带 `type`（0=所有时间 1=本周），否则 -2 无权限。 */
        private const val PLAY_RECORD_PATH = "/api/v1/play/record"
    }
}