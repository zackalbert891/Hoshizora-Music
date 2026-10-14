package com.puremusic.ui.theme

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.os.Build
import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader as AndroidShader
import androidx.annotation.RequiresApi
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

/**
 * 液态玻璃材质层（移植自 IRISMusic 的 LiquidGlass，按本项目配色改造）。
 *
 * 真「玻璃」的关键不是叠一层半透明白 —— 那样只会得到乳白塑料。通透感来自**位移取样**：
 * 边缘的像素去采样更靠内的背景，背景于是在玻璃边缘被压缩、拉伸，看起来真像透过一块
 * 有厚度的玻璃。这件事画渐变永远做不到，只能得到"雾"，所以必须拿到背板像素：
 *
 * 1. [glassBackdropSource] 把背景内容录进 [GraphicsLayer]（见 AppBackground）；
 * 2. 玻璃元件把背板重画进自己的图层，挂上 RenderEffect 链：调色 → 模糊 → AGSL 折射；
 * 3. 再用第二个 AGSL（SDF 法向 · 光源点积）描一圈镜面内缘。
 *
 * 三档自动降级：
 * - API 33+：真折射 + 色散 + SDF 内缘（AGSL RuntimeShader）；
 * - API 31~32：真模糊 + 调色 + 渐变内缘（无 AGSL）；
 * - API 30 及以下：纯绘制近似（半透明填充 + 渐变内缘），不透背景。
 *
 * SDF 折射与高光算法参考 Kyant0/AndroidLiquidGlass（Apache-2.0）。
 */

/** 表面层级：决定玻璃厚度（折射深度 / 模糊 / 染色浓度）。 */
enum class GlassLevel {
    /** 底部弹层：盖住内容且承载大量文字，玻璃最厚 */
    SHEET,
    /** 播放器主卡片 / MiniPlayer */
    PLAYER,
    /** 普通卡片 / 设置项 / 上栏 */
    CARD,
    /** 歌单/歌曲行：数量多，玻璃最薄且不做逐行取样，避免一屏十几条背板流水线 */
    ROW,
    /** 小徽章 / 圆形按钮 */
    CHIP,
}

/**
 * 是否处在另一块玻璃内部。
 *
 * 玻璃里的玻璃不能再去取样背板：外壳已经调色压过一遍，内层若重新取原始背景会比外壳更亮，
 * 看着像"浮在玻璃上的另一块背景"，层次立刻散掉。真玻璃里的元件只是更厚的一层，
 * 所以内层退化成叠加染色 + 内缘高光 —— 既符合光学，也省下大量 GPU。
 */
val LocalGlassOnGlass = staticCompositionLocalOf { false }

@Composable
fun GlassContent(content: @Composable () -> Unit) =
    androidx.compose.runtime.CompositionLocalProvider(LocalGlassOnGlass provides true, content = content)

/** 玻璃材质令牌。调这里就能整体改质感。 */
@Immutable
data class GlassSpec(
    /** 本体染色（含 alpha）。只为文字可读性服务，越低越通透。 */
    val tint: Color,
    /** 背板模糊半径 */
    val blur: Dp,
    /** 折射带宽度：距边缘多少 dp 以内发生位移取样 */
    val refraction: Dp,
    /** 折射强度系数（相对 [refraction]） */
    val refractionScale: Float,
    /** 透镜弧度：0 = 平板玻璃，1 = 边缘明显外凸 */
    val depth: Float,
    /** 是否开色散（RGB 错位取样）。只给大面积表面开，小元件上只会变成噪点。 */
    val dispersion: Boolean,
    /** 是否取样背板做真折射。列表行关闭（见 [GlassLevel.ROW]）。 */
    val refracts: Boolean = true,
    /** 背板对比度：> 1 让玻璃里的内容更"有东西" */
    val contrast: Float,
    /** 背板饱和度：> 1 保住封面颜色，玻璃才不像灰塑料 */
    val saturation: Float,
    /** 背板亮度偏移：浅色模式需要正偏移压住深色封面，保证黑字可读 */
    val brightness: Float,
    /** 内缘是否用 AGSL 着色器描边。
     *  长列表（歌曲行 / 评论卡）关掉：一屏十几行就是十几个 RuntimeShader 实例在滚动中逐帧重绘，
     *  而内缘高光在 1dp 宽度上肉眼几乎分辨不出 —— 换成线性渐变描边，观感不变、GPU 归零。 */
    val edgeShader: Boolean = true,
    /** 内缘迎光侧颜色（Plus 叠加） */
    val lightEdge: Color,
    /** 内缘背光侧颜色（SrcOver） */
    val darkEdge: Color,
    /** 内缘描边宽度 */
    val edgeWidth: Dp,
    /** 光源方向（度）。-45 = 左上打光。 */
    val edgeAngle: Float,
    /** 内缘衰减指数：越大高光越集中在正对光源的那一段 */
    val edgeFalloff: Float,
    /** 嵌在另一块玻璃里时的叠加色（不重新取样背板） */
    val nestedBody: Color,
    /** 拿不到背板时的回退填充（纯绘制近似，不透背景） */
    val fallbackBody: Color,
)

