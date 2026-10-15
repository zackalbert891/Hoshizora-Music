package com.puremusic

import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.puremusic.data.AccountStore
import com.puremusic.model.MusicSourceType
import com.puremusic.source.SourceRegistry
import com.puremusic.source.autoswitch.AutoSwitchSource
import com.puremusic.source.match.SongMatcher
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Named

/**
 * 真机自检台（调试用，不参与正常 UI）。
 *
 * 一条 adb 命令即可跑完「搜索 → 取流 → 跨源换源」全链路，把结果打到 logcat：
 *   adb shell am start -n com.puremusic/.DiagActivity
 *   adb logcat -s PureMusicDiag
 *
 * 存在的意义：音源是否可用**只能靠真机网络判断**（电脑出口 IP 常被风控，
 * 出现 104003 / -462 不代表代码有问题）。手点 UI 验证慢且不可重复，故做成自动化。
 */
@AndroidEntryPoint
class DiagActivity : ComponentActivity() {

    @Inject lateinit var registry: SourceRegistry
    @Inject lateinit var autoSwitch: AutoSwitchSource
    @Inject lateinit var accounts: AccountStore
    @Inject lateinit var qqComment: com.puremusic.source.qq.QqComment
    @Inject lateinit var neteaseComment: com.puremusic.source.netease.NeteaseComment
    @Inject lateinit var neteaseSource: com.puremusic.source.netease.NeteaseMusicSource
    @Inject lateinit var neteaseDiscover: com.puremusic.source.netease.NeteaseDiscover
@Inject lateinit var neteaseEntity: com.puremusic.source.netease.NeteaseEntity
    @Inject lateinit var qqSource: com.puremusic.source.qq.QqMusicSource
    @Inject @Named("plain") lateinit var http: okhttp3.OkHttpClient

    private val TAG = "PureMusicDiag"
    private val out = StringBuilder()

    /** 屏幕上的 TextView：探针跑在中途也能刷新，不必等全部跑完。 */
    private var diagTv: TextView? = null

    private fun tvUpdate() {
        runOnUiThread { diagTv?.text = out.toString() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this).apply {
            textSize = 11f
            setPadding(24, 24, 24, 24)
        }
        setContentView(ScrollView(this).apply { addView(tv) })
        diagTv = tv

        lifecycleScope.launch {
            // 只测评论：adb shell am start -n com.puremusic/.DiagActivity -e only comments
            // （评论链路只关心接口本身，不必等前面十几轮搜索/取流跑完）
            // 只测点赞：adb shell am start -n com.puremusic/.DiagActivity -e only like
            when (intent.getStringExtra("only")) {
                "comments" -> runComments()
                "like" -> runLike()
                "likeprobe" -> runLikeProbe()
                "likeprobe2" -> runLikeProbe2()
                // 发评论端到端自检（发一条 → 复查 → 自动删除）：
                // adb shell am start -n com.puremusic/.DiagActivity -e only post
                "post" -> runPostProbe()
                // 手动清理某条残留评论：
                // adb shell am start -n com.puremusic/.DiagActivity -e only del -e song <id> -e cid <评论id>
                "del" -> runDelete(
                    intent.getStringExtra("song").orEmpty(),
                    intent.getStringExtra("cid").orEmpty(),
                )
                // 手动取消某条赞（清理探针残留 / 验证 unlike 真的写进去了）：
                // adb shell am start -n com.puremusic/.DiagActivity -e only unlike -e song <id> -e cid <评论id>
                "unlike" -> runUnlike(
                    intent.getStringExtra("song").orEmpty(),
                    intent.getStringExtra("cid").orEmpty(),
                )
                "neteaseassets" -> runNeteaseAssets()
                "loginprobe" -> runLoginProbe()
                // 歌手 / 专辑页层的数据探针：
                // adb shell am start -n com.puremusic/.DiagActivity -e only entity -e a 6452 -e al 34720827
                "entity" -> runEntityProbe()
                // QQ 发现层端点探针（排行榜/广场/歌手/专辑/每日推荐各候选端点横比）：
                // adb shell am start -n com.puremusic/.DiagActivity -e only qqdiscover
                "qqdiscover" -> runQqDiscoverProbe()
                // QQ 评论写接口探针（g_tk 种子校准 + 可选真实发/删/赞）：
                //   adb shell am start -n com.puremusic/.DiagActivity -e only qqwrite
                //   adb shell am start -n com.puremusic/.DiagActivity -e only qqwrite -e do 1
                "qqwrite" -> runQqWriteProbe()
                else -> runDiag()
            }
            tv.text = out.toString()
        }
    }

    private fun line(s: String) {
        out.appendLine(s)
        Log.d(TAG, s)
    }

    /**
     * 歌手页 / 专辑页数据探针。
     *
     * 这两个页面的接口都是裸 GET（`/api/v1/artist/<id>`、`/api/artist/albums/<id>`、
     * `/api/v1/album/<id>`），curl 已验证 200，但**必须真机再验一遍**
     * （电脑出口 IP 与手机不同，网易云对不同出口的风控策略不一样）。
     *
     * 用法：`adb shell am start -n com.puremusic/.DiagActivity -e only entity -e a 6452 -e al 34720827`
     */
    private suspend fun runEntityProbe() = withContext(Dispatchers.IO) {
        accounts.awaitLoaded()
        val cookie = accounts.accounts.value[com.puremusic.model.MusicSourceType.NETEASE]?.cookie
        val artistId = intent.getStringExtra("a").orEmpty().ifBlank { "6452" }
        val albumId = intent.getStringExtra("al").orEmpty().ifBlank { "34720827" }

        line("=== 歌手 $artistId ===")
        val artist = neteaseEntity.artist(artistId)
        line("歌手: ${artist?.name}  别名=${artist?.alias}")
        line("  专辑数=${artist?.albumCount} 作品数=${artist?.songCount} 简介长度=${artist?.briefDesc?.length}")
        line("  头像=${artist?.avatarUrl?.take(70)}")

        line("=== 歌手专辑 $artistId ===")
        val page = neteaseEntity.artistAlbums(artistId, limit = 5, cookie = cookie)
        line("专辑数=${page.albums.size} more=${page.more}")
        page.albums.take(5).forEach { al ->
            line("  [${al.id}] ${al.name}  ${al.size}首  ${al.publishTime}")
        }

        line("=== 专辑 $albumId ===")
        val album = neteaseEntity.album(albumId, cookie)
        line("专辑: ${album?.name}  歌手=${album?.artistName}(${album?.artistId})")
        line("  曲目数=${album?.songs?.size}  发行=${album?.publishTime}  简介长度=${album?.description?.length}")
        album?.songs?.take(5)?.forEach { s ->
            line("  ${s.id} ${s.name} - ${(s.ar ?: s.artists).orEmpty().joinToString("/") { it.name }}")
        }
        tvUpdate()
    }

    /**
     * QQ 发现层端点探针（2026-10-15）。
     *
     * 把 QQ 音乐「排行榜 / 歌单广场 / 歌手页 / 专辑页 / 每日推荐」各候选 CGI 端点
     * 一次全部打一遍，确认哪些在真机登录态下真能返回 `code:0` + 数组容器。
     * 电脑出口 IP 打 QQ 必 500003，只能用真机验。
     *
     * 用法：`adb shell am start -n com.puremusic/.DiagActivity -e only qqdiscover`
     */
    private suspend fun runQqDiscoverProbe() = withContext(Dispatchers.IO) {
        line("════════ QQ 发现层端点探针 ════════")
        val result = runCatching { qqSource.probeDiscover() }.getOrElse { e -> "探针异常: ${e.message}" }
        line(result)
        // release 包 Log.d 被优化掉，但 Log.e 在；把完整结果分块打进 logcat，
        // 这样即使看不了屏幕也能完整读到每条端点的命中/失败与响应样本。
        result.chunked(3500).forEach { android.util.Log.e("QqDiscover", it) }
        tvUpdate()
    }

