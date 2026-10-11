package com.puremusic.db

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.puremusic.model.MusicSourceType
import com.puremusic.model.Song

/**
 * 歌曲在数据库中的快照。
 *
 * 采用「拍平存储」而不是只存 id：在线歌曲回源解析需要联网且可能失效，
 * 把标题/歌手/封面等展示字段落库，离线也能渲染歌单与收藏列表。
 */
data class SongSnapshot(
    val sourceId: String,
    val songId: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val coverUrl: String = "",
    val durationMs: Long = 0,
    val playUrl: String? = null,
    /** Song.extra 的 JSON 序列化结果（回源解析直链/歌词用）。 */
    val extraJson: String = "",
) {
    fun toSong(): Song = Song(
        source = MusicSourceType.fromId(sourceId),
        id = songId,
        title = title,
        artist = artist,
        album = album,
        coverUrl = coverUrl,
        durationMs = durationMs,
        playUrl = playUrl,
        extra = parseExtra(extraJson),
    )

    companion object {
        private val gson = Gson()
        private val extraType = object : TypeToken<Map<String, String>>() {}.type

        private fun parseExtra(json: String): Map<String, String> =
            if (json.isBlank()) emptyMap()
            else runCatching { gson.fromJson<Map<String, String>>(json, extraType) }
                .getOrDefault(emptyMap())

        fun from(song: Song): SongSnapshot = SongSnapshot(
            sourceId = song.source.id,
            songId = song.id,
            title = song.title,
            artist = song.artist,
            album = song.album,
            coverUrl = song.coverUrl,
            durationMs = song.durationMs,
            playUrl = song.playUrl,
            extraJson = if (song.extra.isEmpty()) "" else runCatching { gson.toJson(song.extra) }.getOrDefault(""),
        )
    }
}
