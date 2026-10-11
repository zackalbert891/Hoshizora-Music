package com.puremusic.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.puremusic.model.CornerStyle
import com.puremusic.model.DarkMode
import com.puremusic.model.ThemeConfig

/** 全局设计 Token：圆角 / 间距 / 字号 / 动效 统一尺度（YesPlayMusic 式大圆角 + 留白，MotionScheme 式动效节奏）。 */
object UiTokens {
    // 圆角
    val RadiusSmall = 12.dp
    val RadiusMedium = 16.dp
    val RadiusLarge = 20.dp
    val RadiusXLarge = 24.dp
    val RadiusCard = 28.dp

    // 间距
    val SpaceS = 8.dp
    val SpaceM = 12.dp
    val SpaceL = 16.dp
    val SpaceXL = 20.dp
    val SpaceXXL = 24.dp

    // 字号
    val TitleSize = 22.sp
    val BodySize = 16.sp
    val SubSize = 14.sp
    val CaptionSize = 12.sp

    // 动效节奏（MotionScheme：标准 260ms + EaseOut，短动效 180ms，缓入出 600ms）
    const val MotionStandard = 260
    const val MotionShort = 180
    const val MotionEmphasized = 600
}

/**
 * 玻璃 / 半透明面板色板。
 *
 * 面板与描边改为跟随当前配色方案：此前一律是「白色低透明度」，在浅色主题下叠在浅色背景上
 * 几乎不可见，导致卡片糊成一片。现在浅色主题取浅灰容器色、深色主题取深色容器色，
 * 两个主题下都能看出卡片边界。
 *
 * 播放页遮罩（OverlayDark / ScrimTop / ScrimBottom）保持暗色常量 —— 播放页本身是沉浸式
 * 深色界面，不随应用主题变化。
 */
object Glass {
    /** 浅色主题下用纯白卡片，与浅灰页面底拉开对比；深色主题沿用容器色。 */
    private val isLightSurface: Boolean
        @Composable get() = MaterialTheme.colorScheme.surface.luminance() > 0.5f

    val Panel: Color
        @Composable get() =
            if (isLightSurface) Color.White else MaterialTheme.colorScheme.surfaceContainerHigh

    val PanelStrong: Color
        @Composable get() =
            if (isLightSurface) Color.White else MaterialTheme.colorScheme.surfaceContainerHighest

    val Border: Color
        @Composable get() = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)

    val BorderStrong: Color
        @Composable get() = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)

    /**
     * 沉浸式深色页面（播放页）专用：这类页面本身永远是深色底 + 白色前景，
     * 不能跟随应用主题 —— 否则浅色主题下会变成「白底白图标」，控件直接消失。
     */
    val OnDarkPanel = Color.White.copy(alpha = 0.12f)
    val OnDarkPanelStrong = Color.White.copy(alpha = 0.14f)
    val OnDarkBorder = Color.White.copy(alpha = 0.18f)
    val OnDarkBorderStrong = Color.White.copy(alpha = 0.22f)

    val OverlayDark = Color(0x66101418)
    val ScrimTop = Color(0xCC0A0A0E)
    val ScrimBottom = Color(0xF20A0A0E)
}

/** Material Shapes：所有组件默认大圆角。 */
val PureMusicShapes = shapesFor(CornerStyle.LARGE)

/** 按圆角风格缩放基准圆角。 */
fun shapesFor(style: CornerStyle): Shapes {
    val s = style.scale
    return Shapes(
        extraSmall = RoundedCornerShape(UiTokens.RadiusSmall * s),
        small = RoundedCornerShape(UiTokens.RadiusMedium * s),
        medium = RoundedCornerShape(UiTokens.RadiusLarge * s),
        large = RoundedCornerShape(UiTokens.RadiusXLarge * s),
        extraLarge = RoundedCornerShape(UiTokens.RadiusCard * s),
    )
}

