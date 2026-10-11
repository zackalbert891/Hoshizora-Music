package com.puremusic.source.match

import com.puremusic.model.Song

/**
 * 跨平台「同一首歌」匹配引擎（借鉴 Listen1 / UnblockNeteaseMusic / mineradio 的成熟做法）。
 *
 * 设计原则：**宁可漏匹配，绝不配错**。错配会导致换源放错歌、评论串到别的歌，
 * 比"换源失败/没评论"严重得多。因此所有判定都偏严格。
 *
 * 三重判定（[isSameSong]）：
 *  1) 标题相似度 ≥ 0.9（归一化精确相等 = 1.0，否则取 Dice bigram）；
 *  2) 歌手重叠（多歌手拆分 + 中英文别名映射，任一命中即算同演唱者）；
 *  3) 时长差 ≤ 8s（任一缺失时长不惩罚）。
 *
 * 另提供 [groupSongs] 把搜索结果按同曲并组（Listen1 式多源并列展示）。
 */
object SongMatcher {

    /**
     * 歌手别名映射：键/值双向互通（中英文名、常用拼写）。匹配时归一化后做集合交集，
     * 解决「周杰伦 / Jay Chou」「薛之谦 / Joker Xue」等分属不同平台写法导致的漏配。
     */
    private val ARTIST_ALIASES: Map<String, Set<String>> = buildMap {
        fun put(vararg forms: String) {
            for (f in forms) {
                val set = forms.toSet() - f
                this@buildMap[f] = set
            }
        }
        // 网易云部分曲目的歌手写作 "Jay"，不补别名会与 QQ/酷我的「周杰伦」判为不同歌手，
        // 进而让跨源一致性排序失效（搜「稻香」会选中翻唱版而不是周杰伦原版）
        put("周杰伦", "zhoujielun", "jaychou", "jay chou", "jay")
        put("薛之谦", "xuezhiqian", "jokerxue", "joker xue")
        put("陈奕迅", "chenyixun", "easonchan", "eason chan")
        put("林俊杰", "linjunjie", "jjlin", "jj lin", "wayne lin")
        put("邓紫棋", "dengziqi", "gemtang", "g.e.m.", "gem")
        put("李荣浩", "lironghao", "ronghao li")
        put("张学友", "zhangxueyou", "jackycheung", "jacky cheung")
        put("刘德华", "liudehua", "andylau", "andy lau")
        put("王菲", "wangfei", "fayewong", "faye wong")
        put("孙燕姿", "sunyanzi", "stefaniesun", "stefanie sun")
        put("张杰", "zhangjie", "jasonzhang", "jason zhang")
        put("五月天", "wuyuetian", "mayday")
        put("苏打绿", "sodagreen", "soda green")
        put("毛不易", "maobuyi", "mouse")
        put("杨丞琳", "yangchenglin", "rainie yang")
        put("田馥甄", "tianfuzhen", "hebe")
        put("蔡依林", "caiyilin", "jolin tsai", "jolin")
        put("周深", "zhoushen", "charlie zhou")
        put("华晨宇", "huachenyu", "mars")
        put("张韶涵", "zhangshaohan", "angela zhang")
        put("梁静茹", "liangjingru", "fish leong")
        put("莫文蔚", "mowenwei", "karen mok")
        put("陈粒", "chenli")
        put("李健", "lijian")
        put("朴树", "pushu", "pu shu")
    }

    private val ARTIST_SEPARATORS = Regex("[/、&,，]|(feat\\.?)|(ft\\.?)|(合作)|( vs )|( x )")
    private val TITLE_NOISE = Regex("[\\(\\[（【][^()\\[\\]（）【】]*[\\)\\]）】]|[\\s\\-—_·,，.。!！?？:：/\\\\'\"‘’“”&+~#@*\$%='’]")
    private val TITLE_SUFFIX = Regex("(?i)\\b(live|remix|伴奏|原版|新版|demo|acoustic|翻唱|现场|官方|mv|版)\\b")

