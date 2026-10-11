package com.puremusic.source

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.puremusic.model.Account
import com.puremusic.model.Lyrics
import com.puremusic.model.MusicSourceType
import com.puremusic.model.Playlist
import com.puremusic.model.Song
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** 本地音乐源：读取系统媒体库（MediaStore），可离线播放。 */
@Singleton
class LocalMusicSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : MusicSource {

    override val type = MusicSourceType.LOCAL
    override val displayName = "本地音乐"
    override val supportsLogin = false

    override suspend fun search(keyword: String, page: Int, limit: Int): List<Song> =
        scanLocal().filter {
            it.title.contains(keyword, ignoreCase = true) ||
                it.artist.contains(keyword, ignoreCase = true) ||
                it.album.contains(keyword, ignoreCase = true)
        }

    suspend fun scanLocal(): List<Song> = withContext(Dispatchers.IO) {
        val songs = mutableListOf<Song>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
        )
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection, null, null,
            "${MediaStore.Audio.Media.TITLE} ASC",
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val uri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id,
                )
                songs += Song(
                    source = type,
                    id = id.toString(),
                    title = cursor.getString(titleCol) ?: "未知",
                    artist = cursor.getString(artistCol) ?: "未知歌手",
                    album = cursor.getString(albumCol) ?: "",
                    durationMs = cursor.getLong(durCol),
                    playUrl = uri.toString(),
                )
            }
        }
        songs
    }

    override suspend fun getSongUrl(song: Song): String? = song.playUrl

    override suspend fun getLyrics(song: Song): Lyrics = Lyrics()

    override suspend fun getUserPlaylists(): List<Playlist> = listOf(
        Playlist(id = "local_all", name = "本地音乐", source = type),
    )

    override suspend fun getPlaylistDetail(playlist: Playlist): List<Song> = scanLocal()

    override suspend fun login(phone: String, password: String): Account =
        throw UnsupportedOperationException("本地音乐无需登录")

    override suspend fun logout() = Unit
}
