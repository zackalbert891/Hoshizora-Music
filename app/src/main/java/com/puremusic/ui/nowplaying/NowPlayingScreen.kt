package com.puremusic.ui.nowplaying

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.puremusic.model.PlayMode
import com.puremusic.model.Song
import com.puremusic.ui.components.AddToPlaylistDialog
import com.puremusic.ui.components.NeteaseTurntable
import com.puremusic.ui.components.ThinSeekBar
import com.puremusic.ui.components.seekBubbleLabel
import com.puremusic.ui.theme.PressFeedback
import com.puremusic.ui.theme.UiTokens
import com.puremusic.ui.theme.pressable
import java.io.File
import kotlinx.coroutines.launch

/**
 * 播放页（网易云音乐布局范式）。
 *
 * 结构自上而下：
 * 1. 顶栏：返回 + 居中「歌名 / 歌手」+ 分享；
 * 2. 唱片页 / 歌词页横向分页（左右滑动，底部两个小圆点指示）；
 *    - 唱片页：黑胶唱机（含会起落的唱针）+ 底部图标栏（喜欢 / 评论 / 更多）；
 *    - 歌词页：全屏沉浸式歌词（无卡片边框），点击可回到唱片页；
 * 3. 进度条（网易云细线样式）+ 左右时间；
 * 4. 控制区：随机 / 上一首 / 播放暂停 / 下一首 / 循环。
 *
 * 倍速、睡眠定时、添加到歌单收进「更多」菜单，避免底部堆太多控件。
 */
