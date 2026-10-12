package com.puremusic.ui.entity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.puremusic.data.AccountStore
import com.puremusic.model.MusicSourceType
import com.puremusic.model.Song
import com.puremusic.playback.PlayerManager
import com.puremusic.source.netease.NeteaseEntity
import com.puremusic.source.netease.NeteasePlayRecord
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 听歌排行页状态。 */
data class PlayRecordUiState(
    val records: List<NeteasePlayRecord> = emptyList(),
    val weekly: Boolean = false,
    val loading: Boolean = false,
    /** 未登录时给出明确提示，而不是空白页。 */
    val needLogin: Boolean = false,
    val error: String? = null,
)

/**
 * 听歌排行（网易云 `/api/v1/play/record`）。
 *
 * 两个硬约束（2026-10-12 实测）：
 * 1. 必须带 `type`（0=所有时间 / 1=本周），漏了服务端直接返回 `-2 无权限访问`；
 * 2. 必须带登录 Cookie，匿名同样 -2。
 *
 * 服务端**只给次数、不给时间戳**，所以这里只能排行；「最近播放」用本地历史
 * （音乐库 → 播放历史），别在这页假装能按时间排序。
 */
@HiltViewModel
class PlayRecordViewModel @Inject constructor(
    private val entity: NeteaseEntity,
    private val accountStore: AccountStore,
    private val playerManager: PlayerManager,
) : ViewModel() {

    private val _state = MutableStateFlow(PlayRecordUiState())
    val state: StateFlow<PlayRecordUiState> = _state.asStateFlow()

    private var loaded = false

    fun load(force: Boolean = false) {
        if (loaded && !force) return
        loaded = true
        fetch(weekly = false)
    }

    /** 切换「本周 / 所有时间」。 */
    fun setWeekly(weekly: Boolean) {
        if (_state.value.weekly == weekly) return
        _state.value = _state.value.copy(weekly = weekly)
        fetch(weekly)
    }

    private fun fetch(weekly: Boolean) {
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            accountStore.awaitLoaded()
            val account = accountStore.accounts.value[MusicSourceType.NETEASE]
            val cookie = account?.cookie?.takeIf { it.isNotBlank() }
            if (cookie == null) {
                _state.value = PlayRecordUiState(weekly = weekly, needLogin = true)
                return@launch
            }
            val uid = account.userId.takeIf { it.isNotBlank() }
                ?: Regex("(?:^|;)\\s*uid=([^;]*)").find(cookie)?.groupValues?.get(1)
                    ?.takeIf { it.isNotBlank() && it != "0" }
            if (uid == null) {
                _state.value = _state.value.copy(loading = false, error = "读不到账号 uid，重新登录一次即可")
                return@launch
            }
            val list = runCatching { entity.playRecord(uid, weekly, cookie = cookie) }
                .getOrDefault(emptyList())
            _state.value = PlayRecordUiState(
                records = list,
                weekly = weekly,
                loading = false,
                error = if (list.isEmpty()) "暂无听歌记录（云端排行需要手机/客户端产生过播放）" else null,
            )
        }
    }

    /** 播放整榜（按次数降序即为顺序）。 */
    fun playAll() {
        val rows = _state.value.records.map { it.song }
        if (rows.isNotEmpty()) playerManager.play(rows.first(), rows)
    }

    fun play(song: Song) {
        val rows = _state.value.records.map { it.song }
        playerManager.play(song, rows)
    }
}