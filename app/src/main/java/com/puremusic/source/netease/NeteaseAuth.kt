package com.puremusic.source.netease

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import com.google.gson.Gson
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/** 网易云登录结果。 */
data class NeteaseLoginResult(
    val success: Boolean,
    val cookie: String = "",
    val profile: NeteaseProfile? = null,
    val message: String = "",
    /** 命中风控（-460/-462）时带回的验证信息；非 null 表示"需要用户先做安全验证"。 */
    val risk: NeteaseRiskControl? = null,
)

/**
 * 网易云风控凭证。
 *
 * 登录接口（二维码轮询 / 手机号 / 密码）命中 -462 时会返回 `data.verifyUrl` 与 `verifyToken`，
 * 官方客户端的做法是打开该验证页让用户完成滑块或短信验证，之后**用同一个 verifyToken 重试登录**
 * 即可放行。因此这里把它一路透传到 UI 层：由 UI 弹出 WebView 验证页，验证完自动重试原登录动作。
 */
data class NeteaseRiskControl(
    val verifyUrl: String,
    val verifyToken: String,
    val verifyId: String = "",
    val verifyType: Int = 0,
) {
    /** 验证页完整地址（官方拼法：verifyUrl + verifyToken / verifyId / verifyType）。 */
    val pageUrl: String
        get() {
            val base = verifyUrl.ifBlank { DEFAULT_VERIFY_URL }
            val q = mutableListOf<String>()
            if (verifyToken.isNotBlank()) q += "verifyToken=$verifyToken"
            if (verifyId.isNotBlank()) q += "verifyId=$verifyId"
            if (verifyType != 0) q += "verifyType=$verifyType"
            return if (q.isEmpty()) base else "$base?${q.joinToString("&")}"
        }

    private companion object {
        const val DEFAULT_VERIFY_URL = "https://st.music.163.com/encrypt-pages"
    }
}

/** 二维码轮询状态。 */
sealed class NeteaseQrStatus {
    object Expired : NeteaseQrStatus()
    object Waiting : NeteaseQrStatus()
    object Scanned : NeteaseQrStatus()
    data class Success(val cookie: String) : NeteaseQrStatus()
    data class Error(val message: String) : NeteaseQrStatus()
    /** 命中风控：需要用户完成安全验证后用同一 token 重试（[NeteaseRiskControl] 即验证页参数）。 */
    data class Risk(val risk: NeteaseRiskControl, val message: String) : NeteaseQrStatus()
}

/**
 * 网易云认证中心：二维码 / 短信验证码 / 密码 / Cookie 导入，四种登录方式 + 匿名凭证。
 *
 * ## 为什么重写
 * 原实现只用 `music.163.com/weapi/...`：实测该域名的 weapi 端点在多数网络下返回**空 body**
 * （服务端对非官方客户端的风控），表现为"二维码一直刷不出来 / 登录必失败"。
 * 而 `interface.music.163.com` / `interface3.music.163.com` 的 **eapi** 端点正常返回数据，
 * 因此本实现以 eapi 为主通道、weapi 为兜底，并统一补齐官方客户端必备的基础 Cookie
 * （`os` / `appver` / `NMTID` / `__remember_me` / 匿名 `MUSIC_A`）。
 *
 * ## 风控兜底
 * 网易云对登录接口有 -462 滑块风控（IP 级）。扫码/短信都可能命中，此时唯一稳定方案是
 * **Cookie 导入**（用户在网页版登录后复制 Cookie），故本类把该路径做成一等公民。
 */