    /**
     * 派生版本特征词（伴奏 / KTV / DJ / 现场 / 翻唱 / 纯音乐 / 片段 / 铃声 / 器乐版 …）。
     *
     * 为什么必须单独判定：[coreTitle] 为了算相似度会**主动剥掉**这些词
     * （"晴天(KTV版伴奏)" → "晴天"），这在相似度计算里是对的，但直接拿去换源/并组
     * 就会把伴奏、DJ 版、KTV 版当成原曲播给用户 —— 用户体感就是"换源放错歌"。
     * 因此这里用**原始标题**单独识别，再在 [isSameSong] 里做互斥。
     */
    /**
     * 派生版本词。
     *
     * 词边界必须用 `(?<![a-zA-Z])` / `(?![a-zA-Z])` 而非 `\b`：Java 的 `\b` 以
     * `[a-zA-Z_0-9]` 判定，中文不算单词字符，导致「平凡之路（DJ版）」里的
     * `\bdj\b` 边界判定异常、DJ 版被当成原版混入主列表（真机实测命中）。
     * 用前后「非英文字母」约束，DJ版 / Remix版 / Live版 才能稳定识别。
     */
    private val DERIVATIVE = Regex(
        "(?i)(" +
            "伴奏|纯音乐|无人声|无字|清唱|哼唱|和声|伴唱|卡拉|ktv|karaoke|" +
            "(?<![a-z])inst(?![a-z])|(?<![a-z])instrumental(?![a-z])|off\\s*vocal|" +
            "(?<![a-z])dj(?![a-z])|串烧|(?<![a-z])remix(?![a-z])|电音版|混音版|" +
            "翻唱|cover\\s*by|covered\\s*by|" +
            "(?<![a-z])live(?![a-z])|现场|演唱会|音乐节|" +
            "钢琴版|吉他版|古筝|二胡|小提琴|萨克斯|八音盒|指弹|弹唱|独奏|演奏版|纯音|" +
            "(?<![a-z])demo(?![a-z])|片段|铃声|彩铃|试听版|高潮版|副歌|" +
            "童声|女声版|男声版|合唱版|变速|加速版|慢速|降调|升调|" +
            // 二次创作/情绪版：真机实测「小幸运 (催泪版)」这类会顶掉原唱
            "催泪版|治愈版|伤感版|空灵版|温柔版|安静版|炸裂版|高燃版|带感|洗脑版|emo版|" +
            "鬼畜|二创|恶搞|搞笑版" +
            ")",
    )

    /** 标题是否为派生版本（伴奏 / KTV / DJ / 现场 / 翻唱 / 器乐 / 片段等）。 */
    fun isDerivative(title: String): Boolean = DERIVATIVE.containsMatchIn(title)

    /** 归一化标题：去括号内容(Live/Remix/伴奏)、去标点空格、转小写、剥离常见后缀词。 */
    fun coreTitle(title: String): String {
        var t = title.lowercase()
        t = t.replace(TITLE_NOISE, "")
        t = t.replace(TITLE_SUFFIX, "")
        return t.trim()
    }

    /** 歌手归一化：拆分多歌手、去噪转小写、应用别名映射，返回规范 token 集合。 */
    fun normalizeArtists(artist: String): Set<String> {
        if (artist.isBlank()) return emptySet()
        val result = mutableSetOf<String>()
        val parts: List<String> = artist.split(ARTIST_SEPARATORS)
        for (raw in parts) {
            val tok = raw.lowercase().replace(TITLE_NOISE, "").trim()
            if (tok.isBlank()) continue
            result.add(tok)
            ARTIST_ALIASES[tok]?.let { result.addAll(it) }
        }
        return result
    }

    /** 歌手是否重叠：任一词（含别名）命中即视为同一演唱者。 */
    fun artistOverlap(a: String, b: String): Boolean {
        val sa = normalizeArtists(a)
        val sb = normalizeArtists(b)
        if (sa.isEmpty() || sb.isEmpty()) return false
        return sa.intersect(sb).isNotEmpty()
    }

    /** 标题相似度：归一化精确相等 = 1.0，否则取 Dice bigram。 */
    fun titleSimilarity(a: String, b: String): Double {
        val ca = coreTitle(a)
        val cb = coreTitle(b)
        if (ca.isEmpty() || cb.isEmpty()) return 0.0
        if (ca == cb) return 1.0
        return diceBigram(ca, cb)
    }

    private fun bigrams(text: String): Set<String> {
        if (text.length < 2) return setOf(text)
        return text.windowed(2, 1).toSet()
    }

    private fun diceBigram(a: String, b: String): Double {
        val sa = bigrams(a)
        val sb = bigrams(b)
        if (sa.isEmpty() || sb.isEmpty()) return 0.0
        val inter = sa.count { it in sb }
        return 2.0 * inter / (sa.size + sb.size)
    }

    /** 时长是否接近：任一方缺失(<=0)视为未知不惩罚；否则差 ≤ toleranceMs（默认 8s，用于打分）。 */
    fun durationClose(aMs: Long, bMs: Long, toleranceMs: Long = 8000): Boolean {
        if (aMs <= 0 || bMs <= 0) return true
        return kotlin.math.abs(aMs - bMs) <= toleranceMs
    }

    /** 判定同曲时的时长硬排除阈值：差超过此值视为明显不同的录音。 */
    private const val MAX_DRIFT_MS = 45_000L

    /**
     * 判定是否为「同一首歌」：标题相似度 ≥ 0.9 且 歌手重叠 且 时长差 ≤ [MAX_DRIFT_MS]。
     * 严格判定——避免换源 / 评论映射放错歌。
     *
     * 时长仅作「硬排除」而非精确匹配：Live / 现场 / 扩展版常比录音室版长 10~30s，
     * 仍应认作同一首；只有差超过 45s 才视为明显不同的录音。时长缺失(≤0)不惩罚。
     */
    fun isSameSong(a: Song, b: Song): Boolean =
        isSameSong(a.title, a.artist, a.durationMs, b.title, b.artist, b.durationMs)

