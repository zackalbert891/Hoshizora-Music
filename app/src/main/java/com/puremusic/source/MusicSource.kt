package com.puremusic.source

import com.puremusic.model.Account
import com.puremusic.model.Lyrics
import com.puremusic.model.MusicSourceType
import com.puremusic.model.Playlist
import com.puremusic.model.Song

/**
 * 音乐源插件接口——"极高可自定义性"的核心扩展点。
 *
 * 接入一个新平台（酷狗、咪咕、自建 NAS 等）只需：
 *  1. 在 [MusicSourceType] 加一个枚举值；
 *  2. 实现本接口；
 *  3. 在 [SourceRegistry] 里注册。
 * 上层 UI / 播放器完全无感知。
 */
interface MusicSource {
    val type: MusicSourceType
    val displayName: String
    val supportsLogin: Boolean

    /** 搜索歌曲。 */
    suspend fun search(keyword: String, page: Int = 0, limit: Int = 30): List<Song>

    /** 解析可播放直链，解析失败返回 null。 */
    suspend fun getSongUrl(song: Song): String?

    /** 获取歌词（含翻译）。 */
    suspend fun getLyrics(song: Song): Lyrics

    /** 当前登录用户（若有）的歌单。 */
    suspend fun getUserPlaylists(): List<Playlist>

    /** 拉取歌单内全部歌曲。 */
    suspend fun getPlaylistDetail(playlist: Playlist): List<Song>

    /** 登录，失败抛异常。 */
    suspend fun login(phone: String, password: String): Account

    suspend fun logout()
}
