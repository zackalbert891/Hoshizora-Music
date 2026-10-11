package com.puremusic.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.puremusic.data.AccountStore
import com.puremusic.data.SettingsStore
import com.puremusic.model.Account
import com.puremusic.model.MusicSourceType
import com.puremusic.model.ThemeConfig
import com.puremusic.ui.login.QqLoginManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/** QQ 音乐登录态展示信息。 */
data class QqLoginStatus(
    val loggedIn: Boolean = false,
    val valid: Boolean = false,
    val text: String = "未登录（VIP 歌曲需登录）",
    val error: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsStore: SettingsStore,
    private val accountStore: AccountStore,
    private val qqLogin: QqLoginManager,
) : ViewModel() {

    private val _qqLoginStatus = MutableStateFlow(QqLoginStatus())
    /** QQ 音乐登录态（进入设置页自动检查一次；过期时尝试自动续期）。 */
    val qqLoginStatus: StateFlow<QqLoginStatus> = _qqLoginStatus.asStateFlow()

    init {
        viewModelScope.launch {
            // 等首次磁盘读取完成，避免把「尚未加载」误判为未登录
            accountStore.loaded.first { it }
            checkQqLogin()
        }
    }

    /** 手动重新检查登录态（过期则尝试自动续期，失败才提示重新登录）。 */
    fun recheckQqLogin() {
        viewModelScope.launch { checkQqLogin() }
    }

    private suspend fun checkQqLogin() {
        val account = accountStore.accounts.value[MusicSourceType.QQ]
        if (account == null || account.cookie.isBlank()) {
            _qqLoginStatus.value = QqLoginStatus()
            return
        }
        if (!qqLogin.isCredentialExpired(account)) {
            _qqLoginStatus.value = QqLoginStatus(
                loggedIn = true, valid = true, text = "登录有效",
            )
            return
        }
        _qqLoginStatus.value = QqLoginStatus(
            loggedIn = true, valid = false, text = "登录已过期，正在自动续期…",
        )
        val result = runCatching { qqLogin.refreshCredential(account) }.getOrNull()
        if (result != null && result.status == "DONE" && result.musickey.isNotBlank()) {
            accountStore.save(
                account.copy(
                    userId = result.musicid.ifBlank { account.userId },
                    cookie = result.cookie,
                    refreshKey = result.refreshKey.ifBlank { account.refreshKey.orEmpty() },
                    refreshToken = result.refreshToken.ifBlank { account.refreshToken.orEmpty() },
                    credentialExpiresAt = result.expiresAt.takeIf { it > 0 }
                        ?: account.credentialExpiresAt,
                )
            )
            _qqLoginStatus.value = QqLoginStatus(
                loggedIn = true, valid = true, text = "登录有效（已自动续期）",
            )
        } else {
            val hint = result?.message?.takeIf { it.isNotBlank() }
                ?: "自动续期失败，请重新登录"
            _qqLoginStatus.value = QqLoginStatus(
                loggedIn = true, valid = false, text = "登录已过期", error = hint,
            )
        }
    }

    val themeConfig = settingsStore.themeConfig
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeConfig())

    val backgroundImagePath = settingsStore.backgroundImagePath
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 播放页专用背景。 */
    val playerBackgroundPath = settingsStore.playerBackgroundPath
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val accounts = accountStore.accounts

    val svipEnabled = settingsStore.svipEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val serverUrl = settingsStore.serverUrl
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsStore.DEFAULT_SERVER_URL)

    fun saveServerUrl(url: String) {
        var normalized = url.trim()
        if (normalized.isBlank()) return
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            normalized = "http://$normalized"
        }
        normalized = normalized.trimEnd('/')
        viewModelScope.launch { settingsStore.setServerUrl(normalized) }
    }

    fun setSvip(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setSvip(enabled) }
    }

    fun updateTheme(config: ThemeConfig) {
        viewModelScope.launch { settingsStore.setThemeConfig(config) }
    }

    fun importBackground(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val dest = File(context.filesDir, "background.jpg")
            val ok = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                } != null
            }.getOrDefault(false)
            if (ok) settingsStore.setBackgroundImage(dest.absolutePath)
        }
    }

    /**
     * 存框选好的背景图（音乐库/设置页）。[bitmap] 由界面层的 [ImageCropDialog] 产出。
     *
     * 存完必须 recycle：框选时为了画质把原图降采样到 2048px 再裁完还留在手里，
     * 不回收就是白白多占十几 MB。
     */
    fun saveCroppedBackground(bitmap: android.graphics.Bitmap) {
        viewModelScope.launch(Dispatchers.IO) {
            val path = com.puremusic.ui.components.saveBitmapAsJpeg(context, bitmap, "background.jpg")
            bitmap.recycle()
            if (path != null) settingsStore.setBackgroundImage(path)
        }
    }

    /** 存框选好的播放页背景图。 */
    fun saveCroppedPlayerBackground(bitmap: android.graphics.Bitmap) {
        viewModelScope.launch(Dispatchers.IO) {
            val path = com.puremusic.ui.components.saveBitmapAsJpeg(
                context, bitmap, "player_background.jpg",
            )
            bitmap.recycle()
            if (path != null) settingsStore.setPlayerBackground(path)
        }
    }

    fun clearBackground() {
        viewModelScope.launch { settingsStore.setBackgroundImage(null) }
    }

    /** 导入播放页背景图（与音乐库背景分开存，互不干扰）。 */
    fun importPlayerBackground(uri: Uri) {
        viewModelScope.launch {
            val path = settingsStore.copyToInternalFile(uri, "player_background.jpg")
            settingsStore.setPlayerBackground(path)
        }
    }

    fun clearPlayerBackground() {
        viewModelScope.launch { settingsStore.setPlayerBackground(null) }
    }

    fun logout(source: MusicSourceType) {
        viewModelScope.launch { accountStore.remove(source) }
    }

    fun saveCookie(cookie: String) {
        val trimmed = cookie.trim()
        if (trimmed.isBlank()) return
        val uin = Regex("""(?:uin|p_uin)=o?0*(\d+)""").find(trimmed)
            ?.groupValues?.get(1)
            ?: Regex("""musicid=(\d+)""").find(trimmed)?.groupValues?.get(1).orEmpty()
        viewModelScope.launch {
            accountStore.save(
                Account(source = MusicSourceType.QQ, userId = uin, cookie = trimmed)
            )
        }
    }
}
