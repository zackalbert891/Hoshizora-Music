# Hoshizora 架构设计

## 1. 分层总览

```
┌────────────────────────────────────────────────────────────┐
│  UI 层（Compose）                                          │
│  Home / Search / Library / Playlist / NowPlaying / Settings │
└───────────────┬────────────────────────────────────────────┘
                │ 观察 StateFlow / 调用方法
┌───────────────▼────────────────────────────────────────────┐
│  ViewModel 层（Hilt）                                       │
│  Main / Search / Library / Playlist / NowPlaying / Settings │
└───────┬───────────────────────────┬────────────────────────┘
        │                           │
┌───────▼────────────┐   ┌──────────▼──────────────┐
│ Source 插件层       │   │ 播放引擎                │
│ MusicSource 接口    │   │ PlayerManager +         │
│ ├ LocalMusicSource │   │ PlaybackService(Media3) │
│ └ 多个在线源        │   └──────────▲──────────────┘
└───────┬────────────┘              │ 需要直链时调 getSongUrl()
        │ 持久化                    │
┌───────▼────────────┐   ┌──────────▼──────────────┐
│ 数据层               │   │ 模型层 model/           │
│ SettingsStore       │   │ Song/Playlist/Account/  │
│ AccountStore        │   │ ThemeConfig/LyricLine   │
└─────────────────────┘   └─────────────────────────┘
```

## 2. 核心设计决策

### 2.1 音乐源插件系统（"极高可自定义性"的核心）

所有平台实现统一的 [`MusicSource`](../app/src/main/java/com/puremusic/source/MusicSource.kt) 接口：

```kotlin
interface MusicSource {
    val type: MusicSourceType
    val displayName: String
    val supportsLogin: Boolean
    suspend fun search(...): List<Song>
    suspend fun getSongUrl(song: Song): String?
    suspend fun getLyrics(song: Song): Lyrics
    suspend fun getUserPlaylists(): List<Playlist>
    suspend fun getPlaylistDetail(playlist: Playlist): List<Song>
    suspend fun login(phone, password): Account
    suspend fun logout()
}
```

**新增一个平台只需三步：**

1. 在 `MusicSourceType` 加一个枚举值；
2. 实现 `MusicSource` 接口；
3. 在 `SourceRegistry` 构造函数里注册。

上层 UI / 播放器通过 `Song` 这个统一模型工作，对具体平台无感知。`Song` 里保留 `source` 与 `extra` 字段用于回源解析直链、歌词等。

### 2.2 播放引擎

- `PlaybackService : MediaSessionService`：持有 `ExoPlayer`，由 Media3 自动提升为前台服务并生成媒体通知，保证后台 / 锁屏 / 蓝牙播放。
- `PlayerManager`（单例）：通过 `MediaController` 连接服务，对外暴露 `currentSong / isPlaying / positionMs / durationMs / queue` 等 `StateFlow`，UI 只观察这些状态，不直接碰播放器。
- 播放时先经 `MusicSource.getSongUrl()` 解析直链（本地音乐直接用 `content://` URI），再构建 `MediaItem`。

### 2.3 主题系统（可自定义性）

`ThemeConfig`（`DarkMode` + `dynamicColor` + `seedColor` + `amoledBlack`）持久化于 DataStore。
`PureMusicTheme` 按优先级解析配色：

1. 跟随系统 Material You 动态取色（Android 12+）；
2. AMOLED 纯黑；
3. 自定义种子色。

扩展方向：字体、圆角、间距、完整 tonal palette（可引入 `materialkolor` 库）。

## 3. 音源接口鉴权

各在线源接口采用各自的签名 / 鉴权方案，相关实现集中在 `source/<平台>/` 包内，此处不展开。
关键点：

- 登录态由 `AccountStore` 缓存并在网络层自动附加，未登录时仅能使用免登录的接口；
- 取流类接口通常需要携带登录态，否则只能拿到试听片段；
- 部分写接口（如评论点赞）对客户端身份与风控敏感，需按移动端身份调用。

## 4. 数据流示例（搜索 → 播放）

```
用户输入关键词 → SearchViewModel.search()
  → SourceRegistry.byId(type).search()      # 各源统一入口
  → List<Song>
  → 点击结果 → PlayerManager.play(song)
      → getSongUrl(song)                    # 解析直链
      → 构建 MediaItem → MediaController.setMediaItems + play
      → PlaybackService(ExoPlayer) 播放 + 通知
```

## 5. 扩展点清单

- [x] 在线源直链 / 歌词 / 登录
- [x] 本地歌单持久化 / 收藏（Room：自建歌单、我喜欢的、播放历史）
- [x] Room 数据库（`db/` 包：`AppDatabase` + 三个 DAO + `MusicRepository`）
- [ ] 均衡器（播放速度、音质切换已完成，见 [6.2](#62-播放器增强)）
- [x] 更多主题维度：字体缩放、圆角风格、布局密度（完整 tonal palette 未做）
- [x] 二维码登录（本地用 ZXing 生成二维码）
- [x] 音源登录态取流（VIP 曲目）：透传登录 Cookie，避免匿名通道只能拿试听片段

## 6. 数据持久化

### 6.1 Room（`db/`）

```
AppDatabase (puremusic.db, version 1)
├── playlists          # 本地自建歌单
├── playlist_songs     # 歌单内歌曲，主键 (playlistId, mediaId)，外键级联删除
├── favorites          # 我喜欢的，主键 mediaId
└── history            # 播放历史，主键 mediaId，重复播放累加 playCount
```

要点：

- 歌曲以**快照**形式落库（`SongSnapshot`），展示字段不依赖回源，离线也能渲染歌单。
- `Song.extra` 用 Gson 序列化为 `extraJson`，保留回源解析直链 / 歌词所需字段。
- UI 只依赖 `MusicRepository`，不直接碰 DAO；`observeXxx()` 返回 Flow，数据变更自动刷新界面。

### 6.2 播放器增强

`PlayerManager` 对外新增两个状态与对应方法：

- `speed: StateFlow<Float>` / `setSpeed()`：0.5x ~ 2.0x，通过 `PlaybackParameters` 生效。
- `sleepTimerEndMs: StateFlow<Long>` / `setSleepTimer(minutes)`：到点自动暂停（协程 `delay`，取消即归零）。

播放历史由 `NowPlayingViewModel` 在切歌时写入 `MusicRepository.recordPlay()`。
