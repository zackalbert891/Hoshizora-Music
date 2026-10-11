package com.puremusic.source

import com.puremusic.model.MusicSourceType
import com.puremusic.model.Song
import com.puremusic.source.bilibili.BilibiliMusicSource
import com.puremusic.source.kugou.KugouMusicSource
import com.puremusic.source.kuwo.KuwoMusicSource
import com.puremusic.source.migu.MiguMusicSource
import com.puremusic.source.netease.NeteaseMusicSource
import com.puremusic.source.qianqian.QianqianMusicSource
import com.puremusic.source.qq.QqMusicSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import javax.inject.Singleton

/** 聚合搜索结果：songs 为多源合并结果，failures 记录失败源（单个源失败不拖垮整体）。 */
data class AggregateSearchResult(
    val songs: List<Song>,
    val failures: List<String>,
)

/** 音乐源注册表：已接入 QQ、网易云、酷我（免登录兜底）与本地音乐。 */
@Singleton
class SourceRegistry @Inject constructor(
    val qq: QqMusicSource,
    val netease: NeteaseMusicSource,
    val kuwo: KuwoMusicSource,
    val bilibili: BilibiliMusicSource,
    val local: LocalMusicSource,
    val qianqian: QianqianMusicSource,
    val migu: MiguMusicSource,
    val kugou: KugouMusicSource,
) {
    val all: List<MusicSource> = listOf(qq, netease, kuwo, bilibili, local, qianqian, migu, kugou)

    fun byId(id: String): MusicSource? = all.firstOrNull { it.type.id == id }

    /**
     * 遍历已注册音源并发搜索并合并结果；单源异常仅记入 failures，不影响整体。
     *
     * 注意：**聚合搜索排除 B站** —— B站结果是视频（标题带「翻唱/现场/Reaction」等噪声，
     * 且时长与歌曲不匹配），混入歌曲列表会明显拉低搜索质量；
     * B站仍保留在 [all] 中，作为「跨源换源」与「评论聚合」的补充来源。
     */
    suspend fun searchAll(keyword: String, page: Int = 0, limit: Int = 30): AggregateSearchResult =
        coroutineScope {
            val resultsOf = all.filter { it.type != MusicSourceType.BILIBILI }.map { source ->
                async { source to runCatching { source.search(keyword, page, limit) } }
            }.awaitAll()
            AggregateSearchResult(
                songs = resultsOf.flatMap { (_, r) -> r.getOrNull().orEmpty() },
                failures = resultsOf.mapNotNull { (s, r) ->
                    r.exceptionOrNull()?.let { "${s.displayName}: ${it.message}" }
                },
            )
        }
}
