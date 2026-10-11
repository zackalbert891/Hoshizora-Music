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
    @Inject @Named("plain") lateinit var http: okhttp3.OkHttpClient

    private val TAG = "PureMusicDiag"
    private val out = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this).apply {
            textSize = 11f
            setPadding(24, 24, 24, 24)
        }
        setContentView(ScrollView(this).apply { addView(tv) })

        lifecycleScope.launch {
            // 只测评论：adb shell am start -n com.puremusic/.DiagActivity -e only comments
            // （评论链路只关心接口本身，不必等前面十几轮搜索/取流跑完）
            // 只测点赞：adb shell am start -n com.puremusic/.DiagActivity -e only like
            when (intent.getStringExtra("only")) {
                "comments" -> runComments()
                "like" -> runLike()
                "likeprobe" -> runLikeProbe()
                "likeprobe2" -> runLikeProbe2()
                // 手动取消某条赞（清理探针残留 / 验证 unlike 真的写进去了）：
                // adb shell am start -n com.puremusic/.DiagActivity -e only unlike -e song <id> -e cid <评论id>
                "unlike" -> runUnlike(
                    intent.getStringExtra("song").orEmpty(),
                    intent.getStringExtra("cid").orEmpty(),
                )
                else -> runDiag()
            }
            tv.text = out.toString()
        }
    }

    private fun line(s: String) {
        out.appendLine(s)
        Log.d(TAG, s)
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
        val p = runCatching { neteaseComment.page(songId, 0, 20) }.getOrNull()
        val c = p?.hot?.firstOrNull { it.id == cid } ?: p?.comments?.firstOrNull { it.id == cid }
        line("复查 liked=${c?.liked} 赞=${c?.likeCount}")
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

    private companion object {
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
