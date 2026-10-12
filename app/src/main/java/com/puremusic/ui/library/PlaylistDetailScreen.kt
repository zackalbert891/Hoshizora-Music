package com.puremusic.ui.library

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.puremusic.model.MusicSourceType
import com.puremusic.model.Song
import com.puremusic.ui.components.AddToPlaylistDialog
import com.puremusic.ui.components.Cover
import com.puremusic.ui.components.SongRow
import com.puremusic.ui.theme.UiTokens
import com.puremusic.ui.theme.pressable

/**
 * 歌单详情页 / 榜单详情页。
 *
 * **榜单与歌单共用这一页**：网易云的榜单就是一张特殊歌单（id 相同），
 * 所以从「排行榜」和「歌单广场」点进来是同一份代码、同一条取数路径，
 * 只是榜单没有「创建者/描述/收藏」这些字段（值自然为空）。
 *
 * 版式对标 NCMusic：大封面头图 → 名称/计数 → 播放全部 + 收藏 → 标签 → 描述 → 曲目。
 */
@Composable
fun PlaylistDetailScreen(
    sourceId: String,
    playlistId: String,
    name: String,
    onBack: () -> Unit,
    onOpenNowPlaying: () -> Unit = {},
    onLogin: () -> Unit = {},
    onOpenArtist: (String) -> Unit = {},
    onOpenAlbum: (String) -> Unit = {},
) {
    val vm: PlaylistViewModel = hiltViewModel()
    val favoriteIds by vm.favoriteIds.collectAsStateWithLifecycle()
    val localPlaylists by vm.localPlaylists.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()
    var pendingSong by remember { mutableStateOf<Song?>(null) }
    val context = LocalContext.current

    LaunchedEffect(sourceId, playlistId) { vm.load(sourceId, playlistId, name) }

    // 收藏提示（未登录 / 失败）：弹一次即清
    LaunchedEffect(detail.hint) {
        val hint = detail.hint ?: return@LaunchedEffect
        android.widget.Toast.makeText(context, hint, android.widget.Toast.LENGTH_SHORT).show()
        vm.consumeHint()
    }

    val isNetease = sourceId == MusicSourceType.NETEASE.id

    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(bottom = 28.dp),
    ) {
        // ── 顶栏：返回 + 歌单名 + 刷新 ──
        item(key = "topbar") {
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Text(
                    detail.name.ifBlank { name },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (isNetease) {
                    IconButton(onClick = vm::reload) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                }
            }
        }

        // ── 头图区 ──
        item(key = "header") {
            PlaylistHeader(
                coverUrl = detail.coverUrl.ifBlank { vm.songs.firstOrNull()?.coverUrl.orEmpty() },
                name = detail.name.ifBlank { name },
                creatorName = detail.creatorName,
                trackCount = vm.songs.size,
                playCount = detail.playCount,
                subscribedCount = detail.subscribedCount,
                showSubscribe = isNetease,
                subscribed = detail.subscribed,
                subscribing = detail.subscribing,
                onToggleSubscribe = vm::toggleSubscribed,
                onPlayAll = {
                    vm.playAll()
                    onOpenNowPlaying()
                },
            )
        }

        // ── 标签 ──
        if (detail.tags.isNotEmpty()) {
            item(key = "tags") { TagRow(detail.tags) }
        }

        // ── 描述 ──
        if (detail.description.isNotBlank()) {
            item(key = "desc") {
                Text(
                    detail.description.take(DESC_MAX),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = UiTokens.SpaceL, vertical = 6.dp),
                )
            }
        }

        // ── 状态 ──
        when {
            vm.loading && vm.songs.isEmpty() -> item(key = "loading") {
                Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            vm.error != null && vm.songs.isEmpty() -> item(key = "err") {
                Text(
                    vm.error.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(UiTokens.SpaceL),
                )
            }

            vm.songs.isEmpty() -> item(key = "empty") {
                Text(
                    if (vm.removable) "这里还空空如也" else "该歌单暂无法加载歌曲",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(UiTokens.SpaceL, vertical = 24.dp),
                )
            }

            else -> itemsIndexed(vm.songs, key = { _, it -> it.mediaId }) { index, song ->
                val favorited = song.mediaId in favoriteIds
                SongRow(
                    song = song,
                    onClick = {
                        vm.play(song)
                        onOpenNowPlaying()
                    },
                    modifier = Modifier.animateItem(),
                    index = index,
                    // 只有网易云歌曲的 extra 里才有 arId / alId，换源来的其他平台不给入口。
                    // ⚠️ 这里必须**直接调用** onOpenArtist(...)：写成 `?.let { aid -> { onOpenArtist(x) } }`
                    // 的话内层花括号是函数对象（不是调用），点击会静默无效 —— 表现为「点了没反应」。
                    onArtistClick = song.extra[com.puremusic.source.netease.EXTRA_ARTIST_ID]
                        ?.toLongOrNull()?.takeIf { it > 0L }
                        ?.let { aid -> { onOpenArtist(aid.toString()) } },
                    onAlbumClick = song.extra[com.puremusic.source.netease.EXTRA_ALBUM_ID]
                        ?.toLongOrNull()?.takeIf { it > 0L }
                        ?.let { alId -> { onOpenAlbum(alId.toString()) } },
                    trailing = {
                        Row {
                            if (vm.removable) {
                                IconButton(onClick = { vm.remove(song) }) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = "移除",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            } else {
                                IconButton(onClick = { vm.toggleFavorite(song) }) {
                                    Icon(
                                        Icons.Filled.Favorite,
                                        contentDescription = if (favorited) "取消收藏" else "收藏",
                                        tint = if (favorited) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                }
                                IconButton(onClick = { pendingSong = song }) {
                                    Icon(
                                        Icons.Filled.Add,
                                        contentDescription = "添加到歌单",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    },
                )
            }
        }
    }

    pendingSong?.let { song ->
        AddToPlaylistDialog(
            playlists = localPlaylists,
            onDismiss = { pendingSong = null },
            onSelect = { vm.addToPlaylist(it, song) },
            onCreate = { vm.createPlaylistWith(it, song) },
        )
    }
}

/**
 * 歌单头图区：大封面 + 名称 + 计数 + 三个动作（播放全部 / 收藏 / 评论）。
 *
 * 用**模糊封面做背景 + 深色遮罩**，而不是纯色块 —— 网易云歌单页就是这个观感，
 * 封面色会渗进整块头图，一眼就能认出是哪张歌单。
 */
@Composable
private fun PlaylistHeader(
    coverUrl: String,
    name: String,
    creatorName: String,
    trackCount: Int,
    playCount: Long,
    subscribedCount: Long,
    showSubscribe: Boolean,
    subscribed: Boolean,
    subscribing: Boolean,
    onToggleSubscribe: () -> Unit,
    onPlayAll: () -> Unit,
) {
    val shape = RoundedCornerShape(UiTokens.RadiusCard)
    Box(
        Modifier
            .padding(horizontal = UiTokens.SpaceL, vertical = 8.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp),
    ) {
        // 背景层：同一张封面放大模糊，透明度低，仅作氛围
        if (coverUrl.isNotBlank()) {
            AsyncImage(
                model = coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = 0.18f,
                modifier = Modifier.matchParentSize(),
            )
        }
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Cover(
                    coverUrl,
                    Modifier
                        .size(104.dp)
                        .aspectRatio(1f),
                    cornerRadius = 12.dp,
                )
                Spacer(Modifier.width(UiTokens.SpaceM))
                Column(Modifier.weight(1f)) {
                    Text(
                        name.ifBlank { "歌单" },
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (creatorName.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CreatorAvatar(creatorName)
                            Spacer(Modifier.width(5.dp))
                            Text(
                                creatorName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        buildString {
                            if (trackCount > 0) append("$trackCount 首")
                            if (playCount > 0) {
                                if (isNotEmpty()) append(" · ")
                                append("${formatCount(playCount)}次播放")
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // 动作行：播放全部（实心）+ 收藏 / 评论（描边圆钮）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.pressable(
                        scaleDown = 0.95f,
                        onClick = onPlayAll,
                    ),
                ) {
                    Row(
                        Modifier.padding(horizontal = 18.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "播放全部",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }

                if (showSubscribe) {
                    Spacer(Modifier.width(UiTokens.SpaceM))
                    RoundAction(
                        icon = if (subscribed) Icons.Filled.Star else Icons.Filled.StarBorder,
                        label = when {
                            subscribing -> "…"
                            subscribed -> formatCount(subscribedCount)
                            else -> "收藏"
                        },
                        tint = if (subscribed) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        onClick = { if (!subscribing) onToggleSubscribe() },
                    )
                }
            }
        }
    }
}

/** 描边圆钮：图标 + 小字（收藏数）。 */
@Composable
private fun RoundAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
        modifier = Modifier.pressable(scaleDown = 0.93f, onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
            if (label.isNotBlank()) {
                Spacer(Modifier.width(5.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = tint,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 创建者头像：网易云头像 URL 带参数，只取前缀部分做 Coil 缓存键更稳。 */
@Composable
private fun CreatorAvatar(name: String) {
    Box(
        Modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.take(1).ifBlank { "?" },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/** 标签横滑：网易云歌单页头部下方的分类标签。 */
@Composable
private fun TagRow(tags: List<String>) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = UiTokens.SpaceL, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(tags, key = { "tag-" + it }) { tag ->
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                    .padding(horizontal = 11.dp, vertical = 5.dp),
            ) {
                Text(
                    tag,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

private const val DESC_MAX = 300

/** 大数格式化：播放量动辄千万，原始数字会把整行撑爆。 */
private fun formatCount(n: Long): String = when {
    n <= 0 -> ""
    n < 10_000 -> "$n"
    n < 100_000_000 -> "${n / 10_000}万"
    else -> "${n / 100_000_000}亿"
}
