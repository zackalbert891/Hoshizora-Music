package com.puremusic.ui.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.puremusic.data.AccountStore
import com.puremusic.model.MusicSourceType
import com.puremusic.model.Song
import com.puremusic.playback.PlayerManager
import com.puremusic.source.netease.NeteaseDiscover
import com.puremusic.source.netease.NeteaseMusicSource
import com.puremusic.source.qq.QqCategory
import com.puremusic.source.qq.QqDiscover
import com.puremusic.source.qq.QqPlaylistCard
import com.puremusic.source.qq.QqSquarePage
import com.puremusic.source.qq.QqToplist
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
    /** 当前浏览的音源：网易云 or QQ 音乐（两套发现层数据互不干涉）。 */
    val source: MusicSourceType = MusicSourceType.NETEASE,
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

    /**
     * 当前源是否已登录（决定推荐空态文案）：
     * 未登录 → 提示去登录；已登录却空 → 提示接口空/重试，不能反过来误导用户。
     */
    val loggedIn: Boolean = false,

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
    /** 榜单/歌单 id，用于进详情页。QQ 榜单前缀 `top:` 以区分 dissid。 */
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
 * **双源复用**：网易云走 [NeteaseDiscover]，QQ 音乐走 [QqDiscover]。
 * 两个发现层的数据形状不同（网易云榜单带分组、QQ 榜单是内置常量），但都被规整成
 * 同一套 UI 状态（[ToplistGroup] / [PlaylistTile]），UI 无需关心来源。
 *
 * 网易云的「每日推荐 / 私人 FM」需要登录态，未登录时不拉（拉了也是空）；
 * QQ 的每日推荐同理，未登录（或接口偶发空）时返回空，UI 给占位提示。
 */
