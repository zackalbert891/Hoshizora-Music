package com.puremusic.di

import com.puremusic.data.SettingsStore
import com.puremusic.source.netease.NeteaseApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /** 通用 HTTP 客户端（QQ JSONP 等公开接口）。 */
    @Provides
    @Singleton
    @Named("plain")
    fun providePlainOkHttp(): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
            .build()

    /** 网易云专用客户端：把请求动态指向用户配置的自建服务器。 */
    @Provides
    @Singleton
    @Named("netease")
    fun provideNeteaseOkHttp(settingsStore: SettingsStore): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val server = runCatching { settingsStore.currentServerUrl.toHttpUrl() }.getOrNull()
                val newReq = if (server != null) {
                    req.newBuilder().url(
                        req.url.newBuilder()
                            .scheme(server.scheme)
                            .host(server.host)
                            .port(server.port)
                            .build()
                    ).build()
                } else {
                    req
                }
                chain.proceed(newReq)
            }
            .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
            .build()

    @Provides
    @Singleton
    fun provideNeteaseApi(@Named("netease") client: OkHttpClient): NeteaseApi =
        Retrofit.Builder()
            .baseUrl(SettingsStore.DEFAULT_SERVER_URL + "/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(NeteaseApi::class.java)
}
