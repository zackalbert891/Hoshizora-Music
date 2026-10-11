package com.puremusic.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 背景图「框选」对话框：选完图不直接按居中裁掉，而是让用户自己框。
 *
 * 之前是 `ContentScale.Crop` 直接居中取景：用户挑一张竖版壁纸当音乐库背景，
 * 手机是 9:19.5 的长屏，横向内容几乎全被裁掉，人像/主体偏上更是直接看不见。
 * 现在改成全图铺在框里，**拖动平移 + 双指缩放**，框住哪块就存哪块。
 *
 * 取景框的宽高比默认与屏幕一致（背景图最终就是全屏铺满），
 * 这样框出来的成品用 `ContentScale.Crop` 展示时不会再二次裁掉内容。
 */
@Composable
fun ImageCropDialog(
    bitmap: Bitmap,
    /** 输出宽高比；默认与屏幕一致。 */
    aspectRatio: Float? = null,
    onDismiss: () -> Unit,
    onConfirm: (Bitmap) -> Unit,
) {
    val cfg = LocalConfiguration.current
    val ratio = aspectRatio ?: (cfg.screenHeightDp.toFloat() / cfg.screenWidthDp.toFloat())

    var frame by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var inited by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }

    val fw0 = frame.width.toFloat()
    val fh0 = frame.height.toFloat()

    // 图片按 Contain 放进框后的实际显示尺寸 —— 所有换算都以它为基准。
    val fitW: Float
    val fitH: Float
    if (fw0 > 0f && fh0 > 0f) {
        val k = min(fw0 / bitmap.width, fh0 / bitmap.height)
        fitW = bitmap.width * k
        fitH = bitmap.height * k
    } else {
        fitW = 0f
        fitH = 0f
    }

    /** 缩放范围：min 是刚好盖满框（contain），max 放大到 6 倍。 */
    val minScale = min(fw0 / fitW, fh0 / fitH).let { if (it.isFinite()) it else 1f }
    val maxScale = minScale * 6f

    /** 保证图始终盖满框：偏移上限 = (缩放后显示尺寸 - 框尺寸) / 2。 */
    fun clampOffset(s: Float, o: Offset): Offset {
        val maxX = max(0f, (fitW * s - fw0) / 2f)
        val maxY = max(0f, (fitH * s - fh0) / 2f)
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    LaunchedEffect(frame) {
        if (frame.width > 0 && frame.height > 0 && !inited) {
            scale = minScale
            offset = Offset.Zero
            inited = true
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF0B0B0F)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
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
                    "拖动平移 · 双指缩放 · 框住你想要的部分",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f),
                )
                Spacer(Modifier.height(16.dp))

                // ── 取景框 ──
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(ratio)
                        .background(Color(0xFF16161C), RoundedCornerShape(12.dp))
                        .onSizeChanged { frame = it },
                ) {
                    if (frame.width > 0) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
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
                                }
                                .pointerInput(Unit) {
                                    detectTransformGestures { _, pan, zoom, _ ->
                                        val ns = (scale * zoom).coerceIn(minScale, maxScale)
                                        val no = clampOffset(ns, offset + pan)
                                        scale = ns
                                        offset = no
                                    }
                                },
                        )
                        // 三分线 + 边框：给用户构图参照，同时明确"框住的就是这一整块"
                        Canvas(Modifier.fillMaxSize()) {
                            val stroke = 1.dp.toPx()
                            drawRect(
                                color = Color.White.copy(alpha = 0.85f),
                                topLeft = Offset.Zero,
                                size = Size(size.width, size.height),
                                style = Stroke(width = stroke),
                            )
                            val line = Color.White.copy(alpha = 0.22f)
                            for (i in 1..2) {
                                val x = size.width * i / 3f
                                val y = size.height * i / 3f
                                drawLine(line, Offset(x, 0f), Offset(x, size.height), stroke)
                                drawLine(line, Offset(0f, y), Offset(size.width, y), stroke)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(onClick = onDismiss) {
                        Text("取消", color = Color.White.copy(alpha = 0.75f))
                    }
                    Button(
                        enabled = frame.width > 0 && !working,
                        onClick = {
                            if (frame.width == 0) return@Button
                            working = true
                            val cropped = runCatching {
                                cropBitmap(bitmap, fw0, fh0, fitW, fitH, scale, offset)
                            }.getOrNull()
                            if (cropped != null) onConfirm(cropped) else onDismiss()
                        },
                    ) {
                        Text(if (working) "处理中…" else "确定")
                    }
                    TextButton(
                        enabled = frame.width > 0,
                        onClick = {
                            scale = minScale
                            offset = Offset.Zero
                        },
                    ) {
                        Text("重置", color = Color.White.copy(alpha = 0.75f))
                    }
                }
            }
        }
    }
}

/**
 * 把框内可见区域从原图抠出来。
 *
 * 映射：图以 [fitW]×[fitH]（Contain 后尺寸）居中画在框里，整体缩放 [scale] 再平移 [offset]。
 * 于是「框内某点 → 原图坐标」= `((框内点 - 框中心 - offset) / scale) + fit尺寸/2`，
 * 再按 fit 尺寸 / 原图尺寸 的比例映射回像素。
 */
private fun cropBitmap(
    src: Bitmap,
    frameW: Float,
    frameH: Float,
    fitW: Float,
    fitH: Float,
    scale: Float,
    offset: Offset,
): Bitmap {
    // fit 坐标系（左上角为原点）里框的左上角
    val boxLeft = (frameW - fitW * scale) / 2f + offset.x
    val boxTop = (frameH - fitH * scale) / 2f + offset.y
    val boxW = frameW / scale
    val boxH = frameH / scale

    val kx = src.width / fitW
    val ky = src.height / fitH

    var left = (boxLeft * kx).roundToInt()
    var top = (boxTop * ky).roundToInt()
    var w = (boxW * kx).roundToInt()
    var h = (boxH * ky).roundToInt()

    left = left.coerceIn(0, src.width - 1)
    top = top.coerceIn(0, src.height - 1)
    w = w.coerceIn(1, src.width - left)
    h = h.coerceIn(1, src.height - top)

    return Bitmap.createBitmap(src, left, top, w, h)
}

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