# macOS Apple Silicon 构建（实验性）

macOS 支持目前面向 Apple Silicon（arm64）。应用仍使用 Kotlin / Compose Desktop，
启动器、内置 Java 运行时、Skiko、JavaFX、SQLite 和 Chromium 均使用 arm64 原生库，无需 Rosetta。
现有 Windows 构建入口保持不变。

## 构建和运行

在 Apple Silicon Mac 的项目根目录执行：

```bash
./build-macos.sh
```

脚本优先使用 `JAVA_HOME` 或系统中可用的 arm64 JDK 17（需包含 `javac`、`jlink` 和 `jpackage`）。
没有可用 JDK 时会将 Temurin 17 下载到项目内 `.tools/`，校验官方 SHA-256 后解包。
Gradle 版本由仓库 wrapper 固定，缓存默认写入项目内 `.gradle-home/`。

默认流程运行全部单元测试，再生成 App 和 DMG。产物位置：

| 产物 | 位置 | 用法 |
| --- | --- | --- |
| App | `release/YunX-Desktop.app` | 双击运行；自带 Java 运行时，无需另装 Java |
| DMG | `release/YunX-Desktop-<版本>-macOS-arm64.dmg` | 打开后将 App 拖入 Applications |

版本号同样取自根目录 `version.txt`。也可以向脚本传入 Gradle 任务：

```bash
./build-macos.sh :desktop:test
./build-macos.sh :desktop:run
./build-macos.sh :desktop:createDistributable
```

首次启动时，内嵌 Chromium 会从随包附带的 macOS arm64 原生 jar 解包至应用缓存。
默认数据目录沿用 `~/.yunx-desktop`；验证或开发时可设置 `YUNX_DESKTOP_DATA_DIR` 使用独立目录。

## macOS 系统集成

- Finder 打开目录与定位文件、系统文件选择器、菜单栏托盘、Cmd+Q 退出。
- 普通档凭证密钥通过 IOPlatformUUID 绑定本机；Windows DPAPI 档在 macOS 不可选。
- Gopeed 自动选择官方 `macos-arm64` 内核；导入后设置可执行权限。
- 系统 HTTP/HTTPS 代理通过 `scutil --proxy` 读取；PAC 和系统代理绕过列表暂不支持。
- 下载期间通过 `caffeinate` 阻止系统闲置休眠。
- 更新检测仅接受 DMG，不提示下载 Windows EXE / ZIP，也不因缺少 macOS 包而强制更新。

## 视频引擎与已知限制

macOS 暂不支持应用内自动安装 FFmpeg，可自行安装：

```bash
brew install ffmpeg
```

应用除 PATH 外还会检查 `/opt/homebrew/bin` 和 `/usr/local/bin`，从 Finder 启动也能识别 Homebrew 安装。
macOS 的 ffplay 使用独立播放窗口，FFmpeg 可用于音视频合轨。

以下 Windows 专属功能暂未移植：通知中心下载进度条、浏览器 Cookie 自动解密导入、
下载完成后关机以及 Windows 自定义窗口选位器。登录可使用内嵌 Chromium 或手动 Cookie。

构建包使用 ad-hoc 签名，未经过 Apple Developer ID 签名或公证。
在其它电脑上打开时，可能需要通过 macOS 正常安全设置允许打开。
目前已在 Apple Silicon Mac 验证构建、窗口交互、SQLite、内嵌浏览器 Cookie 读写和 Cmd+Q 退出；
未覆盖所有网盘账号登录、真实下载及全部视频格式。