@Composable
fun NowPlayingScreen(
    onBack: () -> Unit,
    onCoverColor: (Long) -> Unit = {},
    onComments: (Song) -> Unit = {},
) {
    val vm: NowPlayingViewModel = hiltViewModel()
    val song by vm.currentSong.collectAsStateWithLifecycle()
    val isPlaying by vm.isPlaying.collectAsStateWithLifecycle()
    /** 播放模式：顺序 / 随机 / 单曲，一个按钮点三下循环。 */
    val playMode by vm.playMode.collectAsStateWithLifecycle()
    /** 播放页自定义背景图；为空时用封面。 */
    val playerBg by vm.playerBackgroundPath.collectAsStateWithLifecycle()
    val themeConfig by vm.themeConfig.collectAsStateWithLifecycle()
    /** 背景模糊跟随设置里的强度（0 = 完全清晰，1 ≈ 40dp 半径）。 */
    val blurRadius = (themeConfig.blurStrength.coerceIn(0f, 1f) * 40f).dp

    val pagerState = rememberPagerState(initialPage = if (vm.showLyrics) 1 else 0) { 2 }
    val pagerScope = rememberCoroutineScope()
    val context = LocalContext.current
    // 与设置页一致：选图后先框选，不直接居中裁掉。
    var cropUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var cropBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var cropLoading by remember { mutableStateOf(false) }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        cropUri = uri
    }

    val pendingUri = cropUri
    LaunchedEffect(pendingUri) {
        if (pendingUri == null) return@LaunchedEffect
        cropLoading = true
        val bmp = com.puremusic.ui.components.loadBitmapForCrop(context, pendingUri)
        cropLoading = false
        if (bmp == null) {
            cropUri = null
        } else {
            cropBitmap = bmp
        }
    }

    LaunchedEffect(pagerState.currentPage) {
        vm.showLyrics = pagerState.currentPage == 1
    }

    // 封面主色：驱动标题、进度条、播放按钮配色
    var coverColorRaw by remember { mutableStateOf(Color(0xFFFF6B00)) }
    val coverColor by animateColorAsState(
        targetValue = coverColorRaw,
        animationSpec = tween(600, easing = FastOutSlowInEasing),
        label = "coverColor",
    )

    Box(Modifier.fillMaxSize()) {
        // 背景：封面模糊 + 上暗/中主色/下暗遮罩（保证白色文字在任何封面上都可读）
        Crossfade(
            targetState = playerBg?.takeIf { it.isNotBlank() } ?: song?.coverUrl,
            animationSpec = tween(600, easing = FastOutSlowInEasing),
            label = "bgCrossfade",
        ) { url ->
            if (url != null && url.isNotBlank()) {
                val custom = !playerBg.isNullOrBlank()
                AsyncImage(
                    model = if (custom) File(playerBg!!) else url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = if (blurRadius > 0.dp) {
                        Modifier.fillMaxSize().blur(blurRadius)
                    } else {
                        Modifier.fillMaxSize()
                    },
                )
            } else {
                Box(Modifier.fillMaxSize().background(Color(0xFF101014)))
            }
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF0A0A0E).copy(alpha = 0.62f),
                        coverColor.copy(alpha = 0.28f),
                        Color(0xFF0A0A0E).copy(alpha = 0.94f),
                    )
                )
            )
        )

        // 顶栏 / 指示点 / 进度条 / 控制区在唱片页与歌词页都常驻。
        // 「歌词全面屏」那版把它们全收掉，观感反而更差，这里回到页内样式。
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            NeteaseTopBar(
                song = song,
                onBack = onBack,
                onShare = { s ->
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, "我正在听《${s.title}》- ${s.artist}")
                    }
                    runCatching {
                        context.startActivity(Intent.createChooser(intent, "分享歌曲"))
                    }
                },
            )

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                beyondViewportPageCount = 1,
            ) { page ->
                if (song == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("暂无播放", color = Color.White.copy(alpha = 0.7f))
                    }
                } else if (page == 1) {
                    LyricsPanel(
                        vm = vm,
                        lines = vm.lyrics.lines,
                        coverColor = coverColor,
                        onTapCover = { pagerScope.launch { pagerState.animateScrollToPage(0) } },
                    )
                } else {
                    DiscPage(
                        vm = vm,
                        song = song!!,
                        isPlaying = isPlaying,
                        coverColor = coverColor,
                        onCoverColor = { color ->
                            coverColorRaw = color
                            onCoverColor(color.value.toLong() and 0xFFFFFFFFL)
                        },
                        onComments = onComments,
                        onShowLyrics = { pagerScope.launch { pagerState.animateScrollToPage(1) } },
                        onPickBackground = { imagePicker.launch("image/*") },
                        hasCustomBg = !playerBg.isNullOrBlank(),
                    )
                }
            }

            // 分页指示：两个小圆点（网易云式，替代文字按钮）
            Row(
                Modifier.padding(top = 2.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(2) { index ->
                    val selected = pagerState.currentPage == index
                    val width by animateFloatAsState(
                        targetValue = if (selected) 16f else 6f,
                        animationSpec = tween(260, easing = FastOutSlowInEasing),
                        label = "dot$index",
                    )
                    Box(
                        Modifier
                            .size(width = width.dp, height = 6.dp)
                            .clip(CircleShape)
                            .background(
                                if (selected) coverColor else Color.White.copy(alpha = 0.35f)
                            )
                    )
                }
            }

            PlayerProgressSection(vm = vm, coverColor = coverColor)

            Spacer(Modifier.height(20.dp))

            MainControls(
                vm = vm,
                isPlaying = isPlaying,
                mode = playMode,
                coverColor = coverColor,
                hasCustomBg = !playerBg.isNullOrBlank(),
                onPickBackground = { imagePicker.launch("image/*") },
            )

            Spacer(Modifier.height(14.dp))
        }
    }

    if (cropLoading) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            text = { Text("正在读取图片…") },
        )
    }
    val pending = cropBitmap
    if (pending != null) {
        com.puremusic.ui.components.ImageCropDialog(
            bitmap = pending,
            onDismiss = {
                pending.recycle()
                cropBitmap = null
                cropUri = null
            },
            onConfirm = { cropped ->
                vm.saveCroppedPlayerBackground(cropped)
                pending.recycle()
                cropBitmap = null
                cropUri = null
            },
        )
    }
}

