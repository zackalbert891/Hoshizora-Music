package com.puremusic.model

/** 已登录的平台账号信息。 */
data class Account(
    val source: MusicSourceType,
    val userId: String = "",
    val nickname: String = "",
    val avatarUrl: String = "",
    /** 网易云用 cookie（MUSIC_U 等）维持登录态。 */
    val cookie: String = "",
    val token: String = "",
    /**
     * QQ 音乐凭证续期用 refresh_key。
     * 声明为可空是为了兼容旧版本已持久化的 JSON：Gson 反射创建对象时不会应用 Kotlin
     * 默认值，缺失字段会得到 null。
     */
    val refreshKey: String? = null,
    /** QQ 音乐凭证续期用 refresh_token。 */
    val refreshToken: String? = null,
    /** QQ 音乐 musickey 过期时间（Unix 秒，0 表示未知）。 */
    val credentialExpiresAt: Long = 0,
)
