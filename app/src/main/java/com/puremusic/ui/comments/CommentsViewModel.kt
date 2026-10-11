package com.puremusic.ui.comments

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.puremusic.data.AccountStore
import com.puremusic.model.Comment
import com.puremusic.model.CommentSource
import com.puremusic.model.MusicSourceType
import com.puremusic.source.match.SongMatcher
import com.puremusic.source.netease.CommentPage
import com.puremusic.source.netease.NeteaseComment
import com.puremusic.source.netease.NeteaseMusicSource
import com.puremusic.source.netease.PostResult
import com.puremusic.source.qq.QqComment
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 评论页入参：资源 id + 来源平台 + 展示用的标题/歌手。
 *
 * 只做**歌曲评论区**：网易云对非官方客户端不开放歌单评论（真机实测带登录态也是
 * code=200 + total=0），所以代码里不再保留 `R_PL_0_` 那条分支，避免留一个永远空着的入口。
 */
data class CommentsArgs(
    val songId: String,
    val sourceId: String,
    val title: String,
    val artist: String,
) {
    companion object {
        fun from(savedStateHandle: SavedStateHandle): CommentsArgs = CommentsArgs(
            songId = savedStateHandle.get<String>("songId").orEmpty(),
            sourceId = savedStateHandle.get<String>("sourceId").orEmpty(),
            title = savedStateHandle.get<String>("title").orEmpty(),
            artist = savedStateHandle.get<String>("artist").orEmpty(),
        )
    }
}

/**
 * 正在回复的那条评论（非空 = 输入栏进入回复态，显示「回复 @某某」并可取消）。
 */
data class ReplyTarget(
    val commentId: String,
    val userName: String,
    val userId: Long,
)

/** 评论页状态：精彩评论 + 最新评论（全量串流）+ 发表评论相关。 */
data class CommentsUiState(
    val total: Int = 0,
    val hot: List<Comment> = emptyList(),
    val comments: List<Comment> = emptyList(),
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = true,
    /** 是否正在后台把剩余评论**连续拉完**（用户看到的第 N 条就是这么一条条串出来的）。 */
    val streaming: Boolean = false,
    val error: String? = null,
    /** 实际取到评论的曲目（非原生源时会映射到同名曲）。 */
    val mappedTitle: String = "",
    val mappedArtist: String = "",
    /** 评论来自哪个平台：显示在标题旁，避免"QQ 的歌怎么是网易云评论"的疑惑。 */
    val platform: String = "",
    /** 一次性提示（点赞失败 / 未登录等）：UI 弹完即清。 */
    val hint: String? = null,
    /** 这个评论区能不能**真的**点赞：网易云通道 + 已登录（QQ 通道还没接写接口）。 */
    val canLike: Boolean = false,
    /** 能不能**发表评论**：必须是网易云通道（写接口只接了网易云）+ 已登录 + 映射到了歌曲。 */
    val canPost: Boolean = false,
    /** 正在回复的那条评论；非空时输入栏变成回复态。 */
    val replyTo: ReplyTarget? = null,
    /** 正在发送（输入栏发送按钮转圈，防连点刷屏）。 */
    val sending: Boolean = false,
)

/**
 * 评论 ViewModel。
 *
 * **按来源分平台**：
 * - 歌曲本身就是 QQ 音乐 → 走 [QqComment]，看 QQ 的评论区（同一首歌在 QQ 那边的讨论）；
 * - 歌曲是网易云 → 走 [NeteaseComment]（原样不变）；
 * - 其它源（本地 / 咪咕 / 酷我…）→ 先按「歌名 + 歌手」映射到网易云原曲再取评论（[resolveNeteaseId]）；
 * - QQ 评论通道拿不到内容时，同样回落到网易云映射，保证"评论区不会是空的"。
 *
 * **全量串流**：首屏一页落地即渲染，随后后台连续翻页，每页落地就追加（UI 实时长大），
 * 直到 `more == false`。也就是「这首歌有多少条评论，这里就有多少条」。
 */
