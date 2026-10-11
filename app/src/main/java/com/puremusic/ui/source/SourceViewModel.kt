package com.puremusic.ui.source

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.puremusic.data.AccountStore
import com.puremusic.db.MusicRepository
import com.puremusic.model.Account
import com.puremusic.model.MusicSourceType
import com.puremusic.model.Playlist
import com.puremusic.source.MusicSource
import com.puremusic.source.SourceRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 单个平台的「云端音乐库」页面。
 *
 * 需求核心是「分化」：点 QQ 音乐看到的就只是 QQ 音乐的云端歌单，点网易云看到的就只是网易云的，
 * 不再把所有平台的歌单混在一张列表里。
 */
@HiltViewModel
class SourceViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sourceRegistry: SourceRegistry,
    private val accountStore: AccountStore,
    repository: MusicRepository,
) : ViewModel() {

    val sourceId: String = savedStateHandle["sourceId"] ?: ""

    val source: MusicSource? = sourceRegistry.byId(sourceId)

    val type: MusicSourceType = source?.type ?: MusicSourceType.LOCAL

    /** 当前平台登录态（null = 未登录）。 */
    val account: StateFlow<Account?> = accountStore.accounts
        .map { it[type] }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** 本地平台的自建歌单（Room 驱动，自动刷新）。 */
    val localPlaylists: StateFlow<List<Playlist>> =
        repository.observeLocalPlaylists()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 云端歌单。 */
    var playlists by mutableStateOf(emptyList<Playlist>())
        private set

    var loading by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    /** 是否已完成一次加载（用于区分「加载中」和「真的没有」）。 */
    var loaded by mutableStateOf(false)
        private set

    init {
        refresh()
    }

    fun refresh() {
        val s = source ?: return
        if (s.type == MusicSourceType.LOCAL) {
            loaded = true
            return
        }
        viewModelScope.launch {
            accountStore.awaitLoaded()
            val acc = accountStore.accounts.value[s.type]
            if (acc == null) {
                playlists = emptyList()
                error = null
                loading = false
                loaded = true
                return@launch
            }
            loading = true
            error = null
            runCatching { s.getUserPlaylists() }
                .onSuccess {
                    playlists = it.withFavoriteEntry(s.type)
                    error = if (playlists.isEmpty()) "没有获取到云端歌单" else null
                }
                .onFailure { e ->
                    playlists = emptyList()
                    error = e.message?.takeIf { it.isNotBlank() } ?: "加载云端歌单失败"
                }
            loading = false
            loaded = true
        }
    }

    /**
     * QQ 的「我喜欢的」不在 GetPlaylistByUin 返回的普通歌单里（它是 dirid=201 的特殊歌单），
     * 这里显式补一个入口，否则用户永远点不到自己收藏最多的那张。
     */
    private fun List<Playlist>.withFavoriteEntry(type: MusicSourceType): List<Playlist> {
        if (type != MusicSourceType.QQ || isEmpty()) return this
        if (any { it.name.contains("喜欢") }) return this
        return listOf(
            Playlist(
                id = QQ_FAVORITE_ID,
                name = "我喜欢的",
                source = type,
            ),
        ) + this
    }

    fun logout() {
        viewModelScope.launch {
            source?.logout()
            playlists = emptyList()
            loaded = false
        }
    }

    private companion object {
        /** QQ「我喜欢的」特殊歌单 dirid。 */
        const val QQ_FAVORITE_ID = "201"
    }
}