@Singleton
class NeteaseAuth @Inject constructor(
    @Named("plain") private val client: OkHttpClient,
) {

    private val gson = Gson()

    /** 匿名凭证（MUSIC_A）缓存：注册一次后随基础 Cookie 一起发送，可显著降低风控概率。 */
    @Volatile
    private var anonymousMusicA: String = ""

    /** 匿名注册失败后 10 分钟内不再重试：该接口常被风控，反复打只会加重风控、拖慢登录。 */
    @Volatile
    private var anonymousFailedUntil: Long = 0L

    /** 极简 HTTP 响应承载体（登录需要读 Set-Cookie，OkHttp 的 Response 不能跨线程留存）。 */
    private data class Resp(val code: Int, val body: String, val setCookies: List<String>)

    // ---------------------------------------------------------------- 基础 Cookie

    /** 官方客户端风格的基础 Cookie：os/appver/NMTID/__remember_me + 匿名 MUSIC_A。 */
    private fun baseCookie(): String {
        val nmtid = randomHex(16)
        val parts = LinkedHashMap<String, String>().apply {
            put("__remember_me", "true")
            put("NMTID", nmtid)
            put("os", "pc")
            put("appver", "8.9.70")
            if (anonymousMusicA.isNotBlank()) put("MUSIC_A", anonymousMusicA)
        }
        return parts.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /** 在用户 cookie 之上补齐官方客户端必需字段（缺失时服务端会要求二次验证）。 */
    private fun enrichCookie(cookie: String): String {
        val map = LinkedHashMap<String, String>()
        cookie.split(";").forEach { seg ->
            val t = seg.trim()
            if (t.isEmpty()) return@forEach
            val idx = t.indexOf('=')
            if (idx <= 0) return@forEach
            map[t.substring(0, idx).trim()] = t.substring(idx + 1).trim()
        }
        if (map["os"].isNullOrBlank()) map["os"] = "pc"
        if (map["appver"].isNullOrBlank()) map["appver"] = "8.9.70"
        if (map["NMTID"].isNullOrBlank()) map["NMTID"] = randomHex(16)
        if (map["__remember_me"].isNullOrBlank()) map["__remember_me"] = "true"
        return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /**
     * 注册匿名账号，换取 MUSIC_A。
     *
     * 协议依据 NeteaseCloudMusicApi `register_anonimous`：`username` =
     * Base64(MD5(`NMUSIC<随机设备 id>` 与 magic 逐字节异或))，Cookie 带 `os=iOS`。
     *
     * 注意：这个接口本身也常被风控（返回 400）。拿到 MUSIC_A 只是"锦上添花"，
     * 拿不到不该阻塞登录，所以失败时只做冷却、不向上抛错。
     */
    suspend fun ensureAnonymousToken(): String {
        if (anonymousMusicA.isNotBlank()) return anonymousMusicA
        if (System.currentTimeMillis() < anonymousFailedUntil) return ""
        return withContext(Dispatchers.IO) {
            val username = encodedDeviceId()
            val iosCookie = "__remember_me=true; NMTID=${randomHex(16)}; os=iOS; appver=8.20.21"
            // eapi 通道
            val eapiForm = NeteaseCrypto.eapi("/api/register/anonimous", mapOf("username" to username))
            for (host in EAPI_HOSTS) {
                val musicA = extractMusicA(
                    runCatching { postRaw("$host/eapi/register/anonimous", eapiForm, iosCookie) }.getOrNull()
                )
                if (musicA.isNotBlank()) {
                    anonymousMusicA = musicA
                    return@withContext musicA
                }
            }
            // weapi 通道（/api 与 /weapi 两个前缀都试，历史上都用过）
            val form = NeteaseCrypto.weapi(mapOf("username" to username))
            for (url in listOf(
                "https://music.163.com/api/register/anonimous",
                "https://music.163.com/weapi/register/anonimous",
            )) {
                val musicA = extractMusicA(
                    runCatching { postRaw(url, form, iosCookie) }.getOrNull()
                )
                if (musicA.isNotBlank()) {
                    anonymousMusicA = musicA
                    return@withContext musicA
                }
            }
            anonymousFailedUntil = System.currentTimeMillis() + ANON_COOLDOWN_MS
            ""
        }
    }

    /** 从注册响应里提取 Set-Cookie 的 MUSIC_A（风控时为空，调用方继续走其它通道）。 */
    private fun extractMusicA(resp: Resp?): String = resp?.setCookies.orEmpty()
        .asSequence()
        .mapNotNull { h -> h.split(";").firstOrNull()?.trim() }
        .firstOrNull { it.startsWith("MUSIC_A=") }
        ?.substringAfter("MUSIC_A=")
        .orEmpty()

    /** `NMUSIC {id}` 的混淆 + Base64（与 NeteaseCloudMusicApi 的 cloudmusic_dll_encode_id 一致）。 */
    private fun encodedDeviceId(): String {
        // 官方 magic（NeteaseCloudMusicApi util/crypto.js encrypted.id）：
        // 曾经写成 "3go8&$833h0k(2)2"，少了 `8*3*3` 里的两个字符，匿名注册恒返回 400。
        val key = "3go8&\$8*3*3h0k(2)2"
        val deviceId = "NMUSIC${randomHex(8)}"
        val xored = deviceId.mapIndexed { i, c ->
            (c.code xor key[i % key.length].code).toChar()
        }.joinToString("")
        val md5 = MessageDigest.getInstance("MD5").digest(xored.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(md5, Base64.NO_WRAP)
    }

    // ---------------------------------------------------------------- 二维码登录

    /** 二维码内容：官方登录页 + codekey。 */
    fun qrContent(unikey: String): String = "https://music.163.com/login?codekey=$unikey"

    /** 生成二维码位图（本地编码，不依赖网络图片接口）。 */
    fun generateQr(content: String, size: Int = 640): Bitmap {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to 1,
        )
        val matrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val bmp = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
        for (x in 0 until matrix.width) {
            for (y in 0 until matrix.height) {
                bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }

    /** 取二维码 key：eapi 优先（实测可用），weapi 兜底。 */
    suspend fun getUnikey(): String? = withContext(Dispatchers.IO) {
        ensureAnonymousToken()
        val cookie = baseCookie()
        // 通道 1：weapi 裸域（2026-10-12 真机实测 200）
        // 登录类端点不走 eapi —— /eapi/login/qrcode/unikey 在 interface/interface3
        // 上实测均 404「接口未找到」，原因与手机号登录一致：eapi 只收 App 内部路径。
        runCatching {
            gson.fromJson(
                post("https://music.163.com/weapi$UNIKEY_PATH", NeteaseCrypto.weapi(mapOf("type" to 1)), cookie),
                UnikeyResponse::class.java,
            )
        }.getOrNull()?.takeIf { it.code == 200 }?.keyOrNull()?.let { return@withContext it }

        // 通道 2：明文 GET（免签，实测同样 200；作为weapi 被风控时的兜底）
        runCatching {
            gson.fromJson(get("https://music.163.com$UNIKEY_PATH?type=1", cookie), UnikeyResponse::class.java)
        }.getOrNull()?.takeIf { it.code == 200 }?.keyOrNull()
    }

    /** 轮询扫码结果；803 成功时从 Set-Cookie 取登录态。 */
    suspend fun poll(unikey: String, verifyToken: String? = null): NeteaseQrStatus = withContext(Dispatchers.IO) {
        ensureAnonymousToken()
        val cookie = buildList {
            add(baseCookie())
            if (!verifyToken.isNullOrBlank()) add("verifyToken=$verifyToken")
        }.joinToString("; ")
        // 通道顺序（2026-10-12 真机实测调整）：weapi 裸域 → 明文 GET → eapi。
        // 登录类端点在 interface/interface3 的 /eapi 上实测均 404「接口未找到」，
        // eapi 只收 App 内部路径，所以把它降到最后当兜底；-462 时换通道仍值得一试。
        val pollBody = mapOf("key" to unikey, "type" to 1)
        // form 为 null 表示明文 GET（免签），否则走 weapi/eapi 的 POST
        val candidates: List<Pair<String, Map<String, String>?>> = listOf(
            "https://music.163.com/weapi$POLL_PATH" to NeteaseCrypto.weapi(pollBody),
            "https://music.163.com$POLL_PATH?key=$unikey&type=1" to null,
            "https://music.163.com/eapi$POLL_PATH" to NeteaseCrypto.eapi(POLL_PATH, pollBody),
        ) + EAPI_HOSTS.map { host ->
            "$host/eapi$POLL_PATH" to NeteaseCrypto.eapi(POLL_PATH, pollBody)
        }

        var lastMsg = ""
        var lastRisk: NeteaseRiskControl? = null
        for ((url, form) in candidates) {
            val resp = if (form == null) {
                getRaw(url, cookie)
            } else {
                runCatching { postRaw(url, form, cookie) }.getOrNull()
            } ?: continue
            val body = resp.body
            if (body.isBlank()) continue
            val parsed = runCatching { gson.fromJson(body, RiskResponse::class.java) }.getOrNull()
            val code = parsed?.code
            when (code) {
                800 -> return@withContext NeteaseQrStatus.Expired
                801 -> return@withContext NeteaseQrStatus.Waiting
                802 -> return@withContext NeteaseQrStatus.Scanned
                803 -> {
                    val merged = mergeCookies(cookie, resp.setCookies)
                    if (merged.isBlank()) {
                        return@withContext NeteaseQrStatus.Error("登录成功但未取到 Cookie")
                    }
                    // 关键校验：真正的登录凭证是 MUSIC_U。eapi 跨域轮询时 Set-Cookie 可能只带回
                    // 匿名 cookie（MUSIC_A），此时界面会显示「登录成功」但实际仍是游客——
                    // VIP / 无损音源照样取不到。缺 MUSIC_U 必须报失败而不是假成功。
                    if (!merged.contains("MUSIC_U")) {
                        return@withContext NeteaseQrStatus.Error(
                            "已扫码但未拿到登录凭证（MUSIC_U），请改用「Cookie 导入」登录",
                        )
                    }
                    return@withContext NeteaseQrStatus.Success(merged)
                }
                -462, -460 -> {
                    lastMsg = if (code == -460) {
                        "检测到网络环境存在风险（-460）"
                    } else {
                        "网易云安全校验（-462）"
                    }
                    lastRisk = parsed?.riskOrNull()
                    continue
                }
                else -> lastMsg = "登录失败（code=$code）"
            }
        }
        // 命中风控时把验证页信息带回去，UI 弹出官方验证页让用户过一次，之后重试即可
        if (lastRisk != null) {
            return@withContext NeteaseQrStatus.Risk(lastRisk, lastMsg)
        }
        NeteaseQrStatus.Error(lastMsg.ifBlank { "扫码状态获取失败" })
    }

// ---------------------------------------------------------------- Cookie 导入

    /**
     * 导入已有 Cookie（网页版 / 抓包获得），并立即校验有效性。
     *
     * 这是网易云风控下最稳的登录方式：只要 cookie 里含 `MUSIC_U`，VIP 权益即可生效。
     */
    suspend fun importCookie(raw: String): NeteaseLoginResult = withContext(Dispatchers.IO) {
        val normalized = enrichCookie(normalizeCookieInput(raw))
        if (!normalized.contains("MUSIC_U") && !normalized.contains("MUSIC_A")) {
            return@withContext NeteaseLoginResult(
                false,
                message = "Cookie 中未找到 MUSIC_U，请确认复制的是 music.163.com 的完整 Cookie",
            )
        }
        val profile = fetchProfile(normalized)
            ?: return@withContext NeteaseLoginResult(false, message = "Cookie 无效或已过期")
        NeteaseLoginResult(true, cookie = normalized, profile = profile)
    }

    // ---------------------------------------------------------------- 手机号登录

    /**
     * 登录专用 Cookie：**刻意不带 os 字段**。
     *
     * 这与读接口（[baseCookie]，os=pc）和评论写接口（os=android）都不同，原因是真机实测
     * 出的硬结论（2026-10-12，DiagActivity `only=loginprobe` 四格正交对照）：
     *
     *   os=android + 安卓 UA → -462 风控拦截
     *   os=android + PC  UA → -462 风控拦截← 换 UA 无用，证明与 UA 无关
     *   无 os     + 安卓 UA → 400业务拒绝（通道没被拦）
     *   无 os+ PC  UA → 400 业务拒绝（通道没被拦）
     *
     * 即**决定成败的只有 cookie 里的 os 字段**：声称自己是安卓客户端去做登录，
     * 本身就是风控特征。别看到别处写"安卓要伪装 os=android"就照搬——那条只对评论/收藏成立。
     */
    private fun loginCookie(verifyToken: String? = null): String = buildList {
        anonymousMusicA.takeIf { it.isNotBlank() }?.let { add("MUSIC_A=$it") }
        add("__remember_me=true")
        verifyToken?.takeIf { it.isNotBlank() }?.let { add("verifyToken=$it") }
    }.joinToString("; ")

    /**
     * 发送短信验证码。
     *
     * ⚠️ 参数名是 `cellphone` + `ctcode`，不是 `phone` + `countrycode`（后者是登录接口的写法）。
     * 用错名字服务端返 400「参数错误」，很容易被误判成被风控 —— 已在真机踩过一次。
     *
     * 实测（2026-10-12）：带 os=android 也能发出去（code=200 data=true），
     * 无 os 时返回 405「发送验证码间隔过短」，说明两条路都真的把短信发出去了。
     */
    suspend fun sendCaptcha(phone: String): NeteaseLoginResult = withContext(Dispatchers.IO) {
        val text = postWeapiLogin(
            SMS_CAPTCHA_PATH,
            mapOf("cellphone" to phone.trim(), "ctcode" to "86"),
        )
        val code = codeOf(text)
        when {
            code == 200 && text.contains("\"true\"") ->
                NeteaseLoginResult(true, message = "验证码已发送")
            code == 405 -> NeteaseLoginResult(false, message = "发送过于频繁，请稍后再试")
            code == -460 || code == -462 || code == 250 ->
                NeteaseLoginResult(false, message = "被网易云风控拦截，请稍后再试")
            code == 400 ->
                NeteaseLoginResult(false, message = "手机号格式有误，请检查后重试")
            text.isBlank() ->
                NeteaseLoginResult(false, message = "网络异常，请稍后再试")
            else -> NeteaseLoginResult(false, message = "发送失败（code=$code）")
        }
    }

    /**
     * 验证码登录。
     *
     * 参数照网易云客户端：`phone` + `captcha` + `countrycode` + `rememberLogin`。
     * 成功时登录凭证在 Set-Cookie 的 MUSIC_U 里，因此必须走 [postRaw] 保留响应头。
     */
    suspend fun loginByCaptcha(
        phone: String,
        captcha: String,
        verifyToken: String? = null,
    ): NeteaseLoginResult = withContext(Dispatchers.IO) {
        val resp = postRaw(
            "https://music.163.com$LOGIN_CAPTCHA_PATH",
            NeteaseCrypto.weapi(
                mapOf(
                    "phone" to phone.trim(),
                    "captcha" to captcha.trim(),
                    "countrycode" to "86",
                    "rememberLogin" to "true",
                    "csrf_token" to "",
                )
            ),
            loginCookie(verifyToken),
        ) ?: return@withContext NeteaseLoginResult(false, message = "网络异常，请稍后再试")

        finishLogin(resp, phone)
    }

    /**
     * 密码登录。与 [loginByCaptcha] 同端点，只是把 captcha 换成 password。
     *
     * 实测：真机上无论参数怎么填，带 os 都会被 -462 拦；不带 os 能到"账号或密码错误"这一步，
     * 说明通道本身可用。因此这里不再做 os 伪装。
     */
    suspend fun loginByPassword(
        phone: String,
        password: String,
        verifyToken: String? = null,
    ): NeteaseLoginResult = withContext(Dispatchers.IO) {
        val resp = postRaw(
            "https://music.163.com$LOGIN_CAPTCHA_PATH",
            NeteaseCrypto.weapi(
                mapOf(
                    "phone" to phone.trim(),
                    "password" to password,
                    "countrycode" to "86",
                    "rememberLogin" to "true",
                    "csrf_token" to "",
                )
            ),
            loginCookie(verifyToken),
        ) ?: return@withContext NeteaseLoginResult(false, message = "网络异常，请稍后再试")

        finishLogin(resp, phone)
    }

    /** 登录响应的统一收尾：取 Cookie → 换资料 → 判风控。 */
    private suspend fun finishLogin(resp: Resp, phone: String): NeteaseLoginResult {
        val code = codeOf(resp.body)
        if (code == 200) {
            val merged = mergeCookies(loginCookie(), resp.setCookies)
            if (merged.contains("MUSIC_U")) {
                val normalized = enrichCookie(merged)
                val profile = fetchProfile(normalized)
                return NeteaseLoginResult(true, cookie = normalized, profile = profile)
            }
            return NeteaseLoginResult(
                false,
                message = "登录成功但未取到登录凭证（MUSIC_U），请改用「Cookie 导入」登录",
            )
        }
        if (code == -460 || code == -462 || code == 250) {
            val risk = runCatching { gson.fromJson(resp.body, RiskResponse::class.java) }.getOrNull()?.riskOrNull()
            return NeteaseLoginResult(
                false,
                message = "被网易云安全校验拦截，请完成验证后重试",
                risk = risk,
            )
        }
        return NeteaseLoginResult(false, message = loginFailReason(code, resp.body))
    }

    private fun loginFailReason(code: Int, body: String): String {
        val serverMsg = Regex("\"(?:msg|message)\"\\s*:\\s*\"([^\"]{0,40})").find(body)?.groupValues?.get(1)
        return when (code) {
            0 -> "网络异常或登录态异常，请稍后再试"
            400 -> serverMsg?.takeIf { it.isNotBlank() } ?: "请求被拒（code=400）"
            502, 505 -> serverMsg?.takeIf { it.isNotBlank() } ?: "账号或密码错误（code=$code）"
            301 -> "请先登录网易云账号"
            405 -> "操作过于频繁，请稍后再试"
            else -> serverMsg?.takeIf { it.isNotBlank() } ?: "登录失败（code=$code）"
        }
    }

    /** 登录类接口统一走 weapi 裸域：eapi 在 interface/interface3 上对这些路径一律 404。 */
    private fun postWeapiLogin(path: String, body: Map<String, String>): String =
        post("https://music.163.com$path", NeteaseCrypto.weapi(body), loginCookie())

    private fun codeOf(text: String): Int =
        Regex("\"code\"\\s*:\\s*(-?\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    /** 兼容直接粘贴整段请求头（含 "Cookie:" 前缀）或 name=value 列表。 */
    private fun normalizeCookieInput(raw: String): String {
        var s = raw.trim()
        s = s.replace(Regex("(?i)^cookie\\s*:\\s*"), "")
        // 多行请求头：只保留含 '=' 的行
        if (s.contains("\n")) {
            s = s.lines().firstOrNull { it.contains("=") && !it.contains(":") }?.trim()
                ?: s.lines().filter { it.contains("=") }.joinToString("; ") { it.trim() }
        }
        return s.replace(Regex("\\s+"), " ").trim().trimEnd(';')
    }

    // ---------------------------------------------------------------- 账号资料

    /** 用登录态换取账号资料（昵称/头像/uid），失败返回 null（视为 cookie 失效）。 */
    suspend fun fetchProfile(cookie: String): NeteaseProfile? = withContext(Dispatchers.IO) {
        val enriched = enrichCookie(cookie)
        // 主：/api/v1/user/detail（裸 GET，实测稳定）
        val text = get("https://music.163.com/api/v1/user/detail?_nmclfl=1", enriched)
        val parsed = runCatching { gson.fromJson(text, ProfileResponse::class.java) }.getOrNull()
        if (parsed?.code == 200 && parsed.profile?.userId != null && parsed.profile.userId > 0) {
            return@withContext parsed.profile
        }
        // 兜底：账号信息接口（返回 account + profile 两段）
        val text2 = get("https://music.163.com/api/w/nuser/account/get", enriched)
        val p2 = runCatching { gson.fromJson(text2, AccountGetResponse::class.java) }.getOrNull()
        val account = p2?.account
        if (p2?.code == 200 && account != null) {
            return@withContext NeteaseProfile(
                userId = account.id,
                nickname = account.userName ?: p2.profile?.nickname,
                avatarUrl = account.avatarUrl ?: p2.profile?.avatarUrl,
            )
        }
        null
    }

    // ---------------------------------------------------------------- HTTP 基础

    private fun post(url: String, form: Map<String, String>, cookie: String): String =
        runCatching { postRaw(url, form, cookie)?.body.orEmpty() }.getOrDefault("")

    private fun postRaw(url: String, form: Map<String, String>, cookie: String): Resp? {
        val builder = FormBody.Builder()
        form.forEach { (k, v) -> builder.add(k, v) }
        val request = Request.Builder()
            .url(url)
            .post(builder.build())
            .header("User-Agent", UA)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Referer", "https://music.163.com/")
            .header("Origin", "https://music.163.com")
            .apply { if (cookie.isNotBlank()) header("Cookie", cookie) }
            .build()
        return runCatching {
            client.newCall(request).execute().use { r ->
                Resp(r.code, r.body?.string().orEmpty(), r.headers("Set-Cookie"))
            }
        }.getOrNull()
    }

    private fun get(url: String, cookie: String): String = getRaw(url, cookie).body

    /** GET 但保留 Set-Cookie —— 二维码轮询的 803 响应要靠它拿 MUSIC_U。 */
    private fun getRaw(url: String, cookie: String): Resp {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Referer", "https://music.163.com/")
            .apply { if (cookie.isNotBlank()) header("Cookie", cookie) }
            .get()
            .build()
        return runCatching {
            client.newCall(request).execute().use {
                Resp(it.code, if (it.isSuccessful) it.body?.string().orEmpty() else "", it.headers("Set-Cookie"))
            }
        }.getOrElse { Resp(0, "", emptyList()) }
    }

    /** 把响应 Set-Cookie 与既有 cookie 合并（同名覆盖）。 */
    private fun mergeCookies(base: String, setCookies: List<String>): String {
        val map = LinkedHashMap<String, String>()
        base.split(";").forEach { seg ->
            val t = seg.trim()
            val idx = t.indexOf('=')
            if (idx > 0) map[t.substring(0, idx).trim()] = t.substring(idx + 1).trim()
        }
        setCookies.forEach { h ->
            val pair = h.split(";").firstOrNull()?.trim().orEmpty()
            val idx = pair.indexOf('=')
            if (idx > 0) {
                val name = pair.substring(0, idx).trim()
                val value = pair.substring(idx + 1).trim()
                if (name.isNotBlank() && value.isNotBlank()) map[name] = value
            }
        }
        // 过滤掉无用属性键（Path/Expires 之类不会被写进来，但保险起见）
        map.keys.removeAll(setOf("Path", "path", "Expires", "expires", "Max-Age", "Domain", "HttpOnly", "SameSite"))
        return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun randomHex(n: Int): String {
        val bytes = ByteArray(n / 2 + n % 2)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }.take(n)
    }

    /**
     * 通用响应壳：登录类接口的 `code` 与风控 `data`。
     *
     * 成功时 `data` 是账号对象（这里解析不到风控字段，[riskOrNull] 自然返回 null），
     * 命中 -460/-462 时 `data` 是 `verifyUrl / verifyToken / verifyId / verifyType`。
     */
    private data class RiskResponse(val code: Int? = null, val data: RiskData? = null) {
        fun riskOrNull(): NeteaseRiskControl? {
            val token = data?.verifyToken?.takeIf { it.isNotBlank() } ?: return null
            return NeteaseRiskControl(
                verifyUrl = data.verifyUrl.orEmpty(),
                verifyToken = token,
                verifyId = data.verifyId?.toString().orEmpty(),
                verifyType = data.verifyType ?: 0,
            )
        }
    }

    private data class RiskData(
        val verifyUrl: String? = null,
        val verifyToken: String? = null,
        val verifyId: Any? = null,
        val verifyType: Int? = null,
    )

    /** unikey 有 `unikey` 与 `codekey` 两种字段名（不同版本接口都见过），都兼容。 */
    private data class UnikeyResponse(
        val code: Int = 0,
        val unikey: String? = null,
        val codekey: String? = null,
    ) {
        fun keyOrNull(): String? = unikey?.takeIf { it.isNotBlank() } ?: codekey?.takeIf { it.isNotBlank() }
    }
    private data class ProfileResponse(val code: Int = 0, val profile: NeteaseProfile? = null)
    private data class AccountGetResponse(
        val code: Int = 0,
        val profile: NeteaseProfile? = null,
        val account: NeteaseAccount? = null,
    )
    private data class NeteaseAccount(
        val id: Long = 0,
        val userName: String? = null,
        val avatarUrl: String? = null,
    )

    private companion object {
        const val UNIKEY_PATH = "/api/login/qrcode/unikey"
        const val POLL_PATH = "/api/login/qrcode/client/login"
        const val SMS_CAPTCHA_PATH = "/weapi/sms/captcha/sent"
        const val LOGIN_CAPTCHA_PATH = "/weapi/login/cellphone"
        /** 匿名注册失败后的冷却时间：该接口常被风控，别在每个登录动作前反复打。 */
        const val ANON_COOLDOWN_MS = 10 * 60 * 1000L
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        val EAPI_HOSTS = listOf(
            "https://interface.music.163.com",
            "https://interface3.music.163.com",
        )
        val FORM = "application/x-www-form-urlencoded".toMediaType()
    }
}

/** 网易云账号资料。 */
data class NeteaseProfile(
    val userId: Long = 0,
    val nickname: String? = null,
    val avatarUrl: String? = null,
)