@HiltViewModel
class DiscoverViewModel @Inject constructor(
    private val neteaseDiscover: NeteaseDiscover,
    private val netease: NeteaseMusicSource,
    private val qqDiscover: QqDiscover,
    private val accountStore: AccountStore,
    private val playerManager: PlayerManager,
) : ViewModel() {

    private val _state = MutableStateFlow(DiscoverUiState())
    val state: StateFlow<DiscoverUiState> = _state.asStateFlow()

    /** 当前音源（从状态读，避免到处传参）。 */
    private val source: MusicSourceType get() = _state.value.source

    /**
     * 当前源是否已登录（用于推荐空态文案，不白拉请求）。
     *
     * 网易云看 cookie 是否非空（dailyRecommend 传的就是它）；QQ 看账号是否存在
     * （QqDiscover.dailyRecommend 从 accounts[QQ] 取 uin + cookie）。
     */
    private fun loggedIn(src: MusicSourceType): Boolean = when (src) {
        MusicSourceType.NETEASE -> accountStore.currentNeteaseCookie?.isNotBlank() == true
        else -> accountStore.accounts.value[src] != null
    }

    init {
        refresh()
    }

    /** 切换音源：清空当前浏览结果再整页刷新（两个发现层完全独立）。 */
    fun selectSource(src: MusicSourceType) {
        if (src == _state.value.source) return
        _state.value = DiscoverUiState(source = src)
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
            if (source == MusicSourceType.NETEASE) loadNeteaseBrowse() else loadQqBrowse()
            loadDaily()
            if (_state.value.square.isEmpty()) loadSquare(reset = true)
        }
    }

    /** 网易云浏览：榜单（分组）+ 分类。两路并发，各自 try/catch 互不影响。 */
    private suspend fun loadNeteaseBrowse() {
        val cookie = accountStore.accounts.value[MusicSourceType.NETEASE]?.cookie
        val groups: List<ToplistGroup>
        val cats: List<String>
        coroutineScope {
            val topJob = async { runCatching { neteaseDiscover.toplists(cookie) } }
            val catJob = async { runCatching { neteaseDiscover.categories() } }
            groups = topJob.await().getOrNull().orEmpty().toGroups()
            // distinct：分类名直接当 LazyRow 的 key，重复会撞 key 崩页
            cats = catJob.await().getOrNull().orEmpty().map { it.name }.distinct()
        }
        _state.value = _state.value.copy(
            loading = false,
            toplists = groups,
            categories = cats,
            error = if (groups.isEmpty() && cats.isEmpty()) "榜单与歌单都拉取失败，检查网络" else null,
        )
    }

    /** QQ 浏览：内置榜单 + 内置分类（Web 端点族在真机稳定可用，但榜单列表/分类列表接口返回空）。 */
    private suspend fun loadQqBrowse() {
        val tiles = qqDiscover.toplists().map { t ->
            PlaylistTile(
                id = "top:${t.id}",
                name = t.name,
                subtitle = t.subtitle,
                coverUrl = t.coverUrl,
                kindRef = "top:${t.id}",
            )
        }
        _state.value = _state.value.copy(
            loading = false,
            toplists = listOf(ToplistGroup("QQ音乐榜", tiles)),
            categories = qqDiscover.categories().map { it.name }.distinct(),
            error = null,
        )
    }

    /** 每日推荐（需登录）。未登录直接清空，不白跑请求。 */
    private fun loadDaily() {
        viewModelScope.launch {
            accountStore.awaitLoaded()
            val loggedIn = loggedIn(source)
            val daily = when (source) {
                MusicSourceType.NETEASE -> if (loggedIn) {
                    runCatching { netease.dailyRecommend() }.getOrDefault(emptyList())
                } else {
                    emptyList()
                }
                else -> runCatching { qqDiscover.dailyRecommend() }.getOrDefault(emptyList())
            }
            _state.value = _state.value.copy(daily = daily, loggedIn = loggedIn)
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
            val result = if (source == MusicSourceType.NETEASE) {
                val cookie = accountStore.accounts.value[MusicSourceType.NETEASE]?.cookie
                runCatching {
                    neteaseDiscover.squarePlaylists(
                        category = cur.selectedCategory,
                        limit = SQUARE_PAGE_SIZE,
                        offset = offset,
                        cookie = cookie,
                    )
                }.getOrDefault(NeteaseDiscover.SquarePage()).let { it.playlists.toNeteaseTiles() to it.more }
            } else {
                val categoryId = if (cur.selectedCategory.isBlank()) "10000000"
                else qqDiscover.categories().firstOrNull { it.name == cur.selectedCategory }?.id
                    ?: "10000000"
                runCatching {
                    qqDiscover.squarePlaylists(
                        categoryId = categoryId,
                        page = offset / SQUARE_PAGE_SIZE,
                        pageSize = SQUARE_PAGE_SIZE,
                    )
                }.getOrDefault(QqSquarePage()).let { it.playlists.toQqTiles() to it.more }
            }
            // ⚠️ 必须按 kindRef 去重再合并：QQ 广场接口翻页在边界页会返回与上一页
            // 重复的 dissid（真机实测 `sq-7707261125` 出现两次），LazyGrid 的
            // key 撞重直接抛 IllegalArgumentException 崩页（2026-10-15 真机复现）。
            val merged = (if (reset) result.first else cur.square + result.first)
                .distinctBy { it.kindRef }
            _state.value = _state.value.copy(
                square = merged,
                squareMore = result.second,
                squareLoadingMore = false,
                loading = false,
                error = if (result.first.isEmpty() && cur.square.isEmpty()) {
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
            val tiles = filter { it.group == group }.toNeteaseToplistTiles { t ->
                listOfNotNull(
                    t.updateFrequency.takeIf { it.isNotBlank() },
                    "${t.trackCount} 首",
                ).joinToString(" · ")
            }
            if (tiles.isEmpty()) null else ToplistGroup(group, tiles)
        }
    }

    private fun List<com.puremusic.source.netease.NeteasePlaylistCard>.toNeteaseTiles(
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

    private fun List<com.puremusic.source.netease.NeteaseToplist>.toNeteaseToplistTiles(
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

    /** QQ 歌单广场卡片 → UI 通用卡片（dissid 直接作 kindRef，进详情走 musicu srfDissInfo）。 */
    private fun List<QqPlaylistCard>.toQqTiles(): List<PlaylistTile> = map { c ->
        PlaylistTile(
            id = c.id.toString(),
            name = c.name,
            subtitle = listOfNotNull(
                "${c.trackCount} 首".takeIf { c.trackCount > 0 },
                formatCount(c.playCount).takeIf { c.playCount > 0 },
            ).joinToString(" · "),
            coverUrl = c.coverUrl,
            kindRef = c.id.toString(),
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
