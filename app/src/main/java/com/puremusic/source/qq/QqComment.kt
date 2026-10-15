package com.puremusic.source.qq

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.puremusic.data.AccountStore
import com.puremusic.model.Comment
import com.puremusic.model.CommentSource
import com.puremusic.model.MusicSourceType
import com.puremusic.model.RepliedComment
import com.puremusic.source.netease.CommentPage
import com.puremusic.source.netease.PostResult
import com.puremusic.ui.login.QqLoginManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/** QQ 音乐评论正文上限（服务端超了直接拒，输入栏据此提前拦）。 */
const val MAX_QQ_COMMENT_LEN = 300

/**
 * QQ 音乐评论数据源（读 + 写）。
 *
 * **为什么要单独一个源**：QQ 音乐的歌就该看 QQ 音乐的评论 —— 拿歌名去网易云搜同名曲再取评论，
 * 遇到翻唱 / 同名曲就会串到别人家的评论区。所以 QQ 源的歌走这里，网易云源的歌照旧走网易云。
 *
 * ## 两条读通道
 * 1. **现代接口（主力）**：musicu 的 `music.globalComment.CommentRead`
 *    （`GetNewCommentList` / `GetHotCommentList`），`BizId` 用**数字 songid**。
 *    一条请求就带回 `Total` / `HasMore` / `IsSelf` / `IsPraised` / `Location`，
 *    而且评论 id（`CmId`）与**写接口同一套**（发表回的 `AddedCmId`、删除要的 `CommentId`）
 *    —— 这是写能力能不能真正闭环的关键。
 * 2. **老 H5 接口（兜底）**：`fcg_global_comment_h5.fcg`。字段名各版本漂移得厉害
 *    （正文在 `middlecommentcontent[].subcommentcontent`、总数恒为 0），
 *    且它的 `commentid` 与现代 `CmId` **不是一套 id**，所以只在现代通道整个失败时才用。
 *
 * `songmid → 数字 songid` 的解析见 [resolveSongId]（三条通道兜底 + 缓存）。
 *
 * ## 写通道
 * 发表 / 删除走 musicu `music.globalComment.CommentWriteServer`（`AddComment` / `DelComment`）；
 * 点赞只有老 H5 `fcg_global_comment_praise_h5.fcg` 一条路。
 *
 * **两条写通道要的凭据是同一套：web 侧 `g_tk` + Cookie 里的 `qm_keyst` / `qqmusic_key`**
 * —— 见 [webComm] / [webAuths]。扫码登录下发的 cookie 里只有 `musickey`，
 * 少了那两个键，两条通道都会判「未登录」（musicu 报 `code:500026`、H5 报
 * `code:1000 login check failed`），这才是「QQ 评论点不了也发不出」的真正根因。
 */
