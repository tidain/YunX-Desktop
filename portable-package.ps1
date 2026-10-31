# YunX-Desktop 免安装打包脚本
# 输出: release\YunX-Desktop\ （整个文件夹可拷贝到任意位置，双击 YunX-Desktop.exe 运行）
# 注意: 本文件必须保持 UTF-8 带 BOM 编码（PS 5.1 会把无 BOM 的 UTF-8 当 ANSI 解析，中文注释变乱码）
# 注意: Gradle 会向 stderr 输出无害警告，不要把原生 stderr 当作致命错误处理
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$ErrorActionPreference = "Continue"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root
Write-Host "Portable package working directory: $root" -ForegroundColor Cyan

# ---- 定位 JDK（缺失时自动下载，打包要求带 jpackage.exe）----
. (Join-Path $root "jdk-setup.ps1")
$JDK = Ensure-Jdk -RequireJPackage
Write-Host "[OK] Using JDK: $JDK" -ForegroundColor Green

# ---- 版本号单一来源：仓库根 version.txt（改版本只动这一个文件；第二行 Beta=1/0 不参与版本号）----
$AppVersion = ((Get-Content (Join-Path $root "version.txt") | Select-Object -First 1) -as [string]).Trim()
if (-not $AppVersion) { throw "version.txt missing or empty (expect a version like 1.2.2)" }
Write-Host "[OK] App version: $AppVersion" -ForegroundColor Green

# ---- 遮罩选位器 EXE：必须先在 Gradle 打包前编出来 ----
# desktop\build.gradle.kts 的 processResources 会把它作为资源塞进 desktop.jar（yunx\ 前缀），
# 运行期由 util/PositionPicker.kt 释放到 %TEMP% 再执行 —— 便携包目录里不再出现这个 exe。
$pickerSrc = Join-Path $root "picker\PositionPicker.cs"
$pickerExeOut = Join-Path $root "build\YunX-PositionPicker.exe"
if (-not (Test-Path $pickerSrc)) { throw "picker source not found: $pickerSrc" }
$csc = "$env:WINDIR\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
if (-not (Test-Path $csc)) { throw "csc.exe not found at $csc" }
& $csc /nologo /target:winexe /platform:x64 /optimize+ `
  "/r:System.Windows.Forms.dll" `
  "/r:System.Drawing.dll" `
  "/out:$pickerExeOut" `
  "$pickerSrc"
if ($LASTEXITCODE -ne 0) { throw "picker compile failed with exit $LASTEXITCODE" }
Write-Host "[OK] Position picker built: $pickerExeOut" -ForegroundColor Green

Write-Host ""
Write-Host "[1/5] Gradle: build jar + native darkmode.dll + export runtime libs..." -ForegroundColor Cyan
$buildOut = Invoke-Gradle ":desktop:jar" ":desktop:compileDarkMode" ":desktop:exportRuntimeLibs" ":desktop:exportJcefNatives" "--no-build-cache" "--console=plain" 2>&1 | Out-String
Write-Host $buildOut
if ($buildOut -match "BUILD FAILED|FAILURE:") { throw "gradle export failed" }

# 暗色桥接 DLL（由 :desktop:compileDarkMode 用 MSVC 编译，需要 cl；缺失则中止）
$darkModeDll = Join-Path $root "desktop\build\native\darkmode.dll"
if (-not (Test-Path $darkModeDll)) { throw "darkmode.dll not found: $darkModeDll" }

Write-Host ""
Write-Host "[2/5] Assembling portable-libs folder..." -ForegroundColor Cyan
$libs = Join-Path $root "portable-libs"
if (Test-Path $libs) { Remove-Item $libs -Recurse -Force }
New-Item -ItemType Directory -Force -Path $libs | Out-Null
Copy-Item (Join-Path $root "desktop\build\libs\desktop.jar") $libs -Force
$exportDir = Join-Path $root "desktop\build\exportLibs"
if (-not (Test-Path $exportDir)) { throw "exportLibs dir not found at $exportDir" }
Get-ChildItem $exportDir -File | ForEach-Object { Copy-Item $_.FullName $libs -Force }
$jarCount = (Get-ChildItem $libs -File).Count
Write-Host "libs assembled: $jarCount jars" -ForegroundColor Green

