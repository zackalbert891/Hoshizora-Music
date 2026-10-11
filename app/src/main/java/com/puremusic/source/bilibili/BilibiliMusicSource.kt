package com.puremusic.source.bilibili

import com.puremusic.model.Account
import com.puremusic.model.Lyrics
import com.puremusic.model.MusicSourceType
import com.puremusic.model.Playlist
import com.puremusic.model.Song
import com.puremusic.source.MusicSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * B站音源：把视频的 DASH 音轨当作可播放音频。
 *
 * 价值：翻唱、现场、纯音乐、下架/无版权曲目在 B站往往有资源，是主流平台的补充源；
 * 同时 B站也是本应用「评论聚合」的来源之一（见 [BilibiliApi.hotComments]）。
 *
 * 限制：B站没有歌词接口（返回空 Lyrics），也不支持登录/歌单。
 */
@Singleton
class BilibiliMusicSource @Inject constructor(
    private val api: BilibiliApi,
) : MusicSource {

    override val type = MusicSourceType.BILIBILI
    override val displayName = "哔哩哔哩"
    override val supportsLogin = false

    override suspend fun search(keyword: String, page: Int, limit: Int): List<Song> =
        api.searchVideos(keyword, page = page + 1, limit = limit).map { it.toSong() }

    override suspend fun getSongUrl(song: Song): String? {
        val bvid = song.extra[com.puremusic.source.autoswitch.AutoSwitchSource.SRC_ID_KEY] ?: song.id
        if (!bvid.startsWith("BV")) return null
        val cid = api.firstCid(bvid)
        return api.audioUrl(bvid, cid)
    }

    override suspend fun getLyrics(song: Song): Lyrics = Lyrics()

    override suspend fun getUserPlaylists(): List<Playlist> = emptyList()

    override suspend fun getPlaylistDetail(playlist: Playlist): List<Song> = emptyList()

    override suspend fun login(phone: String, password: String): Account =
        throw UnsupportedOperationException("B站音源不支持登录")

    override suspend fun logout() = Unit

    private fun BiliVideo.toSong(): Song = Song(
        source = MusicSourceType.BILIBILI,
        id = bvid,
        title = title,
        artist = author.ifBlank { "B站UP主" },
        album = "哔哩哔哩",
        coverUrl = coverUrl,
        durationMs = durationSec * 1000L,
    )
}