/** 顶栏：返回 + 居中歌名/歌手（切歌横向滑动过渡）+ 分享。 */
@Composable
private fun NeteaseTopBar(
    song: Song?,
    onBack: () -> Unit,
    onShare: (Song) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PressIconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White)
        }
        Spacer(Modifier.weight(1f))
        AnimatedContent(
            targetState = song?.mediaId,
            transitionSpec = {
                (slideInHorizontally(tween(UiTokens.MotionStandard, easing = FastOutSlowInEasing)) { it / 3 } + fadeIn(tween(UiTokens.MotionStandard, easing = FastOutSlowInEasing))) togetherWith
                    (slideOutHorizontally(tween(UiTokens.MotionShort, easing = FastOutSlowInEasing)) { -it / 3 } + fadeOut(tween(UiTokens.MotionShort, easing = FastOutSlowInEasing)))
            },
            label = "headerSong",
        ) {
            val s = song
            if (s != null) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.widthIn(max = 200.dp),
                ) {
                    Text(
                        s.title,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        s.artist,
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Spacer(Modifier.width(0.dp))
            }
        }
        Spacer(Modifier.weight(1f))
        PressIconButton(onClick = { song?.let(onShare) }) {
            Icon(Icons.Filled.Share, contentDescription = "分享", tint = Color.White)
        }
    }
}

/**
 * 唱片页：黑胶唱机 + 图标栏（喜欢 / 评论 / 更多）。
 * 点击唱片区域切到歌词页（与网易云一致）。
 */
@Composable
private fun DiscPage(
    vm: NowPlayingViewModel,
    song: Song,
    isPlaying: Boolean,
    coverColor: Color,
    onCoverColor: (Color) -> Unit,
    onComments: (Song) -> Unit,
    onShowLyrics: () -> Unit,
    onPickBackground: () -> Unit,
    hasCustomBg: Boolean,
) {
    val favoriteIds by vm.favoriteIds.collectAsStateWithLifecycle()
    val speed by vm.speed.collectAsStateWithLifecycle()
    val sleepEnd by vm.sleepTimerEndMs.collectAsStateWithLifecycle()
    val playlists by vm.localPlaylists.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var moreMenu by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    val favorited = song.mediaId in favoriteIds

    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BoxWithConstraints(
            Modifier.fillMaxWidth().weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            val turntableSize = minOf(maxWidth * 0.88f, maxHeight * 0.86f)
            // 切歌滑动过渡
            AnimatedContent(
                targetState = song.mediaId,
                transitionSpec = {
                    (slideInHorizontally(tween(UiTokens.MotionStandard, easing = FastOutSlowInEasing)) { it / 2 } + fadeIn(tween(UiTokens.MotionStandard, easing = FastOutSlowInEasing))) togetherWith
                        (slideOutHorizontally(tween(UiTokens.MotionShort, easing = FastOutSlowInEasing)) { -it / 2 } + fadeOut(tween(UiTokens.MotionShort, easing = FastOutSlowInEasing)))
                },
                label = "coverSwitch",
            ) {
                NeteaseTurntable(
                    coverUrl = song.coverUrl,
                    isPlaying = isPlaying,
                    onDominantColor = onCoverColor,
                    modifier = Modifier
                        .size(turntableSize)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onShowLyrics,
                        ),
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // 图标栏：喜欢 / 评论 / 更多。
        // 这里**没有下载**：把在线曲目落盘既用不上（离线还得依赖缓存与版权校验），
        // 又有版权风险，因此整个下载链路（DownloadManager + 公共音乐目录）已移除。
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PressIconButton(onClick = { vm.toggleFavorite(song) }) {
                Icon(
                    if (favorited) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = if (favorited) "取消喜欢" else "喜欢",
                    tint = if (favorited) Color(0xFFEC4141) else Color.White,
                    modifier = Modifier.size(26.dp),
                )
            }
            PressIconButton(onClick = { onComments(song) }) {
                Icon(
                    Icons.AutoMirrored.Filled.Comment,
                    contentDescription = "评论",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp),
                )
            }
            Box {
                PressIconButton(onClick = { moreMenu = true }) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "更多",
                        tint = if (speed != 1f || sleepEnd > 0L) coverColor else Color.White,
                        modifier = Modifier.size(26.dp),
                    )
                }
                DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(if (speed != 1f) "倍速 ${speed}x" else "倍速") },
                        onClick = { moreMenu = false },
                    )
                    listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { v ->
                        DropdownMenuItem(
                            text = { Text(if (v == 1f) "正常 1.0x" else "${v}x") },
                            onClick = { vm.setSpeed(v); moreMenu = false },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(if (sleepEnd > 0L) "取消定时" else "睡眠定时") },
                        onClick = {
                            if (sleepEnd > 0L) vm.cancelSleepTimer() else vm.setSleepTimer(30)
                            moreMenu = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("添加到歌单") },
                        onClick = { moreMenu = false; showAddDialog = true },
                    )
                    DropdownMenuItem(
                        text = { Text("导入播放页背景图") },
                        onClick = { moreMenu = false; onPickBackground() },
                    )
                    if (hasCustomBg) {
                        DropdownMenuItem(
                            text = { Text("恢复封面背景") },
                            onClick = { moreMenu = false; vm.clearPlayerBackground() },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
    }

    if (showAddDialog) {
        AddToPlaylistDialog(
            playlists = playlists,
            onDismiss = { showAddDialog = false },
            onSelect = { vm.addToPlaylist(it, song) },
            onCreate = { vm.createPlaylistWith(it, song) },
        )
    }
}

