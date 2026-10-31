# 构建与打包

> 概览见 [README](../README.md)

## 环境要求

要求：Windows 10/11 x64。

| 依赖 | 说明 |
| --- | --- |
| JDK 17 | 缺失时 `jdk-setup.ps1` 自动下载便携版 |
| Gradle | 由 wrapper 自动拉取，无需手动安装 |
| **C++ 编译器** | 托盘菜单配色桥接层 `native/darkmode.cpp` 需编译为 `darkmode.dll`：优先 `cl.exe`（Visual Studio 2019/2022，勾选「使用 C++ 的桌面开发」工作负载），否则回退 `g++`（MinGW） |
| Inno Setup 6 | 仅打安装包需要；缺失时脚本自动下载并静默安装 |

**缺 C++ 编译器时**：`run` / `package` / `installer` 会在编译 `darkmode.dll` 这一步失败；
`run.ps1 build` 只编译 Kotlin，不受影响。

## 命令

```powershell
git clone https://github.com/tidain/YunX-Desktop.git
cd YunX-Desktop

.\run.ps1 run        # 编译并启动（开发，含 darkmode.dll）
.\run.ps1 build      # 仅编译 Kotlin（不需要 C++ 编译器）
.\run.ps1 package    # 免安装便携版 → release\YunX-Desktop\
.\run.ps1 installer  # 单文件安装程序 → release\YunX-Desktop-setup-*.exe
```

也可双击 `run.bat`（走同一入口）。中文向导语言包随仓库分发（`installer\ChineseSimplified.isl`）。

## 版本号

根目录 `version.txt` 是**唯一版本来源**：jpackage 版本、便携包 / 安装包的文件名与程序版本、
应用内「关于页 / 更新检测」全部由它派生。**发版只需改这一个文件。**

```
1.3.1            ← 第一行：版本号（必填）
Beta=0           ← 第二行：Beta=1 预览版 / Beta=0 正式版（缺省 = 正式版）
```

第二行写 `Beta=1` 时，构建出的 `APP_IS_PREVIEW = true`，关于页版本号旁会显示**黄色 Beta 标记**。
打包脚本只取第一行作为版本号，第二行不参与文件名 / jpackage 版本。

启动器 exe 的版本资源（公司 `tidain`、版权 `Copyright (C) 2026 tidain`）也在打包时由该版本号生成。

### 发布说明里的强制更新字段

`Releases.md`（GitHub Release 正文）开头可以写两个字段，客户端据此决定是否**强制更新**：

```
> 强制更新：1
> 最低强制更新版本：v1.3.0
```

- 只有 `强制更新 = 1` **且** 当前版本 ≥ 最低强制更新版本 **且** 当前版本 < 新版本 时才强制；
  例如新版本 v1.3.1、最低强制 v1.3.0 时，1.2.9 不会被拦，1.3.0 会被拦。
- 两个字段缺任意一个 / 为空 / 解析不出来 → 一律**不强制**（老用户不会被锁死）。
- 强制更新时更新弹窗不可关闭、没有「稍后 / 不再提示该版本」，且不受用户之前「不再提示该版本」的影响。

## 打包产物

