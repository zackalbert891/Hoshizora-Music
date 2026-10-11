package com.puremusic.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.puremusic.data.AccountStore
import com.puremusic.db.MusicRepository
import com.puremusic.model.Playlist
import com.puremusic.model.Song
import com.puremusic.playback.PlayerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 音乐库首页：本地数据（自建歌单 / 收藏 / 历史）+ 各平台登录态。
 *
 * 云端内容（网易云歌单、排行榜、歌单广场）不在这里拉，
 * 改由 [com.puremusic.ui.discover.DiscoverScreen] 与各平台页按需加载 ——
 * 首页同时拉五组云端数据会让冷启动变慢，而且其中多数接口未登录时本就返回空。
 */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    accountStore: AccountStore,
    private val repository: MusicRepository,
    private val playerManager: PlayerManager,
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
