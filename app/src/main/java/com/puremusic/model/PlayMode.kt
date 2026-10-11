package com.puremusic.model

/**
 * 播放模式：**一个按钮，点三下循环三种形态**。
 *
 * - [SEQUENCE] 顺序播放：按列表顺序播，播完最后一首回到第一首（不会停下来）；
 * - [SHUFFLE]  随机播放：随机取下一首，同样一直续播；
 * - [REPEAT_ONE] 单曲循环：当前这首反复播，不自动切走。
 *
 * 为什么不拆成「随机」+「循环」两个开关：那需要两个按钮，
 * 而实际只会用到上面三种组合，两个开关还会产生「随机 + 单曲」这种无意义状态。
 */
enum class PlayMode(val label: String) {
    SEQUENCE("顺序播放"),
    SHUFFLE("随机播放"),
    REPEAT_ONE("单曲循环"),
    ;

    fun next(): PlayMode = when (this) {
        SEQUENCE -> SHUFFLE
        SHUFFLE -> REPEAT_ONE
        REPEAT_ONE -> SEQUENCE
    }
}