/**
 * 主控制区：**播放模式 / 上一首 / 播放暂停 / 下一首 / 换背景图**。
 *
 * 播放模式只有[一个按钮]：点一下切一档（顺序 → 随机 → 单曲 → 顺序），
 * 相比"随机 + 循环"两个开关少一个按钮，也避免了「随机 + 单曲」这种没意义的状态。
 */
@Composable
private fun MainControls(
    vm: NowPlayingViewModel,
    isPlaying: Boolean,
    mode: PlayMode,
    coverColor: Color,
    hasCustomBg: Boolean,
    onPickBackground: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayModeButton(mode = mode, tint = coverColor, onClick = vm::cyclePlayMode)
        PressIconButton(onClick = vm::previous) {
            Icon(Icons.Filled.SkipPrevious, "上一首", tint = Color.White, modifier = Modifier.size(34.dp))
        }
        PlayPauseButton(isPlaying = isPlaying, onClick = vm::toggle, color = coverColor)
        PressIconButton(onClick = vm::next) {
            Icon(Icons.Filled.SkipNext, "下一首", tint = Color.White, modifier = Modifier.size(34.dp))
        }
        PressIconButton(onClick = onPickBackground) {
            Icon(
                Icons.Filled.Image,
                contentDescription = "导入播放页背景图",
                tint = if (hasCustomBg) coverColor else Color.White,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/** 播放模式按钮：图标随模式切换（列表循环 / 随机 / 单曲 1），非顺序态点亮主色。 */
@Composable
private fun PlayModeButton(
    mode: PlayMode,
    tint: Color,
    onClick: () -> Unit,
) {
    val active = mode != PlayMode.SEQUENCE
    PressIconButton(onClick = onClick) {
        AnimatedContent(
            targetState = mode,
            transitionSpec = {
                (fadeIn(tween(UiTokens.MotionShort, easing = FastOutSlowInEasing))) togetherWith
                    (fadeOut(tween(UiTokens.MotionShort, easing = FastOutSlowInEasing)))
            },
            label = "playMode",
        ) { m ->
            Icon(
                when (m) {
                    PlayMode.SEQUENCE -> Icons.Filled.Repeat
                    PlayMode.SHUFFLE -> Icons.Filled.Shuffle
                    PlayMode.REPEAT_ONE -> Icons.Filled.RepeatOne
                },
                contentDescription = m.label,
                tint = if (active) tint else Color.White,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/** 播放/暂停主按钮：按压缩放 + 图标交叉渐变 + 主题色圆形底。 */
@Composable
private fun PlayPauseButton(
    isPlaying: Boolean,
    onClick: () -> Unit,
    color: Color,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.86f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 500f),
        label = "playScale",
    )
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(68.dp).graphicsLayer { scaleX = scale; scaleY = scale },
        interactionSource = interaction,
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().shadow(10.dp, CircleShape, spotColor = color.copy(alpha = 0.6f)),
            shape = CircleShape,
            color = color,
        ) {
            AnimatedContent(
                targetState = isPlaying,
                transitionSpec = {
                    (fadeIn(tween(UiTokens.MotionStandard, easing = FastOutSlowInEasing))) togetherWith
                        (fadeOut(tween(UiTokens.MotionShort, easing = FastOutSlowInEasing)))
                },
                label = "playPauseIcon",
            ) { playing ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        if (playing) "暂停" else "播放",
                        tint = Color.White,
                        modifier = Modifier.size(36.dp),
                    )
                }
            }
        }
    }
}

/**
 * 进度区：**一条 2.5dp 的细线 + 一个圆点**（[ThinSeekBar]）。
 *
 * 之前用 Material Slider + 下面另加一条缓冲条，同一个位置出现两条轨道，
 * 而且 Slider 自带的轨道偏粗，观感很重。现在已播放 / 缓冲 / 未播放都画在同一条线上，
 * 只有圆点会随拖动放大并浮出时间气泡。
 */
@Composable
private fun PlayerProgressSection(vm: NowPlayingViewModel, coverColor: Color) {
    val position by vm.currentPosition.collectAsStateWithLifecycle()
    val duration by vm.duration.collectAsStateWithLifecycle()
    val buffered by vm.bufferedPosition.collectAsStateWithLifecycle()

    var dragging by remember { mutableStateOf(false) }
    var dragRatio by remember { mutableFloatStateOf(0f) }
    val maxDur = duration.coerceAtLeast(1L)
    val ratio = if (dragging) dragRatio
    else (position.coerceIn(0L, duration).toFloat() / maxDur.toFloat()).coerceIn(0f, 1f)
    val bufferedRatio = (buffered.coerceIn(0L, duration).toFloat() / maxDur.toFloat()).coerceIn(0f, 1f)

    Column(Modifier.fillMaxWidth()) {
        ThinSeekBar(
            progress = ratio,
            buffered = bufferedRatio,
            activeColor = coverColor,
            trackColor = Color.White.copy(alpha = 0.16f),
            bufferedColor = Color.White.copy(alpha = 0.30f),
            thumbColor = coverColor,
            bubbleText = { r -> seekBubbleLabel((maxDur.toFloat() * r).toLong()) },
            onDraggingChanged = { dragging = it },
            onSeek = { r -> vm.seek((maxDur.toFloat() * r).toLong()) },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                formatTime((if (dragging) (maxDur.toFloat() * dragRatio).toLong() else position)
                    .coerceAtLeast(0L)),
                color = if (dragging) coverColor else Color.White.copy(alpha = 0.6f),
                fontSize = 11.sp,
            )
            Text(formatTime(duration), color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
        }
    }
}

/**
 * 歌词页：**页内样式**（顶栏 / 指示点 / 进度条 / 控制区都在），歌词在中间区域滚动。
 *
 * 上一版做成整屏沉浸、把顶栏和进度条全收掉，观感反而更差（字飘、没有落点），
 * 这里改回页内样式；同时不再压一条突兀的黑色蒙版，改用「离当前行的距离」控制透明度
 * —— 近处亮、远处淡，边缘自然收口。
 */
@Composable
private fun LyricsPanel(
    vm: NowPlayingViewModel,
    lines: List<com.puremusic.model.LyricLine>,
    coverColor: Color,
    onTapCover: () -> Unit,
) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onTapCover,
            )
    ) {
        // 字号 / 行距按可用高度自适应：小屏不挤、大屏不空
        val baseSp = (maxHeight.value / 30f).coerceIn(15f, 19f)
        val lineGap = (maxHeight.value / 130f).coerceIn(5f, 9f).dp

        if (lines.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无歌词", color = Color.White.copy(alpha = 0.55f), fontSize = baseSp.sp)
            }
            return@BoxWithConstraints
        }

        val listState = rememberLazyListState()
        val position by vm.currentPosition.collectAsStateWithLifecycle()
        val currentIndex = binarySearchCurrentLine(lines, position).coerceAtLeast(0)
        // 上一次已经定位过的行：跳转很远（首次进入 / 拖动进度条）时直接落位，
        // 否则只用动画挪一行 —— 每次换行都 animate 会被下一次换行打断，越拖越远。
        var anchoredIndex by remember { mutableIntStateOf(-1) }

        LaunchedEffect(currentIndex) {
            // 注意：LazyColumn 的 scrollToItem 会把「顶部 contentPadding」留在视口里，
            // 所以滚动到第 i 行（offset 0）时它正好落在 topPadding 处 —— 也就是下面的 42%。
            // 再额外给 scrollOffset 反而会把当前行压到视口偏下的位置（实测 ≈ 2 倍偏移）。
            val far = anchoredIndex < 0 || kotlin.math.abs(currentIndex - anchoredIndex) > 4
            if (far) listState.scrollToItem(currentIndex)
            else listState.animateScrollToItem(currentIndex)
            anchoredIndex = currentIndex
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            // 上下各留 4 成屏：当前行能停在 42% 处，第一行 / 最后一行也能滚到该位置
            contentPadding = PaddingValues(top = maxHeight * 0.42f, bottom = maxHeight * 0.58f),
        ) {
            itemsIndexed(
                lines,
                key = { index, line -> "$index:${line.timeMs}" },
                contentType = { _, _ -> "lyric" },
            ) { index, line ->
                val isCurrent = index == currentIndex
                val distance = kotlin.math.abs(index - currentIndex)
                val alpha = when {
                    isCurrent -> 1f
                    distance == 1 -> 0.66f
                    distance == 2 -> 0.46f
                    distance == 3 -> 0.32f
                    else -> 0.22f
                }
                val animatedColor by animateColorAsState(
                    targetValue = if (isCurrent) coverColor else Color.White.copy(alpha = alpha),
                    animationSpec = tween(UiTokens.MotionStandard, easing = FastOutSlowInEasing),
                    label = "lyricColor",
                )
                val sizeSp by animateFloatAsState(
                    targetValue = if (isCurrent) baseSp + 4f else baseSp,
                    animationSpec = tween(UiTokens.MotionStandard, easing = FastOutSlowInEasing),
                    label = "lyricSize",
                )
                Text(
                    text = line.text,
                    color = animatedColor,
                    fontSize = sizeSp.sp,
                    lineHeight = (sizeSp * 1.4f).sp,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier
                        .fillMaxWidth(0.92f)
                        .padding(vertical = lineGap),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

/** 二分查找最后一个 timeMs <= position 的行下标（歌词行需按 timeMs 升序）。 */
private fun binarySearchCurrentLine(lines: List<com.puremusic.model.LyricLine>, position: Long): Int {
    var lo = 0
    var hi = lines.size - 1
    var ans = 0
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (lines[mid].timeMs <= position) {
            ans = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return ans
}

private fun formatTime(millis: Long): String {
    val totalSeconds = millis / 1000
    return String.format("%02d:%02d", totalSeconds / 60, totalSeconds % 60)
}

/**
 * 播放页图标按钮：弹簧按压 + 轻触感，替代 Material 的 IconButton（水波在深色沉浸页很脏）。
 * 尺寸与 IconButton 默认一致（48dp），图标由调用点自行给 tint。
 */
@Composable
private fun PressIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .pressable(scaleDown = 0.86f, feedback = PressFeedback.CLICK, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}
