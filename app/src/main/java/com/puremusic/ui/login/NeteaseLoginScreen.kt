package com.puremusic.ui.login

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.puremusic.ui.theme.GlassLevel
import com.puremusic.ui.theme.UiTokens
import com.puremusic.ui.theme.glassCard
import com.puremusic.ui.theme.pressable

/** 网易云品牌红（登录页的视觉主色）。 */
private val BrandRed = Color(0xFFEC4141)
private val BrandRedDeep = Color(0xFFB92929)

/**
 * 网易云登录页。
 *
 * 四种方式并列（一键登录 / 扫码 / 手机号 / Cookie 导入），但**推荐顺序是有讲究的**：
 * 网易云对第三方客户端的登录接口有 -460/-462 风控（IP 与设备维度，换机器换网络就可能命中），
 * 扫码与短信随时可能被拦；而「一键登录」打开的是网易云自己的官网登录页，不存在这个前提，
 * 成功率与在浏览器里登录完全一致，所以默认停在它上面，文案也直说这点。
 *
 * 其它方式被风控拦下时不再干等：把官方给的验证页（verifyUrl + verifyToken）拉起来，
 * 用户过一次验证后带着 token 自动重放刚才那个登录动作。
 */
@Composable
fun NeteaseLoginScreen(onBack: () -> Unit, onSuccess: () -> Unit) {
    val vm: NeteaseLoginViewModel = hiltViewModel()

    LaunchedEffect(vm.loggedIn) {
        if (vm.loggedIn) onSuccess()
    }

    Box(Modifier.fillMaxSize()) {
        // 品牌渐变只铺上半屏，往下淡出到透明（底下的 App 背景继续透出来）
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.46f)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            BrandRed.copy(alpha = 0.92f),
                            BrandRedDeep.copy(alpha = 0.55f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )

        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                Row(
                    Modifier
                        .statusBarsPadding()
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                    Text(
                        "登录网易云音乐",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (vm.tab == NeteaseLoginTab.QR) {
                        IconButton(onClick = vm::startQr) { Icon(Icons.Filled.Refresh, "刷新") }
                    }
                }
            },
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BrandHeader()
                Spacer(Modifier.height(22.dp))
                LoginTabSegment(vm.tab) { vm.selectTab(it) }
                Spacer(Modifier.height(14.dp))
                LoginPaneCard(vm)
                Spacer(Modifier.height(16.dp))
                Text(
                    "VIP 权益来自你的账号：登录后即可播放会员曲目与无损音质",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
            }
        }

        if (vm.riskVisible) RiskVerifyOverlay(vm)
    }
}

// ------------------------------------------------------------------ 头部品牌区

