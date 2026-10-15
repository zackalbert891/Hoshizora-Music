package com.puremusic.ui

import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.puremusic.source.autoswitch.AutoSwitchSource
import com.puremusic.model.Song
import com.puremusic.model.albumEntityId
import com.puremusic.model.artistEntityId
import com.puremusic.ui.comments.CommentsScreen
import com.puremusic.ui.components.AppBackground
import com.puremusic.ui.components.MiniPlayer
import com.puremusic.ui.discover.DiscoverScreen
import com.puremusic.ui.entity.AlbumScreen
import com.puremusic.ui.entity.ArtistScreen
import com.puremusic.ui.theme.GlassLevel
import com.puremusic.ui.theme.LocalGlassBackdrops
import com.puremusic.ui.theme.LocalLiquidGlass
import com.puremusic.ui.theme.glassCard
import com.puremusic.ui.theme.glassSurface
import com.puremusic.ui.theme.pressable
import com.puremusic.ui.theme.rememberGlassBackdrop
import com.puremusic.ui.theme.spec
import com.puremusic.ui.library.LibraryScreen
import com.puremusic.ui.library.PlaylistDetailScreen
import com.puremusic.ui.search.SearchScreen
import com.puremusic.ui.login.LoginScreen
import com.puremusic.ui.login.NeteaseLoginScreen
import com.puremusic.ui.nowplaying.NowPlayingScreen
import com.puremusic.ui.settings.SettingsScreen
import com.puremusic.ui.source.SourceScreen
import com.puremusic.ui.theme.PureMusicTheme

object Destinations {
    const val LIBRARY = "library"
    const val SETTINGS = "settings"
    const val NOW_PLAYING = "nowplaying"
    const val LOGIN = "login"
    const val NETEASE_LOGIN = "neteaselogin"
    const val PLAYLIST = "playlist/{sourceId}/{playlistId}/{playlistName}"
    const val SEARCH = "search/{sourceId}"
    const val COMMENTS = "comments/{songId}/{sourceId}/{title}/{artist}"
    /** 单个平台的云端音乐库：点 QQ 音乐就只看 QQ 音乐的歌单。 */
    const val SOURCE = "source/{sourceId}"
    /** 发现页：推荐 / 榜单 / 歌单广场（对标 NCMusic 的「发现」）。 */
    const val DISCOVER = "discover"
    /** 歌手页（源无关：网易云 arId / QQ singermid）。 */
    const val ARTIST = "artist/{sourceId}/{artistId}"
    /** 专辑页（源无关：网易云 alId / QQ 数字 albumid）。 */
    const val ALBUM = "album/{sourceId}/{albumId}"

    fun artist(sourceId: String, artistId: String) = "artist/$sourceId/$artistId"

    fun album(sourceId: String, albumId: String) = "album/$sourceId/$albumId"

    fun comments(songId: String, sourceId: String, title: String, artist: String) =
        "comments/${Uri.encode(songId)}/$sourceId/${Uri.encode(title)}/${Uri.encode(artist)}"

    fun search(sourceId: String = "") = "search/$sourceId"

    fun source(sourceId: String) = "source/$sourceId"

    fun playlist(sourceId: String, playlistId: String, name: String) =
        "playlist/$sourceId/$playlistId/${Uri.encode(name)}"
}

/**
 * 取歌曲 extra 里的实体 id（歌手 / 专辑）走 [com.puremusic.model.artistEntityId] /
 * [com.puremusic.model.albumEntityId]（源无关），返回 null 表示这首歌没有该 id，
 * 调用方据此**不显示入口**，而不是跳到一个空页面。
 */

