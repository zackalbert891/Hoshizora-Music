package com.puremusic.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 背景图「框选」对话框：选完图不直接按居中裁掉，而是让用户自己框。
 *
 * 最初是 `ContentScale.Crop` 直接居中取景：用户挑一张竖版壁纸当音乐库背景，
 * 手机是 9:19.5 的长屏，横向内容几乎全被裁掉，人像/主体偏上更是直接看不见。
 *
 * 交互（2026-10-15 重做）：
 * - **取景框是可调的**：拖框内移动、拖四角等比缩放，比例锁定输出比例（所见即所得）；
 * - 图片单指拖动平移、双指缩放，放大看细节；
 * - 底部「效果预览」实时显示裁出来的成品 —— 选好立刻能看到，不用先确定再猜。
 *
 * 取景框宽高比与屏幕一致（背景图最终就是全屏铺满），
 * 这样框出来的成品用 `ContentScale.Crop` 展示时不会再二次裁掉内容。
 */
@Composable
fun ImageCropDialog(
    bitmap: Bitmap,
    /** 输出宽高比（宽/高）；默认与屏幕一致。 */
    aspectRatio: Float? = null,
    onDismiss: () -> Unit,
    onConfirm: (Bitmap) -> Unit,
) {
    val cfg = LocalConfiguration.current
    val outAr = aspectRatio ?: (cfg.screenWidthDp.toFloat() / cfg.screenHeightDp.toFloat())
    val density = LocalDensity.current
    val image = remember(bitmap) { bitmap.asImageBitmap() }

    /** 取景区大小（= 图片按比例铺满的区域，相当于「屏幕」）。 */
    var stage by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    /** 取景框，stage 坐标系。 */
    var frameRect by remember { mutableStateOf(Rect.Zero) }
    var inited by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }

    // 下面取值全部现场读 state 换算 —— 手势协程是长驻的，闭包里的普通 val 重组后会变陈旧，
    // 而状态对象永远读到最新值。别把它们缓存成局部 val。

    fun fitSize(): Size {
        val s = stage
        if (s.width <= 0 || s.height <= 0) return Size.Zero
        val k = min(s.width / bitmap.width.toFloat(), s.height / bitmap.height.toFloat())
        return Size(bitmap.width * k, bitmap.height * k)
    }

    fun minScaleNow(): Float {
        val f = fitSize()
        if (f.width <= 0f || f.height <= 0f) return 1f
        return max(stage.width / f.width, stage.height / f.height)
    }

    fun maxScaleNow(): Float = minScaleNow() * 6f

    /** 保证图始终盖满取景区：偏移上限 = (缩放后显示尺寸 - 取景区尺寸) / 2。 */
    fun clampOffset(s: Float, o: Offset): Offset {
        val f = fitSize()
        val maxX = max(0f, (f.width * s - stage.width) / 2f)
        val maxY = max(0f, (f.height * s - stage.height) / 2f)
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    /** 取景框最大宽度：横竖都得放得进取景区。 */
    fun maxFrameWidth(): Float {
        val s = stage
        return if (s.width <= 0 || s.height <= 0) 0f else min(s.width.toFloat(), s.height * outAr)
    }

    fun minFrameWidth(): Float = with(density) { 64.dp.toPx() }.coerceAtMost(maxFrameWidth())

    /** 取景框回到默认：最大尺寸 + 居中，图片也回到初始缩放。 */
    fun resetAll() {
        val fw = maxFrameWidth()
        if (fw <= 0f) return
        val fh = fw / outAr
        val s = stage
        frameRect = Rect(
            (s.width - fw) / 2f,
            (s.height - fh) / 2f,
            (s.width + fw) / 2f,
            (s.height + fh) / 2f,
        )
        scale = minScaleNow()
        offset = Offset.Zero
    }

    LaunchedEffect(stage) {
        if (stage.width > 0 && stage.height > 0 && !inited) {
            resetAll()
            inited = true
        }
    }

    /** 落点判定：0..3 = 四角（TL/TR/BL/BR），-1 = 框内，-2 = 框外。 */
    fun hitZone(p: Offset, touch: Float): Int {
        val r = frameRect
        if (r.width <= 0f) return -2
        fun near(cx: Float, cy: Float) = abs(p.x - cx) <= touch && abs(p.y - cy) <= touch
        if (near(r.left, r.top)) return 0
        if (near(r.right, r.top)) return 1
        if (near(r.left, r.bottom)) return 2
        if (near(r.right, r.bottom)) return 3
        return if (r.contains(p)) -1 else -2
    }

    /** 拖某一角：对角为锚点等比缩放（锁定输出比例）。 */
    fun resizeFrame(zone: Int, pos: Offset) {
        val r = frameRect
        if (r.width <= 0f) return
        val anchor = when (zone) {
            0 -> Offset(r.right, r.bottom)
            1 -> Offset(r.left, r.bottom)
            2 -> Offset(r.right, r.top)
            else -> Offset(r.left, r.top)
        }
        val rightSide = zone == 1 || zone == 3
        val bottomSide = zone == 2 || zone == 3
        val s = stage
        // 让框的两个方向都尽量跟住手指：取两个轴向里更大的一个
        val want = max(abs(pos.x - anchor.x), abs(pos.y - anchor.y) * outAr)
        val maxWX = if (rightSide) s.width - anchor.x else anchor.x
        val maxWY = if (bottomSide) (s.height - anchor.y) * outAr else anchor.y * outAr
        val lower = minFrameWidth()
        val upper = max(lower, min(maxWX, maxWY))
        val w = want.coerceIn(lower, upper)
        val h = w / outAr
        val left = if (rightSide) anchor.x else anchor.x - w
        val top = if (bottomSide) anchor.y else anchor.y - h
        frameRect = Rect(left, top, left + w, top + h)
    }

    /** 拖框内：整体平移取景框（留在取景区内）。 */
    fun moveFrame(delta: Offset) {
        val r = frameRect
        if (r.width <= 0f) return
        val s = stage
        val nx = (r.left + delta.x).coerceIn(0f, max(0f, s.width - r.width))
        val ny = (r.top + delta.y).coerceIn(0f, max(0f, s.height - r.height))
        frameRect = Rect(nx, ny, nx + r.width, ny + r.height)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF0B0B0F)),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "框选背景区域",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "拖框移动 · 拖四角调大小 · 双指缩放图片",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f),
                )
                Spacer(Modifier.height(12.dp))

                // ── 取景区：按图片比例撑满可用空间，图片和可调取景框都画在这里 ──
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .aspectRatio(bitmap.width.toFloat() / bitmap.height.toFloat())
                            .clipToBounds()
                            .onSizeChanged { stage = it }
                            .pointerInput(Unit) {
                                val touchMax = 40.dp.toPx()
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val touch = min(touchMax, frameRect.width * 0.35f)
                                    val zone = hitZone(down.position, touch)
                                    do {
                                        val event = awaitPointerEvent()
                                        val pressed = event.changes.filter { it.pressed }
                                        if (pressed.isEmpty()) break
                                        if (pressed.size >= 2) {
                                            // 双指：缩放图片（并跟随质心平移）
                                            val zoom = event.calculateZoom()
                                            val pan = event.calculatePan()
                                            if (zoom != 1f || pan != Offset.Zero) {
                                                val ns = (scale * zoom).coerceIn(minScaleNow(), maxScaleNow())
                                                scale = ns
                                                offset = clampOffset(ns, offset + pan)
                                            }
                                            pressed.forEach { it.consume() }
                                        } else {
                                            val c = pressed.first()
                                            val delta = c.position - c.previousPosition
                                            when {
                                                zone >= 0 -> resizeFrame(zone, c.position)
                                                zone == -1 -> moveFrame(delta)
                                                else -> offset = clampOffset(scale, offset + delta)
                                            }
                                            c.consume()
                                        }
                                    } while (true)
                                }
                            },
                    ) {
                        if (stage.width > 0 && stage.height > 0) {
                            Image(
                                bitmap = image,
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        // 先绕中心缩放，再整体平移：p → c + (p-c)*s + t
                                        scaleX = scale
                                        scaleY = scale
                                        translationX = offset.x
                                        translationY = offset.y
                                    },
                            )
                            CropOverlay(
                                frame = frameRect,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ── 效果预览：实时显示取景框里裁出来的成品（所见即所得）──
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "效果预览",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.6f),
                    )
                    Box(
                        Modifier
                            .height(112.dp)
                            .aspectRatio(outAr)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF16161C))
                            .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(8.dp)),
                    ) {
                        if (frameRect.width > 0f) {
                            Canvas(Modifier.fillMaxSize()) {
                                val f = fitSize()
                                val src = srcRectOf(
                                    bitmap,
                                    stage.width.toFloat(), stage.height.toFloat(),
                                    f.width, f.height,
                                    scale, offset, frameRect,
                                )
                                drawImage(
                                    image = image,
                                    srcOffset = IntOffset(src.left, src.top),
                                    srcSize = IntSize(src.width(), src.height()),
                                    dstOffset = IntOffset.Zero,
                                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                                    filterQuality = FilterQuality.Medium,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(onClick = onDismiss) {
                        Text("取消", color = Color.White.copy(alpha = 0.75f))
                    }
                    Button(
                        enabled = stage.width > 0 && frameRect.width > 0f && !working,
                        onClick = {
                            if (stage.width > 0 && frameRect.width > 0f) {
                                working = true
                                val f = fitSize()
                                val cropped = cropBitmap(
                                    bitmap,
                                    stage.width.toFloat(), stage.height.toFloat(),
                                    f.width, f.height,
                                    scale, offset, frameRect,
                                )
                                if (cropped != null) onConfirm(cropped) else onDismiss()
                            }
                        },
                    ) {
                        Text(if (working) "处理中…" else "确定")
                    }
                    TextButton(
                        enabled = stage.width > 0,
                        onClick = { resetAll() },
                    ) {
                        Text("重置", color = Color.White.copy(alpha = 0.75f))
                    }
                }
            }
        }
    }
}