    /**
     * QQ 评论**写接口**探针（2026-10-15）。
     *
     * 结论先行（写进注释免得以后重走）：
     * - 老 H5 `fcg_global_comment_h5.fcg` 的写命令（`cmd=1|3`）**对本 App 无解** ——
     *   它要的 g_tk 来自 web 侧 `skey`/`qqmusic_key`，而 QQ 互联 OAuth 扫码登录拿不到，
     *   实测 cookie 里所有密钥 + 通用常量一律 `code:1000 login check failed`。
     * - 能用的路是 musicu `music.globalComment.CommentWriteServer`（`AddComment`/`DelComment`），
     *   鉴权靠 `comm.authst=musickey`，不需要 g_tk。
     * - 点赞只有老 H5 `fcg_global_comment_praise_h5.fcg` 一条路，所以这里逐个 g_tk 候选试一遍。
     *
     * 探针会真发一条自检评论并**立刻删掉**，不留垃圾。
     *
     * 用法：`adb shell am start -n com.puremusic/.DiagActivity -e only qqwrite [-e mid <songmid>]`
     */
    private suspend fun runQqWriteProbe() = withContext(Dispatchers.IO) {
        line("════════ QQ 评论写接口探针 ════════")
        accounts.awaitLoaded()
        val acc = accounts.accounts.value[MusicSourceType.QQ]
        if (acc == null) {
            line("❌ 未登录 QQ 音乐 —— 写接口必然失败，先去扫码登录")
            return@withContext
        }
        val cookie = acc.cookie.orEmpty()
        val fields = cookie.split(";").map { it.trim().substringBefore("=") }.filter { it.isNotBlank() }
        line("cookie ${fields.size} 字段: ${fields.joinToString(",")}")

        val mid = intent.getStringExtra("mid").orEmpty().ifBlank { "0039MnYb0qxYhV" }
        val sid = runCatching { qqComment.resolveSongId(mid) }.getOrNull().orEmpty()
        line("songmid=$mid → songId=$sid")
        if (sid.isBlank()) {
            line("songId 解析失败，无法继续")
            return@withContext
        }

        // ── 一、读通道：现代 musicu 有没有数据（顺带对比两套 id）──
        line("")
        line("── 读通道 ──")
        val modern = runCatching { qqComment.page(mid, 0, 25) }.getOrNull()
        line("page: n=${modern?.comments?.size} hot=${modern?.hot?.size} total=${modern?.total} more=${modern?.more}")
        modern?.comments?.take(3)?.forEach {
            line("   ▸ id=${it.id} | ${it.userName} | ${it.content.take(30)} | 赞${it.likeCount} 我发的=${it.isSelf} 已赞=${it.liked}")
        }
        modern?.hot?.take(2)?.forEach {
            line("   ★ id=${it.id} | ${it.userName} | ${it.content.take(30)}")
        }
        val target = modern?.comments?.firstOrNull()

        // ── 二、发表 → 回读 → 删除（真实链路，跑完自动清理）──
        line("")
        line("── 发表 / 删除 ──")
        val text = "Hoshizora 自检评论 ${System.currentTimeMillis() % 100000}"
        var postedId = ""
        when (val r = runCatching { qqComment.postComment(mid, text) }.getOrNull()) {
            null -> line("❌ postComment 抛异常")
            is com.puremusic.source.netease.PostResult.Failure -> line("❌ 发表失败：${r.reason}")
            is com.puremusic.source.netease.PostResult.Success -> {
                postedId = r.commentId
                line("✅ 发表成功 AddedCmId=${r.commentId}")
                line("   回读对象=${r.comment?.let { "id=${it.id} nick=${it.userName} 我发的=${it.isSelf} :: ${it.content}" } ?: "无"}")
            }
        }
        kotlinx.coroutines.delay(1000)
        val after = runCatching { qqComment.page(mid, 0, 25) }.getOrNull()
        val mine = after?.comments?.firstOrNull { it.content.contains("Hoshizora 自检评论") }
        if (mine == null) {
            line("⚠️ 最新列表里没找到刚发的那条（id=$postedId）")
        } else {
            line("✅ 列表里找到了：id=${mine.id} 我发的=${mine.isSelf}（isSelf 为 true 才有删除按钮）")
        }
        val delId = mine?.id ?: postedId
        if (delId.isNotBlank()) {
            val okDel = runCatching { qqComment.deleteComment(mid, delId) }.getOrDefault(false)
            line("${if (okDel) "✅ 已删除自检评论" else "⚠️ 删除失败，请手动删掉：$text"}")
        }

        // ── 三、点赞：直接走产品路径（内部自动挑 g_tk × Cookie 组合），点完立刻取消抵消 ──
        line("")
        line("── 点赞（走产品路径 qqComment.setLike）──")
        if (target == null) {
            line("评论区没有可点的评论，跳过")
        } else {
            line("目标评论 id=${target.id} 原赞=${target.likeCount} 原已赞=${target.liked}")
            val on = runCatching { qqComment.setLike(target.id, true) }.getOrDefault(false)
            line("setLike(true) → $on")
            kotlinx.coroutines.delay(1200)
            val after = runCatching { qqComment.page(mid, 0, 25) }.getOrNull()
                ?.comments?.firstOrNull { it.id == target.id }
            if (after != null) {
                line("回读：赞=${after.likeCount} 已赞=${after.liked}（比原值多 1 才算真点上）")
            } else {
                line("回读：列表里没这条了")
            }
            kotlinx.coroutines.delay(600)
            val off = runCatching { qqComment.setLike(target.id, false) }.getOrDefault(false)
            line("setLike(false) → $off（取消抵消）")
        }
        line("════════ 探针结束 ════════")
        tvUpdate()
    }

