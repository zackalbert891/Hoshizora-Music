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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val qqLogin: QqLoginManager,
    private val accountStore: AccountStore,
) : ViewModel() {

    var qrBytes by mutableStateOf<ByteArray?>(null)
        private set
    var status by mutableStateOf("正在加载二维码…")
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var loggedIn by mutableStateOf(false)
        private set

    private var job: Job? = null

    init {
        start()
    }

    /** 二维码过期后的自动刷新次数上限，避免长时间无人扫码时无限轮询。 */
    private val maxAutoRefresh = 5

    fun start() {
        job?.cancel()
        error = null
        loggedIn = false
        qrBytes = null
        job = viewModelScope.launch {
            var refreshCount = 0
            while (isActive) {
                status = if (refreshCount == 0) "正在加载二维码…" else "二维码已过期，正在刷新…"
                val (bytes, qrsig) = runCatching { qqLogin.getQrCode() }.getOrElse { e ->
                    status = "获取二维码失败"
                    error = e.message
                    return@launch
                }
                if (qrsig.isEmpty() || bytes.isEmpty()) {
                    status = "获取二维码失败"
                    error = "qrsig 为空"
                    return@launch
                }
                qrBytes = bytes
                status = "请用 QQ 扫一扫"

                // 内层轮询：二维码过期时跳出，由外层自动换取新码继续，无需用户手动刷新
                var expired = false
                while (isActive) {
                    delay(2000)
                    val r = runCatching { qqLogin.poll(qrsig) }.getOrNull()
                    when (r?.status) {
                        "SCANNED" -> status = "已扫码，请在手机上确认"
                        "CONFIRMED" -> status = "已确认，正在登录处理中…"
                        "EXPIRED" -> expired = true
                        "REJECTED" -> {
                            status = "已拒绝登录"
                            error = r?.message.orEmpty().ifBlank { "已在手机端拒绝本次登录" }
                            return@launch
                        }
                        "DONE" -> {
                            status = "正在获取凭证…"
                            val uin = r?.uin
                            val ptsigx = r?.ptsigx
                            if (uin != null && ptsigx != null) {
                                val cred = runCatching { qqLogin.authorize(uin, ptsigx) }.getOrNull()
                                if (cred != null && cred.status == "DONE" && cred.musickey.isNotBlank()) {
                                    accountStore.save(
                                        Account(
                                            source = MusicSourceType.QQ,
                                            userId = cred.musicid,
                                            cookie = cred.cookie,
                                            // 一并持久化续期凭证，供后续免登录自动续期
                                            refreshKey = cred.refreshKey,
                                            refreshToken = cred.refreshToken,
                                            credentialExpiresAt = cred.expiresAt,
                                        )
                                    )
                                    loggedIn = true
                                } else {
                                    status = "登录失败"
                                    error = cred?.message.orEmpty().ifBlank { "获取凭证失败" }
                                }
                            } else {
                                status = "登录失败"
                                error = "解析扫码结果失败"
                            }
                            return@launch
                        }
                        "ERROR" -> {
                            status = "登录出错，请重试"
                            error = r?.message.orEmpty().ifBlank { "扫码登录失败，请重试" }
                            return@launch
                        }
                        else -> { /* WAITING，继续轮询 */ }
                    }
                    if (expired) break
                }
                if (!expired) return@launch
                refreshCount++
                qrBytes = null
                if (refreshCount > maxAutoRefresh) {
                    status = "二维码多次过期，请点击刷新"
                    return@launch
                }
            }
        }
    }

    override fun onCleared() {
        job?.cancel()
    }
}
