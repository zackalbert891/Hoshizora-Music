package com.puremusic.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import coil.compose.AsyncImage
import coil.request.ImageRequest

/**
 * 封面旋转动画角度：播放时匀速旋转（约 30s/圈），暂停时停在当前角度。
 * 旋转速度可通过 [secondsPerTurn] 调整。
 */
@Composable
fun rememberCoverRotation(isPlaying: Boolean, secondsPerTurn: Int = 30): Float {
    val rotation = remember { Animatable(0f) }
    val updatedPlaying by rememberUpdatedState(isPlaying)
    LaunchedEffect(updatedPlaying) {
        if (updatedPlaying) {
            // 播放：从当前角度无限匀速旋转
            while (true) {
                rotation.animateTo(
                    rotation.value + 360f,
                    animationSpec = tween(durationMillis = secondsPerTurn * 1000, easing = LinearEasing),
                )
            }
        }
    }
    return rotation.value
}

/**
 * 旋转小封面（MiniPlayer / 列表内使用）：圆形封面随播放状态匀速旋转。
 */
@Composable
fun RotatingMiniCover(
    coverUrl: String,
    isPlaying: Boolean,
    size: Dp = 40.dp,
    modifier: Modifier = Modifier,
) {
    val rotation = rememberCoverRotation(isPlaying)
    Box(
        modifier = modifier
            .size(size)
            .shadow(4.dp, CircleShape, spotColor = Color.Black.copy(alpha = 0.4f))
            .clip(CircleShape)
            .graphicsLayer { rotationZ = rotation },
        contentAlignment = Alignment.Center,
    ) {
        if (coverUrl.isBlank()) {
            Box(Modifier.fillMaxSize().background(Color(0xFF2A2A30)), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.MusicNote,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.size(size * 0.5f),
                )
            }
        } else {
            AsyncImage(
                model = coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * 播放页完整唱片盘：圆形封面 + 唱片纹路光效 + 中心孔 + 阴影，播放时匀速旋转。
 * [onDominantColor] 在封面加载完成后回调提取出的主色（用于播放页动态配色）。
 */
@Composable
fun RotatingVinylDisc(
    coverUrl: String,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    onDominantColor: ((Color) -> Unit)? = null,
) {
    val rotation = rememberCoverRotation(isPlaying)
    // 暂停挤压动效（PixelPlayer pause squish）：暂停缩至 0.95f，播放回弹 1f，260ms EaseOut
    val squish by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0.95f,
        animationSpec = tween(260, easing = FastOutSlowInEasing),
        label = "pauseSquish",
    )
    // 封面加载状态：先占位后浮现（防闪白），换歌时重置
    var coverLoaded by remember(coverUrl) { mutableStateOf(false) }
    Box(modifier = modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        // 唱片盘面（旋转 + 挤压）
        Box(
            Modifier
                .fillMaxSize()
                .shadow(
                    elevation = 24.dp,
                    shape = CircleShape,
                    ambientColor = Color.Black.copy(alpha = 0.5f),
                    spotColor = Color.Black.copy(alpha = 0.65f),
                )
                .clip(CircleShape)
                .graphicsLayer {
                    rotationZ = rotation
                    scaleX = squish
                    scaleY = squish
                },
        ) {
            if (coverUrl.isBlank()) {
                Box(Modifier.fillMaxSize().background(Color(0xFF1E1E24)))
            } else {
                // 封面常驻组合树、用 alpha 渐显。
                // 不能把 AsyncImage 放进 Crossfade 的条件分支：切到该分支时它才首次组合，
                // 会立刻触发 onLoading 把 coverLoaded 打回 false，于是又切回占位，
                // 结果封面永远闪不出来（背景主色却能提取成功，正是这个错位）。
                val coverAlpha by animateFloatAsState(
                    targetValue = if (coverLoaded) 1f else 0f,
                    animationSpec = tween(400, easing = FastOutSlowInEasing),
                    label = "coverAlpha",
                )
                AsyncImage(
                    // allowHardware(false)：主色提取需要读像素，硬件位图不支持 getPixel
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(coverUrl)
                        .allowHardware(false)
                        .build(),
                    contentDescription = "封面",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().alpha(coverAlpha),
                    onError = { coverLoaded = false },
                    onSuccess = { result ->
                        coverLoaded = true
                        onDominantColor?.let { cb ->
                            val bitmap = runCatching { result.result.drawable.toBitmap() }.getOrNull()
                            if (bitmap != null) {
                                val c = dominantColorFrom(bitmap)
                                cb(Color(c.toInt()))
                            }
                        }
                    },
                )
                if (!coverLoaded) {
                    // 渐变占位：深色底 + 音符图标，避免换歌闪白
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(Color(0xFF2E2E38), Color(0xFF14141A)),
                                )
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.MusicNote,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.35f),
                            modifier = Modifier.size(72.dp),
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
                                Color.White.copy(alpha = 0.14f),
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.30f),
                            ),
                            radius = 1100f,
                        )
                    )
            )
            // 唱片纹路环
            Box(
                Modifier
                    .fillMaxSize(0.84f)
                    .clip(CircleShape)
                    .border(1.dp, Color.White.copy(alpha = 0.10f), CircleShape)
            )
            Box(
                Modifier
                    .fillMaxSize(0.62f)
                    .clip(CircleShape)
                    .border(1.dp, Color.White.copy(alpha = 0.10f), CircleShape)
            )
            Box(
                Modifier
                    .fillMaxSize(0.42f)
                    .clip(CircleShape)
                    .border(1.dp, Color.White.copy(alpha = 0.08f), CircleShape)
            )
        }
        // 中心孔（不旋转）
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(Color(0xFF101014))
                .border(2.dp, Color.White.copy(alpha = 0.30f), CircleShape)
        )
    }
}