# ---- [2b/5] 打包期依赖裁剪（只作用于便携包内的 jar；Gradle 编译期依赖保持不变）----
# 目标：功能/外观/路径完全不变，只去掉运行时用不到的内容以缩减发布体积。
# 每项都要求源 jar 存在，缺失即中止，避免产出半裁剪的不完整包。
Write-Host ""
Write-Host "[2b/5] Trimming bundled jars (material-icons-extended / sqlite-jdbc / skiko)..." -ForegroundColor Cyan
Add-Type -AssemblyName System.IO.Compression.FileSystem

# 按“要保留的条目全名集合”重写 jar：条目名与目录结构原样保留，仅丢弃未列入的条目。
function New-TrimmedJar {
    param(
        [string]$SourceJar,
        [string]$TargetJar,
        [System.Collections.Generic.HashSet[string]]$KeepEntryNames
    )
    $src = [System.IO.Compression.ZipFile]::OpenRead($SourceJar)
    $tmp = "$TargetJar.tmp"
    if (Test-Path $tmp) { Remove-Item $tmp -Force }
    $fs = [System.IO.File]::Create($tmp)
    $dst = New-Object System.IO.Compression.ZipArchive($fs, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($entry in $src.Entries) {
            if (-not $KeepEntryNames.Contains($entry.FullName)) { continue }
            $out = $dst.CreateEntry($entry.FullName, [System.IO.Compression.CompressionLevel]::Optimal)
            $is = $entry.Open(); $os = $out.Open()
            $is.CopyTo($os); $os.Close(); $is.Close()
        }
    } finally {
        $dst.Dispose(); $fs.Dispose(); $src.Dispose()
    }
    Move-Item $tmp $TargetJar -Force
}
function Get-JarSizeMb([string]$Path) { return [math]::Round((Get-Item $Path).Length / 1MB, 2) }

# ---- (1) material-icons-extended：只保留实际被引用的图标类 ----
# 扫描便携包内所有 class（含 desktop.jar 与各依赖 jar）的常量池，收集被引用的
# androidx/compose/material/icons/** 类名，再从完整 jar 中只抽取这些 class。
# 编译期仍使用完整 jar（Gradle 依赖未改）；运行时用的是同一批图标类，外观不变。
# 已被 material-icons-core 覆盖的图标（Add/Close/ArrowBack 等）不在此 jar 内，无需处理。
$iconsJar = Get-ChildItem $libs -Filter "material-icons-extended-*.jar" | Select-Object -First 1 -ExpandProperty FullName
if (-not $iconsJar) { throw "material-icons-extended jar not found in $libs" }
$latin1 = [System.Text.Encoding]::GetEncoding(28591)
$iconPrefix = "androidx/compose/material/icons/"
$referencedIcons = New-Object 'System.Collections.Generic.HashSet[string]'
$scanJars = @(Get-ChildItem $libs -Filter *.jar | Where-Object { $_.FullName -ne $iconsJar } | ForEach-Object { $_.FullName })
$scanJars += (Join-Path $root "desktop\build\libs\desktop.jar")
foreach ($jar in $scanJars) {
    if (-not (Test-Path $jar)) { continue }
    $z = [System.IO.Compression.ZipFile]::OpenRead($jar)
    foreach ($e in $z.Entries) {
        if (-not $e.FullName.EndsWith(".class")) { continue }
        $ms = New-Object System.IO.MemoryStream
        $s = $e.Open(); $s.CopyTo($ms); $s.Close()
        $txt = $latin1.GetString($ms.ToArray()); $ms.Dispose()
        $i = 0
        while (($i = $txt.IndexOf($iconPrefix, $i)) -ge 0) {
            $j = $i + $iconPrefix.Length
            while ($j -lt $txt.Length) {
                $c = $txt[$j]
                if (($c -ge 'a' -and $c -le 'z') -or ($c -ge 'A' -and $c -le 'Z') -or
                    ($c -ge '0' -and $c -le '9') -or $c -eq '$' -or $c -eq '_' -or $c -eq '/') { $j++ } else { break }
            }
            [void]$referencedIcons.Add($txt.Substring($i, $j - $i)); $i = $j
        }
    }
    $z.Dispose()
}
$iconBefore = Get-JarSizeMb $iconsJar
$keepIcons = New-Object 'System.Collections.Generic.HashSet[string]'
[void]$keepIcons.Add("META-INF/MANIFEST.MF")
$zi = [System.IO.Compression.ZipFile]::OpenRead($iconsJar)
foreach ($e in $zi.Entries) {
    if ($e.FullName.EndsWith(".class") -and $referencedIcons.Contains($e.FullName.Substring(0, $e.FullName.Length - 6))) {
        [void]$keepIcons.Add($e.FullName)
    }
}
$zi.Dispose()
New-TrimmedJar -SourceJar $iconsJar -TargetJar $iconsJar -KeepEntryNames $keepIcons
Write-Host "  material-icons-extended: $iconBefore MB -> $(Get-JarSizeMb $iconsJar) MB (kept $($keepIcons.Count - 1) icon classes of $($referencedIcons.Count) referenced)" -ForegroundColor Green

