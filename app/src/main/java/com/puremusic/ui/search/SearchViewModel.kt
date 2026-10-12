package com.puremusic.ui.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.puremusic.data.SettingsStore
import com.puremusic.model.Song
import com.puremusic.playback.PlayerManager
import com.puremusic.source.SourceRegistry
import com.puremusic.source.match.SearchRelevance
import com.puremusic.source.match.SongGroup
import com.puremusic.source.match.SongMatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 聚合搜索时各平台的展示优先级（决定同曲分组的「主曲」与排序）。 */
/**
 * 主展示源优先级。
 *
 * 排序依据「实际能不能取到流」，而不是平台名气：
 * 1. netease —— 已登录，真机实测绝大多数曲目可取完整直链；
 * 2. kuwo —— 免登录兜底，接口稳定；
 * 3. qq —— 取流依赖绿钻（无会员时 result=101404 全档位拒绝），故排在有把握的源之后；
 * 4. bilibili —— 本质是视频音频，仅作最后手段。
 */
/** 展示优先级：按「实际能不能取到流 + 搜索质量」排，不按平台名气。
 *  migu/qianqian/kugou 为免登录源（2026-10 实测可用，千千还能给 flac 无损）。 */
private val SOURCE_ORDER = listOf(
    "netease", "migu", "qianqian", "kuwo", "kugou", "qq", "bilibili",
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val sourceRegistry: SourceRegistry,
    private val playerManager: PlayerManager,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    val sources = sourceRegistry.all

    /** 最近搜索（最新在前）。网易云的搜索热词接口已下线，这里只给历史。 */
    val history: StateFlow<List<String>> = settingsStore.searchHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    var keyword by mutableStateOf("")
    /** null = 聚合搜索全部已注册音源；否则为指定音源 id。 */
    var selectedSourceId by mutableStateOf<String?>(null)
    var results by mutableStateOf<List<Song>>(emptyList())
    /** 同曲多源分组（Listen1 式）：同一首歌在各平台的可用版本并到一组。 */
    var groups by mutableStateOf<List<SongGroup>>(emptyList())
    /** 弱相关结果（单源搜索时用于「其它结果」分区）。 */
    var weakResults by mutableStateOf<List<Song>>(emptyList())
    /** 弱相关结果分组（各音源「宁可多给」带来的噪声，如搜 Faded 带出 F. - 李子豪）。 */
    var weakGroups by mutableStateOf<List<SongGroup>>(emptyList())
    /** 「其它结果」分区是否展开：默认折叠，保证主列表干净。 */
    var showWeak by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    fun presetSource(id: String) {
        selectedSourceId = id.takeIf { it.isNotBlank() }
    }

    fun search() {
        val kw = keyword.trim()
        if (kw.isEmpty()) return
        viewModelScope.launch {
            // 搜索成功才记历史：失败（断网 / 全源报错）不该把词留在历史里
            settingsStore.addSearchHistory(kw)
        }
        viewModelScope.launch {
            loading = true
            error = null
            showWeak = false
            val target = selectedSourceId
            val raw: List<Song> = if (target == null) {
                // 聚合搜索：由 SourceRegistry 并发遍历全部已注册音源并合并结果；单源失败不拖垮整体
                val aggregate = sourceRegistry.searchAll(kw)
                if (aggregate.songs.isEmpty() && aggregate.failures.isNotEmpty()) {
                    error = aggregate.failures.joinToString("\n")
                }
                aggregate.songs
            } else {
                runCatching { sourceRegistry.byId(target)?.search(kw) ?: emptyList() }
                    .onFailure { error = it.message ?: "搜索失败" }
                    .getOrDefault(emptyList())
            }

            // 相关性分层：强相关进主列表，弱相关折叠进「其它结果」
            // （各音源接口宁可多给也不漏给，不过滤会混入 F. - 李子豪 这类噪声）
            val relevance = SearchRelevance.partition(kw, raw)
            // 跨源一致性修正：同名歌曲里，被更多平台收录的歌手版本更可能是原版
            // （网易云接口会把 AI/翻唱排在原唱前面，例如搜「稻香」首条是 Lie 而非周杰伦）
            val rankedStrong = SongMatcher.rankByConsensus(relevance.strong)
            val rankedWeak = SongMatcher.rankByConsensus(relevance.weak)
            results = rankedStrong
            weakResults = rankedWeak
            // 按「同一首歌」并组：让跨平台同曲并列展示，点不同源芯片即可切换平台播放
            groups = SongMatcher.groupSongs(rankedStrong, SOURCE_ORDER)
            weakGroups = SongMatcher.groupSongs(rankedWeak, SOURCE_ORDER)
            loading = false
        }
    }

    /** 取音源展示名（供分组行芯片显示）。 */
    fun sourceLabel(typeId: String): String =
        sourceRegistry.byId(typeId)?.displayName ?: typeId

    /**
     * 列表实际展示的可播放行：单源搜索直接用 [results]，聚合搜索取每组的主曲。
     * UI 用它渲染，[play] 也用它当队列上下文 —— 两边必须是同一份，否则播完会跳到别的歌。
     */
    fun playableRows(): List<Song> =
        if (results.isNotEmpty()) results else groups.map { it.primary }

    fun weakPlayableRows(): List<Song> =
        if (weakResults.isNotEmpty()) weakResults else weakGroups.map { it.primary }

    /** 带上下文：搜索结果整队入列，播完自动续下一首。 */
    fun play(song: Song) = playerManager.play(song, playableRows())

    /** 点历史词：填入输入框并立刻搜。 */
    fun searchAgain(word: String) {
        keyword = word
        search()
    }

    fun clearHistory() {
        viewModelScope.launch { settingsStore.clearSearchHistory() }
    }
}
