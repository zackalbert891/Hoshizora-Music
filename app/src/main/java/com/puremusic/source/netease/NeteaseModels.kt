package com.puremusic.source.netease

/** 网易云后端服务（NeteaseCloudMusicApi）响应模型，字段名与后端返回一致。 */

data class NeteaseSong(
    val id: Long,
    val name: String,
    // 搜索接口用 artists / album / duration
    val artists: List<NeteaseArtist>? = null,
    val album: NeteaseAlbum? = null,
    val duration: Long = 0,
    // 歌单/详情接口用 ar / al / dt
    val ar: List<NeteaseArtist>? = null,
    val al: NeteaseAlbum? = null,
    val dt: Long = 0,
    /** 聚合搜索排序用热度（0-100），新版 /api/search/song/list/page 的 simpleSongData 携带。 */
    val pop: Int = 0,
)

data class NeteaseArtist(val id: Long = 0, val name: String = "")

data class NeteaseAlbum(val id: Long = 0, val name: String = "", val picUrl: String? = null)

/** 新版聚合搜索响应：data.resources[].baseInfo.simpleSongData。 */
data class SearchPageResponse(
    val code: Int,
    val data: SearchPageData? = null,
)

data class SearchPageData(
    val resources: List<SearchResource>? = null,
)

data class SearchResource(
    val resourceType: String? = null,
    val baseInfo: SearchBaseInfo? = null,
)

data class SearchBaseInfo(
    val simpleSongData: NeteaseSong? = null,
)

data class SearchResponse(
    val code: Int,
    val result: SearchResult? = null,
)

data class SearchResult(
    val songCount: Int = 0,
    val songs: List<NeteaseSong>? = null,
)

data class SongUrlResponse(
    val code: Int,
    val data: List<SongUrlItem>? = null,
)

/**
 * 取流结果条目。
 *
 * 2026-10 实测补齐：服务端对无权限曲目不再返回空 url，而是 `code` 带具体码
 * （-110 无版权/需会员、-105 需购买、-460/-462 风控），且试听片段通过
 * [freeTrialInfo] 标注。缺这些字段就无法区分「接口坏了」和「账号没权限」。
 */
data class SongUrlItem(
    val id: Long = 0,
    val url: String? = null,
    val br: Int = 0,
    val size: Long = 0,
    val type: String? = null,
    val level: String? = null,
    val code: Int = 200,
    val message: String? = null,
    val freeTrialInfo: FreeTrialInfo? = null,
)

/** 试听片段区间（毫秒）。非 null 表示这首只能听 [start]~[end] 这一段。 */
data class FreeTrialInfo(val start: Long = 0, val end: Long = 0)

data class LyricResponse(
    val code: Int,
    val lrc: LyricPart? = null,
    val tlyric: LyricPart? = null,
)

data class LyricPart(val version: Int = 0, val lyric: String? = null)

data class PlaylistDetailResponse(
    val code: Int,
    val playlist: NeteasePlaylistDetail? = null,
)

data class NeteasePlaylistDetail(
    val id: Long,
    val name: String,
    val coverImgUrl: String? = null,
    val trackCount: Int = 0,
    val tracks: List<NeteaseSong>? = null,
)

data class UserPlaylistResponse(
    val code: Int,
    val playlist: List<NeteasePlaylistSummary>? = null,
)

data class NeteasePlaylistSummary(
    val id: Long,
    val name: String,
    val coverImgUrl: String? = null,
    val trackCount: Int = 0,
)

data class LoginResponse(
    val code: Int,
    val token: String? = null,
    val cookie: String? = null,
    val account: LoginAccount? = null,
    val profile: LoginProfile? = null,
    val msg: String? = null,
)

data class LoginAccount(val id: Long)

data class LoginProfile(val userId: Long, val nickname: String, val avatarUrl: String)
