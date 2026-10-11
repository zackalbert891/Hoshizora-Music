package com.puremusic.ui.login

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.puremusic.data.AccountStore
import com.puremusic.model.Account
import com.puremusic.model.MusicSourceType
import com.puremusic.source.netease.NeteaseAuth
import com.puremusic.source.netease.NeteaseLoginResult
import com.puremusic.source.netease.NeteaseProfile
import com.puremusic.source.netease.NeteaseQrStatus
import com.puremusic.source.netease.NeteaseRiskControl
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 网易云登录方式：一键登录（官网授权） / 扫码 / 手机号 / Cookie 导入。 */
enum class NeteaseLoginTab(val label: String) {
    WEB("一键登录"),
    QR("扫码登录"),
    PHONE("手机号"),
    COOKIE("Cookie 导入"),
}

/**
 * 网易云登录 ViewModel：多种登录路径统一在此调度。
 *
 * ## 为什么把「一键登录」排第一
 * 网易云的**第三方客户端登录接口**（扫码轮询 / 短信 / 密码）有 IP 与设备维度的 -462 风控，
 * 换台手机、换个网络就可能直接失败。而官网登录页是网易云自己维护的，
 * 不存在"第三方调用"这个前提 —— 用户在 WebView 里正常登录，
 * 我们从 CookieManager 里取回 `MUSIC_U`，成功率与在浏览器里登录完全一样。
 * 用户也不用去找第二台设备扫码、更不用抓包复制 Cookie。
 *
 * 扫码 / Cookie 导入保留为兜底：前者适合已装网易云 App 的人，后者适合自动化场景。
 */
