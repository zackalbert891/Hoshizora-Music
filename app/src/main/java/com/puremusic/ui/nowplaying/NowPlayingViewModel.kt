package com.puremusic.ui.nowplaying

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.puremusic.data.SettingsStore
import com.puremusic.db.MusicRepository
import com.puremusic.lyrics.LyricCache
import com.puremusic.model.Lyrics
import com.puremusic.model.Playlist
import com.puremusic.model.Song
import com.puremusic.model.ThemeConfig
import com.puremusic.playback.PlayerManager
import com.puremusic.source.SourceRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NowPlayingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playerManager: PlayerManager,
    private val sourceRegistry: SourceRegistry,
    private val settingsStore: SettingsStore,
    private val lyricCache: LyricCache,
    private val repository: MusicRepository,
) : ViewModel() {

    val currentSong = playerManager.currentSong
    val isPlaying = playerManager.isPlaying
    val currentPosition = playerManager.positionMs
    val duration = playerManager.durationMs
    val bufferedPosition = playerManager.bufferedPositionMs
    val shuffle = playerManager.shuffle
    val repeat = playerManager.repeat
    /** 播放模式（顺序 / 随机 / 单曲）：播放页一个按钮循环切换。 */
    val playMode = playerManager.playMode
    val speed = playerManager.speed
    val sleepTimerEndMs = playerManager.sleepTimerEndMs

    val favoriteIds: StateFlow<Set<String>> =
        repository.observeFavoriteIds()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val localPlaylists: StateFlow<List<Playlist>> =
        repository.observeLocalPlaylists()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 播放页自定义背景图（null = 用封面）。 */
    val playerBackgroundPath: StateFlow<String?> = settingsStore.playerBackgroundPath
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** 主题配置（播放页要用其中的背景模糊强度）。 */
    val themeConfig: StateFlow<ThemeConfig> = settingsStore.themeConfig
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ThemeConfig())

    var lyrics by mutableStateOf(Lyrics())
    var showLyrics by mutableStateOf(false)

    init {
        viewModelScope.launch {
            playerManager.currentSong.collect { song ->
                lyrics = if (song == null) Lyrics() else loadLyrics(song)
                // 切歌即写入播放历史（同一首会累加次数并刷新时间）
                if (song != null) runCatching { repository.recordPlay(song) }
            }
        }
    }

    /** 歌词两级缓存读取：命中缓存直接返回，否则拉取并按「源:真实id」写入缓存。 */
    private suspend fun loadLyrics(song: Song): Lyrics {
        val key = LyricCache.keyOf(song)
        lyricCache.get(key)?.let { return Lyrics(lines = it.lines, translation = it.translation) }
        val source = sourceRegistry.byId(song.source.id) ?: return Lyrics()
        val fetched = runCatching { source.getLyrics(song) }.getOrDefault(Lyrics())
        if (fetched.lines.isNotEmpty() || fetched.translation.isNotEmpty()) {
            lyricCache.put(key, fetched)
        }
        return fetched
    }

    fun toggle() = playerManager.togglePlayPause()
    fun next() = playerManager.next()
    fun previous() = playerManager.previous()
    fun seek(ms: Long) = playerManager.seekTo(ms)
    /** 顺序 → 随机 → 单曲 → 顺序。 */
    fun cyclePlayMode() = playerManager.cyclePlayMode()

    /** 导入播放页背景图。 */
    fun importPlayerBackground(uri: Uri) {
        viewModelScope.launch {
            val path = settingsStore.copyToInternalFile(uri, "player_background.jpg")
            settingsStore.setPlayerBackground(path)
        }
    }

    /** 存框选好的播放页背景图（存完 recycle，别让大图挂在内存里）。 */
    fun saveCroppedPlayerBackground(bitmap: android.graphics.Bitmap) {
        viewModelScope.launch(Dispatchers.IO) {
            val path = com.puremusic.ui.components.saveBitmapAsJpeg(
                context, bitmap, "player_background.jpg",
            )
            bitmap.recycle()
            if (path != null) settingsStore.setPlayerBackground(path)
        }
    }

    fun clearPlayerBackground() {
        viewModelScope.launch { settingsStore.setPlayerBackground(null) }
    }

    fun setSpeed(value: Float) = playerManager.setSpeed(value)
    fun setSleepTimer(minutes: Int) = playerManager.setSleepTimer(minutes)
    fun cancelSleepTimer() = playerManager.cancelSleepTimer()

    fun toggleFavorite(song: Song) {
        viewModelScope.launch { repository.toggleFavorite(song) }
    }

    fun addToPlaylist(playlistId: String, song: Song) {
        viewModelScope.launch { repository.appendSongs(playlistId, listOf(song)) }
    }

    fun createPlaylistWith(name: String, song: Song) {
        viewModelScope.launch { repository.createPlaylist(name.trim(), listOf(song)) }
    }
}