@Composable
private fun BrandHeader() {
    Box(
        modifier = Modifier
            .size(78.dp)
            .shadow(10.dp, CircleShape, spotColor = BrandRed.copy(alpha = 0.35f))
            .glassCard(GlassLevel.CHIP, CircleShape, accent = BrandRed, elevation = 0.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.MusicNote,
            contentDescription = null,
            tint = BrandRed,
            modifier = Modifier.size(40.dp),
        )
    }
    Spacer(Modifier.height(16.dp))
    Text(
        "登录网易云音乐",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        "同步云端歌单、收藏与会员音质",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

// ------------------------------------------------------------------ 方式切换

/** 各页签在分段控件里显示的短名（完整 label 太长，四个挤一行会截断）。 */
private fun shortLabel(tab: NeteaseLoginTab): String = when (tab) {
    NeteaseLoginTab.WEB -> "一键"
    NeteaseLoginTab.QR -> "扫码"
    NeteaseLoginTab.PHONE -> "手机号"
    NeteaseLoginTab.COOKIE -> "Cookie"
}

/**
 * 分段控件：一整条玻璃底 + 选中项填色。
 *
 * 比 ScrollableTabRow 更适合登录页 —— 四个入口地位平等、需要一眼看全，
 * 而且不会被系统 TabRow 的下划线和固定最小宽度限制住。
 */
@Composable
private fun LoginTabSegment(
    selected: NeteaseLoginTab,
    onSelect: (NeteaseLoginTab) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .glassCard(GlassLevel.CHIP, RoundedCornerShape(26.dp), elevation = 2.dp)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeteaseLoginTab.entries.forEach { tab ->
            val isSelected = tab == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(
                        if (isSelected) {
                            Brush.horizontalGradient(listOf(BrandRed, BrandRedDeep))
                        } else {
                            Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
                        },
                    )
                    .pressable(scaleDown = 0.95f, onClick = { onSelect(tab) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    shortLabel(tab),
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) {
                        Color.White
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                )
            }
        }
    }
}

// ------------------------------------------------------------------ 主卡片

@Composable
private fun LoginPaneCard(vm: NeteaseLoginViewModel) {
    Column(
        Modifier
            .fillMaxWidth()
            .glassCard(GlassLevel.CARD, RoundedCornerShape(UiTokens.RadiusCard), elevation = 6.dp)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (vm.tab) {
            NeteaseLoginTab.WEB -> WebPane(vm)
            NeteaseLoginTab.QR -> QrPane(vm)
            NeteaseLoginTab.PHONE -> PhonePane(vm)
            NeteaseLoginTab.COOKIE -> CookiePane(vm)
        }
        StatusStrip(vm)
    }
}

// ------------------------------------------------------------------ 一键登录

/** 网易云官网登录页：走手机版 UA，页面自适应且支持验证码 / 密码 / 扫码三种官方方式。 */
private const val NETEASE_LOGIN_URL = "https://music.163.com/#/login"
private const val MOBILE_UA =
    "Mozilla/5.0 (Linux; Android 13; SM-S908B) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/120.0.0.0 Mobile Safari/537.36"

/**
 * 一键登录：WebView 打开网易云官网登录页，登录成功自动取回 Cookie。
 *
 * 这是成功率最高的方式 —— 官网登录页由网易云自己维护，不存在"第三方客户端调接口"这个前提，
 * 所以不会被 -460/-462 拦。页面每次加载完成都检查 CookieManager 里有没有 `MUSIC_U`。
 */
@Composable
private fun WebPane(vm: NeteaseLoginViewModel) {
    PaneHeader(
        icon = Icons.Filled.Language,
        title = "在网易云官网登录",
        subtitle = "支持手机号验证码 / 密码 / 扫码，登录成功会自动返回。" +
            "走的是网易云自己的登录页，不会被风控拦截，推荐用这个。",
    )
    Spacer(Modifier.height(14.dp))
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    Box(
        Modifier
            .fillMaxWidth()
            .height((screenHeight * 0.46f).coerceIn(320.dp, 520.dp))
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
    ) {
        AndroidView(
            factory = { ctx -> buildLoginWebView(ctx, NETEASE_LOGIN_URL, null) { vm.onWebCookie(it) } },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

// ------------------------------------------------------------------ 扫码

@Composable
private fun QrPane(vm: NeteaseLoginViewModel) {
    PaneHeader(
        icon = Icons.Filled.QrCode2,
        title = "用网易云 App 扫一扫",
        subtitle = "二维码约 5 分钟有效，过期会自动换一张。",
    )
    Spacer(Modifier.height(18.dp))
    Box(
        Modifier
            .size(214.dp)
            .shadow(10.dp, RoundedCornerShape(20.dp))
            .background(Color.White, RoundedCornerShape(20.dp))
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        vm.qrBitmap?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "登录二维码",
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)),
            )
        } ?: CircularProgressIndicator(
            modifier = Modifier.size(40.dp),
            color = BrandRed,
            strokeWidth = 3.dp,
        )
    }
}

// ------------------------------------------------------------------ 手机号

