@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.puremusic.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.puremusic.ui.theme.GlassLevel
import com.puremusic.ui.theme.UiTokens
import com.puremusic.ui.theme.glassSurface
import com.puremusic.ui.theme.pressable
import com.puremusic.ui.theme.spec
import com.puremusic.ui.theme.staggerEnter

/**
 * 歌单网格卡片（音乐库 / 平台页共用）。
 *
 * 卡片本体是液态玻璃，封面图压在玻璃上但四周留白 —— 留白让玻璃的边缘折射与内缘高光
 * 露出来，这才是"玻璃卡片"而不是"贴了张图的白板"。
 */
@Composable
fun PlaylistGridCard(
    name: String,
    subtitle: String,
    coverUrl: String,
    modifier: Modifier = Modifier,
    index: Int = 0,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val cardShape = RoundedCornerShape(UiTokens.RadiusLarge)
    val clickModifier = if (onLongClick != null) {
        Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick,
            onLongClickLabel = "删除歌单",
        )
    } else {
        Modifier.pressable(scaleDown = 0.96f, onClick = onClick)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .glassSurface(GlassLevel.CARD.spec(), cardShape, elevation = 3.dp)
            .then(clickModifier)
            .staggerEnter(index = index)
            .padding(7.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(UiTokens.RadiusMedium)),
            contentAlignment = Alignment.Center,
        ) {
            if (coverUrl.isBlank()) {
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
                        Icons.Filled.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f),
                        modifier = Modifier.size(40.dp),
                    )
                }
            } else {
                Cover(coverUrl, Modifier.fillMaxSize(), cornerRadius = 0.dp)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
