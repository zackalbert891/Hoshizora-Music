package com.puremusic.ui.comments

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.SubdirectoryArrowLeft
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.puremusic.source.netease.MAX_COMMENT_LEN
import com.puremusic.ui.theme.PressFeedback
import com.puremusic.ui.theme.pressable

/**
 * 网易云表情面板：网易云没有可用的表情列表接口（`api/expressions` 几个路径全 404，
 * 2026-10 实测），但**评论正文里的方括号文本就是表情**（`[开心]` 这类），
 * 服务端只当普通字符串存，任何端都渲染一致 —— 所以这里内置一份常用表，
 * 点一下就往光标处插一段文本，不需要任何网络请求。
 *
 * 混编 Unicode emoji 也是网易云正文里常见写法，一并放进来。
 */
private val NETEASE_FACES: List<String> = listOf(
    "[开心]", "[大笑]", "[酷]", "[亲亲]", "[可怜]", "[流泪]",
    "[发呆]", "[困]", "[惊讶]", "[生气]", "[流泪]", "[发抖]",
    "[喜欢]", "[崇拜]", "[鼓掌]", "[思考]", "[无奈]", "[必胜]",
    "[晕]", "[抓狂]", "[吐舌]", "[愉快]", "[微笑]", "[憨笑]",
    "[调皮]", "[得意]", "[流泪]", "[撇嘴]", "[疑问]", "[嘘]",
    "[睡着]", "[皱眉]", "[叹气]", "[裂开]", "[aru]", "[aru2]",
)

private val UNICODE_EMOJI: List<String> = listOf(
    "😂", "🥹", "😍", "🤩", "😘", "😎",
    "🥳", "😭", "😤", "🤔", "🫠", "😴",
    "👍", "👎", "🙏", "👏", "💪", "🤝",
    "❤️", "💔", "🔥", "✨", "🎉", "🌟",
    "🎵", "🎧", "🍂", "🌙", "☕️", "🍻",
)

/**
 * 评论发表栏：固定在页面底部。
 *
 * 结构（与网易云 App 一致）：输入框 + 表情按钮 + 发送按钮，
 * 回复态时在输入框上方多一条「回复 @某某 ×」。
 *
 * @param canPost 未登录时禁用并显示引导文案 —— 不能给一个按了没用的输入框。
 * @param platform 当前评论来自哪个平台（"QQ音乐" / "网易云"）：未登录提示要指名道姓，
 *   QQ 的歌提示"登录网易云账号"会把人指到错误的登录页去。
 * @param maxLength 输入上限，**按平台**给（网易云 1000 / QQ 音乐 300）。
 * @param onLogin 未登录时点输入框直接跳**对应平台**的登录页。
 */
@Composable
fun CommentComposer(
    canPost: Boolean,
    sending: Boolean,
    platform: String,
    maxLength: Int = MAX_COMMENT_LEN,
    replyTo: ReplyTarget?,
    onReplyCancel: () -> Unit,
    onSend: (String) -> Unit,
    onLogin: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var emojiOpen by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(22.dp)
    val enabled = canPost && !sending
    val limit = maxLength
    val sendScale by animateFloatAsState(
        targetValue = if (text.isNotBlank() && enabled) 1f else 0.88f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 800f),
        label = "sendScale",
    )

    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
            .border(
                0.8.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
            )
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        // ── 回复态提示条 ──
        AnimatedVisibility(
            visible = replyTo != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp, bottom = 6.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.SubdirectoryArrowLeft,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "回复 @${replyTo?.userName.orEmpty()}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onReplyCancel),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "取消回复",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }

        // ── 输入行 ──
        Row(verticalAlignment = Alignment.Bottom) {
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                    .border(
                        0.8.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                        shape,
                    )
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (text.isEmpty()) {
                    Text(
                        if (enabled) "说点什么吧…"
                        else "登录${platform.ifBlank { "网易云" }}账号即可发评论",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        maxLines = 1,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !enabled, onClick = onLogin),
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = {
                        // 网易云服务端正文上限 1000 字，这里先卡住，避免白发一次请求
                        if (it.length <= limit) text = it
                    },
                    enabled = enabled,
                    textStyle = TextStyle(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp,
                        lineHeight = 21.sp,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // 表情按钮
            Box(
                Modifier
                    .padding(start = 4.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .pressable(
                        scaleDown = 0.88f,
                        feedback = PressFeedback.CLICK,
                        onClick = { emojiOpen = !emojiOpen },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.EmojiEmotions,
                    contentDescription = "表情",
                    tint = if (emojiOpen) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    modifier = Modifier.size(22.dp),
                )
            }

            // 发送按钮：空内容 / 未登录 / 发送中都是灰的，避免"按了没反应"
            Box(
                Modifier
                    .padding(start = 2.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (text.isNotBlank() && enabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    )
                    .clickable(
                        enabled = text.isNotBlank() && enabled,
                        onClick = {
                            val body = text
                            text = ""
                            emojiOpen = false
                            onSend(body)
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (sending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(17.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Icon(
                        Icons.Filled.Send,
                        contentDescription = "发送",
                        tint = if (text.isNotBlank() && enabled) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        },
                        modifier = Modifier.size(19.dp).scale(sendScale),
                    )
                }
            }
        }

        // 剩余字数：只在快到上限时才提示，平时不占地方
        if (text.length > limit - 100) {
            Text(
                "${text.length}/$limit",
                style = MaterialTheme.typography.labelSmall,
                color = if (text.length > limit) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                },
                modifier = Modifier.align(Alignment.End).padding(top = 4.dp),
            )
        }

        // ── 表情面板 ──
        AnimatedVisibility(
            visible = emojiOpen,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            EmojiPanel(
                onPick = { face ->
                    if (text.length + face.length <= limit) text += face
                },
            )
        }
    }
}

/** 表情面板：上半网易云方括号表情，下半 Unicode emoji，各自 7 列网格。 */
@Composable
private fun EmojiPanel(onPick: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .height(228.dp),
    ) {
        EmojiGrid(NETEASE_FACES, onPick, textStyle = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(6.dp))
        EmojiGrid(UNICODE_EMOJI, onPick, textStyle = MaterialTheme.typography.titleMedium)
    }
}

/** 单个表情网格。表情多时用 LazyVerticalGrid，展开时不一次量全部高度。 */
@Composable
private fun ColumnScope.EmojiGrid(
    faces: List<String>,
    onPick: (String) -> Unit,
    textStyle: TextStyle,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(7),
        contentPadding = PaddingValues(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxWidth().weight(1f),
    ) {
        items(faces) { face ->
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .pressable(scaleDown = 0.82f, onClick = { onPick(face) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    face,
                    style = textStyle.copy(fontWeight = FontWeight.Normal),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
        }
    }
}