@Composable
private fun PhonePane(vm: NeteaseLoginViewModel) {
    PaneHeader(
        icon = if (vm.usePassword) Icons.Filled.Lock else Icons.Filled.Sms,
        title = if (vm.usePassword) "密码登录" else "验证码登录",
        subtitle = if (vm.usePassword) {
            "密码只在本地做 MD5 后上传，不会明文传出。"
        } else {
            "先获取验证码，再填入登录。"
        },
    )
    Spacer(Modifier.height(16.dp))
    LoginField(
        value = vm.phone,
        onValueChange = { vm.phone = it },
        label = "手机号",
        icon = Icons.Filled.Phone,
        keyboardType = KeyboardType.Phone,
    )
    Spacer(Modifier.height(12.dp))
    if (vm.usePassword) {
        LoginField(
            value = vm.password,
            onValueChange = { vm.password = it },
            label = "密码",
            icon = Icons.Filled.Lock,
            visualTransformation = PasswordVisualTransformation(),
        )
        Spacer(Modifier.height(18.dp))
        BrandButton("登录", !vm.busy, vm.busy, vm::loginByPassword)
    } else {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            LoginField(
                value = vm.code,
                onValueChange = { vm.code = it },
                label = "验证码",
                icon = Icons.Filled.Sms,
                keyboardType = KeyboardType.Number,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            // 发送验证码做成胶囊按钮，和输入框同高，避免和主按钮抢视觉重量
            Box(
                Modifier
                    .height(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(BrandRed.copy(alpha = if (vm.busy) 0.35f else 0.14f))
                    .pressable(scaleDown = 0.95f, enabled = !vm.busy, onClick = vm::sendCode)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (vm.codeSent) "重发" else "获取",
                    color = BrandRed,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        BrandButton("登录", !vm.busy, vm.busy, vm::loginByCode)
    }
    TextButton(onClick = vm::togglePasswordMode) {
        Text(
            if (vm.usePassword) "改用短信验证码登录" else "改用密码登录",
            fontSize = 13.sp,
        )
    }
}

// ------------------------------------------------------------------ Cookie

@Composable
private fun CookiePane(vm: NeteaseLoginViewModel) {
    PaneHeader(
        icon = Icons.Filled.Key,
        title = "手动导入 Cookie",
        subtitle = "在电脑浏览器打开 music.163.com 登录，按 F12 → Network → 任选一个请求 → " +
            "复制 Request Headers 里的 Cookie（需包含 MUSIC_U）。",
    )
    Spacer(Modifier.height(14.dp))
    LoginField(
        value = vm.cookieInput,
        onValueChange = { vm.cookieInput = it },
        label = "Cookie",
        icon = Icons.Filled.Key,
        minLines = 4,
    )
    Spacer(Modifier.height(18.dp))
    BrandButton("校验并登录", !vm.busy, vm.busy, vm::importCookie)
}

// ------------------------------------------------------------------ 通用件

@Composable
private fun PaneHeader(icon: ImageVector, title: String, subtitle: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(44.dp)
                .background(BrandRed.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = BrandRed, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun LoginField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    minLines: Int = 1,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 14.sp) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp)) },
        singleLine = minLines == 1,
        minLines = minLines,
        visualTransformation = visualTransformation,
        shape = RoundedCornerShape(16.dp),
        textStyle = MaterialTheme.typography.bodyMedium,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = BrandRed,
            focusedLabelColor = BrandRed,
            focusedLeadingIconColor = BrandRed,
            cursorColor = BrandRed,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

/** 品牌色渐变主按钮：登录页唯一的「主行动点」，所以做得比次要按钮重。 */
@Composable
private fun BrandButton(
    text: String,
    enabled: Boolean,
    loading: Boolean,
    onClick: () -> Unit,
) {
    val alpha by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.45f,
        animationSpec = tween(160),
        label = "btnAlpha",
    )
    Box(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(
                Brush.horizontalGradient(listOf(BrandRed, BrandRedDeep)),
                alpha = alpha,
            )
            .pressable(scaleDown = 0.97f, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                color = Color.White,
                strokeWidth = 2.dp,
            )
        } else {
            Text(text, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** 状态 / 错误条：成功态只是一行小字，失败态给可点的下一步。 */
@Composable
private fun StatusStrip(vm: NeteaseLoginViewModel) {
    if (vm.status.isBlank() && vm.error.isNullOrBlank()) return
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(16.dp))
        if (vm.error.isNullOrBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (vm.busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = BrandRed,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    vm.status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            return
        }
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.errorContainer,
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    vm.error.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // 风控：官方给了验证页，过一次就能继续，比让用户换方式重试强
            if (vm.risk != null) {
                TextButton(onClick = vm::showRisk) { Text("完成安全验证", fontSize = 13.sp) }
            }
            TextButton(onClick = { vm.selectTab(NeteaseLoginTab.WEB) }) {
                Text("改用一键登录", fontSize = 13.sp)
            }
        }
    }
}

// ------------------------------------------------------------------ 风控验证

/**
 * 风控验证覆盖层：官方验证页（易盾滑块 / 短信）+ 底部「我已完成」。
 *
 * 验证通过后网易云那边会把这个 verifyToken 标记为已验证，此时带着同一个 token
 * 重放刚才失败的登录动作即可放行 —— 所以这里不做任何解析，只负责把页面给用户、
 * 并在用户点「已完成」时把 token 交回去。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun RiskVerifyOverlay(vm: NeteaseLoginViewModel) {
    val risk = vm.risk ?: return
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .statusBarsPadding()
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = vm::dismissRisk) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                }
                Text("安全验证", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            AndroidView(
                factory = { ctx -> buildLoginWebView(ctx, risk.pageUrl, null) { } },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .navigationBarsPadding(),
            ) {
                Text(
                    "在上方页面完成验证后点下面这个按钮，会自动带着验证结果重新登录",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                BrandButton("我已完成验证", !vm.busy, vm.busy) {
                    vm.retryAfterVerify(risk.verifyToken)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ WebView 构造

@SuppressLint("SetJavaScriptEnabled")
private fun buildLoginWebView(
    ctx: android.content.Context,
    url: String,
    onError: ((String) -> Unit)?,
    onCookie: (String) -> Unit,
): WebView = WebView(ctx).apply {
    settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        databaseEnabled = true
        // 手机版 UA：页面按窄屏布局，登录入口才是最好的那种
        userAgentString = MOBILE_UA
        loadWithOverviewMode = true
        useWideViewPort = true
    }
    CookieManager.getInstance().setAcceptCookie(true)
    webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView, finishedUrl: String) {
            val cookie = CookieManager.getInstance().getCookie("https://music.163.com")
            if (!cookie.isNullOrBlank()) onCookie(cookie)
        }

        override fun onReceivedError(
            view: WebView,
            request: android.webkit.WebResourceRequest,
            error: android.webkit.WebResourceError,
        ) {
            if (request.isForMainFrame) onError?.invoke("网页无法加载")
        }
    }
    loadUrl(url)
}
