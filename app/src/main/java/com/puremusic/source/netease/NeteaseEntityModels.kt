package com.puremusic.source.netease

import com.puremusic.model.Song

/** 歌手资料（`/api/v1/artist/<id>` 裸 GET 实测 200）。 */
data class NeteaseArtistDetail(
    val id: Long,
    val name: String,
    /** 头像（大图）。 */
    val avatarUrl: String,
    /** 别名 / 译名，如「超越」。 */
    val alias: String,
    /** 简介，可能很长（含换行）。 */
    val briefDesc: String,
    val albumCount: Int,
    val songCount: Int,
)

/** 歌手的一张专辑摘要（`/api/artist/albums/<id>` 的 hotAlbums 元素）。 */
data class NeteaseAlbumCard(
    val id: Long,
    val name: String,
    val coverUrl: String,
    val publishTime: Long,
    val size: Int,
)

/** 歌手专辑分页结果。 */
data class ArtistAlbumPage(
    val albums: List<NeteaseAlbumCard> = emptyList(),
    val more: Boolean = false,
)

/** 专辑详情 + 全量歌曲（`/api/v1/album/<id>`）。 */
data class NeteaseAlbumFull(
    val id: Long,
    val name: String,
    val coverUrl: String,
    val artistName: String,
    val artistId: Long,
    val description: String = "",
    val publishTime: Long = 0L,
    val songs: List<NeteaseSong> = emptyList(),
)