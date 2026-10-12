package com.puremusic.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.puremusic.model.Song
import com.puremusic.ui.theme.GlassLevel
import com.puremusic.ui.theme.UiTokens
import com.puremusic.ui.theme.glassSurface
import com.puremusic.ui.theme.pressable
import com.puremusic.ui.theme.spec
import com.puremusic.ui.theme.staggerEnter

/** 统一的歌曲封面：有图加载图，无图显示占位音符。大圆角（默认 14dp）。 */
@Composable
fun Cover(url: String, modifier: Modifier = Modifier, cornerRadius: Dp = 14.dp) {
    val shape = RoundedCornerShape(cornerRadius)
    if (url.isBlank()) {
        Surface(
            modifier = modifier.clip(shape),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    } else {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(url)
                .crossfade(true)
                .build(),
            contentDescription = null,
            modifier = modifier.clip(shape),
            contentScale = ContentScale.Crop,
        )
    }
}

/**
 * 歌曲列表行：液态玻璃薄层 + 弹簧按压 + 逐项错开入场。
 *
 * ROW 级玻璃刻意不做逐行背板取样（GlassLevel.ROW.refracts = false）：一屏十几行
 * 就是十几条"调色 → 模糊 → AGSL"全背板流水线，而背景本身已是高斯糊过的封面，
 * 肉眼分辨不出这一层，观感几乎不变但省下大量 GPU。
 *
 * @param onArtistClick 传入时副标题（歌手名）可点，用于跳歌手页；不传就是普通文字
 *   （不能无差别做成可点，那样点哪儿都跳页）。
 * @param onAlbumClick 同理，作用于 [Song.album]。
 *
 * ## 命中分区（改这个文件前务必读完）
 *
 * 整行播放与副标题跳歌手页的命中范围天然重叠。Compose 里 `clickable` 都是
 * `pointerInput`，**同一条 hit path 上先注册者先消费**；把歌名和歌手名各自做成
 * clickable 在 LazyColumn（带 `animateItem()`，会多出一层 LayoutNode）里实测不生效。
 *
 * 最终方案：**层级分派**。
 * - 底层：整行 `pressable`（点哪儿都能播，保留原有手感）；
 * - 上层：只有需要跳转的副标题才生成一个「透明点击块」，位置按内容排布对齐，
 *   覆盖在整行之上 —— 后加入的节点在 hit path 里更靠后但**同级兄弟中最后声明者胜**，
 *   所以它能抢到底层的手势。
 *
 * 不要改回「父 clickable + 子 clickable」的嵌套写法，会复现「点歌手名却开始播放」。
 */
@Composable
fun SongRow(
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    index: Int = 0,
    onArtistClick: (() -> Unit)? = null,
    onAlbumClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(UiTokens.RadiusLarge)
    val showAlbum = onAlbumClick != null && song.album.isNotBlank() && song.album != song.artist
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = UiTokens.SpaceL, vertical = 5.dp)
            .glassSurface(GlassLevel.ROW.spec(), shape)
            .then(if (index > 0) Modifier.staggerEnter(index = index) else Modifier),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pressable(scaleDown = 0.975f, onClick = onClick)
                .padding(horizontal = UiTokens.SpaceM, vertical = UiTokens.SpaceM),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Cover(song.coverUrl, Modifier.size(52.dp))
            Spacer(Modifier.width(UiTokens.SpaceM))
            Column(Modifier.weight(1f)) {
                Text(song.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                if (song.artist.isNotBlank()) {
                    Text(
                        song.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (onArtistClick != null) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                if (showAlbum) {
                    Text(
                        song.album,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                        maxLines = 1,
                    )
                }
            }
            trailing?.invoke()
        }

        // 副标题点击块：只覆盖「歌手名 / 专辑名」所在的横向条带，垂直位置按行内排布算。
        // 用 Spacer 而不是 Box：不需要绘制任何东西，只需要占位与接收点击。
        if (onArtistClick != null || showAlbum) {
            Row(
                modifier = Modifier
                    .matchParentSize()
                    .padding(
                        // 左侧让开封面（52dp）与两段间距
                        start = UiTokens.SpaceM + 52.dp + UiTokens.SpaceM,
                        end = UiTokens.SpaceM,
                        // 顶部让开「上内边距 + 歌名行高（约 24dp）」
                        top = UiTokens.SpaceM + 24.dp,
                        bottom = UiTokens.SpaceM / 2,
                    ),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.Start,
            ) {
                if (onArtistClick != null) {
                    Spacer(
                        Modifier
                            .fillMaxWidth()
                            .height(SUBTITLE_ARTIST_HIT)
                            .clickable(onClick = onArtistClick),
                    )
                }
                if (showAlbum) {
                    Spacer(
                        Modifier
                            .fillMaxWidth()
                            .height(SUBTITLE_ALBUM_HIT)
                            .clickable(onClick = onAlbumClick ?: {}),
                    )
                }
            }
        }
    }
}

/**
 * 副标题点击块的行高。
 *
 * 比文字本身略高一点，手指更好点；又比下一行小，保证两条点击带不互相吞掉。
 * 这两个值与 [SongRow] 里歌手名 / 专辑名两行的实际排布对应，改字号时一起改。
 */
private val SUBTITLE_ARTIST_HIT = 20.dp
private val SUBTITLE_ALBUM_HIT = 17.dp