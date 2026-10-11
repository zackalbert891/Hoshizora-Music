package com.puremusic.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/** 本地自建歌单。 */
@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val coverUrl: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/** 歌单内的歌曲（含快照）。主键 (playlistId, mediaId) 天然去重。 */
@Entity(
    tableName = "playlist_songs",
    primaryKeys = ["playlistId", "mediaId"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("playlistId")],
)
data class PlaylistSongEntity(
    val playlistId: String,
    val mediaId: String,
    val sortOrder: Long,
    @Embedded val snapshot: SongSnapshot,
)

/** 我喜欢的（收藏）。 */
@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val mediaId: String,
    val addedAt: Long = System.currentTimeMillis(),
    @Embedded val snapshot: SongSnapshot,
)

/** 播放历史，同一首重复播放只更新时间与次数。 */
@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey val mediaId: String,
    val playedAt: Long = System.currentTimeMillis(),
    val playCount: Int = 1,
    @Embedded val snapshot: SongSnapshot,
)

/** 歌单 + 其下歌曲（Room Relation 一次性取出）。 */
data class PlaylistWithSongs(
    @Embedded val playlist: PlaylistEntity,
    @Relation(parentColumn = "id", entityColumn = "playlistId")
    val songs: List<PlaylistSongEntity>,
)
