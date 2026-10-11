package com.puremusic.ui.theme

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 全局动效中心（移植自 IRISMusic 的 IrisMotion）。
 *
 * 整个 App 的弹性手感集中在这里：低阻尼弹簧带来"果冻回弹"，
 * 页面/列表入场统一走 spring，避免各处 tween 时长不一导致的顿挫感。
 */
object PureMotion {
    /** 按钮按压回弹（按压缩小 → 松手弹回） */
    fun press(): FiniteAnimationSpec<Float> = spring(dampingRatio = 0.55f, stiffness = 380f)

    /** 开关拨钮滑动 */
    fun knob(): FiniteAnimationSpec<Float> = spring(dampingRatio = 0.6f, stiffness = 420f)

    /** 卡片 / 元素位移 */
    fun move(): FiniteAnimationSpec<Float> = spring(dampingRatio = 0.75f, stiffness = 340f)
    fun moveDp(): FiniteAnimationSpec<Dp> = spring(dampingRatio = 0.75f, stiffness = 340f)

    /** 出现 / 展开 */
    fun appear(): FiniteAnimationSpec<Float> = spring(dampingRatio = 0.7f, stiffness = 300f)
    fun appearDp(): FiniteAnimationSpec<Dp> = spring(dampingRatio = 0.7f, stiffness = 300f)

    /** 列表逐项错开的时间间隔（ms） */
    const val STAGGER_MS = 26
}

/** 按压强度档位 */
enum class PressFeedback { CLICK, TAP, NONE }

/** 主控制按钮按压幅度：大按钮缩得多一点才有回弹感 */
const val PRESS_SCALE_STRONG = 0.86f

/** 通用按压幅度：图标按钮、开关、条目 */
const val PRESS_SCALE_DEFAULT = 0.94f

/**
 * 可按压元素的统一封装：弹簧按压缩放 + 轻触感 + 无水波点击。
 *
 * 收拢成 Modifier 之前，这类样板要在每处自己 remember InteractionSource、collectIsPressed、
 * animateFloatAsState、写 graphicsLayer 和 indication = null，按压幅度还会各处飘。
 */
@Composable
fun Modifier.pressable(
    scaleDown: Float = PRESS_SCALE_DEFAULT,
    feedback: PressFeedback = PressFeedback.TAP,
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier {
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) scaleDown else 1f,
        animationSpec = PureMotion.press(),
        label = "pressScale",
    )
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
        ) {
            when (feedback) {
                PressFeedback.CLICK -> view.haptic(HapticFeedbackConstants.CLOCK_TICK)
                PressFeedback.TAP -> view.haptic(HapticFeedbackConstants.KEYBOARD_TAP)
                PressFeedback.NONE -> Unit
            }
            onClick()
        }
}

private fun View.haptic(code: Int) {
    runCatching { performHapticFeedback(code) }
}

/**
 * 列表项入场：淡入 + 轻微上浮 + 逐项错开。
 *
 * 只跑一次（[LaunchedEffect] key 为 Unit），不会因为重组或滚动重新触发 ——
 * 否则每次滚动列表都会整屏闪一遍。
 */
@Composable
fun Modifier.staggerEnter(index: Int = 0, offsetY: Dp = 14.dp): Modifier {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        // 只让首屏几项错开：长列表第 N 项若真的 delay N*26ms，滚到底部要等好几秒。
        val slot = index.coerceAtMost(8)
        if (slot > 0) kotlinx.coroutines.delay((slot * PureMotion.STAGGER_MS).toLong())
        progress.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 300f))
    }
    val p by progress.asState()
    return this.graphicsLayer {
        alpha = p
        translationY = (1f - p) * offsetY.toPx()
    }
}

/** 底部弹层统一入场：自底部上滑归位。 */
@Composable
fun Modifier.sheetSlideIn(offsetDp: Dp = 160.dp): Modifier {
    val slide = remember { Animatable(1f) }
    LaunchedEffect(Unit) { slide.animateTo(0f, tween(300, easing = FastOutSlowInEasing)) }
    return this.graphicsLayer { translationY = slide.asState().value * offsetDp.toPx() }
}

/**
 * 页面级入场：整块内容淡入并轻微上浮。比 Navigation 默认的横向位移更"顺"，
 * 尤其适合列表页——横移会让整屏文字跟着扫，观感偏重。
 */
@Composable
fun Modifier.screenEnter(offsetY: Dp = 18.dp): Modifier {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 260f)) }
    val p by progress.asState()
    return this.graphicsLayer {
        alpha = p
        translationY = (1f - p) * offsetY.toPx()
    }
}