    /**
     * 网易云「我的音乐资产」五接口探针。
 *
 * 一次跑完红心 / 听歌排行 / 最近播放 / 每日推荐 / 私人FM，各自打印数量与样本，
 * 用来判断哪些接口在真机网络下真的通（电脑出口 IP 常被风控，只能真机验证）。
 */
private suspend fun runNeteaseAssets() = withContext(Dispatchers.IO) {
    line("════ 网易云音乐资产 ════")
    accounts.awaitLoaded()
    val a = accounts.accounts.value[MusicSourceType.NETEASE]
    if (a == null) {
        line("网易云未登录 —— 五个接口全部需要登录态（MUSIC_U）")
        line("请先在 App「设置 → 网易云音乐 → 扫码登录」完成登录再跑本探针")
        return@withContext
    }
    line("账号 uid=${a.userId} 昵称=${a.nickname ?: "?"} cookieLen=${a.cookie.length}")

    suspend fun probe(label: String, block: suspend () -> List<com.puremusic.model.Song>) {
        val songs = runCatching { block() }.getOrElse { emptyList() }
        line("")
        line("【$label】 ${songs.size} 首")
        songs.take(3).forEach {
            line("   · ${it.title} / ${it.artist} (${it.durationMs / 1000}s) id=${it.id}")
        }
        if (songs.isEmpty()) line("   （空）")
    }

    probe("红心") { neteaseSource.likedSongs() }
    probe("每日推荐") { neteaseSource.dailyRecommend() }
    probe("私人FM") { neteaseSource.personalFm() }
    discoverProbe(a.cookie)

    // release 包日志会被裁掉，探针界面是唯一可靠出口 —— 直接把服务端原文打出来。
    line("")
    line("════ 服务端原文 ════")
    line(neteaseDirectProbe(a.cookie))
}

/**
 * 发现层探针：榜单 / 歌单广场 / 分类 / 歌单详情 / 收藏写接口。
 *
 * 这几项是 2026-10-11 新加的裸 GET 通道，必须真机确认：电脑出口 IP 常被风控，
 * 且这些接口在 eapi 上是 404（路径写法不同），只有真机能证明到底通不通。
 */
private suspend fun discoverProbe(cookie: String) = withContext(Dispatchers.IO) {
    line("")
    line("════ 发现层（榜单/歌单）════")
    val tops = runCatching { neteaseDiscover.toplists(cookie) }.getOrDefault(emptyList())
    line("榜单 ${tops.size} 张")
    tops.take(4).forEach {
        line("   · ${it.name} [${it.group}] ${it.trackCount} 首 ${it.updateFrequency}")
    }
    val cats = runCatching { neteaseDiscover.categories() }.getOrDefault(emptyList())
    line("分类标签 ${cats.size} 个：${cats.take(6).joinToString { it.name }}")
    val sq = runCatching { neteaseDiscover.squarePlaylists("华语", 3, 0, cookie) }
        .getOrDefault(com.puremusic.source.netease.NeteaseDiscover.SquarePage())
    line("歌单广场(华语) ${sq.playlists.size} 首 more=${sq.more} total=${sq.total}")
    sq.playlists.take(3).forEach { line("   · ${it.name} ${it.trackCount}首 ${it.creatorName}") }
    val first = tops.firstOrNull()
    if (first != null) {
        val full = runCatching { neteaseDiscover.playlistFull(first.id.toString(), cookie) }.getOrNull()
        line("榜单详情「${first.name}」: ${full?.songs?.size ?: 0} 首 / trackCount=${full?.trackCount}")
        line("   已收藏=${full?.subscribed} 播放=${full?.playCount} 描述=${full?.description?.take(40)}")
        full?.songs?.take(3)?.forEach {
            line("   · ${it.name} / ${(it.ar ?: it.artists)?.joinToString(", ") { a -> a.name }}")
        }
    }
}

/** 逐个接口把原始响应打出来（只截前 260 字符，够看清 code / 错误信息 / 容器字段名）。 */
private suspend fun neteaseDirectProbe(cookie: String): String = withContext(Dispatchers.IO) {
    val sb = StringBuilder()
    suspend fun hit(label: String, url: String, form: Map<String, String>, take: Int = 260) {
        val body = form.entries.joinToString("&") { (k, v) ->
            "${java.net.URLEncoder.encode(k, "UTF-8")}=${java.net.URLEncoder.encode(v, "UTF-8")}"
        }
        val req = okhttp3.Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .header("Cookie", cookie)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("Origin", "https://music.163.com")
            .header("Referer", "https://music.163.com/")
            .build()
        val txt = runCatching {
            http.newCall(req).execute().use { it.body?.string() }
        }.getOrNull()
        sb.appendLine("[$label] ${txt?.take(take) ?: "空响应"}")
    }
    val csrf = Regex("""(?:^|;\s*)__csrf=([^;]+)""").find(cookie)?.groupValues?.get(1).orEmpty()
    val uid = Regex("""(?:^|;\s*)uid=([^;]+)""").find(cookie)?.groupValues?.get(1) ?: ""

    hit("playRecord-eapi",
        "https://interface.music.163.com/eapi/v1/play/record",
        com.puremusic.source.netease.NeteaseCrypto.eapi(
            "/api/v1/play/record",
            mapOf("uid" to uid, "type" to "1", "limit" to "30", "csrf_token" to csrf),
        ),
    )
    hit("playRecord-weapi",
        "https://interface.music.163.com/weapi/v1/play/record",
        com.puremusic.source.netease.NeteaseCrypto.weapi(
            mapOf("uid" to uid, "type" to "1", "limit" to "30", "csrf_token" to csrf),
        ),
    )
    hit("recommendResource-eapi",
        "https://interface.music.163.com/eapi/recommend/resource",
        com.puremusic.source.netease.NeteaseCrypto.eapi(
            "/api/recommend/resource",
            mapOf("limit" to "1", "csrf_token" to csrf),
        ),
    )
    hit("personalFm-weapi",
        "https://interface.music.163.com/weapi/personal/fm",
        com.puremusic.source.netease.NeteaseCrypto.weapi(
            mapOf("time" to (System.currentTimeMillis() / 1000).toString(),
                "algorithm" to "10", "wechat" to "false", "csrf_token" to csrf),
        ),
    )
    // ↓ 2026-10-11 定论记录（勿再重复试这几条）：
    // - 每日推荐：`/api/discovery/recommend/songs`（eapi）200 + **顶层 `recommend` 数组** ✅
    //   老路径 `/api/recommend/resource` 已 404。
    // - 私人 FM：`/api/personal/fm` weapi/eapi **均 404**，已下线，UI 隐藏该入口。
    // - 歌单评论：带登录态打 `/api/v1/resource/comments/R_PL_0_<id>` 返回 code=200 但
    //   comments 空、total=0（对「有 23 条评论」的歌单同样如此）→ 网易云不对非官方
    //   客户端开放歌单评论；歌曲评论（R_SO_4_）照常可用。
    hit("discoveryRecommend-raw",
        "https://interface.music.163.com/eapi/discovery/recommend/songs",
        com.puremusic.source.netease.NeteaseCrypto.eapi(
            "/api/discovery/recommend/songs",
            mapOf("limit" to "30", "csrf_token" to csrf),
        ),
        700,
    )
    sb.toString()
}

private suspend fun runDiag() = withContext(Dispatchers.IO) {
        line("════════ 自检开始 ════════")

        // ---------- 1. 登录态 ----------
        line("\n【1】登录态")
        accounts.awaitLoaded()
        val map = accounts.accounts.value
        for (t in listOf(MusicSourceType.QQ, MusicSourceType.NETEASE)) {
            val a = map[t]
            if (a == null) {
                line("  ${t.id}: 未登录")
            } else {
                val ck = a.cookie.orEmpty()
                val keys = ck.split(";").map { it.trim().substringBefore("=") }
                val hit = keys.filter {
                    it.contains("MUSIC_U") || it.contains("musickey") || it == "uin"
                }
                line("  ${t.id}: ${a.nickname ?: "?"} uid=${a.userId} cookieLen=${ck.length} key=$hit")
            }
        }

        // ---------- 2. 各源搜索 ----------
        for (kw in listOf("稻香", "青花瓷")) {
            line("\n【2】搜索「$kw」")
            for (srcId in listOf("qq", "netease", "kuwo")) {
                val src = registry.byId(srcId) ?: continue
                val r = runCatching { src.search(kw, 0, 20) }
                val songs = r.getOrNull()
                if (songs == null) {
                    line("  $srcId: 异常 ${r.exceptionOrNull()?.message}")
                    continue
                }
                line("  $srcId: ${songs.size} 条")
                songs.take(5).forEach { s ->
                    line("     ${s.id} | ${s.title.take(26)} | ${s.artist.take(18)} | ${s.durationMs / 1000}s")
                }
            }
        }

        // ---------- 3. 取流（各源首条） ----------
        line("\n【3】取流")
        for (kw in listOf("孤勇者", "周杰伦 晴天")) {
            for (srcId in listOf("qq", "netease", "kuwo")) {
                val src = registry.byId(srcId) ?: continue
                val songs = runCatching { src.search(kw, 0, 20) }.getOrNull().orEmpty()
                if (songs.isEmpty()) {
                    line("  $srcId/$kw: 无搜索结果")
                    continue
                }
                val url = runCatching { src.getSongUrl(songs.first()) }.getOrNull()
                line("  $srcId/$kw [${songs.first().title.take(20)}] → ${if (url.isNullOrBlank()) "空" else url.take(90)}")
            }
        }

        // ---------- 4. 端到端：点播第一条 + 失败自动换源 ----------
        line("\n【4】端到端（点播 → 失败换源）")
        for (kw in listOf("孤勇者", "起风了", "晴天")) {
            line("  ── $kw ──")
            val agg = runCatching { registry.searchAll(kw, 0, 20) }.getOrNull()
            if (agg == null) {
                line("     聚合搜索异常")
                continue
            }
            // 与线上 SearchViewModel 同链路：先做相关性过滤，再并组
            val relevance = com.puremusic.source.match.SearchRelevance.partition(kw, agg.songs)
            val groups = SongMatcher.groupSongs(relevance.strong)
            if (groups.isEmpty()) {
                line("     强相关 0 组（搜索过滤把结果全砍了！）weak=${relevance.weak.size}")
                continue
            }
            line("     强相关 ${groups.size} 组 / 其它 ${relevance.weak.size} 条")
            // 测前三组，覆盖「用户实际会点的几首」
            for (g in groups.take(3)) {
                val target = g.primary
                line("     ▸ ${target.source.id} ${target.title.take(22)} - ${target.artist.take(16)} " +
                    "(${target.durationMs / 1000}s) 并组${g.matches.size}源")
                val first = runCatching { registry.byId(target.source.id)?.getSongUrl(target) }.getOrNull()
                if (!first.isNullOrBlank()) {
                    line("       原源取流 OK → ${first.take(70)}")
                    continue
                }
                line("       原源取流失败，尝试换源…")
                val sw = runCatching {
                    autoSwitch.trySwitch(target, setOf(target.source.id))
                }.getOrNull()
                if (sw == null) {
                    line("       ✗ 换源失败")
                } else {
                    line("       ✓ 换源 → ${sw.song.source.id} ${sw.song.title.take(20)} url=${sw.url.take(70)}")
                }
            }
        }

        // ---------- 5. QQ 专项：看 vkey 到底返回什么 ----------
        line("\n【5】QQ 取流专项")
        val qq = registry.byId("qq")
        for (kw in listOf("孤勇者", "晴天", "起风了")) {
            val qqSongs = runCatching { qq?.search(kw, 0, 10) }.getOrNull().orEmpty()
            if (qqSongs.isEmpty()) {
                line("  QQ 搜索「$kw」无结果")
                continue
            }
            line("  ── $kw ──")
            // 逐条试：区分「全部失败（接口/凭证问题）」与「个别失败（版权/会员限制）」
            for (s in qqSongs.take(5)) {
                // QQ 对高频请求有风控（req_1.code=2001 / 搜索突然 0 条），必须节流
                kotlinx.coroutines.delay(1200)
                val url = runCatching { qq?.getSongUrl(s) }.getOrNull()
                line("     ${s.title.take(18)} - ${s.artist.take(12)} ${s.durationMs / 1000}s → " +
                    if (url.isNullOrBlank()) "空" else "OK ${url.take(45)}")
            }
        }

        // ---------- 6. 覆盖率：真正回答「有多少歌能听」 ----------
        line("\n【6】覆盖率实测")
        coverage()

        // ---------- 7. QQ 搜索 CGI 参数矩阵：找出真正能出结果的那组 ----------
        line("\n【7】QQ 搜索 CGI 参数矩阵")
        probeQqSearch()

        line("\n════════ 自检结束 ════════")
    }

