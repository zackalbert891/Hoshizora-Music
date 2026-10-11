package com.puremusic.ui.comments

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.puremusic.model.Comment
import com.puremusic.ui.theme.PressFeedback
import com.puremusic.ui.theme.pressable

/**
 * 评论页：**完整**的网易云评论区体验。
 *
 * 结构：歌曲信息头（共 N 条）→「精彩评论」→「最新评论」→ 后台**全量串流**（一页页连续取完），
 * 卡片包含头像、昵称、正文、被回复内容、时间、IP 属地、回复数、可点的点赞，
 * 以及**发表 / 回复 / 删除自己评论**（底部固定发表栏 + 表情面板）。
 * 非网易云源的歌会先按同名同歌手映射到网易云原曲，再取该曲评论。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentsScreen(
    onBack: () -> Unit,
    onLogin: () -> Unit = {},
    viewModel: CommentsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val myUid = viewModel.myUserId

    // 点赞 / 发表评论结果提示：弹一次即清，避免每次重组都弹
    LaunchedEffect(state.hint) {
        val hint = state.hint ?: return@LaunchedEffect
        android.widget.Toast.makeText(context, hint, android.widget.Toast.LENGTH_SHORT).show()
        viewModel.consumeHint()
    }
    // 0 = 精彩评论（热评），1 = 最新评论（可翻页）。网易云 App 也是这两个页签，
    // 否则 30 条热评要滚很久才看得到最新评论。
    var tab by remember { mutableIntStateOf(0) }

    LaunchedEffect(tab) {
        if (listState.firstVisibleItemIndex > 0) listState.scrollToItem(0)
    }

    // 触底自动翻页：最后 4 项可见时就预取下一页，滚动不会停在半空
    val shouldLoadMore by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = listState.layoutInfo.totalItemsCount
            total > 0 && last >= total - 4
        }
    }
    LaunchedEffect(shouldLoadMore, state.hasMore, tab) {
        if (tab == 1 && shouldLoadMore && state.hasMore && !state.loading) viewModel.loadMore()
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "评论",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        )
                        Text(
                            buildString {
                                append(viewModel.title)
                                if (viewModel.artist.isNotBlank()) {
                                    append(" · ").append(viewModel.artist)
                                }
                                if (state.platform.isNotBlank()) append(" · ").append(state.platform)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    Row(
                        Modifier
                            .padding(start = 6.dp)
                            .size(40.dp)
                            .pressable(scaleDown = 0.9f, feedback = PressFeedback.CLICK, onClick = onBack),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    Row(
                        Modifier
                            .padding(end = 6.dp)
                            .size(40.dp)
                            .pressable(
                                scaleDown = 0.9f,
                                feedback = PressFeedback.CLICK,
                                onClick = viewModel::reload,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                modifier = Modifier.statusBarsPadding(),
            )
        },
        bottomBar = {
            CommentComposer(
                canPost = state.canPost,
                sending = state.sending,
                replyTo = state.replyTo,
                onReplyCancel = viewModel::cancelReply,
                onSend = { text ->
                    viewModel.sendComment(text) {
                        // 发表成功后切到「最新评论」，否则新评论发出去用户看不见（还在热评页）
                        tab = 1
                    }
                },
                onLogin = onLogin,
            )
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                // 真的一条评论都没有（且不是加载失败）：保留页签与输入栏，
                // 因为现在能发评论了 —— 空页面正是"来发第一条"的场景。
                state.comments.isEmpty() && state.hot.isEmpty() && state.error != null -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            state.error ?: "评论加载失败",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "点右上角刷新重试",
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }

                else -> Column(Modifier.fillMaxSize()) {
                    CommentTabs(
                        total = state.total,
                        selected = tab,
                        onSelect = { tab = it },
                    )
                    // 网易云评论区未登录：点赞只能本地假装，刷新就没了 ——
                    // 与其做个假按钮，不如明说，并给一条直达登录的入口。
                    if (!state.canLike && state.platform == "网易云") {
                        LoginBanner(onClick = onLogin)
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (tab == 0) {
                            if (state.hot.isEmpty()) {
                                item(key = "hot-empty") { EmptyCommentHint("还没有人在这首歌下面发过评论") }
                            } else {
                                items(
                                    state.hot,
                                    key = { "hot:" + it.id },
                                    contentType = { "comment" },
                                ) { c ->
                                    CommentCard(
                                        comment = c,
                                        isMine = myUid > 0L && c.userId == myUid,
                                        replyToId = state.replyTo?.commentId,
                                        canReply = state.canPost,
                                        onLike = { viewModel.toggleLike(c) },
                                        onReply = { viewModel.toggleReply(c) },
                                        onDelete = { viewModel.deleteComment(c) },
                                    )
                                }
                                item(key = "hot-footer") {
                                    Box(
                                        Modifier.fillMaxWidth().padding(vertical = 18.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            "— 精彩评论共 ${formatCount(state.hot.size)} 条 —",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                        )
                                    }
                                }
                            }
                        } else {
                            if (state.comments.isEmpty()) {
                                item(key = "new-empty") { EmptyCommentHint("还没有评论，来做第一个吧") }
                            }
                            items(
                                state.comments,
                                key = { "new:" + it.id },
                                contentType = { "comment" },
                            ) { c ->
                                CommentCard(
                                    comment = c,
                                    isMine = myUid > 0L && c.userId == myUid,
                                    replyToId = state.replyTo?.commentId,
                                    canReply = state.canPost,
                                    onLike = { viewModel.toggleLike(c) },
                                    onReply = { viewModel.toggleReply(c) },
                                    onDelete = { viewModel.deleteComment(c) },
                                )
                            }
                            item(key = "footer") {
                                CommentsFooter(
                                    loaded = state.comments.size,
                                    total = state.total,
                                    streaming = state.streaming,
                                    loadingMore = state.loadingMore,
                                    hasMore = state.hasMore,
                                    error = state.error,
                                    onLoadMore = viewModel::loadMore,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 评论区顶部页签：精彩评论 / 最新评论（胶囊，跟随主题色）。
 *
 * 页签上**不挂数字**：串流过程中它会一直跳（50 → 900 → 4.7k），看着像界面在闪。
 * 总数只在右侧那一个「共 N 条」上体现。
 */
