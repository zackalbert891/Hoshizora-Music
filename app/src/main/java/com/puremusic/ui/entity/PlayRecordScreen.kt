package com.puremusic.ui.entity

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.puremusic.source.netease.EXTRA_ARTIST_ID
import com.puremusic.ui.components.SongRow
import com.puremusic.ui.theme.UiTokens
import com.puremusic.ui.theme.pressable

/**
 * 听歌排行页。
 *
 * 版式对标网易云「听歌排行」：顶部「本周 / 所有时间」切换 + 播放全部，下面是带次数的榜单。
 * 次数用**次**为单位（服务端只给次数），不做「x 小时」这种服务端没有的数据。
 */
@Composable
fun PlayRecordScreen(
    onBack: () -> Unit,
    onLogin: () -> Unit = {},
    onOpenArtist: (String) -> Unit = {},
    onOpenNowPlaying: () -> Unit = {},
) {
    val vm: PlayRecordViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.load() }

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
                    "听歌排行",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { vm.load(force = true) }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                }
            }
        }

        item(key = "filter") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = UiTokens.SpaceL, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            ) {
                listOf(false to "所有时间", true to "本周").forEach { (weekly, label) ->
                    FilterChip(
                        selected = state.weekly == weekly,
                        onClick = { vm.setWeekly(weekly) },
                        label = { Text(label) },
                    )
                }
                Spacer(Modifier.weight(1f))
                if (state.records.isNotEmpty()) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.pressable(scaleDown = 0.95f) {
                            vm.playAll()
                            onOpenNowPlaying()
                        },
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(17.dp),
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

        when {
            state.needLogin -> item(key = "login") {
                Column(
                    Modifier.fillMaxWidth().padding(UiTokens.SpaceL).padding(top = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "登录网易云账号后才能查看听歌排行",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = onLogin) { Text("去登录") }
                }
            }

            state.loading && state.records.isEmpty() -> item(key = "loading") {
                Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            state.records.isEmpty() -> item(key = "empty") {
                Text(
                    state.error ?: "暂无听歌记录",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(UiTokens.SpaceL, vertical = 30.dp),
                )
            }

            else -> itemsIndexed(state.records, key = { _, r -> "rec-${r.songId}" }) { index, rec ->
                SongRow(
                    song = rec.song,
                    onClick = { vm.play(rec.song); onOpenNowPlaying() },
                    trailing = {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                "${rec.playCount}",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = if (index < 3) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "次",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                )
            }
        }
    }
}