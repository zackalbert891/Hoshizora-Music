package com.puremusic.ui.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.puremusic.db.MusicRepository
import com.puremusic.model.MusicSourceType
import com.puremusic.model.Playlist
import com.puremusic.model.Song
import com.puremusic.playback.PlayerManager
import com.puremusic.source.SourceRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 歌单详情页的三种本地数据源标识（复用同一条路由，靠 sourceId 区分）。 */
internal const val KIND_FAVORITES = "fav"
internal const val KIND_HISTORY = "hist"

@HiltViewModel
class PlaylistViewModel @Inject constructor(
    private val sourceRegistry: SourceRegistry,
    private val playerManager: PlayerManager,
    private val repository: MusicRepository,
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

    private var collectJob: Job? = null
    private var kind: String = ""
    private var listId: String = ""

    fun load(sourceId: String, playlistId: String) {
        kind = sourceId
        listId = playlistId
        collectJob?.cancel()
        error = null

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
