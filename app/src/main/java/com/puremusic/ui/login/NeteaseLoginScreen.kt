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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MarkunreadMailbox
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Smartphone
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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
 * 三条路：**扫码**（默认）、**手机号验证码**、**Cookie 导入**。
 *
 * 手机号曾经被当成"用不了"删过 —— 当时归因是被 -460/-462 动态风控拦死，
 * 但 2026-10-12 真机正交对照证明真因是**登录请求里带了 `os` 字段**：
 * 自称安卓客户端去做登录本身就是风控特征（读接口与评论写接口反而要伪装成安卓，
 * 两条路规则相反）。去掉 `os` 伪装后发验证码实测 code=200。
 *
 * 「一键登录」（WebView 官网）仍没加回来：它拿不到 `MUSIC_U`，与风控无关。
 *
 * 扫码也可能撞上风控，此时不干等：把官方给的验证页（verifyUrl + verifyToken）拉起来，
 * 用户过一次验证后带着 token 自动重试。
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

/** 各页签在分段控件里显示的短名（完整 label 太长，三个挤一行会截断）。 */
private fun shortLabel(tab: NeteaseLoginTab): String = when (tab) {
    NeteaseLoginTab.QR -> "扫码"
    NeteaseLoginTab.PHONE -> "手机号"
    NeteaseLoginTab.COOKIE -> "Cookie"
}

/**
 * 分段控件：一整条玻璃底 + 选中项填色。
 *
 * 比 ScrollableTabRow 更适合登录页 —— 入口要一眼看全，
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
            NeteaseLoginTab.QR -> QrPane(vm)
            NeteaseLoginTab.PHONE -> PhonePane(vm)
            NeteaseLoginTab.COOKIE -> CookiePane(vm)
        }
        StatusStrip(vm)
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

/**
 * 手机号验证码登录。
 *
 * 验证码输入框与「获取验证码」并排成一行——拆成上下两个控件会让这一屏多出将近
 * 一行高度，而登录卡片上方还有品牌区与页签，三者叠加后在窄屏上按钮会被顶到
 * 折叠线以下。这里宁可让验证码框窄一点，也保证首屏可见。
 */
@Composable
private fun PhonePane(vm: NeteaseLoginViewModel) {
    PaneHeader(
        icon = Icons.Filled.Smartphone,
        title = "手机号验证码登录",
        subtitle = "验证码由网易云官方发送，约 1 分钟内有效。",
    )
    Spacer(Modifier.height(16.dp))
    LoginField(
        value = vm.phoneInput,
        onValueChange = vm::onPhoneChange,
        label = "手机号",
        icon = Icons.Filled.PhoneAndroid,
        keyboardType = KeyboardType.Phone,
    )
    Spacer(Modifier.height(10.dp))
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LoginField(
            value = vm.captchaInput,
            onValueChange = vm::onCaptchaChange,
            label = "验证码",
            icon = Icons.Filled.MarkunreadMailbox,
            keyboardType = KeyboardType.NumberPassword,
            modifier = Modifier.weight(1f),
        )
        SendCaptchaButton(vm)
    }
    Spacer(Modifier.height(18.dp))
    BrandButton("登录", !vm.busy && vm.captchaInput.isNotBlank(), vm.busy, vm::submitCaptcha)
}

/** 「获取验证码」按钮：倒计时期间变成不可点的剩余秒数。 */
@Composable
private fun SendCaptchaButton(vm: NeteaseLoginViewModel) {
    val counting = vm.captchaCountdown > 0
    val enabled = !vm.busy && !counting && vm.phoneInput.length == 11
    val label = when {
        counting -> "${vm.captchaCountdown}s"
        else -> "获取验证码"
    }
    Box(
        Modifier
            .height(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                BrandRed.copy(alpha = if (enabled) 0.14f else 0.07f),
            )
            .pressable(scaleDown = 0.96f, enabled = enabled, onClick = vm::sendCaptcha)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (vm.busy && !counting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = BrandRed,
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                label,
                fontSize = 13.sp,
                maxLines = 1,
                fontWeight = FontWeight.Medium,
                color = if (enabled) BrandRed else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        }
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
    minLines: Int = 1,
    keyboardType: KeyboardType = KeyboardType.Text,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 14.sp) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp)) },
        singleLine = minLines == 1,
        minLines = minLines,
        visualTransformation = VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        shape = RoundedCornerShape(16.dp),
        textStyle = MaterialTheme.typography.bodyMedium,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = BrandRed,
            focusedLabelColor = BrandRed,
            focusedLeadingIconColor = BrandRed,
            cursorColor = BrandRed,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
        ),
        modifier = modifier,
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
            if (vm.tab != NeteaseLoginTab.COOKIE) {
                TextButton(onClick = { vm.selectTab(NeteaseLoginTab.COOKIE) }) {
                    Text("改用 Cookie 导入", fontSize = 13.sp)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 风控验证

/**
 * 风控验证覆盖层：官方验证页（易盾滑块 / 短信）+ 底部「我已完成」。
 *
 * 验证通过后网易云那边会把这个 verifyToken 标记为已验证，此时带着同一个 token
 * 重新取二维码即可放行 —— 所以这里不做任何解析，只负责把页面给用户、
 * 并在用户点「已完成」时把 token 交回去。
 *
 * 这是登录页里唯一剩下的 WebView：官网登录页那条路已删，但风控页必须用官方页面过。
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
                factory = { ctx -> buildRiskWebView(ctx, risk.pageUrl) },
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
                    "在上方页面完成验证后点下面这个按钮，会自动带着验证结果重新获取二维码",
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

/** 风控页固定用手机版 UA：官方验证页按窄屏布局给出的是移动版组件。 */
private const val MOBILE_UA =
    "Mozilla/5.0 (Linux; Android 13; SM-S908B) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/120.0.0.0 Mobile Safari/537.36"

@SuppressLint("SetJavaScriptEnabled")
private fun buildRiskWebView(ctx: android.content.Context, url: String): WebView =
    WebView(ctx).apply {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = MOBILE_UA
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        CookieManager.getInstance().setAcceptCookie(true)
        webViewClient = object : WebViewClient() {}
        loadUrl(url)
    }
