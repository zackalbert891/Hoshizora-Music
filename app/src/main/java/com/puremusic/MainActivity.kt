package com.puremusic

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.puremusic.data.AccountStore
import com.puremusic.model.Account
import com.puremusic.model.MusicSourceType
import com.puremusic.source.netease.NeteaseAuth
import com.puremusic.ui.AppRoot
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var neteaseAuth: NeteaseAuth
    @Inject lateinit var accountStore: AccountStore

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 忽略结果 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Android 13+ 需要运行时授权，媒体通知才能显示。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            AppRoot()
        }

        handleDeepLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    /**
     * 调试/导入通道：`puremusic://netease/cookie?c=<Cookie>`。
     *
     * 存在的意义：网易云网页版 Cookie 长达数百字符，手机上手动粘贴极不现实
     * （且电脑剪贴板与手机不互通）。有了这个入口，电脑上一条
     * `adb shell am start -d "puremusic://netease/cookie?c=..."` 就能把登录态直接灌进 App，
     * 省掉所有手工输入。Cookie 仍然走 NeteaseAuth.importCookie 的完整校验，
     * 缺 MUSIC_U 会照常报错，不会写入无效登录态。
     */
    private fun handleDeepLink(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != SCHEME) return
        if (uri.host != HOST_NETEASE || uri.path != PATH_COOKIE) return
        val raw = uri.getQueryParameter("c")?.trim().orEmpty()
        if (raw.isBlank()) return

        lifecycleScope.launch {
            val result = runCatching { neteaseAuth.importCookie(raw) }.getOrNull()
            val msg = if (result?.success == true) {
                accountStore.save(
                    Account(
                        source = MusicSourceType.NETEASE,
                        userId = result.profile?.userId?.toString().orEmpty(),
                        nickname = result.profile?.nickname.orEmpty(),
                        avatarUrl = result.profile?.avatarUrl.orEmpty(),
                        cookie = result.cookie,
                    )
                )
                "网易云 Cookie 导入成功${result.profile?.nickname?.let { "：$it" } ?: ""}"
            } else {
                "导入失败：${result?.message.orEmpty().ifBlank { "未知错误" }}"
            }
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
        }
    }

    private companion object {
        const val SCHEME = "puremusic"
        const val HOST_NETEASE = "netease"
        const val PATH_COOKIE = "/cookie"
    }
}
