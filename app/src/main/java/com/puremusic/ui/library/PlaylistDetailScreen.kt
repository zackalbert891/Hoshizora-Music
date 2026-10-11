package com.puremusic.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.puremusic.model.Song
import com.puremusic.ui.components.AddToPlaylistDialog
import com.puremusic.ui.components.SongRow

@Composable
fun PlaylistDetailScreen(
    sourceId: String,
    playlistId: String,
    name: String,
    onBack: () -> Unit,
) {
    val vm: PlaylistViewModel = hiltViewModel()

    LaunchedEffect(sourceId, playlistId) { vm.load(sourceId, playlistId) }

    val favoriteIds by vm.favoriteIds.collectAsStateWithLifecycle()
    val localPlaylists by vm.localPlaylists.collectAsStateWithLifecycle()
    var pendingSong by remember { mutableStateOf<Song?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            Text(name, style = MaterialTheme.typography.titleLarge, maxLines = 1, modifier = Modifier.weight(1f))
            if (vm.songs.isNotEmpty()) {
                TextButton(onClick = vm::playAll) { Text("播放全部") }
            }
        }

        when {
            vm.loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            vm.error != null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(vm.error.orEmpty(), color = MaterialTheme.colorScheme.error)
            }
            vm.songs.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    if (vm.removable) "这里还空空如也" else "该歌单暂无法加载歌曲",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> LazyColumn(Modifier.weight(1f)) {
                itemsIndexed(vm.songs, key = { _, it -> it.mediaId }) { index, song ->
                    val favorited = song.mediaId in favoriteIds
                    SongRow(
                        song = song,
                        onClick = { vm.play(song) },
                        modifier = Modifier.animateItem(),
                        index = index,
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