/** 取景框覆盖层：框外压暗 + 白框 + 三分线 + 四角把手。 */
@Composable
private fun CropOverlay(frame: Rect, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (frame.width <= 0f) return@Canvas
        // 框外压暗（四块矩形拼出来，避免用混合模式清屏）
        val scrim = Color.Black.copy(alpha = 0.5f)
        drawRect(scrim, size = Size(size.width, frame.top))
        drawRect(
            scrim,
            topLeft = Offset(0f, frame.bottom),
            size = Size(size.width, (size.height - frame.bottom).coerceAtLeast(0f)),
        )
        drawRect(
            scrim,
            topLeft = Offset(0f, frame.top),
            size = Size(frame.left, frame.height),
        )
        drawRect(
            scrim,
            topLeft = Offset(frame.right, frame.top),
            size = Size((size.width - frame.right).coerceAtLeast(0f), frame.height),
        )
        // 边框
        drawRect(
            color = Color.White.copy(alpha = 0.9f),
            topLeft = frame.topLeft,
            size = frame.size,
            style = Stroke(width = 2.dp.toPx()),
        )
        // 三分线
        val line = Color.White.copy(alpha = 0.22f)
        val thin = 1.dp.toPx()
        for (i in 1..2) {
            val x = frame.left + frame.width * i / 3f
            val y = frame.top + frame.height * i / 3f
            drawLine(line, Offset(x, frame.top), Offset(x, frame.bottom), thin)
            drawLine(line, Offset(frame.left, y), Offset(frame.right, y), thin)
        }
        // 四角把手（L 形，明确「这里可以拖」）
        val arm = 20.dp.toPx()
        val thick = 3.5.dp.toPx()
        fun bracket(cx: Float, cy: Float, dx: Float, dy: Float) {
            drawLine(Color.White, Offset(cx, cy), Offset(cx + dx * arm, cy), thick, cap = StrokeCap.Round)
            drawLine(Color.White, Offset(cx, cy), Offset(cx, cy + dy * arm), thick, cap = StrokeCap.Round)
        }
        bracket(frame.left, frame.top, 1f, 1f)
        bracket(frame.right, frame.top, -1f, 1f)
        bracket(frame.left, frame.bottom, 1f, -1f)
        bracket(frame.right, frame.bottom, -1f, -1f)
    }
}

