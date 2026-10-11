package com.puremusic.model

enum class DarkMode { SYSTEM, LIGHT, DARK }

/** 全局圆角风格：以 UiTokens 圆角为基准按比例缩放。 */
enum class CornerStyle(val label: String, val scale: Float) {
    STANDARD("标准", 0.55f),
    LARGE("大圆角", 1f),
    ROUNDED("超大", 1.45f),
}

/** 布局密度：整体 dp 缩放，间距与控件大小同步变化（不改代码即可全局生效）。 */
enum class LayoutDensity(val label: String, val scale: Float) {
    COMPACT("紧凑", 0.92f),
    NORMAL("标准", 1f),
    COMFORTABLE("宽松", 1.08f),
}

/**
 * 主题配置。
 *
 * 注意这里**没有**主题色字段：全局配色一律由当前播放封面的主色驱动（`PureMusicTheme` 的
 * `coverColor` 参数）。曾经存在过的 `seedColor` / `dynamicColor` / `amoledBlack` 三个字段
 * 已随设置项一起删除 —— 前两个本来就永远不生效（AppRoot 恒传封面主色），留着只会给人
 * 「改了却没反应」的错觉。
 */
data class ThemeConfig(
    val darkMode: DarkMode = DarkMode.SYSTEM,
    /** 全局字体缩放（0.85 小 / 1.0 标准 / 1.15 大）。 */
    val fontScale: Float = 1.0f,
    val cornerStyle: CornerStyle = CornerStyle.LARGE,
    val layoutDensity: LayoutDensity = LayoutDensity.NORMAL,
    /**
     * 液态玻璃材质（真折射 / 模糊 / 边缘光学）。
     * 关掉后所有玻璃元件退回半透明实色 —— 低端机上若觉得滚动掉帧可关。
     */
    val liquidGlass: Boolean = true,
    /**
     * 背景模糊强度：0 = 完全清晰，1 = 最糊（约 40dp 模糊半径）。
     * 默认 0.45 —— 太糊会丢掉背景图的细节，太清则文字压不住。
     */
    val blurStrength: Float = 0.45f,
)
