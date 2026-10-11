package com.puremusic.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.puremusic.data.AccountStore
import com.puremusic.db.MusicRepository
import com.puremusic.model.MusicSourceType
import com.puremusic.model.Playlist
import com.puremusic.model.Song
import com.puremusic.playback.PlayerManager
import com.puremusic.source.netease.NeteaseMusicSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 网易云「我的音乐资产」区块的一次性拉取结果。
 *
 * [anyLoggedIn] 为 false 时整个区块不渲染（未登录就没数据，拉了也是白拉）。
 * 各列表独立降级：某一项失败只让它自己空着，不影响其他项显示。
 */
data class NeteaseAssets(
    val liked: List<Song> = emptyList(),
    val rank: List<Song> = emptyList(),
    val recent: List<Song> = emptyList(),
    val daily: List<Song> = emptyList(),
    val fm: List<Song> = emptyList(),
    val loading: Boolean = false,
) {
    val anyLoggedIn: Boolean get() = liked.isNotEmpty() || rank.isNotEmpty() ||
        recent.isNotEmpty() || daily.isNotEmpty() || fm.isNotEmpty()

    val isEmpty: Boolean get() = !loading && !anyLoggedIn
}

/** 音乐库首页：本地数据 + 各平台登录态（各平台自己的歌单在平台页按需加载）。 */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    accountStore: AccountStore,
    private val repository: MusicRepository,
    private val playerManager: PlayerManager,
    private val netease: NeteaseMusicSource,
) : ViewModel() {

    val accounts = accountStore.accounts

    /** 本地自建歌单（Room 驱动，自动刷新）。 */
    val localPlaylists: StateFlow<List<Playlist>> =
        repository.observeLocalPlaylists()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 我喜欢的。 */
    val favorites: StateFlow<List<Song>> =
        repository.observeFavorites()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 播放历史（最近 100 条）。 */
    val history: StateFlow<List<Song>> =
        repository.observeHistory(100)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ── 网易云「我的音乐资产」──────────────────────────────────────
    private val _neteaseAssets = MutableStateFlow(NeteaseAssets())
    val neteaseAssets: StateFlow<NeteaseAssets> = _neteaseAssets.asStateFlow()

    init {
        // 只有已登录网易云才拉，否则白跑五次网络请求。
        viewModelScope.launch {
            accountStore.awaitLoaded()
            if (accountStore.accounts.value[MusicSourceType.NETEASE] != null) {
                refreshNeteaseAssets()
            }
        }
    }

    /**
     * 拉取网易云资产。五个接口并发跑、各自 try/catch —— 任何一个挂掉都不影响其他。
     *
     * 私人 FM 每次返回都不一样，所以「刷新」时连它一起重新拉。
     */
    fun refreshNeteaseAssets() {
        _neteaseAssets.value = _neteaseAssets.value.copy(loading = true)
        viewModelScope.launch {
            val (liked, rank, recent, daily, fm) = coroutineScope {
                listOf(
                    async { runCatching { netease.likedSongs() }.getOrDefault(emptyList()) },
                    async { runCatching { netease.playRank() }.getOrDefault(emptyList()) },
                    async { runCatching { netease.recentPlays() }.getOrDefault(emptyList()) },
                    async { runCatching { netease.dailyRecommend() }.getOrDefault(emptyList()) },
                    async { runCatching { netease.personalFm() }.getOrDefault(emptyList()) },
                ).awaitAll()
            } as List<List<Song>>
            _neteaseAssets.value = NeteaseAssets(
                liked = liked,
                rank = rank,
                recent = recent,
                daily = daily,
                fm = fm,
                loading = false,
            )
        }
    }

    fun createPlaylist(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { repository.createPlaylist(trimmed) }
    }

    fun deletePlaylist(playlistId: String) {
        viewModelScope.launch { repository.deletePlaylist(playlistId) }
    }

    /** 播放单曲（「最近播放」区块直接续播）；带上下文列表，播完自动往下走。 */
    fun play(song: Song, context: List<Song> = emptyList()) = playerManager.play(song, context)
}
