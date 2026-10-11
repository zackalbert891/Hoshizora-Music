package com.puremusic.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.puremusic.model.Account
import com.puremusic.model.MusicSourceType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

private val Context.accountsDataStore by preferencesDataStore(name = "accounts")

/** 各平台账号的持久化与内存缓存。 */
@Singleton
class AccountStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 注意：必须先于 init 块初始化，否则 init 里启动的协程会读到 null。
    private val persistedAccounts: Flow<Map<MusicSourceType, Account>> =
        context.accountsDataStore.data.map { p ->
            buildMap {
                p.asMap().forEach { (_, value) ->
                    val json = value as? String ?: return@forEach
                    runCatching { gson.fromJson(json, Account::class.java) }
                        .getOrNull()
                        ?.let { put(it.source, it) }
                }
            }
        }

    private val _accounts = MutableStateFlow<Map<MusicSourceType, Account>>(emptyMap())
    val accounts: StateFlow<Map<MusicSourceType, Account>> = _accounts.asStateFlow()

    private val _neteaseCookie = MutableStateFlow<String?>(null)
    val neteaseCookie: StateFlow<String?> = _neteaseCookie.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    /** 是否已完成首次读取（避免把「尚未加载」误判为未登录）。 */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /** 供网络拦截器同步读取的 cookie 快照。 */
    @Volatile
    var currentNeteaseCookie: String? = null
        private set

    init {
        scope.launch {
            persistedAccounts.collect { map ->
                _accounts.value = map
                _neteaseCookie.value = map[MusicSourceType.NETEASE]?.cookie
                currentNeteaseCookie = _neteaseCookie.value
                if (!_loaded.value) _loaded.value = true
            }
        }
    }

    /**
     * 等待首次持久化读取完成。
     *
     * 必须调用：DataStore 的 collect 是异步的，冷启动后若立刻取流，
     * [accounts] 仍是空 map —— 表现为「明明已登录，却按匿名取流，VIP/版权歌全部失败」。
     * 各音源取流前统一先 awaitLoaded()，超时则放弃等待（不阻塞播放）。
     */
    suspend fun awaitLoaded(timeoutMs: Long = 3000L) {
        if (_loaded.value) return
        runCatching {
            withTimeoutOrNull(timeoutMs) { _loaded.first { it } }
        }
    }

    suspend fun save(account: Account) {
        context.accountsDataStore.edit { p ->
            p[stringPreferencesKey(account.source.id)] = gson.toJson(account)
        }
    }

    suspend fun remove(source: MusicSourceType) {
        context.accountsDataStore.edit { p -> p.remove(stringPreferencesKey(source.id)) }
    }
}