    fun isSameSong(
        titleA: String, artistA: String, durA: Long,
        titleB: String, artistB: String, durB: Long,
    ): Boolean {
        if (titleSimilarity(titleA, titleB) < 0.9) return false
        if (!artistOverlap(artistA, artistB)) return false
        // 派生版本互斥：原版与「伴奏 / KTV / DJ / 现场 / 翻唱 / 器乐版」不是同一首。
        // 双向对称——用户主动搜伴奏时，原版也不该并进伴奏那组。
        if (isDerivative(titleA) != isDerivative(titleB)) return false
        if (!durationClose(durA, durB, MAX_DRIFT_MS)) return false
        return true
    }

    /**
     * 跨源一致性排序：**同名歌曲中，被更多平台收录的歌手版本更可能是原版**。
     *
     * 为什么需要：网易云的搜索接口实测会把 AI/翻唱版本排在原唱前面
     * （搜「稻香」第一条是 `Lie`，周杰伦原版排到第三条；搜「青花瓷」第一条是 `刘芳`）。
     * 但同一首歌在 QQ / 酷我上返回的都是周杰伦 —— 「多个平台都收录」本身就是
     * 很强的正版/原唱信号，用它可以把原版顶回第一位。
     *
     * 只调整**同标题组内**的顺序：不同标题分组之间的相对顺序（即各平台的相关度排序）保持不变。
     */
    fun rankByConsensus(songs: List<Song>): List<Song> {
        if (songs.size <= 1) return songs
        val byTitle = LinkedHashMap<String, MutableList<Song>>()
        for (s in songs) {
            byTitle.getOrPut(coreTitle(s.title)) { mutableListOf() }.add(s)
        }
        val out = ArrayList<Song>(songs.size)
        for (group in byTitle.values) {
            if (group.size == 1) {
                out += group
                continue
            }
            // 该曲的“同源票数”：组内与它歌手重叠的条目覆盖了几个不同平台
            val votes = HashMap<Song, Int>()
            for (s in group) {
                votes[s] = group
                    .filter { other -> artistOverlap(s.artist, other.artist) }
                    .map { it.source }
                    .distinct()
                    .count()
            }
            // sortedByDescending 稳定排序：票数相同时保持接口原始顺序
            out += group.sortedByDescending { votes[it] ?: 1 }
        }
        return out
    }

    /**
     * 可排序匹配分数（0~1）：标题相似度为主，歌手重叠 +0.1，时长接近 +0.1。
     * 用于同曲候选排序；是否「同一首」仍以 [isSameSong] 为准。
     */
    fun matchScore(original: Song, candidate: Song): Double {
        var s = titleSimilarity(original.title, candidate.title)
        if (artistOverlap(original.artist, candidate.artist)) s += 0.1
        if (durationClose(original.durationMs, candidate.durationMs)) s += 0.1
        return s.coerceIn(0.0, 1.0)
    }

    /**
     * 把搜索结果按「同一首歌」并组（Listen1 式多源并列）。
     * - 组内每个平台最多保留一首（同平台取首个命中）；
     * - 组内展示主曲按 [sourceOrder] 优先级选取（如用户偏好网易云/QQ 在前）；
     * - 返回按偏好源、再按多源数量排序的分组列表。
     */
    fun groupSongs(songs: List<Song>, sourceOrder: List<String> = emptyList()): List<SongGroup> {
        val groups = mutableListOf<SongGroup>()
        for (song in songs) {
            val hit = groups.firstOrNull { g -> g.matches.any { isSameSong(it, song) } }
            if (hit != null) {
                if (hit.matches.none { it.source == song.source }) {
                    hit.matches.add(song)
                }
            } else {
                groups.add(SongGroup(primary = song, matches = mutableListOf(song)))
            }
        }
        // 组内选主展示：按 sourceOrder 优先级
        groups.forEach { g ->
            g.primary = g.matches.minByOrNull { orderIndex(it.source.id, sourceOrder) } ?: g.matches.first()
        }
        // 主源优先级高、且多源可用的组排在前面
        groups.sortByDescending { multiSourceWeight(it, sourceOrder) }
        return groups
    }

    private fun multiSourceWeight(g: SongGroup, order: List<String>): Int {
        // 主源优先级越高、可用平台越多，权重越大
        val primaryWeight = order.size - orderIndex(g.primary.source.id, order)
        return primaryWeight * 4 + g.matches.size
    }

    private fun orderIndex(sourceId: String, order: List<String>): Int {
        val i = order.indexOf(sourceId)
        return if (i < 0) order.size else i
    }
}

/** 同曲多源分组：primary 为展示主曲，matches 为各平台可用版本（含 primary）。 */
class SongGroup(
    var primary: Song,
    val matches: MutableList<Song>,
) {
    val sourceCount: Int get() = matches.size
    fun bySource(typeId: String): Song? = matches.firstOrNull { it.source.id == typeId }
}
