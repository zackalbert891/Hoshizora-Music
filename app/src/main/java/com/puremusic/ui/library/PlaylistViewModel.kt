package com.puremusic.ui.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.puremusic.data.AccountStore
import com.puremusic.db.MusicRepository
import com.puremusic.model.MusicSourceType
import com.puremusic.model.Playlist
import com.puremusic.model.Song
import com.puremusic.playback.PlayerManager
import com.puremusic.source.SourceRegistry
import com.puremusic.source.netease.NeteaseDiscover
import com.puremusic.source.netease.NeteaseMusicSource
import com.puremusic.source.netease.neteaseSongToSong
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 歌单详情页的三种本地数据源标识（复用同一条路由，靠 sourceId 区分）。 */
internal const val KIND_FAVORITES = "fav"
internal const val KIND_HISTORY = "hist"

/** 歌单详情页的元信息 + 收藏态（本地歌单不填这些）。 */
data class PlaylistDetailState(
    val name: String = "",
    val coverUrl: String = "",
    val description: String = "",
    val tags: List<String> = emptyList(),
    val creatorName: String = "",
    val creatorAvatar: String = "",
    val playCount: Long = 0L,
    val subscribedCount: Long = 0L,
    /** 当前账号是否已收藏（服务端状态，不是本地猜的）。 */
    val subscribed: Boolean = false,
    /** 收藏写操作飞行中：期间禁用按钮，防连点打出相反的两条请求。 */
    val subscribing: Boolean = false,
    /** 一次性提示（收藏失败 / 未登录）。 */
    val hint: String? = null,
)

