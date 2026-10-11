package com.puremusic.source.netease

import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/** 网易云自建后端（NeteaseCloudMusicApi）REST 接口。 */
interface NeteaseApi {

    @POST("login/cellphone")
    suspend fun login(
        @Query("phone") phone: String,
        @Query("password") password: String,
        @Query("countrycode") countrycode: String = "86",
    ): LoginResponse

    @GET("search")
    suspend fun search(
        @Query("keywords") keywords: String,
        @Query("type") type: Int = 1,
        @Query("limit") limit: Int = 30,
        @Query("offset") offset: Int = 0,
    ): SearchResponse

    @GET("song/url")
    suspend fun songUrl(
        @Query("id") id: String,
        @Query("br") br: Int = 320000,
    ): SongUrlResponse

    @GET("lyric")
    suspend fun lyric(@Query("id") id: String): LyricResponse

    @GET("user/playlist")
    suspend fun userPlaylist(
        @Query("uid") uid: String,
        @Query("cookie") cookie: String? = null,
    ): UserPlaylistResponse

    @GET("playlist/detail")
    suspend fun playlistDetail(
        @Query("id") id: String,
        @Query("cookie") cookie: String? = null,
    ): PlaylistDetailResponse
}