/**
 * 按层级 + 当前明暗生成材质令牌。
 *
 * 染色只保证可读性：深色模式用近黑烟熏而不是叠白 —— 叠白会把背后封面的颜色冲淡，
 * 颜色一淡折射就无从谈起，只剩乳白塑料。
 */
@Composable
fun GlassLevel.spec(dark: Boolean = isDarkSurface): GlassSpec {
    val tint = when (this) {
        GlassLevel.SHEET -> if (dark) Color(0x8F22222C) else Color(0x94FFFFFF)
        GlassLevel.PLAYER -> if (dark) Color(0x5220202A) else Color(0x5CFFFFFF)
        GlassLevel.CARD -> if (dark) Color(0x3D20202A) else Color(0x47FFFFFF)
        GlassLevel.ROW -> if (dark) Color(0x331E1E28) else Color(0x3DFFFFFF)
        GlassLevel.CHIP -> if (dark) Color(0x2E20202A) else Color(0x3AFFFFFF)
    }
    return GlassSpec(
        tint = tint,
        // 模糊别太狠：糊成一片就没有可辨识的折射纹理。
        blur = when (this) {
            GlassLevel.SHEET -> 20.dp
            GlassLevel.PLAYER -> 16.dp
            GlassLevel.CARD -> 12.dp
            GlassLevel.ROW -> 9.dp
            GlassLevel.CHIP -> 7.dp
        },
        // 折射带越宽玻璃越厚。小元件不能给太宽，否则中心也被吃掉。
        refraction = when (this) {
            GlassLevel.SHEET -> 24.dp
            GlassLevel.PLAYER -> 22.dp
            GlassLevel.CARD -> 15.dp
            GlassLevel.ROW -> 11.dp
            GlassLevel.CHIP -> 8.dp
        },
        refractionScale = when (this) {
            GlassLevel.SHEET -> 1.0f
            GlassLevel.PLAYER -> 1.15f
            GlassLevel.CARD -> 1.0f
            GlassLevel.ROW -> 0.9f
            GlassLevel.CHIP -> 0.85f
        },
        depth = when (this) {
            GlassLevel.SHEET, GlassLevel.PLAYER -> 0.5f
            GlassLevel.CARD -> 0.35f
            else -> 0f
        },
        dispersion = this == GlassLevel.PLAYER || this == GlassLevel.SHEET,
        refracts = this != GlassLevel.ROW,
        contrast = if (dark) 1.18f else 1.12f,
        saturation = if (dark) 1.45f else 1.3f,
        brightness = if (dark) -0.02f else 0.14f,
        lightEdge = if (dark) Color(0x2EFFFFFF) else Color(0x40FFFFFF),
        darkEdge = if (dark) Color(0x4D000000) else Color(0x33000000),
        edgeWidth = if (this == GlassLevel.ROW || this == GlassLevel.CHIP) 1.dp else 1.4.dp,
        // 列表行 / 徽章的描边走渐变，不跑 AGSL（见 GlassSpec.edgeShader 注释）
        edgeShader = this != GlassLevel.ROW,
        edgeAngle = -45f,
        edgeFalloff = 1.2f,
        nestedBody = when (this) {
            GlassLevel.SHEET -> if (dark) Color(0x59191922) else Color(0x59FFFFFF)
            GlassLevel.PLAYER -> if (dark) Color(0x2EFFFFFF) else Color(0x14000000)
            GlassLevel.CARD -> if (dark) Color(0x1FFFFFFF) else Color(0x12000000)
            GlassLevel.ROW -> if (dark) Color(0x14FFFFFF) else Color(0x0D000000)
            GlassLevel.CHIP -> if (dark) Color(0x1AFFFFFF) else Color(0x0F000000)
        },
        fallbackBody = if (dark) Color(0xB8181820) else Color(0xC7FFFFFF),
    )
}

