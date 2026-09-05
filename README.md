# YunX-Desktop（云析桌面版）

[云析 YunX](https://github.com/CYQawa/YunX)（Android 网盘分享链接解析 + 高速下载器）的 **Windows 桌面移植**。

粘贴网盘分享链接，浏览分享内容并直接高速下载文件 —— 无需手机，全程在电脑上完成。

## 支持平台

> **不建议用百度网盘，可能导致账号被风控！！！**

- 夸克网盘
- UC 网盘
- 迅雷网盘
- 百度网盘
- 139 网盘（和彩云）
- 123 云盘

## 功能

- **分享链接解析**：识别夸克 / UC / 迅雷 / 百度 / 139 / 123 的分享链接，自动匹配提取码
- **高速下载**：Range 分片并发 + 断点续传 + 自动重试 + 全局限速；弹性区按字节顺序分配，保证分片物理相邻、连接复用率稳定
- **下载任务管理**：暂停 / 继续 / 删除 / 打开文件 / 在资源管理器中显示 / 复制直链
- **Windows 通知中心进度**：下载进度实时显示为 toast 进度条（多任务自动聚合 + 实时速度）
- **阻止电脑休眠**：下载期间通过 `SetThreadExecutionState` 阻止系统睡眠，任务结束自动恢复
- **网盘登录**（三种方式，任选其一）：
  - **内嵌浏览器登录（推荐）**：夸克 / UC / 百度 / 139 登录页内直接打开真实 Chromium 网页（JCEF 132），登录后自动检测并保存 Cookie，全程无感
  - **一键自动导入**：扫描本机已装浏览器（Chrome / Edge / Firefox / 360 / QQ 等），自动解密并导入该平台的登录 Cookie（Windows DPAPI；Chrome/Edge 较新版本因「应用绑定加密」可能无法读取，Firefox/360 等始终可用）
  - **手动粘贴 Cookie**（兜底）
  - 迅雷（账号密码 + 短信验证）、123（账号密码换 JWT）—— 纯 HTTP，直接表单登录
- **认证备份**：用户口令派生密钥（PBKDF2）+ AES-GCM 加密 Cookie/JWT，导出为 `.yunx` 备份文件，跨设备恢复
- **Windows 原生对话框**：下载目录选择、备份导入（选择文件）/ 导出（另存为）均为资源管理器同款样式（Vista+ COM IFileDialog）
- **界面**：深色模式 + 自定义种子色（Monet 风格动态取色）+ 链接收藏

## 构建与运行

要求：Windows 10/11 x64。零手动环境配置 —— 缺 JDK 自动下载（`jdk-setup.ps1`），Gradle 发行版由 wrapper 自动拉取，Inno Setup 6 缺失时安装器脚本会自动下载并静默安装。

```powershell
git clone https://github.com/tidain/YunX-Desktop.git
cd YunX-Desktop

.\run.ps1 run        # 编译并启动（开发）
.\run.ps1 build      # 仅编译
.\run.ps1 package    # 免安装便携版 → release\YunX-Desktop\
.\run.ps1 installer  # 单文件安装程序 → release\YunX-Desktop-setup-*.exe
```

也可双击 `run.bat`（走同一入口）。中文向导语言包随仓库分发（`installer\ChineseSimplified.isl`）。

## 打包产物

- **便携版**：`release\YunX-Desktop\`（整个文件夹拷走即用，双击 `YunX-Desktop.exe` 运行）
- **安装程序**：`release\YunX-Desktop-setup-<版本>.exe`（每用户安装到
  `%LOCALAPPDATA%\Programs\YunX-Desktop`，无需管理员权限，开始菜单/桌面快捷方式「云析」，可卸载）

## 使用

1. 在「网盘」页登录需要用的网盘账号（推荐内嵌浏览器登录）
2. 在「解析」页粘贴分享链接（可带提取码，支持从剪贴板一键粘贴）
3. 浏览分享内容，点击文件加入下载
4. 在「下载」页查看进度，支持暂停 / 继续 / 删除 / 打开；进度同步显示在 Windows 通知中心

## 数据目录

`%USERPROFILE%\.yunx-pc\`：`yunx.db`（任务/凭证库）、`credential.key`（加密密钥）、
`cache/download_tmp`（下载分片）、`files/yunx-pc.log`（运行日志）。
设置存于 Windows 注册表 `HKEY_CURRENT_USER\Software\JavaSoft\Prefs\yunx`。

## 与上游的差异

1. **登录**：夸克/UC/百度/139 除粘贴 Cookie 外，新增内嵌 Chromium 浏览器登录与本机浏览器 Cookie 自动导入（上游 Android 版为 WebView 提取）；迅雷验证页由系统浏览器承载。
2. **通知栏 / 锁屏保活**：按桌面习惯重新实现 —— 下载进度走 Windows 通知中心（toast 进度条）；「锁屏后保持下载」改为「下载时阻止电脑休眠」（偏好键 `keep_awake_downloading`，旧键 `keep_download_when_locked` 自动迁移）。
3. **移除**：电池优化引导、动态取色（Material You）、应用图标切换、APK 更新检测、崩溃独立进程。
4. **系统整合**：目录/文件选择、另存为均为 Windows 原生对话框；下载目录经 Known Folder API 解析（支持用户重定向过的「下载」位置）。

## 免责声明

本项目仅供个人学习与技术交流，请勿用于商业用途。下载内容版权归原作者所有，请在下载后 24 小时内删除。使用本项目产生的任何后果由使用者自行承担。

## 开源协议

本项目基于上游 [CYQawa/YunX](https://github.com/CYQawa/YunX) 移植，同样以 [GNU AGPL-3.0](https://www.gnu.org/licenses/agpl-3.0.html) 协议开源，详见根目录 [LICENSE](LICENSE)。

## 关于协议逆向

部分网盘平台的解析基于抓包分析与开源项目（如 alist）的协议研究整理，接口可能随官方调整而失效，请以实际运行结果为准。