# ---- (2) sqlite-jdbc：只保留 Windows x86_64 原生库 ----
# org.sqlite 按 org/sqlite/native/<OS>/<arch>/<lib> 约定加载，目录结构保持不变。
$sqliteJar = Get-ChildItem $libs -Filter "sqlite-jdbc-*.jar" | Select-Object -First 1 -ExpandProperty FullName
if (-not $sqliteJar) { throw "sqlite-jdbc jar not found in $libs" }
$nativePrefix = "org/sqlite/native/"
$keptNativePrefix = "org/sqlite/native/Windows/x86_64/"
$keepSqlite = New-Object 'System.Collections.Generic.HashSet[string]'
$zs = [System.IO.Compression.ZipFile]::OpenRead($sqliteJar)
foreach ($e in $zs.Entries) {
    # 原生库只留 Windows/x86_64，其余平台目录整段丢弃；jar 内其它内容（class/META-INF 等）全部保留
    if ($e.FullName.StartsWith($nativePrefix) -and -not $e.FullName.StartsWith($keptNativePrefix)) { continue }
    [void]$keepSqlite.Add($e.FullName)
}
$zs.Dispose()
$sqliteBefore = Get-JarSizeMb $sqliteJar
New-TrimmedJar -SourceJar $sqliteJar -TargetJar $sqliteJar -KeepEntryNames $keepSqlite
Write-Host "  sqlite-jdbc: $sqliteBefore MB -> $(Get-JarSizeMb $sqliteJar) MB (Windows/x86_64 native only)" -ForegroundColor Green

# ---- (3) skiko 运行时 jar：原生库已解出到 app\，jar 本身运行时无需 ----
# skiko 通过 skiko.library.path / java.library.path（启动器已设为 app\）直接加载
# app\skiko-windows-x64.dll；该 jar 只在两者都缺失的兜底分支里才会被读取（见 skiko Library.findAndLoad）。
# 故把 jar 从 app 载荷中移除不影响运行——原生库仍在 [5/5] 从 exportLibs 解出到 app\。
$skikoRuntimeJar = Get-ChildItem $libs -Filter "skiko-awt-runtime-windows-x64-*.jar" | Select-Object -First 1
if ($skikoRuntimeJar) {
    $skikoJarMb = Get-JarSizeMb $skikoRuntimeJar.FullName
    Remove-Item $skikoRuntimeJar.FullName -Force
    Write-Host "  skiko runtime jar dropped from app payload: $($skikoRuntimeJar.Name) (-$skikoJarMb MB, native still shipped in app\)" -ForegroundColor Green
} else {
    throw "skiko-awt-runtime-windows-x64 jar not found in $libs"
}