    /**
     * 覆盖率实测：对一批热门曲目逐个走「搜索 → 同曲匹配 → 取流」，统计每个源能播多少。
     *
     * 这是唯一能回答「这播放器到底能不能听歌」的指标 —— 单看接口是否 200 没意义，
     * 因为会员/版权限制会让大量曲目在取流阶段失败。
     */
    private suspend fun coverage() = withContext(Dispatchers.IO) {
        val keywords = listOf(
            "孤勇者", "起风了", "晴天", "稻香", "演员", "成都",
            "平凡之路", "海阔天空", "小幸运", "青花瓷", "罗刹海市", "悬溺",
        )
        val failList = mutableListOf<String>()
        var okCount = 0

        for (kw in keywords) {
            // 完全复刻用户路径：聚合搜索 → 相关性分层 → 跨源一致性排序 → 取第一条 → 取流
            val agg = runCatching { registry.searchAll(kw, 0, 20) }.getOrNull()
            if (agg == null) {
                failList += "$kw(搜索异常)"
                continue
            }
            val strong = com.puremusic.source.match.SearchRelevance.partition(kw, agg.songs).strong
            val ranked = SongMatcher.rankByConsensus(strong)
            // 与 SearchViewModel 完全一致：并组后按 SOURCE_ORDER 选主展示曲
            val group = SongMatcher
                .groupSongs(ranked, listOf("netease", "kuwo", "qq", "bilibili"))
                .firstOrNull()
            val best = group?.primary
            if (best == null) {
                failList += "$kw(无强相关)"
                continue
            }
            kotlinx.coroutines.delay(250)
            val url = runCatching { registry.byId(best.source.id)?.getSongUrl(best) }.getOrNull()
            if (!url.isNullOrBlank()) {
                okCount++
                line("  ${kw.padEnd(6)} ✓ ${best.title.take(14)}/${best.artist.take(10)} [${best.source.id}]")
                continue
            }
            // 原源失败 → 自动换源（用户实际体验到的兜底）
            val sw = runCatching { autoSwitch.trySwitch(best, setOf(best.source.id)) }.getOrNull()
            if (sw == null) {
                failList += "$kw(换源也失败)"
                line("  ${kw.padEnd(6)} ✗ ${best.title.take(14)}/${best.artist.take(10)} [${best.source.id}]")
            } else {
                okCount++
                line("  ${kw.padEnd(6)} ~ ${best.title.take(14)}/${best.artist.take(10)} " +
                    "[${best.source.id}→${sw.song.source.id}]")
            }
        }
        val total = keywords.size
        line("  ── 结果 ──")
        line("     可播放: $okCount/$total")
        line("     失败: ${failList.ifEmpty { listOf("无") }}")
    }

    /**
     * QQ 评论链路专项。
     *
     * 两件事必须证实：① `songmid → 数字 songid` 三条通道哪条真能出结果；
     * ② 评论接口返回的**真实字段名**（老 H5 接口的字段在不同版本会漂移，
     * 解析全靠字段名，所以这里直接把原始响应前 400 字符打出来对照）。
     */
    private suspend fun runComments() = withContext(Dispatchers.IO) {
        line("════════ 评论自检 ════════")
        val mids = listOf("002bFWJI2fdD54", "002jrZAP3E7Uja", "0039MZ0k3HGnHz")
        for (mid in mids) {
            kotlinx.coroutines.delay(1200)
            val sid = runCatching { qqComment.resolveSongId(mid) }.getOrNull()
            line("\n  songmid=$mid → songId=$sid")
            if (sid.isNullOrBlank()) continue
            val url = "https://c.y.qq.com/base/fcgi-bin/fcg_global_comment_h5.fcg" +
                "?g_tk=5381&loginUin=0&hostUin=0&format=json&inCharset=utf8&outCharset=utf-8" +
                "&notice=0&platform=yqq.json&needNewCode=0&cid=205360772&reqtype=2&biztype=1" +
                "&topid=$sid&cmd=8&needmusiccrit=0&pagenum=0&pagesize=25&lasthotcommentid=&domain=qq.com" +
                "&ct=24&cv=101010"
            val raw = runCatching {
                http.newCall(
                    okhttp3.Request.Builder().url(url)
                        .header("User-Agent", UA).header("Referer", "https://y.qq.com/").build()
                ).execute().use { if (it.isSuccessful) it.body?.string().orEmpty() else "" }
            }.getOrDefault("")
            line("  raw len=${raw.length} :: ${raw.take(200)}")
            // 关键：直接把 comment 段的 key 与第一条评论的完整 JSON 打出来 ——
            // QQ 这个老接口的字段名不同版本会漂移，解析全靠字段名对齐。
            val rootJson = runCatching {
                com.google.gson.JsonParser.parseString(raw).asJsonObject
            }.getOrNull()
            val commentObj = runCatching { rootJson?.getAsJsonObject("comment") }.getOrNull()
            line("  root keys=${rootJson?.keySet()?.joinToString(",")}")
            line("  comment keys=${commentObj?.keySet()?.joinToString(",")}")
            val first = runCatching {
                commentObj?.getAsJsonArray("commentlist")?.get(0)?.toString()
            }.getOrNull()
            line("  first=${first?.take(700)}")
            line("  commenttotal=${commentObj?.get("commenttotal")} " +
                "hot_total=${runCatching { rootJson?.getAsJsonObject("hot_comment")?.get("commenttotal") }.getOrNull()} " +
                "hot_n=${runCatching { rootJson?.getAsJsonObject("hot_comment")?.getAsJsonArray("commentlist")?.size() }.getOrNull()}")
            // 翻页是否真的翻得动：0/1/2 页的首条 commentid 应当互不相同（否则要用 lastscore 游标）
            for (pn in 0..2) {
                kotlinx.coroutines.delay(300)
                val p = runCatching { qqComment.page(mid, pn, 25) }.getOrNull()
                line("  page$pn n=${p?.comments?.size ?: -1} total=${p?.total} more=${p?.more} " +
                    "head=${p?.comments?.firstOrNull()?.id?.take(28)}")
            }
            val p0 = runCatching { qqComment.page(mid, 0, 25) }.getOrNull()
            line("  parsed hot=${p0?.hot?.size ?: -1} n=${p0?.comments?.size ?: -1} total=${p0?.total}")
            p0?.comments?.take(4)?.forEach {
                line("     ▸ ${it.userName} | ${it.content.replace("\n", " ").take(40)} | " +
                    "赞${it.likeCount} | ${it.timeText} | vip=${it.vipType} | 回复引用=${it.beReplied.size}")
            }
            p0?.hot?.take(2)?.forEach {
                line("     ★ ${it.userName} | ${it.content.replace("\n", " ").take(34)} | 赞${it.likeCount}")
            }
        }
        line("\n════════ 评论自检结束 ════════")
    }

