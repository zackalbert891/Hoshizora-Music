package com.puremusic.source.autoswitch

import com.puremusic.model.MusicSourceType
import com.puremusic.model.Song
import com.puremusic.source.SourceRegistry
import com.puremusic.source.match.SongMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * 跨源自动换源器（P0：借鉴 MintMusic 照搬 CeruMusic 的思路）。
 *
 * 触发时机：播放某歌曲在原源取流失败，或播放过程中运行时出错时，自动从其它
 * 已注册音源（网易云 / 酷我 / QQ 匿名 / 本地除外）搜索同曲候选并切换播放。
 *
 * **核心修正（用户反馈"换源放的不是同一首歌"）**：候选判定改用 [SongMatcher.isSameSong]
 * 严格校验「同名同歌手同时长」（标题相似度 ≥ 0.9 + 歌手重叠 + 时长差 ≤ 8s），
 * 宁可换源失败也绝不串到别的歌。候选取流前仍经 [verifyUrl] Range 头部字节校验，
 * 杜绝坏链 / 放错歌 / 反复打到失效地址。
 *
 * 换源结果保留原 song.id（对上层播放列表 / UI 无感），仅把 source 切到新源，
 * 真实平台 id 存入 extra["srcId"]，供各源 getSongUrl 取流时使用。
 */
