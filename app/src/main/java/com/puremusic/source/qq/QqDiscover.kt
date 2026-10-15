package com.puremusic.source.qq

import android.util.Log
import com.google.gson.JsonObject
import com.puremusic.data.AccountStore
import com.puremusic.model.MusicSourceType
import com.puremusic.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * QQ 音乐「发现」数据源：排行榜 / 歌单广场 / 歌单详情 / 专辑曲目（发现页要的都在这）。
 *
 * ## 通道选择（2026-10-15 真机三探针定论）
 *
 * 现代 `musicu.fcg` 的 music/playlist/musichallSvr/personalrec 模块在真机一律 `req_0.code=500003`
 * 风控拒绝（同一通道的 search / vkey / srfDissInfo 却正常 —— 卡的是模块族不是通道）。
 * 转用老一代 `c.y.qq.com/v8/fcg-bin/...` Web 端点（与评论接口 `fcg_global_comment_h5` 同族），
 * 全部匿名可读、几乎不风控，实测以下模块可用：
 * - 歌手资料：`fcg_v8_singer_detail_cp.fcg?singermid=`（返回 `getSingerInfo` + 简介）
 * - 歌手专辑：`fcg_v8_singer_album.fcg?singermid=`（返回 `data.list[]`）
 * - 榜单歌曲：`fcg_v8_toplist_cp.fcg?topid=`（返回 `songlist[].data`）
 * - 歌单广场：`splcloud/fcgi-bin/fcg_get_diss_by_tag.fcg?categoryId=`（返回 `data.list[]`）
 * - 歌单曲目：复用 [com.puremusic.source.qq.QqMusicSource.getPlaylistDetail]（musicu srfDissInfo，已可用）
 *
 * 榜单「榜单列表」端点（fcg_v8_toplist_opt）在真机返回 null，故榜单 id/名称直接内置稳定常量。
 */