# ---- [2c/5] CEF 原生库：jar 内嵌 tar.gz → 发布用 tar.gz（裁剪语言包）----
# CEF 原生库是包里最大的一块（解包后 261MB，官方 jar 内 gzip 后约 120MB）。
# 这里用 JcefNativesPacker 把它重新打成 tar.gz（沿用 gzip，不做 xz/LZMA2 —— 实测 xz
# 对内嵌浏览器不友好），把语言包裁到只剩 zh-CN（应用固定 settings.locale = "zh-CN"）。
# 运行期由 JcefNativesInstaller 首次使用时解开，再让 jcefmaven 跳过它自己的安装步骤。
Write-Host ""
Write-Host "[2c/5] Repacking CEF natives (trim locales, tar.gz)..." -ForegroundColor Cyan
$jcefNativesJar = Get-ChildItem (Join-Path $root "desktop\build\jcefNatives") -Filter "jcef-natives-*.jar" -ErrorAction SilentlyContinue |
    Select-Object -First 1 -ExpandProperty FullName
if (-not $jcefNativesJar) { throw "jcef natives jar not found in desktop\build\jcefNatives" }
$jcefNativesMb = Get-JarSizeMb $jcefNativesJar
$jcefArchive = Join-Path $libs "jcef-natives.tar.gz"
$javaExe = Join-Path $JDK "bin\java.exe"
$packOut = & $javaExe -Xmx1g -cp "$libs\*" com.yunx.app.tools.JcefNativesPacker $jcefNativesJar $jcefArchive 2>&1 | Out-String
Write-Host $packOut.Trim()
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $jcefArchive)) { throw "jcef natives repack failed" }
Write-Host "  jcef natives: $jcefNativesMb MB (jar) -> $([math]::Round((Get-Item $jcefArchive).Length / 1MB, 2)) MB (tar.gz, zh-CN only)" -ForegroundColor Green

Write-Host ""
Write-Host "[3/5] Building JRE runtime image (jlink)..." -ForegroundColor Cyan
$outDir = Join-Path $root "release\YunX-Desktop"
$runtimeTmp = Join-Path $root "release-runtime-tmp"
# jlink 要求输出目录不存在，先清掉
if (Test-Path $runtimeTmp) { Remove-Item $runtimeTmp -Recurse -Force }
$jlinkExe = Join-Path $JDK "bin\jlink.exe"
# --compress=2：对 runtime/lib/modules 做 zip 级别压缩（JDK 17 支持；JDK 21+ 才有的 zip-6 不可用）
& $jlinkExe --module-path (Join-Path $JDK "jmods") `
  --add-modules java.base,java.datatransfer,java.xml,java.prefs,java.desktop,java.logging,jdk.crypto.ec,java.sql,java.naming,jdk.httpserver,jdk.unsupported.desktop `
  --strip-debug --no-header-files --no-man-pages --compress=2 --output $runtimeTmp
if ($LASTEXITCODE -ne 0) { throw "jlink failed with exit $LASTEXITCODE" }
# jlink 会带两样运行时用不到的东西：
# - lib\jvm.lib：只在编译期链接 JVM 用，运行期加载的是 bin\server\jvm.dll；
# - bin\splashscreen.dll：jpackage 默认启动器的启动闪屏用，本项目用自研启动器自绘闪屏。
foreach ($unused in @("lib\jvm.lib", "bin\splashscreen.dll")) {
    $p = Join-Path $runtimeTmp $unused
    if (Test-Path $p) {
        $mb = [math]::Round((Get-Item $p).Length / 1MB, 2)
        Remove-Item $p -Force
        Write-Host "  dropped runtime\$unused (-$mb MB)" -ForegroundColor Green
    }
}

