package com.puremusic.playback

import android.content.Intent
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * 前台播放服务：持有 ExoPlayer，由 Media3 自动提升为前台服务并显示媒体通知，
 * 保证后台播放与锁屏/蓝牙控制。
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        try {
            // 各平台 CDN 校验 Referer（B站/网易云/QQ 各不相同）：按 host 动态注入，见 RefererResolvingFactory
            val dataSourceFactory = DefaultDataSource.Factory(this, RefererResolvingFactory())
            val player = ExoPlayer.Builder(this)
                .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
                .setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus = */ true)
                .build()
            player.addAnalyticsListener(object : AnalyticsListener {
                override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
                    Log.e("ExoPlayer", "详细错误 code=${error.errorCode} ${error.errorCodeName} msg=${error.message} cause=${error.cause}")
                }
            })
            mediaSession = MediaSession.Builder(this, player).build()
            setMediaNotificationProvider(DefaultMediaNotificationProvider(this))
        } catch (e: Exception) {
            Log.e("PlaybackService", "初始化失败", e)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null ||
            !player.playWhenReady ||
            player.mediaItemCount == 0 ||
            player.playbackState == Player.STATE_ENDED
        ) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            runCatching { player.release() }
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