@Composable
private fun CommentTabs(
    total: Int,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TabPill(
            label = "精彩评论",
            icon = Icons.Filled.Whatshot,
            selected = selected == 0,
            onClick = { onSelect(0) },
        )
        Spacer(Modifier.width(8.dp))
        TabPill(
            label = "最新评论",
            icon = null,
            selected = selected == 1,
            onClick = { onSelect(1) },
        )
        Spacer(Modifier.weight(1f))
        // 总数放这里而不是顶部副标题：歌名一长，顶部那行会把总数挤成省略号
        if (total > 0) {
            Text(
                "共 ${formatCount(total)} 条",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            )
        }
    }
}

/**
 * 空态占位：一句人话 + 居中留白。
 *
 * 顶部留 60dp 而不是死贴列表顶：空评论区最常见的场景是"进来就想发第一条"，
 * 视线自然落到输入栏，中间的空白正好把它让出来。
 */
@Composable
private fun EmptyCommentHint(text: String) {
    Box(
        Modifier.fillMaxWidth().padding(top = 60.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * 未登录时的写操作引导条：一句人话 + 直达登录。
 *
 * 只占一行高度，条数多也不影响列表；点它去登录页，回来这条自己消失（登录态是 Flow）。
 */
@Composable
private fun LoginBanner(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
            .pressable(scaleDown = 0.98f, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.FavoriteBorder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "登录网易云后，点赞和发评论都会同步到你的账号",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "去登录",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun TabPill(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val content = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
            )
            .pressable(scaleDown = 0.95f, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(5.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = content,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/** 单条评论卡片：头像 + 昵称 + 正文 + 回复引用 + （时间 · 属地）+ 点赞。
 *
 * 刻意**不用液态玻璃**：评论动辄几千条，每一行都跑一遍折射/描边着色器的话，
 * 一屏十几条就是十几条 GPU 管线，滚动必掉帧。这里用"纯色卡片 + 一根细描边"，
 * 视觉上与玻璃卡片同一族，代价却接近于零。
 *
 * 操作行放在底部一行：点赞（人人可点）、回复（已登录且走网易云通道才给）、
 * 删除（只有自己发的才给 —— 网易云删他人评论一律 40102，给了也是坑）。
 */
@Composable
private fun CommentCard(
    comment: Comment,
    isMine: Boolean,
    replyToId: String?,
    canReply: Boolean,
    onLike: () -> Unit,
    onReply: () -> Unit,
    onDelete: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    val replying = replyToId == comment.id
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                // 正在回复的那条高亮一下，不然点了"回复"下面多一条提示，用户不知道回的是谁
                if (replying) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                else MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
            )
            .border(0.8.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f), shape)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Row {
            Avatar(comment)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        comment.userName,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (comment.vipType > 0) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "VIP",
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))
                Text(
                    comment.content,
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 21.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                )

                comment.beReplied.forEach { replied ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "//@${replied.userName}：${replied.content}",
                        style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }

                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        buildString {
                            if (comment.timeText.isNotBlank()) append(comment.timeText)
                            if (comment.ipLocation.isNotBlank()) {
                                if (isNotEmpty()) append("  ·  ")
                                append(comment.ipLocation)
                            }
                            if (isMine) {
                                if (isNotEmpty()) append("  ·  ")
                                append("我")
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (comment.replyCount > 0) {
                        Text(
                            "${formatCount(comment.replyCount)} 回复",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    LikeButton(comment = comment, onClick = onLike)
                }

                // 操作行：回复 / 删除。默认只显示「回复」，与网易云一致（少一次误触删除的机会）。
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                ) {
                    if (canReply) {
                        ActionChip(
                            label = if (replying) "取消回复" else "回复",
                            tint = if (replying) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                            onClick = onReply,
                        )
                    }
                    if (isMine) {
                        if (canReply) Spacer(Modifier.width(8.dp))
                        ActionChip(
                            label = "删除",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                            onClick = onDelete,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 操作行的小胶囊（回复 / 删除）。
 *
 * 不做成图标按钮：两个字说清动作，比一个「↩」箭头更不容易误触，
 * 视觉上也和点赞那排小控件同一族。
 */
@Composable
private fun ActionChip(label: String, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = tint,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
            .pressable(scaleDown = 0.9f, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    )
}

@Composable
private fun LikeButton(comment: Comment, onClick: () -> Unit) {
    val liked = comment.liked
    val scale by animateFloatAsState(
        targetValue = if (liked) 1.12f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 900f),
        label = "likeScale",
    )
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .pressable(scaleDown = 0.9f, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            contentDescription = if (liked) "取消点赞" else "点赞",
            tint = if (liked) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            modifier = Modifier.size(15.dp).scale(scale),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            formatCount(comment.likeCount),
            style = MaterialTheme.typography.labelSmall,
            color = if (liked) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
        )
    }
}

@Composable
private fun Avatar(comment: Comment) {
    if (comment.avatarUrl.isNotBlank()) {
        val context = LocalContext.current
        // 显式限定解码尺寸 + 关掉淡入：评论区几千条时，按原图解码会瞬间吃掉几百 MB 内存，
        // 淡入动画则让快速滚动时每秒新建几十个动画。
        val request = remember(comment.avatarUrl) {
            ImageRequest.Builder(context)
                .data(comment.avatarUrl)
                .size(108)
                .crossfade(false)
                .build()
        }
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
    } else {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                comment.userName.take(1).ifBlank { "?" },
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * 列表尾部：串流进度 / 加载中 / 手动加载更多 / 已到底。
 *
 * 全量串流时这里是最直观的"实时"指示器：`已加载 320 / 4459` 会随着请求一页页往上跳。
 */
@Composable
private fun CommentsFooter(
    loaded: Int,
    total: Int,
    streaming: Boolean,
    loadingMore: Boolean,
    hasMore: Boolean,
    error: String?,
    onLoadMore: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            // 后台还在串流：不写"正在加载 / 已加载 x / y" —— 那行字会一直跳，
            // 反而让人觉得这页没加载完。只留一个安静的小圈，滚到底自然就有新内容。
            streaming || loadingMore -> CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
            )

            hasMore -> Text(
                "加载更多评论",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .pressable(scaleDown = 0.94f, onClick = onLoadMore)
                    .padding(horizontal = 18.dp, vertical = 8.dp),
            )

            else -> {
                AnimatedVisibility(visible = true, enter = fadeIn(), exit = fadeOut()) {
                    Text(
                        error ?: if (total > 0) "— 已取到全部 ${formatCount(loaded)} 条评论 —" else "— 已经到底啦 —",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
            }
        }
    }
}

private fun formatCount(n: Int): String = when {
    n <= 0 -> "0"
    n < 1000 -> n.toString()
    n < 10_000 -> "${n / 1000}.${(n % 1000) / 100}k"
    else -> "${n / 10_000}.${(n % 10_000) / 1000}w"
}