    /** 取消指定评论的赞：探针/自检留下的测试点赞靠它清掉，也用来验证 unlike 真的落库。 */
    private suspend fun runUnlike(songId: String, cid: String) = withContext(Dispatchers.IO) {
        line("════════ 取消点赞 ════════")
        if (songId.isBlank() || cid.isBlank()) {
            line("缺参数：-e song <歌曲id> -e cid <评论id>")
            return@withContext
        }
        accounts.awaitLoaded()
        val ok = runCatching { neteaseComment.setLike(songId, cid, false) }.getOrDefault(false)
        line("unlike song=$songId cid=$cid → $ok")
        kotlinx.coroutines.delay(900)
        val p = runCatching { neteaseComment.resourcePage(songId, 0, 20) }.getOrNull()
        val c = p?.hot?.firstOrNull { it.id == cid } ?: p?.comments?.firstOrNull { it.id == cid }
        line("复查 liked=${c?.liked} 赞=${c?.likeCount}")
    }

    /**
     * 发表评论端到端自检：发一条 → 等 1.2s 重新拉评论区找它 → 立刻删掉。
     *
     * 存在的意义：发评论和点赞一样是**写接口**，端点/参数/身份任何一环不对，
     * 表现都是"没发出去"，而电脑上打网易云一律 -460/-462 根本看不出原因。
     * 这条链跑通就等于证明 `/weapi/comment/add` + 安卓身份 + csrf 三件事都对。
     * 最后一定删除，账号里不留垃圾评论。
     */
    private suspend fun runPostProbe() = withContext(Dispatchers.IO) {
        line("════════ 发表评论自检 ════════")
        accounts.awaitLoaded()
        if (accounts.currentNeteaseCookie.isNullOrBlank()) {
            line("未登录网易云，先去登录页登录再跑")
            return@withContext
        }
        val songs = runCatching { neteaseSource.search("晴天 周杰伦", 0, 10) }.getOrNull().orEmpty()
        val songId = songs.firstOrNull()?.id.orEmpty()
        if (songId.isBlank()) {
            line("搜不到测试用歌曲")
            return@withContext
        }
        line("目标歌曲 song=$songId")
        val text = "Hoshizora 自检评论 ${System.currentTimeMillis() % 100000}"
        val result = neteaseComment.postComment(songId, text)
        when (result) {
            is com.puremusic.source.netease.PostResult.Failure -> {
                line("❌ 发表失败：${result.reason}")
                return@withContext
            }
            is com.puremusic.source.netease.PostResult.Success -> {
                line("✅ 接口 200，回 commentId=${result.commentId}")
                line("   服务端返回对象=${result.comment?.let { "nick=${it.userName} content=${it.content}" } ?: "无"}")
            }
        }
        kotlinx.coroutines.delay(1200)
        // 注意必须走 resourcePage（内部会补 R_SO_4_ 前缀），直接 page(songId) 会拼出裸 id 读不到东西
        val page = runCatching { neteaseComment.resourcePage(songId, 0, 20) }.getOrNull()
        val mine = page?.comments?.firstOrNull { it.content.contains("Hoshizora 自检评论") }
        if (mine == null) {
            line("❌ 复查失败：最新评论里找不到刚发的那条（total=${page?.total} n=${page?.comments?.size}）")
            return@withContext
        }
        line("✅ 复查成功：${mine.userName}「${mine.content}」id=${mine.id} uid=${mine.userId}")
        val uid = accounts.accounts.value[MusicSourceType.NETEASE]?.userId?.toLongOrNull() ?: 0L
        line("   本机登录 uid=$uid  与评论作者一致=${mine.userId == uid}")
        val del = neteaseComment.deleteComment(songId, mine.id)
        line("${if (del) "✅ 已清理该评论" else "⚠️ 删除失败，请手动去网易云删掉：${mine.content}"}")
    }

    /** 手动删除指定评论（清理自检残留）：`-e only del -e song <歌曲id> -e cid <评论id>`。 */
    private suspend fun runDelete(songId: String, cid: String) = withContext(Dispatchers.IO) {
        line("════════ 手动删除评论 ════════")
        if (songId.isBlank() || cid.isBlank()) {
            line("缺参数：-e song <歌曲id> -e cid <评论id>")
            return@withContext
        }
        accounts.awaitLoaded()
        val ok = runCatching { neteaseComment.deleteComment(songId, cid) }.getOrDefault(false)
        line("删除 song=$songId cid=$cid → ${if (ok) "成功" else "失败"}")
    }

    /**
     * 点赞端点探针：**点赞是写接口，端点/参数一变就全废**，而电脑上（代理出口 IP）
     * 打网易云一律 -460/-462，看不出是端点错还是风控。所以把候选组合一次全打一遍，
     * 把每个响应原样打到 logcat，哪个返回 `code:200` 就用哪个。
     *
     * 只试 `like`，最后统一 unlike 一次抵消，不在账号里留下垃圾点赞。
     */
    private suspend fun runLikeProbe() = withContext(Dispatchers.IO) {
        line("════════ 点赞端点探针 ════════")
        accounts.awaitLoaded()
        val raw = accounts.currentNeteaseCookie.orEmpty()
        val csrf = Regex("(?:^|;)\\s*__csrf=([^;]*)").find(raw)?.groupValues?.get(1).orEmpty()
        val cookie = if (raw.contains("os=")) raw else "$raw; os=pc; appver=8.9.70"
        line("cookieLen=${raw.length} csrf=${if (csrf.isBlank()) "【缺失】" else csrf.take(10) + "…"}")

        val songs = runCatching { neteaseSource.search("晴天 周杰伦", 0, 10) }.getOrNull().orEmpty()
        val songId = songs.firstOrNull()?.id.orEmpty()
        val page = runCatching { neteaseComment.page(songId, 0, 20) }.getOrNull()
        val cid = (page?.hot?.firstOrNull() ?: page?.comments?.firstOrNull())?.id.orEmpty()
        line("song=$songId cid=$cid")
        if (songId.isBlank() || cid.isBlank()) {
            line("没有可用的歌/评论，结束")
            return@withContext
        }
        val tid = "R_SO_4_$songId"

        // 每种组合：名称 → URL、签名路径、body、加密方式
        val cases = listOf(
            "1 weapi /weapi/v1/comment/like +csrf" to Probe(
                "https://music.163.com/weapi/v1/comment/like", null,
                mapOf("threadId" to tid, "commentId" to cid, "csrf_token" to csrf), true,
            ),
            "2 weapi /weapi/v1/comment/like 无csrf" to Probe(
                "https://music.163.com/weapi/v1/comment/like", null,
                mapOf("threadId" to tid, "commentId" to cid), true,
            ),
            "3 weapi /api/v1/comment/like +csrf" to Probe(
                "https://music.163.com/api/v1/comment/like", null,
                mapOf("threadId" to tid, "commentId" to cid, "csrf_token" to csrf), true,
            ),
            "4 eapi interface /eapi/v1/comment/like +csrf" to Probe(
                "https://interface.music.163.com/eapi/v1/comment/like", "/api/v1/comment/like",
                mapOf("threadId" to tid, "commentId" to cid, "csrf_token" to csrf), false,
            ),
            "5 eapi interface3 /eapi/v1/comment/like +csrf" to Probe(
                "https://interface3.music.163.com/eapi/v1/comment/like", "/api/v1/comment/like",
                mapOf("threadId" to tid, "commentId" to cid, "csrf_token" to csrf), false,
            ),
            "6 weapi /weapi/v1/comment/like +csrf 数字commentId" to Probe(
                "https://music.163.com/weapi/v1/comment/like", null,
                mapOf("threadId" to tid, "commentId" to cid.toLongOrNull(), "csrf_token" to csrf), true,
            ),
            "7 weapi /weapi/comment/like +csrf" to Probe(
                "https://music.163.com/weapi/comment/like", null,
                mapOf("threadId" to tid, "commentId" to cid, "csrf_token" to csrf), true,
            ),
            "8 eapi /eapi/comment/like +csrf" to Probe(
                "https://interface.music.163.com/eapi/comment/like", "/api/comment/like",
                mapOf("threadId" to tid, "commentId" to cid, "csrf_token" to csrf), false,
            ),
        )

        for ((name, p) in cases) {
            kotlinx.coroutines.delay(700)
            val form = if (p.weapi) {
                com.puremusic.source.netease.NeteaseCrypto.weapi(p.body)
            } else {
                com.puremusic.source.netease.NeteaseCrypto.eapi(p.signPath!!, p.body)
            }
            val text = runCatching { postLike(p.url, form, cookie) }.getOrDefault("")
            val code = runCatching {
                com.google.gson.JsonParser.parseString(text).asJsonObject.get("code")?.asInt
            }.getOrNull()
            line("  $name → code=$code")
            // 风控响应（250）里往往塞着验证页地址/ verifyToken，必须完整打出来看
            if (code != 200) {
                val obj = runCatching { com.google.gson.JsonParser.parseString(text).asJsonObject }.getOrNull()
                line("      keys=${obj?.keySet()?.joinToString(",")} msg=${obj?.get("msg") ?: obj?.get("message")}")
                line("      data=${obj?.get("data")?.toString()?.take(500)}")
            }
        }
        // 抵消：无论上面谁成功过，取消一次
        runCatching {
            neteaseComment.setLike(songId, cid, false)
        }
        line("════════ 探针结束 ════════")
    }

