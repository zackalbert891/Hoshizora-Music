package com.puremusic.source.netease

/**
 * 网易云「发现」层的业务模型：榜单 / 歌单广场 / 歌单详情。
 *
 * 与 [NeteaseSong] 那种「一个接口一套字段名」的原始模型不同，这里是**面向 UI** 的
 * 归一化模型：NCMusic / MusicFree 那类客户端的榜单页、歌单广场、歌单详情页
 * 直接消费这些类型，UI 不需要知道网易云每个接口的容器结构。
 */

/**
 * 一个榜单（如「云音乐热歌榜」）。
 *
 * 关键点：**榜单在网易云就是一张特殊的歌单**（榜单 id 即歌单 id），
 * 所以点进榜单走的是同一个歌单详情页与同一套取歌接口，不需要第二套代码。
 */
data class NeteaseToplist(
    val id: Long,
    val name: String,
    val coverUrl: String,
    /** 「每周四更新」「更新23首」——原样展示，网易云原生文案。 */
    val updateFrequency: String = "",
    val description: String = "",
    val trackCount: Int = 0,
    val playCount: Long = 0L,
    /** 官方榜（飙升/新歌/原创/热歌）/ 特色榜（电音/说唱/古典…）/ 全球榜。 */
    val group: String = "",
)

/** 歌单广场的一张卡片。 */
data class NeteasePlaylistCard(
    val id: Long,
    val name: String,
    val coverUrl: String,
    val trackCount: Int = 0,
    val playCount: Long = 0L,
    val creatorName: String = "",
    val tags: List<String> = emptyList(),
    /** 是否为网易云精品歌单（官方打标，带 VIP 标识）。 */
    val highQuality: Boolean = false,
)

/**
 * 歌单详情（`/api/playlist/detail` 的 `result`）。
 *
 * 相对卡片多出的都是详情页要用的东西：描述、标签、创建者、收藏数、**当前账号是否已收藏**。
 * [subscribed] 是「收藏歌单」这个写操作能成立的前提 —— 取消订阅要靠它显示正确状态。
 */
data class NeteasePlaylistFull(
    val id: Long,
    val name: String,
    val coverUrl: String,
    val description: String = "",
    val tags: List<String> = emptyList(),
    val creatorName: String = "",
    val creatorAvatar: String = "",
    val trackCount: Int = 0,
    val playCount: Long = 0L,
    val subscribedCount: Long = 0L,
    val subscribed: Boolean = false,
    val createTime: Long = 0L,
    val updateTime: Long = 0L,
    val songs: List<NeteaseSong> = emptyList(),
)

/** 歌单广场 / 精品歌单的分类标签。 */
data class NeteaseCategory(
    val id: Long = 0,
    val name: String,
    val hot: Boolean = false,
)
