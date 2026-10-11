package com.puremusic.source.match

import com.puremusic.model.Song

/** 搜索结果分层：strong 为强相关（主结果），weak 为弱相关（折叠在「其它结果」）。 */
data class RelevanceResult(
    val strong: List<Song>,
    val weak: List<Song>,
)

/**
 * 搜索结果相关性过滤（对齐商业播放器的搜索质量）。
 *
 * 背景：各音源的搜索接口「宁可多给也不漏给」，聚合后会混入大量弱相关结果
 * （例如搜 `Faded` 会带出 `F. - 李子豪`）。这类结果直接丢弃会误伤冷门/翻唱版本，
 * 混在主列表里又会让搜索显得很脏。因此做法是**分层而不是删除**：
 * - 强相关（≥ [STRONG_THRESHOLD]）：正常展示，排在前面；
 * - 弱相关：折叠在「其它结果」分区，用户想找冷门版本时可展开。
 *
 * 打分优先级（越高越相关）：
 *  1.0  标题归一化后与关键词完全一致
 *  0.95 标题以关键词开头
 *  0.9  标题是关键词的子串（搜「Alan Walker Faded」命中标题「Faded」）
 *  0.85 标题包含关键词
 *  0.8  歌手/专辑命中关键词（搜歌手名时该歌手所有歌都算相关）
 *  ≤0.8 以上都不命中时，退化为标题 Dice bigram 相似度×0.8
 */
object SearchRelevance {

    /** 强相关阈值：低于该分数归入「其它结果」。 */
    private const val STRONG_THRESHOLD = 0.5

    /** 关键词归一化：与标题/歌手同一套规则，才能直接做包含判定。 */
    private fun normalize(text: String): String = SongMatcher.coreTitle(text)

    /**
     * 相关性打分（0~1）。标题命中 > 歌手/专辑命中 > 模糊相似。
     */
    fun score(query: String, song: Song): Double {
        val q = normalize(query)
        if (q.isBlank()) return 1.0 // 无有效关键词时不做过滤
        val title = normalize(song.title)
        val artist = normalize(song.artist)
        val album = normalize(song.album)

        // 单字符关键词：后缀/包含判定全部失效（几乎什么都能“包含”一个字符），
        // 退化为「标题以该字符开头」+ 相似度兜底，否则搜 F 会带出「万有引力」这类噪声
        if (q.length < 2) {
            return when {
                title == q -> 1.0
                title.startsWith(q) -> 0.95
                else -> SongMatcher.titleSimilarity(song.title, query) * 0.8
            }
        }

        if (title.isNotBlank()) {
            when {
                title == q -> return 1.0
                title.startsWith(q) -> return 0.95
                // 关键词比标题长且完整包含标题：如「周杰伦 晴天」命中「晴天」
                q.contains(title) && title.length >= 2 -> return 0.9
                title.contains(q) && q.length >= 2 -> return 0.85
            }
        }
        // 搜歌手 / 专辑名：该歌手或专辑下的曲目都算相关
        if (artist.isNotBlank() && (artist.contains(q) || q.contains(artist)) && q.length >= 2) {
            return 0.8
        }
        if (album.isNotBlank() && album.contains(q) && q.length >= 2) return 0.8

        return SongMatcher.titleSimilarity(song.title, query) * 0.8
    }

    /** 是否强相关。 */
    fun isRelevant(query: String, song: Song): Boolean = score(query, song) >= STRONG_THRESHOLD

    /**
     * 按相关度分层并排序：强相关按分数降序，弱相关保持原顺序附在后面。
     * 全部结果都已经过排序，调用方可直接用于展示与并组。
     */
    fun partition(query: String, songs: List<Song>): RelevanceResult {
        val scored = songs.map { it to score(query, it) }
        // 派生版本（伴奏 / KTV / DJ / 现场 / 翻唱）折叠进「其它结果」：
        // 酷我等源常把它们排在搜索结果前列，混进主列表会让搜索显得很脏。
        // 用户主动搜这类词（"晴天 伴奏"）时不折叠，保证想找的东西还能找到。
        val queryWantsDerivative = SongMatcher.isDerivative(query)
        val isDerivative = { s: Song -> !queryWantsDerivative && SongMatcher.isDerivative(s.title) }
        return RelevanceResult(
            strong = scored.filter { it.second >= STRONG_THRESHOLD && !isDerivative(it.first) }
                .sortedByDescending { it.second }
                .map { it.first },
            weak = (scored.filter { it.second < STRONG_THRESHOLD } +
                scored.filter { it.second >= STRONG_THRESHOLD && isDerivative(it.first) })
                .sortedByDescending { it.second }
                .map { it.first },
        )
    }
}