    /** 探针专用：原样发表单 POST，不做任何解析（要看的是原始响应）。 */
    private fun postLike(url: String, form: Map<String, String>, cookie: String, ua: String = UA): String {
        val encoded = form.entries.joinToString("&") { (k, v) ->
            "${java.net.URLEncoder.encode(k, "UTF-8")}=${java.net.URLEncoder.encode(v, "UTF-8")}"
        }
        val req = okhttp3.Request.Builder()
            .url(url)
            .post(encoded.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .header("User-Agent", ua)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Referer", "https://music.163.com/")
            .header("Origin", "https://music.163.com")
            .header("Cookie", cookie)
            .build()
        return http.newCall(req).execute().use { if (it.isSuccessful) it.body?.string().orEmpty() else "" }
    }

    private data class Probe(
        val url: String,
        val signPath: String?,
        val body: Map<String, Any?>,
        val weapi: Boolean,
        val ua: String = UA,
        val cookie: String = "",
        val get: Boolean = false,
    )

    /** 第二轮：换 UA / 换端点 / 换请求方式，看 250（设备风控）是不是无解。 */
    private suspend fun runLikeProbe2() = withContext(Dispatchers.IO) {
        line("════════ 点赞探针·第二轮 ════════")
        accounts.awaitLoaded()
        val raw = accounts.currentNeteaseCookie.orEmpty()
        val csrf = Regex("(?:^|;)\\s*__csrf=([^;]*)").find(raw)?.groupValues?.get(1).orEmpty()
        val cookiePc = if (raw.contains("os=")) raw else "$raw; os=pc; appver=8.9.70"
        val cookieAndroid = raw.split(";").filter { !it.trim().startsWith("os=") }
            .joinToString(";") + "; os=android; appver=8.9.70"

        // 两首不同的歌：确认 250 是"账号/设备级"还是"某首歌被限制"
        val targets = listOf("晴天 周杰伦", "稻香").mapNotNull { kw ->
            val s = runCatching { neteaseSource.search(kw, 0, 8) }.getOrNull()?.firstOrNull()
            val id = s?.id ?: return@mapNotNull null
            val p = runCatching { neteaseComment.page(id, 0, 20) }.getOrNull()
            val c = (p?.hot?.firstOrNull() ?: p?.comments?.firstOrNull()) ?: return@mapNotNull null
            Triple(id, c.id, "$kw→${s.title}")
        }
        line("目标数=${targets.size} csrf=${csrf.take(8)}…")

        for ((songId, cid, label) in targets) {
            line("\n【$label】 song=$songId cid=$cid")
            val tid = "R_SO_4_$songId"
            val cases = listOf(
                "A weapi +csrf (Chrome UA)" to Probe(
                    "https://music.163.com/weapi/v1/comment/like", null,
                    mapOf("threadId" to tid, "commentId" to cid, "csrf_token" to csrf), true,
                ),
                "B weapi +csrf (官方PC UA)" to Probe(
                    "https://music.163.com/weapi/v1/comment/like", null,
                    mapOf("threadId" to tid, "commentId" to cid, "csrf_token" to csrf), true,
                    ua = "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/35.0.1916.157 Safari/537.36 NeteaseMusicDesktop 2.9.0.199",
                ),
                "C weapi +csrf (安卓官方UA/os=android)" to Probe(
                    "https://music.163.com/weapi/v1/comment/like", null,
                    mapOf("threadId" to tid, "commentId" to cid, "csrf_token" to csrf), true,
                    ua = "Mozilla/5.0 (Linux; Android 12; 25060RK16C) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 NeteaseMusic",
                    cookie = cookieAndroid,
                ),
                "D weapi /weapi/v1/comment/like/v1" to Probe(
                    "https://music.163.com/weapi/v1/comment/like/v1", null,
                    mapOf("threadId" to tid, "commentId" to cid, "csrf_token" to csrf), true,
                ),
                "E weapi +csrf body带os/appver" to Probe(
                    "https://music.163.com/weapi/v1/comment/like", null,
                    mapOf(
                        "threadId" to tid, "commentId" to cid,
                        "csrf_token" to csrf, "os" to "pc", "appver" to "8.9.70",
                    ), true,
                ),
                "F GET /api/v1/comment/like?query" to Probe(
                    "https://music.163.com/api/v1/comment/like?threadId=$tid&commentId=$cid" +
                        "&csrf_token=$csrf", null, emptyMap(), true, get = true,
                ),
            )
            for ((name, p) in cases) {
                kotlinx.coroutines.delay(600)
                val ck = p.cookie.ifBlank { cookiePc }
                val text = if (p.get) {
                    runCatching {
                        http.newCall(
                            okhttp3.Request.Builder().url(p.url)
                                .header("User-Agent", p.ua).header("Referer", "https://music.163.com/")
                                .header("Cookie", ck).build()
                        ).execute().use { if (it.isSuccessful) it.body?.string().orEmpty() else "" }
                    }.getOrDefault("")
                } else {
                    val form = com.puremusic.source.netease.NeteaseCrypto.weapi(p.body)
                    runCatching { postLike(p.url, form, ck, p.ua) }.getOrDefault("")
                }
                val obj = runCatching {
                    com.google.gson.JsonParser.parseString(text).asJsonObject
                }.getOrNull()
                val code = runCatching { obj?.get("code")?.asInt }.getOrNull()
                line("  $name → code=$code ${obj?.get("msg") ?: obj?.get("message") ?: ""}")
            }
            // 抵消：不管成功没成功，取消一次
            runCatching { neteaseComment.setLike(songId, cid, false) }
        }
        line("\n════════ 第二轮结束 ════════")
    }

    /**
     * 点赞链路专项：写接口**必须真机验证**，电脑上（代理出口 IP）打网易云一律 -460/-462，
     * 成功/失败都跟代码无关。
     *
     * 流程：取一首歌的第一条评论 → 点赞 → 重新拉一次看 `liked` 是否真的变 true → 取消点赞。
     * 首尾抵消，不会在用户账号里留下垃圾点赞。
     */
    private suspend fun runLike() = withContext(Dispatchers.IO) {
        line("════════ 点赞自检 ════════")
        accounts.awaitLoaded()
        val cookie = accounts.currentNeteaseCookie
        line("登录态=${if (cookie.isNullOrBlank()) "未登录（点赞必失败）" else "cookieLen=${cookie.length}"}")

        val songs = runCatching { neteaseSource.search("晴天 周杰伦", 0, 10) }.getOrNull().orEmpty()
        val songId = songs.firstOrNull()?.id
        line("歌曲=${songs.firstOrNull()?.title} id=$songId")
        if (songId.isNullOrBlank()) {
            line("搜不到歌，结束")
            return@withContext
        }

        val page = runCatching { neteaseComment.page(songId, 0, 20) }.getOrNull()
        val target = page?.hot?.firstOrNull() ?: page?.comments?.firstOrNull()
        line("评论 total=${page?.total} hot=${page?.hot?.size} n=${page?.comments?.size}")
        if (target == null) {
            line("没有评论可点，结束")
            return@withContext
        }
        line("目标评论 id=${target.id} 赞=${target.likeCount} liked=${target.liked} " +
            "by=${target.userName} :: ${target.content.replace("\n", " ").take(30)}")

        val ok = runCatching { neteaseComment.setLike(songId, target.id, true) }.getOrDefault(false)
        line("点赞(setLike true) → $ok")

        // 复查：重新拉一次，看服务端到底有没有记住（本地假装点上不算数）
        kotlinx.coroutines.delay(900)
        val again = runCatching { neteaseComment.page(songId, 0, 20) }.getOrNull()
        val persisted = again?.hot?.firstOrNull { it.id == target.id }
            ?: again?.comments?.firstOrNull { it.id == target.id }
        line("复查 liked=${persisted?.liked} 赞=${persisted?.likeCount}（应比上面 +1）")

        // 取消，恢复现场
        kotlinx.coroutines.delay(600)
        val off = runCatching { neteaseComment.setLike(songId, target.id, false) }.getOrDefault(false)
        line("取消点赞(setLike false) → $off")
        line("════════ 点赞自检结束 ════════")
    }

    /** 直接发原始请求试探多组搜索参数，命中即打印（免得每次改主流程都要重编译）。 */
    private suspend fun probeQqSearch() = withContext(Dispatchers.IO) {
        val account = accounts.accounts.value[MusicSourceType.QQ]
        val cookie = account?.cookie.orEmpty()
        val uin = Regex("(?:^|;)\\s*ptui_loginuin=([^;]*)").find(cookie)?.groupValues?.get(1)
            ?: account?.userId ?: "0"
        val authst = listOf("qqmusic_key", "qm_keyst", "musickey").map { name ->
            Regex("(?:^|;)\\s*$name=([^;]*)").find(cookie)?.groupValues?.get(1).orEmpty()
        }.firstOrNull { it.isNotBlank() }.orEmpty()
        line("  uin=$uin authstLen=${authst.length} cookieLen=${cookie.length}")

        val cases = listOf(
            "A:Desktop裸参数" to """{"req_1":{"module":"music.search.SearchCgiService","method":"DoSearchForQQMusicDesktop","param":{"query":"晴天","page_num":1,"num_per_page":20,"search_type":0}},"comm":{"uin":"$uin","format":"json","ct":24,"cv":0,"authst":"$authst","tmeLoginType":2}}""",
            "B:Desktop+grp" to """{"req_1":{"module":"music.search.SearchCgiService","method":"DoSearchForQQMusicDesktop","param":{"query":"晴天","page_num":1,"num_per_page":20,"search_type":0,"grp":1,"nqc_flag":0,"remoteplace":"txt.yqq.song"}},"comm":{"uin":"$uin","format":"json","ct":24,"cv":0,"authst":"$authst","tmeLoginType":2}}""",
            "C:Mobile方法" to """{"req_1":{"module":"music.search.SearchCgiService","method":"DoSearchForQQMusicMobile","param":{"query":"晴天","page_num":1,"num_per_page":20,"search_type":0}},"comm":{"uin":"$uin","format":"json","ct":24,"cv":0,"authst":"$authst","tmeLoginType":2}}""",
            "D:不带authst" to """{"req_1":{"module":"music.search.SearchCgiService","method":"DoSearchForQQMusicDesktop","param":{"query":"晴天","page_num":1,"num_per_page":20,"search_type":0}},"comm":{"uin":"$uin","format":"json","ct":24,"cv":0}}""",
            "E:ct11cv11" to """{"req_1":{"module":"music.search.SearchCgiService","method":"DoSearchForQQMusicDesktop","param":{"query":"晴天","page_num":1,"num_per_page":20,"search_type":0}},"comm":{"uin":"$uin","format":"json","ct":11,"cv":11,"authst":"$authst","tmeLoginType":2}}""",
            "F:uin0匿名" to """{"req_1":{"module":"music.search.SearchCgiService","method":"DoSearchForQQMusicDesktop","param":{"query":"晴天","page_num":1,"num_per_page":20,"search_type":0}},"comm":{"uin":"0","format":"json","ct":24,"cv":0}}""",
        )
        for ((name, json) in cases) {
            kotlinx.coroutines.delay(1200)
            val text = runCatching {
                http.newCall(
                    okhttp3.Request.Builder()
                        .url("https://u.y.qq.com/cgi-bin/musicu.fcg")
                        .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .header("Referer", "https://y.qq.com/")
                        .header("User-Agent", UA)
                        .header("Cookie", cookie)
                        .build()
                ).execute().body?.string()
            }.getOrNull()
            if (text.isNullOrBlank()) {
                line("  $name: 无响应")
                continue
            }
            val sum = Regex("\"sum\"\\s*:\\s*(\\d+)").find(text)?.groupValues?.get(1) ?: "?"
            val first = Regex("\"name\"\\s*:\\s*\"([^\"]{1,24})\"").find(text)?.groupValues?.get(1) ?: ""
            val songN = Regex("\"song\"\\s*:\\s*\\{\\s*\"list\"\\s*:\\s*\\[").find(text) != null
            line("  $name: sum=$sum songList=$songN first=$first :: ${text.take(110)}")
        }
    }

    /**
     * 登录风控对照探针（2026-10-12）。
     *
     * 要回答的问题：v1.3.0 把手机号登录当"用不了"删掉了，实测被 -460/-462 拦。
     * 同类项目 NCMusic（开源）的写法与我们完全不同：
     *   - 域名 裸 music.163.com（我们也是裸域）
     *   - UA   桌面 Chrome（一样）
     *   - csrf_token 传空串（我们传真值）
     *   - Cookie 不额外伪装 os/appver/NMTID（我们的写接口强制 os=android）
     * 到底是哪个差异决定成败？本探针在同一台设备、同一网络下逐项对照。
     *
     * 判定标准很明确：
     *   -460/-462/250 = 动态风控拦截（写操作被拒）
     *   -46x之外的非200 业务码（如 502/505）= 通道通了，只是参数不对 → **能救**
     *   200 = 真成功
     *
     * 注意：故意用假手机号（170 段），既不打扰真人，也不会误发短信；
     * 目的是看服务端在哪一层把我们拦下，而不是真的登录。
     */
    private suspend fun runLoginProbe() = withContext(Dispatchers.IO) {
        line("════ 网易云登录风控对照探针 ════")
        accounts.awaitLoaded()
        val real = accounts.currentNeteaseCookie.orEmpty()
        if (real.isBlank()) {
            line("⚠️ 未登录 —— 无 csrf 可用，本次只测匿名通道")
        }
        val csrf = Regex("""(?:^|;\s*)__csrf=([^;]+)""").find(real)?.groupValues?.get(1).orEmpty()
        val uid = Regex("""(?:^|;\s*)uid=([^;]+)""").find(real)?.groupValues?.get(1).orEmpty()
        line("uid=$uid  csrf=${if (csrf.isBlank()) "(无)" else csrf.take(6) + "…"}")

        // ---- Cookie 变体：A=我们的写法（os=android），B=NCMusic 写法（不伪装）----
        val ckA = androidStyle(real)      // 加 os=android
        val ckB = bareStyle(real)          // 剥掉 os/appver/NMTID，只留服务端下发字段

        line("")
        line("── A=本项目写法 (os=android)  vs  B=NCMusic 写法 (不伪装) ──")

        val fakePhone = "17012345678"
        // 参数名严格照NCMusic 的实现（第一轮我写错成 phone/countrycode，服务端返回
        // 400「参数错误」，差点误判成被风控 —— 记录在此，别再犯）。
        val pwdBody = mapOf(
            "phone" to fakePhone,
            "password" to "x^9zQw!kLm2",     // 假密码：只看是否被风控，不看是否登入
            "countrycode" to "86",
            "rememberLogin" to "true",
            "csrf_token" to csrf.ifBlank { "" },
        )

        // ---- 正交对照：把「os 字段」与「UA」两个变量拆开，否则分不清是谁决定成败 ----
        line("")
        line("── ① 密码登录 weapi：os字段 × UA 四格正交 ──")
        hitLogin("os=android + 安卓UA ", "https://music.163.com/weapi/login/cellphone",
            "weapi", pwdBody, cookieWithOs(real, "android"), ua = ANDROID_UA)
        hitLogin("os=android + PC  UA ", "https://music.163.com/weapi/login/cellphone",
            "weapi", pwdBody, cookieWithOs(real, "android"), ua = UA)
        hitLogin("无os     + 安卓UA ", "https://music.163.com/weapi/login/cellphone",
            "weapi", pwdBody, bareStyle(real), ua = ANDROID_UA)
        hitLogin("无os     + PC  UA ", "https://music.163.com/weapi/login/cellphone",
            "weapi", pwdBody, bareStyle(real), ua = UA)

        // ② eapi 通道（interface/interface3），项目里已验证可用的另一条路
        line("")
        line("── ② 密码登录 eapi 通道 ──")
        for (host in listOf("https://interface.music.163.com", "https://interface3.music.163.com")) {
            hitLogin("eapi $host", "$host/eapi/login/cellphone",
                "eapi", pwdBody, bareStyle(real), ua = UA)
        }

        // ③ 验证码：参数名必须是 cellphone + ctcode（第一轮写成 phone/countrycode 被判参数错误）
        line("")
        line("── ③ 发短信验证码（cellphone+ctcode）──")
        val smsBody = mapOf("cellphone" to fakePhone, "ctcode" to "86")
        hitLogin("os=android + 安卓UA ", "https://music.163.com/weapi/sms/captcha/sent",
            "weapi", smsBody, cookieWithOs(real, "android"), ua = ANDROID_UA)
        hitLogin("无os     + PC  UA ", "https://music.163.com/weapi/sms/captcha/sent",
            "weapi", smsBody, bareStyle(real), ua = UA)

        // ⑤ 二维码 unikey：项目当前用 eapi 优先，NCMusic 走明文 /api。对照一下。
        line("")
        val qrBody = mapOf("type" to "1", "csrf_token" to csrf.ifBlank { "" })
        hitLogin("qrUnikey/eapi", "https://interface3.music.163.com/eapi/login/qrcode/unikey",
            "eapi", qrBody, ckA, ua = ANDROID_UA)
        hitLogin("qrUnikey/weapi裸域", "https://music.163.com/weapi/login/qrcode/unikey",
            "weapi", qrBody, ckB, ua = UA)
        // 明文GET（NCMusic 注释里说的免签路径）
        runCatching {
            http.newCall(
                okhttp3.Request.Builder()
                    .url("https://music.163.com/api/login/qrcode/unikey?type=1")
                    .header("User-Agent", UA)
                    .header("Cookie", ckB)
                    .header("Referer", "https://music.163.com/")
                    .get().build()
            ).execute().use { r -> line("  qrUnikey/明文GET   http=${r.code} ${r.body?.string()?.take(120) ?: "空"}") }
        }.onFailure { line("  qrUnikey/明文GET   异常 ${it.message}") }

        // ⑥ 拿真实 csrf 的方式也可能是差异点：项目用 enrichCookie 补 os/appver/NMTID，
        // NCMusic 直接用浏览器粘来的原始 Cookie。查一下两种 cookie 实际差多少字段。
        line("")
        line("── Cookie 字段差异（决定服务端认不认这个客户端）──")
        line("  A(${ckA.split(";").size}字段): ${ckA.split(";").map { it.trim().substringBefore("=") }.joinToString(",")}")
        line("  B(${ckB.split(";").size}字段): ${ckB.split(";").map { it.trim().substringBefore("=") }.joinToString(",")}")

        line("")
        line("判读：-460/-462/250 = 风控拦死；其它非200 业务码 = 通道本身没被拦，只是参数/账号不对")
    }

    /** 打一个登录类接口并打印服务端原文。kind 决定用 weapi 还是 eapi 签名。 */
    private suspend fun hitLogin(
        tag: String,
        url: String,
        kind: String,
        body: Map<String, String>,
        cookie: String,
        ua: String,
    ) {
        val form = if (kind == "eapi") {
            com.puremusic.source.netease.NeteaseCrypto.eapi(url.substringAfter("163.com"), body)
        } else {
            com.puremusic.source.netease.NeteaseCrypto.weapi(body)
        }
        val encoded = form.entries.joinToString("&") { (k, v) ->
            "${java.net.URLEncoder.encode(k, "UTF-8")}=${java.net.URLEncoder.encode(v, "UTF-8")}"
        }
        val txt = runCatching {
            http.newCall(
                okhttp3.Request.Builder()
                    .url(url)
                    .post(encoded.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                    .header("Cookie", cookie)
                    .header("User-Agent", ua)
                    .header("Origin", "https://music.163.com")
                    .header("Referer", "https://music.163.com/")
                    .build()
            ).execute().use { it.body?.string().orEmpty() }
        }.getOrElse { "异常: ${it.message}" }
        val code = Regex(""""code"\s*:\s*(-?\d+)""").find(txt)?.groupValues?.get(1) ?: "?"
        val msg = Regex(""""(?:msg|message)"\s*:\s*"([^"]{0,60})"""").find(txt)?.groupValues?.get(1).orEmpty()
        val verdict = when (code) {
            "200" -> "✅ 放行"
            "-460", "-462", "250" -> "⛔ 风控拦截"
            "?" -> "⚠️无响应/非JSON"
            else -> "· 业务拒绝(通道未被拦)"
        }
        line("  $tag → code=$code $verdict  $msg")
        line("     ${txt.take(180)}")
    }

    /** A 变体：补 os=android（本项目写接口的既有做法）。 */
    private fun androidStyle(cookie: String): String = cookieWithOs(cookie, "android")

    /** 给 cookie 补/改 os 与 appver（os 是本轮要单独隔离出来的变量）。 */
    private fun cookieWithOs(cookie: String, os: String): String {
        val parts = LinkedHashMap<String, String>()
        cookie.split(";").forEach { seg ->
            val t = seg.trim(); val i = t.indexOf('=')
            if (i > 0) parts[t.substring(0, i).trim()] = t.substring(i + 1).trim()
        }
        parts["os"] = os
        parts["appver"] = "8.9.70"
        return parts.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /** B 变体：剥掉 os/appver/NMTID，只保留服务端下发字段（NCMusic 的做法）。 */
    private fun bareStyle(cookie: String): String =
        cookie.split(";")
            .map { it.trim() }
            .filter { t ->
                val k = t.substringBefore("=").trim()
                k.isNotEmpty() && k !in setOf("os", "appver", "NMTID", "__remember_me")
            }
            .joinToString("; ")

    private companion object {
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        const val ANDROID_UA = "Mozilla/5.0 (Linux; Android 12; 25060RK16C) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}