@Composable
fun PureMusicTheme(
    themeConfig: ThemeConfig,
    coverColor: Color? = null,
    content: @Composable () -> Unit
) {
    // 全局配色唯一来源 = 当前播放封面的主色（AppRoot 恒传入）。
    // 无封面时退到柠檬橙，避免冷启动瞬间整个 App 是灰的。
    val seedColor = coverColor ?: Color(0xFFFF6B00)

    val systemDark = isSystemInDarkTheme()
    val isLight = when (themeConfig.darkMode) {
        DarkMode.LIGHT -> true
        DarkMode.DARK -> false
        DarkMode.SYSTEM -> !systemDark
    }

    val colorScheme = if (isLight) {
        // primary 稍作加深：种子色直接当 primary 时在浅色背景上文字对比度不足（尤其亮橙/亮黄）
        lightColorScheme(
            primary = seedColor.mix(Color.Black, 0.12f),
            onPrimary = Color.White,
            primaryContainer = seedColor.mix(Color.White, 0.78f),
            onPrimaryContainer = seedColor.mix(Color.Black, 0.82f),
            secondaryContainer = seedColor.mix(Color.White, 0.88f),
            onSecondaryContainer = seedColor.mix(Color.Black, 0.78f),
            tertiaryContainer = seedColor.mix(Color.White, 0.84f),
            onTertiaryContainer = seedColor.mix(Color.Black, 0.80f),
            surface = Color(0xFFF7F7FA),
            surfaceContainer = Color(0xFFF1F1F5),
            surfaceContainerHigh = Color(0xFFEAEAF0),
            surfaceContainerHighest = Color(0xFFE3E3EA),
            background = Color(0xFFF7F7FA),
            onSurface = Color(0xFF1B1B1F),
            onSurfaceVariant = Color(0xFF565662),
            outline = Color(0xFF8A8A96),
            outlineVariant = Color(0xFFC6C6D0),
        )
    } else {
        darkColorScheme(
            primary = seedColor,
            primaryContainer = seedColor.mix(Color.Black, 0.55f),
            onPrimaryContainer = seedColor.mix(Color.White, 0.85f),
            surface = Color(0xFF101014),
            surfaceContainer = Color(0xFF1A1A20),
            surfaceContainerHigh = Color(0xFF22222A),
            surfaceContainerHighest = Color(0xFF2A2A32),
            background = Color(0xFF101014),
            onSurface = Color(0xFFEDEDF0),
            onSurfaceVariant = Color(0xFFA6A6B0),
            outline = Color(0xFF8A8A96),
            outlineVariant = Color(0xFF3A3A44),
        )
    }

    // 字体缩放 + 布局密度：通过替换 LocalDensity 全局生效，无需逐个页面改数值。
    // density 影响 dp（间距/控件尺寸），fontScale 只影响 sp（文字），两者独立可调。
    val base = LocalDensity.current
    val themedDensity = Density(
        density = base.density * themeConfig.layoutDensity.scale,
        fontScale = base.fontScale * themeConfig.fontScale,
    )

    CompositionLocalProvider(
        LocalDensity provides themedDensity,
        LocalBlurStrength provides themeConfig.blurStrength.coerceIn(0f, 1f),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography(),
            shapes = shapesFor(themeConfig.cornerStyle),
            content = content
        )
    }
}

/**
 * 背景模糊强度（0 = 完全清晰，1 = 最糊），由 [PureMusicTheme] 依据 ThemeConfig 下发。
 * 背景组件（[com.puremusic.ui.components.AppBackground] / 播放页）统一读它，
 * 用户拖一次滑杆，全 App 的背景模糊一起变。
 */
val LocalBlurStrength = compositionLocalOf { 0.45f }

/** 与另一颜色按比例混合（mixColor 占 weight）。 */
private fun Color.mix(other: Color, weight: Float): Color = Color(
    red = red * (1f - weight) + other.red * weight,
    green = green * (1f - weight) + other.green * weight,
    blue = blue * (1f - weight) + other.blue * weight,
)