@Composable
fun AppRoot() {
    val vm: MainViewModel = hiltViewModel()
    val themeConfig by vm.themeConfig.collectAsStateWithLifecycle()
    val coverSeed by vm.coverSeedColor.collectAsStateWithLifecycle()
    // 全局封面主色平滑过渡（animateColorAsState）：切歌时整个应用配色渐变而非跳变
    val coverColorAnim by animateColorAsState(
        targetValue = if (coverSeed != 0L) Color(coverSeed.toInt()) else Color(0xFFFF6B00),
        animationSpec = tween(600, easing = FastOutSlowInEasing),
        label = "globalCoverColor",
    )

    PureMusicTheme(themeConfig, coverColorAnim) {
        // 全 App 共用一块背板：页面背景把自己的内容录进它，底部栏的玻璃元件再取样折射。
        // 必须在 Scaffold 之上提供，否则 bottomBar 里的 MiniPlayer / 导航栏取不到背板，
        // 会退化成不透明的实心近似（玻璃感全丢）。
        val backdrop = rememberGlassBackdrop()
        val navController = rememberNavController()
        val backStackEntry by navController.currentBackStackEntryAsState()
        val route = backStackEntry?.destination?.route
        // 底部播放条 + 导航胶囊：除沉浸式播放页和登录页外都要在。
        // 之前歌单详情页不在列表里，导致「点进歌单后播放器消失」，很割裂。
        val showBottomBar = route != null &&
            route != Destinations.NOW_PLAYING &&
            route != Destinations.LOGIN &&
            route != Destinations.NETEASE_LOGIN

        val currentSong by vm.currentSong.collectAsStateWithLifecycle()
        val isPlaying by vm.isPlaying.collectAsStateWithLifecycle()
        val bgPath by vm.backgroundImagePath.collectAsStateWithLifecycle()
        // MiniPlayer 进度条：在 AppRoot 收集高频 position，仅在底部栏区域重组（避开 NavHost 内容重组）
        val positionMs by vm.positionMs.collectAsStateWithLifecycle()
        val durationMs by vm.durationMs.collectAsStateWithLifecycle()

        CompositionLocalProvider(
            LocalGlassBackdrops provides listOf(backdrop),
            LocalLiquidGlass provides themeConfig.liquidGlass,
        ) {
        // 背景铺满整屏（含底部栏与系统导航栏区域）。
        // 之前背景只画在 NavHost 的内容里，Scaffold 的 bottomBar 区域是空的，
        // 屏幕底部就露出一条纯白底色 —— 那就是要去掉的「底部白色部分」。
        AppBackground(backgroundImagePath = bgPath) {
        Scaffold(
            containerColor = Color.Transparent,
            // 全面屏：只避让状态栏，内容一路铺到导航栏之后（导航栏区域留给悬浮按钮与播放条）
            contentWindowInsets = WindowInsets.statusBars,
            bottomBar = {
                if (showBottomBar) {
                    Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
                        Column {
                            // 悬浮导航：两个圆形液态玻璃按钮浮在播放条上方，没有底板、不横贯整屏
                            // （之前是一条白胶囊 + 一条白播放条叠在一起，才是"白长条"的来源）
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                FloatingNavButton(
                                    label = "音乐库",
                                    icon = Icons.Filled.LibraryMusic,
                                    selected = route == Destinations.LIBRARY,
                                    onClick = {
                                        navController.navigate(Destinations.LIBRARY) { launchSingleTop = true }
                                    },
                                )
                                Spacer(Modifier.width(10.dp))
                                FloatingNavButton(
                                    label = "发现",
                                    icon = Icons.Filled.Explore,
                                    selected = route == Destinations.DISCOVER,
                                    onClick = {
                                        navController.navigate(Destinations.DISCOVER) { launchSingleTop = true }
                                    },
                                )
                                Spacer(Modifier.width(10.dp))
                                FloatingNavButton(
                                    label = "设置",
                                    icon = Icons.Filled.Settings,
                                    selected = route == Destinations.SETTINGS,
                                    onClick = {
                                        navController.navigate(Destinations.SETTINGS) { launchSingleTop = true }
                                    },
                                )
                            }
                            currentSong?.let { song ->
                                MiniPlayer(
                                    song = song,
                                    isPlaying = isPlaying,
                                    onClick = { navController.navigate(Destinations.NOW_PLAYING) },
                                    onToggle = vm::togglePlayPause,
                                    progress = if (durationMs > 0L) {
                                        positionMs.coerceIn(0L, durationMs).toFloat() / durationMs.toFloat()
                                    } else -1f,
                                    accent = coverColorAnim,
                                )
                            }
                        }
                    }
                }
            }
        ) { padding ->
            // 播放页走真正沉浸式：不套用 Scaffold 系统栏 padding，背景铺满到状态栏/导航栏之后，
            // 页面内容自行通过 WindowInsets.safeDrawing 避让状态栏、导航栏与刘海屏。
            val navPadding = if (route == Destinations.NOW_PLAYING) PaddingValues(0.dp) else padding
            NavHost(
                navController = navController,
                startDestination = Destinations.LIBRARY,
                modifier = Modifier.padding(navPadding),
                // 顺滑版转场：淡入 + 极小位移，横移会让整屏文字跟着扫、观感偏重
                enterTransition = { fadeIn(tween(260, easing = FastOutSlowInEasing)) },
                exitTransition = { fadeOut(tween(160)) },
                popEnterTransition = { fadeIn(tween(260, easing = FastOutSlowInEasing)) },
                popExitTransition = { fadeOut(tween(160)) },
            ) {
                composable(Destinations.LIBRARY) {
                    LibraryScreen(
                        backgroundImagePath = bgPath,
                        onOpenPlaylist = { sourceId, id, name ->
                            navController.navigate(Destinations.playlist(sourceId, id, name))
                        },
                        onOpenSource = { sourceId -> navController.navigate(Destinations.source(sourceId)) },
                        onSearch = { navController.navigate(Destinations.search()) },
                        onOpenNowPlaying = { navController.navigate(Destinations.NOW_PLAYING) },
                    )
                }
                composable(
                    Destinations.SOURCE,
                    arguments = listOf(navArgument("sourceId") { type = NavType.StringType }),
                ) { entry ->
                    val sourceId = entry.arguments?.getString("sourceId").orEmpty()
                    SourceScreen(
                        sourceId = sourceId,
                        backgroundImagePath = bgPath,
                        onBack = { navController.popBackStack() },
                        onLogin = {
                            navController.navigate(
                                if (sourceId == com.puremusic.model.MusicSourceType.QQ.id) {
                                    Destinations.LOGIN
                                } else {
                                    Destinations.NETEASE_LOGIN
                                },
                            )
                        },
                        onOpenPlaylist = { sId, id, name ->
                            navController.navigate(Destinations.playlist(sId, id, name))
                        },
                        onSearch = { navController.navigate(Destinations.search(sourceId)) },
                    )
                }
                composable(Destinations.SETTINGS) {
                    SettingsScreen(
                        backgroundImagePath = bgPath,
                        onLogin = { navController.navigate(Destinations.LOGIN) },
                        onNeteaseLogin = { navController.navigate(Destinations.NETEASE_LOGIN) },
                    )
                }
                composable(
                    Destinations.NOW_PLAYING,
                    // 从底部升起：与 MiniPlayer 的位置关系一致，比横向推进更自然
                    enterTransition = { slideInVertically(initialOffsetY = { it / 4 }) + fadeIn(tween(260)) },
                    exitTransition = { slideOutVertically(targetOffsetY = { it / 4 }) + fadeOut(tween(200)) },
                ) {
                    NowPlayingScreen(
                        onBack = { navController.popBackStack() },
                        onCoverColor = { vm.updateCoverSeed(it) },
                        onArtist = { song ->
                            song.artistEntityId()?.let {
                                navController.navigate(Destinations.artist(song.source.id, it))
                            }
                        },
                        onAlbum = { song ->
                            song.albumEntityId()?.let {
                                navController.navigate(Destinations.album(song.source.id, it))
                            }
                        },
                        onComments = { song ->
                            // 用「用户点进来时的原始平台」决定评论区归属：跨源换源只改 song.source，
                            // 原平台留在 extra 里。否则 QQ 的歌一换源出流，评论就会串到网易云去。
                            val ownerSource =
                                song.extra[AutoSwitchSource.ORIGIN_SOURCE_KEY] ?: song.source.id
                            navController.navigate(
                                Destinations.comments(song.id, ownerSource, song.title, song.artist)
                            )
                        },
                    )
                }
                composable(
                    Destinations.COMMENTS,
                    arguments = listOf(
                        navArgument("songId") { type = NavType.StringType },
                        navArgument("sourceId") { type = NavType.StringType },
                        navArgument("title") { type = NavType.StringType },
                        navArgument("artist") { type = NavType.StringType },
                    ),
                ) {
                    CommentsScreen(
                        onBack = { navController.popBackStack() },
                        onLogin = { navController.navigate(Destinations.NETEASE_LOGIN) },
                    )
                }
                composable(Destinations.LOGIN) {
                    LoginScreen(
                        onBack = { navController.popBackStack() },
                        onSuccess = { navController.popBackStack() },
                    )
                }
                composable(Destinations.NETEASE_LOGIN) {
                    NeteaseLoginScreen(
                        onBack = { navController.popBackStack() },
                        onSuccess = { navController.popBackStack() },
                    )
                }
                composable(
                    Destinations.PLAYLIST,
                    arguments = listOf(
                        navArgument("sourceId") { type = NavType.StringType },
                        navArgument("playlistId") { type = NavType.StringType },
                        navArgument("playlistName") { type = NavType.StringType },
                    ),
                ) { entry ->
                    val sourceId = entry.arguments?.getString("sourceId").orEmpty()
                    val playlistId = entry.arguments?.getString("playlistId").orEmpty()
                    val name = Uri.decode(entry.arguments?.getString("playlistName").orEmpty())
                    PlaylistDetailScreen(
                        sourceId = sourceId,
                        playlistId = playlistId,
                        name = name,
                        onBack = { navController.popBackStack() },
                        onOpenNowPlaying = { navController.navigate(Destinations.NOW_PLAYING) },
                        onLogin = { navController.navigate(Destinations.NETEASE_LOGIN) },
                        onOpenArtist = { aid -> navController.navigate(Destinations.artist(sourceId, aid)) },
                        onOpenAlbum = { alId -> navController.navigate(Destinations.album(sourceId, alId)) },
                    )
                }
                composable(
                    Destinations.ARTIST,
                    arguments = listOf(
                        navArgument("sourceId") { type = NavType.StringType },
                        navArgument("artistId") { type = NavType.StringType },
                    ),
                ) { entry ->
                    val sourceId = entry.arguments?.getString("sourceId").orEmpty()
                    val artistId = entry.arguments?.getString("artistId").orEmpty()
                    ArtistScreen(
                        sourceId = sourceId,
                        artistId = artistId,
                        onBack = { navController.popBackStack() },
                        onOpenAlbum = { albumId, _ ->
                            navController.navigate(Destinations.album(sourceId, albumId))
                        },
                    )
                }
                composable(
                    Destinations.ALBUM,
                    arguments = listOf(
                        navArgument("sourceId") { type = NavType.StringType },
                        navArgument("albumId") { type = NavType.StringType },
                    ),
                ) { entry ->
                    val sourceId = entry.arguments?.getString("sourceId").orEmpty()
                    val albumId = entry.arguments?.getString("albumId").orEmpty()
                    AlbumScreen(
                        sourceId = sourceId,
                        albumId = albumId,
                        onBack = { navController.popBackStack() },
                        onOpenArtist = { aid, _ ->
                            navController.navigate(Destinations.artist(sourceId, aid))
                        },
                        onOpenNowPlaying = { navController.navigate(Destinations.NOW_PLAYING) },
                    )
                }
                composable(
                    Destinations.DISCOVER,
                ) {
                    DiscoverScreen(
                        onBack = { navController.popBackStack() },
                        onOpenPlaylist = { sId, id, pname ->
                            navController.navigate(Destinations.playlist(sId, id, pname))
                        },
                        onOpenNowPlaying = { navController.navigate(Destinations.NOW_PLAYING) },
                    )
                }
                composable(
                    Destinations.SEARCH,
                    arguments = listOf(
                        navArgument("sourceId") { type = NavType.StringType; defaultValue = "" },
                    ),
                ) { entry ->
                    val sourceId = entry.arguments?.getString("sourceId").orEmpty()
                    SearchScreen(
                        presetSourceId = sourceId,
                        onBack = { navController.popBackStack() },
                        onOpenNowPlaying = { navController.navigate(Destinations.NOW_PLAYING) },
                    )
                }
            }
        }
        }
        }
    }
}

/**
 * 悬浮导航按钮：一个 48dp 的圆形**液态玻璃**按钮，浮在播放条上方。
 *
 * 用 [glassCard] 而不是自带配色的实心圆：实心圆在浅色主题下就是一坨色块，
 * 玻璃才会把身后的背景折射进来（折射取样的是 AppRoot 那层铺满全屏的背板）。
 * 不用 NavigationBar / 胶囊底板 —— 任何横向的底板都会和播放条叠成一条"白长条"。
 */
@Composable
private fun FloatingNavButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(48.dp)
            .glassCard(
                level = GlassLevel.CHIP,
                shape = CircleShape,
                accent = if (selected) scheme.primary else null,
                elevation = 6.dp,
            )
            .pressable(scaleDown = 0.90f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (selected) scheme.primary else scheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
    }
}
