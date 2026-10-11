package com.puremusic.ui.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.puremusic.data.AccountStore
import com.puremusic.model.MusicSourceType
import com.puremusic.model.Song
import com.puremusic.playback.PlayerManager
import com.puremusic.source.netease.NeteaseDiscover
import com.puremusic.source.netease.NeteaseMusicSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 发现页的三个页签，对应网易云 App 的「推荐 / 排行 / 歌单」。 */
enum class DiscoverTab(val label: String) {
    RECOMMEND("推荐"),
    TOPLIST("排行榜"),
    SQUARE("歌单"),
}

/** 发现页整体状态。 */
data class DiscoverUiState(
    val tab: DiscoverTab = DiscoverTab.RECOMMEND,
    val loading: Boolean = false,
    val error: String? = null,

    // ── 推荐 ──
    /**
     * 每日推荐（32 首）。
     *
     * 「我喜欢的音乐」曾放在这里，但它是歌单 id=1，跟音乐库的网易云页重复；
     * 私人 FM 接口 2026-11 已下线，长期是个空区块。两个都撤了，这一页只留每日推荐。
     */
    val daily: List<Song> = emptyList(),

    // ── 排行榜 ──
    val toplists: List<ToplistGroup> = emptyList(),

    // ── 歌单广场 ──
    val categories: List<String> = emptyList(),
    val selectedCategory: String = "",
    val square: List<PlaylistTile> = emptyList(),
    val squareMore: Boolean = false,
    val squareLoadingMore: Boolean = false,
) {
    val anyContent: Boolean
        get() = daily.isNotEmpty() || toplists.isNotEmpty() || square.isNotEmpty()
}

/**
 * 歌单卡片（榜单与歌单广场共用一个形状，UI 才能复用同一套卡片组件）。
 *
 * [subtitle] 是已经格式化的次要信息（歌单是「30 首 · 5.4万次播放」，榜单是「每周四更新」），
 * 把格式化逻辑留在 ViewModel，UI 只管渲染。
 */
data class PlaylistTile(
    val id: String,
    val name: String,
    val subtitle: String,
    val coverUrl: String,
    /** 榜单/歌单 id，用于进详情页。 */
    val kindRef: String,
)

/** 一组榜单（官方榜 / 特色榜 / 全球榜）。 */
data class ToplistGroup(
    val name: String,
    val tiles: List<PlaylistTile>,
)

/**
 * 发现页 ViewModel。
 *
 * 数据全部走 [NeteaseDiscover] 的裸 GET 通道，**免登录也能看榜单和逛歌单**；
 * 每日推荐与私人 FM 需要登录态，未登录时不拉（拉了也是空）。
 */