Write-Host ""
Write-Host "[4/5] Creating app-image with jpackage (YunX-Desktop.exe)..." -ForegroundColor Cyan
# 打包前先杀运行中的实例：YunX-Desktop.exe / java 子进程 / JCEF 助手会锁住输出目录下的 jar 和运行时文件
Get-Process YunX-Desktop -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
Get-Process | Where-Object { $_.Path -like "*\release\YunX-Desktop\*" } | Stop-Process -Force -ErrorAction SilentlyContinue
Get-Process jcef_helper -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
# 资源管理器/杀软可能短暂占用句柄，删除旧输出目录需重试
$removed = $false
for ($i = 1; $i -le 6; $i++) {
    if (-not (Test-Path $outDir)) { $removed = $true; break }
    try {
        Remove-Item $outDir -Recurse -Force -ErrorAction Stop
        $removed = $true
        break
    } catch {
        Write-Host "[i] Output dir locked, retry $i/6 in 2s (close YunX-Desktop / Explorer windows)..." -ForegroundColor Yellow
        Start-Sleep -Seconds 2
    }
}
if (-not $removed) { throw "Cannot remove $outDir - close running YunX-Desktop and Explorer windows, then retry." }
# 参数必须内联传递：@argfile 会在空格处把带空格的参数值拆成多个选项
$jpackageExe = Join-Path $JDK "bin\jpackage.exe"
& $jpackageExe `
  --type app-image `
  --dest release `
  --name YunX-Desktop `
  --app-version $AppVersion `
  --vendor "YunX-Desktop" `
  --description "YunX-Desktop - netdisk share-link parser and high-speed downloader" `
  --input portable-libs `
  --main-jar desktop.jar `
  --main-class com.yunx.app.MainKt `
  --icon YunX-Desktop.ico `
  --runtime-image release-runtime-tmp `
  --java-options -Xmx4g `
  --java-options "-Dfile.encoding=UTF-8"
if ($LASTEXITCODE -ne 0) { throw "jpackage failed with exit $LASTEXITCODE" }
Remove-Item $runtimeTmp -Recurse -Force -ErrorAction SilentlyContinue

Write-Host ""
Write-Host "[4b/5] Building custom launcher (in-process JVM / rename-proof / CJK path-proof)..." -ForegroundColor Cyan
# 换用自研启动器：jpackage 的启动器要求 "exe 名 == app\同名.cfg"，改 exe 名就失效；
# 自研启动器固定读 app\YunX-Desktop.cfg，且用宽字符 API，中文路径/重命名都不会坏。
# 启动器用 JNI（P/Invoke jvm.dll）在本进程内创建 JVM → 任务管理器只有一个顶层进程
# 「云析 YunX-Desktop」，不再出现 runtime\bin\java.exe 子进程。
$csc = "$env:WINDIR\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
$launcherSrc = Join-Path $root "launcher\Launcher.cs"
$launcherExe = Join-Path $outDir "YunX-Desktop.exe"
$launcherIco = Join-Path $root "YunX-Desktop.ico"
$launcherTmp = Join-Path $root "build\YunX-Desktop-launcher.exe"
if (-not (Test-Path $csc)) { throw "csc.exe not found at $csc" }
# 生成程序集版本属性：csc 据此写入 Win32 版本资源。缺了它资源管理器/任务管理器里
# 的 YunX-Desktop.exe 会显示 0.0.0.0、产品名为空
$launcherInfo = Join-Path $root "build\LauncherVersion.cs"
$launcherInfoDir = Split-Path -Parent $launcherInfo
if (-not (Test-Path $launcherInfoDir)) { New-Item -ItemType Directory -Force -Path $launcherInfoDir | Out-Null }
@"
using System.Reflection;

[assembly: AssemblyTitle("云析 YunX-Desktop")]
[assembly: AssemblyProduct("云析 YunX-Desktop")]
[assembly: AssemblyCompany("tidain")]
[assembly: AssemblyCopyright("Copyright (C) 2026 tidain")]
[assembly: AssemblyDescription("网盘分享链接解析与高速下载器")]
[assembly: AssemblyVersion("$AppVersion")]
[assembly: AssemblyFileVersion("$AppVersion")]
[assembly: AssemblyInformationalVersion("$AppVersion")]
"@ | Set-Content -Path $launcherInfo -Encoding UTF8
# 先编译到 build\ 再覆盖：直接写正在使用的 release exe 可能被杀软/文件锁拒绝
# /platform:x64 必须：JNI 结构体按 x64 布局手工排布（进程内 JVM 仅支持 64 位运行时）
& $csc /nologo /target:winexe /platform:x64 /optimize+ `
  "/win32icon:$launcherIco" `
  "/r:System.Windows.Forms.dll" `
  "/r:System.Drawing.dll" `
  "/out:$launcherTmp" `
  "$launcherSrc" `
  "$launcherInfo"
if ($LASTEXITCODE -ne 0) { throw "launcher compile failed with exit $LASTEXITCODE" }
Copy-Item $launcherTmp $launcherExe -Force
if ($LASTEXITCODE -ne 0) { throw "launcher compile failed with exit $LASTEXITCODE" }
Write-Host "Custom launcher installed: $launcherExe" -ForegroundColor Green

Write-Host ""
Write-Host "[5/5] Extracting Skiko native resources (DLL/icudtl.dat) + darkmode.dll..." -ForegroundColor Cyan
Add-Type -AssemblyName System.IO.Compression.FileSystem
# skiko 运行时 jar 已在 [2b] 从 app 载荷移除，但 exportLibs 仍保留原件：
# 从这里解出 dll/icudtl.dat 放进 app\（原生库仍随包分发，只是不再带 .jar）。
$skikoJar = Get-ChildItem $exportDir -Filter "skiko-awt-runtime-windows-x64*.jar" | Select-Object -First 1 -ExpandProperty FullName
if (-not $skikoJar -or -not (Test-Path $skikoJar)) {
    throw "skiko jar not found in $exportDir"
}
$zip = [System.IO.Compression.ZipFile]::OpenRead($skikoJar)
$appDir = Join-Path $outDir "app"
foreach ($entry in $zip.Entries) {
    if ($entry.FullName -match "\.class$" -or $entry.FullName -match "/$") { continue }
    $leaf = Split-Path $entry.FullName -Leaf
    if ($leaf -eq "") { continue }
    $target = Join-Path $appDir $leaf
    $s = $entry.Open(); $fs = [IO.File]::Create($target); $s.CopyTo($fs); $fs.Close(); $s.Close()
}
$zip.Dispose()
# 暗色桥接 DLL 与 skiko 原生库同放 app\：启动器已把 app\ 加进 java.library.path，
# 应用的 System.loadLibrary("darkmode") 才能找到它
Copy-Item $darkModeDll $appDir -Force
$nativeCount = (Get-ChildItem $appDir -File -Filter "*.dll").Count
Write-Host "Copied native DLLs: $nativeCount" -ForegroundColor Green

# ---- [5b/5] 清理调试残留 + 体积汇总 ----
Write-Host ""
Write-Host "[5b/5] Cleaning debug residue + size report..." -ForegroundColor Cyan
$debugResidue = @(Get-ChildItem $outDir -Recurse -File -ErrorAction SilentlyContinue |
    Where-Object { $_.Extension -in ".pdb", ".map", ".dsym" })
if ($debugResidue.Count -gt 0) {
    foreach ($f in $debugResidue) {
        Write-Host "  removing debug residue: $($f.FullName)" -ForegroundColor Yellow
        Remove-Item $f.FullName -Force
    }
} else {
    Write-Host "  no *.pdb/*.map/*.dSYM residue found" -ForegroundColor Green
}
# 旧版本曾把遮罩 EXE 放在包根目录；现已改为打进 desktop.jar（运行期释放到 %TEMP%），升级时清掉残留
$legacyPicker = Join-Path $outDir "YunX-PositionPicker.exe"
if (Test-Path $legacyPicker) {
    Write-Host "  removing legacy picker exe: $legacyPicker" -ForegroundColor Yellow
    Remove-Item $legacyPicker -Force
}
$appSize = [math]::Round(((Get-ChildItem $appDir -File | Measure-Object -Sum Length).Sum) / 1MB, 1)
$runtimeSize = [math]::Round(((Get-ChildItem (Join-Path $outDir "runtime") -Recurse -File | Measure-Object -Sum Length).Sum) / 1MB, 1)
$totalSize = [math]::Round(((Get-ChildItem $outDir -Recurse -File | Measure-Object -Sum Length).Sum) / 1MB, 1)
Write-Host "  app\: $appSize MB   runtime\: $runtimeSize MB   total: $totalSize MB" -ForegroundColor Green

Write-Host ""
Write-Host "Portable package ready: $outDir" -ForegroundColor Green
Write-Host "Copy the whole folder anywhere and run YunX-Desktop.exe (no install needed)."
