package com.puremusic.ui.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.puremusic.model.Song
import com.puremusic.ui.components.Cover
import com.puremusic.ui.components.SongRow
import com.puremusic.ui.theme.UiTokens
import com.puremusic.ui.theme.pressable
import com.puremusic.ui.theme.staggerEnter

/**
 * 发现页：把网易云的「排行榜 / 歌单广场 / 推荐」三块内容搬进来。
 *
 * 对标 NCMusic 的发现页结构，但**不做无限嵌套滚动**：
 * 三个页签共用一个 [LazyColumn]，切页签只换 item 内容，滚动位置天然独立、切换零卡顿。
 *
 * 设计取舍：所有卡片一律「纯色/渐变背景 + 细边」，不上液态玻璃 —— 榜单页一屏就有
 * 十几张卡，玻璃每帧重绘会明显掉帧（本项目已踩过这个坑，见 SongRow 的注释）。
 */
@Composable
fun DiscoverScreen(
    onBack: () -> Unit,
    onOpenPlaylist: (sourceId: String, id: String, name: String) -> Unit,
    onOpenNowPlaying: () -> Unit = {},
) {
    val vm: DiscoverViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // 歌单广场触底自动续页
    val shouldLoadMore by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 3
        }
    }
    LaunchedEffect(shouldLoadMore, state.tab, state.squareMore) {
        if (state.tab == DiscoverTab.SQUARE && shouldLoadMore && state.squareMore) {
            vm.loadSquare(reset = false)
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // ── 顶栏 ──
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                "发现",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = vm::refresh) {
                Icon(Icons.Filled.Refresh, contentDescription = "刷新")
            }
        }

        TabRow(
            selected = state.tab,
            onSelect = vm::selectTab,
            modifier = Modifier.padding(horizontal = UiTokens.SpaceL, vertical = 4.dp),
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 28.dp),
        ) {
            if (state.loading && !state.anyContent) {
                item(key = "loading") {
                    Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
            state.error?.let { msg ->
                item(key = "err") { ErrorHint(msg, onRetry = vm::refresh) }
            }

            when (state.tab) {
                DiscoverTab.RECOMMEND -> recommendSection(
                    liked = state.liked,
                    daily = state.daily,
                    fm = state.fm,
                    curated = state.curated,
                    onOpenPlaylist = onOpenPlaylist,
                    onPlayAll = { song, list ->
                        vm.play(song, list)
                        onOpenNowPlaying()
                    },
                )

                DiscoverTab.TOPLIST -> {
                    if (state.toplists.isEmpty() && !state.loading) {
                        item(key = "tl-empty") { ErrorHint("榜单还没拉下来，点右上角重试") }
                    }
                    state.toplists.forEach { group ->
                        item(key = "tl-h-${group.name}") {
                            SectionHeader(group.name, "${group.tiles.size} 张榜")
                        }
                        item(key = "tl-${group.name}") {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = UiTokens.SpaceL),
                                horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceM),
                            ) {
                                items(group.tiles, key = { "tl-" + it.id }) { tile ->
                                    ToplistCard(
                                        tile = tile,
                                        onClick = { onOpenPlaylist("netease", tile.kindRef, tile.name) },
                                    )
                                }
                            }
                        }
                    }
                }

                DiscoverTab.SQUARE -> {
                    if (state.categories.isNotEmpty()) {
                        item(key = "cat") {
                            CategoryChips(
                                categories = state.categories,
                                selected = state.selectedCategory,
                                onSelect = vm::selectCategory,
                            )
                        }
                    }
                    if (state.square.isEmpty() && !state.squareLoadingMore) {
                        item(key = "sq-empty") {
                            ErrorHint(state.error ?: "歌单还在加载…", onRetry = { vm.loadSquare(true) })
                        }
                    }
                    itemsIndexed(state.square, key = { _, t -> "sq-" + t.id }) { index, tile ->
                        PlaylistRow(
                            tile = tile,
                            index = index,
                            onClick = { onOpenPlaylist("netease", tile.kindRef, tile.name) },
                        )
                    }
                    if (state.squareLoadingMore) {
                        item(key = "sq-more") {
                            Box(
                                Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  页签
// ═══════════════════════════════════════════════════════════════

/** 页签条：三个胶囊，未选中是淡底、选中是主题色。 */
@Composable
private fun TabRow(
    selected: DiscoverTab,
    onSelect: (DiscoverTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DiscoverTab.entries.forEach { tab ->
            val on = tab == selected
            val tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(
                        if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
                    )
                    .pressable(scaleDown = 0.95f, onClick = { onSelect(tab) })
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    tab.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = tint,
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  推荐页
// ═══════════════════════════════════════════════════════════════

private fun androidx.compose.foundation.lazy.LazyListScope.recommendSection(
    liked: List<Song>,
    daily: List<Song>,
    fm: List<Song>,
    curated: List<PlaylistTile>,
    onOpenPlaylist: (String, String, String) -> Unit,
    onPlayAll: (Song, List<Song>) -> Unit,
) {
    if (liked.isNotEmpty()) {
        item(key = "liked-h") { SectionHeader("我喜欢的音乐", "${liked.size} 首") }
        item(key = "liked") {
            SongStrip(
                songs = liked,
                tint = Color(0xFFE23A3A),
                icon = Icons.Filled.Favorite,
                onPlay = onPlayAll,
            )
        }
    }
    if (daily.isNotEmpty()) {
        item(key = "daily-h") { SectionHeader("每日推荐", "${daily.size} 首") }
        item(key = "daily") {
            SongStrip(
                songs = daily,
                tint = Color(0xFF2FBF71),
                icon = Icons.Filled.AutoAwesome,
                onPlay = onPlayAll,
            )
        }
    }
    // 私人 FM 的接口 2026-11 已 404，拉不到就不渲染这个区块 —— 显示一个空的「私人 FM」
    // 只会让人以为是加载中没回来。
    if (fm.isNotEmpty()) {
        item(key = "fm-h") { SectionHeader("私人 FM", "换一批") }
        item(key = "fm") {
            SongStrip(
                songs = fm,
                tint = Color(0xFF7C5CE6),
                icon = Icons.Filled.Radio,
                onPlay = onPlayAll,
            )
        }
    }
    if (curated.isNotEmpty()) {
        item(key = "cur-h") { SectionHeader("精品歌单", "网易云官方挑选") }
        itemsIndexed(curated, key = { _, t -> "cur-" + t.id }) { index, tile ->
            PlaylistRow(
                tile = tile,
                index = index,
                onClick = { onOpenPlaylist("netease", tile.kindRef, tile.name) },
            )
        }
    }
    if (liked.isEmpty() && daily.isEmpty() && curated.isEmpty()) {
        item(key = "rec-empty") {
            ErrorHint("登录网易云后这里会显示你的红心与每日推荐")
        }
    }
}

/**
 * 推荐曲目横滑：每天 30 首 / FM 几首，用横滑比纵列更合适（一天听不完 30 首）。
 * 卡片用纯色描边（不上玻璃），横滑十几张才不会掉帧。
 */
@Composable
private fun SongStrip(
    songs: List<Song>,
    tint: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onPlay: (Song, List<Song>) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = UiTokens.SpaceL),
        horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceM),
    ) {
        items(songs.take(20), key = { "strip-" + it.mediaId }) { song ->
            Column(
                Modifier
                    .width(140.dp)
                    .clip(RoundedCornerShape(UiTokens.RadiusLarge))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, tint.copy(alpha = 0.35f), RoundedCornerShape(UiTokens.RadiusLarge))
                    .pressable(scaleDown = 0.95f, onClick = { onPlay(song, songs) })
                    .padding(10.dp),
            ) {
                Cover(song.coverUrl, Modifier.fillMaxWidth().aspectRatio(1f), cornerRadius = 10.dp)
                Spacer(Modifier.height(8.dp))
                Text(
                    song.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    song.artist,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  榜单 / 歌单 卡片
// ═══════════════════════════════════════════════════════════════

/** 榜单横滑卡：封面 + 榜单名 + 更新频率。榜单封面本身就有榜单标识，够醒目。 */
@Composable
private fun ToplistCard(tile: PlaylistTile, onClick: () -> Unit) {
    Column(
        Modifier
            .width(136.dp)
            .pressable(scaleDown = 0.95f, onClick = onClick)
            .staggerEnter(),
    ) {
        Cover(tile.coverUrl, Modifier.fillMaxWidth().aspectRatio(1f), cornerRadius = 14.dp)
        Spacer(Modifier.height(6.dp))
        Text(
            tile.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            tile.subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 歌单纵列行：大封面在左、信息在右，歌单广场一屏能看 4~5 行。 */
@Composable
private fun PlaylistRow(tile: PlaylistTile, index: Int, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = UiTokens.SpaceL, vertical = 4.dp)
            .clip(RoundedCornerShape(UiTokens.RadiusLarge))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
            .pressable(scaleDown = 0.98f, onClick = onClick)
            .then(if (index < 8) Modifier.staggerEnter(index = index) else Modifier)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(tile.coverUrl, Modifier.size(72.dp), cornerRadius = 10.dp)
        Spacer(Modifier.width(UiTokens.SpaceM))
        Column(Modifier.weight(1f)) {
            Text(
                tile.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                tile.subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** 歌单分类横滑：首项是「全部」，其余来自 /api/playlist/highquality/tags。 */
@Composable
private fun CategoryChips(
    categories: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = UiTokens.SpaceL, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(listOf("全部") + categories, key = { "cat-" + it }) { name ->
            val on = (if (selected.isBlank()) "全部" else selected) == name
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(
                        if (on) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                    )
                    .pressable(scaleDown = 0.94f, onClick = { onSelect(if (name == "全部") "" else name) })
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            ) {
                Text(
                    name,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (on) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  公共小组件
// ═══════════════════════════════════════════════════════════════

@Composable
internal fun SectionHeader(title: String, trailing: String = "") {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(
                start = UiTokens.SpaceL,
                end = UiTokens.SpaceL,
                top = UiTokens.SpaceL,
                bottom = UiTokens.SpaceS,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        if (trailing.isNotBlank()) {
            Text(
                trailing,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun ErrorHint(text: String, onRetry: (() -> Unit)? = null) {
    Box(
        Modifier.fillMaxWidth().padding(horizontal = UiTokens.SpaceL, vertical = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (onRetry != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "点此重试",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.pressable(scaleDown = 0.95f, onClick = onRetry),
                )
            }
        }
    }
}
