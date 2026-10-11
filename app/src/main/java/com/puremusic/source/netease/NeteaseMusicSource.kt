package com.puremusic.source.netease

import com.puremusic.data.AccountStore
import com.puremusic.lyrics.LrcParser
import com.puremusic.model.Account
import com.puremusic.model.Lyrics
import com.puremusic.model.MusicSourceType
import com.puremusic.model.Playlist
import com.puremusic.model.Song
import com.puremusic.source.MusicSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** 网易云音乐源：搜索 / 取流 / 歌词走官方直连（免登录），登录 / 歌单仍走自建后端服务。 */
@Singleton
class NeteaseMusicSource @Inject constructor(
    private val direct: NeteaseDirect,
    private val api: NeteaseApi,
    private val accountStore: AccountStore,
) : MusicSource {

    override val type = MusicSourceType.NETEASE
    override val displayName = "网易云音乐"
    override val supportsLogin = true

    override suspend fun search(keyword: String, page: Int, limit: Int): List<Song> {
        // 多页聚合池 + 主流过滤 + 排序（真实歌手优先、热度降序），再按分页切片。
        val ranked = direct.searchPool(keyword)
            .filterMainstream()
            .sortedWith(MAINSTREAM_COMPARATOR)
        return ranked.drop(page * limit).take(limit).map { it.toSong() }
    }

    /**
     * 取流：带上登录态 cookie，VIP 账号才能拿到付费曲目完整直链与无损音质。
     *
     * 修复前此处完全不传 cookie，导致即使已登录也只能走匿名通道拿试听片段。
     * 跨源换源后的歌曲真实平台 id 在 extra["srcId"]（原 id 已保留给上层 UI）。
     */
    override suspend fun getSongUrl(song: Song): String? {
        // 先确保登录态已从磁盘加载（冷启动竞态：否则会按匿名取流，VIP 曲目拿不到直链）
        accountStore.awaitLoaded()
        val cookie = accountStore.accounts.value[type]?.cookie?.takeIf { it.isNotBlank() }
        val id = song.extra[com.puremusic.source.autoswitch.AutoSwitchSource.SRC_ID_KEY] ?: song.id
        return direct.songUrl(id, cookie)
    }

    override suspend fun getLyrics(song: Song): Lyrics {
        val cookie = accountStore.accounts.value[type]?.cookie?.takeIf { it.isNotBlank() }
        val id = song.extra[com.puremusic.source.autoswitch.AutoSwitchSource.SRC_ID_KEY] ?: song.id
        val resp = direct.lyric(id, cookie) ?: return Lyrics()
        val raw = resp.lrc?.lyric ?: return Lyrics()
        val translation = resp.tlyric?.lyric
        return Lyrics(
            lines = LrcParser.parse(raw),
            translation = translation?.let { LrcParser.parse(it) } ?: emptyList(),
        )
    }

    /**
     * 我的云端歌单：官方直连优先，自建后端兜底。
     *
     * 直连是必须的 —— 手机上没有「电脑跑着的 NeteaseCloudMusicApi」，
     * 只走后端会永远返回 0 个歌单。
     */
    override suspend fun getUserPlaylists(): List<Playlist> {
        accountStore.awaitLoaded()
        val account = accountStore.accounts.value[type] ?: return emptyList()
        val uid = account.userId.takeIf { it.isNotBlank() }
            ?: cookieUid(account.cookie)
            ?: runCatching { direct.currentUid(account.cookie) }.getOrNull()
            ?: return emptyList()
        val summaries = runCatching { direct.userPlaylists(uid, account.cookie) }
            .getOrDefault(emptyList())
        if (summaries.isNotEmpty()) {
            return summaries.map {
                Playlist(
                    id = it.id.toString(),
                    name = it.name,
                    coverUrl = it.coverImgUrl ?: "",
                    source = type,
                    trackCount = it.trackCount,
                )
            }
        }
        // 兜底：自建后端（电脑端调试时可用）
        val resp = runCatching { api.userPlaylist(uid = uid, cookie = account.cookie) }
            .getOrNull() ?: return emptyList()
        return resp.playlist.orEmpty().map {
            Playlist(
                id = it.id.toString(),
                name = it.name,
                coverUrl = it.coverImgUrl ?: "",
                source = type,
                trackCount = it.trackCount,
            )
        }
    }

    override suspend fun getPlaylistDetail(playlist: Playlist): List<Song> {
        accountStore.awaitLoaded()
        val cookie = accountStore.accounts.value[type]?.cookie
        val songs = runCatching { direct.playlistSongs(playlist.id, cookie) }
            .getOrDefault(emptyList())
        if (songs.isNotEmpty()) return songs.map { it.toSong() }
        val resp = runCatching { api.playlistDetail(id = playlist.id, cookie = cookie) }
            .getOrNull() ?: return emptyList()
        return resp.playlist?.tracks.orEmpty().map { it.toSong() }
    }

    private fun cookieUid(cookie: String): String? =
        Regex("(?:^|;)\\s*uid=([^;]*)").find(cookie)?.groupValues?.get(1)
            ?.takeIf { it.isNotBlank() && it != "0" }

    // ═══════════════════════════════════════════════════════════════
    //  我的音乐资产（全部需登录态）
    // ═══════════════════════════════════════════════════════════════

    /** 取当前登录账号；未登录返回 null。 */
    private suspend fun currentAccount(): Account? {
        accountStore.awaitLoaded()
        val a = accountStore.accounts.value[type] ?: return null
        if (a.cookie.isBlank()) return null
        return a
    }

    /** 解析 uid：优先账号已存的，回退到 cookie，再回退到服务端反查。 */
    private suspend fun resolveUid(account: Account): String? =
        account.userId.takeIf { it.isNotBlank() }
            ?: cookieUid(account.cookie)
            ?: runCatching { direct.currentUid(account.cookie) }.getOrNull()

    /** 红心（我喜欢的音乐）。未登录返回空。 */
    suspend fun likedSongs(): List<Song> = withContext(Dispatchers.IO) {
        val account = currentAccount() ?: return@withContext emptyList()
        val uid = resolveUid(account) ?: return@withContext emptyList()
        direct.likedSongs(uid, account.cookie).map { it.toSong() }
    }

    /** 听歌排行（周榜）。未登录返回空。 */
    suspend fun playRank(): List<Song> = withContext(Dispatchers.IO) {
        val account = currentAccount() ?: return@withContext emptyList()
        val uid = resolveUid(account) ?: return@withContext emptyList()
        direct.playRank(uid, account.cookie).map { it.toSong() }
    }

    /** 网易云最近播放。未登录返回空。 */
    suspend fun recentPlays(): List<Song> = withContext(Dispatchers.IO) {
        val account = currentAccount() ?: return@withContext emptyList()
        val uid = resolveUid(account) ?: return@withContext emptyList()
        direct.recentPlays(uid, account.cookie).map { it.toSong() }
    }

    /** 每日推荐（30 首）。未登录返回空。 */
    suspend fun dailyRecommend(): List<Song> = withContext(Dispatchers.IO) {
        val account = currentAccount() ?: return@withContext emptyList()
        direct.dailyRecommend(account.cookie).map { it.toSong() }
    }

    /** 私人 FM。未登录也能返回（但无个性化）。 */
    suspend fun personalFm(): List<Song> = withContext(Dispatchers.IO) {
        val account = accountStore.accounts.value[type]
        direct.personalFm(account?.cookie).map { it.toSong() }
    }

    override suspend fun login(phone: String, password: String): Account {
        val resp = api.login(phone = phone, password = password)
        if (resp.code != 200) {
            throw IllegalStateException("登录失败：${resp.msg ?: "code=${resp.code}"}")
        }
        val account = Account(
            source = type,
            userId = (resp.profile?.userId ?: resp.account?.id)?.toString() ?: "",
            nickname = resp.profile?.nickname ?: "",
            avatarUrl = resp.profile?.avatarUrl ?: "",
            cookie = resp.cookie ?: "",
            token = resp.token ?: "",
        )
        accountStore.save(account)
        return account
    }

    override suspend fun logout() {
        accountStore.remove(type)
    }

    private fun NeteaseSong.toSong(): Song {
        val artistList = artists ?: ar
        val albumObj = album ?: al
        val durMs = if (duration > 0L) duration else dt
        return Song(
            source = MusicSourceType.NETEASE,
            id = id.toString(),
            title = name,
            artist = artistList?.joinToString(" / ") { it.name }.orEmpty().ifBlank { "未知歌手" },
            album = albumObj?.name ?: "",
            coverUrl = albumObj?.picUrl ?: "",
            durationMs = durMs,
        )
    }

    /** 过滤翻唱 / DJ / 伴奏等非主流版本，保证搜索质量为原版。 */
    private fun List<NeteaseSong>.filterMainstream(): List<NeteaseSong> {
        if (isEmpty()) return this
        return filter { song ->
            val title = song.name.lowercase()
            NON_MAINSTREAM.none { title.contains(it) }
        }
    }

    /**
     * 排序：**只把「无真实歌手」的条目沉底，其余严格保留接口返回顺序**。
     *
     * 教训（2026-10 真机实测）：此前按 pop 热度降序重排，结果搜「晴天」时
     * 网红版「晴天/大大怪将军」热度高于周杰伦原版被顶到第一 —— 这正是
     * 「放的都不是同一首歌」的根源。平台自己的搜索接口已按相关度排序，
     * 客户端再做热度重排只会破坏它；商业播放器也是直接用接口顺序。
     *
     * 注意：sortedWith 是稳定排序，比较结果相同的元素保持原顺序。
     */
    private val MAINSTREAM_COMPARATOR: Comparator<NeteaseSong> =
        compareByDescending<NeteaseSong> { it.firstArtistId() > 0L }

    private fun NeteaseSong.firstArtistId(): Long = (artists ?: ar).orEmpty().firstOrNull()?.id ?: 0L

    private companion object {
        val NON_MAINSTREAM = listOf(
            "翻唱", "伴奏", "纯音乐", "dj", "live", "现场", "cover",
            "demo", "remix", "instrumental", "钢琴版", "吉他版", "弹唱",
            "深情版", "女声版", "男声版", "合唱", "粤语版", "歌友会", "全景音效",
            "beat", "trap", "r&b版", "翻自",
        )
    }
}