@HiltViewModel
class CommentsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val neteaseComment: NeteaseComment,
    private val neteaseSource: NeteaseMusicSource,
    private val qqComment: QqComment,
    private val accountStore: AccountStore,
) : ViewModel() {

    private val args = CommentsArgs.from(savedStateHandle)

    val title: String = args.title
    val artist: String = args.artist

    private val _state = MutableStateFlow(CommentsUiState())
    val state: StateFlow<CommentsUiState> = _state.asStateFlow()

    /**
     * 点赞请求正在路上的评论 id。
     *
     * 连点会打两条相反的请求（点赞 + 取消），服务端执行完跟用户看到的正好相反；
     * 这里在请求返回前直接忽略同一条的再次点击。
     */
    private val likePending = HashSet<String>()

    /**
     * 已收录评论 id 集合。
     *
     * 串流时每页都要"过滤掉已有的"，如果拿 `list.none { it.id == c.id }` 判定，
     * 拉到第 4000 条时每页就是 4000×50 次比较 —— 列表越长越卡，正是"评论区一滚就掉帧"的元凶。
     * 用 HashSet 判重后每页都是 O(页大小)。
     */
    private val seenIds = HashSet<String>(1024)

    private var useQqChannel = false
    private var qqSongMid: String? = null
    private var qqPageNum = 0

    private var resolvedId: String? = null
    private var offset = 0
    private var loading = false
    private var streamJob: Job? = null
    /** offset 翻到头之后改用 beforeTime 游标继续取（否则只能拿到头 ~1000 条）。 */
    private var cursorMode = false
    private var cursorTime = 0L

    private val platformLabel: String
        get() = if (useQqChannel) "QQ音乐" else "网易云"

    /** 点赞能不能落到服务端：跟着登录态实时变（登录页返回后 banner 自己消失）。 */
    private fun canLikeNow(): Boolean =
        !useQqChannel && !accountStore.currentNeteaseCookie.isNullOrBlank()

    /**
     * 发表评论能不能落到服务端：写接口只接了网易云，所以三个条件都要满足 ——
     * ① 网易云通道（QQ 评论暂不支持写）、② 已登录、③ 已映射到网易云歌曲 id。
     */
    private fun canPostNow(): Boolean =
        !useQqChannel &&
            !accountStore.currentNeteaseCookie.isNullOrBlank() &&
            !resolvedId.isNullOrBlank()

    init {
        // 登录态一变就刷新"能不能点赞/发表"：去登录页登完回来看不到那条引导了。
        viewModelScope.launch {
            accountStore.neteaseCookie.collect {
                _state.value = _state.value.copy(canLike = canLikeNow(), canPost = canPostNow())
            }
        }
        reload()
    }

    fun reload() {
        if (loading) return
        streamJob?.cancel()
        loading = true
        viewModelScope.launch {
            _state.value = CommentsUiState(loading = true, hasMore = true)
            resetCursor()

            // ① QQ 源的歌：先走 QQ 自己的评论区
            if (args.sourceId == MusicSourceType.QQ.id && args.songId.isNotBlank()) {
                val first = runCatching { qqComment.page(args.songId, 0, QQ_PAGE_SIZE) }.getOrNull()
                if (first != null && (first.comments.isNotEmpty() || first.hot.isNotEmpty())) {
                    useQqChannel = true
                    qqSongMid = args.songId
                    qqPageNum = 1
                    emitFirst(first)
                    loading = false
                    streamAll()
                    return@launch
                }
                // QQ 评论拿不到（曲库改字段 / 风控 / 无版权）→ 不摆烂，回落网易云映射
            }

            // ② 网易云源 / 其它源：映射到网易云原曲后取评论
            useQqChannel = false
            resolvedId = resolveNeteaseId()
            if (resolvedId.isNullOrBlank()) {
                _state.value = CommentsUiState(
                    loading = false,
                    hasMore = false,
                    error = "没能在网易云找到这首歌（可能无版权）",
                )
                loading = false
                return@launch
            }
            val first = fetchNeteaseFirst()
            if (first == null) {
                _state.value = CommentsUiState(
                    loading = false,
                    hasMore = false,
                    error = "评论加载失败：网络异常",
                )
                loading = false
                return@launch
            }
            offset = first.comments.size
            emitFirst(first)
            loading = false
            streamAll()
        }
    }

    /**
     * 网易云首屏评论：先按大 limit 取，失败或返回空再退回经典 20 条分页。
     *
     * 绝不把"接口没认这个 limit"当成"这首歌没有评论" —— 那会让热门歌显示成零评论。
     */
    private suspend fun fetchNeteaseFirst(): CommentPage? {
        val id = resolvedId.orEmpty()
        if (id.isBlank()) return null
        return runCatching { neteaseComment.resourcePage(id, offset = 0, limit = PAGE_SIZE) }
            .getOrNull()
            ?.takeIf { it.total > 0 || it.comments.isNotEmpty() || it.hot.isNotEmpty() }
            ?: runCatching {
                neteaseComment.resourcePage(id, offset = 0, limit = FALLBACK_PAGE_SIZE)
            }.getOrNull()
    }

    /** 首屏一页：渲染热评 + 最新评论，并把 id 灌进去重表。 */
    private fun emitFirst(page: CommentPage) {
        seenIds.clear()
        page.comments.forEach { seenIds.add(it.id) }
        _state.value = CommentsUiState(
            // QQ 评论接口的 total 恒为 0（字段废弃），网易云偶尔也会少报；
            // 用"已加载条数"兜底，否则顶部那句「共 N 条」会凭空消失。
            total = maxOf(page.total, page.comments.size),
            hot = page.hot,
            comments = page.comments,
            loading = false,
            // 首屏为空说明这首确实没人评论（error 兜底文案由 UI 显示）
            hasMore = page.more && page.comments.isNotEmpty(),
            error = if (page.comments.isEmpty() && page.hot.isEmpty()) {
                "这首歌还没有评论，来做第一个吧"
            } else null,
            mappedTitle = title,
            mappedArtist = artist,
            platform = platformLabel,
            canLike = canLikeNow(),
            canPost = canPostNow(),
        )
    }

    private fun resetCursor() {
        offset = 0
        cursorMode = false
        cursorTime = 0L
        qqPageNum = 0
        qqSongMid = null
        useQqChannel = false
        resolvedId = null
        seenIds.clear()
        // 刷新后列表重建，正在路上的点赞回调会改到旧对象上，直接判为已结束
        likePending.clear()
    }

    /**
     * 连续取完剩余评论：每页落地就追加，UI 实时长大；页与页之间留一点间隔，
     * 既不会把接口打爆（两个平台对高频请求都会风控），也让列表有"一条条刷出来"的观感。
     */
    fun streamAll() {
        if (streamJob?.isActive == true) return
        if (!_state.value.hasMore) return
        streamJob = viewModelScope.launch {
            _state.value = _state.value.copy(streaming = true)
            var guard = 0
            while (isActive && guard < MAX_PAGES) {
                guard++
                val current = _state.value
                if (!current.hasMore) break
                val ok = fetchNext()
                if (!ok) break
                delay(PAGE_GAP_MS)
            }
            _state.value = _state.value.copy(streaming = false, loadingMore = false)
        }
    }

    /** 手动/触底翻一页（已有串流在跑时直接忽略，避免重复请求同一段）。 */
    fun loadMore() {
        if (streamJob?.isActive == true) return
        val current = _state.value
        if (loading || !current.hasMore || current.comments.isEmpty()) return
        loading = true
        viewModelScope.launch {
            fetchNext()
            loading = false
        }
    }

    /** 取下一页并合并进列表；返回是否要继续（false = 到底了或失败）。 */
    private suspend fun fetchNext(): Boolean =
        if (useQqChannel) fetchNextFromQq() else fetchNextFromNetease()

    private suspend fun fetchNextFromQq(): Boolean {
        val current = _state.value
        val mid = qqSongMid ?: return false
        if (!current.hasMore) return false
        _state.value = current.copy(loadingMore = true)
        return runCatching { qqComment.page(mid, qqPageNum, QQ_PAGE_SIZE) }.fold(
            onSuccess = { page ->
                val (merged, added) = appendFresh(current.comments, page.comments)
                if (added == 0) {
                    _state.value = current.copy(loadingMore = false, hasMore = false)
                    return@fold false
                }
                qqPageNum++
                // 同 QqComment.page 的注释：接口不给 hasmore，"本页装满"也不代表没有下一屏，
                // 所以只要有内容就继续，靠"新增 0 条"收尾（见上面的 added == 0 分支）。
                val more = page.more && page.comments.isNotEmpty()
                _state.value = current.copy(
                    comments = merged,
                    total = maxOf(page.total, merged.size, current.total),
                    loadingMore = false,
                    hasMore = more,
                )
                more
            },
            onFailure = {
                _state.value = current.copy(
                    loadingMore = false,
                    hasMore = false,
                    error = "后面没有了（${it.message ?: "网络异常"}）",
                )
                false
            },
        )
    }

    private suspend fun fetchNextFromNetease(): Boolean {
        val current = _state.value
        val id = resolvedId ?: return false
        if (!current.hasMore) return false
        _state.value = current.copy(loadingMore = true)
        return runCatching {
            if (cursorMode) {
                neteaseComment.resourcePage(id, offset = 0, limit = PAGE_SIZE, beforeTime = cursorTime)
            } else {
                neteaseComment.resourcePage(id, offset = offset, limit = PAGE_SIZE)
            }
        }.fold(
            onSuccess = { page ->
                val (merged, added) = appendFresh(current.comments, page.comments)
                if (added == 0) {
                    // offset 翻到头了（服务端只给头 ~1000 条）但总数还差一大截：
                    // 换成 beforeTime 游标接着往下取，这才是能拿到全部评论的姿势。
                    if (!cursorMode && current.comments.isNotEmpty() && current.comments.size < page.total) {
                        cursorMode = true
                        cursorTime = current.comments.last().createdMs
                        return@fold true
                    }
                    _state.value = current.copy(loadingMore = false, hasMore = false)
                    return@fold false
                }
                offset = merged.size
                if (cursorMode) cursorTime = merged.last().createdMs
                _state.value = current.copy(
                    comments = merged,
                    // 游标翻页能取到的条数偶尔比接口报的 total 还多一点（total 是快照值），
                    // 取较大值，顶部"共 N 条"才不会出现"已加载 4.7k / 共 4.4k"这种自相矛盾
                    total = maxOf(page.total, merged.size, current.total),
                    loadingMore = false,
                    hasMore = true,
                )
                true
            },
            onFailure = {
                _state.value = current.copy(
                    loadingMore = false,
                    hasMore = false,
                    error = "后面没有了（${it.message ?: "网络异常"}）",
                )
                false
            },
        )
    }

    /**
     * 追加去重：只保留没见过的 id，返回新列表与新增条数。
     *
     * 只用一次遍历 + HashSet —— 老实现是"每条都在已加载列表里搜一遍"，
     * 评论越多越慢（4600 条时单页要 20 万次比较），滚动和续拉都会掉帧。
     */
    private fun appendFresh(base: List<Comment>, incoming: List<Comment>): Pair<List<Comment>, Int> {
        if (incoming.isEmpty()) return base to 0
        val out = ArrayList<Comment>(base.size + incoming.size)
        out.addAll(base)
        var added = 0
        for (c in incoming) {
            if (seenIds.add(c.id)) {
                out.add(c)
                added++
            }
        }
        return if (added == 0) base to 0 else out to added
    }

    /**
     * 点赞 / 取消点赞（**写服务端**，刷新后依然在）。
     *
     * 流程：乐观更新（先动 UI，不等网络）→ 打网易云点赞接口 → 失败则回滚 + 提示。
     * 未登录时**不改 UI**，只提示：本地假装点上，下次刷新就掉了，反而更像 bug。
     * QQ 评论通道暂未接入写接口（另一套登录态与签名），保持纯视觉反馈。
     */
    fun toggleLike(comment: Comment) {
        if (comment.id in likePending) return
        val nowLiked = !comment.liked
        if (useQqChannel) {
            applyLike(comment.id, nowLiked)
            return
        }
        val songId = resolvedId
        if (songId.isNullOrBlank()) {
            applyLike(comment.id, nowLiked)
            return
        }
        if (accountStore.currentNeteaseCookie.isNullOrBlank()) {
            _state.value = _state.value.copy(hint = "登录网易云账号后即可点赞")
            return
        }
        applyLike(comment.id, nowLiked)
        likePending.add(comment.id)
        viewModelScope.launch {
            val ok = runCatching { neteaseComment.setLike(songId, comment.id, nowLiked) }
                .getOrDefault(false)
            likePending.remove(comment.id)
            if (!ok) {
                applyLike(comment.id, !nowLiked)
                _state.value = _state.value.copy(hint = "点赞没成功：登录态可能已过期")
            }
        }
    }

    // ── 发表评论 ────────────────────────────────────────────────────────────

    /** 进入回复态：输入栏变成「回复 @某某」，再点一次同一条或取消即退出。 */
    fun toggleReply(comment: Comment) {
        val current = _state.value.replyTo
        if (current?.commentId == comment.id) {
            _state.value = _state.value.copy(replyTo = null)
            return
        }
        _state.value = _state.value.copy(
            replyTo = ReplyTarget(
                commentId = comment.id,
                userName = comment.userName,
                userId = comment.userId,
            ),
        )
    }

    /** 取消回复态。 */
    fun cancelReply() {
        if (_state.value.replyTo != null) _state.value = _state.value.copy(replyTo = null)
    }

    /**
     * 发表 / 回复评论（**真写服务端**，刷新后依然在）。
     *
     * 流程：请求 → 成功把服务端返回的 comment 插到最新评论最前 → 失败只弹提示。
     * 故意**不做乐观插入**：本地编出来的 commentId / time 是假的，
     * 一旦服务端真发成功又返回不同 id，同一条会重复出现两次，比慢半秒难看得多。
     * 服务端返回了真实 comment 对象，直接用它，commentId / userId / time 全是真的，
     * 后续回复、删除这条评论都能正常用。
     *
     * @param onSent 发送成功后回调（UI 用来清空输入框、切到"最新评论"页签）。
     */
    fun sendComment(text: String, onSent: () -> Unit = {}) {
        if (_state.value.sending) return
        val songId = resolvedId
        if (songId.isNullOrBlank()) {
            _state.value = _state.value.copy(hint = "没定位到这首歌的网易云 id，发不出去")
            return
        }
        if (useQqChannel) {
            _state.value = _state.value.copy(hint = "QQ 评论区暂不支持发表，会已切到网易云评论")
            return
        }
        if (accountStore.currentNeteaseCookie.isNullOrBlank()) {
            _state.value = _state.value.copy(hint = "登录网易云账号后才能发评论")
            return
        }
        val reply = _state.value.replyTo
        _state.value = _state.value.copy(sending = true)
        viewModelScope.launch {
            val result = runCatching {
                neteaseComment.postComment(
                    songId = songId,
                    content = text,
                    replyToCommentId = reply?.commentId.orEmpty(),
                    replyToUserId = reply?.userId ?: 0L,
                )
            }.getOrElse {
                Log.w(TAG, "发表异常", it)
                PostResult.Failure("发送失败：网络异常")
            }
            when (result) {
                is PostResult.Success -> {
                    // 服务端一般会回 comment；万一只回 id，就补一条本地展示项
                    val shown = result.comment ?: localEcho(text, result.commentId)
                    if (shown.id.isNotBlank()) {
                        seenIds.add(shown.id)
                        _state.value = _state.value.copy(
                            comments = listOf(shown) + _state.value.comments,
                            total = _state.value.total + 1,
                            sending = false,
                            replyTo = null,
                            hint = if (reply != null) "回复已发表" else "评论已发表",
                        )
                    } else {
                        _state.value = _state.value.copy(
                            sending = false,
                            replyTo = null,
                            hint = "已发表，刷新后可见",
                        )
                    }
                    onSent()
                }

                is PostResult.Failure -> {
                    _state.value = _state.value.copy(sending = false, hint = result.reason)
                }
            }
        }
    }

    /** 服务端只回 commentId 没回对象时，按本地信息拼一条能显示的（id 是真的，其余仅展示用）。 */
    private fun localEcho(text: String, commentId: String): Comment {
        val account = accountStore.accounts.value[MusicSourceType.NETEASE]
        return Comment(
            id = commentId,
            source = CommentSource.NETEASE,
            userId = account?.userId?.toLongOrNull() ?: 0L,
            userName = account?.nickname.orEmpty().ifBlank { "我" },
            avatarUrl = account?.avatarUrl.orEmpty(),
            content = text.trim(),
            createdMs = System.currentTimeMillis(),
        )
    }

    /**
     * 删除自己发的评论（真删服务端）。
     *
     * 只在"当前登录 uid == 评论 userId"时才给 UI 入口 —— 网易云对删他人评论一律 40102，
     * 让用户点了再报错毫无意义。服务端确认删掉后才从列表移除，失败保留原样并提示。
     */
    fun deleteComment(comment: Comment) {
        val songId = resolvedId ?: return
        if (comment.id in likePending) return
        likePending.add(comment.id)
        viewModelScope.launch {
            val ok = runCatching { neteaseComment.deleteComment(songId, comment.id) }
                .getOrDefault(false)
            likePending.remove(comment.id)
            if (ok) {
                _state.value = _state.value.let { s ->
                    s.copy(
                        comments = s.comments.filterNot { it.id == comment.id },
                        hot = s.hot.filterNot { it.id == comment.id },
                        total = (s.total - 1).coerceAtLeast(0),
                        hint = "评论已删除",
                    )
                }
            } else {
                _state.value = _state.value.copy(hint = "删除失败：登录态可能已过期")
            }
        }
    }

    /**
     * 当前登录 uid：用于判定哪些评论是"我自己发的"（决定给不给删除按钮）。
     *
     * 网易云删他人评论一律返回 40102，所以这个判断必须在客户端做 ——
     * 让人点了才报错，比不给入口更糟。
     */
    val myUserId: Long
        get() = accountStore.accounts.value[MusicSourceType.NETEASE]?.userId?.toLongOrNull() ?: 0L

    /** 只改一条评论的点赞态与计数（热评与最新列表里可能同时挂着同一条）。 */
    private fun applyLike(id: String, liked: Boolean) {
        fun apply(list: List<Comment>) = list.map { c ->
            if (c.id != id) c
            else c.copy(
                liked = liked,
                likeCount = (c.likeCount + if (liked) 1 else -1).coerceAtLeast(0),
            )
        }
        _state.value = _state.value.let { it.copy(hot = apply(it.hot), comments = apply(it.comments)) }
    }

    /** UI 弹完提示后调用，避免同一条提示被重复弹出。 */
    fun consumeHint() {
        if (_state.value.hint != null) _state.value = _state.value.copy(hint = null)
    }

    /**
     * 跨源映射到网易云的歌曲 id：本曲就是网易云源则直接用；
     * 否则用「歌名 + 歌手」搜索，优先取严格同曲匹配，宽松兜底取标题最贴近且歌手重叠者。
     */
    private suspend fun resolveNeteaseId(): String? {
        if (args.sourceId == MusicSourceType.NETEASE.id && args.songId.isNotBlank()) return args.songId
        val keyword = "${args.title} ${args.artist}".trim().ifBlank { args.title }
        if (keyword.isBlank()) return null
        val results = runCatching { neteaseSource.search(keyword, 0, 12) }.getOrNull().orEmpty()
            .ifEmpty {
                // 「歌名 + 歌手」搜不到是常态（歌手写法不一致 / 合唱歌带一堆人 / 接口抽风）。
                // 退回只按歌名再搜一次 —— 评论是"有总比没有好"，不值得因为匹配不上就整页空着。
                if (args.title.isNotBlank() && args.title != keyword) {
                    runCatching { neteaseSource.search(args.title, 0, 12) }.getOrNull().orEmpty()
                } else emptyList()
            }
        if (results.isEmpty()) return null
        // 1) 严格同曲（含时长缺失时不惩罚时长）
        val same = results.firstOrNull {
            SongMatcher.isSameSong(args.title, args.artist, 0L, it.title, it.artist, it.durationMs)
        }
        if (same != null) return same.id
        // 2) 宽松兜底：歌手必须重叠，且标题相似度 ≥ 0.85
        val loose = results
            .filter { SongMatcher.artistOverlap(args.artist, it.artist) }
            .maxByOrNull { SongMatcher.titleSimilarity(args.title, it.title) }
        if (loose != null && SongMatcher.titleSimilarity(args.title, loose.title) >= 0.85) {
            return loose.id
        }
        return null
    }

    private companion object {
        const val TAG = "CommentsViewModel"
        /** 网易云一页 50 条：4459 条评论从 220 次请求降到 90 次，串流明显更快。 */
        const val PAGE_SIZE = 50
        /** 大 limit 不被接受时的兜底页大小（网易云经典分页就是 20）。 */
        const val FALLBACK_PAGE_SIZE = 20
        /** QQ 评论接口的页大小上限就是 25，别贪。 */
        const val QQ_PAGE_SIZE = 25
        /** 页间隔：给接口留口气，防风控。 */
        const val PAGE_GAP_MS = 260L
        /** 串流页数硬上限（约 1 万条）：防止接口 hasmore 恒为 1 时无限拉取。 */
        const val MAX_PAGES = 400
    }
}
