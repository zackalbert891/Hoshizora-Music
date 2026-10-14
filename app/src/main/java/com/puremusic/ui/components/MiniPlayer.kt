package com.puremusic.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.puremusic.model.Song
import com.puremusic.ui.theme.PRESS_SCALE_DEFAULT
import com.puremusic.ui.theme.UiTokens
import com.puremusic.ui.theme.pressable

/**
 * 底部迷你播放器：一条**紧凑的**胶囊（封面 + 歌名 + 播放键），
 * 进度是贴着卡片下沿的一条 2dp 细线。
 *
 * 为什么不用液态玻璃：这里贴在屏幕最下方、又常驻在所有页面之上，
 * 一旦走"背板取样 + 模糊 + 折射"的玻璃链路，取到的永远是身后那块
 * 半透明 / 亮白内容，卡片中部会莫名其妙亮出一条**白色长方形**（像渲染坏了）。
 * 所以这一条改成**确定性的封面色渐变**：底色仍跟随主题，只是往封面主色偏一点，
 * 再压一条同色细边 —— 不管身后是什么页面，出来都是一块干净、有颜色倾向的条。
 */
@Composable
fun MiniPlayer(
    song: Song,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float = -1f,
    /** 当前封面的主色：整条播放器按它染色，浅色主题下才不会是一片死白。 */
    accent: Color = Color.Unspecified,
) {
    val shape = RoundedCornerShape(UiTokens.RadiusXLarge)
    val scheme = MaterialTheme.colorScheme
    val tint = accent.takeIf { it != Color.Unspecified }

    // 上沿稍深 + 下沿回归表面色：一条极轻的竖向渐变，比纯色更像"有厚度的条"
    val bottom = if (tint != null) lerp(scheme.surface, tint, 0.16f) else scheme.surface
    val top = if (tint != null) lerp(bottom, tint, 0.26f) else lerp(bottom, scheme.surfaceVariant, 0.6f)
    val hairline = (tint ?: scheme.onSurface).copy(alpha = 0.20f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = UiTokens.SpaceL, vertical = 4.dp)
            .shadow(6.dp, shape, clip = false)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(top, bottom)))
            .border(0.8.dp, hairline, shape)
            .pressable(scaleDown = 0.98f, onClick = onClick),
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RotatingMiniCover(coverUrl = song.coverUrl, isPlaying = isPlaying)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = song.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = scheme.onSurface,
                    )
                    Text(
                        text = song.artist,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = scheme.onSurfaceVariant,
                    )
                }
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .pressable(scaleDown = PRESS_SCALE_DEFAULT, onClick = onToggle),
                    contentAlignment = Alignment.Center,
                ) {
                    AnimatedContent(
                        targetState = isPlaying,
                        transitionSpec = {
                            (scaleIn(initialScale = 0.6f) + fadeIn()) togetherWith
                                (scaleOut(targetScale = 0.6f) + fadeOut())
                        },
                        label = "miniPlayPause",
                    ) { playing ->
                        Icon(
                            if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (playing) "暂停" else "播放",
                            tint = scheme.onSurface,
                        )
                    }
                }
            }
            // 贴底胶囊：未播放时是一条极淡的底边（当作分隔线），播放时左侧亮起主色。
            val progressShape = RoundedCornerShape(percent = 50)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(progressShape)
                    .background(scheme.onSurface.copy(alpha = 0.10f)),
            ) {
                if (progress >= 0f) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                            .height(3.dp)
                            .clip(progressShape)
                            .background(scheme.primary),
                    )
                }
            }
        }
    }
}
