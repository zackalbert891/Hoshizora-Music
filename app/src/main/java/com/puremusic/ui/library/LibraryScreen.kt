@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.puremusic.ui.library


import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Leaderboard
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.puremusic.model.Account
import com.puremusic.model.MusicSourceType
import com.puremusic.model.Playlist
import com.puremusic.model.Song
import com.puremusic.ui.components.AppBackground
import com.puremusic.ui.components.Cover
import com.puremusic.ui.components.PlaylistGridCard
import com.puremusic.ui.components.displayLabel
import com.puremusic.ui.theme.Glass
import com.puremusic.ui.theme.GlassLevel
import com.puremusic.ui.theme.UiTokens
import com.puremusic.ui.theme.glassSurface
import com.puremusic.ui.theme.pressable
import com.puremusic.ui.theme.spec
import com.puremusic.ui.theme.staggerEnter

/**
 * 音乐库首页：**按平台分化**的入口 + 本地收藏。
 *
 * 顶部三张平台卡分别进「本地 / QQ音乐 / 网易云音乐」各自的云端歌单，
 * 不再把各平台歌单混在一张列表里。
 */
@Composable
fun LibraryScreen(
    backgroundImagePath: String?,
    onOpenPlaylist: (sourceId: String, id: String, name: String) -> Unit,
    onOpenSource: (sourceId: String) -> Unit,
    onSearch: () -> Unit,
    onOpenPlayRecord: () -> Unit = {},
    onOpenNowPlaying: () -> Unit = {},
) {
    val vm: LibraryViewModel = hiltViewModel()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val localPlaylists by vm.localPlaylists.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()

    var showCreateDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Playlist?>(null) }

    // 背景已上提到 AppRoot（AppBackground 铺满整屏），页面自己不再画背景，
    // 否则底部栏那一条又会因为没有背景而露白。
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            // 顶部大标题
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = UiTokens.SpaceL, end = UiTokens.SpaceL, top = UiTokens.SpaceXL),
                ) {
                    Text(
                        "音乐库",
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "按平台查看你的云端歌单",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 搜索栏
            item {
                SearchBar(Modifier.padding(horizontal = UiTokens.SpaceL, vertical = UiTokens.SpaceM), onSearch)
            }

            // 平台入口：点哪个平台就看哪个平台的云端歌单
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = UiTokens.SpaceL),
                    horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceM),
                ) {
                    PlatformCard(
                        title = "本地音乐",
                        subtitle = "${localPlaylists.size} 个歌单",
                        icon = Icons.Filled.LibraryMusic,
                        gradient = Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.tertiary,
                            ),
                        ),
                        modifier = Modifier.weight(1f),
                        index = 0,
                        onClick = { onOpenSource(MusicSourceType.LOCAL.id) },
                    )
                    PlatformCard(
                        title = "QQ音乐",
                        subtitle = accountSubtitle(accounts[MusicSourceType.QQ]),
                        icon = Icons.Filled.Album,
                        gradient = Brush.linearGradient(
                            listOf(Color(0xFF31C27C), Color(0xFF0E9B85)),
                        ),
                        modifier = Modifier.weight(1f),
                        index = 1,
                        onClick = { onOpenSource(MusicSourceType.QQ.id) },
                    )
                    PlatformCard(
                        title = "网易云",
                        subtitle = accountSubtitle(accounts[MusicSourceType.NETEASE]),
                        icon = Icons.Filled.Album,
                        gradient = Brush.linearGradient(
                            listOf(Color(0xFFE23A3A), Color(0xFF8E1B21)),
                        ),
                        modifier = Modifier.weight(1f),
                        index = 2,
                        onClick = { onOpenSource(MusicSourceType.NETEASE.id) },
                    )
                }
            }

            // 我喜欢的 / 播放历史
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = UiTokens.SpaceL, vertical = UiTokens.SpaceM),
                    horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceM),
                ) {
                    GradientEntryCard(
                        title = "我喜欢的",
                        subtitle = "${favorites.size} 首",
                        icon = Icons.Filled.Favorite,
                        gradient = Brush.linearGradient(
                            listOf(Color(0xFFFF5F8F), Color(0xFFB44CE8)),
                        ),
                        modifier = Modifier.weight(1f),
                        onClick = { onOpenPlaylist("fav", "favorites", "我喜欢的") },
                    )
                    GradientEntryCard(
                        title = "播放历史",
                        subtitle = "${history.size} 首",
                        icon = Icons.Filled.History,
                        gradient = Brush.linearGradient(
                            listOf(Color(0xFF4C8DE8), Color(0xFF3FD0C9)),
                        ),
                        modifier = Modifier.weight(1f),
                        onClick = { onOpenPlaylist("hist", "history", "播放历史") },
                    )
                }
            }

            // 听歌排行（云端次数榜）：只有网易云有且必须登录，所以登录后才显示。
            if (accounts[MusicSourceType.NETEASE]?.cookie?.isNotBlank() == true) {
                item {
                    GradientEntryCard(
                        title = "听歌排行",
                        subtitle = "云端播放次数榜",
                        icon = Icons.Filled.Leaderboard,
                        gradient = Brush.linearGradient(
                            listOf(Color(0xFFFF8A4C), Color(0xFFE23A3A)),
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = UiTokens.SpaceL, vertical = UiTokens.SpaceS),
                        onClick = onOpenPlayRecord,
                    )
                }
            }

            // 最近播放
            if (history.isNotEmpty()) {
                item { SectionHeader(title = "最近播放") }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = UiTokens.SpaceL),
                        horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceM),
                    ) {
                        items(history.take(12), key = { "recent:" + it.mediaId }) { song ->
                            RecentSongCard(
                                song = song,
                                onClick = {
                                    // 传上下文队列：只播一首的话播完就停了
                                    vm.play(song, history.take(12))
                                    onOpenNowPlaying()
                                },
                            )
                        }
                    }
                }
            }

            // 本地歌单
            item {
                SectionHeader(
                    title = "本地歌单",
                    actionIcon = Icons.Filled.Add,
                    actionContentDescription = "新建歌单",
                    onAction = { showCreateDialog = true },
                )
            }
            if (localPlaylists.isEmpty()) {
                item { EmptyHint("还没有本地歌单，点上方 + 新建一个") }
            } else {
                itemsIndexed(
                    localPlaylists.chunked(2),
                    key = { _, row -> "row:" + row.first().id },
                ) { rowIndex, row ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = UiTokens.SpaceL)
                            .animateItem(),
                        horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceM),
                    ) {
                        row.forEachIndexed { col, p ->
                            PlaylistGridCard(
                                name = p.name,
                                subtitle = "${p.trackCount} 首",
                                coverUrl = p.coverUrl,
                                modifier = Modifier.weight(1f),
                                index = rowIndex * 2 + col,
                                onClick = { onOpenPlaylist(MusicSourceType.LOCAL.id, p.id, p.name) },
                                onLongClick = { pendingDelete = p },
                            )
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(UiTokens.SpaceM))
                }
            }
        }
    }

    if (showCreateDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreateDialog = false },
            onConfirm = { name ->
                showCreateDialog = false
                vm.createPlaylist(name)
            },
        )
    }

    pendingDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除歌单") },
            text = { Text("确定删除「${p.name}」？该操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deletePlaylist(p.id)
                    pendingDelete = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

/** 顶部搜索栏：整块可点击，跳转到搜索页。玻璃胶囊 + 弹簧按压。 */
@Composable
private fun SearchBar(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .glassSurface(GlassLevel.CHIP.spec(), RoundedCornerShape(28.dp), elevation = 2.dp)
            .pressable(scaleDown = 0.97f, onClick = onClick),
        // Box 默认是 TopStart：内容只有一行时会贴着顶边，看着"字偏上"。
        // 显式居中，图标与文字才落在 56dp 胶囊的正中间。
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Search,
                contentDescription = "搜索",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "搜索歌曲、歌手、专辑",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 平台入口卡：品牌渐变 + 平台名 + 登录态/歌单数。弹簧按压 + 错开入场。 */
@Composable
private fun PlatformCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    gradient: Brush,
    modifier: Modifier = Modifier,
    index: Int = 0,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(96.dp)
            .shadow(6.dp, RoundedCornerShape(UiTokens.RadiusLarge), clip = false)
            .clip(RoundedCornerShape(UiTokens.RadiusLarge))
            .background(gradient)
            .pressable(scaleDown = 0.94f, onClick = onClick)
            .staggerEnter(index = index),
    ) {
        // 图标完整内嵌右上角：之前是出界水印（offset 后又被 clip），在窄卡上只剩半个音符，
        // 看着像渲染坏了。改成完整可见的浅色小图标，安静但干净。
        Icon(
            icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.35f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 10.dp, end = 10.dp)
                .size(22.dp),
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 12.dp, bottom = 12.dp, end = 8.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 渐变快捷入口卡片：图标放大置于右下。 */
@Composable
private fun GradientEntryCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    gradient: Brush,
    modifier: Modifier = Modifier,
    index: Int = 0,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(96.dp)
            .shadow(6.dp, RoundedCornerShape(UiTokens.RadiusLarge), clip = false)
            .clip(RoundedCornerShape(UiTokens.RadiusLarge))
            .background(gradient)
            .pressable(scaleDown = 0.94f, onClick = onClick)
            .staggerEnter(index = index),
    ) {
        Column(
            Modifier
                .align(Alignment.TopStart)
                .padding(start = 16.dp, top = 16.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.85f),
            )
        }
        Icon(
            icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.28f),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(56.dp)
                .padding(end = 4.dp, bottom = 2.dp),
        )
    }
}

