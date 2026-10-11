# 归档版本

## Hoshizora-v1.3.0-slim-20261011-2111.apk（当前最新，已装机）

精简设置页与登录方式，净删 510 行（141 增 / 651 删）。

| 项 | 值 |
|---|---|
| versionName / versionCode | 1.0.0 / 2（`build.gradle.kts` 真实值；文件名里的 v1.3.0 是归档代号） |
| 构建时间 | 2026-10-11 21:11 |
| 大小 | 16529292 B（15.8 MB） |
| MD5 | `6b34d20cba5959dd56df78746103b5db` |
| SHA-256 | 见同名 `.sha256` |
| 对应 git tag | `v1.3.0-phone`（提交 `650ebdf`） |

### 这一版删了什么

**登录方式**（`NeteaseLoginTab` 只剩 `QR` / `COOKIE`）

- 「一键登录」（WebView 官网）——实测 WebView 里拿不到 `MUSIC_U`，点不动的死入口
- 「手机号登录」（短信 / 密码）——被 -460/-462 动态风控拦死
- 连带删掉 `NeteaseAuth` 的 `sendCaptcha` / `cellphoneLogin` / `parseLogin` / `riskHint`
- **风控验证页的 WebView 保留**（`buildRiskWebView`），那是登录页唯一还在用的 WebView
- 设置页网易云菜单文案「一键登录」→「扫码登录」

**设置页外观**

- 「音质 / SVIP 无损音质」——开关删了 QQ 取流会永远拿 false，改为 FLAC 直接排取流列表首位
- 「跟随系统取色」（`dynamicColor`）与「AMOLED 纯黑背景」（`amoledBlack`）
- 「主题色」色点区（`SeedPresets.kt` 整个文件删除）
- `ThemeConfig` 去掉 `dynamicColor` / `seedColor` / `amoledBlack` 三个字段 ——
  前两个本来就没用：`AppRoot` 恒传封面主色进去，`seedColor` 永远被盖住，
  所以「换了主题色没反应」。AMOLED 则是留着字段的话，老用户已开的开关状态删不掉。

**滑杆拇指**

- M3 默认 thumb 是竖直胶囊，看着像根小杠 → 改 18dp 圆粒（白色描边）
- track 同时去掉两端停靠点圆点（`drawStopIndicator = null`）+ `thumbTrackGapSize = 0`，
  否则三个橙点并排像坏掉了

## Hoshizora-v1.2.0-comment-20261011-1956.apk

网易云评论区**发表 / 回复 / 删除**全部打通，真机闭环验证：发一条 → 复查服务端已落库 → 删掉。

| 项 | 值 |
|---|---|
| versionName / versionCode | 1.0.0 / 2（`build.gradle.kts` 里的真实值；**文件名中的 v1.2.0 只是归档代号**，应用内版本号一直没动过） |
| 构建时间 | 2026-10-11 19:56 |
| 大小 | 16545676 B（15.8 MB） |
| MD5 | `f29b2cb4740d018da6997b52f0a2dd74` |
| SHA-256 | `ed8a39ab2d82b1200a58c24693566425e8f16aa0b6845fb4ee24df68bb4639aa` |
| 签名 | debug keystore（可覆盖安装，保留数据） |

### 这一版新增

- **发评论**：底部固定输入栏 → 发送，写 `/resource/comments/add` 真实入库。
- **回复**：评论卡片「回复」胶囊 → 楼中楼回复（`replyCommentId` + `replyToUserId`）。
- **删除自己的评论**：卡片上的删除按钮 → `/resource/comments/delete`。
- **表情面板**：网易云方括号表情（`[开心]` 等，纯文本入库，任何端一致）+ Unicode emoji。
  网易云表情列表接口已下线，故不依赖服务端。
- 歌单评论区**整体删除**（入口 / 路由 / `kind` 参数 / `R_PL_0_` 前缀逻辑全部清干净），
  实测网易云对非官方客户端返回 200 但 total=0，不做假数据。

### 接口实测备忘（写接口铁律）

发评论路径**不是** `/weapi/comment/add`（404），真路径是 `/weapi/resource/comments/add`。
所有写接口：登录 Cookie + `os=android` + 安卓 UA，`os=pc` 一律 code=250。

## Hoshizora-v1.1.1-crop-20261011-1927.apk

首页去重（删「发现音乐」区块）、发现页推荐只留每日推荐、自定义背景图改为**框选裁剪**
（`ImageCropDialog`：拖动 / 双指缩放 / 三分线 / 重置，大图先降采样）。

## Hoshizora-v1.1.0-discover-20261011-1902.apk

网易云内容发现层：63 张排行榜（榜单即歌单，复用同一详情页）、歌单广场 + 33 个分类、
歌单详情、收藏 / 取消收藏真实同步、每日推荐。

## Hoshizora-v1.0.0-20261011-1204.apk

**这一版就是 2026-10-11 12:04 装机到手机（IFJZWS75TWHYKFIV）上的那一版**，真机验证通过的最后一版。

| 项 | 值 |
|---|---|
| versionName / versionCode | 1.0.0 / 2 |
| 构建时间 | 2026-10-11 12:04 |
| 大小 | 16430988 B（15.7 MB） |
| MD5 | `ea5e9166dd1e04a7eddb0b1feb580c91` |
| SHA-256 | `f27e94ef7101c49415ed8ae8e0fe23123d59d10172ace6208fc532962ec7286c` |
| 签名 | debug keystore（可覆盖安装，保留数据） |
| 对应 git tag | `v1.0.0-phone` |

### 校验方法

```bash
md5sum -c Hoshizora-v1.0.0-20261011-1204.apk.md5
sha256sum -c Hoshizora-v1.0.0-20261011-1204.apk.sha256
```

设备上装的那一版可以这样比对（需连 adb）：

```bash
adb shell pm path com.puremusic
adb exec-out cat <上面的 base.apk 路径> > dev.apk
md5sum dev.apk   # 应等于 ea5e9166dd1e04a7eddb0b1feb580c91
```

### 这一版包含

- **评论点赞真实写服务端**，不再是刷新就掉的本地假反馈；真机闭环验证：点赞 → 复查已点赞 → 取消。
- 修复了部分写接口在移动端的风控拦截（需按移动端身份调用，否则被判定为风险设备）。
- 未登录时不再假装点上，改为登录引导条；失败回滚并提示。
- 一键登录（WebView 官网）+ 登录页重做。
- 已移除下载功能。
- 其他：底部白条修复、液态玻璃导航圆钮、歌词页内样式、播放模式三态、跨源自动换源。

### 说明

APK 已随本目录一起入库（文件名含版本与构建时间），可直接从仓库或
[GitHub Releases](https://github.com/zackalbert891/Hoshizora-Music/releases) 获取。
代码侧用 `git tag v1.0.0-phone` 标记对应提交。
