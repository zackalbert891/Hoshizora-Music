package com.puremusic.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * 细线进度条：**一条 2.5dp 的横线 + 一个圆点**。
 *
 * 为什么不用 Material 的 Slider：
 * - Slider 有自己的轨道（默认 4dp）与 20dp 触摸高度，和下方的缓冲条叠加后会看到**两条进度条**；
 * - Slider 的圆点固定、轨道偏粗，和沉浸式播放页的观感不搭。
 *
 * 这里把「已播放 / 缓冲 / 未播放」三种状态画在**同一条轨道**上（缓冲用更亮的同色系），
 * 视觉上永远只有一条线；拖动时圆点放大并在上方浮出时间气泡，松手才真正 seek。
 */
@Composable
fun ThinSeekBar(
    progress: Float,
    modifier: Modifier = Modifier,
    buffered: Float = 0f,
    activeColor: Color = Color.White,
    trackColor: Color = Color.White.copy(alpha = 0.16f),
    bufferedColor: Color = Color.White.copy(alpha = 0.30f),
    thumbColor: Color = activeColor,
    trackHeight: Dp = 2.5.dp,
    thumbRadius: Dp = 5.dp,
    showThumb: Boolean = true,
    /** 拖动时的气泡文案（传入当前比例，返回展示文本）；为 null 则不显示气泡。 */
    bubbleText: ((Float) -> String)? = null,
    onDraggingChanged: (Boolean) -> Unit = {},
    onSeek: (Float) -> Unit = {},
) {
    val haptics = LocalHapticFeedback.current
    var width by remember { mutableIntStateOf(1) }
    var dragging by remember { mutableStateOf(false) }
    var dragRatio by remember { mutableFloatStateOf(0f) }
    var bubbleWidth by remember { mutableIntStateOf(0) }

    val shown = if (dragging) dragRatio else progress.coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .height(34.dp)
            .onSizeChanged { width = it.width.coerceAtLeast(1) }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val ratio = (offset.x / width).coerceIn(0f, 1f)
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onSeek(ratio)
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        dragRatio = (offset.x / width).coerceIn(0f, 1f)
                        onDraggingChanged(true)
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    },
                    onDragEnd = {
                        dragging = false
                        onDraggingChanged(false)
                        onSeek(dragRatio)
                    },
                    onDragCancel = {
                        dragging = false
                        onDraggingChanged(false)
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        dragRatio = (change.position.x / width).coerceIn(0f, 1f)
                    },
                )
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cy = size.height / 2f
            val trackPx = trackHeight.toPx()
            val radius = trackPx / 2f
            // 圆点半径决定左右内缩，避免圆点被裁掉
            val inset = thumbRadius.toPx()
            val left = inset
            val usable = (size.width - inset * 2f).coerceAtLeast(1f)

            fun bar(color: Color, ratio: Float) {
                if (ratio <= 0f) return
                drawRoundRect(
                    color = color,
                    topLeft = Offset(left, cy - radius),
                    size = Size(usable * ratio.coerceIn(0f, 1f), trackPx),
                    cornerRadius = CornerRadius(radius, radius),
                )
            }

            bar(trackColor, 1f)
            bar(bufferedColor, buffered.coerceIn(0f, 1f))
            bar(activeColor, shown)

            if (showThumb) {
                val cx = left + usable * shown
                val thumbPx = thumbRadius.toPx() * if (dragging) 1.35f else 1f
                drawCircle(thumbColor.copy(alpha = 0.18f), radius = thumbPx * 2.0f, center = Offset(cx, cy))
                drawCircle(thumbColor, radius = thumbPx, center = Offset(cx, cy))
            }
        }

        if (dragging && bubbleText != null) {
            Text(
                text = bubbleText(dragRatio),
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .onSizeChanged { bubbleWidth = it.width }
                    .graphicsLayer {
                        val inset = thumbRadius.toPx()
                        val usable = (width - inset * 2f).coerceAtLeast(1f)
                        val cx = inset + usable * dragRatio
                        translationX = (cx - bubbleWidth / 2f)
                            .coerceIn(0f, (width - bubbleWidth).toFloat().coerceAtLeast(0f))
                    }
                    .background(activeColor, RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

/** 供调用点把毫秒转成展示文本用的工具（避免各处重复实现）。 */
fun seekBubbleLabel(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/** 把圆点拖到某个比例、换算回毫秒（方便调用点）。 */
fun ratioToMs(ratio: Float, durationMs: Long): Long =
    (durationMs.toFloat() * ratio.coerceIn(0f, 1f)).roundToInt().toLong()
