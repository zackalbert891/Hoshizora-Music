package com.puremusic.ui.nowplaying

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.puremusic.model.Lyrics

/**
 * 逐行滚动的歌词视图，高亮当前行。
 *
 * @param showTranslation 是否显示翻译行。翻译数据（`Lyrics.translation`）本来就一直在
 *   取（歌词接口 `tv=-1` 一起返回、磁盘缓存也分 L/T 两段存），只是以前没渲染 ——
 *   所以这里不需要任何额外请求，开关一开就有。
 *
 * 翻译对齐方式：**按时间戳精确匹配原文行**，匹配不上的整段丢弃。
 * 不用「就近时间」对齐 —— 网易云的翻译行时间戳与原文完全一致，就近对齐在双语
 * 副歌密集的段落会把翻译错配到相邻行，读起来比不显示更糟。
 */
@Composable
fun LyricsView(
    lyrics: Lyrics,
    positionMs: Long,
    showTranslation: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (lyrics.lines.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("暂无歌词", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val currentIndex = lyrics.lines.indexOfLast { it.timeMs <= positionMs }.coerceAtLeast(0)
    val listState = rememberLazyListState()

    // 翻译按时间戳建索引，只在 lyrics 变化时算一次（歌词页每秒重组，不能每帧建 Map）。
    val translationByTime = remember(lyrics) {
        lyrics.translation
            .filter { it.text.isNotBlank() }
            .associate { it.timeMs to it.text }
    }

    LaunchedEffect(currentIndex) {
        listState.animateScrollToItem(currentIndex)
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 160.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        itemsIndexed(lyrics.lines) { index, line ->
            val isCurrent = index == currentIndex
            val translation = if (showTranslation) translationByTime[line.timeMs] else null
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = line.text,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = if (isCurrent) 20.sp else 16.sp,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                )
                if (translation != null) {
                    Text(
                        text = translation,
                        // 翻译永远比原文弱一档，不跟着当前行加粗放大，否则双行会抢掉原行的落点
                        color = if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.72f)
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        fontSize = if (isCurrent) 14.sp else 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
        }
    }
}