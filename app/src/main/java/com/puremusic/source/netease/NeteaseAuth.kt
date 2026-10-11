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
        // 通道 1/2：interface / interface3 的 eapi（本机与多数网络实测正常）
        val eapiForm = NeteaseCrypto.eapi(UNIKEY_PATH, mapOf("type" to 1))
        for (host in EAPI_HOSTS) {
            val text = post("$host/eapi/login/qrcode/unikey", eapiForm, cookie)
            val key = runCatching {
                gson.fromJson(text, UnikeyResponse::class.java)
            }.getOrNull()?.takeIf { it.code == 200 }?.keyOrNull()
            if (!key.isNullOrBlank()) return@withContext key
        }
        // 通道 3：weapi（可能被风控返回空 body）
        val weapiText = post(
            "https://music.163.com/weapi/login/qrcode/unikey",
            NeteaseCrypto.weapi(mapOf("type" to 1)),
            cookie,
        )
        runCatching { gson.fromJson(weapiText, UnikeyResponse::class.java) }
            .getOrNull()?.takeIf { it.code == 200 }?.keyOrNull()
    }

    /** 轮询扫码结果；803 成功时从 Set-Cookie 取登录态。 */
    suspend fun poll(unikey: String, verifyToken: String? = null): NeteaseQrStatus = withContext(Dispatchers.IO) {
        ensureAnonymousToken()
        val cookie = buildList {
            add(baseCookie())
            if (!verifyToken.isNullOrBlank()) add("verifyToken=$verifyToken")
        }.joinToString("; ")
        val candidates = EAPI_HOSTS.map { host ->
            Triple(host, POLL_PATH, NeteaseCrypto.eapi(POLL_PATH, mapOf("key" to unikey, "type" to 1)))
        } + Triple(
            "https://music.163.com",
            POLL_PATH,
            NeteaseCrypto.eapi(POLL_PATH, mapOf("key" to unikey, "type" to 1)),
        )

        // eapi 全被风控（-462）时的兜底：weapi 通道。
        // -462 是 IP/设备维度的风控码，真机移动网络同样会命中；此时换 weapi 签名
        // （不同的加密通道与 Referer 组合）有一定概率放行，值得一试。
        val allCandidates = candidates + listOf(
            Triple("__weapi__", POLL_PATH, NeteaseCrypto.weapi(mapOf("key" to unikey, "type" to 1))),
        )

        var lastMsg = ""
        var lastRisk: NeteaseRiskControl? = null
        for ((host, _, form) in allCandidates) {
            val url = if (host == "__weapi__") {
                "https://music.163.com/weapi/login/qrcode/client/login"
            } else {
                "$host/eapi/login/qrcode/client/login"
            }
            val resp = runCatching { postRaw(url, form, cookie) }.getOrNull()
                ?: continue
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

    // ---------------------------------------------------------------- 手机号登录

    /**
     * 发送短信验证码。
     *
     * 端点必须是 **`/weapi/sms/captcha/sent`**（实测：打 `/api/sms/captcha/sent` 恒返回
     * 400「参数错误」—— `/api/` 前缀的端点走明文参数，不吃 weapi 加密体）。
     * eapi 作为被风控时的兜底通道。
     */
    suspend fun sendCaptcha(phone: String): NeteaseLoginResult = withContext(Dispatchers.IO) {
        val cookie = baseCookie()
        val body = mapOf("ctcode" to "86", "cellphone" to phone)
        val resp = runCatching {
            postRaw("https://music.163.com/weapi/sms/captcha/sent", NeteaseCrypto.weapi(body), cookie)
        }.getOrNull()
        val parsed = parseSimple(resp?.body.orEmpty())
        if (parsed.first == 200) {
            return@withContext NeteaseLoginResult(true, message = "验证码已发送")
        }
        // 兜底：eapi 通道（实测 interface3 也能发成功）
        for (host in EAPI_HOSTS) {
            val eapi = NeteaseCrypto.eapi("/api/sms/captcha/sent", body)
            val eapiBody = runCatching { post("$host/eapi/sms/captcha/sent", eapi, cookie) }.getOrDefault("")
            val eapiParsed = parseSimple(eapiBody)
            if (eapiParsed.first == 200) {
                return@withContext NeteaseLoginResult(true, message = "验证码已发送")
            }
            if (eapiParsed.second != null) {
                return@withContext NeteaseLoginResult(
                    false,
                    message = riskHint(eapiParsed.first, "验证码发送失败"),
                    risk = eapiParsed.second,
                )
            }
        }
        NeteaseLoginResult(
            false,
            message = riskHint(parsed.first, "验证码发送失败，可改用「一键登录」"),
            risk = parsed.second,
        )
    }

    /**
     * 解析「只有 code / 可能带风控 data」的响应。
     * @return (code, 风控信息)
     */
    private fun parseSimple(body: String): Pair<Int, NeteaseRiskControl?> {
        if (body.isBlank()) return 0 to null
        val json = runCatching { gson.fromJson(body, RiskResponse::class.java) }.getOrNull()
            ?: return 0 to null
        return (json.code ?: 0) to json.riskOrNull()
    }

    /** 手机号 + 验证码登录。[verifyToken] 为风控验证通过后重试时带的凭证。 */
    suspend fun loginByCaptcha(phone: String, captcha: String, verifyToken: String? = null): NeteaseLoginResult =
        cellphoneLogin(phone, captcha = captcha, verifyToken = verifyToken)

    /** 手机号 + 密码登录（密码在本地做 MD5，不上传明文）。 */
    suspend fun loginByPassword(phone: String, password: String, verifyToken: String? = null): NeteaseLoginResult =
        cellphoneLogin(phone, passwordMd5 = NeteaseCrypto.md5(password), verifyToken = verifyToken)

    private suspend fun cellphoneLogin(
        phone: String,
        captcha: String? = null,
        passwordMd5: String? = null,
        verifyToken: String? = null,
    ): NeteaseLoginResult = withContext(Dispatchers.IO) {
        val base = buildList {
            add("__remember_me=true")
            add("NMTID=${randomHex(16)}")
            add("os=ios")
            add("appver=8.20.21")
            if (!verifyToken.isNullOrBlank()) add("verifyToken=$verifyToken")
        }.joinToString("; ")
        val body = LinkedHashMap<String, Any>().apply {
            put("phone", phone)
            put("countrycode", "86")
            put("rememberLogin", "true")
            if (!captcha.isNullOrBlank()) put("captcha", captcha)
            else put("password", passwordMd5.orEmpty())
            // 风控验证通过后重试：body 与 cookie 两处都带，官方客户端就是这么做的
            if (!verifyToken.isNullOrBlank()) put("verifyToken", verifyToken)
        }
        // weapi 主通道（官方客户端就是这个）
        val resp = runCatching {
            postRaw("https://music.163.com/weapi/login/cellphone", NeteaseCrypto.weapi(body), base)
        }.getOrNull()
        val parsed = parseLogin(resp, base)
        if (parsed.success || parsed.risk != null) return@withContext parsed
        // eapi 兜底
        val eapiResp = runCatching {
            postRaw(
                "${EAPI_HOSTS.first()}/eapi/login/cellphone",
                NeteaseCrypto.eapi("/api/login/cellphone", body),
                base,
            )
        }.getOrNull()
        val eapiParsed = parseLogin(eapiResp, base, fallbackMessage = parsed.message)
        if (eapiParsed.risk != null) eapiParsed else parsed
    }

    private suspend fun parseLogin(
        resp: Resp?,
        baseCookie: String,
        fallbackMessage: String = "",
    ): NeteaseLoginResult {
        val body = resp?.body.orEmpty()
        if (body.isBlank()) {
            return NeteaseLoginResult(false, message = "登录接口无响应，请改用「一键登录」")
        }
        val json = runCatching { gson.fromJson(body, RiskResponse::class.java) }.getOrNull()
        val code = json?.code
        if (code != 200) {
            return NeteaseLoginResult(
                false,
                message = riskHint(code, fallbackMessage.ifBlank { "登录失败" }),
                risk = json?.riskOrNull(),
            )
        }
        val cookie = mergeCookies(baseCookie, resp?.setCookies.orEmpty())
        // 与扫码登录同一道校验：缺 MUSIC_U 就只是游客 cookie，不能报成功
        if (!cookie.contains("MUSIC_U")) {
            return NeteaseLoginResult(
                false,
                message = "登录成功但未拿到登录凭证（MUSIC_U），请改用「一键登录」",
            )
        }
        val profile = runCatching { gson.fromJson(body, ProfileResponse::class.java) }
            .getOrNull()?.takeIf { it.code == 200 }?.profile
        return NeteaseLoginResult(true, cookie = cookie, profile = profile)
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

    private fun get(url: String, cookie: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Referer", "https://music.163.com/")
            .apply { if (cookie.isNotBlank()) header("Cookie", cookie) }
            .get()
            .build()
        return runCatching {
            client.newCall(request).execute().use { if (it.isSuccessful) it.body?.string().orEmpty() else "" }
        }.getOrDefault("")
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

    /** 风控码 / 常见错误码 → 可读提示。 */
    private fun riskHint(code: Int?, fallback: String): String = when (code) {
        -460 -> "检测到你的网络环境存在风险（-460），请完成安全验证后再试"
        -462 -> "需要先通过网易云安全校验（-462）"
        400 -> "参数错误（400）"
        405 -> "操作太频繁（405），请稍后再试"
        502 -> "账号或验证码错误（502）"
        509 -> "验证码错误或已过期（509）"
        200 -> "成功"
        else -> if (code == null) fallback else "$fallback（code=$code）"
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