private const val DEFAULT_SEED_COLOR = 0xFF6750A4

/**
 * 从封面位图采样提取主色（本地实现，替代 androidx.palette 依赖）。
 *
 * Coil 默认把图解码为 `Config#HARDWARE` 位图，这种位图不支持 `getPixel()`，
 * 直接采样会抛 `IllegalStateException: unable to getPixel()`。这里先转成可读的软件位图。
 */
fun dominantColorFrom(bitmap: android.graphics.Bitmap): Long {
    val readable = if (bitmap.config == android.graphics.Bitmap.Config.HARDWARE) {
        runCatching { bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false) }.getOrNull()
            ?: return DEFAULT_SEED_COLOR
    } else {
        bitmap
    }
    val w = readable.width
    val h = readable.height
    if (w <= 0 || h <= 0) return DEFAULT_SEED_COLOR
    val stepX = (w / 24).coerceAtLeast(1)
    val stepY = (h / 24).coerceAtLeast(1)
    var sumR = 0L
    var sumG = 0L
    var sumB = 0L
    var count = 0L
    var y = stepY / 2
    while (y < h) {
        var x = stepX / 2
        while (x < w) {
            val c = readable.getPixel(x, y)
            if (((c ushr 24) and 0xFF) > 128) {
                sumR += (c ushr 16) and 0xFF
                sumG += (c ushr 8) and 0xFF
                sumB += c and 0xFF
                count++
            }
            x += stepX
        }
        y += stepY
    }
    if (count == 0L) return DEFAULT_SEED_COLOR
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(
        android.graphics.Color.rgb(
            (sumR / count).toInt(),
            (sumG / count).toInt(),
            (sumB / count).toInt(),
        ),
        hsv,
    )
    hsv[1] = hsv[1].coerceAtLeast(0.35f)
    hsv[2] = hsv[2].coerceIn(0.45f, 0.82f)
    return android.graphics.Color.HSVToColor(hsv).toLong() and 0xFFFFFFFFL
}