@Singleton
class QqDiscover @Inject constructor(
    @Named("plain") private val client: OkHttpClient,
    private val accountStore: AccountStore,
    private val qqSource: QqMusicSource,
) {

    private val gson = com.google.gson.Gson()

    // ═══════════════════════════════════════════════════════════════
    //  排行榜（内置稳定榜单，规避 toplist_opt 在真机返回 null）
    // ═══════════════════════════════════════════════════════════════

    /** QQ 音乐稳定榜单（topid 长期不变）。名称取自官方 App，封面走腾讯图床按 topid 拼。 */
    fun toplists(): List<QqToplist> = BUILTIN_TOPLISTS

    /**
     * 榜单歌曲（点进榜单后的曲目）—— 榜单 id 即 topid。
     * `fcg_v8_toplist_cp.fcg` 返回 `songlist[]`，每首歌的字段都在嵌套的 `data` 里。
     */
    suspend fun toplistSongs(topid: String): List<Song> = withContext(Dispatchers.IO) {
        val url = topListCpUrl(topid)
        val text = get(url) ?: return@withContext emptyList()
        val root = runCatching { gson.fromJson(text, JsonObject::class.java) }.getOrNull() ?: return@withContext emptyList()
        val list = root.getAsJsonArray("songlist") ?: return@withContext emptyList()
        val out = ArrayList<Song>(list.size())
        list.forEach { el ->
            val song = runCatching { (el as JsonObject).getAsJsonObject("data") }.getOrNull()?.let { parseCpSong(it) }
            song?.let { out += it }
        }
        Log.d(TAG, "toplistSongs topid=$topid n=${out.size}")
        // 首次拉取时把榜单名打出来核对（防止内置名称与 topid 对不上）
        if (out.isNotEmpty()) {
            val name = root.stringOf("name", "topname") ?: root.obj("topinfo")?.stringOf("name", "topname").orEmpty()
            Log.e(TAG, "toplistSongs topid=$topid serverName=$name")
        }
        out
    }

    // ═══════════════════════════════════════════════════════════════
    //  歌单广场（分类 + 分页）
    // ═══════════════════════════════════════════════════════════════

    /** 歌单分类（内置稳定大类；diss_by_tag 的分类列表端点真机返回空，故内置）。 */
    fun categories(): List<QqCategory> = BUILTIN_CATEGORIES

    /**
     * 歌单广场一页：按分类 id 翻页。
     * `fcg_get_diss_by_tag.fcg` 返回 `data.list[]`，每项是 `dissid` + `dissname` + 封面等。
     */
    suspend fun squarePlaylists(
        categoryId: String = "10000000",
        page: Int = 0,
        pageSize: Int = 30,
    ): QqSquarePage = withContext(Dispatchers.IO) {
        // ⚠️ 广场分页参数是 **sin/ein（起止下标，闭区间）**，不是 page/pagesize：
        // 用 pagesize/page 组合服务端只回 1 个样本（2026-10-15 curl 实测 n=1），
        // 改成 sin=0&ein=29 即完整 30 个。more 判断也要用 ein。
        val sin = page * pageSize
        val ein = sin + pageSize - 1
        val url = "https://c.y.qq.com/splcloud/fcgi-bin/fcg_get_diss_by_tag.fcg" +
            "?categoryId=$categoryId&sortId=5&sin=$sin&ein=$ein" +
            "&g_tk=5381&loginUin=0&hostUin=0&format=json&inCharset=utf8" +
            "&outCharset=utf-8&notice=0&platform=yqq&needNewCode=0"
        // 广场也是登录态接口：匿名请求每页只回 1 个样本（2026-10-15 curl 实测
        // page=0/1 均 n=1），必须带登录 Cookie 才给整页 30 个。
        accountStore.awaitLoaded()
        val cookie = accountStore.accounts.value[MusicSourceType.QQ]?.cookie
        val text = get(url, cookie = cookie) ?: return@withContext QqSquarePage()
        val root = runCatching { gson.fromJson(text, JsonObject::class.java) }.getOrNull() ?: return@withContext QqSquarePage()
        val arr = root.obj("data")?.getAsJsonArray("list")
        val list = arr?.mapNotNull { runCatching { (it as JsonObject).toCard() }.getOrNull() }.orEmpty()
        val sum = root.obj("data")?.longOf("sum") ?: 0L
        Log.e(TAG, "square categoryId=$categoryId sin=$sin ein=$ein n=${list.size} sum=$sum logged=${cookie != null}")
        QqSquarePage(
            playlists = list,
            more = (ein + 1) < sum,
            total = sum,
        )
    }

    /** 歌单曲目（点进歌单广场某歌单后的歌曲）—— 复用已有的 musicu srfDissInfo 通道。 */
    suspend fun dissSongs(dissid: String): List<Song> =
        qqSource.getPlaylistDetail(com.puremusic.model.Playlist(dissid, "", source = MusicSourceType.QQ))

    // ═══════════════════════════════════════════════════════════════
    //  每日推荐（登录态；端点真机偶发空，失败返回空，UI 给占位）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 每日推荐（QQ 的「今日私享」每日 30 首）。
     *
     * 端点考古（2026-10-15 真机 + curl）：
     * - 老端点 `fcg_recommend_song_u.fcg` 已 **HTTP 404 下线**，别再试。
     * - `music.recommend.RecommendFeed.get_recommend_feed` 是登录态接口（匿名返空壳），
     *   返回首页推荐流 `v_shelf[]`；其中 **jumptype=10014** 的卡片就是「今日私享」
     *   个人歌单（dissid 形态的大数字 id），拿到 id 再走 [qqSource] 的歌单详情通道读曲。
     */
    suspend fun dailyRecommend(): List<Song> = withContext(Dispatchers.IO) {
        accountStore.awaitLoaded()
        val account = accountStore.accounts.value[MusicSourceType.QQ] ?: run {
            Log.e(TAG, "dailyRecommend: QQ 未登录，直接返回空")
            return@withContext emptyList()
        }
        val json = """{"comm":{"ct":24,"cv":0,"uin":"${account.userId.takeIf { it.isNotBlank() } ?: "0"}"},""" +
            """"req_0":{"module":"music.recommend.RecommendFeed","method":"get_recommend_feed","param":{}}}"""
        val text = post(json, cookie = account.cookie) ?: run {
            Log.e(TAG, "dailyRecommend: musicu HTTP 失败")
            return@withContext emptyList()
        }
        val root = runCatching { gson.fromJson(text, JsonObject::class.java) }.getOrNull() ?: run {
            Log.e(TAG, "dailyRecommend: JSON 解析失败 body=${text.take(200)}")
            return@withContext emptyList()
        }
        // v_shelf[].v_niche[0].v_card[0]：jumptype=10014 即「今日私享」歌单卡。
        val cards = root.obj("req_0")?.obj("data")?.getAsJsonArray("v_shelf")
            ?.mapNotNull { shelf -> (shelf as? JsonObject)?.vCardOf() }
            .orEmpty()
        val feedCards = cards.mapNotNull { c ->
            val jt = c.intOf("jumptype")
            if (jt == 10014) c.stringOf("id")?.takeIf { it.isNotBlank() } else null
        }
        Log.e(TAG, "dailyRecommend feed 10014-cards=$feedCards (共 ${cards.size} 张卡)")
        val dissid = feedCards.firstOrNull() ?: run {
            Log.e(TAG, "dailyRecommend: 推荐流里没有今日私享卡片 body=${text.take(300)}")
            return@withContext emptyList()
        }
        val songs = runCatching {
            qqSource.getPlaylistDetail(
                com.puremusic.model.Playlist(dissid, "今日私享", source = MusicSourceType.QQ),
            )
        }.getOrDefault(emptyList())
        Log.e(TAG, "dailyRecommend dissid=$dissid n=${songs.size}")
        songs
    }

    /** shelf → v_niche[0].v_card[0]（卡片本体；jumptype/id/title 都在这里）。 */
    private fun JsonObject.vCardOf(): JsonObject? {
        val niche = (getAsJsonArray("v_niche")?.firstOrNull() as? JsonObject) ?: return null
        return (niche.getAsJsonArray("v_card")?.firstOrNull() as? JsonObject)
    }

    /** 复用 QqMusicSource 的 cookie 取值工具。 */
    private fun cookieField(cookie: String, name: String): String =
        Regex("(?:^|;)\\s*$name=([^;]*)").find(cookie)?.groupValues?.get(1).orEmpty()

    // ═══════════════════════════════════════════════════════════════
    //  HTTP + 解析
    // ═══════════════════════════════════════════════════════════════

    private suspend fun get(url: String, cookie: String? = null): String? = withContext(Dispatchers.IO) {
        runCatching {
            val builder = Request.Builder().url(url)
                .header("User-Agent", UA)
                .header("Referer", "https://y.qq.com/")
            if (!cookie.isNullOrBlank()) builder.header("Cookie", cookie)
            client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    // 失败必须留下服务端真实状态码 + body 摘要：
                    // 「HTTP 失败」到底是 403 风控、还是 400 参数错，码决定下一步怎么修。
                    Log.e(TAG, "HTTP ${resp.code} url=${url.take(120)} body=${resp.body?.string()?.take(150)}")
                    ""
                } else {
                    resp.body?.string().orEmpty()
                }
            }
        }.onFailure { Log.e(TAG, "HTTP 异常 url=${url.take(120)} err=$it") }
            .getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** musicu.fcg 的 POST 通道（推荐流等新端点只认 POST + JSON body）。 */
    private suspend fun post(json: String, cookie: String? = null): String? = withContext(Dispatchers.IO) {
        runCatching {
            val builder = Request.Builder().url("https://u.y.qq.com/cgi-bin/musicu.fcg")
                .post(
                    json.toRequestBody(
                        "application/json; charset=utf-8".toMediaType(),
                    ),
                )
                .header("Referer", "https://y.qq.com/")
                .header("User-Agent", UA)
            if (!cookie.isNullOrBlank()) builder.header("Cookie", cookie)
            client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.e(TAG, "POST HTTP ${resp.code} body=${resp.body?.string()?.take(150)}")
                    ""
                } else {
                    resp.body?.string().orEmpty()
                }
            }
        }.onFailure { Log.e(TAG, "POST 异常 err=$it") }
            .getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** 把 toplist_cp / album_cp 里嵌套的 `data` 对象转成 Song（QQ 旧端点的字段全是裸名）。 */
    private fun parseCpSong(data: JsonObject): Song {
        val songmid = data.stringOf("songmid", "song_mid").orEmpty()
        val title = data.stringOf("songname", "song_name", "title").orEmpty()
        val singers = data.getAsJsonArray("singer")
        val artist = singers?.mapNotNull { (it as? JsonObject)?.stringOf("name") }.orEmpty()
            .joinToString(" / ").ifBlank { "未知歌手" }
        val albumMid = data.stringOf("albummid", "album_mid").orEmpty()
        val albumId = data.longOf("albumid", "album_id").takeIf { it > 0L }?.toString().orEmpty()
        val albumName = data.stringOf("albumname", "album_name").orEmpty()
        val interval = data.longOf("interval").takeIf { it > 0L } ?: 0L
        val pay = data.obj("pay")
        val vip = (pay?.intOf("paytrack", "pay_track", "payalbum", "pay_album") ?: 0) > 0
        val cover = albumMid.takeIf { it.isNotBlank() }
            ?.let { "https://y.gtimg.cn/music/photo_new/T002R300x300M000$it.jpg" }.orEmpty()
        val extra = buildMap {
            put("songMid", songmid)
            if (albumId.isNotBlank()) put("albumId", albumId)
            if (albumMid.isNotBlank()) put("albumMid", albumMid)
        }
        return Song(
            source = MusicSourceType.QQ,
            id = songmid,
            title = title,
            artist = artist,
            album = albumName,
            coverUrl = cover,
            durationMs = interval * 1000L,
            extra = extra,
        )
    }

    private fun JsonObject.toCard(): QqPlaylistCard? {
        val id = longOf("dissid", "diss_id").takeIf { it > 0L } ?: return null
        return QqPlaylistCard(
            id = id,
            name = stringOf("dissname", "diss_name").orEmpty().ifBlank { "歌单" },
            coverUrl = stringOf("imgurl", "picurl", "cover").orEmpty().replace("http://", "https://"),
            trackCount = intOf("song_num", "songnum", "total_song_num"),
            playCount = longOf("listennum", "listen_num"),
            creatorName = stringOf("creator_name", "nickname").orEmpty(),
            tags = stringOf("tags")?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
        )
    }

    private companion object {
        const val TAG = "QqDiscover"
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        fun topListCpUrl(topid: String): String =
            "https://c.y.qq.com/v8/fcg-bin/fcg_v8_toplist_cp.fcg" +
                "?type=0&topid=$topid&page=0&pagesize=300&song_begin=0" +
                "&g_tk=5381&loginUin=0&hostUin=0&format=json&inCharset=utf8" +
                "&outCharset=utf-8&notice=0&platform=yqq.json&needNewCode=0"

        /**
         * 内置稳定榜单（topid 长期不变）。
         *
         * 名称取自 QQ 音乐官方 toplist 的 `ListName`（已剥离 `巅峰榜·` 前缀），
         * 封面取自接口返回的 `pic_v12`（T003 图床，HTTPS），更新频率取自 `UpdateType`。
         * 不依赖 `fcg_v8_toplist_opt`（该列表接口在真机/PC 均返回空，只能内置）。
         */
        val BUILTIN_TOPLISTS = listOf(
            QqToplist("26", "热歌", "https://y.gtimg.cn/music/photo_new/T003R300x300M00000187Di31lZYoL.jpg", "每日更新"),
            QqToplist("27", "新歌", "https://y.gtimg.cn/music/photo_new/T003R300x300M000004E2T6B3dpeiZ.jpg", "每日更新"),
            QqToplist("62", "飙升榜", "https://y.gtimg.cn/music/photo_new/T003R300x300M000003QQxZl18UEuL.jpg", "每日更新"),
            QqToplist("4", "流行指数", "https://y.gtimg.cn/music/photo_new/T003R300x300M000003YvFNK2QKXbj.jpg", "每日更新"),
            QqToplist("5", "内地", "https://y.gtimg.cn/music/photo_new/T003R300x300M000004brqID4ImZC5.jpg", "每周更新"),
            QqToplist("3", "欧美", "https://y.gtimg.cn/music/photo_new/T003R300x300M000003EMoue3ajtGw.jpg", "每周更新"),
            QqToplist("16", "韩国", "https://y.gtimg.cn/music/photo_new/T003R300x300M000002dw0hE477NtW.jpg", "每周更新"),
            QqToplist("6", "港台", "https://y.gtimg.cn/music/photo_new/T003R300x300M000000CLXb916ik5d.jpg", "每周更新"),
            QqToplist("17", "日本", "https://y.gtimg.cn/music/photo_new/T003R300x300M0000041QHNA1852C3.jpg", "每周更新"),
            QqToplist("58", "说唱榜", "https://y.gtimg.cn/music/photo_new/T003R300x300M00000409UJk010mMt.jpg", "每周更新"),
            QqToplist("57", "电音榜", "https://y.gtimg.cn/music/photo_new/T003R300x300M000000Gu6Wx2BVT3g.jpg", "每周更新"),
            QqToplist("19", "摇滚", "https://imgcache.qq.com/music/common/upload/iphone_order_channel/toplist_19_300_105830774.jpg", "每日更新"),
            QqToplist("18", "民谣", "https://imgcache.qq.com/music/common/upload/iphone_order_channel/toplist_18_300_104133518.jpg", "每日更新"),
            QqToplist("29", "影视金曲", "https://y.gtimg.cn/music/photo_new/T003R300x300M000001PjJ6x15Xs7x.jpg", "每周更新"),
            QqToplist("60", "抖音热歌", "https://y.gtimg.cn/music/photo_new/T003R300x300M000003CEoMk1bGMNr.jpg", "每周更新"),
        )

        /**
         * 内置歌单大类（categoryId 对应 QQ 歌单广场的顶层分类）。
         *
         * ⚠️ 不要在这里放「全部」：UI 的 [CategoryChips] 会自己补一个「全部」胶囊，
         * 重复会在 LazyRow 里撞 key（`cat-全部`）直接崩页。
         * 「全部」在 UI 侧选中时映射为空串，[squarePlaylists] 收到空串会回落到 10000000。
         */
        val BUILTIN_CATEGORIES = listOf(
            QqCategory("10000001", "华语"),
            QqCategory("10000002", "欧美"),
            QqCategory("10000003", "韩语"),
            QqCategory("10000004", "日语"),
            QqCategory("10000005", "粤语"),
            QqCategory("10000006", "流行"),
            QqCategory("10000007", "摇滚"),
            QqCategory("10000008", "民谣"),
            QqCategory("10000009", "电子"),
            QqCategory("10000010", "说唱"),
        )
    }
}