@Singleton
class QqComment @Inject constructor(
    @Named("plain") private val client: OkHttpClient,
    private val accountStore: AccountStore,
    /** 只为了复用 musicu 的 `comm` 构造与「凭证过期自动续期」的 POST。 */
    private val qqSource: QqMusicSource,
) {

    private val gson = Gson()

    /** songmid → 数字 songid。翻页几十次也只解析一次，省掉每页一次额外往返。 */
    private val songIdCache = ConcurrentHashMap<String, String>()

    /**
     * 取一页评论。
     *
     * 现代 musicu 接口优先，整个失败才回落老 H5 —— 见类文档里两条读通道的说明。
     *
     * @param pageNum 从 0 开始；只有第 0 页会带回精彩评论。
     */
    suspend fun page(songMid: String, pageNum: Int = 0, pageSize: Int = 25): CommentPage =
        withContext(Dispatchers.IO) {
            val songId = resolveSongId(songMid)
            if (songId.isNullOrBlank()) {
                Log.e(TAG, "songId 解析失败 songMid=$songMid")
                return@withContext CommentPage()
            }
            val modern = runCatching { modernPage(songId, pageNum, pageSize) }.getOrNull()
            if (modern != null) {
                Log.e(
                    TAG,
                    "page(modern) songId=$songId page=$pageNum total=${modern.total} " +
                        "hot=${modern.hot.size} n=${modern.comments.size} more=${modern.more}",
                )
                return@withContext modern
            }
            Log.e(TAG, "现代评论接口没给数据，回落老 H5 songId=$songId page=$pageNum")
            legacyPage(songId, pageNum, pageSize)
        }

    /**
     * 现代评论通道：`music.globalComment.CommentRead`。
     *
     * @return `null` = 这一条通道没走通（模块报错 / 网络异常），由上层回落老 H5；
     *   空列表是**合法结果**（这首歌确实没人评论），不会触发回落。
     */
    private suspend fun modernPage(songId: String, pageNum: Int, pageSize: Int): CommentPage? {
        val cookie = qqCookie()
        val req = musicuRead(
            READ_MODULE,
            "GetNewCommentList",
            buildString {
                append("\"PageSize\":${pageSize.coerceIn(1, 50)}")
                append(",\"PageNum\":$pageNum")
                append(",\"HashTagID\":\"\",\"BizType\":$BIZ_TYPE_SONG,\"PicEnable\":1")
                append(",\"LastCommentSeqNo\":\"\",\"SelfSeeEnable\":1")
                append(",\"BizId\":\"$songId\",\"AudioEnable\":1")
                append(",\"BizSubType\":$BIZ_SUB_TYPE_SONG")
            },
            cookie,
        ) ?: return null
        if ((req.intOf("code") ?: -1) != 0) {
            Log.e(TAG, "modernPage 被拒 raw=${req.toString().take(200)}")
            return null
        }
        val data = req.obj("data") ?: return null
        val list = data.obj("CommentList")
        val items = list?.arr("Comments")?.mapNotNull { parseModern(it, hot = false) }.orEmpty()
        val total = list?.intOf("Total") ?: data.intOf("TotalCmNum") ?: 0
        val more = (list?.intOf("HasMore") ?: 0) > 0
        val hot = if (pageNum == 0) modernHot(songId, cookie) else emptyList()
        return CommentPage(
            total = maxOf(total, items.size),
            hot = hot,
            comments = items,
            more = more,
        )
    }

    /** 精彩评论（现代通道）：`GetHotCommentList`。局部失败不拖垮整页，直接给空热评。 */
    private suspend fun modernHot(songId: String, cookie: String): List<Comment> {
        val req = runCatching {
            musicuRead(
                READ_MODULE,
                "GetHotCommentList",
                "\"BizType\":$BIZ_TYPE_SONG,\"BizId\":\"$songId\",\"LastCommentSeqNo\":\"\"" +
                    ",\"PageSize\":15,\"PageNum\":0,\"HotType\":1,\"WithAirborne\":0" +
                    ",\"PicEnable\":1,\"BizSubType\":$BIZ_SUB_TYPE_SONG",
                cookie,
            )
        }.getOrNull() ?: return emptyList()
        if ((req.intOf("code") ?: -1) != 0) return emptyList()
        return req.obj("data")?.obj("CommentList")?.arr("Comments")
            ?.mapNotNull { parseModern(it, hot = true) }.orEmpty()
    }

    /**
     * 解析现代评论节点（`Comments[]`）。
     *
     * `IsSelf` 是服务端直接给的「这条是我发的」—— QQ 的评论者标识是加密串，
     * 与登录 uin 对不上，所以删除入口只能靠它（见 [Comment.isSelf]）。
     */
    private fun parseModern(el: JsonElement, hot: Boolean): Comment? {
        val o = runCatching { el.asJsonObject }.getOrNull() ?: return null
        val content = unescapeHtml(o.stringOf("Content").orEmpty()).replace("\\n", "\n").trim()
        if (content.isBlank()) return null
        val cmId = o.stringOf("CmId").orEmpty()
        val seq = o.stringOf("SeqNo").orEmpty()
        val seconds = o.longOf("PubTime") ?: 0L
        return Comment(
            id = cmId.ifBlank { seq },
            source = CommentSource.QQ,
            userName = o.stringOf("Nick").orEmpty().trim().removePrefix("@").ifBlank { "匿名用户" },
            avatarUrl = o.stringOf("Avatar").orEmpty().replace("http://", "https://"),
            content = content,
            likeCount = o.intOf("PraiseNum") ?: 0,
            replyCount = o.intOf("ReplyCnt") ?: 0,
            timeText = formatTime(seconds * 1000L),
            createdMs = seconds * 1000L,
            // 字段名是 IsPraised（带 d）—— 服务端原始响应里没有 IsPraise，写错点赞状态就永远 false
            liked = (o.intOf("IsPraised", "IsPraise") ?: 0) > 0,
            ipLocation = o.stringOf("Location").orEmpty(),
            hot = hot,
            vipType = if ((o.intOf("VipType") ?: 0) > 0) 1 else 0,
            isSelf = (o.intOf("IsSelf") ?: 0) > 0,
        )
    }

    /** 老 H5 评论通道（兜底）。id 与现代 `CmId` 不是一套，只在现代通道整个失败时使用。 */
    private suspend fun legacyPage(songId: String, pageNum: Int, pageSize: Int): CommentPage =
        withContext(Dispatchers.IO) {
            val cookie = qqCookie()
            val url = "https://c.y.qq.com/base/fcgi-bin/fcg_global_comment_h5.fcg" +
                "?g_tk=5381&loginUin=0&hostUin=0&format=json&inCharset=utf8&outCharset=utf-8" +
                "&notice=0&platform=yqq.json&needNewCode=0" +
                "&cid=205360772&reqtype=2&biztype=1" +
                "&topid=$songId&cmd=8&needmusiccrit=0" +
                "&pagenum=$pageNum&pagesize=$pageSize&lasthotcommentid=&domain=qq.com" +
                "&ct=24&cv=101010"
            val builder = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Referer", "https://y.qq.com/")
            if (cookie.isNotBlank()) builder.header("Cookie", cookie)
            val text = runCatching {
                client.newCall(builder.build()).execute().use {
                    if (it.isSuccessful) it.body?.string().orEmpty() else ""
                }
            }.getOrDefault("")
            if (text.isBlank()) {
                Log.e(TAG, "legacy 空响应 songId=$songId page=$pageNum")
                return@withContext CommentPage()
            }
            val root = runCatching { gson.fromJson(text, JsonObject::class.java) }.getOrNull()
            if (root == null) {
                Log.e(TAG, "legacy 非 JSON songId=$songId resp=${text.take(160)}")
                return@withContext CommentPage()
            }

            val comment = root.obj("comment")
            val list = comment?.arr("commentlist")
                ?.mapNotNull { parseItem(it, hot = false) }.orEmpty()
            // 实测 commenttotal 恒为 0（热评那边才有真实总数），所以总数按"已拿到多少条"算，
            // 由 UI 层累加 —— 用户看到的是"已加载 71 / 共 71"，不会凭空冒出一个假总数。
            val total = maxOf(comment?.intOf("commenttotal") ?: 0, list.size)
            // 接口不给 hasmore，且"本页装满了"不能当作还有更多（实测第一页常常只给 24 条，
            // 后面 2、3 页照样有内容）。所以只要有内容就继续翻，靠"取到空页 / 全是重复"收尾。
            val hasMoreRaw = comment?.intOf("hasmore")
            val more = if (hasMoreRaw != null) hasMoreRaw > 0 else list.isNotEmpty()
            val hot = if (pageNum == 0) {
                root.obj("hot_comment")?.arr("commentlist")
                    ?.mapNotNull { parseItem(it, hot = true) }.orEmpty()
            } else {
                emptyList()
            }
            android.util.Log.d(
                "QqComment",
                "legacy page songId=$songId page=$pageNum total=$total " +
                    "hot=${hot.size} n=${list.size} more=$more",
            )
            CommentPage(
                total = maxOf(total, list.size),
                hot = hot,
                comments = list,
                more = more,
            )
        }

    /**
     * songmid → 数字 songid。
     *
     * 三条通道依次尝试，任一成功即缓存。本机（代理出口 IP）访问 QQ 常态被风控，
     * 所以这里不赌"哪条一定行"，而是全都试一遍 + 打日志 —— 真机 logcat 一看就知道走通的是哪条。
     * A. `fcg_play_single_song.fcg`：最老最稳，`data[0].id`；
     * B. `musicu.fcg / music.trackInfo.UniformRuleCtrl`：官方 App 的曲库接口；
     * C. `fcg_music_express_mobile3.fcg`：取流同款老接口，`data.items[0].songid`。
     */
    suspend fun resolveSongId(songMid: String): String? {
        songIdCache[songMid]?.let { return it }
        val a = songIdViaSingleSong(songMid)
        val b = a ?: songIdViaTrackInfo(songMid)
        val id = b ?: songIdViaExpress(songMid)
        android.util.Log.d("QqComment", "resolveSongId $songMid -> $id (A=$a B=$b)")
        if (!id.isNullOrBlank()) songIdCache[songMid] = id
        return id
    }

    private suspend fun songIdViaSingleSong(songMid: String): String? = withContext(Dispatchers.IO) {
        val url = "https://c.y.qq.com/v8/fcg-bin/fcg_play_single_song.fcg" +
            "?songmid=$songMid&tpl=yqq_song_detail&format=json" +
            "&g_tk=5381&loginUin=0&hostUin=0&inCharset=utf8&outCharset=utf-8" +
            "&notice=0&platform=yqq&needNewCode=0"
        val text = get(url) ?: return@withContext null
        runCatching {
            gson.fromJson(text, JsonObject::class.java)
                .arr("data")?.firstOrNull()?.let { JsonElementParser.objOf(it) }
                ?.stringOf("id")
        }.getOrNull()?.takeIf { it.isNotBlank() && it != "0" }
    }

    private suspend fun songIdViaTrackInfo(songMid: String): String? = withContext(Dispatchers.IO) {
        val json = """{"comm":{"ct":"24","cv":"0","format":"json","inCharset":"utf-8",""" +
            """"outCharset":"utf-8","uin":"0"},"req_0":{"module":"music.trackInfo.""" +
            """UniformRuleCtrl","method":"CgiGetTrackInfo","param":{"ids":["$songMid"],"types":[0]}}}"""
        val text = runCatching {
            client.newCall(
                Request.Builder()
                    .url("https://u.y.qq.com/cgi-bin/musicu.fcg")
                    .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .header("Referer", "https://y.qq.com/")
                    .header("User-Agent", UA)
                    .build()
            ).execute().use { if (it.isSuccessful) it.body?.string().orEmpty() else "" }
        }.getOrDefault("")
        if (text.isBlank()) return@withContext null
        runCatching {
            gson.fromJson(text, JsonObject::class.java)
                .obj("req_0")?.obj("data")?.arr("tracks")
                ?.firstOrNull()?.let { JsonElementParser.objOf(it) }
                ?.stringOf("id")
        }.getOrNull()?.takeIf { it.isNotBlank() && it != "0" }
    }

    private suspend fun songIdViaExpress(songMid: String): String? = withContext(Dispatchers.IO) {
        val url = "https://c.y.qq.com/base/fcgi-bin/fcg_music_express_mobile3.fcg" +
            "?format=json&platform=yqq&cid=205361747&songmid=$songMid" +
            "&filename=C400$songMid.m4a&guid=1234567890"
        val text = get(url) ?: return@withContext null
        runCatching {
            gson.fromJson(text, JsonObject::class.java)
                .obj("data")?.arr("items")?.firstOrNull()?.let { JsonElementParser.objOf(it) }
                ?.stringOf("songid")
        }.getOrNull()?.takeIf { it.isNotBlank() && it != "0" }
    }

    // ═══════════════════════════════════════════════════════════════
    //  写能力：发表 / 回复 / 删除 / 点赞
    // ═══════════════════════════════════════════════════════════════

    /**
     * 发表评论 / 回复。
     *
     * 走 musicu `music.globalComment.CommentWriteServer` / `AddComment`。
     * 鉴权是 **web 侧那一套**（`g_tk = hash33(musickey, 5381)` + Cookie 补
     * `qm_keyst` / `qqmusic_key`），见 [webComm]。用安卓端 comm 会被前置层拒
     * `code:500026`（真机实测）。
     *
     * 服务端响应：`req_0.data.AddedCmId` 是新评论 id，`SubCode == 0` 才算真成功
     * （非 0 是「被内容审核拒了」，`VerifyUrl` 非空是「要过验证码」）。
     * 拿到 id 后再把评论列表读一次，用**服务端真实的评论对象**插到列表里 ——
     * 只有那样 `IsSelf` / 头像 / 时间 / 楼层才全对，删起来也才有正确 id。
     */
    suspend fun postComment(
        songMid: String,
        content: String,
        replyToCommentId: String = "",
    ): PostResult = withContext(Dispatchers.IO) {
        val text = content.trim()
        if (text.isEmpty()) return@withContext PostResult.Failure("评论内容不能为空")
        if (text.length > MAX_QQ_COMMENT_LEN) {
            return@withContext PostResult.Failure("QQ 音乐评论最多 $MAX_QQ_COMMENT_LEN 字")
        }
        val cookie = qqCookie()
        if (cookie.isBlank()) return@withContext PostResult.Failure("登录 QQ 音乐后才能发表评论")
        val songId = resolveSongId(songMid)
            ?: return@withContext PostResult.Failure("没定位到这首歌的 QQ 评论 id")
        val param = buildString {
            append("\"Content\":${gson.toJson(text)}")
            append(",\"BizType\":$BIZ_TYPE_SONG,\"BizId\":\"$songId\"")
            append(",\"BizSubType\":$BIZ_SUB_TYPE_SONG")
            if (replyToCommentId.isNotBlank()) append(",\"RepliedCmId\":\"$replyToCommentId\"")
        }
        val req = musicuWrite(WRITE_MODULE, "AddComment", param, cookie)
            ?: return@withContext PostResult.Failure("网络异常，评论没发出去")
        val code = req.intOf("code") ?: -1
        if (code != 0) {
            val msg = req.stringOf("msg", "Msg").orEmpty()
            Log.e(TAG, "AddComment 被拒 code=$code raw=${req.toString().take(300)}")
            return@withContext PostResult.Failure(
                if (msg.isNotBlank()) msg else writeFailHint(code),
            )
        }
        val data = req.obj("data")
        val sub = data?.intOf("SubCode", "Subcode") ?: 0
        val msg = data?.stringOf("Msg", "msg").orEmpty()
        val verify = data?.stringOf("VerifyUrl").orEmpty()
        if (sub != 0) {
            Log.e(TAG, "AddComment SubCode=$sub msg=$msg verify=$verify")
            return@withContext PostResult.Failure(
                msg.ifBlank { "发表失败：内容被服务端拒绝（$sub）" },
            )
        }
        if (verify.isNotBlank()) {
            return@withContext PostResult.Failure("需要先在 QQ 音乐 App 里过一次验证码")
        }
        val cid = data?.stringOf("AddedCmId", "CmId").orEmpty()
        val echoed = if (cid.isNotBlank()) findPosted(songMid, cid, text) else null
        Log.e(TAG, "AddComment ok cid=$cid 回读=${echoed != null}")
        PostResult.Success(cid, echoed)
    }

    /** 删除自己发的评论：musicu `CommentWriteServer` 的 `DelComment`，`Subcode == 0` 即成功。 */
    suspend fun deleteComment(songMid: String, commentId: String): Boolean =
        withContext(Dispatchers.IO) {
            val cookie = qqCookie()
            if (cookie.isBlank()) return@withContext false
            val songId = resolveSongId(songMid).orEmpty()
            val param = "\"CommentId\":\"$commentId\"" +
                if (songId.isNotBlank()) ",\"BizType\":$BIZ_TYPE_SONG,\"BizId\":\"$songId\"" else ""
            val req = musicuWrite(WRITE_MODULE, "DelComment", param, cookie) ?: return@withContext false
            val code = req.intOf("code") ?: -1
            val sub = req.obj("data")?.intOf("Subcode", "SubCode") ?: 0
            Log.e(TAG, "DelComment cid=$commentId code=$code sub=$sub raw=${req.toString().take(200)}")
            code == 0 && sub == 0
        }

    /**
     * 点赞 / 取消点赞。
     *
     * 点赞**只有老 H5 一条路**（`fcg_global_comment_praise_h5.fcg`，`cmd=1` 赞、`cmd=2` 取消，
     * musicu 侧没有公开的点赞模块），所以这里仍要 g_tk 签名 —— 见 [write] 的候选密钥机制。
     */
    suspend fun setLike(commentId: String, like: Boolean): Boolean = withContext(Dispatchers.IO) {
        val cookie = qqCookie()
        if (cookie.isBlank()) return@withContext false
        val cmd = if (like) PRAISE_CMD_ON else PRAISE_CMD_OFF
        val resp = write(cookie) { auth ->
            "https://c.y.qq.com/base/fcgi-bin/fcg_global_comment_praise_h5.fcg" +
                "?g_tk=${auth.gtk}&loginUin=${auth.loginUin}&hostUin=0&format=json" +
                "&inCharset=utf8&outCharset=utf-8&notice=0&platform=yqq.json&needNewCode=0" +
                "&cid=205360774&cmd=$cmd&reqtype=2&biztype=$BIZ_TYPE_SONG&commentid=$commentId" +
                "&domain=qq.com&ct=24&cv=101010"
        }
        Log.e(
            TAG,
            "setLike cid=$commentId like=$like ok=${resp.ok} code=${resp.code} raw=${resp.text.take(200)}",
        )
        resp.ok
    }

    // ── musicu 通道（现代读写接口都用它）────────────────────────────────────

    /**
     * 打一次 musicu 请求，返回内层 `req_0` 对象（含 `code` / `data`）。
     *
     * ## 🔥 评论模块认的是 **web 侧** comm，不是安卓端那套（2026-10-15 真机实测）
     * `music.globalComment.*` 是 y.qq.com 的 web 模块，鉴权走 web 规矩：
     * `comm.g_tk = hash33(musickey, 5381)` + `g_tk_new_20200303` + Cookie 里的
     * `qm_keyst` / `qqmusic_key`。之前用安卓端 comm（`authst=musickey` + `tmeLoginType`）
     * —— 那是取流/搜索那套 —— 发表评论**一律被拒 `code:500026`**（连 `data` 都不给），
     * 这才是「QQ 评论发不出去」真正的根因，与 g_tk 密钥无关。
     *
     * 读接口两种 comm 都收，所以 [page] 先 web 后安卓，双保险。
     *
     * @param web `true` = web 侧 comm（写接口唯一可用形态）；`false` = 安卓端形态。
     * @return `null` = 网络异常或响应不是 JSON。
     */
    private suspend fun musicu(
        module: String,
        method: String,
        paramJson: String,
        cookie: String,
        web: Boolean = true,
    ): JsonObject? {
        val comm = if (web) webComm(cookie) else androidComm(cookie)
        val sent = if (web) webCookie(cookie) else cookie
        val json = "{\"comm\":{$comm}," +
            "\"req_0\":{\"module\":\"$module\",\"method\":\"$method\",\"param\":{$paramJson}}}"
        val text = qqSource.cgiPost(json, sent) ?: return null
        // 打全一点：评论区字段名各版本漂移，排查时得看得见完整的一条评论（logcat 单行上限 4K）
        Log.e(TAG, "musicu(${if (web) "web" else "android"}) $method <- ${text.take(3000)}")
        return runCatching { gson.fromJson(text, JsonObject::class.java) }.getOrNull()?.obj("req_0")
    }

    /** 读接口：web 侧 comm 优先，非 0 再回退安卓端 comm（两条路都试过才认命）。 */
    private suspend fun musicuRead(
        module: String,
        method: String,
        paramJson: String,
        cookie: String,
    ): JsonObject? {
        val web = musicu(module, method, paramJson, cookie)
        if (web != null && web.intOf("code") == 0) return web
        val alt = musicu(module, method, paramJson, cookie, web = false)
        return if (alt != null && alt.intOf("code") == 0) alt else (web ?: alt)
    }

    /**
     * 写接口：同样 web 优先。
     *
     * 失败时才多打一次安卓端形态的请求——正常路径下不产生额外流量，
     * 但万一用户是手机号（原生账号）登录、web 侧凭据不全，还有条退路。
     * 两边都挂时回传 **web 侧**那条响应 —— 它才是这条通道的正规回复。
     */
    private suspend fun musicuWrite(
        module: String,
        method: String,
        paramJson: String,
        cookie: String,
    ): JsonObject? {
        val web = musicu(module, method, paramJson, cookie)
        if (web != null && web.intOf("code") == 0) return web
        val alt = musicu(module, method, paramJson, cookie, web = false)
        return if (alt != null && alt.intOf("code") == 0) alt else (web ?: alt)
    }

    /**
     * web 侧 `comm` 段（`platform: yqq.json`）。
     *
     * ⚠️ `uin` 必须用 **`musicid`**（QQ 音乐账号 id），不是 QQ 号 —— y.qq.com 的接口
     * 只认它；`g_tk` 用 `hash33(musickey, 5381)`，两个 g_tk 键都要带。
     */
    private fun webComm(cookie: String): String {
        val musicId = musicIdOf(cookie)
        val key = authstOf(cookie)
        val gtk = if (key.isBlank()) 5381 else QqLoginManager.hash33(key, 5381)
        return buildString {
            append("\"ct\":24,\"cv\":$CV,\"platform\":\"yqq.json\",\"chid\":\"0\"")
            if (musicId.isNotBlank()) append(",\"uin\":$musicId")
            append(",\"g_tk\":$gtk,\"g_tk_new_20200303\":$gtk")
            append(",\"format\":\"json\",\"inCharset\":\"utf-8\",\"outCharset\":\"utf-8\"")
            append(",\"notice\":0,\"need_new_code\":1")
        }
    }

    /**
     * web 侧 Cookie：`qm_keyst` / `qqmusic_key` 这两个键**必须由我们自己补**——
     * 扫码登录拿到的 cookie 里只有 `musickey`，没有它们，g_tk 校验就无凭据可依。
     */
    private fun webCookie(cookie: String): String {
        val musicId = musicIdOf(cookie)
        val key = authstOf(cookie)
        val forged = buildList {
            if (musicId.isNotBlank()) {
                add("uin=$musicId")
                add("qqmusic_uin=$musicId")
            }
            if (key.isNotBlank()) {
                add("qm_keyst=$key")
                add("qqmusic_key=$key")
            }
        }.joinToString("; ")
        return forged.ifBlank { cookie }
    }

    /** 安卓端 `comm`（复用 [QqMusicSource.commFields]）：取流/搜索那套。 */
    private fun androidComm(cookie: String): String = buildString {
        append(qqSource.commFields(uinOf(cookie), cookie, authstOf(cookie), ct = 24, cv = CV))
        append(",\"platform\":\"yqq.json\"")
    }

    // ── g_tk 签名（只剩点赞用得上）─────────────────────────────────────────

    /** 一条写请求的原始结果：`code=0` 才是唯一成功；`-1` = 连响应体都没拿到。 */
    private data class WriteResp(val code: Int, val text: String) {
        val ok: Boolean get() = code == 0
    }

    /**
     * 一次 H5 写请求要凑齐的三件套：`g_tk`、`loginUin`、Cookie。
     *
     * 三者必须**互相匹配**——g_tk 是按某个密钥算的，服务端得能从 Cookie 里
     * 找出同一个密钥来验签；loginUin 也得和 Cookie 里的 uin 对得上。
     * 所以候选是「密钥 × Cookie 形态」两个维度，不能只换 g_tk。
     */
    private data class WebAuth(
        val gtk: Int,
        val loginUin: String,
        val cookie: String,
        /** 日志用：命中的是哪个密钥 / 哪种 Cookie。 */
        val label: String,
    )

    /**
     * 命中的组合（进程内缓存）。首次写请求逐个试（见 [webAuths]），命中后不再多打无效请求。
     */
    @Volatile
    private var gtkHit: WebAuth? = null

    /**
     * H5 写接口的候选组合，按「最可能命中」排序。
     *
     * ⚠️ 为什么要"试"而不是写死一个：本 App 的 QQ 登录走 QQ 互联 OAuth，cookie 里
     * 同时躺着**好几套凭据**（`musickey` / `p_skey` / `superkey` / `airkey` …），
     * 服务端认哪一个没有公开文档；而**认错的唯一表现是 `code:1000 login check failed`**
     * —— 和"压根没登录"长得一模一样，肉眼分不出来。
     *
     * 另外还有个容易忽略的坑：H5 的登录校验看的是 Cookie 里的 `qm_keyst` / `qqmusic_key`，
     * 而扫码登录下发的 cookie **只有 `musickey`**。所以要先补一份"web 形态"的 Cookie
     * （见 [webCookie]），否则 g_tk 算得再对也没凭据可验。
     */
    private fun webAuths(cookie: String): List<WebAuth> {
        val web = webCookie(cookie)
        val id = musicIdOf(cookie).ifBlank { uinOf(cookie) }
        val seeds = gtkSeeds(cookie)
        val out = ArrayList<WebAuth>(seeds.size * 2)
        // 第一轮：web 形态 Cookie（补了 qm_keyst/qqmusic_key，理论正解）
        for (s in seeds) out += WebAuth(s.gtk, id, web, "web/${s.name}")
        // 第二轮：原样 cookie 兜底
        for (s in seeds) out += WebAuth(s.gtk, uinOf(cookie), cookie, "raw/${s.name}")
        return out
    }

    /**
     * g_tk 候选：`hash33(<密钥>, 5381)`，按可能性排序。
     *
     * `musickey` 排第一 —— 它是 QQ 音乐自家账号体系的票据，web 侧 g_tk 就是拿它算的
     * （`hash33(musickey, 5381)`，开源实现与本站 web 一致）；后面才是其它 cookie 凭据，
     * 最后兜底把 cookie 里所有字段都当一遍密钥，由服务端告诉我们哪个对。
     */
    private fun gtkSeeds(cookie: String): List<GtkSeed> {
        val out = LinkedHashMap<String, GtkSeed>()
        for (name in GTK_KEY_NAMES) {
            val v = cookieField(cookie, name)?.takeIf { it.isNotBlank() && it != "0" } ?: continue
            out[name] = GtkSeed(name, QqLoginManager.hash33(v, 5381))
        }
        // 常量种子：jsososo / Billxkk 的 QQMusicApi 里对这几个 H5 写接口用的就是这个值
        out["常量"] = GtkSeed("常量", PRAISE_GTK_CONST)
        // 兜底：cookie 全字段（排除明显不是密钥的枚举/数字字段）
        for (seg in cookie.split(";")) {
            val t = seg.trim()
            val i = t.indexOf('=')
            if (i <= 0) continue
            val name = t.substring(0, i).trim()
            val value = t.substring(i + 1).trim()
            if (name in out || value.length < 6 || value.toLongOrNull() != null) continue
            out[name] = GtkSeed(name, QqLoginManager.hash33(value, 5381))
        }
        return out.values.toList()
    }

    private data class GtkSeed(val name: String, val gtk: Int)

    /**
     * 打一次 H5 写请求，组合不对就换下一组重试（上限 [MAX_H5_WRITE_ATTEMPTS] 次）。
     *
     * 判定很直接：`code=1000` = 服务端没认登录态 → 换一组；
     * 其它任何 code 都算"登录这关过了"，原样返回给上层解释（成功 / 业务拒绝）。
     */
    private fun write(cookie: String, build: (WebAuth) -> String): WriteResp {
        val cached = gtkHit
        if (cached != null) {
            val r = hit(build(cached), cached.cookie)
            if (r.code != CODE_NOT_LOGGED_IN) return r
            // 重新登录后会话密钥会轮换，缓存失效 → 清掉重挑
            gtkHit = null
        }
        var last: WriteResp? = null
        var tried = 0
        for (auth in webAuths(cookie)) {
            if (tried++ >= MAX_H5_WRITE_ATTEMPTS) break
            val r = hit(build(auth), auth.cookie)
            if (r.code == CODE_NOT_LOGGED_IN) {
                last = r
                continue
            }
            if (r.code > 0) gtkHit = auth
            Log.e(TAG, "H5 写接口命中 ${auth.label} g_tk=${auth.gtk} code=${r.code}")
            return r
        }
        Log.e(TAG, "H5 写接口试了 $tried 组候选，全部被判未登录")
        return last ?: WriteResp(-1, "")
    }

    private fun hit(url: String, cookie: String): WriteResp {
        val text = runCatching {
            client.newCall(
                Request.Builder().url(url)
                    .header("User-Agent", UA)
                    .header("Referer", "https://y.qq.com/")
                    .header("Cookie", cookie)
                    .build()
            ).execute().use { if (it.isSuccessful) it.body?.string().orEmpty() else "" }
        }.getOrDefault("")
        val code = runCatching {
            gson.fromJson(text, JsonObject::class.java)?.intOf("code")
        }.getOrNull() ?: -1
        return WriteResp(code, text)
    }

    /** 发完回读：服务端落库偶有延迟，重试两轮（间隔 700ms）。 */
    private suspend fun findPosted(songMid: String, commentId: String, content: String): Comment? {
        repeat(2) { round ->
            if (round > 0) delay(700)
            val page = runCatching { page(songMid, 0, 25) }.getOrNull() ?: return@repeat
            val found = page.comments.firstOrNull { it.id == commentId }
                ?: page.comments.firstOrNull { it.content == content }
            if (found != null) return found
        }
        return null
    }

    private fun qqCookie(): String =
        accountStore.accounts.value[MusicSourceType.QQ]?.cookie.orEmpty()

    /** 取写接口要用的鉴权票据（`qqmusic_key` / `qm_keyst` / `musickey` 三选一）。 */
    private fun authstOf(cookie: String): String =
        listOf("qqmusic_key", "qm_keyst", "musickey")
            .asSequence()
            .mapNotNull { cookieField(cookie, it)?.takeIf { v -> v.isNotBlank() } }
            .firstOrNull()
            .orEmpty()

    private fun uinOf(cookie: String): String {
        val raw = cookieField(cookie, "uin")?.removePrefix("o")
            ?: cookieField(cookie, "ptui_loginuin")?.removePrefix("o")
            ?: cookieField(cookie, "musicid")
        return raw?.takeIf { it.isNotBlank() && it != "0" } ?: "0"
    }

    /**
     * QQ 音乐账号 id（web 侧 `comm.uin` / `qqmusic_uin`）。
     *
     * ⚠️ **不等于 QQ 号**：y.qq.com 的接口只认这个 id，拿 QQ 号去打 web 侧会判未登录；
     * 反过来取流（`vkey.GetVkeyServer`）又只认 QQ 号 —— 两套体系各自记牢。
     */
    private fun musicIdOf(cookie: String): String =
        cookieField(cookie, "musicid").orEmpty().ifBlank { uinOf(cookie) }
            .takeIf { it.isNotBlank() && it != "0" }
            .orEmpty()

    private fun cookieField(cookie: String, name: String): String? =
        Regex("(?:^|;)\\s*${Regex.escape(name)}=([^;]*)").find(cookie)?.groupValues?.get(1)

    /**
     * 写接口失败码 → 人能看懂的一句话。
     *
     * 这几个码都是真机实测/社区记录里出现过的，其余一律带码回显，方便对着 logcat 追。
     */
    private fun writeFailHint(code: Int): String = when (code) {
        500026 -> "发表失败：服务端没认登录态（web 鉴权未通过）"
        500003 -> "发表失败：这首歌的评论区没开放"
        1000, 104401, 104400 -> "登录态已失效，请重新登录 QQ 音乐"
        -1 -> "发表失败：响应异常"
        else -> "发表失败（错误码 $code）"
    }

    /**
     * 反转义 HTML 实体（现代评论接口的正文里会出现 `&#39;` / `&quot;` 这类编码，
     * 不转的话评论里就露出一串天书）。
     */
    private fun unescapeHtml(s: String): String {
        if (!s.contains('&')) return s
        return s
            .replace("&#13;", "\n")
            .replace("&#10;", "\n")
            .replace("&nbsp;", " ")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
    }

    private suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(
                Request.Builder().url(url).header("User-Agent", UA)
                    .header("Referer", "https://y.qq.com/").build()
            ).execute().use { if (it.isSuccessful) it.body?.string().orEmpty() else "" }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /**
     * 解析单条评论。
     *
     * 字段名是照着真机真实响应写的（2026-10），QQ 这个老 H5 接口跟网上流传的文档已经对不上：
     * - 正文在 `middlecommentcontent[]`，但它的内容字段叫 **`subcommentcontent`**，不叫 `str`；
     * - 根评论的 `middlecommentcontent` 是 **null**，正文反而落在 `rootcommentcontent` 里；
     * - 点赞数是 `praisenum`（不是 `praise_num`），`vipicon` 是个图片 URL（不是 0/1）。
     */
    private fun parseItem(el: JsonElement, hot: Boolean): Comment? {
        val o = runCatching { el.asJsonObject }.getOrNull() ?: return null
        val content = contentOf(o).trim()
        if (content.isBlank()) return null

        val cid = o.stringOf("commentid").orEmpty()
        val rootId = o.stringOf("rootcommentid").orEmpty()
        val rootContent = o.stringOf("rootcommentcontent").orEmpty().trim()
        val rootNick = o.stringOf("rootcommentnick").orEmpty().trim().trim('@')

        // 楼中楼引用：子评论里被回复者是 replyednick（"@xxx"），
        // 被回复的内容就是 rootcommentcontent；但根评论（rootcommentid == commentid）
        // 的 rootcommentcontent 其实是**它自己的正文**，不能当成引用。
        val replyedNick = o.arr("middlecommentcontent")?.firstOrNull()
            ?.let { JsonElementParser.objOf(it) }
            ?.stringOf("replyednick").orEmpty().trim().trim('@')
        val replied = when {
            replyedNick.isNotBlank() && rootContent.isNotBlank() ->
                listOf(RepliedComment(replyedNick, rootContent))
            rootId.isNotBlank() && rootId != cid && rootContent.isNotBlank() ->
                listOf(RepliedComment(rootNick.ifBlank { "该用户" }, rootContent))
            else -> emptyList()
        }

        val seconds = o.longOf("time", "createTime", "create_time") ?: 0L
        val ms = seconds * 1000L
        // vipicon 是图片地址（有值 = 会员），别按数字读
        val vip = o.stringOf("vipicon").orEmpty().isNotBlank() ||
            listOf("is_vip", "vip", "vip_type").any { (o.intOf(it) ?: 0) > 0 }

        return Comment(
            id = cid.ifBlank { "${o.stringOf("uin").orEmpty()}_${seconds}_${content.hashCode()}" },
            source = CommentSource.QQ,
            // `uin` 是发评人的 QQ 号，与登录 cookie 里的 uin 同源 —— 删除按钮靠它认"自己发的"。
            userId = o.longOf("uin") ?: 0L,
            userName = o.stringOf("nick", "nickname").orEmpty().trim('@').ifBlank { "匿名用户" },
            avatarUrl = o.stringOf("avatarurl", "avatar_url", "avatarUrl").orEmpty()
                .replace("http://", "https://"),
            content = content.replace(EMOJI_PATTERN, "[表情]"),
            likeCount = o.intOf("praisenum", "praise_num", "praiseNum") ?: 0,
            liked = (o.intOf("ispraise", "is_praise") ?: 0) > 0,
            replyCount = o.intOf("cmt_num", "reply_num", "replyNum") ?: 0,
            timeText = formatTime(ms),
            createdMs = ms,
            ipLocation = o.stringOf("ip_location", "location", "ipLocation").orEmpty(),
            beReplied = replied,
            hot = hot,
            vipType = if (vip) 1 else 0,
            // 老接口用 enable_delete 表达"这条是我发的、可删"（与现代接口的 IsSelf 同义）
            isSelf = (o.intOf("enable_delete", "root_enable_delete") ?: 0) > 0,
        )
    }

    /** 正文：① `middlecommentcontent[].subcommentcontent` ② 根评论的 `rootcommentcontent` ③ 新版 `content`。 */
    private fun contentOf(o: JsonObject): String {
        val sub = o.arr("middlecommentcontent")
            ?.joinToString("") { seg ->
                JsonElementParser.objOf(seg)
                    ?.stringOf("subcommentcontent", "str", "content").orEmpty()
            }
            ?.trim()
        if (!sub.isNullOrBlank()) return sub
        val root = o.stringOf("rootcommentcontent").orEmpty().trim()
        if (root.isNotBlank()) return root
        return o.stringOf("content", "commentcontent").orEmpty().trim()
    }

    private fun formatTime(ms: Long): String {
        if (ms <= 0L) return ""
        val diff = System.currentTimeMillis() - ms
        return when {
            diff < 60_000L -> "刚刚"
            diff < 3_600_000L -> "${diff / 60_000L}分钟前"
            diff < 86_400_000L -> "${diff / 3_600_000L}小时前"
            diff < 172_800_000L -> "昨天 ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))}"
            diff < 31_536_000_000L -> SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
            else -> SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(ms))
        }
    }

    private companion object {
        /** 日志 tag（release 包里 `Log.d` 会被优化掉，写接口调试一律用 `Log.e`）。 */
        const val TAG = "QqComment"

        /** QQ 的表情码形如 `[em]e400846[/em]`：不转义的话评论里会露出一串天书。 */
        val EMOJI_PATTERN = Regex("\\[em\\][^\\[]*\\[/em\\]")

        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        /** 写接口未登录/签名不认时的固定返回码（`{"code":1000,"msg":"login check failed"}`）。 */
        const val CODE_NOT_LOGGED_IN = 1000

        /** 点赞接口的 cmd：1 = 点赞，2 = 取消。 */
        const val PRAISE_CMD_ON = 1
        const val PRAISE_CMD_OFF = 2

        /**
         * 点赞 H5 接口的 g_tk 常量。
         *
         * 来自 jsososo / Billxkk 的 QQMusicApi，那几个项目对 `fcg_global_comment_*_h5.fcg`
         * 一直用这个固定值。它可能压根不校验，也可能是某个公共客户端的签名 ——
         * 总之当成候选之一先试，试不通再走 cookie 密钥表。
         */
        const val PRAISE_GTK_CONST = 1157392233

        /** 单次 H5 写请求最多试几组 g_tk × Cookie 候选，防止把服务端试出风控。 */
        private const val MAX_H5_WRITE_ATTEMPTS = 12

        /** musicu 模块：评论读写（QQ 音乐自家账号体系，鉴权靠 comm.authst）。 */
        const val READ_MODULE = "music.globalComment.CommentRead"
        const val WRITE_MODULE = "music.globalComment.CommentWriteServer"

        /** 业务类型：1 = 歌曲；歌曲的子类型固定 2（QQ 的约定，缺了会被判参数错）。 */
        const val BIZ_TYPE_SONG = 1
        const val BIZ_SUB_TYPE_SONG = 2

        /** musicu 的客户端版本号（评论模块认这个 ct/cv 组合）。 */
        const val CV = 4747474

        /**
         * g_tk 密钥候选顺序（在"常量"之后逐个试）。
         *
         * `musickey` 排第一：它是本 App 登录拿到的 QQ 音乐账号凭据，本项目其它
         * QQ CGI（歌词 `fcg_query_lyric_new`）也是用它算 g_tk；
         * 其余是扫码会话可能带回来的 QQ 通用凭据，作为兜底逐个试。
         */
        val GTK_KEY_NAMES = listOf(
            "musickey", "qqmusic_key", "qm_keyst", "skey", "p_skey", "superkey", "airkey",
            "lskey", "p_lskey",
        )
    }
}

