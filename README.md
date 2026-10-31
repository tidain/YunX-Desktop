<div align="center">

<img src="desktop/src/main/resources/icon.png" width="120" alt="云析 YunX-Desktop" />

# 云析桌面版 · YunX-Desktop

**粘贴分享链接，直接高速下载 —— 云析 YunX 官方 Windows 桌面版**

网盘：夸克 / UC / 迅雷 / 百度 / 139 / 123 / 115 / 光鸭 / 蓝奏云 / 蓝奏云优享版；
视频：哔哩哔哩 / 小红书 / 抖音 / 微博 / 快手 / AcFun / Pixiv；另支持磁力 / BT 种子 / ed2k 等 P2P 链接。
Range 分片并发 + 断点续传，B 站高清自动合轨。

[![Release](https://img.shields.io/github/v/release/tidain/YunX-Desktop?style=flat-square\&label=release\&color=4C8BF5)](https://github.com/tidain/YunX-Desktop/releases/latest)
[![Stars](https://img.shields.io/github/stars/tidain/YunX-Desktop?style=flat-square\&logo=github\&color=4C8BF5)](https://github.com/tidain/YunX-Desktop/stargazers)
[![License](https://img.shields.io/badge/license-AGPL--3.0-blue?style=flat-square)](./LICENSE)
[![Platform](https://img.shields.io/badge/platform-Windows-0078D6?style=flat-square\&logo=windows\&logoColor=white)](#构建)
[![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?style=flat-square\&logo=kotlin\&logoColor=white)](https://kotlinlang.org/)
[![Compose](https://img.shields.io/badge/Compose%20Multiplatform-Desktop-4285F4?style=flat-square\&logo=jetpackcompose\&logoColor=white)](https://www.jetbrains.com/lp/compose-multiplatform/)

[下载最新版](https://github.com/tidain/YunX-Desktop/releases/latest) · [功能](docs/FEATURES.md) · [使用](docs/USAGE.md) · [浏览器插件](#浏览器插件) · [构建](docs/BUILD.md) · [常见问题](#常见问题) · [与安卓版差异](docs/UPSTREAM-DIFF.md)

</div>

***

> **云析永远免费开源。** 如果你是在任何地方「花钱买到」的，说明你被骗了，请立即退款。
> 任何收费版本均为二次打包的诈骗版本，与本项目无关。

> **本项目使用 AGPL-3.0 开源协议。** 与安卓版 [CYQawa/YunX](https://github.com/CYQawa/YunX)
> 同属云析项目；若你使用了本项目的代码，请同样以 AGPL-3.0 开放源代码。

## 简介

云析 YunX 的 **官方 Windows 桌面版**：粘贴**网盘分享链接**或**视频作品链接**，即可浏览内容、在线预览并直接高速下载，全程在电脑上完成；
也支持**磁力 / BT 种子 / ed2k** 等 P2P 链接下载。
网盘与视频平台都支持多账号（主账号 / 备注），登录凭证加密存本机，并可导出加密备份换机恢复。
提供**免安装便携版**（整个文件夹拷走即用）与**安装版**（开始菜单 / 桌面快捷方式，可卸载）两种分发形式。

## 截图

|                                                          |                                                            |
| :------------------------------------------------------: | :--------------------------------------------------------: |
| <img src="docs/images/主界面.png" width="430" alt="主界面" />   | <img src="docs/images/网盘登录.png" width="430" alt="网盘登录" />   |
|                           主界面                           |                           网盘登录                           |
| <img src="docs/images/设置.png" width="430" alt="设置" />     | <img src="docs/images/关于.png" width="430" alt="关于" />     |
|                            设置                            |                            关于                            |

## 支持平台

- 夸克网盘

- UC 网盘

- 迅雷网盘

- 百度网盘

- 139 网盘（和彩云）

- 123 云盘

- 115 网盘

- 光鸭云盘

- 蓝奏云 / 蓝奏云优享版

- GitHub（仓库 / 账号 / 文件直链）

- 视频解析：抖音 / 快手 / 微博 / 小红书 / 哔哩哔哩 / AcFun / Pixiv

- 磁力 / BT 种子 / ed2k（P2P 下载，可导入本地种子文件）

> \[!WARNING]
> **不建议使用百度网盘，可能导致账号被风控！**

## 浏览器插件

仓库 `browser-extension/` 内含 Chrome / Edge 扩展「**云析下载助手**」（Manifest V3）—— 让浏览器下载接管到云析的多线程下载。

- **嗅探页面资源**：列出当前网页里可下载的媒体与文件，按图片 / 视频 / 音频 / 文档 / 压缩包 / 程序 / 字体分类，图片与视频支持卡片预览。
- **接管浏览器下载**：浏览器开始下载时自动接管（取消浏览器下载并抹掉记录），由云析弹窗询问保存目录与线程数（最高 512，超过 32 需二次确认）。
- **归还浏览器下载**：点「归还浏览器下载」后重新发起浏览器原生下载，并对该页面网站（根域名及其子域名）写入 **5 分钟冷却**；冷却期内打开插件可看到「还有 X 分 YY 秒」倒计时，并可点「立即重新开始」单独解除当前网站的冷却。
- **视频平台卡片**：B 站视频页可选清晰度与下载方式；抖音 / 微博 / AcFun 视频页可一键交给云析解析下载。
- **安全**：扩展与云析通过本机回环 HTTP（`127.0.0.1`，随机 token）通信，网页脚本无法伪造请求；**云析没运行时探测不到端口，扩展完全不干预浏览器下载**。

安装、开关与工作原理见 [浏览器插件说明](browser-extension/README.md)。

## 功能

- **分享链接解析** —— 识别夸克 / UC / 迅雷 / 百度 / 139 / 123 / 115 / 光鸭 / 蓝奏云 / 蓝奏云优享版 分享链接并自动匹配提取码，另支持 GitHub 仓库 / 账号 / 文件直链

- **视频解析与预览** —— 粘贴抖音 / 快手 / 微博 / 小红书 / B站 / AcFun / Pixiv 作品链接（短链、口令、整段文案均可）即可解析；在线预览优先用 **ffplay** 直读直链（真·边下边播，HLS 原生支持），本机没装 FFmpeg 时回退内置播放器并可一键下载引擎到应用私有目录（不改系统环境变量）；图集可放大查看，下载支持 DASH 合轨（优先 ffmpeg `-c copy`）与 Live Photo 动图合成（可存动态照片 / 静图 / 视频）

- **高速下载** —— Range 分片并发 + 断点续传 + 慢连接自动换线 + 全局限速；B 站高清分轨两轨并行下载后自动合轨

- **P2P 下载** —— 磁力 / BT 种子 / ed2k 电驴链接交给 Gopeed 引擎下载；支持导入本地 `.torrent` 种子文件、BT Tracker 服务器二级页管理与 DHT 开关，下载中可查看「种子 / 连接 / 节点」概览

- **GitHub 浏览与 README 渲染** —— 浏览仓库文件树 / Releases / 账号仓库列表，README 与更新说明以 GFM Markdown 渲染

- **GitHub 下载镜像自动测速** —— 内置五个 gh-proxy 系镜像，下载前并发实测每个镜像的延迟与下载速度，按速度自动选最快（设置页可看 ping / 速度、手动刷新、固定某个或自定义前缀），更新包 / 视频引擎 / Gopeed 内核 / README 图片统一走该镜像

- **通知中心进度** —— 下载进度实时显示为 Windows 通知中心 toast 进度条（多任务聚合 + 实时速度）

- **多种登录方式** —— 内嵌 Chromium 登录 / 一键导入本机浏览器 Cookie / 手动粘贴 Cookie；GitHub 可选 Personal Access Token

- **Windows 系统集成** —— 原生托盘菜单（亮暗跟随系统配色）、原生文件对话框、剪贴板分享链接检测

- **多账号与认证备份** —— 网盘与视频平台都支持多账号（主账号 / 备注 / 逐个退出）；「设置 → 认证备份」可加密导出 / 导入全部账号，换机免重登

- **桌面体验** —— 深色模式 + 自定义种子色、链接收藏、解析历史、窗口内弹窗覆盖层

- **网络** —— GitHub 下载镜像（自动测速选最快 / 固定预选 / 自定义前缀）、HTTP 代理（便于加速或直连受限的场景）

- **引擎管理** —— 下载引擎（Gopeed 内核）与视频预览引擎（FFmpeg 精简版）集中管理，可下载 / 更新 / 卸载并查看路径与来源

完整功能清单见 **[功能清单](docs/FEATURES.md)**。

## 与安卓版的差异

桌面版在**登录方式、系统集成（托盘 / 通知 / 原生对话框）、数据存储、构建分发**上针对 Windows 做了适配，
并移除了移动端专属功能。完整清单见 **[与安卓版的差异](docs/UPSTREAM-DIFF.md)**。

## 技术栈

| 分类          | 选型                                                 |
| ----------- | -------------------------------------------------- |
| 语言          | Kotlin                                             |
| UI          | Compose Multiplatform（Desktop）+ Material 3         |
| 持久化         | `sqlite-jdbc`（SQLite）+ `java.util.prefs`           |
| 网络          | OkHttp 4.12.0（请求 + 分片下载）                           |
| 内嵌浏览器       | JCEF 132（Chromium）                                 |
| 视频预览        | ffplay（FFmpeg 套件，边下边播）+ JavaFX Media（兜底）        |
| 原生桥接        | JNA（Win32 API）+ 自编 `darkmode.dll`                  |
| 构建分发        | Gradle + jlink + jpackage + Inno Setup             |
| Markdown 渲染 | multiplatform-markdown-renderer（GFM：README / 更新说明） |

## 使用

1. 在「网盘」页登录需要使用的网盘账号（推荐内嵌浏览器登录）；视频解析平台的登录在「解析」页对应平台卡片或结果页里完成
2. 在「解析」页粘贴分享链接或视频链接（可带提取码，支持从剪贴板一键粘贴）
3. 网盘分享会进入文件浏览页，视频链接会进入结果页（可选清晰度 / 图集 / 预览）
4. 在「下载」页查看进度，支持暂停 / 继续 / 删除 / 打开；进度同步显示在 Windows 通知中心

设置项、数据目录、托盘操作见 **[使用说明](docs/USAGE.md)**。

## 构建

要求：Windows 10/11 x64，另需一个 **C++ 编译器**（托盘菜单配色桥接层用；缺它时 `run` / `package` /
`installer` 会在这一步失败）。JDK、Gradle、Inno Setup 均无需手动安装。

```powershell
git clone https://github.com/tidain/YunX-Desktop.git
cd YunX-Desktop

.\run.ps1 run        # 编译并启动（开发，含 darkmode.dll）
.\run.ps1 build      # 仅编译 Kotlin（不需要 C++ 编译器）
.\run.ps1 package    # 免安装便携版 → release\YunX-Desktop\
.\run.ps1 installer  # 单文件安装程序 → release\YunX-Desktop-setup-*.exe
```

环境要求、版本号管理与打包产物见 **[构建与打包](docs/BUILD.md)**。

## 常见问题

<details>
<summary><b>为什么从「第三方移植」变成了「官方桌面版」？</b></summary>

项目早期确实是社区移植（安卓版 [CYQawa/YunX](https://github.com/CYQawa/YunX) 的 Windows 移植）。
**2026 年 10 月 2 日上午 8:40**，桌面版作者（tidain）加入安卓版仓库成为协作者，
桌面版自此与安卓版同属一个项目，作为云析的**官方 Windows 桌面端**维护；
两端的代码、发布节奏与分发方式仍各自独立。

</details>

<details>
<summary><b>百度网盘下载 / 转存不了？</b></summary>

账号被风控了，详见安卓版 issue #9。

</details>

<details>
<summary><b>便携版和安装版有什么区别？</b></summary>

便携版是免安装的文件夹，拷到任意位置双击 `YunX-Desktop.exe` 即可运行；
安装版走安装向导，安装到 `%LOCALAPPDATA%\Programs\YunX-Desktop`，并创建开始菜单 / 桌面快捷方式，可在设置中卸载。
两者功能完全一致。

</details>

<details>
<summary><b>下载时看不到 Windows 通知中心的通知？</b></summary>

先确认「设置 → 系统 → 通知」已开启（专注助手 / 免打扰也会拦通知）。应用在检测到通知未送达时，
会尝试自动创建所需的开始菜单快捷方式兜底，但最终能否弹出仍取决于系统通知设置。

</details>

## 支持开发

- **GitHub Star**：给 [tidain/YunX-Desktop](https://github.com/tidain/YunX-Desktop) 点个 Star ⭐

- **提交 Issues**：发现 Bug 或有功能建议欢迎反馈

- **赞赏**：应用内「支持开发」页面可扫码赞赏安卓版作者（CYQawa）与桌面端作者（tidain）

## 文档

| 文档                              | 内容                             |
| ------------------------------- | ------------------------------ |
| [功能清单](docs/FEATURES.md)        | 全部功能与细节                        |
| [使用说明](docs/USAGE.md)           | 快速上手、登录方式、设置项、数据目录、托盘操作        |
| [构建与打包](docs/BUILD.md)          | 环境要求、命令、版本号、打包产物、目录结构          |
| [与安卓版的差异](docs/UPSTREAM-DIFF.md) | 桌面版相对安卓版的新增 / 重做 / 移除 |
| [免责声明](docs/DISCLAIMER.md)      | 免责声明、协议逆向说明、反倒卖、开源协议           |

## 开源协议

本项目与安卓版 [CYQawa/YunX](https://github.com/CYQawa/YunX) 同属云析项目，同样以
[GNU AGPL-3.0](https://www.gnu.org/licenses/agpl-3.0.html) 协议开源，详见 [LICENSE](LICENSE)。
完整的免责与合规说明见 **[免责声明](docs/DISCLAIMER.md)**。

## 更多

- 安卓版仓库：<https://github.com/CYQawa/YunX>

- PC 版仓库：<https://github.com/tidain/YunX-Desktop>

- 问题与建议：<https://github.com/tidain/YunX-Desktop/issues>

## 友情链接
- 云析的Docker社区移植版（基于本仓库修改，作为本仓库的下游分支）：[mu-zi-lee/YunX-Docker](https://github.com/mu-zi-lee/YunX-Docker)

## 贡献者

<sub><sup>Contributors</sup></sub>

<a href="https://github.com/tidain/YunX-Desktop/graphs/contributors">
  <img alt="contributors" src="https://contrib.rocks/image?repo=tidain/YunX-Desktop" />
</a>

## Star History

<a href="https://www.star-history.com/?repos=tidain%2Fyunx-desktop&type=date&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=tidain/yunx-desktop&type=date&theme=dark&legend=top-left" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=tidain/yunx-desktop&type=date&legend=top-left" />
   <img alt="Star History Chart" src="https://api.star-history.com/chart?repos=tidain/yunx-desktop&type=date&legend=top-left" />
 </picture>
</a>
