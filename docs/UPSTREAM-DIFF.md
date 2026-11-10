# 与上游的差异（Windows 桌面版 vs Android 版）

- 上游（Android）：[CYQawa/YunX](https://github.com/CYQawa/YunX)
- 本仓库（Windows 桌面移植）：[tidain/YunX-Desktop](https://github.com/tidain/YunX-Desktop)

**沿用上游的部分**：网盘分享链接的解析协议、下载引擎（Range 分片并发、弹性区按字节顺序分配、
断点续传、失败重试、全局限速）、界面框架与主题体系（Compose + Material 3、深色模式 + 种子色）、
各网盘 API 的协议实现。

下面只列桌面版**新增、重做或移除**的内容。

## 1. 登录与账号

| 能力 | 上游（Android） | 桌面版 |
| --- | --- | --- |
| 夸克 / UC / 百度 / 139 登录 | 内嵌 WebView 提取 Cookie | **内嵌 Chromium（JCEF 132）** 打开真实网页登录，登录完成后自动检测并保存 Cookie |
| 已有浏览器会话 | 无 | **一键导入本机浏览器 Cookie**：扫描 Chrome / Edge / Firefox / 360 / QQ 等，用 Windows DPAPI 解密（Chrome / Edge 较新版本受「应用绑定加密」限制可能读不到，Firefox / 360 等始终可用） |
| 兜底方式 | 手动粘贴 Cookie| 与上游相同       |
| 123 云盘 | 账号密码换 JWT | 同上游（纯 HTTP 表单登录） |
| GitHub Token | Android Keystore 加密存储 | AES-GCM 加密存储，密钥为本地密钥文件 `credential.key`（非 Android Keystore）；仅用于提升 API 限额 |
| 登出 | 清除 WebView 的 Cookie | 通过 CEF 的 `delete` 标志清除该平台全部 Cookie，避免残留 |

## 2. 下载进度与保活

- **进度通知改为 Windows 通知中心**：下载进度显示为 toast 进度条（多任务自动聚合成一条、
  带实时速度与完成度）。
  - 同一条通知**原地刷新**，不会每次刷新都弹横幅或响铃；
  - **送达自检**：发送后对比 Windows 为应用维护的 `LastNotificationAddedTime`，若系统静默丢弃了通知，
    则自动创建带 AppUserModelID 的开始菜单快捷方式兜底（这是未打包桌面应用能发通知的前提条件），
    正常情况下不会改动用户的开始菜单。快捷方式位于开始菜单文件夹
    `%APPDATA%\Microsoft\Windows\Start Menu\Programs\`，且无法保证 100% 送达（取决于系统通知设置）。
- **保活语义变化**：「锁屏后保持下载」→ **「下载时阻止电脑休眠」**，通过
  `SetThreadExecutionState` 实现，任务结束自动恢复（偏好键 `keep_awake_downloading`，旧键
  `keep_download_when_locked` 自动迁移）。
- **完成提示**：全部任务结束且确实下载完成时，额外弹一条完成通知。

## 3. 系统托盘与窗口行为

桌面版新增了完整的托盘与窗口生命周期（上游为 Android 通知栏 / 前台服务概念）：

- **系统托盘**：AWT `TrayIcon` + 手写 Win32 原生右键菜单（JNA 调 `CreatePopupMenu` +
  `AppendMenuW` + `TrackPopupMenuEx`），中文显示正常、不再出现方块。
- **菜单跟随系统配色**：自带原生桥接层 `native/darkmode.cpp` → `darkmode.dll`，启动时通过
  `uxtheme.dll` 的 `SetPreferredAppMode` 打开暗色支持，每次弹菜单前按当前系统配色重新应用，
  因此系统在深色 / 浅色之间切换时菜单会跟着变。
- **窗口行为**：左键托盘图标恢复主窗口（含从任务栏最小化状态恢复）、最小化到托盘、
  关闭主窗口时按设置询问 / 直接退出 / 最小化到托盘。
- **启动与关闭过渡**：启动闪屏 + 主窗口内容就绪后渐入，关闭时先淡出再销毁，
  避免原生窗口销毁瞬间的白屏闪烁。

## 4. Windows 系统整合

- **原生对话框**：下载目录选择、认证备份的导入 / 导出均为资源管理器同款样式
  （Vista+ 的 COM `IFileDialog`，JNA 直接调 vtable，不依赖 Swing）。
- **下载目录解析**：通过 Known Folder API 解析系统「下载」位置，支持用户重定向过的路径。
- **文件定位**：下载项「文件夹」按钮等价于 `explorer /select,<file>`，直接选中目标文件。
- **剪贴板分享链接检测**：主窗口不在前台时轮询剪贴板，发现网盘分享链接在右下角弹出提示卡片，
  点击即可打开解析（可在设置中关闭）。

## 5. 桌面专属体验

- **弹窗统一为窗口内覆盖层**：所有对话框都在主窗口内以覆盖层渲染，不创建原生窗口，
  避免阻塞 UI 线程、动画卡顿与任务栏多出窗口。
- **解析链接历史**：自动记录解析过的分享链接（1 小时去重），支持搜索、复用提取码、删除与清空。
- **链接收藏**：收藏解析链接并分类管理。
- **右键菜单**：统一为窗口内弹窗材质，并新增「复制分享链接」。
- **渲染后端可选**：默认 `OPENGL`（GPU 渲染，动画流畅且文字清晰），
  可用环境变量 `YUNXPC_RENDER_API=SOFTWARE|DIRECT3D|OPENGL` 切换；
  字体 hinting 会按后端自动调整，保证文字清晰度。
- **百度网盘 API 修复**：域名迁移、`bdstoken` 自动刷新、限流重试、超时收紧等一串修复。

## 6. 数据与存储

| 项 | 上游（Android） | 桌面版 |
| --- | --- | --- |
| 数据库 | Room | 纯 JDBC（`sqlite-jdbc`）直连 SQLite |
| 设置存储 | SharedPreferences | `java.util.prefs`，落在注册表 `HKEY_CURRENT_USER\Software\JavaSoft\Prefs\yunx` |
| 文件存储 | SAF / tree Uri | 普通文件路径（下载目录等） |
| 数据目录 | 应用私有目录 | `%USERPROFILE%\.yunx-desktop\`：`yunx.db`、`credential.key`、`cache/download_tmp`、`files/yunx-desktop.log`（旧版 `.yunx-pc` 首次启动自动迁移） |
| 认证备份 | 有 | 同思路：PBKDF2 派生密钥 + AES-GCM 加密 Cookie / JWT，导出 `.yunx` 备份文件，可跨设备恢复 |
| README / 更新说明图片渲染 | Android `BitmapFactory` 解码（`inSampleSize` 降采样） | GFM Markdown 渲染，图片改用 Skia（skiko）解码，无降采样 |

## 7. 构建与分发

- **技术栈**：Kotlin + Compose Multiplatform（Desktop）+ Gradle，JDK 17。
- **打包链路**：`jlink` 裁出运行时镜像 → `jpackage` 生成 app-image → 自研启动器替换默认启动器；
  安装包由 Inno Setup 压缩成单文件（每用户安装、无需管理员权限）。
- **自研启动器**（`launcher/Launcher.cs`，C# 编译为无控制台窗口的 exe）：
  固定读取 `app\YunX-Desktop.cfg`，因此**改 exe 名字或放在中文路径下都不会失效**；
  同时负责启动闪屏与主窗口的交叉淡入。
- **版本号单一来源**：根目录 `version.txt` —— jpackage 版本、便携包 / 安装程序里的程序版本与
  文件名、应用内「关于页 / 更新检测」全部由它派生，**发版只需改这一个文件**。
- **原生桥接层**：`native/darkmode.cpp` 在构建期编译为 `darkmode.dll`（优先 `cl.exe`，回退 `g++`），
  随便携版 / 安装包一起分发。
- **零手动环境配置**：缺 JDK 自动下载、Gradle 由 wrapper 拉取、缺 Inno Setup 自动静默安装。

## 8. 已移除的上游功能

- 电池优化引导
- 壁纸动态取色（Material You / 壁纸取色）——桌面版改为**用户自选种子色**，界面其余主题体系不变
- 应用图标切换
- APK 更新检测（改为检测 GitHub Releases）
- 崩溃独立进程

## 9. 上游提交对齐记录

- 上一次对齐：上游 `2e8bbb2`（GitHub 解析平台 #112/#114），桌面版提交 `7c80a9c`。
- 第二轮对齐：上游 `d9d17a5`（含 `ba3bfb0`/`835b1cb`/`c4ef992`/`a84118a`/`286446c`/`dbccb09`/`d9d17a5`），桌面版提交 `902035f`。
- **本次对齐：上游 `5d614f6`（含 `b6a251b`/`f381914`/`2761069`/`5d614f6`），跳过 `880f7ca`/`2743dcd`。**

### 9.1 已移植

| 上游提交 | 内容 | 桌面实现与取舍 |
| --- | --- | --- |
| `ba3bfb0`（#116） | 大文件下载 OOM 修复 | 全进程在飞上限 `MAX_INFLIGHT_CHUNKS = clamp(maxHeap/8/64KB, 8, 512)` + 跨任务共享 `inflightLimiter`（主池/弹性区/失败重试三条路径统一过闸，取代旧的「每任务一个信号量」）；专用分片线程池 `chunkIoDispatcher`（core=max=上限、30s 空闲回收、daemon，不再受 `Dispatchers.IO` 的 `max(64,核数)` 限制）；**慢连接抢占**（看门狗 5s 采样，阈值 `max(12KB/s, 任务均速/2)`，15s/收尾 3s、每片最多 3 次、零退避，断连续传不丢数据）；读缓冲 256KB→64KB；下载客户端排队上限 64、空闲连接池 8×1min。**HTTP/2 桌面已是可开关（默认仅 1.1）**，无需改。桌面堆更大（jpackage 默认 1/4 物理内存，实测约 4GB → 上限夹到 512），OOM 部分按需取舍。 |
| `835b1cb`（#123） | 更新下载应用自定义镜像前缀 + 失败回退直连 | `SettingsScreen` 的「镜像站下载更新包」改用 `SettingsRepository.githubMirrorPrefix`（未配置用内置默认），并把 GitHub 直连 URL 作为 `fallbackUrl` 传入 `DownloadManager.enqueue`；镜像主 URL 探测失败时整任务切直连（与 GitHub 浏览下载同机制）。 |
| `c4ef992`（#126） | 未登录也可查看解析文件列表 | `ResolveViewModel.isGuest` + 6 平台列表接口匿名：百度 `errno=-6` 文案区分、夸克/UC 非 JSON 响应带 HTTP 码、迅雷 token 为空时不写 `Authorization` 并走 `panCallAnonymous`、139 去掉账号前置校验；`startResolve`/`openFolder`/`goBack`/`navigateToLevel` 空凭据照常下传；下载/转存（`fetchDownloadLink`/`downloadFiles`/`startDownload`/`saveToCloud`/`requestSave`）仍要求登录，提示统一为「…需要先登录 X（未登录仅能浏览文件列表）」；`ShareDetailScreen` 顶部新增 `GuestBrowseNotice` 常驻提示条。 |
| `a84118a`（#127） | 修正创建分享有效期错位、统一中性码 | 新增 `model/ShareExpire`（1/2/3/4 中性码 + `daysOrNull`/`baiduPeriod`/`xunleiDays`，未知码 fail-loud）；百度 `BaiduShareResult.expiredType` 可空、ViewModel 用 `baiduPeriod` 转换并优先用服务端回填；139/123/迅雷 API 的中性码回填不再 fail-open 成「永久/30 天」。**桌面差异**：139 与 123 云盘页自带分享弹窗，有效期本就是「天数」语义（`null=永久 / 1 / 7 / 30`）且直接传给对应 API，故这两个 ViewModel 仍收天数、未改签名；只有百度走 `CloudFileSheets` 的中性码，需按上游修正。 |
| `286446c`（#118） | 合并大文件时显示合并进度 | `DownloadStats.mergePercent`（-1=不在合并）；`ChunkDownloader.mergeChunksToStream` 新增 `onProgress`，`finishDownload` 300ms 节流上报；下载页主任务卡/子任务行/文件夹徽标显示「合并中 · n%」。桌面落盘已改为「边合并边写目标文件」，故进度按「已合并字节 / total」计算。**未同步通知栏**：桌面进度走 Windows 通知中心，合并阶段仍在 100% 后短暂显示（未额外改造 toast 文案）。 |
| `d9d17a5`（#129） | 夸克/UC 免登录下载、设置项开关 | 夸克/UC 游客取链 `getGuestShareDownloadLink`（不转存，`__pugs` 随响应捕获进 `DownloadLink.guestCookie`）；`enqueueDownload` 识别游客直链并用游客头（UC：`GUEST_UA`+`Sec-Ch-Ua`；夸克：`API_USER_AGENT`）；`ResolveViewModel.supportsGuestDownload()` 仅夸克/UC 放行，其余平台下载仍要登录；`GuestBrowseNotice` 按平台给不同文案。设置项：新增「接受预发布版更新」开关（`SettingsRepository.acceptPrereleaseUpdate` + `UpdateChecker` 预发布通道与版本后缀比较 + 更新弹窗「预发布」标记）；「自动识别剪贴板」桌面版早有等价开关（设置页「剪贴板分享链接检测」）。 |

### 9.2 跳过（Android 专属或不适用）

| 上游提交/内容 | 原因 |
| --- | --- |
| `d99daaf`(#113)、`43537b2`(#125) | CI：Android 签名 APK 构建与发布，桌面分发走 jpackage/Inno Setup，无关。 |
| `560d3b6`(#117)、`52f939e`(#119)、`75deabd`(#120)、`06125a8`(#124)、`d85ac95`(#128) | Android 专属：图标/自适应图标、引导页、权限检查等，桌面无对应形态。 |
| `dbccb09`(#122) 目录选择器崩溃修复 | 桌面下载目录选择走原生 `WindowsFolderPicker`（非 Android SAF `ActivityResultLauncher`），不存在 `ActivityNotFoundException` 崩溃路径；且仪器测试依赖 AndroidX Compose test，桌面无该依赖。 |
| `d9d17a5`(#129)「网盘更新自动化」 | 桌面更新弹窗只有「安装版(.exe)/便携版(.zip)」资产下载，**没有** Android 版 Release 说明里的「网盘更新」入口，`startUpdateDownload` 在桌面无调用方，故不涉及。 |
| `d9d17a5`(#129)「自动识别剪贴板」开关 | 桌面版早已有等价开关与开关项（「剪贴板分享链接检测」，键 `clipboard_link_detection`）。 |
| `d9d17a5`(#129) 更新说明改纯文本 | 桌面更新弹窗用自研 Markdown 渲染（与 README 共用），保留既有行为。 |
| `ba3bfb0`(#116) `onTrimMemory` 释放空闲连接 / CrashHandler 内存快照 | Android 生命周期回调与崩溃上报形态，桌面无等价入口（内存高压日志已并入下载进度回调）。 |

### 9.3 本次对齐（上游 `5d614f6`）

本轮覆盖上游 `b6a251b`(#130) / `f381914`(#131·#132) / `2761069`(#135) / `5d614f6`(#136)；
`ShareLinkParser` 的 115 链接形态改动随 `f381914` 一并落地。

#### 已移植

| 上游提交 | 内容 | 桌面实现与取舍 |
| --- | --- | --- |
| `f381914`(#131/#132) | 115 网盘支持（登录 / 云盘管理 / 分享 / 转存 / 下载） | **数据层**：新增 `Pan115Api` / `Pan115Constants` / `Pan115Crypto`（后者把上游 `android.util.Base64` 换成 `java.util.Base64`）；**模型**：`ShareInfo` 增 `warning`（非致命提示），`ShareExpire` 增 115 专属档位（101..105 + `PAN115_OPTIONS`/`pan115Duration`/`pan115CodeOf`/`pan115CodeOfText`）；**仓库**：`Pan115AccountRepository`（Cookie 落库，登出走 `CookieCleaner` 清 `115.com`）与 `Pan115ResolveRepository`（`share/snap` 列目录 → 分享直链；大文件退回「转存临时目录 + 电脑端加密取链」，接入桌面 `TransferSpaceGuard` 空间前置校验）；**DB**：`Pan115AccountEntity`/DAO/`SecureAccountDaos`/`AppDatabase`（新建 `pan115_account` 表，Cookie 加密落库）；**登录**：桌面走既有 JCEF 内嵌浏览器（`Pan115LoginScreen`）打开 115.com，检测 `UID`+`SEID` 后按 `Pan115Constants.filterLoginCookies` 只保留会话字段再用 `/user/info` 校验；**下载**：`DownloadPlatform.PAN115` + `enqueueDownload` 用「登录 Cookie + 取链响应 900s CDN Cookie」拼接、`User-Agent` 用客户端串 `CLIENT_UA`、`Referer` 用 115 域（三处缺失任一 CDN 均 403）；**云盘页**：`Pan115CloudViewModel` + `Pan115CloudScreen`/`Pan115SaveSheet`/`Pan115AccountSheet`（结构对齐 123 云盘页，非照搬 Android UI）；**配套**：网盘页 115 卡片 + 配额（`DriveQuotaViewModel` 并发拉 `getQuota`）、收藏/解析平台标签、认证备份导入导出、`BrowserCookieImporter` 域名后缀、关于/引导页平台列表。**未做端到端真机验证**（无 115 账号，登录/转存/直链需用户自测）。 |
| `2761069`(#135) | 网盘创建文件夹 | 桌面按上游覆盖面做全 7 个平台：`Pan115CloudViewModel`/`QuarkCloudViewModel`/`UCCoudViewModel`/`XunleiCloudViewModel`/`BaiduCloudViewModel`/`C139CloudViewModel`/`Pan123CloudViewModel` 各加 `createFolder`；`Pan123Api` 补 `createDir`（复用 `upload_request`，`type=1`）、`C139Api` 补 `createDir`（`hcy/file/create`，`type=folder`）+ 对应 Constants；7 个云盘页标题行加「新建文件夹」入口，共用 `CloudCreateFolderDialog`（**窗口内覆盖层 `FadeAlertDialog`，按项目约定不使用 Popup/material3 AlertDialog**），含名称本地校验。各平台父目录/根目录约定不同（百度绝对路径、迅雷根为空串、其余 `"0"`/`"/"`）。 |
| `5d614f6`(#136) | 夸克免转存下载（分享凭证直取直链）+ 设置项开关 | `QuarkApi.getShareDownloadLinkWithoutSave`（`fids`/`fids_token`/`pwd_id`/`stoken` 交给 `file/download`，Cookie 用账号态）+ `ShareResolveRepository.getShareDownloadLinkWithoutSave`（默认回退 `getShareDownloadLink`，仅夸克覆写）+ `QuarkResolveRepository` 覆写；`ResolveViewModel.resolveShareLink` 收敛「单文件 / 批量」两处登录态取链，开关关掉走老转存流程、夸克个别分享类型失败自动回退转存；设置页「下载」组新增「免转存下载」开关（`SettingsRepository.quarkNoSaveDownload`，默认开），经 `noSaveDownloadProvider` 注入即时生效。**放置判断**：该开关是功能性取链方式选择（默认即正常行为），不是「实验性功能」页那些高风险调参（HTTP/2、读缓冲、慢连接抢占），故放在主设置页「下载」分组，与上游一致。 |
| `b6a251b`(#130) | QQ 群链接 | 上游在引导页/设置页/关于页引入 `AppLinks`。桌面在**关于页**新增「QQ 交流群」卡片（群号 `635207650`）：Windows 无 `mqqapi://` scheme 的可靠保证，故点击**复制群号**并提示，供用户在 QQ 中搜索加群。 |
| `ShareLinkParser`（随 `f381914`） | 115 链接形态 | `SharePlatform` 增 `PAN115`；新增 `115(?:cdn|rc)?\.com/s/(sw…)`、口令形态 `115…com/(sw…)-(码)`、`?password=` 三个正则与解析分支；兼容既有 6 平台形态不被误伤（探针 69 项全过）。 |

#### 跳过

| 上游提交/内容 | 原因 |
| --- | --- |
| `880f7ca`(#133) 版本号 1.2.8 | 仅 Android 版本号变更，桌面版本号单一来源为根 `version.txt`。 |
| `2743dcd`(#131) F-Droid 元数据/截图 | Android 应用商店元数据与截图，桌面无对应分发渠道。 |

### 9.4 本次对齐（上游 `700ce12`）

本轮覆盖上游 `700ce12`(#137)：**迅雷中文口令解析（口令 → 分享链接 + 提取码）**。

#### 已移植

| 上游提交 | 内容 | 桌面实现与取舍 |
| --- | --- | --- |
| `700ce12`(#137) | 迅雷中文口令解析 | **新增纯逻辑对象** `data/network/XunleiKouling.kt`（判定 / 去装饰符 / 拼 jump URL / 解 `location`，语义与上游逐行一致，去掉 AGPL 文件头）。**接入点**：`XunleiApi.parseKouling(keyword)`（用带 Thunder/TBC 标识的 UA 打 shoulei `jump` 接口，`ext.kouling_type=="share_page"` 时取 `location` 里的分享页地址，否则抛「口令无效」）；`XunleiResolveRepository.resolveKouling(keyword)` 包成 `Result`；`ResolveViewModel.startResolve` 在「`ShareLinkParser.parse(link)==null` 且 `XunleiKouling.looksLikeKouling(link)`」时先换链，之后用 `effectiveLink`（真实分享链接）替代原始口令走既有解析/收藏/复制流程。`ResolveScreen` 输入框占位符加「或 迅雷口令」。**判定规则**：`normalize` 去两侧空白与装饰符；`looksLikeKouling` 要求非空、≤64 字、正文只含汉字/字母/数字且至少一个汉字——带空格/标点的整段文案、纯英文/数字、已是分享链接者均不误触。**桌面差异**：无（纯 Kotlin/Regex，不含 Android API），`java.net.URLEncoder` 与上游同。 |

#### 验证

上游 `XunleiKoulingTest.kt` 的 7 个用例全部移植到桌面并实测（`desktop/build/` 下临时探针直接调用桌面版编译产物，
`:desktop:test` 源集历史损坏、未改动）：真实 `location` → `https://pan.xunlei.com/s/VOEs0DLEAfUV9o-JOAqrzgZmA1?pwd=nw45`
且能被 `ShareLinkParser` 二次解析出 `XUNLEI`/shareId/`nw45`；无 `pwd` 保留裸链接；非分享页/空 location 返回 `null`；
中文口令（含 `【】`/`「」`/两侧空白）判定为口令；分享链接、纯英文/数字、含空格、过长文本均判否；负例（夸克/百度/115 链接、
整段带链接文案、普通 URL）均不会误入口令分支（探针 35 项全过）。

#### 跳过

| 上游提交/内容 | 原因 |
| --- | --- |
| 无 | 本次提交内容全部适用，无跳过项。 |

### 9.5 本次对齐（上游 `989a6d7`）

本轮覆盖上游 `561dd10`(#138) / `989a6d7`(#142)；`6265668`(#141) 的 Gopeed 引擎另见 §9.6（桌面以 exe 子进程形态重做）。

#### 已移植

| 上游提交 | 内容 | 桌面实现与取舍 |
| --- | --- | --- |
| `989a6d7`(#142) | 应用内公告系统（远程列表 / 启动弹窗 / 未读角标） | **数据层**：`data/announcement/AnnouncementApi`（列表 / 详情两接口；成功与否看响应体 `success`；不轮询；详情按 id 缓存）、`AnnouncementReadStore`（已读口径完全本地维护，打开详情 / 关弹窗都算已读）、`AnnouncementTime`（ISO 8601 → 本地时间，逐行一致）。**桌面适配**：日志 `android.util.Log` → 项目自带 `Log`；`SharedPreferences` → `java.util.prefs`（节点 `yunx/announcement`，仍存 JSON 数组、最多 500 条）；`User-Agent` 改 `YunX-Desktop`；按 `API-PC.md` **每个请求携带 `X-Client-Platform: desktop`**（服务端据此只返回「发电脑端」的公告；缺省 / 未知值一律回落手机端，会静默拿到手机端公告）；上游 `network_security_config.xml` 的明文放行在桌面 JVM 下不需要（去掉了对应注释与依赖）。**ViewModel**：`AnnouncementViewModel`（列表唯一真源 / 详情会话内缓存 / 未读数 / 启动弹窗候选口径与上游一致）；`Factory` 适配桌面 lifecycle 的 `create(KClass, CreationExtras)` 签名；`SnackbarController` 与上游同名同语义。**图片**：新增 `RemoteImageLoader`（Skia `Image.makeFromEncoded` 解码 + 128 张 LRU + `Semaphore(4)` + 进行中去重 + GitHub 镜像；SVG 直接跳过，与上游 BitmapFactory 取舍一致）与 `RemoteImage`（`ImageBitmap` 版，`autoHeight` 自算宽高比占位；底框宽度收窄到实际绘制宽度、缩小后靠左；弹窗封面与详情图片均设高度上限），以及全窗口看图组件 `ImageViewerOverlay`（详情页封面 / 正文图集单击放大；滚轮以鼠标为锚点缩放、拖拽平移、双击复位、Esc 或单击图片外关闭）。正文图集多图时按统一行高等高横向排布、放不下自动换行（`FlowRow` + `RemoteImage.fixedHeight`），单图沿用自适应高度。**UI**：`AnnouncementScreen`（列表 ↔ 详情，`AnimatedContent` 淡入淡出）、`AnnouncementListPage`（首屏 / 错误 / 空态；刷新走顶栏按钮）、`AnnouncementDetailPage`（正文复用 GFM 渲染器 + 自研 `GitHubMarkdownImageTransformer`）、`AnnouncementPopupDialog`（**窗口内 `FadeAlertDialog`**，非 material3 AlertDialog）、`AnnouncementUnreadBadge`。**MainScreen**：顶栏 `Campaign` 图标（所有 Tab 可见）+ 未读角标（画在 `IconButton` 外层 48dp Box，避免被 IconButton 的圆形裁剪切掉）+ 公告覆盖页 + 启动弹窗。 |
| `989a6d7`(#142) 桌面取舍 | 共享元素过渡 / 下拉刷新 | **未移植共享元素**：上游用 `SharedTransitionLayout` + `sharedBounds` 做「列表项长成整页 / 图标长成整页」；桌面 `MainScreen` 没有共享元素基础设施（全部叠加页统一 `AnimatedVisibility` 淡入淡出），本页随全站约定改用 `AnimatedContent` 淡入淡出，不额外引入共享元素作用域。**未移植下拉刷新**：桌面无该交互，刷新改为顶栏按钮（`PullToRefreshBox` 亦为 AndroidX 依赖）。**图片缓存不共用**：上游把 Markdown 内嵌图与普通图片合到同一个 `RemoteImageLoader`；桌面 `GitHubMarkdownImageTransformer` 另有 SVG 光栅化 / `<text>` 补绘 / HTML 尺寸标记等渲染器适配，拆分会牵动既有 README 渲染链路，故二者各自持 LRU（同图跨场景重复加载极少）。 |

#### 跳过

| 上游提交/内容 | 原因 |
| --- | --- |
| `561dd10`(#138) README contributors | 上游仓库 README 专属章节；桌面 README 独立维护。 |
| `6265668`(#141) `UpdateChecker` 仓库参数化 / Asset size·digest | 为 Gopeed 内核仓库（`CYQawa/yunx_gopeed_build`）服务；桌面内核取自 Gopeed 官方 Release，改为在 `GopeedKernelProvisioner` 内部自行解析（含 `digest` 校验），不动既有的应用更新检查链路。 |

### 9.6 本次对齐（上游 `6265668` 的 Gopeed 引擎，桌面重做）

上游 `6265668`(#141)「内置 Gopeed 下载引擎」在桌面**以完全不同的形态落地**：上游把 gomobile 编译的
`libgojni.so` 用 `System.load` 装进本进程、配 `apiEnable=false` 走进程内 `rest.Dispatch`；
桌面没有 Windows 内核库（内核仓库只有 4 个 Android ABI 的 AAR，无 exe/dll），改为把 Gopeed 官方
**无界面 web/服务端版 `gopeed.exe` 作为子进程**拉起，走它的本地 HTTP API。

#### 已移植

| 上游提交 | 内容 | 桌面实现与取舍 |
| --- | --- | --- |
| `6265668`(#141) | 内置 Gopeed 下载引擎（双下载器并存 + 内核获取） | **引擎进程** `data/gopeed/GopeedEngine.kt`：内核为 `gopeed.exe`，落 `<dataDir>/gopeed/{bin,storage}`；以 `-A 127.0.0.1 -P <随机空闲端口> -d <storage>` 启动（★ 2026-10-05 修正：早期误传的 `--temp-dir` 在 gopeed 服务端**不存在**（真实开关只有 `-A/-P/-u/-p/-T/-d/-w/-c`，见上游 `cmd/web/flags.go`），会让 gopeed 打印 usage 后立刻退出、引擎永远起不来；启动失败提示也已改为区分「进程已退出（带退出码+日志路径）」与「启动超时」），**不设 `-p` 密码 ⇒ 服务端不启用 Web 鉴权、无需 apiToken**；JDK 17 在 Windows 默认以 `CREATE_NO_WINDOW` 创建子进程（仅显式继承 stdio 时才清除），**不会冒出控制台黑窗**，stdout/stderr 重定向到 `gopeed.log`；启动后轮询 `/api/v1/info` 确认就绪（上限 20s）；pid 落文件，启动时按「pid 存活且可执行路径就是本应用内核」精确清理上次强杀留下的孤儿进程（避免它占着 bolt 存储锁）；注册 JVM 关闭钩子，退出应用时停引擎。**API 客户端**：专用 OkHttp 客户端 **`Proxy.NO_PROXY`**（否则用户配了代理时本地请求会被代理走），信封 `{code,msg,data}`，`code != 0` 取 `msg` 抛错；接口与上游一致：`GET /api/v1/info`、`POST /api/v1/tasks`、`GET /api/v1/tasks/{id}/status`、`PUT .../pause`、`PUT .../continue`、`DELETE /api/v1/tasks/{id}`；建任务体 `{"req":{"url","extra":{"header":{…}},"labels":{"yunxTaskId":…}},"opts":{"path","name","extra":{"connections":N}}}`，**`data` 是任务 ID 字符串**（与查询类接口的对象不同）。**内核获取** `GopeedKernelProvisioner`：从 **Gopeed 官方 Release** 取 `gopeed-web-<tag>-windows-{amd64,arm64}.zip`（按 `os.arch` 映射），下载走 `HttpClients.downloadClient`（跟随代理）、镜像前缀复用「GitHub 下载镜像」设置且失败自动回退直连、按 Release 的 `digest` 校验 sha256（长度非 64 则跳过）后解包导入，结束删除临时包；也支持导入本地 `.zip` / `.exe`。**DB/设置**：`download_task` 增 `engineTaskId`（DDL + ALTER 迁移 + `readTask` 容错 + `insert` 16 占位符）、`DownloadTaskDao` 增 `updateEngineTaskId` / `listSyncableEngineTasks`；设置增 `download_engine`（`builtin` 默认 / `gopeed`）。**下载管理** `DownloadManager`：`enqueue` 末尾统一分流（**平台非 GitHub** + 选了 Gopeed + 内核已导入才走引擎，GitHub 因引擎无法镜像回退仍走内置）；`engineTaskId` 非空的任务其 `start`/`pause`/`remove` 分别转发 `continue`/`pause`/`delete`（绝不落到内置下载器重复下载）；1s 轮询 `/status` 回写进度与 `_stats`（`ready/running/wait` 视为下载中、`done` 完成并写平均速度与清理回调、`error` 置失败、`pause` 置暂停），保活与 Windows 通知沿用既有 `onTaskStarted/onTaskFinished`/`notifyProgress`（引用计数保证恰好配对）；落盘目录沿用设置里的自定义下载目录或系统「下载」目录，路径经 `DownloadPathPolicy.sanitize` 净化（防穿越/非法字符）。**UI** `ui/screens/DownloadEngineScreen.kt`：设置 → 「下载引擎」二级页，展示引擎状态 / 内核体积与核心版本 / 落盘目录，提供「从官方下载内核」「导入本地内核」「重启引擎」「更换内核」「删除内核」与引擎切换（切到 Gopeed 前要求内核已导入）；内核下载进度用**窗口内 `FadeAlertDialog`**（按项目约定，且刻意不可取消）；失败原因用 `SelectionContainer` 原文可复制。 |
| `6265668`(#141) 桌面差异 | Android 专属能力 | **去掉**：AAR 导入与 `System.load`、SAF tree Uri 反解、存储权限三态与「所有文件访问」引导、前台服务保活（桌面由 `WindowsKeepAwake` + 通知中心承担）、`DownloadEngineScreen` 里的「更新内核」入口（需先删后导）。**差异**：引擎不支持的「下载限速 / 失败重试 / GitHub 镜像回退」在桌面仍保留设置项（内置下载器继续使用），已在引擎页与设置项描述里注明「引擎不支持」；引擎内核版本号在官方包名里（`tag`），运行中经 `/api/v1/info` 读取展示。（注：「最大同时下载任务数」自 2026-10-05 起引擎已支持，见 §9.8。） |

#### 验证

- `gradle :desktop:compileKotlin` 通过。
- REST 契约（路由表 / 建任务请求体 / 状态字段 / `data` 为字符串）逐条核对 Gopeed 官方源码
  （`pkg/api/service.go`、`pkg/rest/server.go`、`cmd/web/flags.go`）确认。
- **未做端到端真机验证**：内核下载与引擎进程运行需联网拉取 ~40MB 官方包，尚未实跑；
  首次使用建议按「从官方下载内核」→ 切换引擎 → 下载一个小文件 → 暂停/继续/删除 全流程自测。

### 9.7 本次对齐（上游 `4cfb850`/`8dd441e`/`a4d5a7e`/`040584a`）

#### 已移植

| 上游提交 | 内容 | 桌面实现与取舍 |
| --- | --- | --- |
| `a4d5a7e`(#145) | 新增三个网盘（光鸭云盘 / 蓝奏云优享版 / 蓝奏云） | **网络层**：`GuangYaApi`/`ILanzouApi`/`LanzouApi` + 各自 Constants；蓝奏的 `acw_sc__v2` 人机校验上游本就是纯 Kotlin，桌面逐字节移植到 `LanzouCrypto`（未引入 JS 引擎等新依赖）。**数据层**：三张账号表 + 实体 + DAO + `SecureAccountDaos` AES-GCM 包装（光鸭 4 个凭证字段、优享 appToken/password、蓝奏 cookie）。**认证备份**：三平台纳入导出/导入（**桌面补上游遗漏** —— 上游 #145 未改 `AuthBackupManager`，不补会让「备份→恢复」丢这三个账号）。**解析识别**：`SharePlatform` 增 3 个平台；光鸭 `guangyapan.com/s/{id}`；蓝奏云优享版 `ilanzou.*`；蓝奏云域名族 `lanzou*`/`lan[zs]o[ux]`，**前置边界 `(?:^|[/.])` 不可省**，否则 `www.ilanzou.com` 里的 `lanzou.com` 会被误判成蓝奏云（优享版判断必须先于蓝奏云）。**接入**：`ResolveViewModel`（凭证/仓库/默认目录 `""`/平台名/游客下载/下载请求头/转存分支）、`DriveQuotaViewModel`（光鸭需 accessToken+设备标识、优享需 appToken+uuid；**蓝奏官方无配额接口**）、`DriveScreen` 卡片与路由 8/9/10、`MainScreen` 装配与登录页、关于页/收藏标签/设置页线程项/引导页。**登录**：三平台均为纯 HTTP 账号密码登录（光鸭另含短信验证码），**无需内嵌浏览器**。**UI 形态**：云盘页/账号弹窗/登录页按桌面既有 Pan115 形态重写，不照搬 Android UI。 |
| `4cfb850`(#143) | 公告图集（封面并入图集 / 全屏看图 / 刷新同步详情缓存） | **已移植**：`refresh()` 作废 `detailCache` 并 `reloadCurrentDetail()`（保留旧内容重拉、失败只弹 Snackbar 不闪加载态）、详情页右上角刷新按钮、封面与 `images` 去重（同一张图不再渲染两次）。**未移植**：把封面挪到正文下方组成横向缩略图条 —— 桌面此前已按用户要求实现「正文图集等高横向排布、放不下换行」+ 全窗口看图组件（`ImageViewerOverlay`，滚轮以鼠标为锚点缩放/拖拽/双击复位），上游方案依赖 `SharedTransitionLayout` 共享元素（桌面无此基础设施），改动会退回用户已确认的交互，故保持现状。 |
| `8dd441e`(#144) | 凭证失钥自愈（Android Keystore keyblob 作废） | **已移植**：`GitHubTokenStore.getToken()` 解密失败时清掉解不开的密文（否则每次读取都失败、UI 无法如实显示「未配置」）。**桌面已有**：下载请求头 `loadPersistedHeaders` 失败即清空该条并自愈；各账号 DAO 走 `SecureAccountDaos.decryptOrClear`（失败清该条 + 退回未登录）。**未移植**：Keystore 专项逻辑（`CredentialKeyException{PermanentlyInvalid,Unavailable}`、删坏条目重建密钥、失钥标记弹窗、`shared` 单例）——桌面用本地密钥文件 `FileCredentialCipher`，不存在「设备凭证变更导致 keyblob 永久失效」这一失效形态，失配时退回重登已足够。 |
| `040584a`(#146) | 迅雷/123 登录方式重构（三入口 / 两通道） | **已移植**：① 123 新增 `Pan123DeviceId`（桌面用 `AppContext.miscPrefs` 持久化稳定 UUID，**跨启动不变**，否则被服务端当新设备）与 `passwordLogin`（`user.123pan.cn/api/user/sign_in`，**成功判定 `code==200`**，账号 trim / **密码不 trim**，带 `platform: web`/`app-version: 132`/`loginuuid`/`Origin`/`Referer`）+ `Pan123LoginSupport`（频率/风控/冻结三类文案，**绝不复述服务端原文** —— 响应可能回显账号密码）。② 迅雷短信提为**一等入口**（不再只在密码登录触发风控后出现），两条流程共用 `sendSms`/`loginWithSms`，重发冷却 60s 记在 ViewModel 墙上时钟；进登录页 `resetLoginStep()`。③ 迅雷**网页登录**（`XunleiWebLoginScreen`，内嵌 JCEF 打开 `pan.xunlei.com` + 手动粘贴兜底）：新增 `XunleiWebCredential`（`parse`/`parseRawToken`/`fieldsFrom`/`isValidToken`/`isTrustedUrl` 纯函数 + Cookie 桥脚本）。**桌面关键差异**：上游从 WebView 读 localStorage，而桌面 JCEF 无 `executeJavaScript` 返回值 —— 改为**定期注入 JS 把 localStorage 凭据分片写进 Cookie**（`yunx_xl_cred_*`，规避 4KB 上限），再从 `CefCookieManager` 读回解析；读到的文本必须再过 `GET /drive/v1/about`（`verifyAccessToken`）确认可用才落库。④ **网页 token 与 App token 是两套 OAuth 客户端**（App `Xp6vsxz_7IYVw2BB` + secret + 表单刷新；网页 `Xqp0kJBXWhwaTpB6` 无 secret + JSON 刷新 + `X-Client-Id`），落库记 `authType='webToken'`，`XunleiApi.refreshToken(authType)` 分支，认证备份带上该字段（旧备份缺省=App 通道）。⑤ 上游两个 bug：端口白名单（`Uri.getPort()` 未写端口时返回 -1，`!= 443` 的写法会拦掉所有跳转）桌面无同类代码；剥 `Bearer ` 前缀「先 trim 再匹配」已按正确写法实现。**DB**：`xunlei_account` 增 `authType`（DDL + `ALTER TABLE` 迁移 + 单行表 columns/read/bind 三处同步；**该字段不加密**，它只是判别标记）。**未移植**：123 的网页登录（桌面原本只有账号密码表单；123 的 authorToken 只在 localStorage、不在 Cookie，JCEF 无法低成本读取）。 |
| `4cfb850`(#143) 桌面差异 | 图集交互 | 见上：桌面保留自己的图集与看图方案（用户已确认）。 |
| `040584a`(#146) 桌面差异 | 登录页弹窗 | 登录页是 MainScreen 的**早返回全屏覆盖层**，此时根部 `OverlayDialogHost()` 不参与组合，注册式 `FadeAlertDialog` 不会渲染；网页登录页的「手动粘贴」弹窗因此就地用「零原生窗口」画法（遮罩 + Surface 卡片 + 淡入淡出），仍然不用 material3 `AlertDialog`/`Popup`。 |

#### 验证

- `gradle :desktop:compileKotlin` 通过。
- **未做端到端真机验证**：三个新网盘需真实账号；迅雷网页登录的 JCEF 注入时序、`authType=webToken` 刷新分支、凭据 Cookie 分片拼回均需真机自测。

### 9.8 本次对齐（上游 `a7dbae7`/`43f5073`/`c41ba47`）

本轮覆盖上游 `a7dbae7`(#147) 磁力下载、`43f5073`(#148) 引擎接入最大同时下载数、`c41ba47`(#149) 更新说明清洗装饰。

#### 已移植

| 上游提交 | 内容 | 桌面实现与取舍 |
| --- | --- | --- |
| `a7dbae7`(#147) | 磁力链接（BT）下载交给 Gopeed 引擎 | **解析** `data/download/MagnetLink.kt`：逐字节移植上游的纯字符串实现（识别 `magnet:` / 取 `dn` 显示名 / 取 `xt=urn:btih:` info hash；`+` 按字面处理不被当空格），桌面同样不依赖任何 Android API。**分流与拦截** `DownloadManager.enqueue`：识别磁力后平台落 `DownloadPlatform.MAGNET`，`magnetBlockReason()` 在「内核未导入」/「未切到 Gopeed」时**直接落一条带原因的失败任务**（明确提示去「设置 → 下载 → 下载引擎」，而不是把 `magnet:` 当普通 URL 发给内置分片下载器换来一个无关的协议错误）。**建任务**：磁力传 `opts.name=""` 让引擎自己命名、`connections=0`（BT 不吃 http 分片并发）。**完成回写** `completeEngineTask`：补读 `GET /api/v1/tasks/{id}` 的 `meta.res`（新增 `GopeedEngine.taskDetail()`），用真实种子名修正保存路径（多文件种子落 `<下载目录>/<种子名>/...`，单文件种子落 `<下载目录>/<种子名>`）并回写 `fileName`（新增 `DownloadTaskDao.updateFileName`，DB 实现同步）。**重新下载**：磁力跳过 HTTP Range 探测（探测必然失败会让「重新下载」永远提示直链过期）。**删除本地文件** `DownloadSaver.delete`：目标是非空目录时改走 `deleteRecursively()`（`File.delete()` 对非空目录必返回 false ⇒ 勾了「同时删除本地文件」却什么都没删）。**UI** `DownloadScreen`：① 恢复右下角「添加任务」FAB —— 桌面与上游一样存在「FAB 被误删、`showAddDialog` 永远为 false、空状态却还写着『点击右下角按钮』」的死入口，本次一并修复；② 添加对话框接受磁力、文件名可留空并给引擎提示；③ 磁力在元数据到手前 `totalSize=0`，状态行显示「正在解析磁力」而非 0% 卡死。 |
| `43f5073`(#148) | Gopeed 引擎接入「最大同时下载任务数」（默认 3） | **引擎侧** `GopeedEngine.applyRuntimeConfig(maxRunning, wakeQueued)`：GET 整份 config → 只改顶层 `maxRunning`（以及 `protocolConfig.bt` 的 `seedKeep=false / seedRatio=0 / seedTime=1` 不做种）→ 全量 PUT 回去，其余字段原样保留；Gopeed 启动参数里的 `downloadConfig` **只在空库首次生效**（`Setup()` 读到库里的旧配置会整个替换），所以必须启动后走 REST 写回。**补位唤醒** `wakeQueuedTasks()`：引擎只在「有任务结束」时补一个空位，调大上限时不会放行队列 —— 这里按 `GET /api/v1/tasks` 统计 `running`/`wait`，用空位数逐个 `PUT .../continue` 唤醒排队任务，严格不越过上限、不挤下正在下载的任务；调小时不打断在跑任务。**下载管理** `DownloadManager.syncEngineRuntimeConfig()`：值变化时下发（`lastEngineMaxRunning` 缓存；引擎刚拉起时重置以便重推；引擎没在跑时返回 null 不写缓存，下次重试）；`engineSyncLoop` 每秒按需同步，因此设置页改完 ≤1s 生效。引擎报 `status=wait` 时回写本地「等待中」（`STATUS_PENDING`，不再显示成 0% 的「下载中」）。**默认值** `DEFAULT_MAX_CONCURRENT_DOWNLOADS` 1 → 3（跟随上游；内置下载器与引擎共用同一个值）。**UI** 设置项描述改为「内置下载器与 Gopeed 引擎共用」。 |
| `c41ba47`(#149) | 更新说明过滤掉纯文本里没用的装饰（头图 / QQ 群徽章） | `UpdateChecker.cleanReleaseNotes()`：只去两类 —— ① capsule-render 的 `<img>` 头图；② QQ 群徽章（带外链与裸图片两种形式，**带外链规则必须排在裸图片规则前**，否则会剩一个空链接）。整行只剩装饰时整行删掉、连续空行压成一个、首尾 trim；`fetchLatestRelease` 解析处清洗一次。桌面更新弹窗同样用纯文本显示正文，收益一致；**未移植**上游的日志字数对比（桌面日志无此需求）。 |

#### 跳过 / 桌面差异

| 上游内容 | 处理 |
| --- | --- |
| 上游 `MagnetLink` 里关于 `android.net.Uri` 空壳、JVM 单测的注释 | 桌面无 Android 依赖，注释改为「纯 JVM 便于单测」，实现逐字节一致。 |
| 上游新增的 `MagnetLinkTest` / `UpdateCheckerNotesTest` | **未移植**：桌面测试源集当前配置损坏（`run.ps1` 用 `:desktop:jar` 而非 `build`，`:desktop:test` 会 `ClassNotFoundException`），加了也跑不起来。待测试源集修复后再补。 |
| 上游 `DownloadService.notifyResult`（磁力拦截时发失败通知） | 桌面无前台服务；失败原因写入 `dao.updateError`，由下载卡片「失败原因」展示，与桌面既有引擎失败路径一致。 |
| 上游把「最大同时下载任务数」设置项移出「引擎模式折叠块」 | 桌面设置页本就不折叠该项（无 `engineOn` 包裹），无需改动。 |

#### 验证

- `gradle :desktop:jar` 通过（`run.bat build`）。
- **未做端到端真机验证**：磁力下载需可用的 Gopeed 内核与可用种子/网络（DHT/tracker）；引擎 `maxRunning` 排队与唤醒需多任务实测；均需联网自测。

### 9.9 桌面 Gopeed 引擎修正（2026-10-05，非上游提交）

实测发现引擎「一直没速度、像没启动」，定位到两处**桌面侧**的实现错误（上游 Android 用进程内 `rest.Dispatch` + 自带 AAR，路径与版本都不同，照抄端点会踩坑）：

| 问题 | 现象 | 修正 |
| --- | --- | --- |
| 启动参数 `--temp-dir` 在 gopeed 服务端**不存在** | gopeed 打印 usage 后立刻退出，引擎永远起不来（却被统一报成「启动超时」） | 只传真实存在的开关 `-A 127.0.0.1 -P <port> -d <storage>`（真实开关仅 `-A/-P/-u/-p/-T/-d/-w/-c`，见上游 `cmd/web/flags.go`）；失败提示区分「进程已退出（退出码+日志路径）」与「启动超时」 |
| 进度轮询用 `GET /api/v1/tasks/{id}/status`，而 gopeed **没有这个路由** | 状态查询永远 404 → 进度/速度永远同步不上，界面看起来像引擎没启动 | 改用 `GET /api/v1/tasks/{id}`（Task 对象）：`status`（ready/running/wait/pause/error/done）+ `progress.downloaded` / `progress.speed` + 总大小取 `meta.res.size`（Progress 里没有 total） |

顺带按用户要求改了内核获取与引擎页：

- **内核下载改为 32 路 Range 并行**：先探测 `Range: bytes=0-0` 是否返回 206+Content-Range，支持则按 32 个等长分片并发下载（每片各开一个 fd 按绝对偏移写入，`RandomAccessFile` 不能跨线程共享），不支持则回退单连接流式下载；进度按 300ms 节流。
- **内核下载可取消**：进度弹窗在下载阶段提供「取消」按钮（协程取消 → 下载循环 `ensureActive()` 立即退出 → 删除半截包）；校验/解包阶段不给取消（很快，且中途放弃会留下解包一半的内核）。
- **下载地址可见可复制**：弹窗中展示实际使用的「加速链接」（设置里的 GitHub 镜像前缀，未配置用默认镜像）与 GitHub「直链」，各带一键复制。
- **惰性启动引擎**：只在用户把下载引擎**切换到 Gopeed** 那一刻启动进程；下载/导入内核、进入引擎页都不再提前拉起（此前 `refreshVersion()` 会顺手 `start()`）。引擎未跑时读不到核心版本号属正常。

#### 速度排查与优化（同日追加）

先核实「多线程有没有传过去」：**传过去了**。gopeed HTTP 任务的并发由 `opts.extra.connections` 决定（`pkg/protocol/http/model.go` 的 `OptsExtra.Connections json:"connections"`），桌面按平台线程设置传入（默认 32）；gopeed 自带默认值是 16（`FetcherManager.DefaultConfig()`），任务级值优先。参数没问题，于是从别处找原因：

| 项 | 说明 | 改动 |
| --- | --- | --- |
| **代理没同步给引擎** | 应用的 HTTP 客户端（含内置下载器）都跟随「设置 → 网络代理」，而引擎**默认直连** —— 用户配了代理时引擎被绕过，海外线路可能慢到不可用。这是最可疑的速度差异来源 | `applyRuntimeConfig` 一并下发 `proxy`：直连 `enable=false`；系统代理 `enable=true,system=true`（gopeed 自己读系统代理，原生支持 PAC）；手动 `enable=true,scheme=http,host=host:port`（gopeed 的 Host 直接进 `url.URL.Host`，必须带端口） |
| 全局 HTTP 连接数偏低 | 引擎自带默认 16，仅作兜底（任务级 extra 优先） | `protocolConfig.http.connections` 对齐「下载线程数（通用）」设置 |
| 界面「N 线程」是配置值，不是真实值 | 传了 32 ≠ 真开 32 条连接，无法自查 | 新增 `GopeedEngine.taskConnections()`（`GET /api/v1/tasks/{id}/stats` 的 `connections` 数组长度），同步循环用**真实连接数**更新 `DownloadStats.chunkCount`，取不到才回退配置值 |
| 下发时机 | 原来只在 `maxRunning` 变化时才 PUT，改代理/线程数不会生效 | 缓存键改为「并发上限 + 连接数 + 代理」签名（`GopeedEngine.settingsSignature()`），任一项变化都会在 1s 内重新下发 |

建任务时也会把实际下发的 `connections` 打进日志（`GopeedEngine` 的 `建任务：… connections=…`），便于与界面数字对照。

**实测对比后的结论（2026-10-05，用户反馈：内置 30MB/s、引擎 <7MB/s）**：

- 多线程参数没问题、HTTP/2 也不是原因 —— gopeed 的 transport 显式设了自定义 `DialContext` + `TLSClientConfig`，Go 在这种情况下**默认关闭 HTTP/2**（除非 `ForceAttemptHTTP2=true`），所以 32 个 Range 请求是 32 条独立 TCP 连接。
- 差距来自**两套引擎的分片模型不同**，且 gopeed 这部分**没有对外 API**：
  - 内置下载器是「任务池」模型：`分片数 = 线程数 × 8`（`chunkCountFor`，上限 512），worker 领完一片再领下一片，天然抗慢片拖尾；
  - gopeed 是 1:1 分片映射（`connections` 片对 `connections` 条连接）+ slow-start 渐进放量（按批翻倍，批内全成功才放下一批）+ fast-fail 重试 + work-stealing 阈值（3s / 512KB）。这些行为由内核内部决定，REST API 只暴露 `protocolConfig.http.connections / userAgent / useServerCtime`。
- 因此**引擎侧能做的优化已到顶**：连接数对齐设置、UA 兜底、代理同步。对速度敏感的场景建议继续用内置分片下载器（Gopeed 在 UI 上本就标注「实验性」）。

**切回内置下载器时停用引擎进程**：`switchEngine` 切到 `builtin` 时调用 `stopEngineIfIdle()` → 先查 `GET /api/v1/tasks` 是否还有 ready/running/wait 任务，有则不停（避免打断正在下载的引擎任务）并提示；没有才 `GopeedEngine.stop()`。内核文件保留，随时可再切回来。

### 9.10 内置分片下载器加固（2026-10-05，非上游提交）

用户反馈「下载到 100% 卡死」「会自动重新下载」。定位到根因并修复：

| 问题 | 成因 | 修复 |
| --- | --- | --- |
| **合并失败 → 整文件重下** | `mergeChunksToStream` 原来「**边合并边删分片**」：合并中途失败（磁盘满 / IO 错误 / **用户暂停** / 目标不可写）时已写出的分片已被删除，`dest.abort()` 又删掉目标 ⇒ 重试时数据全没了，只能从 0 重下 | 合并改为**只读不删**，分片在 `dest.commit()` + 大小校验**成功之后**才由 `finishDownload` 整体清空。失败重试只是重新合并一遍，成本极低。代价：合并期间峰值磁盘 ≈ 2 份（分片 + 目标），换「已完成的数据绝不丢」 |
| **界面停在「合并中 100%」像卡死** | 合并失败后内存里的 `mergePercent` 没清，卡片一直显示"合并中" | `finishDownload` 的失败分支补 `_stats.update { it + (id to DownloadStats()) }`（`mergePercent` 默认 -1） |
| **显示 100% 但实际在重下** | 恢复时只做了「DB < 磁盘 才回写」，DB 残留的**更高**旧值（上一次 100%）不会被压下来 | 改为**双向**以磁盘真实长度为准：`if (init != task.downloadedSize)` 就回写 |
| **改线程数就清空重下** | `plan.txt` 签名含 `chunks`（由线程数推导），线程数一变即判定"计划不一致"→`deleteRecursively()` 整目录重下 | 拆分判定：**`total` 变了才清空**（文件真被换掉）；只是线程数变了则**沿用旧计划**（`chunks`/`main` 取 plan.txt 里的值），并发仍按新线程数跑 |
| **合并阶段才发现分片长度不足** | 缺失区间只在 `!allOk`（worker 报失败）时才收集；分片可能"报 OK 但长度不足"（连接提前关闭 / 抢占竞态 / 旧 job 残留），到合并的大小校验才失败，白跑一轮整任务重试 | **一律**以磁盘真实长度收集缺失区间（主池 + 弹性区文件长度 + 弹性区 worker 失败项，按起点去重），补齐后才合并 |
| 合并写盘慢 | 合并是纯本地顺序读+写，原用 64KB 缓冲 | 合并缓冲提到 **1MB**（与 `DownloadSaver.COPY_BUFFER_SIZE` 一致），系统调用次数降 16 倍 |

### 9.11 本次对齐（上游 `c2fec49`/`ccc8622`）

本轮覆盖上游 `c2fec49`(#150)「诊断模式（分模块日志）+ 导出日志改用 uid 过滤」与 `ccc8622`(#151) 版本号提交；桌面版本号按用户要求定为 **1.2.7**（上游是 1.2.9，两边版本线独立）。

#### 已移植

| 上游内容 | 桌面实现与取舍 |
| --- | --- |
| `util/DiagnosticLog`（413 行） | 新增桌面版 `util/DiagnosticLog.kt`：结构、限流（2000 队列 + 300 行/秒）、轮转（单文件 2MB / 每模块 5 个 / 总量 10MB）、六个模块常量与全部 API 均与上游一致。**桌面差异**：目录固定 `<dataDir>/diagnostic_logs`（无 `getExternalFilesDir` 之说，用户可直接在资源管理器打开）；pid 取 `ProcessHandle.current().pid()`；开关读 `SettingsRepository.diagnosticMode`（Preferences）；**所有入口不再需要 `Context` 参数** |
| `DiagnosticNetworkInterceptor`（125 行） | 逐字节移植（OkHttp 拦截器，纯 JVM）：URL 过 `LogRedactor.url` 脱敏；**只有非 200/206 或抛异常才记 body**（各截 1.5KB），请求体用 `TeeRequestBody` 复制一份、对服务端零影响；关闭时首行 return |
| `HttpClients` 挂拦截器 | api / download **两个客户端**都挂上（分片请求也记一行，靠 DiagnosticLog 的行数闸门限流） |
| `SettingsRepository.diagnosticMode` | 新增（键 `diagnostic_mode`，默认关）；只存开关值，运行态由 `DiagnosticLog` 缓存，**改完立刻生效、不必重启** |
| `CredentialCipher` 埋点 | 桌面是 `FileCredentialCipher`（非 Keystore），同样在 encrypt/decrypt 各记一条：**只记用途 + 长度，绝不打明文/密文** |
| `AppDatabase` 库信息埋点 | 上游用 Room `Callback(onCreate/onOpen)`；桌面是 sqlite-jdbc，改为库打开成功时调 `logSchema(conn)`：查 `sqlite_master` 列业务表 + 每张表 `COUNT(*)`（只在诊断模式开启时跑，且只在开库那一次） |
| `DownloadManager` 埋点 | 全部对齐：`task_enqueue` / `task_size_probed` / `chunk_plan` / `task_retry` / `task_stream_fallback` / `merge_start` / `merge_progress`（**按 10% 采样**，合并回调太密）/ `task_complete` / `engine_task_created` / `task_queued`（仅在状态真变化时记）/ `engine_task_failed` / `engine_task_complete`；`dao.insert/complete/delete` 走 `DiagnosticLog.dbOp` 自动记耗时与影响行数；`pause` / `remove` 记 operation 模块 |
| `LogExporter.exportDiagnosticZip` | 桌面版同签名（无 Context）：转发到 `DiagnosticLog.exportZip()`，先 flush 再压，zip 落 `<dataDir>/cache/logs/` |
| 设置页入口 | 与上游一致放在「关于云析」长按 → 开发调试菜单里：**诊断模式开关 + 导出诊断日志（zip）**；另外「导出日志」弹窗在诊断模式开启时也多一个 zip 入口 |
| 冷启动装配 | 上游在 `YunXApp.onCreate`；桌面在 `Main.kt` 的 `AppContext.init()` 之后调 `DiagnosticLog.install()`（用 `runCatching` 包住，绝不影响启动） |

#### 跳过 / 桌面差异

| 上游内容 | 处理 |
| --- | --- |
| `LogExporter` 的 logcat 改动（`--pid=<当前进程>` → **不加任何过滤**） | **不适用**：桌面没有 logcat，导的是 `<dataDir>/files/yunx-desktop.log`（`Log` 门面统一写）。上游那段「别给 logcat 加过滤条件」的教训只对 Android 成立 |
| `DiagnosticWebViewClient`、`WebViewJs` 埋点、8 个登录页的 `WEBVIEW` 模块埋点 | **未移植**：桌面用 JCEF 而非 Android WebView。保留了 `DiagnosticLog.WEBVIEW` 常量与 `DiagnosticLog.webview()` 入口（日志格式与上游兼容），但暂未接 JCEF 的加载/回调事件 |
| 上游 `app/build.gradle.kts` 版本号（1.2.9） | 桌面版本线独立：根目录 `version.txt` 改为 **1.2.7**（jpackage / 便携包 / 安装包 / 应用内版本全部由它派生） |

### 9.12 本次对齐（上游 `94149ed`/`28bfa37`/`0f27e67`/`f729604`/`6384a79`/`bdafe9e`）

本轮覆盖上游 `94149ed`(#152/#153) → `28bfa37`(#154) → `0f27e67`(#155) → `f729604`(#161) → `6384a79`(#163) → `bdafe9e`(#164)，对齐点由 `ccc8622` 推进到 **`bdafe9e`**。

#### 已移植

| 上游内容（提交） | 桌面实现与取舍 |
| --- | --- |
| 迅雷刷新请求补 `Sec-CH-UA` 系客户端提示（#152） | `XunleiWebCredential` 新增 `DESKTOP_SEC_CH_UA` / `DESKTOP_SEC_CH_UA_MOBILE` / `DESKTOP_SEC_CH_UA_PLATFORM` 三个常量，`XunleiApi` 的 webToken 刷新请求同步带上（只改 UA 不同步 client hints 会被判成移动端而冲突） |
| 去掉「也可以直接粘贴 access_token」误导文案（#152） | `XunleiWebLoginScreen` 的手动粘贴说明与输入框 placeholder 同步改掉（`parse()` 仍兼容裸 token，只是不再对外宣传） |
| `verifyAccessToken` 兼容网页 token 的 `kind=drive#about`（#161） | 逐字节移植：该接口在网页（webToken）通道下返回**顶层** `kind/quota`（没有 `data` 包裹），原来只判 `data` 会把合法网页 token 误判成未登录 →「登录成功后点保存提示未检测到登录态」 |
| 中文口令解析（星海，夸克/UC）（#163） | 新增 `StarSeaConstants.kt` + `StarSeaApi.kt`（纯 OkHttp + org.json，直译；`android.util.Log` → 桌面 `Log`）。`ResolveViewModel.startResolve` 的兜底链路改为**先迅雷、迅雷没给出可用链接再问星海**，两个服务都失败时把更靠后那次的原因带出去（不再只丢一句「无法识别分享链接」）；`StarSeaApiTest` 同步移植 |
| 分片级 SHA-256 校验（#163） | `ChunkDownloader`：新增 `ByteArray.toHexDigest()`、`downloadChunk(contentDigest)`、`mergeChunksToStream(onPartDigest)`（写入时增量喂摘要，合并时逐片重算比对，不符即拒绝保存损坏文件，**零额外读盘**）。`DownloadManager`：新增 `taskPartDigests` / `newPartDigest`（把断点续传已有前缀预先喂好），主池 / 弹性区 / 重试三条路径都登记摘要，`finishDownload` 校验不过就 abort |
| 全量 SHA-256（可选开关）（#163） | 合并那一遍用 `DigestOutputStream` 顺带算，写进调试日志的「全量校验」事件；**只在「下载调试」开启时才算**（摘要没有别的去处），设置项 `fullFileSha256` 默认关 |
| 下载调试（按任务事件流 + 调试页）（#163） | 新增 `DownloadDebugLog`（内存态、关闭即清空、单任务 2000 条 / 最多 50 个任务）与 `DownloadDebugScreen`；`DownloadManager` 埋点全覆盖（入队 / 拦截失败 / 断点失效 / 分片规划 / 抢占慢连接 / 抢占结果 / 网络变化 / 开始合并 / 分片校验 / 全量校验 / 校验失败 / 合并失败 / 清理临时文件 / 完成 / 失败 / 暂停 / 删除 / 引擎任务创建），并暴露 `chunkDebugSnapshot` / `resourceDebugSnapshot` / `forcePreemptChunk` 三个拉取式查询。入口：设置 → 关于云析 → 长按 → 开发调试 → **下载调试**（关闭有二次确认，关闭即清空日志）；下载页**右键任务 → 调试信息** |
| Material 3 设计审查修复（#164，适用部分） | ① `SnackbarController` 换成上游的**事件队列 + 宿主优先**实现（修复多宿主并存时第二条提示被吞/重复显示）；② `Type.kt` 对齐 M3 刻度（headlineMedium 22→28、titleLarge 18→22、bodyLarge 15→16、bodyMedium 13→14…）；③ 新增 `ui/theme/ColorContrast.kt` 的 `readableSeedForeground`，主题页色圆上的对勾/Palette 图标由硬编码白色改为**按种子色亮度选黑/白**（浅色种子上原来的白图标几乎看不见）；④ `ResolveScreen` 的 GitHub 徽章由硬编码绿/橙/灰改为主题容器色（深色模式下对比度）；⑤ `DownloadScreen` 两处进度条去掉强制的 `height + clip`，交回 M3 默认尺寸；⑥ `ThemeScreen` 颜色选择器：HEX 输入只接受合法十六进制字符（旧实现 `isLetterOrDigit` 会放进 G-Z，凑满 6 位却解析失败）、`singleLine = true`、弹层加 `verticalScroll`；⑦ `MultiSelectBar` 新增 `busy` 参数（`count == 0 \|\| busy` 时禁用点击并置灰），10 个云盘页传入 `viewModel.isOperating` |

#### 跳过 / 桌面差异

| 上游内容 | 处理 |
| --- | --- |
| 欢迎页「7 大网盘」→「10 大网盘」、认证备份补光鸭/蓝奏云优享版/蓝奏云（#152） | **早已具备**：桌面引导页写的已是「10 大网盘」，`AuthBackupManager` 也已包含三个新平台的导出/导入与 DAO 装配 |
| `suppressRequestedWithHeader()`（8 个登录页关闭 WebView 的 `X-Requested-With` 包名指纹，#152） | **不适用**：那是 Android WebView 的默认行为。桌面用 JCEF，不发这个头；桌面各 API 里显式写的 `X-Requested-With` 是各网盘接口自己的要求，与指纹无关 |
| `applyDesktopClientHints()` / `DESKTOP_RENDER_FIX_JS`（WebView 同步 client hints + 修登录页整页空白/被裁，#161） | **不适用**：桌面 JCEF 就是真正的桌面 Chromium，client hints 天然正确；上游修的是「移动 WebView 里 `html,body{height:100%}` 被算成 0px + `overflow:hidden` 把整页裁掉」，桌面不存在该布局环境 |
| `DownloadService`（Android 前台服务，89 行）、`MainActivity` 实况通知点击落到「下载」Tab（#163） | **不适用**：桌面没有前台服务与 Activity 跳转（窗口常驻）。保活走 `WindowsKeepAwake`，通知走 `WindowsToastNotifier` |
| `YunXApp.onCreate` 里的 `DownloadDebugLog.install` / `AndroidManifest` 改动（#163） | **桌面等价物已实现**：装配改在 `Main.kt`（`AppContext.init()` 之后调 `DownloadDebugLog.install()`，`runCatching` 包住）；无 Manifest 概念 |
| `ui/screens/DownloadDebugScreen` 的单列长滚动布局（#163） | **重新设计为桌面双栏**：顶部一行放「返回 + 标题 + 复制快照 / 导出完整日志」，任务卡压成一行（图标 / 名称 / 状态 / 进度 / 实时速度 / 操作同排），正文左栏＝全局资源 + 线程表、右栏＝实时日志（等宽字体、跟随尾部），两栏各自滚动 —— 桌面横向空间充裕而竖直空间更紧 |
| `ui/components/OperationSheet` + `LocalFileOperationBusy`（#164，ModalBottomSheet 封装） | **未移植**：桌面没有 ModalBottomSheet，各操作面板走 `FadeAlertDialog` / 就地覆盖层。`CloudFileSheets` 的 `ActionItem` 也未加 `busy` 置灰 —— 桌面点菜单项后**立即关闭面板或切到下一步**（下一步自身已按 `operating` 禁用按钮），不存在上游触屏场景下的重复点击窗口 |
| `ui/theme/WindowLayout.kt` 的 `useNavigationRail`（#164） | **不适用**：桌面窗口恒为宽屏，导航即左侧 NavigationRail，没有「宽高判断选导轨还是底栏」这一步 |
| `MultiSelectBar` 的 Tooltip（#164） | **不适用**：桌面的批量条是「图标 + 文字」两行布局（不是上游的纯图标浮动工具条），文字标签本身已经说明了操作含义，不需要 tooltip 兜底 |
| `DownloadScreen` 图标按钮 32dp → 48dp（#164） | **未采用**：48dp 是 Material 的**触屏**最小点击区。桌面用鼠标，48dp 按钮会明显偏大；保留 32dp |
| `ThemeScreen` 自定义颜色弹层补「色相/饱和度/明度」Slider（#164） | **未采用**：桌面弹层本来就有可拖拽的 SatVal 方块 + 色相条，鼠标拖拽比滑条更直接；本次只修了该弹层的 HEX 过滤、单行与滚动 |
| `ThemeScreenAppIconOption` / 主题模式的 `selectable` + `selectableGroup` 语义（#164） | **未移植**：纯无障碍语义改动，桌面端对读屏依赖低，本轮不引入 |
| `28bfa37`(#154) 新增友情链接、`0f27e67`(#155) README 截图替换 | **不移植**：纯 README / 图片资源，桌面仓库的 README 独立维护 |
| 登录页 `liveRegion` 语义、`isError` → `enabled`、密码可见性图标语义（#164） | **未移植**：纯无障碍语义改动（桌面端对读屏依赖低），本轮不引入 |

### 9.13 本次对齐（上游 #178 / #179）

本轮覆盖 `b4d8ad7`(#178) 与 `cad7718`(#179)，对齐点由 `bdafe9e` 推进到 **`cad7718`**。

#### 已移植

| 上游内容（提交） | 桌面实现与取舍 |
| --- | --- |
| 文件夹落盘修复（#178） | 尾斜杠语义（只给目录时名字用 fallback）、引擎任务预建目标目录、完成态路径保留相对子目录、批量下载失败统计；折叠状态 `rememberSaveable` 早已具备 |
| 凭证加固（#179） | `decryptOrNull`（解密失败不再清整张账号表）、密钥只在「确实解不开」时更换、MachineGuid 读不到按暂时不可用、GitHub Token 只失效不删 |
| 多账号基础能力（#179） | 10 张账号表新增 `isPrimary`/`remark`（DDL + 启动时逐表 ALTER 自动迁移，老数据零感知）；DAO/JDBC 改多行实现（同平台单主账号、删主后自动补位）；加密装饰器提供列表接口；`AccountDirectory` + `MultiAccountViewModel`；`resolveAccountSlot` 接入 10 个 Repository（日常重登覆盖主账号行、「添加账号」落新行）；10 个账号弹窗接入 `AccountListSection`（列表 / 设主账号 / 备注 / 退出单个 / 添加账号），网盘页接线完成 |
| C1 多账号下载（#179，实验性） | 批量下载按文件轮换同一网盘的不同账号取链；仓库新增 `allCredentials` / `refreshIfPrimary`，夸克/UC 的 `cookieSink` 改为按本次请求所用 Cookie 回写对应账号行。**覆盖全部 10 个平台**（各平台取链仓库本来就把凭证作为方法参数，token 型平台内部 `cookie.ifBlank { tokenProvider() }` 兜底）。批量前**逐账号**校验整批空间：空间不足的账号跳过、只剩一个可用时自动退回单账号；完成提示显示「N 个账号成功取链 / M 个账号空间不足已跳过 / 已退回单账号」 |
| C2 单文件多源分片（#179，实验性） | 新增 `DownloadSource`；`download_task.altSourcesJson`（加密落库 + 旧库迁移）；分片按 `offset / chunkSize % 源数` 轮流使用不同直链；分片规划 / 并发上限 / 合并逻辑一字未动，断点续传兼容。**覆盖夸克 / UC / 迅雷 / 百度**（这四家的下载请求头差异都只有 Cookie 一项；115 双 Cookie 等需重新评估，未纳入） |
| 设置开关 | 并入桌面现有「实验性功能」页（不新建二级页）：两个开关 + 开启前风险确认；关闭 C1 时强制关闭 C2 |

#### 跳过 / 桌面差异

| 上游内容 | 处理 |
| --- | --- |
| 深链接 / 分享 / 选中文本（`activity-alias`、`singleTask`、`PROCESS_TEXT`，#178） | **不适用**：Android Intent 体系；桌面已有剪贴板识别 |
| `LinkOpenTarget` / `LinkOpenScreen` / 13 项召唤开关（#178） | **不适用**：同上，无 Android 组件可开关 |
| Android WebView 登录态清理（`CookieManager`/`WebStorage`，#179） | **桌面等价物**：`CookieCleaner.clearCookiesForDomains`（JCEF Cookie）——「添加账号」与「退出最后一个账号」时按平台域名清理 |
| `MultiAccountDownloadScreen` 独立二级页 + `ExperimentalBadge`（#179） | **改为并入桌面「实验性功能」页**；未单独抽 `ExperimentalBadge`（桌面实验性条目已有文案标注） |
| C2 的 139 / 123 / 115 / 光鸭 / 蓝奏优享 / 蓝奏覆盖 | **未做**：这些平台的下载请求头不止 Cookie（115 是双 Cookie + Referer，123/光鸭/蓝奏是 CDN 签名头），多源必须按源重建整套头，本次不冒险；开关对它们退回单源 |

### 9.14 本次对齐（上游 1.3.0 十连提交 `0a7c71e` ~ `3e51a4d`）

本轮覆盖 #183 ~ #204（视频解析 + 下载链路 + 播放器 + 细节），对齐点由 `cad7718` 推进到 **`3e51a4d`**。

#### 已移植

| 上游内容（提交） | 桌面实现与取舍 |
| --- | --- |
| 视频解析主体（#183） | `data/network/video/`：`VideoModels` / `VideoHttp` / `VideoParser` / 7 个平台 Parser / `VideoLinkParser`；`VideoParseRepository`、`VideoAccountRepository` |
| 视频账号（#183） | `video_account` 表（含 `isPrimary`/`remark`）+ 列级加密 DAO；账号区复用网盘的多账号卡片（列表 / 设主账号 / 备注 / 退出）；登录走 JCEF `JcefLoginPane`（Cookie 自动回读 + 手动粘贴兜底） |
| 解析结果页（#183） | `VideoResolveScreen` + `VideoResultSection`：作品信息、媒体行、图集网格、批量选择；图片来源桌面自带 `RemoteImageLoader`（走 OkHttp + Skia，不用 Coil） |
| 图集查看器（#183） | `ImageViewerOverlay` 重写为桌面交互：滚轮以鼠标为锚点缩放、任意级别拖拽、双击复位、Esc 关闭 |
| 下载链路（#183） | `DownloadManager` 接入四种形态：单文件 / HLS（`HlsDownloader` 分片→合并）/ Live Photo（`MotionPhotoWriter`，ImageIO 合成）/ 图集批量；`download_task` 新增 `mergeAudioUrl`、`mergeAudioHeadersJson`、`motionUrl` 三列 |
| B站登录 + DASH 高清（#188） | 登录同视频账号体系；DASH 取音视频分轨直链，**`DashMuxer` 用 mp4parser 重写**（Android MediaCodec 无桌面等价物）。M1.5 探针用真实样本验证：双轨齐全、时长差 0.154s |
| 小红书去水印（#189） | 图集地址换算原图 CDN（`sns-img-bd.xhscdn.com/<图片ID>`）；原图 404 时回退平台地址，并以 `notice` 提示降级张数 |
| 动图 Live Photo（#202） | 微博 `videoSrc` / 抖音动图解析 + `MotionPhotoWriter` 合成单张 JPEG（保活元数据丢弃，观感一致） |
| 预览播放器（#183） | **桌面重写**（ExoPlayer → JavaFX）：MP4 走本地回环代理（注入 `Referer`/`UA`/`Cookie` + Range 透传）；HLS 走自研 TS→fMP4 重封装并写本地增长文件实现边下边播（jfxmedia 不播 m3u8、也不吃 HTTP fMP4）。控制条常显：播放/暂停、进度、倍速、静音、全屏 |
| 抖音隐藏 WebView（#183） | `HeadlessWebView` 重写为 JCEF 隐藏浏览器 + `CefMessageRouter`（`cefQuery` 回传替代 `evaluateJavascript` 回调），仅在 SSR 路线失败时兜底 |
| 图标 48→40dp（#185） | 视频平台 Logo 40dp；网盘账号头像 48→40dp 对齐 |
| 版本 1.3.0（#186） | `version.txt` / 构建版本号 1.3.0 |
| 删除弹窗整行可点（#204） | 下载页「删除任务」「删除全部」弹窗的「同时删除本地文件」改为整行 `toggleable`（`Role.Checkbox`），点文字也能切换 |
| 动效降级（跨里程碑约束） | 新增 `MotionPolicy` + `Motion`：`软件渲染 || 用户开关` 时关闭主 Tab 位移（仅 ≤80ms 淡入）、目录切换淡入时长归零、列表 `animateItem` 关闭、引导页无限漂移改静态、返回顶部按钮缩放取消；设置 → 主题与外观 → 动效 新增「减少动画」开关（持久化、即时生效） |

#### 跳过 / 桌面差异

| 上游内容 | 处理 |
| --- | --- |
| 深链接恢复 / 返回键（#204） | **不适用**：Android Intent 与 Activity 返回栈；桌面已有剪贴板识别与 `BackHandler` |
| 设置页容器变换（#184） | **不适用**：上游用 `materialSharedAxis` 的容器级变换，桌面页面切换是 Compose 的 `AnimatedContent`（本轮已接入 §3.7 降级） |
| 支持页卡片几何（#184） | **未改**：桌面支持页布局本就与移动端不同（窗口宽、两列卡片），逐像素对齐没有收益 |
| 清理 mp4parser 残留（#192） | **不适用**：桌面本轮才引入 mp4parser（#188 合轨需要） |
| README 补 PC 说明（#196） | **改为桌面口径**：README / FEATURES 补「视频解析与预览」章节，不照搬移动端说明 |
| 预览用 media3 `MergingMediaSource`（#183） | **改路实现**：JavaFX 不能串两路流，B站 DASH 预览改为解析时另取 `fnval=1&platform=html5` 的单文件 mp4（`VideoMedia.previewUrl`）走回环代理播放；下载仍走高清分轨（详见 §9.15） |
| 视频卡片拆分（#185 后半） | **已由 #183 移植覆盖**：桌面账号区一开始就按「平台卡片 + 账号列表」拆分 |

### 9.15 B 站分轨合轨重写 / DASH 预览 / 动图格式（2026-10-23，非上游提交）

三处实测缺陷的根因与处理（用户反馈：「合轨后时间轴损坏」「B 站无法在线预览」「动图选视频格式后缀仍是 JPG」）：

| 现象 | 根因（实测） | 处理 |
| --- | --- | --- |
| 合轨成品时长只有 0.05 秒、进度条废掉 | `MovieCreator` 读分片 MP4 只认 `trun` 的逐样本时长，而 B 站把时长放在 `tfhd.default_sample_duration`（音频甚至只在 `mvex/trex`），也不读 `tfdt` → 重建的 `stts` 全是 0 | `DashMuxer` 改为**自解析** `moof`/`tfhd`/`tfdt`/`trun`/`trex`，再自写 `moov`+`mdat`（分块约 1 秒、双轨按时间交错、`elst` 原样保留、`stss` 用 `sample_depends_on`）。真实样本（BV1GJ411x7h7，avc1 480P + mp4a）：成品 212.31s / 5306 帧，与 ffmpeg 直转一致，解码无错 |
| B 站结果页没有「预览」 | 预览按钮按 `audioUrl.isBlank()` 判断，分轨条目一律隐藏；且 `.m4s` 分片流 JavaFX 既播不了扩展名也串不了两路 | 解析 DASH 时**另取**一次 `fnval=1&platform=html5` 的单文件 mp4 放进 `VideoMedia.previewUrl`（预览专用，不下载整片），预览优先用它走回环代理；下载仍走高清分轨。DASH 视频轨同时改为**优先 AVC**（codecid=7），合轨产物兼容性最好 |
| Live 图选「视频格式」后仍是 `.jpg` | 只替换了 `url`，`VideoMediaKind` 仍是 `IMAGE` → 文件名按图集规则回退 `.jpg` | 选视频时把 `kind` 改成 `VIDEO`（扩展名由直链后缀决定，没有就 `.mp4`），并清掉「动图」清晰度标签 |

**安装包/预览链路的连带缺陷**（都是开发运行看不出来、只有打包后才暴露的）：

| 现象 | 根因 | 处理 |
| --- | --- | --- |
| 打包后一点预览就提示「该视频暂时无法在线预览」 | jlink 运行时镜像缺 `jdk.httpserver`，`com.sun.net.httpserver.HttpServer` 加载即 `NoClassDefFoundError`，回环代理起不来 | `portable-package.ps1` 与 `build.gradle.kts` 的模块清单补 `jdk.httpserver` |
| 补完模块后播放报 `ERROR_MEDIA_INVALID` | `LoopbackMediaProxy` 用 `resp.body.contentLength()` 取长度；OkHttp 的 HEAD 响应 body 为空（=0），于是对 HEAD 回了 `Content-Length: 0`，GStreamer 据此判定媒体为空 → 无效媒体 | 长度改为优先取上游 `Content-Length` 响应头。实测同一 B 站 durl：修复前 `ERROR_MEDIA_INVALID`，修复后 `READY duration=212394ms` 并正常播放 |
| 打包后播放器直接初始化失败 `jdk/swing/interop/SwingInterOpUtils` | javafx.swing 的 `module-info` 要求 `jdk.unsupported.desktop`，jlink 同样探测不到 | 模块清单补 `jdk.unsupported.desktop` |

### 9.16 本次对齐（上游 `4bd22e4` / #206）

#### 已移植

| 上游内容 | 桌面实现与取舍 |
| --- | --- |
| 迅雷网页账号「验证码无效」 | 根因同上游：网页登录拿到的是**网页 client_id** 的 token，却用 App client_id 签发的 captcha 去请求，服务端一律回 `captcha_invalid`（`client_id not match`）。桌面补上网页端 9 盐 `captcha_sign`（`XunleiWebCredential.computeCaptchaSign`，已知向量单测锁定）、`XunleiApi.initWebCaptcha`、按设备号 + 过期时间的 captcha 缓存、`webCaptchaRefresher` 钩子、`client_id not match` 直接报错不重试 |
| 网页 captcha 只有 300s | 缓存从「单个字符串」改成 `PanCaptcha(token, expiresAtMs)`，过期即先换再请求，不再拿死 token 撞一次 `captcha_invalid` |
| 签名被拒时回退网页会话 | 桌面用 `HeadlessWebView`（JCEF + `cefQuery`）重开 `pan.xunlei.com`，`REFRESH_READY_JS` 等到网页自己刷新出有效 captcha 后再 `READ_SCRIPT` 取凭据；Android 的隐藏 WebView 与 client hints/render-fix 注入不适用，已换成这套 |
| 抢占/重连要点「真」掐断连接 | `ChunkDownloader` 新增 `activeChunkCalls` + `cancelChunkCall`，主池/弹性区/重试/**DASH 分轨**四条路径都逐片登记；看门狗抢占与调试页「重连」都主动 `call.cancel()`（只置标志位对挂住的连接无效） |
| 「疑似卡死」改判据 | 调试页从「速度低于 12KB/s」改为「本轮采样零字节」，并显示「已 Ns 无数据」（`ChunkDebug.idleMs`）；低速但仍在推进的分片不再被误标 |
| 断点续传进度校验 | 按当前计划逐片校验磁盘长度（`part_i` 不超过本片区间、只认弹性起点之后的 `seg_*`），并在取得大小后与恢复时**双向校正**写库，避免旧 job 残留把进度顶到 100% 后卡死 |
| 认证备份纳入视频平台 | `AuthBackupManager` 增 `videoAccountDao`：`video:<PLATFORM>` 前缀与网盘共用 `accounts` 数组，逐行导出 `id/isPrimary/remark`；导入只恢复本版本仍认识的平台 |
| 设置页文案 | 「网盘认证」→「认证备份」，「导出/导入认证信息」并说明含视频平台 |

#### 跳过 / 桌面差异

| 上游内容 | 处理 |
| --- | --- |
| WebView client hints（`UserAgentMetadata`）与登录页 render-fix JS | **不适用**：JCEF 直接跑桌面版页面，本来就是桌面 UA / 桌面布局；`isTrustedUrl` 等域名白名单逻辑桌面已有 |
| 深链接统一处理（VIEW / SEND / PROCESS_TEXT） | **不适用**：Android Intent；桌面已有剪贴板识别 + 拖拽/命令行入口 |
| 认证备份的 Android auto-backup 规则（`AuthBackupManager` 里的 XML 片段） | **不适用**：桌面备份走文件导入导出 |
| 调试页「重连」文案与按钮 | 桌面已有等价入口，仅同步判据与日志文本（「手动抢占」→「手动重连」） |

### 9.17 百度网盘下载与登录（上游 #240）

来源：[CYQawa/YunX#240](https://github.com/CYQawa/YunX/pull/240)，移植基于 PR 的
`4484dffd8540ef5ec9b3ce073f300dff7883ae5b`。

| 上游内容 | macOS 实现 |
| --- | --- |
| 百度登录与下载签名修复 | Pan 域完整 Cookie 筛选、服务端登录验证、按账号隔离缓存、动态 `rand/psign`、用户与设备参数派生；保留桌面个人盘 API 与重试机制 |
| Seed / POP / TPDATA 下载 | 移植 JVM 协议、分片解密和 CRC / MD5 校验；仅建立出站下载连接，先关闭上传能力再查询节点 |
| CDN / P2P 下载模式及慢节点配置 | 中文设置接入现有 macOS 设置页；百度 P2P 模式与其 CDN 回退使用内置下载器 |
| 暂停续传与回退 | 复用桌面全局限速、缓存空间保护与保存目录；保留并发变更前的分片计划和 CDN 回退后的缓存 / 直写进度 |
| 桌面凭证持久化 | P2P 快照使用现有 AES-GCM 凭证机制加密，重启可恢复；日志完整脱敏，确认框和任务菜单不复制快照 |
| 多账号备用源 | HTTP 下载使用各个 P2P 快照的明文 CDN 地址及对应账号请求头，避免把专用快照交给普通 HTTP 下载器 |

多语言资源按需求跳过；Android WebView 和明文网络配置改由 JCEF、JVM 网络实现承担。
增加固定密码学向量、手工协议帧、MockWebServer、SQLite 浏览器 Cookie 及内存任务 DAO 的离线回归测试。









