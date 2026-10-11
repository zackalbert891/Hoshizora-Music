package com.puremusic.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.puremusic.ui.theme.LocalBlurStrength
import com.puremusic.ui.theme.LocalGlassBackdrops
import com.puremusic.ui.theme.glassBackdropSource
import com.puremusic.ui.theme.rememberGlassBackdrop
import java.io.File

/**
 * 全屏毛玻璃背景：背景图模糊 + 明暗遮罩保证文字可读 + 封面主色渐变叠加。
 * [tintColor] 传当前封面主色，会以低透明度叠加，形成 YesPlayMusic 式「主色驱动」背景。
 *
 * 遮罩与兜底底色都跟随主题明暗：浅色主题用浅色遮罩（深色文字才读得清），
 * 深色主题用黑色遮罩，避免两个主题下出现「浅底黑字被压暗」或「深底浅字被冲淡」。
 *
 * 另外这一层会被录进 [rememberGlassBackdrop] 的背板层，供页面里的液态玻璃元件
 * （MiniPlayer / 卡片 / 导航栏）取样折射 —— 玻璃元件必须放在 content() 里，
 * 不能放进背板，否则会自己取样自己。
 */
@Composable
fun AppBackground(
    backgroundImagePath: String?,
    tintColor: Color = Color.Transparent,
    content: @Composable () -> Unit,
) {
    val isLight = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    // 模糊强度跟随设置（0 ~ 1 → 0 ~ 40dp）；0 时不加 blur 修饰符（省一层 GPU 开销）
    val blurRadius = (LocalBlurStrength.current.coerceIn(0f, 1f) * 40f).dp

    // 关键：底部栏（MiniPlayer / 导航栏）在 Scaffold 层、位于本组件之外，拿不到页面内的
    // CompositionLocal。所以背板必须由 AppRoot 统一创建并沿 LocalGlassBackdrops 下发，
    // 页面背景只负责"把自己的内容录进这块背板"。没有上层提供时才自己建一块。
    val provided = LocalGlassBackdrops.current.firstOrNull()
    val own = rememberGlassBackdrop()
    val backdrop = provided ?: own

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().glassBackdropSource(backdrop)) {
            val path = backgroundImagePath?.takeIf { it.isNotBlank() }
            if (path != null) {
                AsyncImage(
                    model = File(path),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = if (blurRadius > 0.dp) {
                        Modifier.fillMaxSize().blur(blurRadius)
                    } else {
                        Modifier.fillMaxSize()
                    },
                )
                // 明暗自适应遮罩：保证前景文字与状态栏可读
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            if (isLight) {
                                // 遮罩过重会把背景图完全盖住、与白色卡片糊成一片
                                listOf(Color.White.copy(alpha = 0.66f), Color.White.copy(alpha = 0.44f))
                            } else {
                                listOf(Color.Black.copy(alpha = 0.52f), Color.Black.copy(alpha = 0.08f))
                            }
                        )
                    )
                )
                // 封面主色渐变叠加（底部浓、顶部淡）
                if (tintColor != Color.Transparent) {
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(
                                listOf(
                                    tintColor.copy(alpha = 0.05f),
                                    tintColor.copy(alpha = if (isLight) 0.18f else 0.30f),
                                )
                            )
                        )
                    )
                }
            } else {
                // 无背景图：主色渐变 + 主题底色
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            listOf(
                                if (tintColor != Color.Transparent) {
                                    tintColor.copy(alpha = 0.16f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainer
                                },
                                MaterialTheme.colorScheme.surface,
                            )
                        )
                    )
                )
            }
        }
        CompositionLocalProvider(LocalGlassBackdrops provides listOf(backdrop)) {
            content()
        }
    }
}
