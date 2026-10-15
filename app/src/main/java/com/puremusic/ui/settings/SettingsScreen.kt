package com.puremusic.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import coil.compose.AsyncImage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.puremusic.model.Account
import com.puremusic.model.CornerStyle
import com.puremusic.model.DarkMode
import com.puremusic.model.LayoutDensity
import com.puremusic.model.MusicSourceType
import com.puremusic.ui.components.AppBackground
import com.puremusic.ui.theme.pressable

// Slider 的 thumb / track 覆写属于 M3 实验 API，稳定后再摘掉这个注解。
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    backgroundImagePath: String?,
    onLogin: () -> Unit,
    onNeteaseLogin: () -> Unit = {},
) {
    val vm: SettingsViewModel = hiltViewModel()
    val themeConfig by vm.themeConfig.collectAsStateWithLifecycle()
    val bgPath by vm.backgroundImagePath.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val qqAccount = accounts[MusicSourceType.QQ]
    val neteaseAccount = accounts[MusicSourceType.NETEASE]

    var showCookieDialog by remember { mutableStateOf(false) }

    val playerBgPath by vm.playerBackgroundPath.collectAsStateWithLifecycle()

    // 背景图走「选图 → 框选 → 存盘」三步：直接 ContentScale.Crop 会把竖图的主体裁没。
    // cropTarget 记住这次要写去哪（音乐库背景 / 播放页背景），确认后按它分派。
    var cropSource by remember { mutableStateOf<android.net.Uri?>(null) }
    var cropTarget by remember { mutableStateOf<CropTarget?>(null) }
    var cropBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var cropLoading by remember { mutableStateOf(false) }

    val context = androidx.compose.ui.platform.LocalContext.current

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            cropTarget = CropTarget.LIBRARY
            cropSource = uri
        }
    }
    val playerImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            cropTarget = CropTarget.PLAYER
            cropSource = uri
        }
    }

    // 选中 Uri 后解码；解码失败（格式不支持/文件被删）就静默退回，不弹框打断。
    val srcUri = cropSource
    LaunchedEffect(srcUri) {
        if (srcUri == null) return@LaunchedEffect
        cropLoading = true
        val bmp = com.puremusic.ui.components.loadBitmapForCrop(context, srcUri)
        cropLoading = false
        if (bmp == null) {
            cropSource = null
            cropTarget = null
        } else {
            cropBitmap = bmp
        }
    }

    // 背景由 AppRoot 的 AppBackground 统一铺满整屏（含底部栏），页面不再自己画。
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(com.puremusic.ui.theme.UiTokens.SpaceL)
        ) {
            Text("设置", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(16.dp))

            Text("账号", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            // 两块极简卡：只放「品牌图标 / 头像 + 平台名」，
            // 登录态、Cookie、各种按钮全部收进点开后的菜单（露太多信息既难看又没用）。
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AccountTile(
                    name = "QQ音乐",
                    brand = Color(0xFF31C27C),
                    initial = "QQ",
                    account = qqAccount,
                    modifier = Modifier.weight(1f),
                ) { dismiss ->
                    if (qqAccount != null) {
                        DropdownMenuItem(
                            text = { Text("退出登录") },
                            onClick = { dismiss(); vm.logout(MusicSourceType.QQ) },
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text("扫码登录") },
                            onClick = { dismiss(); onLogin() },
                        )
                        DropdownMenuItem(
                            text = { Text("手动输 Cookie") },
                            onClick = { dismiss(); showCookieDialog = true },
                        )
                    }
                }

                AccountTile(
                    name = "网易云音乐",
                    brand = Color(0xFFEC4141),
                    initial = "云",
                    account = neteaseAccount,
                    modifier = Modifier.weight(1f),
                ) { dismiss ->
                    if (neteaseAccount != null) {
                        DropdownMenuItem(
                            text = { Text("退出登录") },
                            onClick = { dismiss(); vm.logout(MusicSourceType.NETEASE) },
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text("手机号 / Cookie 登录") },
                            onClick = { dismiss(); onNeteaseLogin() },
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Text("外观", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DarkMode.entries.forEach { mode ->
                    FilterChip(
                        selected = themeConfig.darkMode == mode,
                        onClick = { vm.updateTheme(themeConfig.copy(darkMode = mode)) },
                        label = { Text(mode.label()) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            SettingSwitch("液态玻璃材质", themeConfig.liquidGlass) {
                vm.updateTheme(themeConfig.copy(liquidGlass = it))
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "背景模糊",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "清晰",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = themeConfig.blurStrength,
                    onValueChange = { vm.updateTheme(themeConfig.copy(blurStrength = it)) },
                    valueRange = 0f..1f,
                    // M3 默认的滑杆拇指是一根竖直胶囊（Material You 那套设计语言），
                    // 在这个细长轨道上看着像根小杠，换成圆点更贴手。
                    // track 同时去掉了两端的「停靠点圆点」——留着会跟拇指撞色，
                    // 三个橙点并排看着像坏掉了，实际只有一个是手柄。
                    thumb = { DotThumb() },
                    track = { state ->
                        SliderDefaults.Track(
                            sliderState = state,
                            colors = SliderDefaults.colors(
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = MaterialTheme.colorScheme.primaryContainer,
                                activeTickColor = Color.Transparent,
                                inactiveTickColor = Color.Transparent,
                            ),
                            drawStopIndicator = null,
                            thumbTrackGapSize = 0.dp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp),
                        )
                    },
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Text(
                    "很糊",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "同时作用于音乐库背景与播放页背景（${(themeConfig.blurStrength * 100).toInt()}%）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "字体大小",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FONT_SCALE_OPTIONS.forEach { (value, label) ->
                    FilterChip(
                        selected = themeConfig.fontScale == value,
                        onClick = { vm.updateTheme(themeConfig.copy(fontScale = value)) },
                        label = { Text(label) },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                "圆角风格",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CornerStyle.entries.forEach { style ->
                    FilterChip(
                        selected = themeConfig.cornerStyle == style,
                        onClick = { vm.updateTheme(themeConfig.copy(cornerStyle = style)) },
                        label = { Text(style.label) },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                "布局密度",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LayoutDensity.entries.forEach { density ->
                    FilterChip(
                        selected = themeConfig.layoutDensity == density,
                        onClick = { vm.updateTheme(themeConfig.copy(layoutDensity = density)) },
                        label = { Text(density.label) },
                    )
                }
            }
            Text(
                "密度影响全局间距与控件大小，字体大小独立调节",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(24.dp))
            Text("背景图片", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { imagePicker.launch("image/*") }) { Text("选择并框选") }
                if (bgPath != null) {
                    TextButton(onClick = vm::clearBackground) { Text("清除") }
                }
            }
            Text(
                if (bgPath != null) "已设置自定义背景（音乐库/设置页展示）" else "未设置，默认纯色背景",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { playerImagePicker.launch("image/*") }) { Text("选择并框选（播放页）") }
                if (playerBgPath != null) {
                    TextButton(onClick = vm::clearPlayerBackground) { Text("清除") }
                }
            }
            Text(
                if (playerBgPath != null) "播放页已用自定义背景" else "播放页默认用封面做背景",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(24.dp))
            Text("关于", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "${com.puremusic.BuildConfig.APP_DISPLAY_NAME} · 星空",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "版本 ${com.puremusic.BuildConfig.VERSION_NAME}（${com.puremusic.BuildConfig.VERSION_CODE}）",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showCookieDialog) {
        CookieDialog(
            onSave = { vm.saveCookie(it) },
            onDismiss = { showCookieDialog = false },
        )
    }

    // 框选对话框：解码中先给个转圈，别让用户以为点了没反应
    if (cropLoading) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            text = { Text("正在读取图片…") },
        )
    }
    val pending = cropBitmap
    if (pending != null && cropTarget != null) {
        val target = cropTarget!!
        com.puremusic.ui.components.ImageCropDialog(
            bitmap = pending,
            onDismiss = {
                // 取消也要回收，否则这张 2048px 的解码图会一直挂在内存里
                pending.recycle()
                cropBitmap = null
                cropSource = null
                cropTarget = null
            },
            onConfirm = { cropped ->
                when (target) {
                    CropTarget.LIBRARY -> vm.saveCroppedBackground(cropped)
                    CropTarget.PLAYER -> vm.saveCroppedPlayerBackground(cropped)
                }
                pending.recycle()
                cropBitmap = null
                cropSource = null
                cropTarget = null
            },
        )
    }
}

/** 这次框选的图要写到哪个背景槽位。 */
private enum class CropTarget { LIBRARY, PLAYER }

@Composable
private fun CookieDialog(onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("手动输入 Cookie") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Cookie 字符串") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "电脑浏览器登录 y.qq.com 后，按 F12 → 切到 Console（控制台）→ " +
                        "输入 copy(document.cookie) 回车（已自动复制）→ 直接粘贴到这里。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text); onDismiss() }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 字体缩放档位。 */
private val FONT_SCALE_OPTIONS = listOf(
    0.85f to "小",
    1.0f to "标准",
    1.15f to "大",
)

private fun DarkMode.label(): String = when (this) {
    DarkMode.SYSTEM -> "跟随系统"
    DarkMode.LIGHT -> "浅色"
    DarkMode.DARK -> "深色"
}

@Composable
private fun SettingSwitch(title: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

/**
 * 滑杆拇指：小圆粒。
 *
 * Material 3 默认给的是一根竖直胶囊（Material You 的设计语言），放在这条细长轨道上
 * 看着像根小竖杠。换成实心圆点 + 一圈浅色描边，与 App 里其它可拖控件更一致。
 */
@Composable
private fun DotThumb() {
    Box(
        Modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .border(2.5.dp, MaterialTheme.colorScheme.surface, CircleShape),
    )
}

/**
 * 极简账号卡：只有「头像（或品牌首字）+ 平台名」，点开才是操作菜单。
 *
 * 之前这里堆着平台名、昵称 / 账号号、登录态、错误原因和一排按钮，
 * 两块卡加起来占了小半屏——信息既露得多又不好看。
 */
@Composable
private fun AccountTile(
    name: String,
    brand: Color,
    initial: String,
    account: Account?,
    modifier: Modifier = Modifier,
    menuContent: @Composable (dismiss: () -> Unit) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)
    Box(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(com.puremusic.ui.theme.Glass.Panel)
                .border(
                    1.dp,
                    com.puremusic.ui.theme.Glass.Border.copy(alpha = 0.5f),
                    shape,
                )
                .pressable(scaleDown = 0.96f, onClick = { menu = true })
                .padding(vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AccountAvatar(brand = brand, initial = initial, account = account)
            Spacer(Modifier.height(8.dp))
            Text(
                name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            menuContent { menu = false }
        }
    }
}

/** 头像：有头像图就显示，否则用品牌色圆底 + 平台首字；未登录时是灰底加号。 */
@Composable
private fun AccountAvatar(
    brand: Color,
    initial: String,
    account: Account?,
) {
    val logged = account != null
    val url = account?.avatarUrl.orEmpty()
    Box(
        Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(if (logged) brand.copy(alpha = 0.85f) else brand.copy(alpha = 0.25f)),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isNotBlank()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (logged) {
            Text(
                initial,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
            )
        } else {
            Icon(
                Icons.Filled.Add,
                contentDescription = "登录",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
