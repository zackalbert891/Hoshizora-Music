package com.puremusic.ui.entity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.puremusic.data.AccountStore
import com.puremusic.model.MusicSourceType
import com.puremusic.model.Song
import com.puremusic.playback.PlayerManager
import com.puremusic.source.netease.ArtistAlbumPage
import com.puremusic.source.netease.NeteaseAlbumCard
import com.puremusic.source.netease.NeteaseAlbumFull
import com.puremusic.source.netease.NeteaseArtistDetail
import com.puremusic.source.netease.NeteaseEntity
import com.puremusic.source.netease.neteaseSongToSong
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 歌手页状态。 */
data class ArtistUiState(
    val detail: NeteaseArtistDetail? = null,
    val albums: List<NeteaseAlbumCard> = emptyList(),
    val loading: Boolean = false,
    /** 还有下一页专辑（首屏结果里 more=true 才为 true）。 */
    val hasMore: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class ArtistViewModel @Inject constructor(
    private val entity: NeteaseEntity,
    private val accountStore: AccountStore,
) : ViewModel() {

    private val _state = MutableStateFlow(ArtistUiState())
    val state: StateFlow<ArtistUiState> = _state.asStateFlow()

    private var artistId: String = ""

    fun load(id: String) {
        if (id.isBlank() || id == artistId) return
        artistId = id
        _state.value = ArtistUiState(loading = true)
        viewModelScope.launch {
            accountStore.awaitLoaded()
            val cookie = accountStore.accounts.value[MusicSourceType.NETEASE]?.cookie
            val detail = runCatching { entity.artist(id) }.getOrNull()
            val page = runCatching { entity.artistAlbums(id, limit = 30, offset = 0, cookie = cookie) }
                .getOrDefault(ArtistAlbumPage())
            _state.value = ArtistUiState(
                detail = detail,
                albums = page.albums,
                loading = false,
                hasMore = page.more,
                error = if (detail == null && page.albums.isEmpty()) "歌手资料加载失败" else null,
            )
        }
    }

    /** 列表滚到底时翻下一页专辑。 */
    fun loadMoreAlbums() {
        val cur = _state.value
        if (!cur.hasMore || cur.loading) return
        _state.value = cur.copy(loading = true)
        viewModelScope.launch {
            accountStore.awaitLoaded()
            val cookie = accountStore.accounts.value[MusicSourceType.NETEASE]?.cookie
            val next = runCatching {
                entity.artistAlbums(artistId, limit = 30, offset = cur.albums.size, cookie = cookie)
            }.getOrDefault(ArtistAlbumPage())
            _state.value = _state.value.copy(
                albums = _state.value.albums + next.albums,
                loading = false,
                hasMore = next.more && next.albums.isNotEmpty(),
            )
        }
    }
}

/** 专辑页状态。 */
data class AlbumUiState(
    val detail: NeteaseAlbumFull? = null,
    val songs: List<Song> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class AlbumViewModel @Inject constructor(
    private val entity: NeteaseEntity,
    private val accountStore: AccountStore,
    private val playerManager: PlayerManager,
) : ViewModel() {

    private val _state = MutableStateFlow(AlbumUiState())
    val state: StateFlow<AlbumUiState> = _state.asStateFlow()

    private var albumId: String = ""

    fun load(id: String) {
        if (id.isBlank() || id == albumId) return
        albumId = id
        _state.value = AlbumUiState(loading = true)
        viewModelScope.launch {
            accountStore.awaitLoaded()
            val cookie = accountStore.accounts.value[MusicSourceType.NETEASE]?.cookie
            val full = runCatching { entity.album(id, cookie) }.getOrNull()
            _state.value = AlbumUiState(
                detail = full,
                songs = full?.songs.orEmpty().map { neteaseSongToSong(it) },
                loading = false,
                error = if (full == null) "专辑加载失败" else null,
            )
        }
    }

    /** 播放全部（传完整队列，点歌换源与自动续播都靠它）。 */
    fun playAll() {
        val list = _state.value.songs
        if (list.isEmpty()) return
        playerManager.play(list.first(), list)
    }

    fun play(song: Song) {
        val list = _state.value.songs
        playerManager.play(song, list)
    }
}