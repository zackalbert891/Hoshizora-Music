@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.puremusic.ui.source

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.puremusic.model.MusicSourceType
import com.puremusic.model.Playlist
import com.puremusic.ui.components.AppBackground
import com.puremusic.ui.components.PlaylistGridCard
import com.puremusic.ui.components.displayLabel
import com.puremusic.ui.theme.Glass
import com.puremusic.ui.theme.UiTokens

/**
 * 平台页：点「QQ音乐」只看 QQ 音乐的云端歌单，点「网易云音乐」只看网易云的。
 *
 * 未登录时给出明确登录入口；登录后展示该账号自己的歌单（含我喜欢的）。
 */
@Composable
fun SourceScreen(
    sourceId: String,
    backgroundImagePath: String?,
    onBack: () -> Unit,
    onLogin: () -> Unit,
    onOpenPlaylist: (sourceId: String, id: String, name: String) -> Unit,
    onSearch: () -> Unit = {},
) {
    val vm: SourceViewModel = hiltViewModel()
    val account by vm.account.collectAsStateWithLifecycle()
    val localPlaylists by vm.localPlaylists.collectAsStateWithLifecycle()
    val type = vm.type
    val name = vm.source?.displayName ?: "音乐库"
    val isLocal = type == MusicSourceType.LOCAL
    val needsLogin = !isLocal && (vm.source?.supportsLogin == true)

    // 登录成功后自动刷新云端歌单（从登录页返回时 account 由 null 变为非 null）
    LaunchedEffect(account?.cookie) {
        if (account != null) vm.refresh()
    }

    // 背景由 AppRoot 的 AppBackground 统一铺满整屏（含底部栏），页面不再自己画。
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 28.dp),
        ) {
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 8.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                    Text(
                        name,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (!isLocal) {
                        IconButton(onClick = vm::refresh) {
                            Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                        }
                    }
                }
            }

            item {
                BrandHeader(
                    name = name,
                    type = type,
                    subtitle = when {
                        isLocal -> "本机音乐 · ${localPlaylists.size} 个歌单"
                        // 只写昵称；拿不到昵称、或昵称本身就是一串号码（QQ 的 uin / 网易云脱敏手机号），
                        // 统一写「已登录」—— 那串数字对用户没有任何意义。
                        account != null -> account.displayLabel()
                        needsLogin -> "登录后查看你的云端歌单"
                        else -> "免登录音源，可直接搜索播放"
                    },
                    actionLabel = when {
                        isLocal -> null
                        account != null -> "退出"
                        needsLogin -> "登录"
                        else -> null
                    },
                    onAction = {
                        if (account != null) vm.logout() else onLogin()
                    },
                    modifier = Modifier.padding(horizontal = UiTokens.SpaceL, vertical = UiTokens.SpaceS),
                )
            }

            if (isLocal) {
                item { SectionTitle("我的歌单") }
                if (localPlaylists.isEmpty()) {
                    item { Hint("还没有本地歌单，可在音乐库页新建") }
                } else {
                    gridItems(localPlaylists, "local") { p, index ->
                        PlaylistGridCard(
                            name = p.name,
                            subtitle = "${p.trackCount} 首",
                            coverUrl = p.coverUrl,
                            index = index,
                            onClick = { onOpenPlaylist(MusicSourceType.LOCAL.id, p.id, p.name) },
                        )
                    }
                }
            } else if (!needsLogin) {
                item {
                    Surface(
                        shape = RoundedCornerShape(UiTokens.RadiusLarge),
                        color = Glass.Panel,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = UiTokens.SpaceL, vertical = UiTokens.SpaceS),
                    ) {
                        Column(Modifier.padding(20.dp)) {
                            Text("该音源无需登录", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "它是搜索 / 换源时的备用音源，没有云端歌单。直接在搜索里搜即可播放。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(12.dp))
                            TextButton(onClick = onSearch) { Text("去搜索") }
                        }
                    }
                }
            } else if (account == null) {
                item {
                    Surface(
                        shape = RoundedCornerShape(UiTokens.RadiusLarge),
                        color = Glass.Panel,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = UiTokens.SpaceL, vertical = UiTokens.SpaceS),
                    ) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                Icons.Filled.CloudQueue,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(48.dp),
                            )
                            Spacer(Modifier.height(12.dp))
                            Text("登录后查看 $name 的云端歌单", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "支持扫码登录、手机号登录与手动粘贴 Cookie",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = onLogin) { Text("登录 $name") }
                        }
                    }
                }
            } else {
                item { SectionTitle("云端歌单（${vm.playlists.size}）") }
                when {
                    vm.loading -> item { LoadingBlock() }
                    vm.playlists.isEmpty() -> item { Hint(vm.error ?: "没有获取到云端歌单") }
                    else -> gridItems(vm.playlists, "cloud") { p, index ->
                        PlaylistGridCard(
                            name = p.name,
                            subtitle = "${p.trackCount} 首",
                            coverUrl = p.coverUrl,
                            index = index,
                            onClick = { onOpenPlaylist(p.source.id, p.id, p.name) },
                        )
                    }
                }
            }
        }
    }
}

