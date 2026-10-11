package com.puremusic.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.puremusic.model.PlayMode
import com.puremusic.model.Song
import com.puremusic.source.SourceRegistry
import com.puremusic.source.autoswitch.AutoSwitchSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** 把统一的 Song 映射为 Media3 的 MediaItem。 */
private fun Song.toMediaItem(url: String?): MediaItem {
    val metadata = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artist)
        .setAlbumTitle(album)
        .setArtworkUri(coverUrl.takeIf { it.isNotBlank() }?.let(Uri::parse))
        .build()
    return MediaItem.Builder()
        .setMediaId(mediaId)
        .setMediaMetadata(metadata)
        .setUri(url?.let(Uri::parse) ?: Uri.EMPTY)
        .build()
}

/**
 * 全局播放控制器（单例）。通过 MediaController 连接 [PlaybackService]，
 * 对外暴露播放状态 StateFlow，供 UI 观察。
 */
@Singleton
class PlayerManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sourceRegistry: SourceRegistry,
    private val autoSwitch: AutoSwitchSource,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controller: MediaController? = null
    private var queueSongs: List<Song> = emptyList()

    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _bufferedPositionMs = MutableStateFlow(0L)
    val bufferedPositionMs: StateFlow<Long> = _bufferedPositionMs.asStateFlow()

    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    val queue: StateFlow<List<Song>> = _queue.asStateFlow()

    private val _shuffle = MutableStateFlow(false)
    val shuffle: StateFlow<Boolean> = _shuffle.asStateFlow()

    private val _repeat = MutableStateFlow(false)
    val repeat: StateFlow<Boolean> = _repeat.asStateFlow()

    /** 播放模式（顺序 / 随机 / 单曲循环），UI 上一个按钮循环切换。 */
    private val _playMode = MutableStateFlow(PlayMode.SEQUENCE)
    val playMode: StateFlow<PlayMode> = _playMode.asStateFlow()

    /** 播放倍速（0.5 ~ 2.0）。 */
    private val _speed = MutableStateFlow(1.0f)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    /** 睡眠定时结束时间戳（毫秒）；0 表示未设置。 */
    private val _sleepTimerEndMs = MutableStateFlow(0L)
    val sleepTimerEndMs: StateFlow<Long> = _sleepTimerEndMs.asStateFlow()

    private var sleepJob: Job? = null

    private var initialized = false

    /**
     * 原始（未解析直链）的播放队列，索引与 MediaController 的 playlist 一一对齐。
     * 懒解析靠它：队列里大部分曲目一开始只放一个空 uri 占位，播到附近才补解析。
     */
    private var pendingSongs: List<Song> = emptyList()

    /** 单次预解析的窗口大小（含当前首）：太小会被用户点"下一首"追上，太大会拖慢开播。 */
    private val preloadAhead = 4

    /** 正在跑的预解析任务，避免 onMediaItemTransition 高频回调时重复起任务。 */
    private var preloadJob: Job? = null

    /** 播放错误后的恢复尝试计数，切换媒体时重置。 */
    private var errorRetryCount = 0

    /** 最多换源尝试次数（防死循环）。 */
    private val maxSwitchTries = 3

    /** 等待播放成功时提示的换源提示：mediaId -> 新源显示名。 */
    private val pendingSwitchToast = mutableMapOf<String, String>()

    private fun displayNameOf(sourceId: String): String =
        sourceRegistry.byId(sourceId)?.displayName ?: sourceId

    /**
     * 解析单曲可播放直链：原源取流失败时自动跨源换源（保留原 song.id，对 UI 无感）。
     * 返回（实际播放用 Song，直链）；无可用直链返回 null。
     */
    private suspend fun resolvePlayable(song: Song): Pair<Song, String>? {
        song.playUrl?.let { return song to it }
        val originalUrl = runCatching { sourceRegistry.byId(song.source.id)?.getSongUrl(song) }
            .getOrNull()
        if (!originalUrl.isNullOrBlank() && autoSwitch.verifyUrl(originalUrl)) {
            return song to originalUrl
        }
        if (!originalUrl.isNullOrBlank()) {
            // 原源直链无效：进坏 URL 缓存，避免后续换源候选误用同款失效直链
            autoSwitch.recordBadUrl(
                AutoSwitchSource.badKeyOf(
                    song.source.id,
                    song.extra[AutoSwitchSource.SRC_ID_KEY] ?: song.id,
                ),
                originalUrl,
            )
        }
        // 原源失败 → 跨源自动换源
        val switched = runCatching { autoSwitch.trySwitch(song, excludeSourceIds = emptySet()) }
            .getOrNull()
        if (switched != null) {
            android.util.Log.w(
                "PlayerManager",
                "跨源自动换源: ${song.source.id} -> ${switched.song.source.id} (${switched.song.mediaId})"
            )
            pendingSwitchToast[switched.song.mediaId] = displayNameOf(switched.song.source.id)
            return switched.song to switched.url
        }
        return null
    }

    fun init() {
        if (initialized) return
        initialized = true

        startPositionPoller()

        try {
            val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
            val future = MediaController.Builder(context, token).buildAsync()
            future.addListener({
                val c = runCatching { future.get() }.getOrNull()
                if (c == null) {
                    android.util.Log.e("PlayerManager", "MediaController 连接失败")
                    return@addListener
                }
                controller = c
                c.addListener(controllerListener)
                _isPlaying.value = c.isPlaying
                _shuffle.value = c.shuffleModeEnabled
                _repeat.value = c.repeatMode == Player.REPEAT_MODE_ALL
                _playMode.value = modeOf(c)
                // 服务重建后把 UI 上的模式写回播放器：ExoPlayer 默认是「不循环」，
                // 不写回的话播完最后一首就停 —— 表现为"不会自动切下一首"。
                applyMode(c, _playMode.value)
                _speed.value = c.playbackParameters.speed
            }, ContextCompat.getMainExecutor(context))
        } catch (e: Exception) {
            android.util.Log.e("PlayerManager", "init 异常", e)
        }
    }

    private fun startPositionPoller() {
        scope.launch {
            while (isActive) {
                controller?.let { c ->
                    _positionMs.value = c.currentPosition
                    _durationMs.value = c.duration.takeIf { it != C.TIME_UNSET } ?: 0L
                    _bufferedPositionMs.value = c.bufferedPosition.takeIf { it != C.TIME_UNSET } ?: 0L
                }
                delay(500)
            }
        }
    }

    private val controllerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            android.util.Log.e("Player", "播放错误 code=${error.errorCode} msg=${error.message} cause=${error.cause}")
            val c = controller ?: return
            val reason = error.errorCodeName?.takeIf { it.isNotBlank() } ?: error.message ?: "未知错误"
            // 直链还没解析出来就播了（用户连点下一首跳到窗口外）：这不是源的问题，
            // 交给 onMediaItemTransition 的补解析兜底，别去换源、更别跳下一首。
            val uri = c.currentMediaItem?.localConfiguration?.uri
            if (uri == null || uri == Uri.EMPTY) {
                android.util.Log.w("PlayerManager", "直链尚未解析（$reason），等待补解析")
                return
            }
            if (errorRetryCount < maxSwitchTries) {
                errorRetryCount++
                val currentId = c.currentMediaItem?.mediaId
                val current = queueSongs.firstOrNull { it.mediaId == currentId }
                if (current != null) {
                    // 播放错误大概率是当前直链失效：记入坏 URL 缓存，避免换源时再命中
                    val badUrl = c.currentMediaItem?.localConfiguration?.uri?.toString().orEmpty()
                    if (badUrl.startsWith("http")) {
                        autoSwitch.recordBadUrl(
                            AutoSwitchSource.badKeyOf(
                                current.source.id,
                                current.extra[AutoSwitchSource.SRC_ID_KEY] ?: current.id,
                            ),
                            badUrl,
                        )
                    }
                    scope.launch {
                        // 运行时 error → 跨源自动换源（同曲其它源）
                        val switched = runCatching {
                            autoSwitch.trySwitch(current, excludeSourceIds = setOf(current.source.id))
                        }.getOrNull()
                        if (switched != null) {
                            queueSongs = queueSongs.map {
                                if (it.mediaId == currentId) switched.song else it
                            }
                            pendingSwitchToast[switched.song.mediaId] =
                                displayNameOf(switched.song.source.id)
                            android.util.Log.w(
                                "PlayerManager",
                                "运行时错误跨源换源: ${current.source.id} -> ${switched.song.source.id}"
                            )
                            // MediaController 无 (item, index, pos) 三参重载：二参版本即替换当前 index 的曲目
                            c.setMediaItem(switched.song.toMediaItem(switched.url), 0L)
                            c.prepare()
                            c.play()
                        } else {
                            fallbackAfterPlayerError(c, reason)
                        }
                    }
                    return
                }
                fallbackAfterPlayerError(c, reason)
            } else {
                errorRetryCount = 0
                android.widget.Toast.makeText(
                    context, "播放失败：$reason",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            if (isPlaying) {
                // 播放成功后才提示已换源
                val id = controller?.currentMediaItem?.mediaId
                if (id != null) {
                    val sourceName = pendingSwitchToast.remove(id)
                    if (sourceName != null) {
                        android.widget.Toast.makeText(
                            context, "已自动切换到${sourceName}播放",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            errorRetryCount = 0
            val c = controller ?: return
            val id = mediaItem?.mediaId
            _currentSong.value = queueSongs.firstOrNull { it.mediaId == id }
            val idx = c.currentMediaItemIndex
            if (idx < 0) return
            // 播到哪补到哪：预解析窗口始终跟着当前位置走，切下一首时直链早已就位
            ensureWindow(c, idx)
            // 跳到窗口外（用户连点下一首 / 随机跳很远）时的兜底：立刻补解析并重播
            val uri = mediaItem?.localConfiguration?.uri
            if (uri == null || uri == Uri.EMPTY) {
                scope.launch {
                    val s = pendingSongs.getOrNull(idx) ?: return@launch
                    val resolved = runCatching { resolvePlayable(s) }.getOrNull() ?: return@launch
                    writeResolved(idx, resolved.first)
                    runCatching {
                        c.replaceMediaItem(idx, resolved.first.toMediaItem(resolved.second))
                        c.prepare()
                        c.play()
                    }.onFailure { android.util.Log.w("PlayerManager", "补解析后重播失败", it) }
                }
            }
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            _shuffle.value = shuffleModeEnabled
            controller?.let { _playMode.value = modeOf(it) }
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            _repeat.value = repeatMode == Player.REPEAT_MODE_ALL
            controller?.let { _playMode.value = modeOf(it) }
        }

        override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
            _speed.value = playbackParameters.speed
        }
    }

    /** 设置播放倍速。 */
    fun setSpeed(value: Float) {
        val clamped = value.coerceIn(0.5f, 2.0f)
        controller?.setPlaybackSpeed(clamped)
        _speed.value = clamped
    }

    /** 设置睡眠定时：minutes 分钟后自动暂停；传 <=0 表示取消。 */
    fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        sleepJob = null
        if (minutes <= 0) {
            _sleepTimerEndMs.value = 0L
            return
        }
        val endAt = System.currentTimeMillis() + minutes * 60_000L
        _sleepTimerEndMs.value = endAt
        sleepJob = scope.launch {
            delay(minutes * 60_000L)
            controller?.pause()
            _sleepTimerEndMs.value = 0L
            android.widget.Toast.makeText(context, "睡眠定时已到，已暂停播放", android.widget.Toast.LENGTH_SHORT).show()
            sleepJob = null
        }
    }

    fun cancelSleepTimer() = setSleepTimer(0)

    /** 换源失败后的兜底：有下一首切下一首，否则原地重试。 */
    private fun fallbackAfterPlayerError(c: MediaController, reason: String) {
        val hasMore = c.mediaItemCount > 0 && c.currentMediaItemIndex < c.mediaItemCount - 1
        if (hasMore) {
            android.widget.Toast.makeText(
                context, "播放失败（$reason），已自动切换下一首",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            c.seekToNextMediaItem()
        } else {
            android.widget.Toast.makeText(
                context, "播放失败（$reason），正在重试",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            c.seekTo(c.currentPosition)
            c.play()
        }
    }

    /**
     * 播放单曲。
     *
     * **必须传 [context]（点这首歌时所在的列表）**：只把一首歌塞进队列的话，
     * 播完就结束了 —— 这就是「歌曲不会自动切下一首」的根因。
     * 传了列表就整队入列并从这一首开始播，播完自然往下走。
     */
    fun play(song: Song, context: List<Song> = emptyList()) {
        val idx = context.indexOfFirst { it.mediaId == song.mediaId }
        if (idx >= 0) playQueue(context, idx) else playQueue(listOf(song), 0)
    }

    /**
     * 播放一个队列，从 index 开始。
     *
     * 采用**懒解析**：只先解析起始这一首并立刻出声，其余曲目先放空 uri 占位，
     * 播到附近（[preloadAhead] 首内）时才在后台补解析并替换。
     * 之前是进队列前逐首取直链 —— 617 首的歌单要点几百次网络请求、
     * 几分钟后才能出声，而且中途任何一首超时都会拖垮整队。
     */
    fun playQueue(songs: List<Song>, index: Int) {
        scope.launch {
            if (songs.isEmpty()) return@launch
            val c = controller
            if (c == null) {
                android.util.Log.e("PlayerManager", "controller 为 null，无法播放")
                return@launch
            }
            val startIndex = index.coerceIn(0, songs.lastIndex)
            val first = runCatching { resolvePlayable(songs[startIndex]) }.getOrNull()
            if (first == null) {
                android.widget.Toast.makeText(
                    context,
                    "该歌曲无可用播放源（可能为 VIP 或无版权）",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            queueSongs = songs.toMutableList().also { it[startIndex] = first.first }
            _queue.value = queueSongs
            pendingSongs = songs

            try {
                val items = songs.mapIndexed { i, s ->
                    if (i == startIndex) first.first.toMediaItem(first.second) else s.toMediaItem(null)
                }
                c.setMediaItems(items, startIndex, 0L)
                applyMode(c, _playMode.value)
                c.prepare()
                c.play()
                android.util.Log.d("PlayerManager", "播放命令已发送, 队列=${songs.size} 起播=$startIndex")
                _currentSong.value = first.first
            } catch (e: Exception) {
                android.util.Log.e("PlayerManager", "播放异常", e)
            }
            ensureWindow(c, startIndex)
        }
    }

    /**
     * 预解析窗口：把 [from] 起的 [preloadAhead] 首里还没解析的曲目补上直链。
     *
     * 用 MediaItem 的 uri 是否为空判断"已解析"，因此重复调用是幂等的；
     * 换过源的曲目会被写回 [queueSongs]，UI 看到的仍是同一个 mediaId。
     */
    private fun ensureWindow(c: MediaController, from: Int) {
        if (from !in pendingSongs.indices) return
        val to = minOf(from + preloadAhead, pendingSongs.lastIndex)
        preloadJob?.cancel()
        preloadJob = scope.launch {
            for (i in from..to) {
                if (i !in pendingSongs.indices) break
                val item = runCatching { c.getMediaItemAt(i) }.getOrNull() ?: break
                val uri = item.localConfiguration?.uri
                if (uri != null && uri != Uri.EMPTY) continue
                val resolved = runCatching { resolvePlayable(pendingSongs[i]) }.getOrNull() ?: continue
                writeResolved(i, resolved.first)
                runCatching {
                    c.replaceMediaItem(i, resolved.first.toMediaItem(resolved.second))
                }.onFailure { android.util.Log.w("PlayerManager", "预解析替换失败 i=$i", it) }
            }
        }
    }

    /** 把换源/解析后的曲目写回队列（保持索引不变，UI 无感）。 */
    private fun writeResolved(index: Int, song: Song) {
        if (index !in queueSongs.indices) return
        queueSongs = queueSongs.toMutableList().also { it[index] = song }
        _queue.value = queueSongs
    }

    fun togglePlayPause() {
        controller?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    fun next() = controller?.seekToNextMediaItem()

    fun previous() = controller?.seekToPreviousMediaItem()

    fun seekTo(ms: Long) = controller?.seekTo(ms)

    /** 播放模式三态循环：顺序 → 随机 → 单曲 → 顺序。 */
    fun cyclePlayMode() = setPlayMode(_playMode.value.next())

    fun setPlayMode(mode: PlayMode) {
        _playMode.value = mode
        controller?.let { applyMode(it, mode) }
    }

    /** 把 [PlayMode] 落到 ExoPlayer 的两个底层开关上。 */
    private fun applyMode(c: MediaController, mode: PlayMode) {
        when (mode) {
            PlayMode.SEQUENCE -> {
                c.shuffleModeEnabled = false
                // 列表循环：播完最后一首回到第一首，永远不会"播完就停"
                c.repeatMode = Player.REPEAT_MODE_ALL
            }
            PlayMode.SHUFFLE -> {
                c.shuffleModeEnabled = true
                c.repeatMode = Player.REPEAT_MODE_ALL
            }
            PlayMode.REPEAT_ONE -> {
                c.shuffleModeEnabled = false
                c.repeatMode = Player.REPEAT_MODE_ONE
            }
        }
    }

    /** 从播放器当前状态反推 [PlayMode]（服务重建 / 外部改了模式时用它回读）。 */
    private fun modeOf(c: MediaController): PlayMode = when {
        c.repeatMode == Player.REPEAT_MODE_ONE -> PlayMode.REPEAT_ONE
        c.shuffleModeEnabled -> PlayMode.SHUFFLE
        else -> PlayMode.SEQUENCE
    }
}
