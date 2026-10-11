package com.puremusic.ui.login

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.puremusic.data.AccountStore
import com.puremusic.model.Account
import com.puremusic.model.MusicSourceType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PhoneLoginViewModel @Inject constructor(
    private val qqLogin: QqLoginManager,
    private val accountStore: AccountStore,
) : ViewModel() {

    var phone by mutableStateOf("")
    var code by mutableStateOf("")
    var status by mutableStateOf("")
    var loading by mutableStateOf(false)
    var loggedIn by mutableStateOf(false)
    var securityUrl by mutableStateOf<String?>(null)

    fun sendCode() {
        val p = phone.trim()
        if (p.isBlank()) {
            status = "请输入手机号"
            return
        }
        viewModelScope.launch {
            loading = true
            status = "正在发送验证码…"
            handleResult(qqLogin.sendAuthCode(p))
            loading = false
        }
    }

    fun resendCode(cookie: String) {
        val p = phone.trim()
        if (p.isBlank()) return
        viewModelScope.launch {
            loading = true
            status = "正在重新发送验证码…"
            handleResult(qqLogin.sendAuthCode(p, cookie))
            loading = false
        }
    }

    private fun handleResult(result: QqLoginManager.AuthCodeResult) {
        when (result.status) {
            "SENT" -> {
                status = "验证码已发送，请查收短信"
                securityUrl = null
            }
            "CAPTCHA" -> {
                status = "请先完成滑块验证"
                securityUrl = result.securityUrl
            }
            else -> status = "发送失败，请重试"
        }
    }

    fun login(onSuccess: () -> Unit) {
        val p = phone.trim()
        val c = code.trim()
        if (p.isBlank() || c.isBlank()) {
            status = "请输入手机号和验证码"
            return
        }
        viewModelScope.launch {
            loading = true
            status = "正在登录…"
            val result = qqLogin.phoneLogin(p, c)
            if (result.status == "DONE" && result.musickey.isNotBlank()) {
                accountStore.save(
                    Account(
                        source = MusicSourceType.QQ,
                        userId = result.musicid,
                        cookie = result.cookie,
                        // 一并持久化续期凭证，供后续免登录自动续期
                        refreshKey = result.refreshKey,
                        refreshToken = result.refreshToken,
                        credentialExpiresAt = result.expiresAt,
                    )
                )
                loggedIn = true
                onSuccess()
            } else {
                // 优先展示服务端分类错误提示（设备数超限/操作频繁等），无则用兜底文案
                status = result.message.takeIf { it.isNotBlank() }
                    ?.let { "登录失败：$it" }
                    ?: "登录失败：验证码错误或已过期"
            }
            loading = false
        }
    }
}
