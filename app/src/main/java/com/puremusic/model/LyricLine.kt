package com.puremusic.model

/** 一行歌词：时间戳 + 文本。 */
data class LyricLine(
    val timeMs: Long,
    val text: String,
)

/** 一首歌的完整歌词，含原文与翻译。 */
data class Lyrics(
    val lines: List<LyricLine> = emptyList(),
    val translation: List<LyricLine> = emptyList(),
)