@Singleton
class AutoSwitchSource @Inject constructor(
    private val sourceRegistry: SourceRegistry,
    @Named("plain") private val client: OkHttpClient,
) {

    /** 换源候选（同曲不同源 + 评分）。 */
    data class Candidate(val song: Song, val score: Double)

    /** 换源成功结果：保留原 id 的展示歌曲 + 新源可播放直链。 */
    data class SwitchResult(val song: Song, val url: String)

    /** 坏 URL 失败缓存：key=源id::真实id -> 首次记录时间戳（30 分钟过期）。 */
    private val badUrlAt = HashMap<String, Long>()

    /**
     * 跨源搜索同曲候选并尝试换源：先按评分取候选，再逐个候选取流，
     * 直链为空、无效或命中坏 URL 缓存则跳过；第一个通过 URL 有效性校验的候选即为换源结果；
     * 全部失败返回 null。
     *
     * @param original         原歌曲（保留其 id / 展示字段）
     * @param excludeSourceIds 需要排除的音源（原源 / 已尝试过的源）
     */
    suspend fun trySwitch(original: Song, excludeSourceIds: Set<String>): SwitchResult? {
        val candidates = searchCandidates(original, excludeSourceIds)
        for (candidate in candidates) {
            val realId = candidate.song.id
            val key = badKeyOf(candidate.song.source.id, realId)
            // 1) 失败缓存：近期取流失败/URL 无效的候选直接跳过
            if (isBad(key)) continue
            val url = runCatching {
                sourceRegistry.byId(candidate.song.source.id)?.getSongUrl(candidate.song)
            }.getOrNull()
            if (url.isNullOrBlank()) {
                recordBadUrl(key, url.orEmpty())
                continue
            }
            // 2) URL 有效性校验：Range 只读头部，避免放错歌 / 坏链
            if (!verifyUrl(url)) {
                recordBadUrl(key, url)
                continue
            }
            // 换源后保留原 song.id，展示字段沿用原曲（对 UI 无感），真实平台 id 存 srcId
            // originSource：记录**用户点进来时的平台**。换源会改写 song.source（变成实际出流的音源），
            // 于是"QQ 音乐里的歌"一旦换到网易云出流，评论区就会跑去网易云 —— 而用户要的是这首歌在
            // QQ 的讨论。所以原平台必须单独留一份，评论 / 归属判断都读它。
            val switched = candidate.song.copy(
                id = original.id,
                title = original.title,
                artist = original.artist,
                album = original.album,
                coverUrl = original.coverUrl,
                durationMs = original.durationMs,
                extra = candidate.song.extra + mapOf(
                    SRC_ID_KEY to candidate.song.id,
                    ORIGIN_SOURCE_KEY to (original.extra[ORIGIN_SOURCE_KEY] ?: original.source.id),
                ),
            )
            return SwitchResult(switched, url)
        }
        return null
    }

    /**
     * 在其它已注册音源中搜索同曲候选，按「严格同曲」过滤后按匹配分降序返回
     * （每个平台最多保留一首，避免同平台多版本挤占）。本地源不参与跨源换源。
     *
     * 关键修正：改用 [SongMatcher.isSameSong] 严格判定「同名同歌手同时长」，
     * 从根本上杜绝换源串歌（之前 diceBigram 阈值 0.45 太低，易混入不同歌曲）。
     */
    suspend fun searchCandidates(
        original: Song,
        excludeSourceIds: Set<String>,
        maxPerSource: Int = DEFAULT_SEARCH_PER_SOURCE,
    ): List<Candidate> {
        val keyword = "${original.title} ${original.artist}".trim()
            .ifBlank { original.title }
            .ifBlank { return emptyList() }
        val sources = sourceRegistry.all.filter {
            it.type != MusicSourceType.LOCAL &&
                it.type != original.source &&
                it.type.id !in excludeSourceIds
        }
        if (sources.isEmpty()) return emptyList()

        // 每个平台内部先筛同曲、再取最佳：
        // 原来用 distinctBy 只保留每个源的第一条，而第一条常是翻唱/DJ 版（接口排序问题），
        // 被 isSameSong 拒掉后整个源就白白失去了换源机会。
        return sources.mapNotNull { source ->
            runCatching { source.search(keyword, 0, maxPerSource) }.getOrNull().orEmpty()
                // 严格同曲过滤：宁可换源失败也不放错歌
                .filter { SongMatcher.isSameSong(original, it) }
                .maxByOrNull { SongMatcher.matchScore(original, it) }
        }
            .map { Candidate(it, SongMatcher.matchScore(original, it)) }
            // B 站排到最后：它的"歌曲"本质是视频音频（常带现场/翻唱/主持声），
            // 作为最后手段可以救急，但绝不能优先于正规音频源。
            .sortedWith(
                compareBy(
                    { it.song.source == MusicSourceType.BILIBILI },
                    { -it.score },
                )
            )
            .take(MAX_CANDIDATES)
    }

    /**
     * URL 有效性校验：Range 只读响应头部字节（至多 2KB），状态码 200/206 且
     * 文件头是常见音频格式（ID3/FLAC/OggS/RIFF/ftyp/MP3 同步字/ADTS AAC）才视为有效。
     */
    suspend fun verifyUrl(url: String): Boolean = withContext(Dispatchers.IO) {
        if (!url.startsWith("http")) return@withContext false
        runCatching {
            client.newCall(
                Request.Builder().url(url)
                    .header("Range", "bytes=0-2047")
                    .header("User-Agent", UA)
                    .build()
            ).execute().use { resp ->
                val code = resp.code
                if (code != 200 && code != 206) return@use false
                val stream = resp.body?.byteStream() ?: return@use false
                val header = ByteArray(16)
                var read = 0
                while (read < header.size) {
                    val r = stream.read(header, read, header.size - read)
                    if (r < 0) break
                    read += r
                }
                if (read == 0) return@use false
                isLikelyAudio(header, read)
            }
        }.getOrDefault(false)
    }

    private fun isLikelyAudio(header: ByteArray, length: Int): Boolean {
        // ID3（MP3 带标签）
        if (length >= 3 && header[0] == 'I'.code.toByte() && header[1] == 'D'.code.toByte() && header[2] == '3'.code.toByte()) return true
        // FLAC
        if (length >= 4 && header[0] == 'f'.code.toByte() && header[1] == 'L'.code.toByte() && header[2] == 'a'.code.toByte() && header[3] == 'C'.code.toByte()) return true
        // OggS（Vorbis/Opus）
        if (length >= 4 && header[0] == 'O'.code.toByte() && header[1] == 'g'.code.toByte() && header[2] == 'g'.code.toByte() && header[3] == 'S'.code.toByte()) return true
        // RIFF（WAV）
        if (length >= 4 && header[0] == 'R'.code.toByte() && header[1] == 'I'.code.toByte() && header[2] == 'F'.code.toByte() && header[3] == 'F'.code.toByte()) return true
        // MP4/M4A（....ftyp）
        if (length >= 8 && header[4] == 'f'.code.toByte() && header[5] == 't'.code.toByte() && header[6] == 'y'.code.toByte() && header[7] == 'p'.code.toByte()) return true
        // MP3 无 ID3 同步字（0xFF Ex）
        if (length >= 2 && header[0] == 0xFF.toByte() && (header[1].toInt() and 0xE0) == 0xE0) return true
        // ADTS AAC（0xFF F1 / 0xFF F9）
        if (length >= 2 && header[0] == 0xFF.toByte() && (header[1].toInt() and 0xF6) == 0xF0) return true
        return false
    }

    /** 记录坏 URL：命中即进入失败缓存（30 分钟 TTL），期间该「源:真实id」不再尝试取流。 */
    fun recordBadUrl(key: String, url: String = "") {
        synchronized(badUrlAt) {
            badUrlAt[key] = System.currentTimeMillis()
        }
        if (url.isNotBlank()) {
            android.util.Log.w("AutoSwitch", "记录坏URL[$key]: $url")
        }
    }

    /** 判断 key 是否处于失败缓存有效期内（过期自动清理）。 */
    fun isBad(key: String): Boolean {
        synchronized(badUrlAt) {
            val at = badUrlAt[key] ?: return false
            if (System.currentTimeMillis() - at < BAD_URL_TTL_MS) return true
            badUrlAt.remove(key)
            return false
        }
    }

    companion object {
        /** 换源后真实平台 id 的 extra 键。 */
        const val SRC_ID_KEY = "srcId"

        /** 用户点进这首歌时的原始平台 id（换源不改写它），评论 / 归属判断都读这个。 */
        const val ORIGIN_SOURCE_KEY = "originSource"

        /** 坏 URL 失败缓存有效期：30 分钟。 */
        const val BAD_URL_TTL_MS = 30 * 60 * 1000L

        const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        /** 坏 URL 缓存 key：源id::真实平台id。 */
        fun badKeyOf(sourceId: String, realId: String) = "$sourceId::$realId"

        private const val MAX_CANDIDATES = 8
        private const val DEFAULT_SEARCH_PER_SOURCE = 8
    }
}
