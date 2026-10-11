package com.puremusic.source.netease

import com.google.gson.Gson
import com.google.gson.JsonObject
import android.util.Log
import com.puremusic.data.AccountStore
import com.puremusic.model.Comment
import com.puremusic.model.CommentSource
import com.puremusic.model.RepliedComment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/** 一页评论：精彩评论 + 最新评论分页信息。 */
data class CommentPage(
    /** 该曲评论区总数（网易云返回的 total）。 */
    val total: Int = 0,
    /** 精彩评论（热评）。 */
    val hot: List<Comment> = emptyList(),
    val hotMore: Boolean = false,
    /** 最新评论。 */
    val comments: List<Comment> = emptyList(),
    /** 是否还有下一页最新评论。 */
    val more: Boolean = false,
)

/** 发评论结果：成功带服务端返回的 commentId 与新评论，失败带可直接展示的中文原因。 */
sealed interface PostResult {
    data class Success(val commentId: String, val comment: Comment?) : PostResult
    data class Failure(val reason: String) : PostResult
}

/** 网易云的「资源」类型：歌曲评论区的 threadId 前缀。 */
private const val THREAD_PREFIX = "R_SO_4_"

/** 网易云评论正文上限，超了服务端会直接拒（输入栏据此提前拦）。 */
const val MAX_COMMENT_LEN = 1000

/**
 * 网易云评论数据源：读（热评 / 最新 / 全量串流）+ 写（点赞 / 发表 / 删除）。
 *
 * 读的三条通道串行兜底（详见 [requestJson]）：裸 GET → weapi → eapi(interface / interface3)。
 * 只留一条通道时，换台机器/换个网络就会出现"评论全是空的、连总数都没有"，
 * 三通道实测（2026-10）均可用，代价只是多几次往返。
 *
 * 写的规矩更硬（详见 [writeJson]）：必须登录 Cookie + 必须伪装安卓身份，
 * 否则一律 250「设备存在安全风险」。
 *
 * 展示字段对齐网易云原版：点赞数、回复数、IP 属地、被回复的内容（`//@某人：…`）、
 * 用户 VIP 标识与头像，全部由这里解析，UI 层不再碰原始字段。
 */
