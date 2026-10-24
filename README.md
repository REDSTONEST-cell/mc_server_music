# DgMusic

Minecraft 全服点歌插件，曲库来自 QQ 音乐。玩家搜歌、点歌后，全服所有玩家都能听到，**客户端无需安装任何模组**。

- 支持 **Paper** 与 **Folia** 1.21.11（同一个 jar）
- 搜索结果可点击直接点播
- 多人点歌自动排队，支持切歌、管理员一键停止、个人静音

## 原理

原版客户端无法直接播放网络音频，因此插件的做法是：

1. 通过 QQ 音乐接口获取歌曲播放链接并下载；
2. 使用 ffmpeg 转码为 OGG Vorbis；
3. 打包成一个只包含这首歌的资源包，由插件内置的 HTTP 服务器提供下载；
4. 推送资源包给所有在线玩家，玩家加载完成后播放这首歌。

由此带来的限制：

- 玩家需要**接受服务器资源包**，每次切歌客户端都会重新加载一次资源（屏幕会短暂卡顿 1~3 秒）；
- 中途进服的玩家会从头开始播放当前歌曲（可在配置中关闭）；
- 每位玩家各自在资源包加载完成时开始播放，相互之间会有几秒误差。

## 安装

1. 服务器安装 **ffmpeg**（需要带 libvorbis，常见发行版自带的即可）：
   - Debian/Ubuntu：`apt install ffmpeg`
   - CentOS/RHEL：通过 RPM Fusion 安装，或下载静态编译版本
   - Windows：下载 ffmpeg 后在 `config.yml` 的 `audio.ffmpeg` 填写 `ffmpeg.exe` 的完整路径
2. 将 `DgMusic-x.x.x.jar` 放入 `plugins` 目录，启动一次服务器生成配置文件。
3. 编辑 `plugins/DgMusic/config.yml`：
   - `qq.cookie`：填写 QQ 音乐 Cookie（见下文）；
   - `http.public-url`：填写**玩家能访问到**的地址，例如 `http://123.45.67.89:8765`；
   - 在防火墙/安全组中放行 `http.port`（默认 8765，TCP）。
4. 执行 `/dgmusic reload` 或重启服务器。

### 获取 QQ 音乐 Cookie

1. 用电脑浏览器打开 <https://y.qq.com> 并登录（建议使用开通了会员的账号，否则 VIP 歌曲无法播放）；
2. 按 `F12` 打开开发者工具 → 「网络 / Network」，刷新页面，随便点开一个发往 `y.qq.com` 的请求；
3. 在「请求标头 / Request Headers」中找到 `Cookie`，复制整段值，填入 `qq.cookie`。

Cookie 中至少需要包含 `uin` 与 `qqmusic_key`（或 `qm_keyst`）。Cookie 一般几天到几周会失效，失效后播放时会提示「QQ音乐登录已失效」，重新复制一次并 `/dgmusic reload` 即可。

> ⚠️ Cookie 等同于你的账号登录凭证，**不要发给任何人，也不要提交到公开仓库**。

## 指令

| 指令 | 说明 | 权限 | 默认 |
|---|---|---|---|
| `/dgss <歌曲名>` | 搜索歌曲，弹出候选列表（可点击） | `dgmusic.search` | 所有人 |
| `/dgxz <序号>` | 点播搜索结果中的歌曲 | `dgmusic.select` | 所有人 |
| `/dgqg` | 切歌，跳过当前歌曲 | `dgmusic.skip` | 所有人 |
| `/dgstop` | 停止所有播放并清空队列 | `dgmusic.stop` | OP |
| `/dgmusic list` | 查看点歌队列 | `dgmusic.list` | 所有人 |
| `/dgmusic mute` | 开关自己的点歌播放 | `dgmusic.mute` | 所有人 |
| `/dgmusic reload` | 重载配置 | `dgmusic.reload` | OP |

`dgmusic.bypass`（默认 OP）：无视搜索冷却和个人排队数量上限。

如果只想让管理员切歌，用权限插件（如 LuckPerms）取消默认组的 `dgmusic.skip` 即可。

## 配置

详见 [`config.yml`](src/main/resources/config.yml) 中的注释，常用项：

- `queue.max-size` / `queue.max-per-player`：队列总长度 / 每人最多排队数；
- `audio.max-duration`：允许点播的最长歌曲时长；
- `audio.ogg-quality`：音质，越高资源包越大，玩家下载越慢；
- `playback.sound-source`：使用客户端哪个音量滑块，默认「唱片机/音符盒」。

## 构建

需要 JDK 21：

```bash
./gradlew build
```

产物位于 `build/libs/DgMusic-<版本>.jar`。

## 免责声明

本插件通过 QQ 音乐网页端的非官方接口获取数据，仅供个人学习与非商业的私人服务器使用。歌曲版权归 QQ 音乐及版权方所有。使用 Cookie 调用接口可能违反 QQ 音乐用户协议，账号存在被风控的风险，请自行承担。