/** 两列歌单网格：LazyColumn + chunked(2)，避免嵌套滚动容器测量冲突。 */
private fun LazyListScope.gridItems(
    items: List<Playlist>,
    keyPrefix: String,
    content: @Composable (Playlist, Int) -> Unit,
) {
    itemsIndexed(items.chunked(2), key = { _, row -> "$keyPrefix:" + row.first().id }) { rowIndex, row ->
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = UiTokens.SpaceL)
                .animateItem(),
            horizontalArrangement = Arrangement.spacedBy(UiTokens.SpaceM),
        ) {
            row.forEachIndexed { col, p ->
                // 把行号折算成整体序号，卡片才能逐张错开入场
                Box(Modifier.weight(1f)) { content(p, rowIndex * 2 + col) }
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.height(UiTokens.SpaceM))
    }
}

@Composable
private fun LoadingBlock() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(180.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = UiTokens.SpaceL, vertical = 8.dp),
    )
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(
            start = UiTokens.SpaceL,
            end = UiTokens.SpaceS,
            top = UiTokens.SpaceL,
            bottom = UiTokens.SpaceS,
        ),
    )
}

/** 平台品牌头卡：品牌色渐变 + 账号状态 + 登录/退出按钮。 */
@Composable
private fun BrandHeader(
    name: String,
    type: MusicSourceType,
    subtitle: String,
    actionLabel: String?,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = brandGradient(type)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(132.dp)
            .clip(RoundedCornerShape(UiTokens.RadiusCard))
            .background(Brush.linearGradient(colors)),
    ) {
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 20.dp, end = 20.dp, bottom = 18.dp),
        ) {
            Text(
                name,
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (actionLabel != null) {
            Button(
                onClick = onAction,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 14.dp, end = 14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White.copy(alpha = 0.22f),
                    contentColor = Color.White,
                ),
            ) { Text(actionLabel) }
        }
    }
}

/** 各平台品牌色渐变（无网络 logo，靠品牌色建立辨识度）。 */
@Composable
private fun brandGradient(type: MusicSourceType): List<Color> = when (type) {
    MusicSourceType.QQ -> listOf(Color(0xFF31C27C), Color(0xFF0E9B85))
    MusicSourceType.NETEASE -> listOf(Color(0xFFE23A3A), Color(0xFF8E1B21))
    MusicSourceType.KUWO -> listOf(Color(0xFFFF8A00), Color(0xFFFFC13B))
    MusicSourceType.KUGOU -> listOf(Color(0xFF2E9BFF), Color(0xFF0F6FE0))
    MusicSourceType.MIGU -> listOf(Color(0xFF7C5CFF), Color(0xFF3E8BFF))
    MusicSourceType.QIANQIAN -> listOf(Color(0xFFFF5C8A), Color(0xFFB44CE8))
    MusicSourceType.BILIBILI -> listOf(Color(0xFFFB7299), Color(0xFFFF9DB7))
    MusicSourceType.LOCAL -> listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.tertiary,
    )
}