@Singleton
class NeteaseComment @Inject constructor(
    @Named("plain") private val client: OkHttpClient,
    private val accountStore: AccountStore,
) {

private val gson = Gson()

/** 把歌曲 id 拼成网易云 threadId。 */
    fun threadIdFor(songId: String): String = THREAD_PREFIX + songId

    /** 取评论：[songId] 是网易云歌曲 id。 */
    suspend fun resourcePage(
        songId: String,
        offset: Int = 0,
        limit: Int = 20,
        beforeTime: Long = 0L,
    ): CommentPage = page(threadIdFor(songId), offset, limit, beforeTime)

    /** 热评（网易云"精彩评论"）。 */
    suspend fun hotComments(songId: String, limit: Int = 30): List<Comment> =
        fetchRaw("/api/v1/resource/hotcomments/R_SO_4_$songId?limit=$limit&offset=0", songId, "hotComments")

    /** 最新评论（按 offset 翻页）。 */
    suspend fun comments(songId: String, offset: Int = 0, limit: Int = 20): List<Comment> =
        fetchRaw("/api/v1/resource/comments/R_SO_4_$songId?limit=$limit&offset=$offset", songId, "comments")

    /**
     * 一次性拿齐：精彩评论 + 指定 offset 的最新评论一页（含总数与是否还有更多）。
     *
     * 这样打开评论页只需要两个请求，比"先热评再翻页"少一次往返；
     * offset > 0 时不再重复拉热评（热评只在首屏展示）。
     *
     * [threadId] 传**完整前缀**（`R_SO_4_` + 歌曲 id），也就是 [resourcePage] 拼好的那种。
     * 直接传裸歌曲 id 会读不到任何评论（网易云按 threadId 路由资源）—— 这是个很容易踩的坑，
     * 所以对外的正常入口只有 [resourcePage]。
     *
     * [beforeTime] 是**游标翻页**：接口的 offset 只能翻到 1000 条左右，
     * 再往后就返回空（这是服务端的硬限制，不是分页写错了）。热度高的大曲
     * 动辄四五千条评论，想全部拿到就必须换成"以上一条评论的时间为游标"，
     * 也就是网易云 Web 端用的 beforeTime。
     */
    suspend fun page(
        threadId: String,
        offset: Int = 0,
        limit: Int = 20,
        beforeTime: Long = 0L,
    ): CommentPage = withContext(Dispatchers.IO) {
        val cursor = if (beforeTime > 0L) "&beforeTime=$beforeTime" else ""
        val root = requestJson(
            path = "/api/v1/resource/comments/$threadId?limit=$limit&offset=$offset$cursor",
            eapiPath = "/api/v1/resource/comments/$threadId",
            eapiBody = mapOf(
                "rid" to threadId,
                "offset" to offset,
                "limit" to limit,
                "total" to true,
                "beforeTime" to beforeTime,
            ),
        )
        val page = parsePage(root, "comments")
        android.util.Log.d(
            "NeteaseComment",
            "page threadId=$threadId offset=$offset before=$beforeTime total=${page.total} " +
                "n=${page.comments.size} more=${page.more}"
        )
        if (offset > 0 || beforeTime > 0L) return@withContext page

        val hotRoot = requestJson(
            path = "/api/v1/resource/hotcomments/$threadId?limit=$HOT_LIMIT&offset=0",
            eapiPath = "/api/v1/resource/hotcomments/$threadId",
            eapiBody = mapOf("rid" to threadId, "offset" to 0, "limit" to HOT_LIMIT, "total" to false),
        )
        val hot = parseList(hotRoot?.get("hotComments")?.takeIf { it.isJsonArray }?.asJsonArray, hot = true)
        android.util.Log.d("NeteaseComment", "hot threadId=$threadId n=${hot.size}")
        page.copy(hot = hot)
    }

    /**
     * 评论点赞 / 取消点赞（**真的写服务端**，不是本地假反馈）。
     *
     * 端点：weapi `https://music.163.com/weapi/v1/comment/{like|unlike}`，
     * 失败再走 eapi `interface(3) /eapi/v1/comment/{like|unlike}`。
     * 参数是 `threadId`（`R_SO_4_` + 歌曲 id）+ `commentId`，与 NeteaseCloudMusicApi 一致。
     *
     * **必须带登录 Cookie**：匿名请求一律失败（常见 code 301 / -460 / -462），
     * 所以这里先判登录态，未登录直接失败，由 UI 提示「登录网易云后可点赞」。
     *
     * @return true 成功；false 失败（原因见 Log，通常是风控或登录态过期）。
     */
    suspend fun setLike(songId: String, commentId: String, like: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            val body = mapOf(
                "threadId" to threadIdFor(songId),
                "commentId" to commentId,
                "csrf_token" to csrfOrFail() ?: return@withContext false,
            )
            writeJson("/v1/comment/${if (like) "like" else "unlike"}", body).first == 200
        }

    /**
     * **发表评论 / 回复某条评论**（真写服务端）。
     *
     * 端点：weapi `https://music.163.com/weapi/resource/comments/add`，
     * 失败再走 eapi `interface(3) /eapi/resource/comments/add`。与 NeteaseCloudMusicApi 一致：
     * - `type=1` 固定为歌曲；
     * - `threadId` = `R_SO_4_` + 歌曲 id；
     * - `content` 正文，**网易云的表情就是正文里的方括号文本**（如 `[开心]`），
     *   所以表情不需要任何接口，客户端直接拼字符串即可；
     * - `replyCommentId` + `replyToUserId` 非 0 时即为「回复」，网易云会把它渲染成楼中楼。
     *
     * 服务端成功时会回吐 `comment` 字段（新评论对象），直接拿来插进列表最准 ——
     * 免得自己拼一条假数据（commentId、userId、time 全是编的，回复/删除立刻会失效）。
     */
    suspend fun postComment(
        songId: String,
        content: String,
        replyToCommentId: String = "",
        replyToUserId: Long = 0L,
    ): PostResult = withContext(Dispatchers.IO) {
        val cookie = accountStore.currentNeteaseCookie
        if (cookie.isNullOrBlank()) {
            return@withContext PostResult.Failure("请先登录网易云账号")
        }
        val text = content.trim()
        if (text.isEmpty()) return@withContext PostResult.Failure("评论内容不能为空")
        if (text.length > MAX_COMMENT_LEN) {
            return@withContext PostResult.Failure("评论最多 $MAX_COMMENT_LEN 字")
        }
        val threadId = threadIdFor(songId)
        val body = buildMap {
            put("type", 1)
            put("threadId", threadId)
            put("content", text)
            put("csrf_token", csrfOrFail().orEmpty())
            // 网易云前端对回复就是塞这两个字段：0 表示不是回复
            put("replyCommentId", replyToCommentId.toLongOrNull() ?: 0L)
            put("replyToUserId", replyToUserId)
        }
        // 端点是 `/api/resource/comments/add`（不是 `/api/comment/add` —— 后者已 404，
        // 2026-10-11 真机实测：/api/comment/add 三通道全 code=404「接口未找到」）。
        val (code, raw) = writeJson("/resource/comments/add", body)
        Log.d(TAG, "发表 code=$code replyTo=$replyToCommentId body=${raw.take(200)}")
        if (code != 200) {
            return@withContext PostResult.Failure(postFailReason(code))
        }
        val root = runCatching { gson.fromJson(raw, JsonObject::class.java) }.getOrNull()
        // 服务端返回 comment（没有就退而用 code 判成功，列表里用本地乐观那条）
        val commentObj = root?.get("comment")?.takeIf { it.isJsonObject }?.asJsonObject
        val mine = commentObj?.let { parseOne(it) }
        val newId = commentObj?.let { str(it, "commentId") }.orEmpty()
            .ifBlank { mine?.id.orEmpty() }
        PostResult.Success(newId, mine)
    }

    /**
     * 删除自己发的评论（真删服务端）。
     *
     * 端点：weapi `https://music.163.com/weapi/resource/comments/delete`，
     * 参数 `threadId` + `commentId` + `csrf_token`。只有删自己的才会 200，
     * 删别人的会返回 40102「请勿删除他人评论」—— 所以 UI 只在自己评论上给删除按钮。
     */
    suspend fun deleteComment(songId: String, commentId: String): Boolean =
        withContext(Dispatchers.IO) {
            val csrf = csrfOrFail() ?: return@withContext false
            val body = mapOf(
                "threadId" to threadIdFor(songId),
                "commentId" to commentId,
                "csrf_token" to csrf,
            )
            val (code, raw) = writeJson("/resource/comments/delete", body)
            Log.d(TAG, "删除评论 code=$code id=$commentId body=${raw.take(160)}")
            code == 200
        }

    /**
     * 写接口统一走：登录 Cookie + 安卓身份 + weapi → eapi → 裸 /api 三通道。
     *
     * 两条铁律都是真机踩出来的：
     * 1. 必须带登录 Cookie（匿名一律 301 / -460 / -462）；
     * 2. 必须 `os=android` + 安卓 UA —— 带 `os=pc` 一律 code=250「系统检测到您的设备存在安全风险」，
     *    我们在 App 里却自称 PC 客户端本身就是风控特征（读接口用 os=pc 反而没事）。
     *
     * [path] 一律传**不带 /api 前缀**的路径（如 `/resource/comments/add`）：
     * 三个通道各自拼前缀（weapi/、eapi/、裸 /api），若这里就把 `/api` 带上，
     * weapi 通道会拼成 `/weapi/api/...` 直接 404。
     *
     * @return 第一个返回 200 的通道的 (code, 原始 body)；全挂则回最后一个响应的 code（多半是 250/301）。
     */
    private fun writeJson(path: String, body: Map<String, Any?>): Pair<Int, String> {
        val login = accountStore.currentNeteaseCookie
        if (login.isNullOrBlank()) return 0 to ""
        val cookie = androidCookie(login)
        var last = 0 to ""
        fun take(tag: String, text: String): Boolean {
            val code = codeOf(text)
            last = code to text
            Log.d(TAG, "写接口[$tag] path=$path code=$code body=${text.take(120)}")
            return code == 200
        }
        // ① weapi：官方客户端同款，命中率最高
        if (take("weapi", weapiPost("https://music.163.com/weapi$path", body, cookie, ANDROID_UA))) {
            return last
        }
        // ② eapi：某些网络下 weapi 域名被风控，interface 域名仍然放行
        for (host in EAPI_HOSTS) {
            if (take("eapi", eapiPost("$host/eapi$path", path, body, cookie, ANDROID_UA))) {
                return last
            }
        }
        // ③ 裸 /api 路径 + weapi 加密：老版本服务端就认这个
        if (take("api", weapiPost("https://music.163.com/api$path", body, cookie, ANDROID_UA))) return last
        return last
    }

    /** 网易云常见失败码 → 中文提示。-460/-462 是风控，40102 是删别人的评论。 */
    private fun postFailReason(code: Int): String = when (code) {
        0 -> "发送失败：网络异常或登录态已过期"
        -460, -462, 250 -> "发送失败：被网易云风控拦截，请稍后再试"
        301 -> "发送失败：请先登录网易云账号"
        400 -> "发送失败：内容被网易云拒绝（可能含违规词）"
        40102 -> "只能删除自己发布的评论"
        40501 -> "发送失败：评论频率过快，稍等一下"
        else -> "发送失败（code=$code）"
    }

    /** 取 csrf_token；未登录返回 null，由调用方决定提示什么。 */
    private fun csrfOrFail(): String? {
        val cookie = accountStore.currentNeteaseCookie ?: return null
        if (cookie.isNullOrBlank()) return null
        val csrf = csrfOf(cookie)
        if (csrf.isBlank()) return null
        return csrf
    }

    /** 从响应里取 code：解析不出来（空 body / 风控 HTML）返回 0。 */
    private fun codeOf(text: String): Int {
        if (text.isBlank()) return 0
        return runCatching {
            gson.fromJson(text, JsonObject::class.java)?.get("code")?.takeIf { it.isJsonPrimitive }?.asInt
        }.getOrNull() ?: 0
    }

    /** weapi 写接口要带 csrf_token，值就是登录 Cookie 里的 `__csrf`。 */
    private fun csrfOf(cookie: String): String {
        cookie.split(";").forEach { seg ->
            val t = seg.trim()
            if (t.startsWith("__csrf=")) return t.substringAfter("__csrf=")
        }
        return ""
    }

    /**
     * 把登录 Cookie 改写成**安卓客户端**身份（`os=android`）。
     *
     * 实测必须这么干：同一账号、同一参数，带 `os=pc` 打点赞返回 250「设备存在安全风险」，
     * 改成 `os=android` 就是 200。我们在安卓 App 里却自称 PC 客户端，本身就是风险特征。
     */
    private fun androidCookie(base: String): String {
        val parts = LinkedHashMap<String, String>()
        base.split(";").forEach { seg ->
            val t = seg.trim()
            val i = t.indexOf('=')
            if (i > 0) parts[t.substring(0, i).trim()] = t.substring(i + 1).trim()
        }
        parts["os"] = "android"
        parts["appver"] = ANDROID_APPVER
        return parts.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private suspend fun fetchRaw(path: String, songId: String, arrayKey: String): List<Comment> =
        withContext(Dispatchers.IO) {
            val root = requestJson(
                path = path,
                eapiPath = path.substringBefore('?'),
                eapiBody = mapOf("rid" to songId, "offset" to 0, "limit" to 20, "total" to false),
            )
            parseList(root?.get(arrayKey)?.takeIf { it.isJsonArray }?.asJsonArray ?: root?.get("comments")?.takeIf { it.isJsonArray }?.asJsonArray)
        }

    /**
     * 三通道取评论：裸 GET → weapi → eapi（interface / interface3），谁先返回内容用谁。
     *
     * 为什么要三条：评论区是**匿名可读**接口，但网易云对不同网络出口（家宽 / 移动 / 机房 IP）
     * 的态度不一样 —— 有的放行裸 GET、有的只对加密通道放行、还有的只认 interface 域名。
     * 只用一条通道时，表现为"你这台机器能看评论，别人装的就全是空的"，
     * 且失败时 total 也是 0，连「共 N 条」都不显示。三通道实测（2026-10）均可用，
     * 串行尝试的代价只是多几次网络往返，换来的是跨网络稳定性。
     */
    private fun requestJson(path: String, eapiPath: String, eapiBody: Map<String, Any?>): JsonObject? {
        val cookie = requestCookie()
        parse(get("https://music.163.com$path", cookie))?.let {
            Log.d(TAG, "评论命中通道=裸GET path=$path")
            return it
        }
        parse(weapiPost("https://music.163.com$eapiPath", eapiBody, cookie))?.let {
            Log.d(TAG, "评论命中通道=weapi path=$eapiPath")
            return it
        }
        for (host in EAPI_HOSTS) {
            parse(eapiPost("$host/eapi${eapiPath.removePrefix("/api")}", eapiPath, eapiBody, cookie))?.let {
                Log.d(TAG, "评论命中通道=eapi host=$host path=$eapiPath")
                return it
            }
        }
        Log.w(TAG, "评论三条通道全部失败 path=$path")
        return null
    }

    private fun parse(text: String): JsonObject? {
        if (text.isBlank()) return null
        val root = runCatching { gson.fromJson(text, JsonObject::class.java) }.getOrNull() ?: return null
        // 风控响应（-462）没有 comments 字段，视为失败以便走 eapi 兜底
        return if (root.has("comments") || root.has("hotComments")) root else null
    }

    private fun parsePage(root: JsonObject?, arrayKey: String): CommentPage {
        if (root == null) return CommentPage()
        return CommentPage(
            total = root.get("total")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0,
            comments = parseList(root.get(arrayKey)?.takeIf { it.isJsonArray }?.asJsonArray),
            more = root.get("more")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
            hotMore = root.get("moreHot")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
        )
    }

    private fun parseList(
        arr: com.google.gson.JsonArray?,
        hot: Boolean = false,
    ): List<Comment> {
        if (arr == null) return emptyList()
        return arr.mapNotNull { el ->
            val o = runCatching { el.asJsonObject }.getOrNull() ?: return@mapNotNull null
            parseOne(o, hot)
        }
    }

    /** 单条评论对象 → [Comment]；正文为空返回 null（服务端偶发空壳评论）。 */
    private fun parseOne(o: JsonObject, hot: Boolean = false): Comment? {
        val content = str(o, "content").orEmpty()
        if (content.isBlank()) return null
        val user = o.get("user")?.takeIf { it.isJsonObject }?.asJsonObject
        val replied = o.get("beReplied")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { r ->
            val ro = runCatching { r.asJsonObject }.getOrNull() ?: return@mapNotNull null
            val ru = ro.get("user")?.takeIf { it.isJsonObject }?.asJsonObject
            val rc = str(ro, "content").orEmpty()
            if (rc.isBlank()) null
            else RepliedComment(str(ru ?: JsonObject(), "nickname").orEmpty().ifBlank { "该用户" }, rc)
        }.orEmpty()

        return Comment(
            id = str(o, "commentId") ?: str(o, "id") ?: content.hashCode().toString(),
            source = CommentSource.NETEASE,
            userId = long(user ?: JsonObject(), "userId"),
            userName = str(user ?: JsonObject(), "nickname").orEmpty().ifBlank { "匿名用户" },
            avatarUrl = str(user ?: JsonObject(), "avatarUrl").orEmpty(),
            content = content.replace("\\n", "\n"),
            likeCount = int(o, "likedCount"),
            liked = bool(o, "liked"),
            replyCount = int(o, "replyCount"),
            timeText = formatTime(long(o, "time")),
            createdMs = long(o, "time"),
            ipLocation = o.get("ipLocation")?.takeIf { it.isJsonObject }?.asJsonObject
                ?.let { str(it, "location") }.orEmpty(),
            beReplied = replied,
            hot = hot,
            vipType = int(user ?: JsonObject(), "vipType"),
        )
    }

    /** eapi 通道：POST 到 interface / interface3 的 /eapi 前缀路径。 */
    private fun eapiPost(
        url: String,
        signPath: String,
        body: Map<String, Any?>,
        cookie: String,
        ua: String = UA,
    ): String {
        return postForm(url, NeteaseCrypto.eapi(signPath, body), cookie, ua)
    }

    /** weapi 通道：官方客户端同款加密（params + encSecKey），风控时命中率最高。 */
    private fun weapiPost(
        url: String,
        body: Map<String, Any?>,
        cookie: String,
        ua: String = UA,
    ): String {
        return postForm(url, NeteaseCrypto.weapi(body), cookie, ua)
    }

    private fun postForm(url: String, form: Map<String, String>, cookie: String, ua: String = UA): String {
        // params 是 Base64（含 + / =），必须逐个 URL 编码，否则服务端解不出签名
        val encoded = form.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, CHARSET)}=${URLEncoder.encode(v, CHARSET)}"
        }
        val request = Request.Builder()
            .url(url)
            .post(encoded.toRequestBody(FORM))
            .header("User-Agent", ua)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Referer", "https://music.163.com/")
            .header("Origin", "https://music.163.com")
            .header("Cookie", cookie)
            .build()
        return runCatching {
            client.newCall(request).execute().use { if (it.isSuccessful) it.body?.string().orEmpty() else "" }
        }.getOrDefault("")
    }

    private fun get(url: String, cookie: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Referer", "https://music.163.com/")
            .header("Cookie", cookie)
            .get()
            .build()
        return runCatching {
            client.newCall(request).execute().use { if (it.isSuccessful) it.body?.string().orEmpty() else "" }
        }.getOrDefault("")
    }

    /**
     * 请求用 Cookie：已登录就用登录态（风控概率显著更低，且能拿到自己的点赞状态），
     * 否则退回官方客户端风格的匿名 Cookie（os / appver 是必需字段，缺失会被当野客户端）。
     */
    private fun requestCookie(): String {
        val login = accountStore.currentNeteaseCookie
        if (login.isNullOrBlank()) {
            return "__remember_me=true; NMTID=${randomHex(16)}; os=pc; appver=8.9.70"
        }
        return if (login.contains("os=")) login else "$login; os=pc; appver=8.9.70"
    }

    private fun str(o: JsonObject, key: String): String? =
        o.get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun int(o: JsonObject, key: String): Int =
        runCatching { o.get(key)?.asInt }.getOrDefault(0) ?: 0

    private fun long(o: JsonObject, key: String): Long =
        runCatching { o.get(key)?.asLong }.getOrDefault(0L) ?: 0L

    private fun bool(o: JsonObject, key: String): Boolean =
        runCatching { o.get(key)?.asBoolean }.getOrDefault(false) ?: false

    /** 评论区时间要"刚刚 / 12分钟前 / 昨天 18:20 / 2024-03-01"，日期越近越口语。 */
    private fun formatTime(ms: Long): String {
        if (ms <= 0L) return ""
        val now = System.currentTimeMillis()
        val diff = now - ms
        return when {
            diff < 60_000L -> "刚刚"
            diff < 3_600_000L -> "${diff / 60_000L}分钟前"
            diff < 86_400_000L -> "${diff / 3_600_000L}小时前"
            diff < 172_800_000L -> "昨天 ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))}"
            diff < 31_536_000_000L -> SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
            else -> SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(ms))
        }
    }

    private fun randomHex(n: Int): String {
        val bytes = ByteArray(n / 2)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val TAG = "NeteaseComment"
        /** 精彩评论一次要多少条：网易云一次给不满就是没有更多了。 */
        const val HOT_LIMIT = 50
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        /** 写接口（点赞）必须自称安卓客户端，否则一律 250「设备存在安全风险」。 */
        const val ANDROID_UA = "Mozilla/5.0 (Linux; Android 12; 25060RK16C) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 NeteaseMusic"
        const val ANDROID_APPVER = "8.9.70"
        const val CHARSET = "UTF-8"
        val FORM = "application/x-www-form-urlencoded".toMediaType()
        val EAPI_HOSTS = listOf(
            "https://interface.music.163.com",
            "https://interface3.music.163.com",
        )
    }
}