@HiltViewModel
class DiscoverViewModel @Inject constructor(
    private val discover: NeteaseDiscover,
    private val netease: NeteaseMusicSource,
    private val accountStore: AccountStore,
    private val playerManager: PlayerManager,
) : ViewModel() {

    private val _state = MutableStateFlow(DiscoverUiState())
    val state: StateFlow<DiscoverUiState> = _state.asStateFlow()

    /** 已登录网易云才有「每日推荐 / 私人 FM」，未登录就不白跑请求。 */
    private val loggedIn: Boolean
        get() = accountStore.currentNeteaseCookie?.isNotBlank() == true

    init {
        refresh()
    }

    fun selectTab(tab: DiscoverTab) {
        _state.value = _state.value.copy(tab = tab)
    }

    fun selectCategory(name: String) {
        if (name == _state.value.selectedCategory) return
        _state.value = _state.value.copy(selectedCategory = name, square = emptyList())
        loadSquare(reset = true)
    }

    /** 整页刷新：榜单 / 分类 / 歌单 + （登录后的）每日推荐。 */
    fun refresh() {
        viewModelScope.launch {
            accountStore.awaitLoaded()
            _state.value = _state.value.copy(loading = true, error = null)
            val cookie = accountStore.accounts.value[MusicSourceType.NETEASE]?.cookie

            // 并发两路：榜单 / 分类。任一失败只影响自己那一块（各自 try/catch）。
            val groups: List<ToplistGroup>
            val cats: List<String>
            coroutineScope {
                val topJob = async { runCatching { discover.toplists(cookie) } }
                val catJob = async { runCatching { discover.categories() } }
                groups = topJob.await().getOrNull().orEmpty().toGroups()
                cats = catJob.await().getOrNull().orEmpty().map { it.name }
            }

            _state.value = _state.value.copy(
                loading = false,
                toplists = groups,
                categories = cats,
                error = if (groups.isEmpty() && cats.isEmpty()) "榜单与歌单都拉取失败，检查网络" else null,
            )

            // 每日推荐需要登录态，失败不影响上面的浏览功能
            loadDaily()
            if (_state.value.square.isEmpty()) loadSquare(reset = true)
        }
    }

    /** 每日推荐（30 首，需登录）。未登录直接清空，不白跑请求。 */
    private fun loadDaily() {
        if (!loggedIn) {
            _state.value = _state.value.copy(daily = emptyList())
            return
        }
        viewModelScope.launch {
            val daily = runCatching { netease.dailyRecommend() }.getOrDefault(emptyList())
            _state.value = _state.value.copy(daily = daily)
        }
    }

    /** 歌单广场：切分类时 [reset]=true，触底加载时为 false（追加）。 */
    fun loadSquare(reset: Boolean) {
        val cur = _state.value
        if (cur.squareLoadingMore) return
        val offset = if (reset) 0 else cur.square.size
        if (!reset && !cur.squareMore) return

        _state.value = cur.copy(squareLoadingMore = true, loading = reset)
        viewModelScope.launch {
            accountStore.awaitLoaded()
            val cookie = accountStore.accounts.value[MusicSourceType.NETEASE]?.cookie
            val page = runCatching {
                discover.squarePlaylists(
                    category = cur.selectedCategory,
                    limit = SQUARE_PAGE_SIZE,
                    offset = offset,
                    cookie = cookie,
                )
            }.getOrDefault(NeteaseDiscover.SquarePage())
            val merged = if (reset) page.playlists.toTiles() else cur.square + page.playlists.toTiles()
            _state.value = _state.value.copy(
                square = merged,
                squareMore = page.more,
                squareLoadingMore = false,
                loading = false,
                error = if (page.playlists.isEmpty() && cur.square.isEmpty()) {
                    "这个分类下暂时没有歌单"
                } else {
                    null
                },
            )
        }
    }

    /** 63 张榜按 官方 / 特色 / 全球 分组，每组内部保持网易云原序。 */
    private fun List<com.puremusic.source.netease.NeteaseToplist>.toGroups(): List<ToplistGroup> {
        if (isEmpty()) return emptyList()
        val order = listOf("官方榜", "特色榜", "全球榜", "其他")
        return order.mapNotNull { group ->
            val tiles = filter { it.group == group }.toToplistTiles { t ->
                listOfNotNull(
                    t.updateFrequency.takeIf { it.isNotBlank() },
                    "${t.trackCount} 首",
                ).joinToString(" · ")
            }
            if (tiles.isEmpty()) null else ToplistGroup(group, tiles)
        }
    }

    private fun List<com.puremusic.source.netease.NeteasePlaylistCard>.toTiles(
        subtitleOf: (com.puremusic.source.netease.NeteasePlaylistCard) -> String = { p ->
            listOfNotNull(
                "${p.trackCount} 首".takeIf { p.trackCount > 0 },
                formatCount(p.playCount).takeIf { p.playCount > 0 },
            ).joinToString(" · ")
        },
    ): List<PlaylistTile> = map { p ->
        PlaylistTile(
            id = p.id.toString(),
            name = p.name,
            subtitle = subtitleOf(p),
            coverUrl = p.coverUrl,
            kindRef = p.id.toString(),
        )
    }

    private fun List<com.puremusic.source.netease.NeteaseToplist>.toToplistTiles(
        subtitleOf: (com.puremusic.source.netease.NeteaseToplist) -> String,
    ): List<PlaylistTile> = map { t ->
        PlaylistTile(
            id = t.id.toString(),
            name = t.name,
            subtitle = subtitleOf(t),
            coverUrl = t.coverUrl,
            kindRef = t.id.toString(),
        )
    }

    /** 播放单曲并带上上下文队列（每日推荐 30 首必须能连续播，不能只播一首就停）。 */
    fun play(song: Song, context: List<Song>) = playerManager.play(song, context)

    private companion object {
        const val SQUARE_PAGE_SIZE = 30

        fun formatCount(n: Long): String = when {
            n <= 0 -> ""
            n < 10_000 -> "$n"
            n < 100_000_000 -> "${n / 10_000}万"
            else -> "${n / 100_000_000}亿"
        }
    }
}
