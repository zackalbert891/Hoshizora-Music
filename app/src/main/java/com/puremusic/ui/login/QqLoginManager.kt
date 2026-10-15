package com.puremusic.ui.login

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.puremusic.model.Account
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.UUID
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/** QQ 音乐登录结果。 */
data class QqLoginResult(
    val status: String,          // WAITING / SCANNED / CONFIRMED / EXPIRED / REJECTED / ERROR
    val musicid: String = "",
    val musickey: String = "",
    val cookie: String = "",     // 完整 cookie（含 uin 等）
    val message: String = "",    // 失败 / 拒绝时的分类提示，可直接展示给用户
    // —— 以下字段用于凭证持久化与自动续期（登录 / 续期响应中一并提取）——
    val refreshKey: String = "",
    val refreshToken: String = "",
    val accessToken: String = "",
    val openid: String = "",
    val unionid: String = "",
    val encryptUin: String = "",
    val expiresAt: Long = 0,     // musickey 过期时间（Unix 秒，0 表示未知）
)

@Singleton
class QqLoginManager @Inject constructor(
    @Named("plain") private val client: OkHttpClient,
) {
    private val gson = Gson()

    // check_sig / authorize 需要拿到重定向前的响应（cookie 与 Location 头）
    private val noRedirectClient = client.newBuilder().followRedirects(false).build()

    /** QQ 会话 cookie（skey/p_skey/ptcz 等），VIP 判定需要。 */
    @Volatile
    private var sessionCookies: String = ""

    /** 获取登录二维码，返回 PNG 字节 + qrsig。 */
    suspend fun getQrCode(): Pair<ByteArray, String> = withContext(Dispatchers.IO) {
        val url = "https://ssl.ptlogin2.qq.com/ptqrshow" +
            "?appid=716027609&e=2&l=M&s=3&d=72&v=4&t=${Math.random()}" +
            "&daid=383&pt_3rd_aid=100497308"
        val resp = client.newCall(
            Request.Builder().url(url).header("Referer", "https://xui.ptlogin2.qq.com/").build()
        ).execute()
        val qrsig = extractCookie(resp, "qrsig")
        val bytes = resp.body?.bytes() ?: ByteArray(0)
        bytes to qrsig
    }

    /** 轮询登录状态，返回状态与（完成时）的 uin/ptsigx。 */
    suspend fun poll(qrsig: String): PollResult = withContext(Dispatchers.IO) {
        val ptqrtoken = hash33(qrsig)
        val url = "https://ssl.ptlogin2.qq.com/ptqrlogin" +
            "?u1=https://graph.qq.com/oauth2.0/login_jump&ptqrtoken=$ptqrtoken" +
            "&ptredirect=0&h=1&t=1&g=1&from_ui=1&ptlang=2052" +
            "&action=0-0-${System.currentTimeMillis()}&js_ver=20102616&js_type=1" +
            "&pt_uistyle=40&aid=716027609&daid=383&pt_3rd_aid=100497308&has_onekey=1"
        val resp = client.newCall(
            Request.Builder().url(url)
                .header("Referer", "https://xui.ptlogin2.qq.com/")
                .header("Cookie", "qrsig=$qrsig")
                .build()
        ).execute()
        val text = resp.body?.string()
            ?: return@withContext PollResult("ERROR", null, null, "网络异常，未能获取扫码状态")

        val args = parsePtuiArgs(text)
        val code = args.getOrNull(0)
            ?: return@withContext PollResult("ERROR", null, null, "扫码状态响应异常")
        // ptuiCB 第 5 个参数为服务端提示文本，作为分类提示的兜底
        val hint = args.getOrNull(4).orEmpty().trim()
        when {
            code == "0" -> {
                sessionCookies = joinCookies(resp)
                val redirect = args.getOrNull(2).orEmpty()
                val uin = Regex("""uin=([^&]+)""").find(redirect)?.groupValues?.get(1)
                val ptsigx = Regex("""ptsigx=([^&]+)""").find(redirect)?.groupValues?.get(1)
                PollResult("DONE", uin, ptsigx)
            }
            // 65：二维码已失效（TIMEOUT），由上层自动换取新码
            code == "65" -> PollResult("EXPIRED", null, null, hint.ifBlank { "二维码已过期" })
            // 66：已扫码待确认
            code == "66" -> PollResult("SCANNED", null, null)
            // 67：已在手机端确认，凭证换取中
            code == "67" -> PollResult("CONFIRMED", null, null)
            // 68：已在手机端拒绝本次登录
            code == "68" ->
                PollResult("REJECTED", null, null, hint.ifBlank { "已在手机端拒绝本次登录" })
            // 1~5、7~9 为明确失败码，直接返回提示；其余未知码保持 WAITING 继续轮询
            code in setOf("1", "2", "3", "4", "5", "7", "8", "9") ->
                PollResult("ERROR", null, null, hint.ifBlank { "扫码登录失败（状态码 $code）" })
            else -> PollResult("WAITING", null, null)
        }
    }

    /** 用扫码结果换取 QQ 音乐凭证（musicid/musickey）。 */
    suspend fun authorize(uin: String, ptsigx: String): QqLoginResult = withContext(Dispatchers.IO) {
        // 1) check_sig 拿 p_skey
        val checkUrl = "https://ssl.ptlogin2.graph.qq.com/check_sig" +
            "?uin=$uin&pttype=1&service=ptqrlogin&nodirect=0&ptsigx=$ptsigx" +
            "&s_url=https://graph.qq.com/oauth2.0/login_jump&ptlang=2052&ptredirect=100" +
            "&aid=716027609&daid=383&j_later=0&low_login_hour=0&regmaster=0" +
            "&pt_login_type=3&pt_aid=0&pt_aaid=16&pt_light=0&pt_3rd_aid=100497308"
        val checkResp = noRedirectClient.newCall(
            Request.Builder().url(checkUrl).header("Referer", "https://xui.ptlogin2.qq.com/").build()
        ).execute()
        val pSkey = extractCookie(checkResp, "p_skey")
        val checkCookies = joinCookies(checkResp)
        if (pSkey.isEmpty()) {
            return@withContext QqLoginResult("ERROR", message = "未能获取 p_skey，请重新扫码登录")
        }

        // 2) graph.qq.com authorize 拿 code
        val gtk = hash33(pSkey, 5381)
        val form = FormBody.Builder()
            .add("response_type", "code")
            .add("client_id", "100497308")
            .add("redirect_uri", "https://y.qq.com/portal/wx_redirect.html?login_type=1&surl=https://y.qq.com/")
            .add("scope", "get_user_info,get_app_friends")
            .add("state", "state")
            .add("switch", "")
            .add("from_ptlogin", "1")
            .add("src", "1")
            .add("update_auth", "1")
            .add("openapi", "1010_1030")
            .add("g_tk", gtk.toString())
            .add("auth_time", System.currentTimeMillis().toString())
            .add("ui", UUID.randomUUID().toString())
            .build()
        val authResp = noRedirectClient.newCall(
            Request.Builder().url("https://graph.qq.com/oauth2.0/authorize")
                .post(form)
                .header("Referer", "https://graph.qq.com/")
                .header("Cookie", checkCookies)
                .build()
        ).execute()
        val location = authResp.header("Location").orEmpty()
        val code = Regex("""code=([^&]+)""").find(location)?.groupValues?.get(1)
            ?: return@withContext QqLoginResult("ERROR", message = "授权未返回 code，请重试")

        // 3) QQ音乐 CGI 用 code 换凭证
        val cgiJson = """{"req_0":{"module":"QQConnectLogin.LoginServer","method":"QQLogin","param":{"code":"$code"}},"comm":{"tmeLoginType":2,"format":"json","ct":23,"cv":0}}"""
        val cgiResp = client.newCall(
            Request.Builder().url("https://u.y.qq.com/cgi-bin/musicu.fcg")
                .post(cgiJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .header("Referer", "https://y.qq.com/")
                .build()
        ).execute()
        val cgiText = cgiResp.body?.string().orEmpty()
        // 登录响应含敏感凭证（musickey / p_skey 等），仅解析状态，不打印原文
        // 扫码走 QQ 互联 OAuth：登录类型固定为 2（第三方授权）
        val result = parseCredential(cgiText, loginType = LOGIN_TYPE_QQ)
        // 合并 QQ 会话 cookie（p_skey/skey/ptcz 等），VIP 判定需要完整 cookie
        val extra = listOf(checkCookies, sessionCookies).filter { it.isNotBlank() }.joinToString("; ")
        // 真实 QQ 号（uin）必须写入 cookie：vkey 取流（CgiGetVkey）的 param.uin / comm.uin 要用它，
        // 不能用 musicid（开放平台 id），否则版权歌一律取流失败。
        val cleanUin = uin.trim().removePrefix("o")
        val uinCookie = cleanUin.takeIf { it.isNotBlank() }
            ?.let { "uin=$it; ptui_loginuin=$it" }.orEmpty()
        return@withContext result.copy(
            cookie = listOf(result.cookie, uinCookie, extra)
                .filter { it.isNotBlank() }.joinToString("; "),
        )
    }

    private suspend fun cgiPost(json: String, cookie: String): String? = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url("https://u.y.qq.com/cgi-bin/musicu.fcg")
            .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Referer", "https://y.qq.com/")
        if (cookie.isNotBlank()) builder.header("Cookie", cookie)
        runCatching { client.newCall(builder.build()).execute().body?.string() }.getOrNull()
    }

    /**
     * @param loginType 本次登录的账号体系类型，会写进 cookie 供后续取流沿用。
     */
    private fun parseCredential(
        json: String,
        failPrefix: String = "获取登录凭证失败",
        loginType: Int = LOGIN_TYPE_QQ,
    ): QqLoginResult {
        // 完整响应必须可观测：服务端到底发了哪些鉴权字段，直接决定取流能否带上
        // 正确的票据。缺 uin / qm_keyst 的表现就是「登录成功但 VIP 歌 101404」。
        android.util.Log.e("QqVipProbe", "登录/续期响应原文: ${json.take(3000)}")
        val resp = runCatching {
            gson.fromJson(normalizeCgiJson(json), LoginResp::class.java)
        }.getOrNull() ?: return QqLoginResult("ERROR", message = "登录响应解析失败")
        val code = resp.req_0?.code ?: -1
        val d = resp.req_0?.data
        if (d == null || d.musickey.isNullOrBlank()) {
            // 失败时按 QQ 音乐错误码给出可读提示（如 20279 设备数超限 / 104604 操作频繁）
            val rawMsg = resp.req_0?.msg?.takeIf { it.isNotBlank() } ?: extractErrorMsg(json)
            return QqLoginResult("ERROR", message = credentialErrorHint(code, rawMsg, failPrefix))
        }
        val expiresAt = when {
            d.musickeyCreateTime > 0 && d.keyExpiresIn > 0 -> d.musickeyCreateTime + d.keyExpiresIn
            d.expired_at > 0 -> d.expired_at
            else -> 0L
        }
        val cookie = listOf(
            "musicid=${d.musicid}",
            "musickey=${d.musickey}",
            "openid=${d.openid.orEmpty()}",
            "refresh_token=${d.refresh_token.orEmpty()}",
            "access_token=${d.access_token.orEmpty()}",
            "unionid=${d.unionid.orEmpty()}",
            "encryptUin=${d.encryptUin.orEmpty()}",
            // 登录类型必须持久化：取流（CgiGetVkey）的 comm.tmeLoginType 要与登录时一致。
            "tmeLoginType=$loginType",
            "loginType=$loginType",
            "loginMethod=${loginMethodOf(loginType)}",
            // guid 必须在登录时就固定下来并持久化。
            // 取流（CgiGetVkey）要求 guid 与登录态一致，否则服务端判为异常请求返回 101404；
            // 以前没有这个字段，取流只能临时用一个进程级随机 guid —— 重启即变，权益时好时坏。
            "guid=${newGuid()}",
        ).joinToString("; ")
        return QqLoginResult(
            status = "DONE",
            musicid = d.musicid.toString(),
            musickey = d.musickey.orEmpty(),
            cookie = cookie,
            refreshKey = d.refresh_key.orEmpty(),
            refreshToken = d.refresh_token.orEmpty(),
            accessToken = d.access_token.orEmpty(),
            openid = d.openid.orEmpty(),
            unionid = d.unionid.orEmpty(),
            encryptUin = d.encryptUin.orEmpty(),
            expiresAt = expiresAt,
        )
    }

    /**
     * 用已持久化的 refresh_key / refresh_token 续期（免重复登录）。
     *
     * 协议依据：开源项目 luren-dc/QQMusicApi 的 LoginApi.refresh_credential —— 服务
     * `music.login.LoginServer`、方法 `Login`；QQ（login_type=2）需携带
     * openid / access_token / refresh_token / musickey / refresh_key，
     * 返回结构与登录一致（data.musickey / refresh_token / refresh_key / keyExpiresIn 等）。
     */
    suspend fun refreshCredential(account: Account): QqLoginResult = withContext(Dispatchers.IO) {
        val cookie = account.cookie.orEmpty()
        val musickey = cookieField(cookie, "musickey")
        val refreshKey = account.refreshKey.orEmpty().ifBlank { cookieField(cookie, "refresh_key") }
        val refreshToken =
            account.refreshToken.orEmpty().ifBlank { cookieField(cookie, "refresh_token") }
        if (musickey.isBlank() || (refreshKey.isBlank() && refreshToken.isBlank())) {
            return@withContext QqLoginResult(
                "ERROR", message = "缺少 refresh_key，无法自动续期，请重新登录",
            )
        }
        // 续期必须沿用原账号的登录体系，否则类型被改写，取流时与登录态对不上。
        val savedType = cookieField(cookie, "tmeLoginType").toIntOrNull() ?: LOGIN_TYPE_QQ
        val param = JsonObject().apply {
            addProperty("openid", cookieField(cookie, "openid"))
            addProperty("access_token", cookieField(cookie, "access_token"))
            addProperty("refresh_token", refreshToken)
            addProperty("musickey", musickey)
            addProperty("refresh_key", refreshKey)
            addProperty("str_musicid", account.userId)
            addProperty("loginType", savedType)
        }
        val req = JsonObject().apply {
            addProperty("module", "music.login.LoginServer")
            addProperty("method", "Login")
            add("param", param)
        }
        val comm = JsonObject().apply {
            addProperty("tmeLoginType", savedType)
            addProperty("tmeLoginMethod", loginMethodOf(savedType))
            addProperty("format", "json")
            addProperty("ct", 23)
            addProperty("cv", 0)
        }
        val body = JsonObject().apply {
            add("req_0", req)
            add("comm", comm)
        }
        val text = cgiPost(gson.toJson(body), cookie)
            ?: return@withContext QqLoginResult("ERROR", message = "网络异常，凭证续期请求失败")
        // 完整响应必须可观测：登录/续期到底给了哪些字段（uin / qm_keyst / qqmusic_key 等），
        // 直接决定取流能不能带上正确的鉴权票据 —— 少了哪个都表现为「有会员却 101404」。
        android.util.Log.e("QqVipProbe", "续期完整响应(type=$savedType): $text")
        // 续期响应含敏感凭证，仅解析状态，不打印原文。
        // 登录类型沿用原账号的，保证续期前后账号体系一致。
        val result = parseCredential(
            text,
            failPrefix = "凭证续期失败",
            loginType = savedType,
        )
        if (result.status != "DONE") return@withContext result
        // 续期只更新鉴权相关字段，会话 cookie（uin / guid / p_skey 等）保持原样
        val merged = mergeCookie(
            cookie,
            mapOf(
                "musicid" to result.musicid,
                "musickey" to result.musickey,
                "openid" to result.openid,
                "refresh_token" to result.refreshToken,
                "refresh_key" to result.refreshKey,
                "access_token" to result.accessToken,
                "unionid" to result.unionid,
                "encryptUin" to result.encryptUin,
            ),
        )
        result.copy(cookie = merged)
    }

    /** 登录态是否仍有效（凭据过期时间未知时视为有效，留待业务请求遇错时刷新）。 */
    fun isCredentialExpired(account: Account?): Boolean {
        val a = account ?: return true
        if (a.cookie.isBlank()) return true
        val exp = a.credentialExpiresAt
        return exp > 0 && exp * 1000L <= System.currentTimeMillis()
    }

    /** 兼容两种响应形态：musicu.fcg 的 req_0 包装，或 Tars 风格的顶层 music.login.LoginServer.Login。 */
    private fun normalizeCgiJson(json: String): String {
        if (json.isBlank() || json.contains("\"req_0\"")) return json
        val root = runCatching { gson.fromJson(json, JsonObject::class.java) }.getOrNull() ?: return json
        val node = root.get("music.login.LoginServer.Login")
        if (node == null || !node.isJsonObject) return json
        return gson.toJson(JsonObject().apply { add("req_0", node) })
    }

    /** 从 cookie 串中读取单个键值。 */
    private fun cookieField(cookie: String, name: String): String =
        Regex("(?:^|;)\\s*$name=([^;]*)").find(cookie)?.groupValues?.get(1).orEmpty()

    /** 以旧 cookie 为底，用新凭证字段覆盖同名键。 */
    private fun mergeCookie(oldCookie: String, updates: Map<String, String>): String {
        val map = LinkedHashMap<String, String>()
        oldCookie.split(";").forEach { seg ->
            val t = seg.trim()
            if (t.isEmpty()) return@forEach
            val key = t.substringBefore("=").trim()
            if (key.isEmpty()) return@forEach
            map[key] = t.substringAfter("=", "")
        }
        updates.forEach { (k, v) -> if (v.isNotBlank()) map[k] = v }
        return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /** 凭证接口错误码 → 用户可读的分类提示。 */
    private fun credentialErrorHint(
        code: Int,
        rawMsg: String,
        failPrefix: String = "获取登录凭证失败",
    ): String = when (code) {
        20279 -> "登录设备数已达上限，请先在 QQ 音乐 App 的设备管理中移除多余设备"
        104604 -> "操作过于频繁，请稍后再试"
        else -> rawMsg.ifBlank { "${failPrefix}（错误码 $code）" }
    }

    /** 从原始 JSON 中兜底提取错误描述文本。 */
    private fun extractErrorMsg(json: String): String =
        Regex(""""(?:errMsg|errorMsg|msg)"\s*:\s*"([^"]*)"""")
            .find(json)?.groupValues?.get(1).orEmpty()

    private fun extractCookie(resp: Response, name: String): String {
        for (header in resp.headers("Set-Cookie")) {
            for (part in header.split(";")) {
                val t = part.trim()
                if (t.startsWith("$name=")) return t.substringAfter("=")
            }
        }
        return ""
    }

    private fun joinCookies(resp: Response): String {
        val list = mutableListOf<String>()
        for (header in resp.headers("Set-Cookie")) {
            val name = header.substringBefore("=").trim()
            val value = header.substringBefore(";").substringAfter("=")
            list.add("$name=$value")
        }
        return list.joinToString("; ")
    }

    /** 解析 ptuiCB('...','...',...) 中的参数。 */
    private fun parsePtuiArgs(text: String): List<String> {
        val start = text.indexOf('(')
        val end = text.lastIndexOf(')')
        if (start < 0 || end < start) return emptyList()
        val inner = text.substring(start + 1, end)
        val args = mutableListOf<String>()
        var i = 0
        while (i < inner.length) {
            if (inner[i] == '\'') {
                val j = inner.indexOf('\'', i + 1)
                if (j < 0) break
                args.add(inner.substring(i + 1, j))
                i = j + 1
            } else {
                i++
            }
        }
        return args
    }

    companion object {
        /**
         * 账号体系类型。写进 cookie 供 QqMusicSource 取流时沿用。
         *
         * 两套账号体系，取流时 comm.tmeLoginType 必须与登录时一致。
         * 注意：二者**都能正常使用会员权益**（实测 type=2 扫码登录同样取到
         * FLAC 无损），此前「扫码不带权益」的判断是被 ct/cv 参数误导的误判。
         */
        const val LOGIN_TYPE_NATIVE = 0   // 手机号 / 原生账号体系
        const val LOGIN_TYPE_QQ = 2       // QQ 互联 OAuth 扫码

        /** 与登录类型配套的 tmeLoginMethod（取流 comm 需一并带上）。 */
        fun loginMethodOf(loginType: Int): Int =
            if (loginType == LOGIN_TYPE_NATIVE) 3 else 2

        /** 生成 QQ 惯例的 10 位数字 guid（登录时固定并写入 cookie，取流沿用）。 */
        fun newGuid(): String = (1000000000L..9999999999L).random().toString()

        fun hash33(s: String, h: Int = 0): Int {
            var result = h
            for (c in s) result = (result shl 5) + result + c.code
            return 0x7FFFFFFF and result
        }
    }

    data class PollResult(
        val status: String,      // WAITING / SCANNED / CONFIRMED / EXPIRED / REJECTED / ERROR
        val uin: String?,
        val ptsigx: String?,
        val message: String = "",  // 分类提示（过期 / 拒绝 / 失败原因）
    )


    data class LoginResp(val code: Int = 0, val req_0: LoginReq0? = null)

    data class LoginReq0(val code: Int = 0, val data: LoginData? = null, val msg: String? = null)

    data class LoginData(
        val musicid: Long = 0,
        val musickey: String? = null,
        val openid: String? = null,
        val refresh_token: String? = null,
        val access_token: String? = null,
        val expired_at: Long = 0,
        val unionid: String? = null,
        val str_musicid: String? = null,
        val refresh_key: String? = null,
        val encryptUin: String? = null,
        // 凭证有效期字段：musickeyCreateTime + keyExpiresIn（常为 259200 = 3 天）
        val musickeyCreateTime: Long = 0,
        val keyExpiresIn: Long = 0,
    )
}
