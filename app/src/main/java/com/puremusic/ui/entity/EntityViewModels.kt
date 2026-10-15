package com.puremusic.ui.entity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.puremusic.data.AccountStore
import com.puremusic.model.MusicSourceType
import com.puremusic.model.Song
import com.puremusic.playback.PlayerManager
import com.puremusic.source.netease.ArtistAlbumPage
import com.puremusic.source.netease.NeteaseEntity
import com.puremusic.source.netease.neteaseSongToSong
import com.puremusic.source.qq.QqAlbumPage
import com.puremusic.source.qq.QqEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/* ────────────────────────────────────────────────────────────────
 * 源无关 UI 模型：歌手页 / 专辑页只依赖这套。
 * 网易云与 QQ 各自映射进来（网易云用数字 id，QQ 用 mid / 数字 albumid，
 * 到 UI 层统一成字符串 id），所以两套屏可以完全共用。
 * ──────────────────────────────────────────────────────────────── */

data class UiArtistDetail(
    val name: String,
    val avatarUrl: String,
    /** 英文名 / 别名，可空。 */
    val alias: String,
    val albumCount: Int,
    val songCount: Int,
    val briefDesc: String,
)

data class UiAlbumCard(
    val id: String,
    val name: String,
    val coverUrl: String,
    val size: Int,
)

data class UiAlbumDetail(
    val name: String,
    val coverUrl: String,
    val artistName: String,
    /** 歌手页路由 key：网易云数字 id / QQ 的 singermid。空 = 不可跳。 */
    val artistId: String,
    val publishTime: Long,
    val description: String,
)

/** 歌手页状态。 */
data class ArtistUiState(
    val detail: UiArtistDetail? = null,
    val albums: List<UiAlbumCard> = emptyList(),
    val loading: Boolean = false,
    /** 还有下一页专辑（首屏结果里 more=true 才为 true）。 */
    val hasMore: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class ArtistViewModel @Inject constructor(
    private val neteaseEntity: NeteaseEntity,
    private val qqEntity: QqEntity,
    private val accountStore: AccountStore,
) : ViewModel() {

    private val _state = MutableStateFlow(ArtistUiState())
    val state: StateFlow<ArtistUiState> = _state.asStateFlow()

    private var sourceId: String = ""
    private var artistId: String = ""

    fun load(src: String, id: String) {
        if (id.isBlank() || (id == artistId && src == sourceId)) return
        sourceId = src
        artistId = id
        _state.value = ArtistUiState(loading = true)
        viewModelScope.launch {
            accountStore.awaitLoaded()
            val cookie = accountStore.accounts.value[MusicSourceType.NETEASE]?.cookie
            if (src == MusicSourceType.QQ.id) {
                val detail = runCatching { qqEntity.artist(id) }.getOrNull()
                val page = runCatching { qqEntity.artistAlbums(id, begin = 0, num = 30) }
                    .getOrDefault(QqAlbumPage())
                _state.value = ArtistUiState(
                    detail = detail?.let {
                        UiArtistDetail(it.name, it.coverUrl, it.alias, it.albumCount, it.songCount, it.briefDesc)
                    },
                    albums = page.albums.map { UiAlbumCard(it.id, it.name, it.coverUrl, it.size) },
                    loading = false,
                    hasMore = page.more,
                    error = if (detail == null && page.albums.isEmpty()) "歌手资料加载失败" else null,
                )
            } else {
                val detail = runCatching { neteaseEntity.artist(id) }.getOrNull()
                val page = runCatching {
                    neteaseEntity.artistAlbums(id, limit = 30, offset = 0, cookie = cookie)
                }.getOrDefault(ArtistAlbumPage())
                _state.value = ArtistUiState(
                    detail = detail?.let {
                        UiArtistDetail(it.name, it.avatarUrl, it.alias, it.albumCount, it.songCount, it.briefDesc)
                    },
                    albums = page.albums.map { UiAlbumCard(it.id.toString(), it.name, it.coverUrl, it.size) },
                    loading = false,
                    hasMore = page.more,
                    error = if (detail == null && page.albums.isEmpty()) "歌手资料加载失败" else null,
                )
            }
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
            if (sourceId == MusicSourceType.QQ.id) {
                val next = runCatching { qqEntity.artistAlbums(artistId, begin = cur.albums.size, num = 30) }
                    .getOrDefault(QqAlbumPage())
                _state.value = _state.value.copy(
                    albums = _state.value.albums +
                        next.albums.map { UiAlbumCard(it.id, it.name, it.coverUrl, it.size) },
                    loading = false,
                    hasMore = next.more && next.albums.isNotEmpty(),
                )
            } else {
                val next = runCatching {
                    neteaseEntity.artistAlbums(artistId, limit = 30, offset = cur.albums.size, cookie = cookie)
                }.getOrDefault(ArtistAlbumPage())
                _state.value = _state.value.copy(
                    albums = _state.value.albums +
                        next.albums.map { UiAlbumCard(it.id.toString(), it.name, it.coverUrl, it.size) },
                    loading = false,
                    hasMore = next.more && next.albums.isNotEmpty(),
                )
            }
        }
    }
}

/** 专辑页状态。 */
data class AlbumUiState(
    val detail: UiAlbumDetail? = null,
    val songs: List<Song> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class AlbumViewModel @Inject constructor(
    private val neteaseEntity: NeteaseEntity,
    private val qqEntity: QqEntity,
    private val accountStore: AccountStore,
    private val playerManager: PlayerManager,
) : ViewModel() {

    private val _state = MutableStateFlow(AlbumUiState())
    val state: StateFlow<AlbumUiState> = _state.asStateFlow()

    private var sourceId: String = ""
    private var albumId: String = ""

    fun load(src: String, id: String) {
        if (id.isBlank() || (id == albumId && src == sourceId)) return
        sourceId = src
        albumId = id
        _state.value = AlbumUiState(loading = true)
        viewModelScope.launch {
            accountStore.awaitLoaded()
            val cookie = accountStore.accounts.value[MusicSourceType.NETEASE]?.cookie
            if (src == MusicSourceType.QQ.id) {
                val full = runCatching { qqEntity.album(id) }.getOrNull()
                _state.value = AlbumUiState(
                    detail = full?.let {
                        UiAlbumDetail(it.name, it.coverUrl, it.artistName, it.artistMid, it.publishTime, it.description)
                    },
                    songs = full?.songs.orEmpty(),
                    loading = false,
                    error = if (full == null) "专辑加载失败" else null,
                )
            } else {
                val full = runCatching { neteaseEntity.album(id, cookie) }.getOrNull()
                _state.value = AlbumUiState(
                    detail = full?.let {
                        UiAlbumDetail(
                            name = it.name,
                            coverUrl = it.coverUrl,
                            artistName = it.artistName,
                            artistId = it.artistId.takeIf { v -> v > 0L }?.toString().orEmpty(),
                            publishTime = it.publishTime,
                            description = it.description,
                        )
                    },
                    songs = full?.songs.orEmpty().map { neteaseSongToSong(it) },
                    loading = false,
                    error = if (full == null) "专辑加载失败" else null,
                )
            }
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
