package com.puremusic.db

import com.puremusic.model.Playlist
import com.puremusic.model.Song
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 本地音乐库仓库：歌单 / 收藏 / 历史的唯一入口。
 * UI 只依赖本类，不直接碰 DAO。
 */
@Singleton
class MusicRepository @Inject constructor(
    private val playlistDao: PlaylistDao,
    private val favoriteDao: FavoriteDao,
    private val historyDao: HistoryDao,
) {

    // ---------- 歌单 ----------

    /** 本地自建歌单（含歌曲数与封面）。 */
    fun observeLocalPlaylists(): Flow<List<Playlist>> =
        playlistDao.observeAllWithSongs().map { list ->
            list.map { it.toPlaylist() }
        }

    fun observePlaylistSongs(playlistId: String): Flow<List<Song>> =
        playlistDao.observeSongs(playlistId).map { rows -> rows.map { it.snapshot.toSong() } }

    suspend fun createPlaylist(name: String, songs: List<Song> = emptyList()): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        playlistDao.insertPlaylist(
            PlaylistEntity(id = id, name = name, coverUrl = songs.firstOrNull()?.coverUrl.orEmpty(), createdAt = now, updatedAt = now)
        )
        if (songs.isNotEmpty()) appendSongs(id, songs)
        return id
    }

    /** 追加歌曲到歌单尾部；已存在的（同 mediaId）先移除再追加，等于「移到末尾」。 */
    suspend fun appendSongs(playlistId: String, songs: List<Song>) {
        if (songs.isEmpty()) return
        var order = playlistDao.maxSortOrder(playlistId)
        val rows = songs.distinctBy { it.mediaId }.map { song ->
            order++
            PlaylistSongEntity(
                playlistId = playlistId,
                mediaId = song.mediaId,
                sortOrder = order,
                snapshot = SongSnapshot.from(song),
            )
        }
        playlistDao.insertSongs(rows)
        playlistDao.touch(playlistId)
    }

    suspend fun removeSongFromPlaylist(playlistId: String, mediaId: String) =
        playlistDao.removeSong(playlistId, mediaId)

    suspend fun renamePlaylist(playlistId: String, name: String) =
        playlistDao.rename(playlistId, name)

    suspend fun deletePlaylist(playlistId: String) {
        playlistDao.clearSongs(playlistId)
        playlistDao.deletePlaylist(playlistId)
    }

    // ---------- 收藏 ----------

    fun observeFavorites(): Flow<List<Song>> =
        favoriteDao.observeAll().map { rows -> rows.map { it.snapshot.toSong() } }

    fun observeFavoriteIds(): Flow<Set<String>> =
        favoriteDao.observeIds().map { it.toSet() }

    suspend fun isFavorite(mediaId: String): Boolean = favoriteDao.exists(mediaId)

    /** 切换收藏状态，返回切换后的状态（true = 已收藏）。 */
    suspend fun toggleFavorite(song: Song): Boolean {
        return if (favoriteDao.exists(song.mediaId)) {
            favoriteDao.delete(song.mediaId)
            false
        } else {
            favoriteDao.insert(
                FavoriteEntity(mediaId = song.mediaId, snapshot = SongSnapshot.from(song))
            )
            true
        }
    }

    suspend fun removeFavorite(mediaId: String) = favoriteDao.delete(mediaId)

    // ---------- 历史 ----------

    fun observeHistory(limit: Int = 100): Flow<List<Song>> =
        historyDao.observeRecent(limit).map { rows -> rows.map { it.snapshot.toSong() } }

    /** 记录一次播放：已存在则累加次数并刷新时间。 */
    suspend fun recordPlay(song: Song) {
        val prev = historyDao.get(song.mediaId)
        historyDao.insert(
            HistoryEntity(
                mediaId = song.mediaId,
                playedAt = System.currentTimeMillis(),
                playCount = (prev?.playCount ?: 0) + 1,
                snapshot = SongSnapshot.from(song),
            )
        )
    }

    suspend fun removeHistory(mediaId: String) = historyDao.delete(mediaId)

    suspend fun clearHistory() = historyDao.clear()
}

private fun PlaylistWithSongs.toPlaylist(): Playlist = Playlist(
    id = playlist.id,
    name = playlist.name,
    coverUrl = playlist.coverUrl.ifBlank { songs.firstOrNull()?.snapshot?.coverUrl.orEmpty() },
    source = com.puremusic.model.MusicSourceType.LOCAL,
    trackCount = songs.size,
    songs = songs.map { it.snapshot.toSong() },
)
