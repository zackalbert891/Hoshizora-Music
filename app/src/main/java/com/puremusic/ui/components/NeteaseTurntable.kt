package com.puremusic.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import coil.compose.AsyncImage
import coil.request.ImageRequest

/** 唱针角度：播放时落在唱片胶木区上，暂停时向右上方抬起离开唱片。 */
private const val NEEDLE_ANGLE_PLAYING = -10f
private const val NEEDLE_ANGLE_PAUSED = -40f

/** 唱片中心在容器中的相对位置与半径（唱针坐标按同一套比例计算）。 */
private const val DISC_RATIO = 0.82f
private const val COVER_RATIO = 0.62f

/**
 * 网易云式黑胶唱机：外圈黑色胶木盘 + 内嵌圆形封面，右上角一根会起落的唱针。
 *
 * 与旧 [RotatingVinylDisc] 的区别：
 * - 旧实现是「封面铺满整个圆 + 叠加纹路环」，看起来是一个旋转的圆图；
 *   网易云的正宗做法是**封面只占内圈 62%**，外面一圈黑色胶木才是唱片本体；
 * - **新增唱针**：这是网易云播放页最具辨识度的元素——播放时唱针落到唱片上，
 *   暂停时抬起移开。缺了它，再怎么调配色都不像网易云。
 *
 * 唱针坐标（相对容器宽度 w，容器为正方形）：
 * - 转轴 pivot = (0.70w, 0.04w)，臂长 0.30w；
 * - 播放 -10°：唱头落在 (≈0.752w, ≈0.336w)，距圆心 0.30w —— 恰好在封面圈(0.254w)之外、
 *   唱片边缘(0.41w)之内，即压在**胶木区**上（与网易云一致，不会盖住封面）；
 * - 暂停 -40°：唱头移到 (≈0.893w, ≈0.270w)，距圆心 0.455w > 唱片半径 0.41w，完全离开唱片。
 */
@Composable
fun NeteaseTurntable(
    coverUrl: String,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    onDominantColor: ((Color) -> Unit)? = null,
) {
    val rotation = rememberCoverRotation(isPlaying)
    val needleAngle by animateFloatAsState(
        targetValue = if (isPlaying) NEEDLE_ANGLE_PLAYING else NEEDLE_ANGLE_PAUSED,
        animationSpec = tween(450, easing = FastOutSlowInEasing),
        label = "needleAngle",
    )
    var coverLoaded by remember(coverUrl) { mutableStateOf(false) }

    BoxWithConstraints(modifier = modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        val discSize = maxWidth * DISC_RATIO

        // ── 黑胶唱片（旋转）
        Box(
            Modifier
                .size(discSize)
                .shadow(
                    elevation = 22.dp,
                    shape = CircleShape,
                    ambientColor = Color.Black.copy(alpha = 0.5f),
                    spotColor = Color.Black.copy(alpha = 0.7f),
                )
                .clip(CircleShape)
                .graphicsLayer { rotationZ = rotation },
        ) {
            // 胶木盘面：径向渐变模拟黑胶反光
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Color(0xFF2A2A30),
                                Color(0xFF111114),
                                Color(0xFF08080A),
                            ),
                            radius = discSize.value * 2.2f,
                        )
                    )
            )
            // 胶木纹路环（由外向内逐渐变淡，模拟唱片沟槽）
            listOf(0.94f, 0.86f, 0.78f, 0.70f, 0.66f).forEachIndexed { i, f ->
                Box(
                    Modifier
                        .fillMaxSize(f)
                        .align(Alignment.Center)
                        .clip(CircleShape)
                        .border(1.dp, Color.White.copy(alpha = 0.07f - i * 0.008f), CircleShape)
                )
            }
            // 内嵌封面（占唱片 62%）
            Box(
                Modifier
                    .fillMaxSize(COVER_RATIO)
                    .align(Alignment.Center)
                    .clip(CircleShape)
            ) {
                if (coverUrl.isBlank()) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color(0xFF24242C)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.MusicNote,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.35f),
                            modifier = Modifier.size(discSize * 0.18f),
                        )
                    }
                } else {
                    val coverAlpha by animateFloatAsState(
                        targetValue = if (coverLoaded) 1f else 0f,
                        animationSpec = tween(400, easing = FastOutSlowInEasing),
                        label = "coverAlpha",
                    )
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(coverUrl)
                            .allowHardware(false) // 主色提取要读像素，硬件位图不支持 getPixel
                            .build(),
                        contentDescription = "封面",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().alpha(coverAlpha),
                        onError = { coverLoaded = false },
                        onSuccess = { result ->
                            coverLoaded = true
                            onDominantColor?.let { cb ->
                                val bitmap = runCatching { result.result.drawable.toBitmap() }.getOrNull()
                                if (bitmap != null) cb(Color(dominantColorFrom(bitmap).toInt()))
                            }
                        },
                    )
                    if (!coverLoaded) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.radialGradient(
                                        colors = listOf(Color(0xFF2E2E38), Color(0xFF14141A)),
                                    )
                                )
                        )
                    }
                }
            }
            // 盘面高光
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.10f),
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.28f),
                            ),
                            radius = discSize.value * 2.0f,
                        )
                    )
            )
            // 中心孔
            Box(
                Modifier
                    .size(discSize * 0.075f)
                    .align(Alignment.Center)
                    .clip(CircleShape)
                    .background(Color(0xFF08080A))
                    .border(1.5.dp, Color.White.copy(alpha = 0.22f), CircleShape)
            )
        }

        // ── 唱针（画在最上层，压在唱片之上）
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val pivot = Offset(w * 0.70f, w * 0.04f)
            val armLen = w * 0.30f
            rotate(degrees = needleAngle, pivot = pivot) {
                // 转轴底座
                drawCircle(color = Color(0xFF35353E), radius = w * 0.050f, center = pivot)
                drawCircle(color = Color(0xFF12121A), radius = w * 0.032f, center = pivot)
                // 唱臂
                drawLine(
                    color = Color(0xFFC6C6D0),
                    start = Offset(pivot.x, pivot.y + w * 0.015f),
                    end = Offset(pivot.x, pivot.y + armLen),
                    strokeWidth = w * 0.019f,
                    cap = StrokeCap.Round,
                )
                // 唱头
                drawRoundRect(
                    color = Color(0xFFE2E2EA),
                    topLeft = Offset(pivot.x - w * 0.026f, pivot.y + armLen - w * 0.014f),
                    size = Size(w * 0.052f, w * 0.074f),
                    cornerRadius = CornerRadius(w * 0.016f, w * 0.016f),
                )
            }
        }
    }
}
