package com.puremusic.model

import com.puremusic.source.netease.EXTRA_ALBUM_ID
import com.puremusic.source.netease.EXTRA_ARTIST_ID
import com.puremusic.source.qq.EXTRA_QQ_ALBUM_ID
import com.puremusic.source.qq.EXTRA_QQ_SINGER_MID
import java.io.Serializable

/** 音乐平台来源。新增平台 = 加一个枚举值 + 一个 MusicSource 实现。 */
enum class MusicSourceType(val id: String) {
    LOCAL("local"),
    NETEASE("netease"),
    QQ("qq"),
    /** 内置免登录兜底音源。 */
    KUWO("kuwo"),
    /** B站视频音频（翻唱 / 现场 / 稀缺资源的补充源）。 */
    BILIBILI("bilibili"),
    /** 千千音乐（91q / 太合）：免登录，可给 flac 无损直链。 */
    QIANQIAN("qianqian"),
    /** 咪咕音乐：免登录 h5 通道，周杰伦等独家版权的重要补位源。 */
    MIGU("migu"),
    /** 酷狗音乐：免登录，key=MD5(hash+"kgcloudv2") 取流。 */
    KUGOU("kugou");

    companion object {
        fun fromId(id: String): MusicSourceType = entries.firstOrNull { it.id == id } ?: LOCAL
    }
}

/** 统一的歌曲模型，屏蔽各平台字段差异。 */
data class Song(
    val source: MusicSourceType,
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val coverUrl: String = "",
    val durationMs: Long = 0,
    /** 可播放直链。本地音乐为 content:// 或 file://，在线音乐在点播时再解析。 */
    val playUrl: String? = null,
    val extra: Map<String, String> = emptyMap(),
) : Serializable {

    /** 全局唯一标识：source:id，同时用作 MediaItem.mediaId。 */
    val mediaId: String get() = "${source.id}:$id"
}

/**
 * 歌手页路由 key（源无关）：网易云取 `arId`，QQ 取 `qqSingerMid`（singermid）。
 * 返回 null 表示这首歌没有该 id，调用方据此**不显示入口**，而不是跳到一个空页面。
 */
fun Song.artistEntityId(): String? = when (source) {
    MusicSourceType.NETEASE -> extra[EXTRA_ARTIST_ID]?.takeIf { it.isNotBlank() && it != "0" }
    MusicSourceType.QQ -> extra[EXTRA_QQ_SINGER_MID]?.takeIf { it.isNotBlank() }
    else -> null
}

/**
 * 专辑页路由 key（源无关）：网易云取 `alId`，QQ 取 `qqAlbumId`（数字 albumid）。
 */
fun Song.albumEntityId(): String? = when (source) {
    MusicSourceType.NETEASE -> extra[EXTRA_ALBUM_ID]?.takeIf { it.isNotBlank() && it != "0" }
    MusicSourceType.QQ -> extra[EXTRA_QQ_ALBUM_ID]?.takeIf { it.isNotBlank() }
    else -> null
}