/**
 * 取景框 → 原图像素区域的映射（预览与最终裁剪共用，保证「看到什么就裁什么」）。
 *
 * 映射：图以 [fitW]×[fitH]（Contain 后尺寸）居中画在取景区里，绕中心缩放 [scale] 再平移 [offset]。
 * 于是「取景区某点 → 原图坐标」= `((点 - 取景区中心 - offset) / (fit尺寸 × scale)) × 原图尺寸`。
 */
private fun srcRectOf(
    src: Bitmap,
    stageW: Float,
    stageH: Float,
    fitW: Float,
    fitH: Float,
    scale: Float,
    offset: Offset,
    frame: Rect,
): android.graphics.Rect {
    // 图中变换后在取景区里的左上角
    val imgLeft = (stageW - fitW * scale) / 2f + offset.x
    val imgTop = (stageH - fitH * scale) / 2f + offset.y

    // 屏幕像素 → 源像素
    val kx = src.width / (fitW * scale)
    val ky = src.height / (fitH * scale)

    val l = ((frame.left - imgLeft) * kx).roundToInt().coerceIn(0, src.width - 1)
    val t = ((frame.top - imgTop) * ky).roundToInt().coerceIn(0, src.height - 1)
    val w = (frame.width * kx).roundToInt().coerceIn(1, src.width - l)
    val h = (frame.height * ky).roundToInt().coerceIn(1, src.height - t)
    return android.graphics.Rect(l, t, l + w, t + h)
}

