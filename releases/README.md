# 归档版本

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

- **评论点赞真的写进网易云**（`weapi/v1/comment/{like|unlike}`），不再是刷新就掉的本地假反馈；
  真机闭环验证：点赞 → 复查 `liked=true` → 取消。
- 点赞风控修复：写接口必须伪装安卓客户端（`os=android` + 安卓 UA），自称 `os=pc` 一律 code 250「设备存在安全风险」。
- 未登录时不再假装点上，改为登录引导条；失败回滚并提示。
- 网易云一键登录（WebView 官网）+ 登录页重做。
- 已移除下载功能。
- 其他：底部白条修复、液态玻璃导航圆钮、歌词页内样式、播放模式三态、跨源自动换源。

### 说明

APK 本身不进 git（`.gitignore` 排除 `*.apk`），此目录只有说明与校验文件入库。
代码侧用 `git tag v1.0.0-phone` 标记对应提交——之后仓库只改了 README 与 `.gitignore`，
不影响 APK 内容。
