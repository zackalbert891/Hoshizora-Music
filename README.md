# Hoshizora · 星空

纯净 · 流畅 · 高度自定义的安卓音乐播放器。Kotlin + Jetpack Compose 构建，支持本地媒体库、网易云 / QQ / 酷我多音源聚合、在线歌词与跨源自动换源。

> **声明 / Disclaimer**
>
> 本项目是个人学习用途的 Android 客户端，不隶属于、也未获得网易云音乐、QQ 音乐、酷我音乐等任何平台的授权。
> 仓库中的音源接口均为对**公开可访问 HTTP 接口**的逆向分析与实现，仅用于技术学习；
> **不提供、不包含任何音乐下载、付费内容破解或 DRM 解密功能**（下载功能已从项目中移除）。
> 所有音乐内容的版权归各自权利人所有，播放行为发生在用户本人的账号与设备上。
> 若你是相关权利人并认为本项目侵犯你的权益，请通过 Issue 联系，我将在 24 小时内删除相关内容。


## 功能特性

- **本地音乐**：扫描系统媒体库（MediaStore），离线播放。
- **多音源聚合**：
  - **网易云音乐**：搜索、云端歌单、歌词、一键登录（WebView 官网登录）/ 扫码 / 手机号验证码、评论与真实点赞。
  - **QQ 音乐**：搜索、歌词、评论、我的歌单与歌单详情、扫码登录 / 手机号验证码登录、凭证自动续期与匿名降级。
  - **酷我音乐**：免登录搜索 / 取流 / 歌词（kw_token 自动刷新，新版接口失败自动回落旧版）。
  - **咪咕 / 千千 / 酷狗**：免登录搜索与取流补充，供跨源自动换源使用。
- **播放页全面屏适配**：`enableEdgeToEdge` + `safeDrawing` 避让，HorizontalPager 封面 ↔ 歌词双页切换。
- **歌词**：LRC 解析 + 逐行滚动高亮 + 两级缓存（内存 + 磁盘源:真实id 键控）。
- **跨源自动换源**：原源取流失败 / 播放运行时出错时，自动在其它音源搜索同曲候选并切换（标题/歌手 bigram 相似度评分，坏 URL 30 分钟失败缓存 + Range 校验）。
- **本地音乐库（Room 持久化）**：自建歌单（增删歌 / 删除歌单）、「我喜欢的」收藏、播放历史，全部落库，重启不丢；在线歌单里的歌可一键收藏或加入本地歌单。
- **音乐库首页**：大标题 + 圆角搜索栏 + 渐变色快捷入口（我喜欢的 / 播放历史）+ 「最近播放」横滑卡片（点封面直接续播）+ 歌单两列网格。
- **播放控制**：倍速 0.5x ~ 2.0x、睡眠定时（10/15/30/60 分钟后自动暂停）。
- **高度自定义主题**：跟随系统 / 浅色 / 深色、Material You 动态取色、自定义种子色、AMOLED 纯黑、字体大小（小/标准/大）、圆角风格（标准/大圆角/超大）、布局密度（紧凑/标准/宽松）。
- **前台播放**：Media3 前台服务 + 媒体通知，支持后台 / 锁屏播放。

## 技术栈

| 层 | 选型 |
|---|---|
| 语言 / UI | Kotlin 2.0.21 · Jetpack Compose · Material 3 |
| 构建 | AGP 8.5.2 · Gradle 8.9 · JDK 17 |
| 播放 | Media3 ExoPlayer 1.4.1 + MediaSession |
| 依赖注入 | Hilt 2.52 (KSP) |
| 网络 | OkHttp 4.12 · Gson 2.11 |
| 存储 | DataStore Preferences · Room 2.6.1 (KSP) |
| 图片 | Coil 2.7 |

## 目录结构