/**
 * 液态玻璃总开关（设置项）。关掉后玻璃元件退回半透明实色 + 渐变内缘，
 * 不再建 GraphicsLayer、不再跑模糊与 AGSL —— 低端机滚动卡顿时可关闭。
 */
val LocalLiquidGlass = staticCompositionLocalOf { true }

/** 当前是否为深色底（决定玻璃染色方向）。 */
val isDarkSurface: Boolean
    @Composable get() = MaterialTheme.colorScheme.surface.luminance() < 0.5f

// ==================== 背板 ====================

/** 一块可被玻璃取样的背板。coordinates 用 neverEqualPolicy：位置每次上报都算变化，滚动时才跟得住。 */
@Stable
class GlassBackdrop internal constructor(internal val layer: GraphicsLayer?) {
    internal var coordinates: LayoutCoordinates? by mutableStateOf(null, neverEqualPolicy())
}

@Composable
fun rememberGlassBackdrop(): GlassBackdrop {
    val layer = if (renderEffectSupported) rememberGraphicsLayer() else null
    return remember(layer) { GlassBackdrop(layer) }
}

/** 把这段内容照常画到屏幕上，同时录进背板层。玻璃元件本身不能放进被录制的内容里。 */
fun Modifier.glassBackdropSource(backdrop: GlassBackdrop): Modifier = this
    .onGloballyPositioned { backdrop.coordinates = it }
    .drawWithContent {
        val layer = backdrop.layer
        if (layer == null) {
            drawContent()
            return@drawWithContent
        }
        layer.record { this@drawWithContent.drawContent() }
        drawLayer(layer)
    }

/** 当前可用的背板栈，按绘制顺序从后到前。 */
val LocalGlassBackdrops = staticCompositionLocalOf<List<GlassBackdrop>> { emptyList() }

private fun DrawScope.drawBackdropStack(backdrops: List<GlassBackdrop>, target: LayoutCoordinates?) {
    if (target == null || !target.isAttached) return
    backdrops.forEach { backdrop ->
        val layer = backdrop.layer ?: return@forEach
        val source = backdrop.coordinates ?: return@forEach
        if (!source.isAttached) return@forEach
        val offset = try {
            source.localPositionOf(target, Offset.Zero)
        } catch (_: Throwable) {
            target.positionInWindow() - source.positionInWindow()
        }
        translate(-offset.x, -offset.y) { drawLayer(layer) }
    }
}

// ==================== 能力探测 ====================

/** AGSL RuntimeShader：API 33 起 */
internal val runtimeShaderSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

/** RenderEffect（模糊 / 色彩矩阵）与 GraphicsLayer 取样：API 31 起 */
internal val renderEffectSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

// ==================== AGSL ====================

