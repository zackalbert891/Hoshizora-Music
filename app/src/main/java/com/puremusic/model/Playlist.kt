package com.puremusic.model

/** 歌单：既可以是平台的，也可以是本地自建。 */
data class Playlist(
    val id: String,
    val name: String,
    val coverUrl: String = "",
    val source: MusicSourceType = MusicSourceType.LOCAL,
    val trackCount: Int = 0,
    val songs: List<Song> = emptyList(),
)