/** 把框内可见区域从原图抠出来。 */
private fun cropBitmap(
    src: Bitmap,
    stageW: Float,
    stageH: Float,
    fitW: Float,
    fitH: Float,
    scale: Float,
    offset: Offset,
    frame: Rect,
): Bitmap? = runCatching {
    val r = srcRectOf(src, stageW, stageH, fitW, fitH, scale, offset, frame)
    Bitmap.createBitmap(src, r.left, r.top, r.width(), r.height())
}.getOrNull()

// ── Uri → Bitmap ────────────────────────────────────────────

/** 长边上限：超出先降采样（相机原图动辄 4000px，真正需要的只有屏幕大小一块）。 */
private const val MAX_EDGE = 2048

/**
 * 解码用户选的图供框选使用；长边超过 [MAX_EDGE] 先降采样。
 *
 * 直接 `decodeStream` 一张 4000×3000 要吃 48MB，加上界面里已有一张封面就容易 OOM。
 */
suspend fun loadBitmapForCrop(context: Context, uri: Uri): Bitmap? =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                // ImageDecoder 自带 EXIF 方向校正，BitmapFactory 那条路没有
                val src = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                    val w = info.size.width
                    val h = info.size.height
                    val longEdge = max(w, h)
                    if (longEdge > MAX_EDGE) {
                        val k = longEdge.toFloat() / MAX_EDGE
                        decoder.setTargetSize((w / k).roundToInt(), (h / k).roundToInt())
                    }
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } else {
                // API 26/27：两遍解码，第一遍只读尺寸
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
                var sample = 1
                while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_EDGE) sample *= 2
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, opts)
                }
            }
        }.getOrNull()
    }

/** 把框好的 Bitmap 存成 JPEG 并返回绝对路径；失败返回 null。 */
fun saveBitmapAsJpeg(context: Context, bitmap: Bitmap, fileName: String): String? =
    runCatching {
        val dest = java.io.File(context.filesDir, fileName)
        dest.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
        dest.absolutePath
    }.getOrNull()
