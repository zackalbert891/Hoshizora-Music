package com.puremusic.data

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.puremusic.model.CornerStyle
import com.puremusic.model.DarkMode
import com.puremusic.model.LayoutDensity
import com.puremusic.model.ThemeConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/** 主题、服务器地址、背景图等设置的持久化。 */
@Singleton
class SettingsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val DARK_MODE = stringPreferencesKey("dark_mode")
        val FONT_SCALE = floatPreferencesKey("font_scale")
        val CORNER_STYLE = stringPreferencesKey("corner_style")
        val LAYOUT_DENSITY = stringPreferencesKey("layout_density")
        val SERVER_URL = stringPreferencesKey("server_url")
        val BACKGROUND_IMAGE = stringPreferencesKey("background_image")
        val LIQUID_GLASS = booleanPreferencesKey("liquid_glass")
        val BLUR_STRENGTH = floatPreferencesKey("blur_strength")
        /** 播放页单独的背景图（不跟随音乐库/设置页那张）。 */
        val PLAYER_BACKGROUND = stringPreferencesKey("player_background")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val themeConfig: Flow<ThemeConfig> = context.settingsDataStore.data.map { p ->
        ThemeConfig(
            darkMode = runCatching { DarkMode.valueOf(p[Keys.DARK_MODE] ?: "SYSTEM") }
                .getOrDefault(DarkMode.SYSTEM),
            fontScale = p[Keys.FONT_SCALE] ?: 1.0f,
            cornerStyle = enumOf(p[Keys.CORNER_STYLE], CornerStyle.LARGE),
            layoutDensity = enumOf(p[Keys.LAYOUT_DENSITY], LayoutDensity.NORMAL),
            liquidGlass = p[Keys.LIQUID_GLASS] ?: true,
            blurStrength = (p[Keys.BLUR_STRENGTH] ?: 0.45f).coerceIn(0f, 1f),
        )
    }

    /** 播放页自定义背景图路径；null = 用封面做背景。 */
    val playerBackgroundPath: Flow<String?> = context.settingsDataStore.data
        .map { it[Keys.PLAYER_BACKGROUND] }

    val serverUrl: Flow<String> = context.settingsDataStore.data
        .map { it[Keys.SERVER_URL] ?: DEFAULT_SERVER_URL }

    val backgroundImagePath: Flow<String?> = context.settingsDataStore.data
        .map { it[Keys.BACKGROUND_IMAGE] }

    /** 供网络拦截器同步读取的服务器地址快照。 */
    @Volatile
    var currentServerUrl: String = DEFAULT_SERVER_URL
        private set

    init {
        scope.launch {
            serverUrl.collect { currentServerUrl = it }
        }
    }

    suspend fun setThemeConfig(config: ThemeConfig) {
        context.settingsDataStore.edit { p ->
            p[Keys.DARK_MODE] = config.darkMode.name
            p[Keys.FONT_SCALE] = config.fontScale
            p[Keys.CORNER_STYLE] = config.cornerStyle.name
            p[Keys.LAYOUT_DENSITY] = config.layoutDensity.name
            p[Keys.LIQUID_GLASS] = config.liquidGlass
            p[Keys.BLUR_STRENGTH] = config.blurStrength.coerceIn(0f, 1f)
        }
    }

    suspend fun setPlayerBackground(path: String?) {
        context.settingsDataStore.edit { p ->
            if (path == null) p.remove(Keys.PLAYER_BACKGROUND) else p[Keys.PLAYER_BACKGROUND] = path
        }
    }

    /** 把用户选的图拷进私有目录（content:// 的 Uri 只有临时读权限，不能直接存路径）。 */
    suspend fun copyToInternalFile(uri: Uri, fileName: String): String? = withContext(Dispatchers.IO) {
        val dest = File(context.filesDir, fileName)
        val ok = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } != null
        }.getOrDefault(false)
        if (ok) dest.absolutePath else null
    }

    suspend fun setServerUrl(url: String) {
        context.settingsDataStore.edit { p -> p[Keys.SERVER_URL] = url }
    }

    suspend fun setBackgroundImage(path: String?) {
        context.settingsDataStore.edit { p ->
            if (path == null) p.remove(Keys.BACKGROUND_IMAGE) else p[Keys.BACKGROUND_IMAGE] = path
        }
    }

    companion object {
        const val DEFAULT_SERVER_URL = "http://127.0.0.1:3000"
    }
}

/** 解析枚举，失败（含旧版本数据）回退默认值。 */
private inline fun <reified T : Enum<T>> enumOf(name: String?, fallback: T): T =
    if (name == null) fallback
    else runCatching { enumValues<T>().first { it.name == name } }.getOrDefault(fallback)