/* ---------- 容错取值：字段缺失 / 类型不符一律给 null，由上层兜底，绝不抛异常 ---------- */

private object JsonElementParser {
    fun objOf(el: JsonElement?): JsonObject? =
        if (el != null && el.isJsonObject) el.asJsonObject else null
}

private fun JsonObject.obj(key: String): JsonObject? = JsonElementParser.objOf(get(key))

private fun JsonObject.arr(key: String): JsonArray? {
    val el = get(key) ?: return null
    return if (el.isJsonArray) el.asJsonArray else null
}

private fun JsonObject.stringOf(vararg keys: String): String? {
    for (k in keys) {
        val el = get(k) ?: continue
        if (el.isJsonPrimitive) {
            val v = runCatching { el.asString }.getOrNull()
            if (v != null) return v
        }
    }
    return null
}

private fun JsonElement.stringOf(): String? =
    if (isJsonPrimitive) runCatching { asString }.getOrNull() else null

private fun JsonObject.intOf(vararg keys: String): Int? {
    for (k in keys) {
        val el = get(k) ?: continue
        if (el.isJsonPrimitive) {
            val v = runCatching { el.asInt }.getOrNull()
            if (v != null) return v
        }
    }
    return null
}

private fun JsonObject.longOf(vararg keys: String): Long? {
    for (k in keys) {
        val el = get(k) ?: continue
        if (el.isJsonPrimitive) {
            val v = runCatching { el.asLong }.getOrNull()
            if (v != null) return v
        }
    }
    return null
}
