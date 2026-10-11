package com.puremusic.model

/** 评论来源平台（聚合展示用）。 */
enum class CommentSource(val id: String, val label: String) {
    NETEASE("netease", "网易云"),
    BILIBILI("bilibili", "B站"),
    QQ("qq", "QQ音乐"),
}

/** 被回复的评论（网易云 `beReplied`）：展示为「//@某人：内容」。 */
data class RepliedComment(
    val userName: String,
    val content: String,
)

/** 统一评论模型：把各平台字段差异屏蔽在数据源内部。 */
data class Comment(
    val id: String,
    val source: CommentSource,
    val userName: String,
    val avatarUrl: String = "",
    val content: String,
    val likeCount: Int = 0,
    val replyCount: Int = 0,
    /** 平台侧的时间展示文本（网易云为毫秒时间戳，B站为秒时间戳，统一在此处格式化）。 */
    val timeText: String = "",
    /** B站评论附带的视频标题（点击可跳转对应视频）。 */
    val sourceTitle: String = "",
    val sourceUrl: String = "",
    /** 原始时间戳（毫秒），用于排序与二次格式化。 */
    val createdMs: Long = 0L,
    /** 当前账号是否已点赞（网易云返回 liked）。 */
    val liked: Boolean = false,
    /** IP 属地，如「广东」。 */
    val ipLocation: String = "",
    /** 被回复的评论内容（可能多条）。 */
    val beReplied: List<RepliedComment> = emptyList(),
    /** 是否来自「精彩评论」（热评）。 */
    val hot: Boolean = false,
    /** 用户 VIP 等级（>0 表示会员，展示角标）。 */
    val vipType: Int = 0,
)