private const val ROUNDED_RECT_SDF = """
float glassRadiusAt(float2 c, float4 radii) {
    if (c.x >= 0.0) {
        return c.y <= 0.0 ? radii.y : radii.z;
    } else {
        return c.y <= 0.0 ? radii.x : radii.w;
    }
}

float glassSd(float2 c, float2 hs, float r) {
    float2 q = abs(c) - (hs - float2(r));
    return length(max(q, 0.0)) - r + min(max(q.x, q.y), 0.0);
}

float2 glassGrad(float2 c, float2 hs, float r) {
    float2 q = abs(c) - (hs - float2(r));
    if (q.x >= 0.0 || q.y >= 0.0) {
        return sign(c) * normalize(max(q, float2(0.0001)));
    } else {
        float gx = step(q.y, q.x);
        return sign(c) * float2(gx, 1.0 - gx);
    }
}

float2 glassSafeNorm(float2 v) {
    float l = length(v);
    return l > 0.0001 ? v / l : float2(0.0, 0.0);
}

float glassCircleMap(float x) {
    return 1.0 - sqrt(max(1.0 - x * x, 0.0));
}
"""

/** 折射：距边缘 height 以内的像素，沿边缘法向向内偏移取样。偏移量走圆弧曲线（玻璃厚度切面）。 */
private const val REFRACTION_SHADER = """
uniform shader content;
uniform float2 size;
uniform float2 offset;
uniform float4 cornerRadii;
uniform float height;
uniform float amount;
uniform float depth;

$ROUNDED_RECT_SDF

half4 main(float2 coord) {
    float2 hs = size * 0.5;
    float2 cc = (coord + offset) - hs;
    float r = glassRadiusAt(cc, cornerRadii);

    float sd = glassSd(cc, hs, r);
    if (-sd >= height) {
        return content.eval(coord);
    }
    sd = min(sd, 0.0);

    float d = glassCircleMap(1.0 - (-sd / height)) * -amount;
    float gr = min(max(r, 1.0) * 1.5, min(hs.x, hs.y));
    float2 g = normalize(glassGrad(cc, hs, gr) + depth * glassSafeNorm(cc));

    return content.eval(coord + d * g);
}
"""

/** 带色散的折射：RGB 三通道错开取样，四角最强，边缘泛出极淡的虹彩。 */
private const val REFRACTION_DISPERSION_SHADER = """
uniform shader content;
uniform float2 size;
uniform float2 offset;
uniform float4 cornerRadii;
uniform float height;
uniform float amount;
uniform float depth;
uniform float dispersion;

$ROUNDED_RECT_SDF

half4 main(float2 coord) {
    float2 hs = size * 0.5;
    float2 cc = (coord + offset) - hs;
    float r = glassRadiusAt(cc, cornerRadii);

    float sd = glassSd(cc, hs, r);
    if (-sd >= height) {
        return content.eval(coord);
    }
    sd = min(sd, 0.0);

    float d = glassCircleMap(1.0 - (-sd / height)) * -amount;
    float gr = min(max(r, 1.0) * 1.5, min(hs.x, hs.y));
    float2 g = normalize(glassGrad(cc, hs, gr) + depth * glassSafeNorm(cc));

    float2 base = coord + d * g;
    float k = dispersion * ((cc.x * cc.y) / (hs.x * hs.y));
    float2 shift = d * g * k;

    half4 red = content.eval(base + shift);
    half4 green = content.eval(base);
    half4 blue = content.eval(base - shift);

    return half4(red.r, green.g, blue.b, (red.a + green.a + blue.a) / 3.0);
}
"""

/** 镜面内缘：SDF 法向与光源方向的点积取幂作强度。迎光侧亮、背光侧暗。输出预乘。 */
private const val EDGE_SHADER = """
uniform float2 size;
uniform float4 cornerRadii;
layout(color) uniform half4 lightColor;
layout(color) uniform half4 darkColor;
uniform float lightAlpha;
uniform float darkAlpha;
uniform float angle;
uniform float falloff;

$ROUNDED_RECT_SDF

half4 main(float2 coord) {
    float2 hs = size * 0.5;
    float2 cc = coord - hs;
    float r = glassRadiusAt(cc, cornerRadii);

    float gr = min(max(r, 1.0) * 1.5, min(hs.x, hs.y));
    float2 g = glassGrad(cc, hs, gr);
    float d = dot(g, float2(cos(angle), sin(angle)));
    float intensity = pow(abs(d), falloff);

    float3 rgb = d >= 0.0 ? float3(lightColor.rgb) : float3(darkColor.rgb);
    float a = (d >= 0.0 ? lightAlpha : darkAlpha) * intensity;
    return half4(half3(rgb * a), half(a));
}
"""