/** 一个榜单（topid + 名称 + 封面 + 更新频率）。 */
data class QqToplist(
    val id: String,
    val name: String,
    val coverUrl: String = "",
    /** 更新频率文案（"每日更新" / "每周更新"），直接当卡片副标题。 */
    val subtitle: String = "",
)

/** 歌单广场的一张卡片。 */
data class QqPlaylistCard(
    val id: Long,
    val name: String,
    val coverUrl: String = "",
    val trackCount: Int = 0,
    val playCount: Long = 0L,
    val creatorName: String = "",
    val tags: List<String> = emptyList(),
)

/** 歌单广场一页结果。 */
data class QqSquarePage(
    val playlists: List<QqPlaylistCard> = emptyList(),
    val more: Boolean = false,
    val total: Long = 0L,
)

/** 歌单广场 / 精选歌单的分类标签。 */
data class QqCategory(
    val id: String,
    val name: String,
)

/** 容错取值：字段缺失 / 类型不符一律给 null，由上层兜底，绝不抛异常。 */
private fun JsonObject.obj(key: String): JsonObject? {
    val el = get(key) ?: return null
    return if (el.isJsonObject) el.asJsonObject else null
}

private fun JsonObject.stringOf(vararg keys: String): String? {
    for (k in keys) {
        val el = get(k) ?: continue
        if (el.isJsonPrimitive) {
            val v = runCatching { el.asString }.getOrNull()
            if (!v.isNullOrBlank()) return v
        }
    }
    return null
}

private fun JsonObject.longOf(vararg keys: String): Long {
    for (k in keys) {
        val el = get(k) ?: continue
        if (el.isJsonPrimitive) {
            val v = runCatching { el.asLong }.getOrNull() ?: el.asString.toLongOrNull()
            if (v != null && v > 0L) return v
        }
    }
    return 0L
}

private fun JsonObject.intOf(vararg keys: String): Int {
    for (k in keys) {
        val el = get(k) ?: continue
        if (el.isJsonPrimitive) {
            val v = runCatching { el.asInt }.getOrNull() ?: el.asString.toIntOrNull()
            if (v != null) return v
        }
    }
    return 0
}
