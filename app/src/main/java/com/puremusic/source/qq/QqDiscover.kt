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

    /**
     * 歌单分类（语种 / 流派 / 主题 / 心情 / 场景，拍平成一条列表）。
     *
     * 首选官方分类配置端点 `fcg_get_diss_tag_conf.fcg`（2026-10-15 实测可用，
     * 与广场 `fcg_get_diss_by_tag` 同族），成功一次后进程内缓存；失败回落内置常量。
     *
     * ⚠️ 历史坑：此前内置的 10000001~10000010 是**猜的 id**，服务端对不存在的分类
     * 返回空列表 —— 表现为「点任何分类永远是空白」。现在内置表已换成官方实测 id
     * （165 国语 / 6 流行 / 11 摇滚 …），即便在线拉取失败也能正常用。
     */
    suspend fun categories(): List<QqCategory> {
        categoriesCache?.let { return it }
        val fetched = runCatching { fetchCategories() }.getOrDefault(emptyList())
        if (fetched.isNotEmpty()) {
            categoriesCache = fetched
            Log.e(TAG, "categories 在线拉取成功 n=${fetched.size}")
            return fetched
        }
        Log.e(TAG, "categories 在线拉取为空，回落内置 n=${BUILTIN_CATEGORIES.size}")
        return BUILTIN_CATEGORIES
    }

    /** 在线拉取成功后的分类缓存（进程内）；拉取失败不缓存，下次调用会重试。 */
    @Volatile
    private var categoriesCache: List<QqCategory>? = null

    /**
     * 官方分类配置：`data.categories[].items[]`（按 热门/语种/流派/主题/心情/场景 分组）。
     * 这里拍平所有分组；「全部」由 UI 的 [com.puremusic.ui.discover] 自带胶囊提供，跳过。
     */
    private suspend fun fetchCategories(): List<QqCategory> = withContext(Dispatchers.IO) {
        val url = "https://c.y.qq.com/splcloud/fcgi-bin/fcg_get_diss_tag_conf.fcg" +
            "?g_tk=5381&loginUin=0&hostUin=0&format=json&inCharset=utf8" +
            "&outCharset=utf-8&notice=0&platform=yqq.json&needNewCode=0"
        val text = get(url) ?: return@withContext emptyList()
        val root = runCatching { gson.fromJson(text, JsonObject::class.java) }.getOrNull()
            ?: return@withContext emptyList()
        val groups = root.obj("data")?.getAsJsonArray("categories") ?: return@withContext emptyList()
        val out = LinkedHashMap<String, QqCategory>()
        for (gel in groups) {
            val items = (gel as? JsonObject)?.getAsJsonArray("items") ?: continue
            for (iel in items) {
                val o = iel as? JsonObject ?: continue
                val idNum = o.intOf("categoryId")
                if (idNum <= 0 || idNum == 10000000) continue
                val name = unescapeHtml(o.stringOf("categoryName").orEmpty()).trim()
                if (name.isBlank()) continue
                out.putIfAbsent(name, QqCategory(idNum.toString(), name))
            }
        }
        out.values.toList()
    }

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
         * 内置歌单分类（categoryId 全部为 2026-10-15 curl 实测的真实分类 id）。
         *
         * 来源：官方分类配置端点 `fcg_get_diss_tag_conf.fcg` 的返回，按
         * 语种 / 流派 / 主题 / 心情 / 场景 顺序拍平（「全部」10000000 跳过，UI 自带）。
         *
         * ⚠️ 不要凭空编造 categoryId：服务端对不存在的分类 id 返回空列表，
         * 表现是「点分类永远空白」（10000001 这种序号在服务端并不存在）。
         * ⚠️ 也不要在这里放「全部」：UI 的 CategoryChips 会自己补一个「全部」胶囊，
         * 重复会在 LazyRow 里撞 key（`cat-全部`）直接崩页。
         */
        val BUILTIN_CATEGORIES = listOf(
            // 语种
            QqCategory("165", "国语"), QqCategory("167", "英语"), QqCategory("168", "韩语"),
            QqCategory("166", "粤语"), QqCategory("169", "日语"), QqCategory("170", "小语种"),
            QqCategory("203", "闽南语"), QqCategory("204", "法语"), QqCategory("205", "拉丁语"),
            // 流派
            QqCategory("6", "流行"), QqCategory("15", "轻音乐"), QqCategory("11", "摇滚"),
            QqCategory("28", "民谣"), QqCategory("8", "R&B"), QqCategory("153", "嘻哈"),
            QqCategory("24", "电子"), QqCategory("27", "古典"), QqCategory("18", "乡村"),
            QqCategory("22", "蓝调"), QqCategory("21", "爵士"), QqCategory("164", "新世纪"),
            QqCategory("25", "拉丁"), QqCategory("218", "后摇"), QqCategory("219", "中国传统"),
            QqCategory("220", "世界音乐"),
            // 主题
            QqCategory("39", "ACG"), QqCategory("136", "经典"), QqCategory("146", "网络歌曲"),
            QqCategory("133", "影视"), QqCategory("141", "KTV热歌"), QqCategory("131", "儿歌"),
            QqCategory("145", "中国风"), QqCategory("194", "古风"), QqCategory("148", "情歌"),
            QqCategory("196", "城市"), QqCategory("197", "现场音乐"), QqCategory("199", "背景音乐"),
            QqCategory("200", "佛教音乐"), QqCategory("201", "UP主"), QqCategory("202", "乐器"),
            QqCategory("226", "MC喊麦"), QqCategory("14", "DJ"),
            // 心情
            QqCategory("52", "伤感"), QqCategory("122", "安静"), QqCategory("117", "快乐"),
            QqCategory("116", "治愈"), QqCategory("125", "励志"), QqCategory("59", "甜蜜"),
            QqCategory("55", "寂寞"), QqCategory("126", "宣泄"), QqCategory("68", "思念"),
            // 场景
            QqCategory("78", "睡前"), QqCategory("102", "夜店"), QqCategory("101", "学习"),
            QqCategory("99", "运动"), QqCategory("85", "开车"), QqCategory("76", "约会"),
            QqCategory("94", "工作"), QqCategory("81", "旅行"), QqCategory("103", "派对"),
            QqCategory("222", "婚礼"), QqCategory("223", "咖啡馆"), QqCategory("224", "跳舞"),
            QqCategory("16", "校园"),
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

/**
 * 反转义官方配置里出现的 HTML 实体（`R&#38;B` → `R&B`、`&amp;` → `&` 等）。
 * 只处理这几个常见实体，够用且不引依赖。
 */
private fun unescapeHtml(s: String): String {
    if (!s.contains('&')) return s
    return s
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&#39;", "'")
        .replace("&#38;", "&")
        .replace("&#x27;", "'")
}