@HiltViewModel
class NeteaseLoginViewModel @Inject constructor(
    private val auth: NeteaseAuth,
    private val accountStore: AccountStore,
) : ViewModel() {

    var tab by mutableStateOf(NeteaseLoginTab.WEB)
        private set

    // -------- 通用 --------
    var status by mutableStateOf("")
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var loggedIn by mutableStateOf(false)
        private set

    // -------- 扫码 --------
    var qrBitmap by mutableStateOf<Bitmap?>(null)
        private set

    // -------- 手机号 --------
    var phone by mutableStateOf("")
    var code by mutableStateOf("")
    var password by mutableStateOf("")
    var usePassword by mutableStateOf(false)
        private set
    var codeSent by mutableStateOf(false)
        private set

    // -------- Cookie --------
    var cookieInput by mutableStateOf("")

    // -------- 风控 --------
    /**
     * 命中 -460/-462 时网易云返回的安全验证信息（验证页地址 + token）。
     * 非 null 时登录页弹出官方验证页，用户验证完点「我已完成」即可带着 token 重试。
     */
    var risk by mutableStateOf<NeteaseRiskControl?>(null)
        private set

    /** 风控验证页是否展开：默认不自动弹，让用户先看清楚错误提示再决定。 */
    var riskVisible by mutableStateOf(false)
        private set

    /** 上次失败的那个登录动作，验证通过后原样重放（传入 verifyToken）。 */
    private var pendingAction: (suspend (String?) -> NeteaseLoginResult)? = null

    /** 风控验证通过后拿到的 token，重放登录动作时带上。 */
    private var verifyToken: String? = null

    // -------- 一键登录（WebView 官网授权） --------
    /** 已经从网页拿到 cookie 并开始校验，避免 onPageFinished 反复触发。 */
    private var webHandled = false

    private var qrJob: Job? = null

    init {
        // 默认页是一键登录，不浪费一次二维码请求；切到扫码页时再取。
    }

    fun selectTab(t: NeteaseLoginTab) {
        tab = t
        error = null
        risk = null
        riskVisible = false
        if (t != NeteaseLoginTab.QR) qrJob?.cancel()
        if (t == NeteaseLoginTab.QR && qrBitmap == null && !loggedIn) startQr()
    }

    fun showRisk() {
        riskVisible = true
    }

    /** 关掉风控验证页（不重试）。 */
    fun dismissRisk() {
        riskVisible = false
    }

    /**
     * 用户在官方验证页完成安全验证后调用：记住 token，原样重放刚才失败的那个登录动作。
     *
     * 网易云的 verifyToken 是与这次风控绑定的一次性凭证，验证通过后用它重试登录才会放行。
     */
    fun retryAfterVerify(token: String? = null) {
        riskVisible = false
        val action = pendingAction ?: run {
            // 扫码没有单个"登录动作"，重启轮询即可（会带上 token）
            risk = null
            if (tab == NeteaseLoginTab.QR) startQr()
            return
        }
        if (!token.isNullOrBlank()) verifyToken = token
        risk = null
        viewModelScope.launch {
            busy = true
            error = null
            status = "验证完成，正在重试登录…"
            val result = runCatching { action(verifyToken) }.getOrNull()
            busy = false
            if (result?.success == true) {
                finish(result.cookie, result.profile)
            } else {
                risk = result?.risk
                status = "登录失败"
                error = result?.message.orEmpty().ifBlank { "登录失败" }
            }
        }
    }

    /**
     * WebView 里检测到登录态（cookie 中含 MUSIC_U）时回调。
     *
     * 每次页面加载完成都会调一次，所以要么成功、要么失败都只处理一次：
     * 成功的会直接跳转离开；失败的（比如 cookie 已过期）也只提示一次，
     * 之后用户重新登录会触发新的页面加载，这里再放行。
     */
    fun onWebCookie(raw: String) {
        if (webHandled || loggedIn || busy) return
        if (!raw.contains("MUSIC_U")) return
        webHandled = true
        viewModelScope.launch {
            busy = true
            error = null
            status = "已检测到登录，正在校验…"
            val result = runCatching { auth.importCookie(raw) }.getOrNull()
            busy = false
            if (result?.success == true) {
                finish(result.cookie, result.profile)
            } else {
                status = "登录未完成"
                error = result?.message.orEmpty().ifBlank {
                    "取到的登录态无效，请在下方页面重新登录一次"
                }
                // 允许重试：用户重新登录会再次触发页面加载
                webHandled = false
            }
        }
    }

    /** 网页登录页加载失败（网络不通 / 被拦截）时的提示。 */
    fun onWebError(message: String) {
        if (loggedIn) return
        status = "页面加载失败"
        error = "$message。可改用扫码或 Cookie 导入登录"
    }

    fun togglePasswordMode() {
        usePassword = !usePassword
        error = null
    }

    // ------------------------------------------------------------- 扫码登录

    fun startQr() {
        qrJob?.cancel()
        error = null
        loggedIn = false
        qrBitmap = null
        qrJob = viewModelScope.launch {
            // 先注册匿名凭证，可显著降低后续接口命中风控的概率
            runCatching { auth.ensureAnonymousToken() }
            var refreshCount = 0
            while (isActive && refreshCount <= MAX_QR_REFRESH) {
                status = if (refreshCount == 0) "正在获取二维码…" else "二维码已过期，正在刷新…"
                val unikey = runCatching { auth.getUnikey() }.getOrNull()
                if (unikey.isNullOrBlank()) {
                    status = "获取二维码失败"
                    error = "网易云接口未返回二维码 key，请改用「Cookie 导入」"
                    return@launch
                }
                qrBitmap = runCatching { auth.generateQr(auth.qrContent(unikey)) }.getOrNull()
                status = "请用网易云音乐 App 扫一扫"

                var expired = false
                while (isActive) {
                    delay(POLL_INTERVAL_MS)
                    when (val r = runCatching { auth.poll(unikey, verifyToken) }.getOrNull()) {
                        is NeteaseQrStatus.Waiting -> Unit
                        is NeteaseQrStatus.Scanned -> status = "已扫码，请在手机上确认"
                        is NeteaseQrStatus.Expired -> expired = true
                        is NeteaseQrStatus.Success -> {
                            status = "登录成功，正在获取账号信息…"
                            finish(r.cookie, auth.fetchProfile(r.cookie))
                            return@launch
                        }
                        is NeteaseQrStatus.Risk -> {
                            // 轮询被风控：停在这里等用户在官方验证页过一次，之后重启轮询重试
                            status = "需要完成安全验证"
                            error = r.message
                            risk = r.risk
                            pendingAction = null
                            return@launch
                        }
                        is NeteaseQrStatus.Error -> {
                            status = "登录失败"
                            error = r.message.ifBlank { "扫码失败，可改用「一键登录」" }
                            return@launch
                        }
                        null -> Unit
                    }
                    if (expired) break
                }
                refreshCount++
            }
            if (!loggedIn) {
                status = "二维码已失效"
                error = "长时间未扫码，请刷新重试或使用「Cookie 导入」"
            }
        }
    }

    // ------------------------------------------------------------- 手机号登录

    fun sendCode() {
        if (phone.isBlank()) {
            error = "请输入手机号"
            return
        }
        viewModelScope.launch {
            busy = true
            error = null
            risk = null
            status = "正在发送验证码…"
            val result = runCatching { auth.sendCaptcha(phone) }.getOrNull()
            busy = false
            if (result?.success == true) {
                codeSent = true
                status = "验证码已发送"
            } else {
                status = "发送失败"
                risk = result?.risk
                error = result?.message.orEmpty().ifBlank { "验证码发送失败" }
            }
        }
    }

    fun loginByCode() {
        if (code.isBlank()) {
            error = "请输入验证码"
            return
        }
        runLogin("正在登录…") { token -> auth.loginByCaptcha(phone, code, token) }
    }

    fun loginByPassword() {
        if (password.isBlank()) {
            error = "请输入密码"
            return
        }
        val pwd = password
        runLogin("正在登录…") { token -> auth.loginByPassword(phone, pwd, token) }
    }

    /**
     * 统一的登录动作执行器：记住这个动作（供风控验证后重放），执行，失败时把风控信息带出来。
     */
    private fun runLogin(runningText: String, block: suspend (String?) -> NeteaseLoginResult) {
        pendingAction = block
        viewModelScope.launch {
            busy = true
            error = null
            risk = null
            status = runningText
            val result = runCatching { block(verifyToken) }.getOrNull()
            busy = false
            if (result?.success == true) {
                finish(result.cookie, result.profile)
            } else {
                risk = result?.risk
                status = "登录失败"
                error = result?.message.orEmpty().ifBlank { "登录失败" }
            }
        }
    }

    // ------------------------------------------------------------- Cookie 导入

    fun importCookie() {
        if (cookieInput.isBlank()) {
            error = "请粘贴 Cookie"
            return
        }
        viewModelScope.launch {
            busy = true
            error = null
            status = "正在校验 Cookie…"
            val result = runCatching { auth.importCookie(cookieInput) }.getOrNull()
            busy = false
            if (result?.success == true) {
                finish(result.cookie, result.profile)
            } else {
                status = "Cookie 无效"
                error = result?.message.orEmpty().ifBlank { "Cookie 校验失败" }
            }
        }
    }

    // ------------------------------------------------------------- 收尾

    private suspend fun finish(cookie: String, profile: NeteaseProfile?) {
        if (cookie.isBlank()) {
            status = "登录失败"
            error = "未取到登录态 Cookie"
            return
        }
        accountStore.save(
            Account(
                source = MusicSourceType.NETEASE,
                userId = profile?.userId?.toString().orEmpty(),
                nickname = profile?.nickname.orEmpty(),
                avatarUrl = profile?.avatarUrl.orEmpty(),
                cookie = cookie,
            )
        )
        status = "登录成功${profile?.nickname?.let { "：$it" } ?: ""}"
        loggedIn = true
        qrJob?.cancel()
    }

    override fun onCleared() {
        qrJob?.cancel()
    }

    private companion object {
        /** 轮询间隔：官方客户端约 1s，第三方放宽到 3s 以降低风控概率。 */
        const val POLL_INTERVAL_MS = 3000L
        const val MAX_QR_REFRESH = 3
    }
}