@HiltViewModel
class PlaylistViewModel @Inject constructor(
    private val sourceRegistry: SourceRegistry,
    private val playerManager: PlayerManager,
    private val repository: MusicRepository,
    private val discover: NeteaseDiscover,
    private val netease: NeteaseMusicSource,
    private val accountStore: AccountStore,
) : ViewModel() {

    var songs by mutableStateOf<List<Song>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** 本地来源（自建歌单 / 收藏 / 历史）允许删除条目。 */
    var removable by mutableStateOf(false)
        private set

    /** 网易云歌单/榜单的元信息与收藏态；本地来源保持默认值。 */
    private val _detail = MutableStateFlow(PlaylistDetailState())
    val detail: StateFlow<PlaylistDetailState> = _detail.asStateFlow()

    private var collectJob: Job? = null
    private var kind: String = ""
    private var listId: String = ""

    fun load(sourceId: String, playlistId: String, name: String = "") {
        kind = sourceId
        listId = playlistId
        collectJob?.cancel()
        error = null
        _detail.value = PlaylistDetailState(name = name)

        when (sourceId) {
            KIND_FAVORITES -> {
                removable = true
                observe(repository.observeFavorites())
            }
            KIND_HISTORY -> {
                removable = true
                observe(repository.observeHistory(100))
            }
            MusicSourceType.LOCAL.id -> {
                removable = true
                observe(repository.observePlaylistSongs(playlistId))
            }
            MusicSourceType.NETEASE.id -> {
                removable = false
                loadNetease(playlistId)
            }
            else -> {
                removable = false
                loadOnline(sourceId, playlistId)
            }
        }
    }

    /** Room Flow 常驻收集：数据变化自动反映到列表。 */
    private fun observe(flow: Flow<List<Song>>) {
        loading = true
        collectJob = viewModelScope.launch {
            flow.catch { e ->
                error = e.message
                loading = false
            }.collect {
                songs = it
                loading = false
            }
        }
    }

    private fun loadOnline(sourceId: String, playlistId: String) {
        viewModelScope.launch {
            loading = true
            error = null
            val source = sourceRegistry.byId(sourceId)
            val playlist = Playlist(
                id = playlistId,
                name = "",
                source = MusicSourceType.fromId(sourceId),
            )
            runCatching { source?.getPlaylistDetail(playlist) ?: emptyList() }
                .onSuccess { songs = it }
                .onFailure { error = it.message ?: "加载失败" }
            loading = false
        }
    }

    /**
     * 网易云歌单 / 榜单详情。
     *
     * **一次请求拿全**：`/api/playlist/detail` 自带全量 songs（热歌榜 200 首、
     * 大歌单上千首），所以不再「先取元信息再取歌曲」两次往返。只有当返回条数
     * 少于 trackCount 时才继续翻页补齐（接口单次上限 1000）。
     *
     * **榜单与歌单同一套代码**：网易云榜单就是一张特殊歌单（id 相同），
     * 所以从「排行榜」和「歌单广场」点进来的详情页走的是同一条路径。
     */
    private fun loadNetease(playlistId: String) {
        viewModelScope.launch {
            loading = true
            error = null
            accountStore.awaitLoaded()
            val cookie = accountStore.accounts.value[MusicSourceType.NETEASE]?.cookie

            val full = runCatching { discover.playlistFull(playlistId, cookie) }.getOrNull()
            if (full == null) {
                // 详情接口失败 → 退回老路径（v6 详情），保证歌单不会整页空着
                val fallback = runCatching {
                    netease.getPlaylistDetail(
                        Playlist(playlistId, "", source = MusicSourceType.NETEASE)
                    )
                }.getOrDefault(emptyList())
                songs = fallback
                loading = false
                if (fallback.isEmpty()) error = "该歌单暂时无法加载，检查网络或登录态"
                return@launch
            }

            var all = full.songs
            if (all.size < full.trackCount) {
                var offset = all.size
                while (all.size < full.trackCount && offset < full.trackCount) {
                    val page = runCatching {
                        discover.morePlaylistSongs(playlistId, offset, cookie = cookie)
                    }.getOrDefault(emptyList())
                    if (page.isEmpty()) break
                    all = all + page
                    offset += page.size
                }
            }
            songs = all.map { neteaseSongToSong(it) }
            _detail.value = PlaylistDetailState(
                name = full.name,
                coverUrl = full.coverUrl,
                description = full.description,
                tags = full.tags,
                creatorName = full.creatorName,
                creatorAvatar = full.creatorAvatar,
                playCount = full.playCount,
                subscribedCount = full.subscribedCount,
                subscribed = full.subscribed,
            )
            loading = false
        }
    }

    /** 刷新（歌单页右上角）。 */
    fun reload() {
        if (kind == MusicSourceType.NETEASE.id) loadNetease(listId)
    }

    /**
     * 收藏 / 取消收藏网易云歌单（**写服务端**，换设备登录后依然在）。
     *
     * 乐观更新 → 打接口 → 失败回滚并明确提示。不做「本地假装成功」：
     * 那样下次刷新状态就跳回去，用户只会当成 bug。
     */
    fun toggleSubscribed() {
        val st = _detail.value
        if (st.subscribing) return
        if (accountStore.currentNeteaseCookie.isNullOrBlank()) {
            _detail.value = st.copy(hint = "登录网易云账号后才能收藏歌单")
            return
        }
        val target = !st.subscribed
        _detail.value = st.copy(subscribed = target, subscribing = true)
        viewModelScope.launch {
            val ok = runCatching { discover.setSubscribed(listId, target) }.getOrDefault(false)
            if (ok) {
                _detail.value = _detail.value.copy(
                    subscribing = false,
                    subscribedCount = (_detail.value.subscribedCount + if (target) 1 else -1)
                        .coerceAtLeast(0),
                )
            } else {
                _detail.value = _detail.value.copy(
                    subscribing = false,
                    subscribed = !target,
                    hint = if (target) "收藏失败：登录态可能已过期" else "取消收藏失败",
                )
            }
        }
    }

    /** UI 弹完提示后调用，避免同一条提示被重复弹出。 */
    fun consumeHint() {
        if (_detail.value.hint != null) _detail.value = _detail.value.copy(hint = null)
    }

    /** 从当前列表移除一首（仅本地来源有效）。 */
    fun remove(song: Song) {
        viewModelScope.launch {
            when (kind) {
                KIND_FAVORITES -> repository.removeFavorite(song.mediaId)
                KIND_HISTORY -> repository.removeHistory(song.mediaId)
                MusicSourceType.LOCAL.id -> repository.removeSongFromPlaylist(listId, song.mediaId)
            }
        }
    }

    /** 已收藏的 mediaId 集合，用于渲染收藏按钮状态。 */
    val favoriteIds: StateFlow<Set<String>> =
        repository.observeFavoriteIds()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    /** 目标歌单候选（新建/追加用）。 */
    val localPlaylists: StateFlow<List<Playlist>> =
        repository.observeLocalPlaylists()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun toggleFavorite(song: Song) {
        viewModelScope.launch { repository.toggleFavorite(song) }
    }

    fun addToPlaylist(playlistId: String, song: Song) {
        viewModelScope.launch { repository.appendSongs(playlistId, listOf(song)) }
    }

    fun createPlaylistWith(name: String, song: Song) {
        viewModelScope.launch {
            val id = repository.createPlaylist(name.trim(), listOf(song))
            addToPlaylist(id, song)
        }
    }

    /** 点歌单里的一首：整队入列并从这首开始，播完自动续下一首。 */
    fun play(song: Song) = playerManager.play(song, songs)

    fun playAll() {
        if (songs.isEmpty()) return
        playerManager.playQueue(songs, 0)
    }
}
