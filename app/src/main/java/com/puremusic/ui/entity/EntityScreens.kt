package com.puremusic.ui.entity

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.puremusic.model.Song
import com.puremusic.ui.components.Cover
import com.puremusic.ui.components.SongRow
import com.puremusic.ui.theme.UiTokens
import com.puremusic.ui.theme.pressable

/**
 * 歌手页：头像 + 简介 + 专辑网格。
 *
 * **网易云与 QQ 共用这一页**：网易云拿不到「歌手热门歌曲」（相关接口已全下线），
 * 所以给的是「资料 + 专辑列表」；QQ 同样只给「资料 + 专辑列表」。
 * 点进专辑页才有完整曲目。不要为了「看起来完整」去接一个必然空白的热门歌曲区。
 */
@Composable
fun ArtistScreen(
    sourceId: String,
    artistId: String,
    onBack: () -> Unit,
    onOpenAlbum: (albumId: String, name: String) -> Unit,
    onOpenArtist: (artistId: String) -> Unit = {},
    onOpenNowPlaying: () -> Unit = {},
) {
    val vm: ArtistViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(sourceId, artistId) { vm.load(sourceId, artistId) }

    val detail = state.detail
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 28.dp),
    ) {
        item(key = "topbar") {
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Text(
                    detail?.name.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { vm.load(sourceId, artistId) }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                }
            }
        }

        item(key = "header") {
            Column(Modifier.padding(horizontal = UiTokens.SpaceL, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Cover(detail?.avatarUrl.orEmpty(), Modifier.size(96.dp), cornerRadius = 48.dp)
                    Spacer(Modifier.width(UiTokens.SpaceM))
                    Column(Modifier.weight(1f)) {
                        Text(
                            detail?.name.orEmpty().ifBlank { "歌手" },
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val alias = detail?.alias.orEmpty()
                        if (alias.isNotBlank()) {
                            Text(
                                alias,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            buildString {
                                val n = detail?.albumCount ?: 0
                                val s = detail?.songCount ?: 0
                                if (n > 0) append("$n 张专辑")
                                if (s > 0) {
                                    if (isNotEmpty()) append(" · ")
                                    append("$s 首作品")
                                }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                val desc = detail?.briefDesc.orEmpty()
                if (desc.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item(key = "albumsTitle") {
            Text(
                "专辑",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    start = UiTokens.SpaceL, end = UiTokens.SpaceL,
                    top = 14.dp, bottom = 6.dp,
                ),
            )
        }

        if (state.loading && state.albums.isEmpty()) {
            item(key = "loading") {
                Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        } else if (state.albums.isEmpty()) {
            item(key = "empty") {
                Text(
                    state.error ?: "暂无专辑",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(UiTokens.SpaceL, vertical = 24.dp),
                )
            }
        } else {
            item(key = "albums") {
                AlbumGrid(
                    albums = state.albums,
                    onOpenAlbum = { onOpenAlbum(it.id, it.name) },
                )
            }
            // 专辑列表已经一次性铺完（不是懒加载列表），翻页由底部按钮显式触发，
            // 不用滚动监听 —— 嵌套懒加载滚动会跟外层 LazyColumn 抢手势。
            if (state.hasMore) {
                item(key = "more") {
                    Text(
                        "加载更多专辑",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !state.loading) { vm.loadMoreAlbums() }
                            .padding(vertical = 14.dp),
                    )
                }
            }
            if (state.loading) {
                item(key = "loadingMore") {
                    Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                }
            }
        }
    }
}

/** 专辑网格：3 列方形封面 + 名称 / 曲目数。 */
@Composable
private fun AlbumGrid(
    albums: List<UiAlbumCard>,
    onOpenAlbum: (UiAlbumCard) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(horizontal = UiTokens.SpaceL, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            // 嵌在 LazyColumn 里，必须给固定高度，否则测量不出内容高度、整页空白
            .height(((albums.size + 2) / 3 * 168).dp.coerceAtLeast(168.dp)),
        userScrollEnabled = false,
    ) {
        items(albums, key = { "al-${it.id}" }) { album ->
            Column(
                Modifier
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .clickable { onOpenAlbum(album) },
            ) {
                Cover(album.coverUrl, Modifier.fillMaxWidth().aspectRatio(1f), cornerRadius = 10.dp)
                Spacer(Modifier.height(5.dp))
                Text(
                    album.name,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (album.size > 0) {
                    Text(
                        "${album.size} 首",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * 专辑页：大封面头图 + 播放全部 + 曲目。
 *
 * 曲目直接来自 `/api/v1/album/<id>` 的 `songs`，是全量的，不需要翻页。
 */
@Composable
fun AlbumScreen(
    sourceId: String,
    albumId: String,
    onBack: () -> Unit,
    onOpenArtist: (artistId: String, name: String) -> Unit = { _, _ -> },
    onOpenAlbum: (albumId: String, name: String) -> Unit = { _, _ -> },
    onOpenNowPlaying: () -> Unit = {},
) {
    val vm: AlbumViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(sourceId, albumId) { vm.load(sourceId, albumId) }

    val detail = state.detail
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 28.dp),
    ) {
        item(key = "topbar") {
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Text(
                    detail?.name.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { vm.load(sourceId, albumId) }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                }
            }
        }

        item(key = "header") {
            Column(Modifier.padding(horizontal = UiTokens.SpaceL, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(120.dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        val cover = detail?.coverUrl.orEmpty()
                        if (cover.isNotBlank()) {
                            AsyncImage(
                                model = cover,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    Spacer(Modifier.width(UiTokens.SpaceM))
                    Column(Modifier.weight(1f)) {
                        Text(
                            detail?.name.orEmpty().ifBlank { "专辑" },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val artistName = detail?.artistName.orEmpty()
                        if (artistName.isNotBlank()) {
                            val aid = detail?.artistId.orEmpty()
                            Text(
                                artistName,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (aid.isNotBlank()) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = if (aid.isNotBlank()) {
                                    Modifier.clickable { onOpenArtist(aid, artistName) }
                                } else {
                                    Modifier
                                },
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        val count = state.songs.size
                        val year = (detail?.publishTime ?: 0L)
                            .takeIf { it > 0L }
                            ?.let { simpleYear(it) }
                            .orEmpty()
                        Text(
                            listOf(count.takeIf { it > 0 }?.let { "$it 首" }.orEmpty(), year)
                                .filter { it.isNotBlank() }
                                .joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        if (state.songs.isNotEmpty()) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.pressable(scaleDown = 0.95f) {
                                    vm.playAll()
                                    onOpenNowPlaying()
                                },
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
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
                        }
                    }
                }
                val desc = detail?.description.orEmpty()
                if (desc.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        desc.take(400),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        when {
            state.loading && state.songs.isEmpty() -> item(key = "loading") {
                Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            state.songs.isEmpty() -> item(key = "empty") {
                Text(
                    state.error ?: "该专辑暂无可用曲目",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(UiTokens.SpaceL, vertical = 24.dp),
                )
            }

            else -> items(state.songs, key = { it.mediaId }) { song ->
                SongRow(
                    song = song,
                    onClick = { vm.play(song); onOpenNowPlaying() },
                )
            }
        }
    }
}

/** publishTime 是毫秒时间戳，取年份即可。 */
private fun simpleYear(ms: Long): String {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = ms }
    return "${cal.get(java.util.Calendar.YEAR)}"
}