@Composable
private fun SectionHeader(
    title: String,
    actionIcon: ImageVector? = null,
    actionContentDescription: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(
                start = UiTokens.SpaceL,
                end = UiTokens.SpaceS,
                top = UiTokens.SpaceL,
                bottom = UiTokens.SpaceS,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        if (actionIcon != null && onAction != null) {
            IconButton(onClick = onAction) {
                Icon(actionIcon, contentDescription = actionContentDescription)
            }
        }
    }
}

/** 最近播放卡片：封面主导的横滑单元，点击即续播。 */
@Composable
private fun RecentSongCard(song: Song, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(124.dp)
            .pressable(scaleDown = 0.95f, onClick = onClick)
            .padding(bottom = 6.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(UiTokens.RadiusLarge),
            color = Glass.Panel,
            shadowElevation = 2.dp,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        ) {
            if (song.coverUrl.isBlank()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    MaterialTheme.colorScheme.tertiaryContainer,
                                ),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.LibraryMusic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f),
                        modifier = Modifier.size(36.dp),
                    )
                }
            } else {
                Cover(song.coverUrl, Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            song.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            song.artist,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = UiTokens.SpaceL, vertical = 4.dp),
    )
}

@Composable
private fun CreatePlaylistDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建歌单") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text("歌单名称") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text("创建") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * 平台卡副标题：只显示昵称；没有昵称（QQ 只有 uin）或昵称本身就是一串号码
 * （网易云脱敏手机号）时，只写「已登录」—— 卡片只有三分之一屏宽，一串数字既挤又没意义。
 */
private fun accountSubtitle(acc: Account?): String = acc.displayLabel()
