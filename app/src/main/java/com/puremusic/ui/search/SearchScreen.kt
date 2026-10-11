package com.puremusic.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.puremusic.model.Song
import com.puremusic.source.match.SongGroup
import com.puremusic.ui.components.SongRow
import com.puremusic.ui.theme.Glass
import com.puremusic.ui.theme.UiTokens

@Composable
fun SearchScreen(
    presetSourceId: String,
    onBack: () -> Unit,
    onOpenNowPlaying: () -> Unit = {},
) {
    val vm: SearchViewModel = hiltViewModel()

    LaunchedEffect(presetSourceId) { vm.presetSource(presetSourceId) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = UiTokens.SpaceL, top = UiTokens.SpaceS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            Text("搜索", style = MaterialTheme.typography.headlineMedium)
        }

        // 圆角填充式搜索框：与音乐库顶部搜索栏视觉统一，并支持键盘「搜索」键直接触发
        Row(
            Modifier.fillMaxWidth().padding(horizontal = UiTokens.SpaceL),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = vm.keyword,
                onValueChange = { vm.keyword = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("歌曲 / 歌手 / 专辑") },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                // 显式搜索按钮：仅靠输入法「搜索」键会让人找不到触发入口
                trailingIcon = {
                    IconButton(onClick = vm::search) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "搜索")
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.search() }),
            )
        }

        when {
            vm.loading -> Box(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            vm.error != null -> Box(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(vm.error.orEmpty(), color = MaterialTheme.colorScheme.error)
            }

            vm.results.isEmpty() && vm.weakResults.isEmpty() -> Box(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                EmptySearchHint()
            }

            else -> ResultList(
                vm = vm,
                onOpenNowPlaying = onOpenNowPlaying,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * 结果列表：
 * - 聚合搜索用「同曲多源并组」行（Listen1 式），单源搜索用普通行；
 * - 末尾统一挂一个折叠的「其它结果」分区，收纳弱相关结果，保证主列表干净。
 */
@Composable
private fun ResultList(
    vm: SearchViewModel,
    onOpenNowPlaying: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val onPlay: (Song) -> Unit = {
        vm.play(it)
        onOpenNowPlaying()
    }
    // 统一用普通歌曲行：不再挂"QQ音乐 / 网易云 / 酷我"的平台芯片，
    // 聚合搜索直接展示匹配到的主曲，界面干净，点哪首就播哪首（取流时自动选可用源）。
    val rows = vm.playableRows()
    val weakRows = vm.weakPlayableRows()
    val weakCount = weakRows.size

    LazyColumn(
        modifier,
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        itemsIndexed(rows, key = { _, it -> it.mediaId }) { index, song ->
            SongRow(
                song = song,
                onClick = { onPlay(song) },
                modifier = Modifier.animateItem(),
                index = index,
            )
        }

        if (weakCount > 0) {
            item {
                WeakResultsToggle(
                    count = weakCount,
                    expanded = vm.showWeak,
                    onToggle = { vm.showWeak = !vm.showWeak },
                )
            }
            if (vm.showWeak) {
                itemsIndexed(weakRows, key = { _, it -> "w:" + it.mediaId }) { index, song ->
                    SongRow(
                        song = song,
                        onClick = { onPlay(song) },
                        modifier = Modifier.animateItem(),
                        index = index,
                    )
                }
            }
        }
    }
}

/** 「其它结果」折叠开关：弱相关结果默认收起，避免污染主列表。 */
@Composable
private fun WeakResultsToggle(
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = UiTokens.SpaceL, vertical = 12.dp)
            .clip(RoundedCornerShape(50))
            .background(Glass.Panel)
            .border(1.dp, Glass.Border, RoundedCornerShape(50))
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            if (expanded) "收起其它结果（$count）" else "其它结果（$count）",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 空状态：图标 + 主副标题，比单行文字更有引导性。 */
@Composable
private fun EmptySearchHint() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(24.dp),
    ) {
        Icon(
            Icons.Filled.SearchOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text("输入关键词开始搜索", style = MaterialTheme.typography.titleMedium)
    }
}