```
Hoshizora/
├── README.md
├── LICENSE                      # MIT License
├── docs/ARCHITECTURE.md         # 架构设计文档
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/
│   ├── libs.versions.toml       # 版本目录
│   └── wrapper/gradle-wrapper.properties
├── .github/workflows/android-build.yml   # GitHub Actions 构建
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── res/…
        └── java/com/puremusic/
            ├── App.kt / MainActivity.kt
            ├── model/             # Song / Playlist / Account / ThemeConfig / LyricLine
            ├── source/            # 音乐源插件层（local / netease / qq / kuwo / external）
            │   └── autoswitch/    # AutoSwitchSource 跨源自动换源
            ├── data/              # SettingsStore / AccountStore / PluginStore
            ├── db/                # Room：AppDatabase / 各 Entity / DAO / MusicRepository
            ├── playback/          # PlaybackService / PlayerManager
            ├── lyrics/            # LrcParser / LyricCache
            ├── di/                # AppModule / DatabaseModule
            └── ui/                # theme + 各页面 + ViewModel（含 login）
```

## 构建与运行

**环境要求**：JDK 17（AGP 8.5 要求；系统 JDK 26 过新不兼容）、Gradle 8.9（仓库已含 wrapper）。

```bash
# 方式一：命令行（离线依赖已就绪时）
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk

# 方式二：Android Studio
# 用 JDK 17 打开本目录 → 等待 Gradle 同步 → Run
```

> 仓库内 `settings.gradle.kts` 已配置阿里云镜像优先、官方仓库兜底，首次构建需联网拉取依赖。

**关于代理**：`gradle.properties` 的 `org.gradle.jvmargs` 中带了本机 HTTP 代理（`127.0.0.1:8298`）与
`-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT`（让 JVM 直接信任 Windows 系统证书库，解决代理环境下的
PKIX 证书链错误）。若无代理或使用系统 JDK，请删掉这几个 `-D` 参数：

```properties
org.gradle.jvmargs=-Xmx4096m -Dfile.encoding=UTF-8 -XX:+UseParallelGC
```

**构建命令（本机实测可用）**：

```bash
export JAVA_HOME="E:/android-env/jdk17/jdk-17.0.20+8"
export ANDROID_HOME="E:/android-env/sdk"
"E:/android-env/gradle-dist/gradle-8.9/bin/gradle" assembleDebug
```

安装到设备：`adb install -r app/build/outputs/apk/debug/app-debug.apk`

## 音源说明与免责声明

QQ 音乐与网易云音乐**均未开放官方公开 API**，本项目接入方式：

- **网易云**：实现 `weapi` 签名（AES-128-CBC + RSA），直接调用 `music.163.com` 接口。
- **QQ 音乐**：`c.y.qq.com` JSONP 搜索接口、`u.y.qq.com` musicu.fcg CGI 取流 / 登录（扫码 / 手机号验证码）、`i.y.qq.com` 歌词接口。
- **酷我音乐**：`www.kuwo.cn` / `search.kuwo.cn` / `antiserver.kuwo.cn` / `m.kuwo.cn` 公开接口。

以上接入方式属于**个人学习 / 自用性质**，存在明确的合规与稳定性风险：

1. 接口随时可能失效、变更或触发风控，本项目不保证任何音源的持续可用性。
2. 请勿将本项目用于任何商业用途或应用商店分发。
3. 播放内容版权归各平台与版权方所有，请在下载/播放后 24 小时内删除，切勿传播。
4. 因使用本项目产生的任何法律风险由使用者自行承担，作者不承担任何责任。

## 致谢

- [Mint-Music](https://github.com/xiaoyvyv/Mint-Music) —— QQ 匿名取流 / 凭证续期思路参考
- [lxmusic-android](https://github.com/lxmusics/lxmusic-android) —— 播放与架构参考
- [QingMusic](https://github.com/hellodword/QingMusic) / [AlgerMusicPlayer](https://github.com/AlgerFan/AlgerMusicPlayer) —— UI 与体验参考
- [luren-dc/QQMusicApi](https://github.com/luren-dc/QQMusicApi) —— QQ 登录 / 续期协议参考

## License

[MIT](LICENSE)
*（内容由AI生成，仅供参考）*
