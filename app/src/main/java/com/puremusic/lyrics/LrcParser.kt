package com.puremusic.lyrics

import com.puremusic.model.LyricLine

/**
 * LRC 歌词解析器：支持 [mm:ss.xx]、[mm:ss:xx] 两种时间格式；
 * 支持 [ti:]/[ar:]/[al:]/[by:]/[offset:] 等元数据标签；
 * 同一行多个时间标签会展开为多行；[offset:] 正值表示歌词整体提前（加到每行时间戳）。
 */
object LrcParser {

    private val timeTagRegex = Regex("""\[(\d{1,2}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val metaRegex = Regex("""\[(ti|ar|al|by|offset|re|ve):(.*)]""", RegexOption.IGNORE_CASE)

    /** 解析结果：元数据 + 歌词行（按时间排序，同时间戳多行文本合并）。 */
    data class ParsedLrc(
        val metadata: Map<String, String> = emptyMap(),
        val lines: List<LyricLine> = emptyList(),
    )

    /** 兼容旧接口：只返回歌词行。 */
    fun parse(lrc: String): List<LyricLine> = parseFull(lrc).lines

    fun parseFull(lrc: String): ParsedLrc {
        val metadata = mutableMapOf<String, String>()
        val entries = mutableListOf<Pair<Long, String>>()
        var offsetMs = 0L

        for (raw in lrc.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val meta = metaRegex.find(line)
            if (meta != null) {
                val key = meta.groupValues[1].lowercase()
                val value = meta.groupValues[2].trim()
                metadata[key] = value
                if (key == "offset") offsetMs = value.toLongOrNull() ?: 0L
                continue
            }
            val tags = timeTagRegex.findAll(line).toList()
            if (tags.isEmpty()) continue
            val text = line.substring(tags.last().range.last + 1).trim()
            if (text.isEmpty()) continue
            for (tag in tags) {
                val g = tag.groupValues
                val min = g[1].toLong()
                val sec = g[2].toLong()
                val frac = g[3].ifEmpty { "0" }.padEnd(3, '0').take(3)
                val timeMs = min * 60_000 + sec * 1_000 + frac.toLong() + offsetMs
                entries += timeMs to text
            }
        }

        val lines = entries
            .sortedBy { it.first }
            .groupBy { it.first }
            .map { (t, pairs) -> LyricLine(t, pairs.joinToString("\n") { it.second }) }
        return ParsedLrc(metadata, lines)
    }
}