| 产物 | 位置 | 说明 |
| --- | --- | --- |
| 便携版 | `release\YunX-Desktop\` | 整个文件夹拷走即用，双击 `YunX-Desktop.exe` 运行，无需安装 |
| 安装程序 | `release\YunX-Desktop-setup-<版本>.exe` | 每用户安装到 `%LOCALAPPDATA%\Programs\YunX-Desktop`，无需管理员权限；创建开始菜单 / 桌面快捷方式「云析」，可卸载 |

打包脚本会自动结束正在运行的程序实例，并在输出目录被占用时重试删除。

便携包约 230MB（其中运行期 JRE 约 40MB、CEF 原生库压缩包约 110MB），体积主要来自 Chromium。

## 发布包体积裁剪

打包脚本只改**发布包里的文件**，不改变编译期依赖，也不改变任何功能：

| 项目 | 处理 | 效果 |
| --- | --- | --- |
| `material-icons-extended` | 扫描所有 class 的常量池，只保留实际被引用的图标类 | 0.2 MB（完整包 36 MB） |
| `sqlite-jdbc` | 只保留 `org/sqlite/native/Windows/x86_64` 原生库 | 2.37 → 0.75 MB |
| `skiko-awt-runtime-windows-x64` | 从 `app\` 载荷里去掉 jar（原生库仍解到 `app\` 供加载） | −10.3 MB |
| **CEF 原生库** | jar 内嵌 `tar.gz` 重打成 `tar.gz`（gzip），语言包只留 `zh-CN` | 131 → 110 MB |
| `runtime\lib\jvm.lib` / `runtime\bin\splashscreen.dll` | 运行时用不到（链接期文件 / jpackage 默认启动器闪屏），jlink 后删除 | −1.2 MB |

### CEF 原生库重打（tar.gz）

发布包**不再**随带 `jcef-natives-*.jar`（解包后 261MB，gzip 后 120MB），而是：

1. 打包时 `:desktop:exportJcefNatives` 单独导出原生 jar（`exportRuntimeLibs` 会排除它），
   由 `JcefNativesPacker` 重打成 `app\jcef-natives.tar.gz`（沿用 gzip，不做 xz/LZMA2 —— 实测 xz 对内嵌浏览器不友好）；
2. 运行期首次需要内嵌浏览器时，`JcefNativesInstaller` 把它解到 `<缓存>\jcef-bundle`，
   写 `install.lock` 与就绪标记（记录压缩包大小 / 时间，换包自动重解）；
3. 随后 `CefAppBuilder.setSkipInstallation(true)`，jcefmaven 不再去找 classpath 里的原生 jar。

开发运行（`run.ps1 run`）不吃这套：原生 jar 仍在 classpath 上，也没有这个压缩包，
`ensureInstalled` 返回 false，行为与改动前一致。

> 升级 jcef 版本只需改 `desktop/build.gradle.kts` 里的依赖坐标，打包脚本与压缩器无需改动。

## 体积与本地裁剪包

`desktop/libs/` 下有三个**随仓库分发**的 jar，都是为了压缩体积或规避仓库坐标问题，改动前先读各自的说明：

| 文件 | 作用 | 重新生成 |
| --- | --- | --- |
| `material-color-utilities-1.0.0.jar` | 主题种子色（该库无公开 Maven 坐标，从 material 1.14.0 AAR 解包 `classes.jar`） | 升级 material 依赖时需重新解包 |
| `sqlite-jdbc-3.47.1.0-win.jar` | 只保留 `org/sqlite/native/Windows` 的 SQLite JDBC（官方包 13.6 MB → 2.4 MB） | 升级 sqlite-jdbc 时：解压官方 jar，删掉 `org/sqlite/native/{Mac,Linux,Linux-Musl,FreeBSD,Linux-Android}` 后重新打包 |
| `material-icons-extended-trimmed-1.7.3.jar` | 只保留项目实际引用的图标（官方包 36 MB → 219 KB） | 见下 |

### 图标裁剪包

项目**不再**依赖 `compose.materialIconsExtended`（完整包 5000+ 图标、36 MB）。裁剪包只保留源码里
`Icons.Outlined/Filled/Rounded/AutoMirrored.*` 实际引用到的图标类（约 92 个）。

新增图标时：**先在代码里直接写** `Icons.Outlined.Xxx`，如果编译报 `Unresolved reference`，说明裁剪包里没有，
按下面步骤重新生成（PowerShell，JDK 17）：

```powershell
# 1) 从源码收集引用到的图标类名
$rx = [regex]'Icons\.(AutoMirrored\.)?(Outlined|Filled|Rounded|TwoTone|Default)\.([A-Za-z0-9_]+)'
$need = New-Object 'System.Collections.Generic.HashSet[string]'
Get-ChildItem desktop\src\main\kotlin -Recurse -Filter *.kt | ForEach-Object {
  foreach ($m in $rx.Matches((Get-Content $_.FullName -Raw))) {
    $auto = if ($m.Groups[1].Value) { "automirrored/" } else { "" }
    $style = $m.Groups[2].Value.ToLower(); if ($style -eq "default") { $style = "filled" }
    $null = $need.Add("androidx/compose/material/icons/$auto$style/$($m.Groups[3].Value)Kt.class")
  }
}
# 2) 从 Gradle 缓存里的官方 jar 中挑出这些 class（外加 META-INF/MANIFEST.MF 与 *.kotlin_module）
#    写成 desktop\libs\material-icons-extended-trimmed-<版本>.jar，并同步 build.gradle.kts 的文件名
```

> 说明：`material-icons-core`（几百 KB）仍由 `compose.material3` 传递引入，核心图标（Add / Close / Search …）
> 的 Filled 版本来自它，不在裁剪包范围内。

## 目录结构

| 目录 | 说明 |
| --- | --- |
| `desktop/` | 应用本体（Kotlin + Compose Multiplatform） |
| `desktop/native/` | 原生桥接层源码（`darkmode.cpp` → `darkmode.dll`） |
| `launcher/` | 自研启动器（C# 编译为无控制台窗口的 exe；固定读取 `app\YunX-Desktop.cfg`，改 exe 名或放在中文路径下都不会失效，同时负责启动闪屏与主窗口交叉淡入） |
| `installer/` | Inno Setup 脚本与中文语言包 |
| `docs/` | 文档（见 [README](../README.md#文档)） |