/** RuntimeShader 必须复用实例，uniform 每次重设。 */
internal class GlassShaders {
    private val cache = HashMap<String, RuntimeShader>(4)

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun obtain(key: String, source: String): RuntimeShader =
        cache.getOrPut(key) { RuntimeShader(source) }
}

private const val DISPERSION_STRENGTH = 0.6f

/** 背板效果链：调色 → 模糊 → 折射。折射必须在模糊之后，否则会把锐利像素拽到边缘出现重影。 */
@RequiresApi(Build.VERSION_CODES.S)
private fun buildGlassEffect(
    shaders: GlassShaders,
    spec: GlassSpec,
    size: Size,
    cornerRadii: FloatArray,
    padding: Float,
    blurPx: Float,
    refractionPx: Float,
): AndroidRenderEffect? {
    var effect: AndroidRenderEffect? = null

    if (spec.contrast != 1f || spec.saturation != 1f || spec.brightness != 0f) {
        effect = AndroidRenderEffect.createColorFilterEffect(
            colorControlsFilter(spec.brightness, spec.contrast, spec.saturation)
        )
    }

    if (blurPx > 0f) {
        effect = if (effect == null) {
            AndroidRenderEffect.createBlurEffect(blurPx, blurPx, AndroidShader.TileMode.CLAMP)
        } else {
            AndroidRenderEffect.createBlurEffect(blurPx, blurPx, effect, AndroidShader.TileMode.CLAMP)
        }
    }

    if (refractionPx > 0f && runtimeShaderSupported) {
        val shader = if (spec.dispersion) {
            shaders.obtain("dispersion", REFRACTION_DISPERSION_SHADER).apply {
                setFloatUniform("dispersion", DISPERSION_STRENGTH)
            }
        } else {
            shaders.obtain("refraction", REFRACTION_SHADER)
        }
        shader.apply {
            setFloatUniform("size", size.width, size.height)
            setFloatUniform("offset", -padding, -padding)
            setFloatUniform("cornerRadii", cornerRadii)
            setFloatUniform("height", refractionPx)
            setFloatUniform("amount", refractionPx * spec.refractionScale)
            setFloatUniform("depth", spec.depth)
        }
        val lens = AndroidRenderEffect.createRuntimeShaderEffect(shader, "content")
        effect = if (effect == null) lens else AndroidRenderEffect.createChainEffect(lens, effect)
    }

    return effect
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun buildEdgeShader(
    shaders: GlassShaders,
    spec: GlassSpec,
    size: Size,
    cornerRadii: FloatArray,
    light: Boolean,
): AndroidShader = shaders.obtain(if (light) "edgeLight" else "edgeDark", EDGE_SHADER).apply {
    setFloatUniform("size", size.width, size.height)
    setFloatUniform("cornerRadii", cornerRadii)
    setColorUniform("lightColor", spec.lightEdge.copy(alpha = 1f).toArgb())
    setColorUniform("darkColor", spec.darkEdge.copy(alpha = 1f).toArgb())
    setFloatUniform("lightAlpha", if (light) spec.lightEdge.alpha else 0f)
    setFloatUniform("darkAlpha", if (light) 0f else spec.darkEdge.alpha)
    setFloatUniform("angle", Math.toRadians(spec.edgeAngle.toDouble()).toFloat())
    setFloatUniform("falloff", spec.edgeFalloff)
}

/**
 * 亮度 / 对比度 / 饱和度矩阵 —— "不泛白"的核心：想让玻璃里的背景更清楚就提对比度和
 * 饱和度，而不是往上叠白（叠白会把背后封面的颜色一起冲淡）。
 */
private fun colorControlsFilter(brightness: Float, contrast: Float, saturation: Float): ColorMatrixColorFilter {
    val invSat = 1f - saturation
    val r = 0.213f * invSat
    val g = 0.715f * invSat
    val b = 0.072f * invSat
    val t = (0.5f - contrast * 0.5f + brightness) * 255f
    val cr = contrast * r
    val cg = contrast * g
    val cb = contrast * b
    val cs = contrast * saturation
    return ColorMatrixColorFilter(
        ColorMatrix(
            floatArrayOf(
                cr + cs, cg, cb, 0f, t,
                cr, cg + cs, cb, 0f, t,
                cr, cg, cb + cs, 0f, t,
                0f, 0f, 0f, 1f, 0f,
            )
        )
    )
}

private fun Density.glassCornerRadii(shape: Shape, size: Size, layoutDirection: LayoutDirection): FloatArray {
    val max = size.minDimension / 2f
    val corners = shape as? CornerBasedShape ?: return FloatArray(4) { 0f }
    val ltr = layoutDirection == LayoutDirection.Ltr
    val topLeft = (if (ltr) corners.topStart else corners.topEnd).toPx(size, this)
    val topRight = (if (ltr) corners.topEnd else corners.topStart).toPx(size, this)
    val bottomRight = (if (ltr) corners.bottomEnd else corners.bottomStart).toPx(size, this)
    val bottomLeft = (if (ltr) corners.bottomStart else corners.bottomEnd).toPx(size, this)
    return floatArrayOf(
        topLeft.coerceAtMost(max),
        topRight.coerceAtMost(max),
        bottomRight.coerceAtMost(max),
        bottomLeft.coerceAtMost(max),
    )
}

private fun Outline.toPath(): Path = Path().apply {
    when (val o = this@toPath) {
        is Outline.Rectangle -> addRect(o.rect)
        is Outline.Rounded -> addRoundRect(o.roundRect)
        is Outline.Generic -> addPath(o.path)
    }
}

private fun Color.scaleAlpha(f: Float) = copy(alpha = (alpha * f).coerceIn(0f, 1f))

/**
 * 液态玻璃表面。绘制顺序：折射后的背板 → 染色 → 强调色 → 镜面内缘。
 *
 * @param backdrops 要折射的背板栈，默认取 [LocalGlassBackdrops]。
 */
@Composable
fun Modifier.glassSurface(
    spec: GlassSpec,
    shape: Shape,
    accent: Color? = null,
    elevation: Dp = 0.dp,
    backdrops: List<GlassBackdrop> = LocalGlassBackdrops.current,
): Modifier {
    val nested = LocalGlassOnGlass.current
    val enabled = LocalLiquidGlass.current
    val useBackdrop = enabled && !nested && spec.refracts && backdrops.isNotEmpty() && renderEffectSupported
    val shaders = remember { GlassShaders() }
    val layer = if (useBackdrop) rememberGraphicsLayer() else null
    // 用 neverEqualPolicy 的状态而不是普通字段：滚动时元件相对背板的位置在变，
    // 必须让绘制跟着失效，否则玻璃会把背景"粘"在身上一起走。
    val position = if (useBackdrop) remember { PositionHolder() } else null

    return this
        .then(if (elevation > 0.dp) Modifier.shadow(elevation, shape, clip = false) else Modifier)
        .then(if (position != null) Modifier.onGloballyPositioned { position.value = it } else Modifier)
        .clip(shape)
        .drawWithCache {
            if (size.minDimension <= 1f) return@drawWithCache onDrawBehind { }

            val path = shape.createOutline(size, layoutDirection, this).toPath()
            val radii = glassCornerRadii(shape, size, layoutDirection)
            val refractionPx = spec.refraction.toPx().coerceAtMost(size.minDimension * 0.45f)
            val blurPx = spec.blur.toPx()
            val edgePx = spec.edgeWidth.toPx()

            // 折射向外取样、模糊要邻域，图层必须比元件大一圈，否则四边取到空白会出暗带。
            // 取整避免半像素错位 —— 错半格边缘就会出现一条重影。
            val pad = if (layer != null) ceil(blurPx + refractionPx).toInt() else 0

            if (layer != null && renderEffectSupported) {
                // 个别机型/驱动上 RuntimeShader 编译可能失败；失败就退回模糊 + 染色，不要崩
                layer.renderEffect = runCatching {
                    buildGlassEffect(
                        shaders = shaders,
                        spec = spec,
                        size = size,
                        cornerRadii = radii,
                        padding = pad.toFloat(),
                        blurPx = blurPx,
                        refractionPx = refractionPx,
                    )
                }.getOrNull()?.asComposeRenderEffect()
            }

            val edgeBrushes = if (enabled && runtimeShaderSupported && spec.edgeShader) {
                runCatching {
                    ShaderBrush(buildEdgeShader(shaders, spec, size, radii, light = true)) to
                        ShaderBrush(buildEdgeShader(shaders, spec, size, radii, light = false))
                }.getOrNull()
            } else {
                null
            }

            // AGSL 不可用时的内缘：左上亮 → 中部暗 → 右下回亮，模拟双向折射
            val fallbackEdge = Brush.linearGradient(
                0.00f to spec.lightEdge,
                0.28f to spec.lightEdge.scaleAlpha(0.3f),
                0.55f to spec.darkEdge,
                0.82f to spec.lightEdge.scaleAlpha(0.5f),
                1.00f to spec.lightEdge.scaleAlpha(0.85f),
                start = Offset.Zero,
                end = Offset(size.width, size.height),
            )

            onDrawBehind {
                clipPath(path) {
                    when {
                        layer != null -> {
                            drawRefractedBackdrop(layer, backdrops, position?.value, pad)
                            drawRect(spec.tint)
                        }
                        nested -> drawRect(spec.nestedBody)
                        // 不取样的薄表面（列表行）：背景本来就铺在底下且已被高斯糊过，
                        // 直接叠 tint 即可，不能用 fallbackBody 那块实心近似 —— 会糊死。
                        !spec.refracts -> drawRect(spec.tint)
                        else -> drawRect(spec.fallbackBody)
                    }
                    if (accent != null) drawRect(accent.copy(alpha = 0.16f))
                }
                if (edgeBrushes != null) {
                    // 迎光侧用 Plus：叠加而非覆盖，才有金属反光那种"发光"感
                    drawPath(path, brush = edgeBrushes.first, style = Stroke(width = edgePx * 2f), blendMode = BlendMode.Plus)
                    drawPath(path, brush = edgeBrushes.second, style = Stroke(width = edgePx * 2f))
                } else {
                    drawPath(path, brush = fallbackEdge, style = Stroke(width = edgePx * 2f))
                }
            }
        }
}

/** 元件坐标。写在布局阶段、读在绘制阶段，neverEqualPolicy 保证滚动中每帧都重新对齐背板。 */
private class PositionHolder {
    var value: LayoutCoordinates? by mutableStateOf(null, neverEqualPolicy())
}

private fun DrawScope.drawRefractedBackdrop(
    layer: GraphicsLayer,
    backdrops: List<GlassBackdrop>,
    coordinates: LayoutCoordinates?,
    pad: Int,
) {
    layer.record(IntSize(size.width.toInt() + pad * 2, size.height.toInt() + pad * 2)) {
        translate(pad.toFloat(), pad.toFloat()) {
            drawBackdropStack(backdrops, coordinates)
        }
    }
    layer.topLeft = IntOffset(-pad, -pad)
    drawLayer(layer)
}

/** 便捷入口：按层级直接给一块玻璃表面（默认大圆角卡片形状）。 */
@Composable
fun Modifier.glassCard(
    level: GlassLevel = GlassLevel.CARD,
    shape: Shape = RoundedCornerShape(UiTokens.RadiusCard),
    accent: Color? = null,
    elevation: Dp = 0.dp,
): Modifier = glassSurface(level.spec(), shape, accent, elevation)
