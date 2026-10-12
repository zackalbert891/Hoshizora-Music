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

/** 网易云登录方式：扫码 / 手机号验证码 / Cookie 导入。 */
enum class NeteaseLoginTab(val label: String) {
    QR("扫码登录"),
    PHONE("手机号"),
    COOKIE("Cookie 导入"),
}

/**
 * 网易云登录 ViewModel：扫码、手机号验证码与 Cookie 导入三条路径在此调度。
 *
 * ## 手机号为什么回来了
 * v1.3.0 曾把它当"用不了"删掉。当时的结论是被 -460/-462 动态风控拦死，
 * 但 2026-10-12 真机正交对照（`DiagActivity -e only loginprobe`）证明**归因错了**：
 *
 *   os=android + 安卓 UA → -462      ← 换 UA 仍 -462，说明与 UA 无关
 *   os=android + PC  UA → -462
 *   无 os     + 任意 UA → 400 业务拒绝（通道本身没被拦）
 *
 * 决定成败的只有 Cookie 里的 `os` 字段：声称自己是安卓客户端去做**登录**，
 * 本身就是风控特征（读接口和评论写接口反而要伪装成安卓，两条路的规则相反）。
 * 去掉 `os` 伪装后，`/weapi/sms/captcha/sent` 实测 code=200，短信真的发得出去。
 *
 * 一键登录（WebView 官网）仍然没加回来：它拿不到 `MUSIC_U`，与风控无关。
 *
 * 扫码也常被风控拦下，所以命中 -460/-462 时不干等：把官方给的验证页拉起来，
 * 用户过一次验证后带着 verifyToken 重放同一个登录动作。
 */
@HiltViewModel
class NeteaseLoginViewModel @Inject constructor(
    private val auth: NeteaseAuth,
    private val accountStore: AccountStore,
) : ViewModel() {

    var tab by mutableStateOf(NeteaseLoginTab.QR)
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

    // -------- Cookie --------
    var cookieInput by mutableStateOf("")

    // -------- 手机号 --------
    var phoneInput by mutableStateOf("")
        private set
    var captchaInput by mutableStateOf("")
        private set

    /** 验证码是否已进入倒计时等待（发出去之后 60s 内禁用重复发送）。 */
    var captchaSent by mutableStateOf(false)
        private set

    /** 倒计时剩余秒数；0 表示可重新发送。 */
    var captchaCountdown by mutableStateOf(0)
        private set
    private var countdownJob: Job? = null

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

    /** 风控验证通过后拿到的 token，重启扫码轮询时带上。 */
    private var verifyToken: String? = null

    private var qrJob: Job? = null

    init {
        // 默认页是扫码：进页面就取二维码，不浪费用户一次点击
        startQr()
    }

    fun selectTab(t: NeteaseLoginTab) {
        tab = t
        error = null
        risk = null
        riskVisible = false
        // 离开扫码页就停掉轮询，别在后台空转打接口
        if (t != NeteaseLoginTab.QR) qrJob?.cancel()
        if (t != NeteaseLoginTab.PHONE) countdownJob?.cancel()
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
     * 用户在官方验证页完成安全验证后调用：记住 token，重启扫码轮询。
     *
     * 网易云的 verifyToken 是与这次风控绑定的一次性凭证，验证通过后用它重新取二维码才会放行。
     */
    fun retryAfterVerify(token: String? = null) {
        riskVisible = false
        if (!token.isNullOrBlank()) verifyToken = token
        risk = null
        error = null
        when (tab) {
            NeteaseLoginTab.QR -> startQr()
            // 手机号登录带 token 直接重试，省得用户重新输一遍验证码
            NeteaseLoginTab.PHONE -> if (captchaSent && captchaInput.isNotBlank()) submitCaptcha()
            NeteaseLoginTab.COOKIE -> Unit
        }
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
                            return@launch
                        }
                        is NeteaseQrStatus.Error -> {
                            status = "登录失败"
                            error = r.message.ifBlank { "扫码失败，可改用「Cookie 导入」" }
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

    // ------------------------------------------------------------- 手机号登录

    fun onPhoneChange(v: String) {
        // 只保留数字，输错字母时不用用户自己删
        phoneInput = v.filter { it.isDigit() }.take(11)
        error = null
    }

    fun onCaptchaChange(v: String) {
        captchaInput = v.filter { it.isDigit() }.take(6)
        error = null
    }

    fun sendCaptcha() {
        if (phoneInput.length != 11) {
            error = "请输入 11 位手机号"
            return
        }
        viewModelScope.launch {
            busy = true
            error = null
            status = "正在发送验证码…"
            val r = runCatching { auth.sendCaptcha(phoneInput) }.getOrNull()
            busy = false
            if (r?.success == true) {
                status = "验证码已发送，请查收短信"
                captchaSent = true
                startCountdown()
            } else {
                status = "发送失败"
                error = r?.message.orEmpty().ifBlank { "验证码发送失败，请稍后再试" }
            }
        }
    }

    private fun startCountdown() {
        countdownJob?.cancel()
        captchaCountdown = 60
        countdownJob = viewModelScope.launch {
            while (isActive && captchaCountdown > 0) {
                delay(1000)
                captchaCountdown -= 1
            }
            captchaCountdown = 0
        }
    }

    fun submitCaptcha() {
        if (phoneInput.length != 11) {
            error = "请输入 11 位手机号"
            return
        }
        if (captchaInput.length < 4) {
            error = "请输入收到的验证码"
            return
        }
        viewModelScope.launch {
            busy = true
            error = null
            status = "正在登录…"
            val r = runCatching { auth.loginByCaptcha(phoneInput, captchaInput, verifyToken) }.getOrNull()
            busy = false
            if (r?.success == true) {
                finish(r.cookie, r.profile)
            } else {
                status = "登录失败"
                error = r?.message.orEmpty().ifBlank { "登录失败，请稍后再试" }
                // 命中风控时把验证页信息带出来，让用户过完官方验证再重试
                r?.risk?.let {
                    risk = it
                    riskVisible = false
                }
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
        countdownJob?.cancel()
    }

    private companion object {
        /** 轮询间隔：官方客户端约 1s，第三方放宽到 3s 以降低风控概率。 */
        const val POLL_INTERVAL_MS = 3000L
        const val MAX_QR_REFRESH = 3
    }
}
