package com.puremusic.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.puremusic.data.AccountStore
import com.puremusic.data.SettingsStore
import com.puremusic.model.Song
import com.puremusic.model.ThemeConfig
import com.puremusic.playback.PlayerManager
import com.puremusic.source.SourceRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Activity 级 ViewModel：主题、音乐源、账号与播放状态的总入口。 */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
    sourceRegistry: SourceRegistry,
    accountStore: AccountStore,
    private val playerManager: PlayerManager,
) : ViewModel() {

    val themeConfig = settingsStore.themeConfig
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeConfig())

    val backgroundImagePath = settingsStore.backgroundImagePath
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val sources = sourceRegistry.all

    val accounts = accountStore.accounts

    val currentSong = playerManager.currentSong
    val isPlaying = playerManager.isPlaying
    val positionMs = playerManager.positionMs
    val durationMs = playerManager.durationMs

    /** 全局封面主色（ARGB Long，0L 表示无封面/未播放）。各页通过 animateColorAsState 平滑过渡。 */
    private val _coverSeedColor = MutableStateFlow(0L)
    val coverSeedColor: StateFlow<Long> = _coverSeedColor.asStateFlow()

    fun updateCoverSeed(color: Long) {
        _coverSeedColor.value = color
    }

    init {
        playerManager.init()
    }

    fun togglePlayPause() = playerManager.togglePlayPause()

    fun play(song: Song, context: List<Song> = emptyList()) = playerManager.play(song, context)

    fun playQueue(songs: List<Song>, index: Int) = playerManager.playQueue(songs, index)
}
