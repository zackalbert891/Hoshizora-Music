package com.puremusic.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
 */
@Composable
fun SongRow(
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    index: Int = 0,
    trailing: @Composable (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(UiTokens.RadiusLarge)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = UiTokens.SpaceL, vertical = 5.dp)
            .glassSurface(GlassLevel.ROW.spec(), shape)
            .pressable(scaleDown = 0.975f, onClick = onClick)
            .then(if (index > 0) Modifier.staggerEnter(index = index) else Modifier),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = UiTokens.SpaceM, vertical = UiTokens.SpaceM),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Cover(song.coverUrl, Modifier.size(52.dp))
            Spacer(Modifier.width(UiTokens.SpaceM))
            Column(Modifier.weight(1f)) {
                Text(song.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                Text(
                    song.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            trailing?.invoke()
        }
    }
}
